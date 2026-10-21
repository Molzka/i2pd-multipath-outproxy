package org.multipath.outproxy

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class Phase { STOPPED, STARTING, BOOTSTRAP, TUNNELS, HANDSHAKE, READY, STOPPING, ERROR }

data class Status(
    val phase: Phase = Phase.STOPPED,
    val links: Int = 16,
    val clientTunnels: Int = 0,
    val expectedTunnels: Int = 64,
    val routers: Int = 0,
    val successRate: Int = -1,
    val rttMs: Long = 0,
    val up: Long = 0,
    val down: Long = 0,
    val streams: Int = 0,
    val startedAt: Long = 0,
    val readySince: Long = 0,
    val error: String? = null,
    val external: Boolean = false,
    val samReachable: Boolean = false,
    val linkError: String? = null,
)

object StatusBus {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Status) -> Unit>()
    private val logLines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    var status = Status()
        private set

    @Synchronized
    fun update(f: (Status) -> Status) {
        val next = f(status)
        if (next == status) return
        status = next
        main.post { listeners.forEach { it(next) } }
    }

    fun listen(l: (Status) -> Unit) {
        listeners += l
        l(status)
    }

    fun unlisten(l: (Status) -> Unit) {
        listeners -= l
    }

    private val ansi = Regex("\u001B\\[[0-9;]*m")
    private val i2pdPrefix = Regex("^\\d\\d:\\d\\d:\\d\\d@\\d+/")
    private val noise = listOf("Profiling: No profile yet", "SSU2: Socket receive buffer", "SSU2: Socket send buffer")

    private var lastKey = ""
    private var repeats = 0

    fun log(source: String, raw: String) {
        val line = raw.replace(ansi, "").replace(i2pdPrefix, "").trim().take(300)
        if (line.isEmpty() || noise.any { it in line }) return
        val key = "$source  $line"
        synchronized(logLines) {
            if (key == lastKey && logLines.isNotEmpty()) {
                repeats++
                logLines.removeLast()
                logLines.addLast("${fmt.format(Date())} $key  ×${repeats + 1}")
                return
            }
            lastKey = key
            repeats = 0
            logLines.addLast("${fmt.format(Date())} $key")
            while (logLines.size > 300) logLines.removeFirst()
        }
    }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }
}
