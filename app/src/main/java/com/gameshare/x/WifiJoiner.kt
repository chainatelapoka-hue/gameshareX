package com.gameshare.x

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.delay

/**
 * Joins the host's Wi-Fi Direct network and returns the [Network] that holds the host's
 * 192.168.49.x address. Android 10+ uses WifiNetworkSpecifier; Android 7-9 use addNetwork.
 * Polls every second and reports the Wi-Fi state if it never gets an address.
 */
class WifiJoiner(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifi = appContext.getSystemService(WifiManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    suspend fun join(info: JoinInfo, timeoutMs: Long = 40_000L): Network {
        release()
        // Keeps Android bringing the Wi-Fi network up. Required on Android 10+.
        val keepAlive = object : ConnectivityManager.NetworkCallback() {}
        callback = keepAlive
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setNetworkSpecifier(specifierFor(info))
            }
            .build()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) connectLegacy(info)
        connectivity.requestNetwork(request, keepAlive)

        val deadline = System.currentTimeMillis() + timeoutMs
        var lastState = "শুরু হয়নি"
        while (System.currentTimeMillis() < deadline) {
            findHostNetwork()?.let { return it }
            lastState = describeWifi()
            delay(1000L)
        }
        release()
        throw IllegalStateException("Host-এর network-এর IP পাওয়া যায়নি। অবস্থা: $lastState")
    }

    /** Stops keeping the host network alive. The caller should also unbind the process. */
    fun release() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
    }

    private fun findHostNetwork(): Network? =
        connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network)
            val link = connectivity.getLinkProperties(network)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                link?.linkAddresses?.any { it.address.hostAddress?.startsWith("192.168.49.") == true } == true
        }

    private fun describeWifi(): String {
        val conn = wifi.connectionInfo
        val gateway = wifi.dhcpInfo?.gateway ?: 0
        val gw = "${gateway and 0xff}.${(gateway shr 8) and 0xff}.${(gateway shr 16) and 0xff}.${(gateway shr 24) and 0xff}"
        return "Wi-Fi ${conn?.supplicantState ?: "?"}, SSID ${conn?.ssid ?: "?"}, gateway $gw"
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
        // Remove older saved entries for this name so a stale password can't block the join.
        val quoted = "\"${info.ssid}\""
        runCatching {
            wifi.configuredNetworks?.filter { it.SSID == quoted }?.forEach { wifi.removeNetwork(it.networkId) }
        }
        val config = WifiConfiguration().apply {
            SSID = quoted
            preSharedKey = "\"${info.passphrase}\""
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        }
        val netId = wifi.addNetwork(config)
        if (netId == -1) throw IllegalStateException("Wi-Fi network যোগ করা যায়নি")
        wifi.disconnect()
        wifi.enableNetwork(netId, true)
        wifi.reconnect()
    }
}
