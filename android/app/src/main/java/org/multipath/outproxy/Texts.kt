package org.multipath.outproxy

import android.content.Context
import java.util.Locale

object Texts {
    fun headline(c: Context, s: Status): String = c.getString(
        when (s.phase) {
            Phase.STOPPED -> R.string.phase_stopped
            Phase.STARTING -> R.string.phase_starting
            Phase.BOOTSTRAP -> if (s.external) R.string.phase_wait_router else R.string.phase_bootstrap
            Phase.TUNNELS -> R.string.phase_tunnels
            Phase.HANDSHAKE -> R.string.phase_handshake
            Phase.READY -> R.string.phase_ready
            Phase.STOPPING -> R.string.phase_stopping
            Phase.ERROR -> R.string.phase_error
        }
    )

    fun detail(c: Context, s: Status): String = if (s.external) externalDetail(c, s) else when (s.phase) {
        Phase.STOPPED -> c.getString(R.string.detail_stopped)
        Phase.STARTING -> c.getString(R.string.detail_starting)
        Phase.BOOTSTRAP -> c.getString(R.string.detail_bootstrap, s.routers)
        Phase.TUNNELS -> c.getString(R.string.detail_tunnels, s.clientTunnels, s.expectedTunnels)
        Phase.HANDSHAKE -> c.getString(R.string.detail_handshake, s.clientTunnels, s.expectedTunnels)
        Phase.READY -> c.getString(R.string.detail_ready, seconds(s.rttMs), s.streams)
        Phase.STOPPING -> ""
        Phase.ERROR -> s.error ?: ""
    }

    private fun externalDetail(c: Context, s: Status): String = when (s.phase) {
        Phase.STARTING -> c.getString(R.string.detail_starting_ext)
        Phase.BOOTSTRAP -> c.getString(R.string.detail_sam_unreachable, Settings(c).samAddress)
        Phase.TUNNELS -> c.getString(R.string.detail_sam_links, s.clientTunnels, s.links)
        Phase.HANDSHAKE -> c.getString(R.string.detail_sam_handshake, s.clientTunnels, s.links) +
            (s.linkError?.let { "\n" + c.getString(R.string.detail_last_error, it) } ?: "")
        Phase.READY -> c.getString(R.string.detail_ready, seconds(s.rttMs), s.streams)
        Phase.ERROR -> s.error ?: ""
        Phase.STOPPED -> c.getString(R.string.detail_stopped)
        Phase.STOPPING -> ""
    }

    fun seconds(ms: Long): String = String.format(Locale.US, "%.1f", ms / 1000.0)

    fun bytes(n: Long): String {
        if (n < 1024) return "$n B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = n / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format(Locale.US, if (v < 10) "%.1f %s" else "%.0f %s", v, units[i])
    }

    fun duration(ms: Long): String {
        val t = ms / 1000
        return String.format(Locale.US, "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60)
    }
}
