package com.gameshare.x

import android.content.Context
import android.content.Intent
import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import hev.htproxy.TProxyService
import java.io.File

/**
 * Sends every app's traffic into a local TUN interface, and hev-socks5-tunnel forwards it
 * (TCP and UDP) to the host's SOCKS5 proxy over the Wi-Fi Direct network.
 */
class ProxyVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        val host = intent?.getStringExtra(EXTRA_HOST)
        val port = intent?.getIntExtra(EXTRA_PORT, JoinInfo.DEFAULT_PORT) ?: JoinInfo.DEFAULT_PORT
        if (intent == null || host == null) {
            lastError = "সংযোগের তথ্য পাওয়া যায়নি"
            stopSelf()
            return START_NOT_STICKY
        }
        val config = TunnelConfig(
            host = host,
            port = port,
            username = intent.getStringExtra(EXTRA_USER).orEmpty(),
            password = intent.getStringExtra(EXTRA_PASS).orEmpty(),
            network = intent.getParcelableExtra<Network>(EXTRA_NETWORK),
        )
        try {
            startTunnel(config)
            lastError = null
        } catch (e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            stopTunnel()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(config: TunnelConfig) {
        stopTunnel()
        val builder = Builder()
            .setSession("gameshareX")
            .setMtu(TUN_MTU)
            .addAddress(TUN_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
        // Keep our own app outside the VPN, otherwise the proxy connection would loop back into it.
        builder.addDisallowedApplication(packageName)
        config.network?.let { builder.setUnderlyingNetworks(arrayOf(it)) }

        val pfd = builder.establish()
            ?: throw IllegalStateException("VPN চালু করা যায়নি। VPN অনুমতি দেওয়া আছে কিনা দেখুন।")
        tunnel = pfd

        val configFile = writeConfig(config)
        val started = TProxyService.TProxyStartService(configFile.absolutePath, pfd.fd)
        if (!started) throw IllegalStateException("Proxy engine চালু হয়নি")
        running = true
    }

    private fun writeConfig(config: TunnelConfig): File {
        val yaml = buildString {
            appendLine("tunnel:")
            appendLine("  name: tun0")
            appendLine("  mtu: $TUN_MTU")
            appendLine("  multi-queue: false")
            appendLine("  ipv4: $TUN_ADDRESS")
            appendLine("socks5:")
            appendLine("  address: ${config.host}")
            appendLine("  port: ${config.port}")
            appendLine("  udp: 'udp'")
            if (config.username.isNotEmpty()) {
                appendLine("  username: ${yamlQuote(config.username)}")
                appendLine("  password: ${yamlQuote(config.password)}")
            }
            appendLine("misc:")
            appendLine("  log-level: warn")
            appendLine("  task-stack-size: 24576")
            appendLine("  tcp-buffer-size: 16384")
            appendLine("  udp-recv-buffer-size: 262144")
            appendLine("  max-session-count: 300")
        }
        val file = File(cacheDir, "hev-socks5.yml")
        file.writeText(yaml)
        return file
    }

    private fun stopTunnel() {
        runCatching { TProxyService.TProxyStopService() }
        running = false
        runCatching { tunnel?.close() }
        tunnel = null
    }

    override fun onRevoke() {
        stopTunnel()
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private class TunnelConfig(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val network: Network?,
    )

    companion object {
        const val ACTION_STOP = "com.gameshare.x.STOP"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_USER = "user"
        private const val EXTRA_PASS = "pass"
        private const val EXTRA_NETWORK = "network"
        private const val TUN_ADDRESS = "198.18.0.1"
        private const val TUN_MTU = 1400

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var lastError: String? = null

        fun start(context: Context, info: JoinInfo, username: String, password: String, network: Network?) {
            lastError = null
            val intent = Intent(context, ProxyVpnService::class.java)
                .putExtra(EXTRA_HOST, info.host)
                .putExtra(EXTRA_PORT, info.port)
                .putExtra(EXTRA_USER, username)
                .putExtra(EXTRA_PASS, password)
                .putExtra(EXTRA_NETWORK, network)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ProxyVpnService::class.java).setAction(ACTION_STOP))
        }

        /** [tx packets, tx bytes, rx packets, rx bytes], or null while stopped. */
        fun stats(): LongArray? =
            if (running) runCatching { TProxyService.TProxyGetStats() }.getOrNull() else null

        private fun yamlQuote(value: String): String = "'" + value.replace("'", "''") + "'"
    }
}
