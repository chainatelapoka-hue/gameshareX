package com.gameshare.x

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private data class PendingConnect(
        val info: JoinInfo,
        val username: String,
        val password: String,
        val network: Network,
    )

    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var ssidInput: EditText
    private lateinit var passInput: EditText
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var userInput: EditText
    private lateinit var authPassInput: EditText

    private lateinit var joiner: WifiJoiner
    private var pending: PendingConnect? = null
    private var statsJob: Job? = null

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        if (raw.isNullOrBlank()) {
            setStatus("স্ক্যান বাতিল হয়েছে")
            return@registerForActivityResult
        }
        val info = JoinInfo.parse(raw)
        if (info == null) {
            setStatus("এটি gameshare QR নয়। Host ফোনের GameShare-এর QR স্ক্যান করুন।")
            return@registerForActivityResult
        }
        fillFields(info)
        connect(info, userInput.text.toString().trim(), authPassInput.text.toString())
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchScanner() else setStatus("ক্যামেরার অনুমতি ছাড়া QR স্ক্যান করা যাবে না")
    }

    private val vpnConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnNow()
        } else {
            setStatus("VPN অনুমতি দেওয়া হয়নি, তাই কানেক্ট হয়নি")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        statsText = findViewById(R.id.statsText)
        ssidInput = findViewById(R.id.ssidInput)
        passInput = findViewById(R.id.passInput)
        hostInput = findViewById(R.id.hostInput)
        portInput = findViewById(R.id.portInput)
        userInput = findViewById(R.id.userInput)
        authPassInput = findViewById(R.id.authPassInput)

        joiner = WifiJoiner(this)

        findViewById<Button>(R.id.scanButton).setOnClickListener { requestScan() }
        findViewById<Button>(R.id.connectButton).setOnClickListener { connectFromFields() }
        findViewById<Button>(R.id.disconnectButton).setOnClickListener { disconnect() }
    }

    override fun onResume() {
        super.onResume()
        if (ProxyVpnService.running || ProxyVpnService.lastError != null) startStatsLoop()
    }

    private fun requestScan() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) launchScanner() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun launchScanner() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Host ফোনের GameShare QR সামনে ধরুন")
            .setBeepEnabled(false)
        scanLauncher.launch(options)
    }

    private fun connectFromFields() {
        val ssid = ssidInput.text.toString().trim()
        val pass = passInput.text.toString()
        if (ssid.isEmpty() || pass.isEmpty()) {
            setStatus("Network নাম আর passphrase লিখুন, অথবা QR স্ক্যান করুন")
            return
        }
        val host = hostInput.text.toString().trim().ifEmpty { JoinInfo.DEFAULT_HOST }
        val port = portInput.text.toString().toIntOrNull()?.takeIf { it in 1..65535 } ?: JoinInfo.DEFAULT_PORT
        connect(
            JoinInfo(ssid = ssid, passphrase = pass, host = host, port = port),
            userInput.text.toString().trim(),
            authPassInput.text.toString(),
        )
    }

    private fun fillFields(info: JoinInfo) {
        ssidInput.setText(info.ssid)
        passInput.setText(info.passphrase)
        hostInput.setText(info.host)
        portInput.setText(info.port.toString())
    }

    private fun connect(info: JoinInfo, username: String, password: String) {
        lifecycleScope.launch {
            setStatus("Wi-Fi-তে যুক্ত হচ্ছে: ${info.ssid} …")
            try {
                val network = joiner.join(info)
                connectivity().bindProcessToNetwork(network)
                pending = PendingConnect(info, username, password, network)
                val consent = VpnService.prepare(this@MainActivity)
                if (consent != null) {
                    vpnConsent.launch(consent)
                } else {
                    startVpnNow()
                }
            } catch (e: Exception) {
                setStatus("ব্যর্থ: ${e.message ?: "অজানা ত্রুটি"}")
            }
        }
    }

    private fun startVpnNow() {
        val p = pending ?: return
        ProxyVpnService.start(this, p.info, p.username, p.password, p.network)
        setStatus("VPN চালু হচ্ছে…")
        startStatsLoop()
    }

    private fun disconnect() {
        ProxyVpnService.stop(this)
        joiner.release()
        connectivity().bindProcessToNetwork(null)
        pending = null
        statsJob?.cancel()
        statsText.text = ""
        setStatus("বন্ধ আছে")
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = lifecycleScope.launch {
            while (isActive) {
                val error = ProxyVpnService.lastError
                val stats = ProxyVpnService.stats()
                val p = pending
                when {
                    error != null -> setStatus("ব্যর্থ: $error")
                    stats != null && p != null -> {
                        setStatus("Active: সব অ্যাপের ইন্টারনেট ${p.info.host}:${p.info.port} দিয়ে যাচ্ছে")
                        statsText.text = String.format(
                            Locale.US,
                            "↑ %s   ↓ %s",
                            formatBytes(stats[1]),
                            formatBytes(stats[3]),
                        )
                    }
                }
                delay(2000)
            }
        }
    }

    private fun setStatus(message: String) {
        statusText.text = message
    }

    private fun connectivity(): ConnectivityManager =
        getSystemService(ConnectivityManager::class.java)

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1_024L -> String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
        else -> "$bytes B"
    }
}
