package com.opdash.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.opdash.connection.ConnectionManager
import com.opdash.model.ConnectionState
import com.opdash.wifi.WifiConnector

class ConnectionViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val manager =
        ConnectionManager(application)

    var dashboardSSID = ""
        private set

    var password = ""
        private set

    var state = ConnectionState.IDLE
        private set

    var statusMessage = "Idle"
        private set

    fun setSSID(value: String) {

        dashboardSSID = value

    }

    fun setPassword(value: String) {

        password = value

    }

    fun connect() {

        if (dashboardSSID.isBlank())
            return

        if (password.isBlank())
            return

        state = ConnectionState.CONNECTING_WIFI

        statusMessage =
            "Connecting..."

        manager.connect(
            dashboardSSID
        )

    }

}