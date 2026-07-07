package com.opdash.connection

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.opdash.logging.Logger

/**
 * Bridge between the UI layer and DashConnectionService.
 * Starts/stops the foreground service with SSID + password extras.
 */
class ConnectionManager(
    private val context: Context
) {

    fun connect(
        dashboardSSID: String,
        password: String,
        onConnected: () -> Unit,
        onFailed: (String) -> Unit
    ) {
        Logger.d("ConnectionManager: Starting service for SSID=$dashboardSSID")

        val intent = Intent(context, DashConnectionService::class.java).apply {
            putExtra(DashConnectionService.EXTRA_SSID, dashboardSSID)
            putExtra(DashConnectionService.EXTRA_PASSWORD, password)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            onConnected()
        } catch (e: Exception) {
            Logger.d("Failed to start service: ${e.message}")
            onFailed("Failed to start service: ${e.message}")
        }
    }

    fun disconnect() {
        Logger.d("ConnectionManager: Stopping service.")
        val intent = Intent(context, DashConnectionService::class.java)
        context.stopService(intent)
    }
}