package com.gameshare.x

/** Details needed to join the host's Wi-Fi Direct network and reach its SOCKS5 proxy. */
data class JoinInfo(
    val ssid: String,
    val passphrase: String,
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val authRequired: Boolean = false,
) {
    companion object {
        const val DEFAULT_HOST = "192.168.49.1"
        const val DEFAULT_PORT = 1080
        private const val GAMESHARE_PREFIX = "GSX1;"

        /**
         * Reads a GameShare QR (`GSX1;S=<ssid>;P=<pass>;H=<host>;O=<port>;A=0|1`).
         * Also accepts a standard Wi-Fi QR (`WIFI:T:WPA;S:<ssid>;P:<pass>;;`), using the default proxy.
         */
        fun parse(raw: String): JoinInfo? {
            val text = raw.trim()
            return when {
                text.startsWith(GAMESHARE_PREFIX) -> parseGameShare(text)
                text.startsWith("WIFI:") -> parseStandardWifi(text)
                else -> null
            }
        }

        private fun parseGameShare(text: String): JoinInfo? {
            val fields = text.removePrefix(GAMESHARE_PREFIX)
                .split(';')
                .mapNotNull { part ->
                    val eq = part.indexOf('=')
                    if (eq > 0) part.substring(0, eq) to part.substring(eq + 1) else null
                }
                .toMap()
            val ssid = fields["S"]?.takeIf { it.isNotBlank() } ?: return null
            val pass = fields["P"] ?: return null
            return JoinInfo(
                ssid = ssid,
                passphrase = pass,
                host = fields["H"]?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST,
                port = fields["O"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_PORT,
                authRequired = fields["A"] == "1",
            )
        }

        private fun parseStandardWifi(text: String): JoinInfo? {
            val fields = splitUnescaped(text.removePrefix("WIFI:"))
                .mapNotNull { part ->
                    val colon = part.indexOf(':')
                    if (colon > 0) part.substring(0, colon) to unescape(part.substring(colon + 1)) else null
                }
                .toMap()
            val ssid = fields["S"]?.takeIf { it.isNotBlank() } ?: return null
            return JoinInfo(ssid = ssid, passphrase = fields["P"].orEmpty())
        }

        /** Splits on ';' while keeping escaped characters (\;) inside a field. */
        private fun splitUnescaped(s: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    cur.append(c).append(s[i + 1])
                    i += 2
                    continue
                }
                if (c == ';') {
                    out.add(cur.toString())
                    cur.clear()
                } else {
                    cur.append(c)
                }
                i++
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
            return out
        }

        private fun unescape(s: String): String = buildString {
            var i = 0
            while (i < s.length) {
                if (s[i] == '\\' && i + 1 < s.length) {
                    append(s[i + 1])
                    i += 2
                } else {
                    append(s[i])
                    i++
                }
            }
        }
    }
}
