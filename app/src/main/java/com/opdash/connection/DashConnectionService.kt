package com.opdash.connection

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.*
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.opdash.logging.Logger
import com.opdash.model.ConnectionState
import com.opdash.protocol.K1GPacketBuilder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.*

/**
 * Foreground service that maintains a persistent connection to the
 * Royal Enfield Tripper Dash over Wi-Fi. Handles:
 * - Wi-Fi network binding via WifiNetworkSpecifier
 * - UDP socket lifecycle on ports 2000 (send) / 2002 (receive)
 * - Periodic 1-second heartbeat telemetry packets
 * - Music metadata forwarding
 * - Incoming command parsing from the dash
 */
@RequiresApi(Build.VERSION_CODES.Q)
class DashConnectionService : Service() {

    companion object {
        private const val CHANNEL_ID = "opdash_service_channel"
        private const val NOTIFICATION_ID = 1001
        private const val DASH_BROADCAST_IP = "192.168.4.255"
        private const val SEND_PORT = 2000
        private const val RECV_PORT = 2002
        private const val HEARTBEAT_INTERVAL_MS = 1000L

        const val EXTRA_SSID = "extra_ssid"
        const val EXTRA_PASSWORD = "extra_password"

        // Observable state for the UI layer
        var connectionState by mutableStateOf(ConnectionState.IDLE)
            private set
        var statusMessage by mutableStateOf("Idle")
            private set
        var packetsSent by mutableIntStateOf(0)
            private set
        var packetsReceived by mutableIntStateOf(0)
            private set
        var lastReceivedHex by mutableStateOf("")
            private set

        // Music metadata currently being sent to dash
        var currentTrackTitle by mutableStateOf("")
        var currentTrackArtist by mutableStateOf("")
        var currentTrackAlbum by mutableStateOf("")
        var isMusicPlaying by mutableStateOf(false)

        fun isRunning(): Boolean =
            connectionState == ConnectionState.CONNECTED ||
            connectionState == ConnectionState.CONNECTING_WIFI ||
            connectionState == ConnectionState.CONNECTED_WIFI ||
            connectionState == ConnectionState.STARTING_UDP
    }

    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null
    private var sendSocket: DatagramSocket? = null
    private var recvSocket: DatagramSocket? = null
    private var heartbeatTimer: Timer? = null
    private var receiverThread: Thread? = null
    private var sequenceCounter: Int = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ssid = intent?.getStringExtra(EXTRA_SSID) ?: ""
        val password = intent?.getStringExtra(EXTRA_PASSWORD) ?: ""

        if (ssid.isBlank() || password.isBlank()) {
            Logger.d("Service started without SSID/Password. Stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            // Start as foreground immediately
            val notification = buildNotification("Connecting to $ssid...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Logger.d("Failed to start foreground service: ${e.message}")
            connectionState = ConnectionState.ERROR
            statusMessage = "Missing Permissions"
            stopSelf()
            return START_NOT_STICKY
        }

        // Acquire a partial wake lock to keep CPU alive for UDP heartbeat
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OPDash::DashHeartbeat"
        ).apply { acquire(10 * 60 * 1000L) } // 10 min max, renewed per heartbeat cycle

        Logger.d("Service started. Connecting to SSID: $ssid")
        connectToWifi(ssid, password)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        connectionState = ConnectionState.IDLE
        statusMessage = "Service stopped"
        Logger.d("Service destroyed.")
    }

    // ──────────────────────── Wi-Fi Connection ────────────────────────

