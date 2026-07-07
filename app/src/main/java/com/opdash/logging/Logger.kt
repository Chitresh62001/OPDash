package com.opdash.logging

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateListOf

object Logger {

    private const val TAG = "OPDash"

    val logs = mutableStateListOf<String>()

    // Main thread handler to safely mutate Compose snapshot state
    private val mainHandler = Handler(Looper.getMainLooper())

    fun d(message: String) {
        Log.d(TAG, message)

        // mutableStateListOf must only be modified on the main thread.
        // Service threads (Timer, UDP receiver, NetworkCallback) call Logger.d()
        // from background threads, so we must post to the main looper.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            addLog(message)
        } else {
            mainHandler.post { addLog(message) }
        }
    }

    private fun addLog(message: String) {
        logs.add(message)
        if (logs.size > 200) {
            logs.removeFirst()
        }
    }
}