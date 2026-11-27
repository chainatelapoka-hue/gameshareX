package com.gameshare.x

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * Joins the host's Wi-Fi Direct group and returns the [Network] that holds the group's 192.168.49.x
 * address. Android 10+ uses WifiNetworkSpecifier; Android 7-9 use the older addNetwork API.
 */
class WifiJoiner(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifi = appContext.getSystemService(WifiManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    suspend fun join(info: JoinInfo, timeoutMs: Long = 30_000L): Network {
        release()
        val found = CompletableDeferred<Network>()
        val callbackForNetwork = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (linkProperties.hasHostGroupAddress()) found.complete(network)
            }
        }
        callback = callbackForNetwork

        val builder = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setNetworkSpecifier(specifierFor(info))
            connectivity.requestNetwork(builder.build(), callbackForNetwork)
        } else {
            connectLegacy(info)
            connectivity.requestNetwork(builder.build(), callbackForNetwork)
        }

        return try {
            withTimeout(timeoutMs) { found.await() }
        } catch (e: Exception) {
            release()
            throw IllegalStateException(
                "Host-এর Wi-Fi-তে যুক্ত হওয়া যায়নি। নাম ও passphrase ঠিক আছে কিনা দেখুন।",
                e,
            )
        }
    }

    /** Stops listening for the host network. The caller should also unbind the process. */
    fun release() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun specifierFor(info: JoinInfo): WifiNetworkSpecifier =
        WifiNetworkSpecifier.Builder()
            .setSsid(info.ssid)
            .setWpa2Passphrase(info.passphrase)
            .build()

    @Suppress("DEPRECATION")
    private fun connectLegacy(info: JoinInfo) {
        if (!wifi.isWifiEnabled) wifi.isWifiEnabled = true
        val config = WifiConfiguration().apply {
            SSID = "\"${info.ssid}\""
            preSharedKey = "\"${info.passphrase}\""
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        }
        val netId = wifi.addNetwork(config)
        if (netId == -1) throw IllegalStateException("Wi-Fi network যোগ করা যায়নি")
        wifi.disconnect()
        wifi.enableNetwork(netId, true)
        wifi.reconnect()
    }

    private fun LinkProperties.hasHostGroupAddress(): Boolean =
        linkAddresses.any { it.address.hostAddress?.startsWith("192.168.49.") == true }
}
