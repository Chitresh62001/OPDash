package com.opdash.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.opdash.connection.ConnectionManager
import com.opdash.model.ConnectionState

class MainViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val manager = ConnectionManager(application)

    var dashboardSSID by mutableStateOf("")
        private set

    var state by mutableStateOf(ConnectionState.IDLE)
        private set

    var status by mutableStateOf("Idle")
        private set

    fun updateDashboardSSID(value: String) {
        dashboardSSID = value
    }

    fun connect() {

        if (dashboardSSID.isBlank()) {
            status = "Enter Dashboard SSID"
            return
        }

        state = ConnectionState.CONNECTING_WIFI
        status = "Connecting..."

        manager.connect(dashboardSSID)

        status = "Connection Requested"
    }

    fun disconnect() {
        state = ConnectionState.IDLE
        status = "Disconnected"
    }
}