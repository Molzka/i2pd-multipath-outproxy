package org.multipath.outproxy

import android.content.Context
import java.net.InetAddress
import java.security.SecureRandom
import java.util.Locale

class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverAddress: String
        get() = p.getString("server", "") ?: ""
        set(v) = p.edit().putString("server", v.trim().lowercase(Locale.ROOT)).apply()

    var links: Int
        get() = p.getInt("links", 16).takeIf { it in SUPPORTED_LINKS } ?: 16
        set(v) = p.edit().putInt("links", v.takeIf { it in SUPPORTED_LINKS } ?: 16).apply()

    var hops: Int
        get() = p.getInt("hops", 3).coerceIn(1, 3)
        set(v) = p.edit().putInt("hops", v.coerceIn(1, 3)).apply()

    var externalRouter: Boolean
        get() = p.getBoolean("external", false)
        set(v) = p.edit().putBoolean("external", v).apply()

    var samAddress: String
        get() = p.getString("sam", DEFAULT_SAM)?.takeIf(::isValidSamAddress) ?: DEFAULT_SAM
        set(v) {
            val value = v.trim().ifEmpty { DEFAULT_SAM }
            require(isValidSamAddress(value))
            p.edit().putString("sam", value).apply()
        }

    var dnsServer: String
        get() = p.getString("dns", DEFAULT_DNS)?.takeIf(::isValidDns) ?: DEFAULT_DNS
        set(v) {
            val value = v.trim()
            require(isValidDns(value))
            p.edit().putString("dns", value).apply()
        }

    var mtu: Int
        get() = p.getInt("mtu", DEFAULT_MTU).takeIf { it in SUPPORTED_MTUS } ?: DEFAULT_MTU
        set(v) {
            require(v in SUPPORTED_MTUS)
            p.edit().putInt("mtu", v).apply()
        }

    var excludedApps: Set<String>
        get() = p.getStringSet("excluded_apps", emptySet())?.filter(PACKAGE::matches)?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("excluded_apps", v.filter(PACKAGE::matches).toSet()).apply()

    var autoReconnect: Boolean
        get() = p.getBoolean("auto_reconnect", true)
        set(v) = p.edit().putBoolean("auto_reconnect", v).apply()

    var connectOnOpen: Boolean
        get() = p.getBoolean("connect_on_open", false)
        set(v) = p.edit().putBoolean("connect_on_open", v).apply()

    var metered: Boolean
        get() = p.getBoolean("metered", true)
        set(v) = p.edit().putBoolean("metered", v).apply()

    fun resetConnectionDefaults() {
        p.edit().remove("links").remove("hops").remove("external").remove("sam")
            .remove("dns").remove("mtu").remove("excluded_apps").remove("auto_reconnect")
            .remove("connect_on_open").remove("metered").apply()
    }

    internal fun snapshot() = ConnectionSettings(
        serverAddress, links, hops, externalRouter, samAddress, dnsServer, mtu,
        excludedApps, autoReconnect, metered, basePort, webPassword,
    )

    val basePort: Int get() = secretInt("base_port") { 20000 + rnd.nextInt(300) * 100 }
    val webPort: Int get() = basePort + 70
    val muxPort: Int get() = basePort + 90
    val webPassword: String get() = secretString("web_pass")

    private fun secretInt(key: String, gen: () -> Int): Int = synchronized(rnd) {
        if (!p.contains(key)) p.edit().putInt(key, gen()).apply()
        p.getInt(key, 0)
    }

    private fun secretString(key: String): String = synchronized(rnd) {
        if (!p.contains(key)) {
            val b = ByteArray(18).also { rnd.nextBytes(it) }
            p.edit().putString(key, b.joinToString("") { "%02x".format(it) }).apply()
        }
        p.getString(key, "") ?: ""
    }

    companion object {
        const val DEFAULT_SAM = "127.0.0.1:7656"
        const val DEFAULT_DNS = "1.1.1.1"
        const val DEFAULT_MTU = 1500
        val SUPPORTED_MTUS = listOf(1280, 1400, 1500)
        val SUPPORTED_LINKS = listOf(8, 16, 24, 32)

        val ROUTER_PACKAGES = listOf(
            "org.purplei2p.i2pd",
            "net.i2p.android",
            "net.i2p.android.router",
        )

        private val rnd = SecureRandom()
        private val ADDRESS = Regex("^[a-z2-7]{52,}\\.b32\\.i2p$")
        private val PACKAGE = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
        private val HOST_LABEL = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
        private val IPV6_CHARACTERS = Regex("^[0-9A-Fa-f:.]+$")

        fun isValidAddress(s: String) = ADDRESS.matches(s.trim().lowercase(Locale.ROOT))

        fun isValidDns(s: String): Boolean {
            val address = numericAddress(s.trim()) ?: return false
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress) return false
            val bytes = address.address.map { it.toInt() and 255 }
            if (bytes.size == 4) {
                val (a, b, c) = bytes
                return a != 0 && a != 10 && a != 127 && a < 224 &&
                    !(a == 100 && b in 64..127) && !(a == 169 && b == 254) &&
                    !(a == 172 && b in 16..31) && !(a == 192 && b == 168) &&
                    !(a == 192 && b == 0 && c in listOf(0, 2)) &&
                    !(a == 198 && b in 18..19) && !(a == 198 && b == 51 && c == 100) &&
                    !(a == 203 && b == 0 && c == 113)
            }
            return bytes[0] in 0x20..0x3f &&
                !(bytes[0] == 0x20 && bytes[1] == 1 && bytes[2] == 0x0d && bytes[3] == 0xb8) &&
                !(bytes[0] == 0x20 && bytes[1] == 1 && bytes[2] == 0 && bytes[3] == 2) &&
                !(bytes[0] == 0x3f && bytes[1] == 0xff && bytes[2] < 0x10)
        }

        fun isValidSamAddress(s: String) = parseSamAddress(s) != null

        internal fun parseSamAddress(s: String): Pair<String, Int>? {
            val value = s.trim()
            val host: String
            val portText: String
            if (value.startsWith("[")) {
                val closing = value.indexOf(']')
                if (closing < 2 || closing + 1 >= value.length || value[closing + 1] != ':') return null
                host = value.substring(1, closing)
                if (!host.contains(':') || numericAddress(host) == null) return null
                portText = value.substring(closing + 2)
            } else {
                if (value.count { it == ':' } != 1) return null
                host = value.substringBefore(':')
                portText = value.substringAfter(':')
                if (host.isEmpty() || host.length > 253) return null
                val numeric = host.all { it.isDigit() || it == '.' }
                if (numeric && numericAddress(host) == null) return null
                if (!numeric && !host.removeSuffix(".").split('.').all(HOST_LABEL::matches)) return null
            }
            if (portText.isEmpty() || !portText.all { it in '0'..'9' }) return null
            val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return host to port
        }

        private fun numericAddress(s: String): InetAddress? {
            if (s.contains(':')) {
                if (!IPV6_CHARACTERS.matches(s)) return null
                return try { InetAddress.getByName(s) } catch (_: Exception) { null }
            }
            val pieces = s.split('.')
            if (pieces.size != 4 || pieces.any {
                    it.isEmpty() || it.length > 3 || !it.all { c -> c in '0'..'9' } ||
                        (it.length > 1 && it.startsWith('0')) || it.toInt() !in 0..255
                }) return null
            return InetAddress.getByAddress(pieces.map { it.toInt().toByte() }.toByteArray())
        }
    }
}

internal data class ConnectionSettings(
    val serverAddress: String,
    val links: Int,
    val hops: Int,
    val externalRouter: Boolean,
    val samAddress: String,
    val dnsServer: String,
    val mtu: Int,
    val excludedApps: Set<String>,
    val autoReconnect: Boolean,
    val metered: Boolean,
    val basePort: Int,
    val webPassword: String,
) {
    val webPort: Int get() = basePort + 70
    val muxPort: Int get() = basePort + 90
}
