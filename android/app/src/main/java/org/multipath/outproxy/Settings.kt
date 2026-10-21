package org.multipath.outproxy

import android.content.Context
import java.security.SecureRandom

class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverAddress: String
        get() = p.getString("server", "") ?: ""
        set(v) = p.edit().putString("server", v.trim().lowercase()).apply()

    var links: Int
        get() = p.getInt("links", 16).coerceIn(4, 32)
        set(v) = p.edit().putInt("links", v.coerceIn(4, 32)).apply()

    var hops: Int
        get() = p.getInt("hops", 3).coerceIn(1, 3)
        set(v) = p.edit().putInt("hops", v.coerceIn(1, 3)).apply()

    var externalRouter: Boolean
        get() = p.getBoolean("external", false)
        set(v) = p.edit().putBoolean("external", v).apply()

    var samAddress: String
        get() = p.getString("sam", DEFAULT_SAM) ?: DEFAULT_SAM
        set(v) = p.edit().putString("sam", v.trim().ifEmpty { DEFAULT_SAM }).apply()

    val basePort: Int get() = secretInt("base_port") { 20000 + rnd.nextInt(300) * 100 }
    val webPort: Int get() = basePort + 70
    val muxPort: Int get() = basePort + 90
    val webPassword: String get() = secretString("web_pass")

    private fun secretInt(key: String, gen: () -> Int): Int {
        if (!p.contains(key)) p.edit().putInt(key, gen()).apply()
        return p.getInt(key, 0)
    }

    private fun secretString(key: String): String {
        if (!p.contains(key)) {
            val b = ByteArray(18).also { rnd.nextBytes(it) }
            p.edit().putString(key, b.joinToString("") { "%02x".format(it) }).apply()
        }
        return p.getString(key, "") ?: ""
    }

    companion object {
        const val DEFAULT_SAM = "127.0.0.1:7656"

        val ROUTER_PACKAGES = listOf(
            "org.purplei2p.i2pd",
            "net.i2p.android",
            "net.i2p.android.router",
        )

        private val rnd = SecureRandom()
        private val ADDRESS = Regex("^[a-z2-7]{52,}\\.b32\\.i2p$")
        fun isValidAddress(s: String) = ADDRESS.matches(s.trim().lowercase())
    }
}
