package com.opdash.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.opdash.connection.ConnectionManager
import com.opdash.connection.DashConnectionService
import com.opdash.logging.Logger
import com.opdash.model.ConnectionState
import com.opdash.protocol.K1GPacketBuilder

class MainViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val manager = ConnectionManager(application)

    var dashboardSSID by mutableStateOf("")
        private set

    var password by mutableStateOf("")
        private set

    // Derived from the service companion state
    val state: ConnectionState
        get() = DashConnectionService.connectionState

    val status: String
        get() = DashConnectionService.statusMessage

    val packetsSent: Int
        get() = DashConnectionService.packetsSent

    val packetsReceived: Int
        get() = DashConnectionService.packetsReceived

    val lastRxHex: String
        get() = DashConnectionService.lastReceivedHex

    val isServiceRunning: Boolean
        get() = DashConnectionService.isRunning()

    val currentTrack: String
        get() {
            val title = DashConnectionService.currentTrackTitle
            val artist = DashConnectionService.currentTrackArtist
            return if (title.isNotEmpty()) "$title — $artist" else "No track"
        }

    val isMusicPlaying: Boolean
        get() = DashConnectionService.isMusicPlaying

    fun updatePassword(value: String) {
        password = value
    }

    fun updateDashboardSSID(value: String) {
        dashboardSSID = value
    }

    fun connect() {
        if (dashboardSSID.isBlank()) {
            Logger.d("Enter Dashboard SSID")
            return
        }
        if (password.isBlank()) {
            Logger.d("Enter WiFi Password")
            return
        }
        if (password.length < 8 || password.length > 63) {
            Logger.d("WiFi Password must be between 8 and 63 characters.")
            return
        }

        manager.connect(
            dashboardSSID,
            password,
            onConnected = {
                Logger.d("Service start requested.")
            },
            onFailed = { error ->
                Logger.d("Connection failed: $error")
            }
        )
    }

    fun disconnect() {
        manager.disconnect()
        Logger.d("Disconnected.")
    }

    // ──────────────────── Simulation Controls ────────────────────

    fun simulateNavigation() {
        Logger.d("SIM: Sending navigation packet")
        // This is just for testing — in production, nav data comes from a navigation app
    }

    fun simulateMusicToggle() {
        DashConnectionService.isMusicPlaying = !DashConnectionService.isMusicPlaying
        if (DashConnectionService.isMusicPlaying) {
            DashConnectionService.currentTrackTitle = "Highway Star"
            DashConnectionService.currentTrackArtist = "Deep Purple"
            DashConnectionService.currentTrackAlbum = "Machine Head"
        }
        Logger.d("SIM: Music ${if (DashConnectionService.isMusicPlaying) "Playing" else "Paused"}")
    }

    fun clearLogs() {
        Logger.logs.clear()
    }
}