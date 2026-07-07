package com.opdash.connection

import android.content.Context

class ConnectionManager(
    private val context: Context
) {

    fun connect(
        dashboardSSID: String
    ) {

        /*
         *
         * Entire connection flow lives here.
         *
         * 1 Connect WiFi
         * 2 Start UDP
         * 3 BLE Authorization (if required)
         *
         */

    }

}