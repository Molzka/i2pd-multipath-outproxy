package org.multipath.outproxy

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class Profile(
    val id: String,
    val name: String,
    val server: String,
    val links: Int = 16,
    val hops: Int = 3,
    val dns: String = Settings.DEFAULT_DNS,
    val mtu: Int = Settings.DEFAULT_MTU,
    val external: Boolean = false,
    val sam: String = Settings.DEFAULT_SAM,
) {
    fun json() = JSONObject().put("type", "multipath-i2p").put("version", 1)
        .put("name", name).put("server", server).put("links", links).put("hops", hops)
        .put("dns", dns).put("mtu", mtu).put("router", if (external) "sam" else "builtin").put("sam", sam)

    fun export(): String {
        require(Settings.isValidAddress(server))
        return json().toString(2)
    }

    fun qr(): String {
        val json = export().toByteArray(Charsets.UTF_8)
        return "multipath://profile?data=" + Base64.encodeToString(json, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun applyTo(editor: SharedPreferences.Editor) {
        editor.putString("server", server).putInt("links", links).putInt("hops", hops)
            .putString("dns", dns).putInt("mtu", mtu).putBoolean("external", external).putString("sam", sam)
    }

    companion object {
        const val MAX_BYTES = 16_384
        const val MAX_PROFILES = 32
        const val MAX_NAME = 48

        fun validName(name: String) = name.isNotBlank() && name.length <= MAX_NAME && name.none { it.isISOControl() }

        fun parse(text: String): Profile {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val decoded = if (text.startsWith("multipath:")) {
                val uri = Uri.parse(text)
                require(uri.scheme == "multipath" && uri.host == "profile" && uri.path.isNullOrEmpty())
                val encoded = requireNotNull(uri.getQueryParameter("data"))
                Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP).toString(Charsets.UTF_8)
            } else text
            return fromJson(JSONObject(decoded), false)
        }

        fun fromJson(j: JSONObject, allowEmpty: Boolean): Profile {
            fun text(key: String): String = (j.get(key) as? String) ?: error("Invalid field")
            fun number(key: String): Int = (j.get(key) as? Int) ?: error("Invalid field")
            require(text("type") == "multipath-i2p" && number("version") == 1)
            val name = text("name").trim()
            val server = text("server").trim().lowercase(java.util.Locale.ROOT)
            val dns = text("dns").trim()
            val sam = text("sam").trim()
            val links = number("links")
            val hops = number("hops")
            val mtu = number("mtu")
            val router = text("router")
            require(validName(name))
            require((allowEmpty && server.isEmpty()) || Settings.isValidAddress(server))
            require(links in Settings.SUPPORTED_LINKS && hops in 1..3 && mtu in Settings.SUPPORTED_MTUS)
            require(Settings.isValidDns(dns) && Settings.isValidSamAddress(sam))
            require(router == "builtin" || router == "sam")
            return Profile("", name, server, links, hops, dns, mtu, router == "sam", sam)
        }
    }
}

internal class Profiles(context: Context) {
    private val settings = Settings(context)
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        if (!prefs.contains("profiles")) {
            val first = current(UUID.randomUUID().toString(), context.getString(R.string.profile_default))
            persist(listOf(first), first)
        }
    }

    val selectedId: String get() = prefs.getString("profile_id", "").orEmpty()

    private fun current(id: String, name: String) = Profile(id, name, settings.serverAddress,
        settings.links, settings.hops, settings.dnsServer, settings.mtu, settings.externalRouter, settings.samAddress)

    fun list(): List<Profile> {
        val array = JSONArray(prefs.getString("profiles", "[]"))
        return (0 until array.length()).map { i ->
            val j = array.getJSONObject(i)
            val id = j.getString("id")
            val profile = Profile.fromJson(j, true).copy(id = id)
            if (id == selectedId) current(id, profile.name) else profile
        }
    }

    fun selected(): Profile = list().first { it.id == selectedId }

    fun select(id: String) {
        val all = list()
        persist(all, all.first { it.id == id })
    }

    fun add(profile: Profile) {
        val all = list()
        require(all.size < Profile.MAX_PROFILES)
        val added = profile.copy(id = UUID.randomUUID().toString())
        persist(all + added, added)
    }

    fun rename(id: String, name: String) {
        require(Profile.validName(name))
        val all = list().map { if (it.id == id) it.copy(name = name) else it }
        persist(all, all.first { it.id == selectedId })
    }

    fun delete(id: String) {
        val all = list()
        require(all.size > 1)
        val kept = all.filterNot { it.id == id }
        persist(kept, kept.firstOrNull { it.id == selectedId } ?: kept.first())
    }

    private fun persist(all: List<Profile>, selected: Profile) {
        val array = JSONArray()
        all.forEach { array.put(it.json().put("id", it.id)) }
        val editor = prefs.edit().putString("profiles", array.toString()).putString("profile_id", selected.id)
        selected.applyTo(editor)
        editor.apply()
    }
}
