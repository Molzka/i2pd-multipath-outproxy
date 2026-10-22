package org.multipath.outproxy

import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class Engine(
    private val svc: VpnService,
    private val worker: ScheduledExecutorService,
    private val settings: ConnectionSettings,
    private val onFatal: () -> Unit,
) {
    private val binDir = File(svc.applicationInfo.nativeLibraryDir)
    private val dataDir = File(svc.filesDir, "i2pd")
    private val sockPath = File(svc.filesDir, "tun.sock").absolutePath

    private var tun: ParcelFileDescriptor? = null
    private val procs = mutableMapOf<String, Process>()
    private val restarts = mutableMapOf<String, Int>()
    private val startedAt = mutableMapOf<String, Long>()
    private val retryAfter = mutableMapOf<String, Long>()

    @Volatile
    private var running = false
    @Volatile
    private var cancelled = false
    @Volatile
    private var handoffSocket: LocalSocket? = null
    @Volatile
    private var monitor: ScheduledFuture<*>? = null
    private var pollErrorLogged = false
    private var nextPollAt = 0L

    val isRunning: Boolean get() = running && !cancelled

    private val links = settings.links
    private val external = settings.externalRouter
    private val expectedTunnels = if (external) links else links * 5
    private val processNames = if (external) listOf(SAM, AGG, MUX) else listOf(I2PD, AGG, MUX)

    fun start() {
        if (cancelled) return
        running = true
        StatusBus.update {
            Status(phase = Phase.STARTING, links = links, expectedTunnels = expectedTunnels,
                startedAt = System.currentTimeMillis(), external = external)
        }
        try {
            require(Settings.isValidAddress(settings.serverAddress)) { svc.getString(R.string.err_no_address) }
            killStale()
            prepareFiles()
            if (!running) return
            tun = establish()
            startAll()
            if (!running) return
            monitor = worker.scheduleWithFixedDelay({
                if (running) {
                    try {
                        checkProcesses()
                        val now = SystemClock.elapsedRealtime()
                        if (running && !external && now >= nextPollAt) {
                            pollI2pd()
                            nextPollAt = now + if (StatusBus.status.phase == Phase.READY) 10_000 else 2000
                        }
                    } catch (e: Exception) {
                        if (running) fail(e.message ?: e.toString())
                    }
                }
            }, 2, 2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            if (cancelled) return
            StatusBus.log("app", "start failed: $e")
            fail(e.message ?: e.toString())
        }
    }

    fun requestStop() {
        cancelled = true
        running = false
        monitor?.cancel(false)
        try { handoffSocket?.close() } catch (_: Exception) {}
    }

    fun stop() {
        val hadResources = running || synchronized(procs) { procs.isNotEmpty() } || tun != null
        requestStop()
        if (!hadResources) return
        StatusBus.update { it.copy(phase = Phase.STOPPING) }
        val processes = synchronized(procs) {
            processNames.reversed().mapNotNull { procs.remove(it) }
        }
        for (p in processes) try { p.destroy() } catch (_: Exception) {}
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        for (p in processes) stopProcess(p)
        StatusBus.update {
            Status(phase = Phase.STOPPED, links = links, expectedTunnels = expectedTunnels, external = external)
        }
    }

    private fun fail(msg: String) {
        if (cancelled) return
        stop()
        StatusBus.update { it.copy(phase = Phase.ERROR, error = msg) }
        onFatal()
    }


    private fun prepareFiles() {
        dataDir.mkdirs()
        File(dataDir, "tunnels.d").mkdirs()
        val versionMark = File(dataDir, ".assets-version")
        val version = svc.packageManager.getPackageInfo(svc.packageName, 0).let {
            @Suppress("DEPRECATION") it.versionCode.toString()
        } + ":" + svc.packageManager.getPackageInfo(svc.packageName, 0).lastUpdateTime
        if (!versionMark.exists() || versionMark.readText() != version) {
            File(dataDir, "certificates").deleteRecursively()
            copyAssets("certificates", File(dataDir, "certificates"))
            svc.assets.open("multipath.cfg").use { i -> File(dataDir, "multipath.cfg").outputStream().use { i.copyTo(it) } }
            versionMark.writeText(version)
        }
        File(dataDir, "i2pd.conf").writeText(i2pdConf())
        File(dataDir, "tunnels.conf").writeText(tunnelsConf())
    }

    private fun copyAssets(path: String, dst: File) {
        val children = svc.assets.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            dst.parentFile?.mkdirs()
            svc.assets.open(path).use { i -> dst.outputStream().use { i.copyTo(it) } }
            return
        }
        dst.mkdirs()
        for (c in children) copyAssets("$path/$c", File(dst, c))
    }

    private fun i2pdConf() = """
        |loglevel = warn
        |ipv4 = true
        |ipv6 = true
        |bandwidth = X
        |notransit = true
        |floodfill = false
        |
        |[http]
        |enabled = true
        |address = 127.0.0.1
        |port = ${settings.webPort}
        |auth = true
        |user = app
        |pass = ${settings.webPassword}
        |
        |[httpproxy]
        |enabled = false
        |[socksproxy]
        |enabled = false
        |[sam]
        |enabled = false
        |[bob]
        |enabled = false
        |[i2cp]
        |enabled = false
        |[i2pcontrol]
        |enabled = false
        |[upnp]
        |enabled = false
        |[nettime]
        |enabled = false
        |
        |[reseed]
        |verify = true
        |
        |[limits]
        |transittunnels = 0
        |
        |[persist]
        |profiles = true
        |addressbook = false
        |""".trimMargin()

    private fun tunnelsConf(): String {
        val dest = settings.serverAddress
        val hops = settings.hops
        val variance = if (hops > 1) -1 else 0
        return (1..links).joinToString("\n") { i ->
            """
            |[o$i]
            |type = client
            |address = 127.0.0.1
            |port = ${settings.basePort + i - 1}
            |destination = $dest
            |destinationport = 1337
            |keys = transient-o$i
            |inbound.quantity = 3
            |outbound.quantity = 2
            |inbound.length = $hops
            |outbound.length = $hops
            |inbound.lengthVariance = $variance
            |outbound.lengthVariance = $variance
            |i2p.streaming.maxResends = 3
            |i2p.streaming.initialAckDelay = 200
            |i2p.streaming.maxWindowSize = 512
            |i2p.streaming.answerPings = true
            |crypto.ratchet.inboundTags = 2400
            |""".trimMargin()
        }
    }


    private fun establish(): ParcelFileDescriptor {
        val b = svc.Builder()
            .setSession(svc.getString(R.string.app_name))
            .setMtu(settings.mtu)
            .addAddress("10.77.0.2", 30)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd77:77::2", 126)
            .addRoute("::", 0)
            .addDnsServer(settings.dnsServer)
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(settings.metered)
        try {
            b.addDisallowedApplication(svc.packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            throw IllegalStateException(e)
        }
        for (pkg in (Settings.ROUTER_PACKAGES + settings.excludedApps).distinct().filter { it != svc.packageName }) {
            try {
                b.addDisallowedApplication(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        b.setConfigureIntent(MainActivity.pendingIntent(svc))
        return b.establish() ?: throw IllegalStateException(svc.getString(R.string.err_vpn_denied))
    }


    private fun startAll() {
        for (name in processNames) {
            if (!running) return
            startProcess(name)
        }
    }

    private fun command(name: String): List<String> = when (name) {
        I2PD -> listOf(
            bin("libi2pd.so"),
            "--datadir=${dataDir.absolutePath}",
            "--conf=${File(dataDir, "i2pd.conf").absolutePath}",
            "--tunconf=${File(dataDir, "tunnels.conf").absolutePath}",
            "--tunnelsdir=${File(dataDir, "tunnels.d").absolutePath}",
            "--certsdir=${File(dataDir, "certificates").absolutePath}",
            "--pidfile=${File(dataDir, "i2pd.pid").absolutePath}",
            "--log=stdout",
        )
        AGG -> buildList {
            add(bin("libaggtunnel.so"))
            add("--cfg"); add(File(dataDir, "multipath.cfg").absolutePath)
            add("client")
            for (i in 0 until links) { add("--tcp"); add("127.0.0.1:${settings.basePort + i}") }
            add("-p"); add("1081:${settings.muxPort}")
            add("--tcp-link-filter"); add("none")
            add("-4"); add("-n")
        }
        SAM -> listOf(
            bin("libi2pmux.so"), "sam",
            "-sam", settings.samAddress,
            "-dest", settings.serverAddress,
            "-links", links.toString(),
            "-listen-base", settings.basePort.toString(),
            "-hops", settings.hops.toString(),
            "-watch-stdin",
        )
        MUX -> listOf(
            bin("libi2pmux.so"), "vpn",
            "-fd-sock", sockPath,
            "-upstream", "127.0.0.1:${settings.muxPort}",
            "-mtu", settings.mtu.toString(),
        )
        else -> error(name)
    }

    private fun bin(name: String) = File(binDir, name).absolutePath

    private fun startProcess(name: String) {
        if (!running) return
        val p = ProcessBuilder(command(name))
            .directory(dataDir)
            .redirectErrorStream(true)
            .start()
        synchronized(procs) { procs[name] = p }
        startedAt[name] = SystemClock.elapsedRealtime()
        Thread({ readOutput(name, p) }, "out-$name").start()
        try {
            if (name == MUX) sendTunFd()
        } catch (e: Exception) {
            p.destroyForcibly()
            throw e
        }
    }

    private fun readOutput(name: String, p: Process) {
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                if (line.startsWith("{") && (name == MUX || name == SAM)) {
                    try {
                        worker.execute {
                            if (running && synchronized(procs) { procs[name] === p } && p.isAliveCompat()) {
                                if (name == MUX) onMuxStatus(line) else onSamStatus(line)
                            }
                        }
                    } catch (_: RejectedExecutionException) {}
                } else {
                    StatusBus.log(name, line)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun sendTunFd() {
        val fd = tun?.fileDescriptor ?: return
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (running) {
            try {
                LocalSocket().use { s ->
                    handoffSocket = s
                    if (!running) return
                    s.connect(LocalSocketAddress(sockPath, LocalSocketAddress.Namespace.FILESYSTEM))
                    s.soTimeout = 1000
                    s.setFileDescriptorsForSend(arrayOf(fd))
                    s.outputStream.write(1)
                    check(s.inputStream.read() == 1) { "TUN handoff was not acknowledged" }
                }
                return
            } catch (e: Exception) {
                if (!running) return
                if (SystemClock.elapsedRealtime() > deadline) throw IllegalStateException("i2pmux: $e")
                Thread.sleep(100)
            } finally {
                handoffSocket = null
            }
        }
    }

    private fun stopProcess(p: Process) {
        try {
            if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) p.destroyForcibly()
        } catch (_: InterruptedException) {
            p.destroyForcibly()
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            p.destroyForcibly()
        }
    }

    private fun killStale() {
        val prefix = binDir.absolutePath
        File("/proc").listFiles { f -> f.name.all(Char::isDigit) }?.forEach { dir ->
            try {
                val cmd = File(dir, "cmdline").readText()
                if (cmd.startsWith(prefix)) {
                    Os.kill(dir.name.toInt(), OsConstants.SIGKILL)
                    StatusBus.log("app", "killed stale ${cmd.substringBefore('\u0000').substringAfterLast('/')}")
                }
            } catch (_: Exception) {
            }
        }
    }


    private fun checkProcesses() {
        val now = SystemClock.elapsedRealtime()
        for (name in processNames) {
            if (!running) return
            val p = synchronized(procs) { procs[name] }
            if (p?.isAliveCompat() == true) {
                if (now - (startedAt[name] ?: now) >= 60_000) restarts.remove(name)
                continue
            }
            if (!retryAfter.containsKey(name)) {
                if (p != null) {
                    StatusBus.log("app", "$name exited (${p.exitValue()})")
                    synchronized(procs) { procs.remove(name) }
                    markUnavailable(name)
                }
                if (!scheduleRetry(name, now)) return
            }
            if (now < (retryAfter[name] ?: now)) continue
            retryAfter.remove(name)
            try {
                startProcess(name)
            } catch (e: Exception) {
                if (!running) return
                StatusBus.log("app", "restart $name failed: $e")
                synchronized(procs) { procs.remove(name) }?.let {
                    it.destroyForcibly()
                    stopProcess(it)
                }
                if (!scheduleRetry(name, SystemClock.elapsedRealtime())) return
            }
        }
    }

    private fun markUnavailable(name: String) {
        StatusBus.update { s ->
            val next = s.copy(
                readySince = 0,
                streams = 0,
                rttMs = 0,
                clientTunnels = if (name == I2PD || name == SAM) 0 else s.clientTunnels,
                routers = if (name == I2PD) 0 else s.routers,
                samReachable = if (name == SAM) false else s.samReachable,
            )
            next.copy(phase = phaseOf(next))
        }
    }

    private fun scheduleRetry(name: String, now: Long): Boolean {
        if (!running) return false
        val attempts = (restarts[name] ?: 0) + 1
        restarts[name] = attempts
        if (!settings.autoReconnect || attempts > 20) {
            fail(svc.getString(if (settings.autoReconnect) R.string.err_crashing else R.string.err_process_stopped, name))
            return false
        }
        retryAfter[name] = now + minOf(1000L * attempts, 10_000L)
        StatusBus.log("app", "$name restart #$attempts scheduled")
        return true
    }

    private fun pollI2pd() {
        val html = try {
            val c = URL("http://127.0.0.1:${settings.webPort}/").openConnection() as HttpURLConnection
            c.connectTimeout = 1500
            c.readTimeout = 3000
            val token = Base64.encodeToString("app:${settings.webPassword}".toByteArray(), Base64.NO_WRAP)
            c.setRequestProperty("Authorization", "Basic $token")
            try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        } catch (e: Exception) {
            if (e !is java.net.ConnectException && !pollErrorLogged) {
                pollErrorLogged = true
                StatusBus.log("app", "i2pd status: $e")
            }
            return
        }
        val routers = I2pdPage.routers(html)
        val tunnels = I2pdPage.clientTunnels(html)
        val rate = I2pdPage.successRate(html)
        if (!running) return
        StatusBus.update { s ->
            val next = s.copy(
                routers = routers ?: s.routers,
                clientTunnels = tunnels ?: s.clientTunnels,
                successRate = rate ?: s.successRate,
            )
            next.copy(phase = phaseOf(next))
        }
    }

    private fun onMuxStatus(line: String) {
        val j = try { JSONObject(line) } catch (_: Exception) { return }
        val state = j.optString("state")
        StatusBus.update { s ->
            val ready = state == "ready" && synchronized(procs) {
                processNames.all { procs[it]?.isAliveCompat() == true }
            }
            val next = s.copy(
                rttMs = j.optLong("rtt_ms"),
                up = j.optLong("up"),
                down = j.optLong("down"),
                streams = j.optInt("streams"),
                readySince = if (ready) (if (s.readySince == 0L) System.currentTimeMillis() else s.readySince) else 0L,
                error = null,
            )
            next.copy(phase = phaseOf(next))
        }
    }

    private fun onSamStatus(line: String) {
        val j = try { JSONObject(line) } catch (_: Exception) { return }
        val err = j.optString("error").ifEmpty { null }
        if (err != null && err != lastSamError) StatusBus.log(SAM, err)
        lastSamError = err
        StatusBus.update { s ->
            val next = s.copy(
                samReachable = j.optString("sam") == "ok",
                clientTunnels = j.optInt("ready"),
                linkError = err,
            )
            next.copy(phase = phaseOf(next))
        }
    }

    private var lastSamError: String? = null

    private fun phaseOf(s: Status): Phase = when {
        !running -> s.phase
        s.readySince > 0 -> Phase.READY
        external && !s.samReachable -> Phase.BOOTSTRAP
        !external && s.routers < 50 -> Phase.BOOTSTRAP
        external && s.clientTunnels == 0 -> Phase.TUNNELS
        !external && s.clientTunnels < links -> Phase.TUNNELS
        else -> Phase.HANDSHAKE
    }

    companion object {
        const val SAM = "sam"
        const val I2PD = "i2pd"
        const val AGG = "agg-tunnel"
        const val MUX = "i2pmux"
    }
}

private fun Process.isAliveCompat(): Boolean = try {
    exitValue(); false
} catch (_: IllegalThreadStateException) {
    true
}