    private fun connectToWifi(ssid: String, password: String) {
        connectionState = ConnectionState.CONNECTING_WIFI
        statusMessage = "Connecting to $ssid..."

        try {
            // WPA2/WPA3 passphrases must be between 8 and 63 characters.
            if (password.length < 8 || password.length > 63) {
                throw IllegalArgumentException("Passphrase must be between 8 and 63 characters.")
            }

            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()

            val handler = android.os.Handler(android.os.Looper.getMainLooper())

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Logger.d("Wi-Fi network available. Binding process.")
                    boundNetwork = network
                    connectivityManager.bindProcessToNetwork(network)
                    connectionState = ConnectionState.CONNECTED_WIFI
                    statusMessage = "Wi-Fi connected. Starting UDP..."

                    updateNotification("Connected to $ssid. Streaming...")
                    startUdpCommunication(network)
                }

                override fun onUnavailable() {
                    Logger.d("Wi-Fi network unavailable.")
                    connectionState = ConnectionState.ERROR
                    statusMessage = "Wi-Fi unavailable"
                    handler.post { stopSelf() }
                }

                override fun onLost(network: Network) {
                    Logger.d("Wi-Fi network lost.")
                    connectionState = ConnectionState.ERROR
                    statusMessage = "Wi-Fi connection lost"
                    handler.post { stopSelf() }
                }
            }

