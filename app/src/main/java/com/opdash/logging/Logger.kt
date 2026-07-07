package com.opdash.logging

import android.util.Log
import androidx.compose.runtime.mutableStateListOf

object Logger {

    private const val TAG = "OPDash"

    val logs = mutableStateListOf<String>()

    fun d(message: String) {

        Log.d(TAG, message)

        logs.add(message)

        if (logs.size > 200) {
            logs.removeFirst()
        }
    }
}