package com.opdash.utils

import android.Manifest
import android.os.Build

object PermissionManager {

    val permissions =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )

        } else {

            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )

        }

}