            connectivityManager.requestNetwork(request, networkCallback!!)
        } catch (e: Exception) {
            Logger.d("Wi-Fi setup failed: ${e.message}")
            connectionState = ConnectionState.ERROR
            statusMessage = "Connection failed: ${e.message}"
            stopSelf()
        }
    }

    // ──────────────────────── UDP Communication ────────────────────────

    private fun startUdpCommunication(network: Network) {
        connectionState = ConnectionState.STARTING_UDP
        statusMessage = "Initializing UDP sockets..."

        Thread {
            try {
                // Create and bind send socket to port 2000
                sendSocket = DatagramSocket(SEND_PORT).apply {
                    soTimeout = 1000
                    broadcast = true
                }
                network.bindSocket(sendSocket!!)
                Logger.d("Send socket bound to port $SEND_PORT")

                // Create and bind receive socket to port 2002
                recvSocket = DatagramSocket(RECV_PORT)
                network.bindSocket(recvSocket!!)
                Logger.d("Receive socket bound to port $RECV_PORT")

                connectionState = ConnectionState.CONNECTED
                statusMessage = "Connected & streaming"
                Logger.d("UDP sockets ready. Starting heartbeat & receiver.")

                startHeartbeat()
                startReceiver()

            } catch (e: Exception) {
                Logger.d("UDP init failed: ${e.message}")
                connectionState = ConnectionState.ERROR
                statusMessage = "UDP error: ${e.message}"
            }
        }.start()
    }

    // ──────────────────────── Heartbeat Timer ────────────────────────

    private fun startHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = Timer("DashHeartbeat", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    sendHeartbeat()
                }
            }, 0L, HEARTBEAT_INTERVAL_MS)
        }
    }

    private fun sendHeartbeat() {
        try {
            val telemetryPacket = K1GPacketBuilder.buildTelemetryPacket(
                cellSignalStrength = getCellSignal(),
                batteryLevel = getBatteryLevel(),
                isCharging = isCharging(),
                isGpsActive = true,
                volume = 5,
                weatherTemp = 30
            )

            sendPacket(telemetryPacket)

            // If music is playing, send metadata every heartbeat
            if (isMusicPlaying && currentTrackTitle.isNotEmpty()) {
                val musicActivePacket = K1GPacketBuilder.buildMusicActivePacket(true)
                sendPacket(musicActivePacket)

                val metadataPacket = K1GPacketBuilder.buildMusicMetadataPacket(
                    title = currentTrackTitle,
                    artist = currentTrackArtist,
                    album = currentTrackAlbum
                )
                sendPacket(metadataPacket)

                val playStatePacket = K1GPacketBuilder.buildMusicPlayStatePacket(true)
                sendPacket(playStatePacket)
            } else {
                val musicInactivePacket = K1GPacketBuilder.buildMusicActivePacket(false)
                sendPacket(musicInactivePacket)
            }

        } catch (e: Exception) {
            Logger.d("Heartbeat error: ${e.message}")
        }
    }

    // ──────────────────────── Packet Send / Receive ────────────────────────

    private fun sendPacket(packetData: ByteArray) {
        try {
            // Inject sequence counter at byte 16 (only if packet is long enough)
            val data = packetData.copyOf()
            if (data.size > 16) {
                data[16] = (sequenceCounter and 0xFF).toByte()
            }
            sequenceCounter = (sequenceCounter + 1) % 256

            val broadcastAddress = InetAddress.getByName(DASH_BROADCAST_IP)
            val dgPacket = DatagramPacket(data, data.size, broadcastAddress, SEND_PORT)
            sendSocket?.send(dgPacket)
            packetsSent++
        } catch (e: Exception) {
            // Don't spam the log on every packet failure
            if (packetsSent % 30 == 0) {
                Logger.d("Send error (seq=$sequenceCounter): ${e.message}")
            }
        }
    }

    private fun startReceiver() {
        receiverThread = Thread {
            val buffer = ByteArray(2000)
            while (connectionState == ConnectionState.CONNECTED) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    recvSocket?.receive(packet)

                    val received = packet.data.copyOf(packet.length)
                    packetsReceived++

                    // Convert to hex for logging/display
                    val hex = received.joinToString("") { "%02X".format(it) }
                    lastReceivedHex = hex

                    if (packetsReceived % 10 == 0) {
                        Logger.d("RX #$packetsReceived: ${hex.take(40)}...")
                    }

                    // Parse incoming commands from the dash
                    parseIncomingPacket(received)

                } catch (_: java.net.SocketTimeoutException) {
                    // Expected on timeout, just loop
                } catch (e: Exception) {
                    if (connectionState == ConnectionState.CONNECTED) {
                        Logger.d("Receiver error: ${e.message}")
                    }
                    break
                }
            }
            Logger.d("Receiver thread stopped.")
        }.apply {
            name = "DashUDPReceiver"
            isDaemon = true
            start()
        }
    }

    private fun parseIncomingPacket(data: ByteArray) {
        // The dash sends commands with byte[8] == 0x0B for FOTA,
        // and various joystick/media control commands.
        // For now, we log and handle media controls.
        if (data.size < 12) return

        // Byte 8 indicates command category
        // We primarily care about media control commands from the joystick
        // These are parsed as hex substrings in the RE app (clk.java)
        val hex = data.joinToString("") { "%02X".format(it) }
        val payload = if (hex.length > 32) hex.substring(32) else return

        // Music Play/Pause toggle (joystick command 05)
        if (payload.contains("0005")) {
            Logger.d("Dash: Music play/pause toggle received")
        }
        // Music Next (joystick command 06)
        if (payload.contains("0006")) {
            Logger.d("Dash: Music next track command received")
        }
        // Music Previous (joystick command 07)
        if (payload.contains("0007")) {
            Logger.d("Dash: Music previous track command received")
        }
    }

    // ──────────────────────── Disconnect / Cleanup ────────────────────────

    private fun disconnect() {
        Logger.d("Disconnecting...")
        heartbeatTimer?.cancel()
        heartbeatTimer = null

        receiverThread?.interrupt()
        receiverThread = null

        try { sendSocket?.close() } catch (_: Exception) {}
        try { recvSocket?.close() } catch (_: Exception) {}
        sendSocket = null
        recvSocket = null

        networkCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        networkCallback = null

        try { connectivityManager.bindProcessToNetwork(null) } catch (_: Exception) {}
        boundNetwork = null

        packetsSent = 0
        packetsReceived = 0
        sequenceCounter = 0
    }

    // ──────────────────────── Telemetry Helpers ────────────────────────

    private fun getBatteryLevel(): Int {
        val batteryIntent = registerReceiver(
            null,
            android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 50) ?: 50
        val scale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        return (level * 100) / scale
    }

    private fun isCharging(): Boolean {
        val batteryIntent = registerReceiver(
            null,
            android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val status = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
               status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

    private fun getCellSignal(): Int {
        // Simplified: return a mock value. Real implementation would use TelephonyManager.
        return 3
    }

    // ──────────────────────── Notification ────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OPDash Connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Maintains connection to the Tripper Dash"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, Class.forName("com.opdash.MainActivity"))

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OPDash")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
