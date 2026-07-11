package com.opdash.connection

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.*
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.opdash.logging.Logger
import com.opdash.model.ConnectionState
import com.opdash.protocol.K1GPacketBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
        private const val DASH_IP = "192.168.4.1"
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

        // Navigation data
        var currentRoadName by mutableStateOf("")
        var distanceToTurn by mutableIntStateOf(0)
        var turnIcon by mutableIntStateOf(0)
        
        // Casting control
        var isMapCasting by mutableStateOf(false)

        fun isRunning(): Boolean =
            connectionState != ConnectionState.IDLE && connectionState != ConnectionState.ERROR
    }
    
    private val mapCaster = com.opdash.media.MapCastingManager { packet ->
        sendPacket(packet)
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // Observe map casting toggle
        snapshotFlow { isMapCasting }
            .onEach { casting ->
                if (casting) {
                    Logger.d("Service: Starting Map Casting...")
                    mapCaster.startCasting()
                } else {
                    Logger.d("Service: Stopping Map Casting...")
                    mapCaster.stopCasting()
                }
            }
            .launchIn(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ssid = intent?.getStringExtra(EXTRA_SSID) ?: ""
        val password = intent?.getStringExtra(EXTRA_PASSWORD) ?: ""

        if (ssid.isBlank()) {
            Logger.d("Service started without SSID. Stopping.")
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
            statusMessage = "Foreground Error"
            stopSelf()
            return START_NOT_STICKY
        }

        // Keep CPU, Wi-Fi and Multicast alive
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OPDash::WakeLock").apply {
            acquire(30 * 60 * 1000L) 
        }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "OPDash::WifiLock").apply {
            acquire()
        }
        multicastLock = wm.createMulticastLock("OPDash::MulticastLock").apply {
            acquire()
        }

        Logger.d("Service started. Target SSID: $ssid")
        connectToWifi(ssid, password)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        multicastLock?.let { if (it.isHeld) it.release() }
        connectionState = ConnectionState.IDLE
        statusMessage = "Service stopped"
        Logger.d("Service destroyed.")
    }

    // ──────────────────────── Wi-Fi Connection ────────────────────────

    private fun connectToWifi(ssid: String, password: String) {
        connectionState = ConnectionState.CONNECTING_WIFI
        statusMessage = "Connecting to $ssid..."

        try {
            // First, try to see if we are already connected to a Wi-Fi network
            val currentNetwork = connectivityManager.activeNetwork
            val currentCaps = connectivityManager.getNetworkCapabilities(currentNetwork)
            if (currentCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                 Logger.d("Device already on Wi-Fi. Attempting to bind UDP...")
                 connectionState = ConnectionState.CONNECTED_WIFI
                 startUdpCommunication(currentNetwork!!)
                 // We still continue to requestNetwork to ensure we "own" the connection 
                 // and stay on it even if there's no internet.
            }

            val builder = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
            
            if (password.isNotEmpty()) {
                builder.setWpa2Passphrase(password)
            }

            val specifier = builder.build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            statusMessage = "Please tap 'Connect' on the system popup"
            Logger.d("Requesting Wi-Fi: $ssid")

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Logger.d("Wi-Fi bound successfully.")
                    boundNetwork = network
                    connectivityManager.bindProcessToNetwork(network)
                    
                    connectionState = ConnectionState.CONNECTED_WIFI
                    statusMessage = "Wi-Fi Ready"
                    updateNotification("Connected to Dash")
                    
                    startUdpCommunication(network)
                }

                override fun onLost(network: Network) {
                    Logger.d("Wi-Fi connection lost.")
                    if (boundNetwork == network) {
                        connectionState = ConnectionState.ERROR
                        statusMessage = "Connection Lost"
                        // Don't stopSelf() immediately, maybe it's a glitch
                    }
                }

                override fun onUnavailable() {
                    Logger.d("Network request timed out or cancelled.")
                    connectionState = ConnectionState.IDLE
                    statusMessage = "Connection Cancelled"
                    stopSelf()
                }
            }

            connectivityManager.requestNetwork(request, networkCallback!!)
            
            // "Better than original": Suggest the network to the system for future auto-connect
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                suggestNetwork(ssid, password)
            }

        } catch (e: Exception) {
            Logger.d("Wi-Fi Error: ${e.message}")
            connectionState = ConnectionState.ERROR
            statusMessage = "Setup Failed"
            stopSelf()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun suggestNetwork(ssid: String, password: String) {
        val suggestionBuilder = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setIsAppInteractionRequired(false) // Try to connect automatically
        
        if (password.isNotEmpty()) {
            suggestionBuilder.setWpa2Passphrase(password)
        }

        val suggestions = listOf(suggestionBuilder.build())
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val status = wifiManager.addNetworkSuggestions(suggestions)
        if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            Logger.d("Network suggestion added for $ssid (Auto-connect enabled)")
        }
    }

    // ──────────────────────── UDP Communication ────────────────────────

    private fun startUdpCommunication(network: Network) {
        connectionState = ConnectionState.STARTING_UDP
        statusMessage = "Initializing UDP..."
        
        Thread {
            try {
                // Send socket: bind to port 2000 locally as well
                // This ensures source port is 2000, which some dashes require.
                sendSocket = DatagramSocket(SEND_PORT)
                sendSocket?.broadcast = true
                network.bindSocket(sendSocket!!)

                // Receive socket: bind to 2002 locally
                recvSocket = DatagramSocket(RECV_PORT)
                network.bindSocket(recvSocket!!)

                connectionState = ConnectionState.CONNECTED
                statusMessage = "Connected & Syncing"
                Logger.d("UDP sockets ready. Source port: 2000, Destination port: 2000")

                startHeartbeat()
                startReceiver()

            } catch (e: Exception) {
                Logger.d("UDP start failed: ${e.message}")
                // Fallback to random source port if 2000 is taken
                try {
                    sendSocket = DatagramSocket()
                    sendSocket?.broadcast = true
                    network.bindSocket(sendSocket!!)
                    Logger.d("UDP TX fallback to random port: ${sendSocket?.localPort}")
                    
                    // Proceed if fallback worked
                    connectionState = ConnectionState.CONNECTED
                    startHeartbeat()
                    startReceiver()
                } catch (e2: Exception) {
                    connectionState = ConnectionState.ERROR
                    statusMessage = "UDP Error"
                }
            }
        }.start()
    }

    // ──────────────────────── Heartbeat Timer ────────────────────────

    private fun startHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = Timer("DashHeartbeat", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    sendAllUpdates()
                }
            }, 0L, HEARTBEAT_INTERVAL_MS)
        }
    }

    private fun sendAllUpdates() {
        try {
            // 0. Handshake (Keep-alive)
            sendPacket(K1GPacketBuilder.buildHandshakePacket())
            
            // Check Map Casting status and trigger caster
            if (isMapCasting) {
                // In a real scenario, we'd pass the actual Map View from UI
                // For this implementation, the caster will run its internal thread
            }

            // 1. Periodic Telemetry
            val telemetryPacket = K1GPacketBuilder.buildTelemetryPacket(
                cellSignalStrength = getCellSignal(),
                batteryLevel = getBatteryLevel(),
                isCharging = isCharging(),
                isGpsActive = true,
                volume = 5,
                weatherTemp = 25
            )
            sendPacket(telemetryPacket)

            // 2. Music Sync
            if (isMusicPlaying && currentTrackTitle.isNotEmpty()) {
                sendPacket(K1GPacketBuilder.buildMusicActivePacket(true))
                sendPacket(K1GPacketBuilder.buildMusicMetadataPacket(
                    title = currentTrackTitle,
                    artist = currentTrackArtist,
                    album = currentTrackAlbum
                ))
                sendPacket(K1GPacketBuilder.buildMusicPlayStatePacket(true))
            } else {
                sendPacket(K1GPacketBuilder.buildMusicActivePacket(false))
            }

            // 3. Navigation Sync
            if (currentRoadName.isNotEmpty()) {
                val navPacket = K1GPacketBuilder.buildNavigationPacket(
                    roadName = currentRoadName,
                    primaryManeuver = turnIcon,
                    distanceToTurn = distanceToTurn,
                    distanceUnit = 1, // Meters
                    etaHours = 0,
                    etaMinutes = 15,
                    totalDistanceRemaining = 5,
                    totalDistanceUnit = 2 // KM
                )
                sendPacket(navPacket)
            }

        } catch (e: Exception) {
            Logger.d("Send error: ${e.message}")
        }
    }

    // ──────────────────────── Packet Send / Receive ────────────────────────

    private fun sendPacket(packetData: ByteArray) {
        try {
            val data = packetData.copyOf()
            if (data.size > 16) {
                data[16] = (sequenceCounter and 0xFF).toByte()
            }
            
            // Calculate CRC16-CCITT (False) over all bytes except the last two (the CRC itself)
            if (data.size > 2) {
                val crc = com.opdash.ble.CRC16.calculate(data.copyOfRange(0, data.size - 2))
                data[data.size - 2] = crc[0]
                data[data.size - 1] = crc[1]
            }

            sequenceCounter = (sequenceCounter + 1) % 256

            // Send to both specific IP and broadcast to ensure delivery
            val dgPacket1 = DatagramPacket(data, data.size, InetAddress.getByName(DASH_IP), SEND_PORT)
            sendSocket?.send(dgPacket1)
            
            val dgPacket2 = DatagramPacket(data, data.size, InetAddress.getByName(DASH_BROADCAST_IP), SEND_PORT)
            sendSocket?.send(dgPacket2)
            
            packetsSent++
        } catch (e: Exception) {
            if (packetsSent % 50 == 0) Logger.d("UDP TX Error: ${e.message}")
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
