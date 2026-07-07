package com.opdash.wifi

import android.content.Context
import android.net.*
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.Q)
class WifiConnector(
    private val context: Context
) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun connect(

        ssid: String,

        password: String,

        onConnected: () -> Unit,

        onFailed: (String) -> Unit

    ) {

        disconnect()

        val specifier =

            WifiNetworkSpecifier.Builder()

                .setSsid(ssid)

                .setWpa2Passphrase(password)

                .build()

        val request =

            NetworkRequest.Builder()

                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)

                .setNetworkSpecifier(specifier)

                .build()

        callback = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {

                connectivityManager.bindProcessToNetwork(network)

                onConnected()

            }

            override fun onUnavailable() {

                onFailed("Unable to connect")

            }

            override fun onLost(network: Network) {

                onFailed("Connection lost")

            }

        }

        connectivityManager.requestNetwork(
            request,
            callback!!
        )

    }

    fun disconnect() {

        callback?.let {

            connectivityManager.unregisterNetworkCallback(it)

        }

        callback = null

    }

}