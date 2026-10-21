package org.multipath.outproxy

import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class Engine(private val svc: VpnService, private val onFatal: () -> Unit) {
    private val settings = Settings(svc)
    private val binDir = File(svc.applicationInfo.nativeLibraryDir)
    private val dataDir = File(svc.filesDir, "i2pd")
    private val sockPath = File(svc.filesDir, "tun.sock").absolutePath

    private var tun: ParcelFileDescriptor? = null
    private val procs = mutableMapOf<String, Process>()
    private val restarts = mutableMapOf<String, Int>()

    @Volatile
    private var running = false
    private var pollErrorLogged = false
    private var monitor: Thread? = null

    private val links = settings.links
    private val external = settings.externalRouter
    private val expectedTunnels = if (external) links else links * 5
    private val processNames = if (external) listOf(SAM, AGG, MUX) else listOf(I2PD, AGG, MUX)

    fun start() {
        running = true
        StatusBus.update {
            Status(phase = Phase.STARTING, links = links, expectedTunnels = expectedTunnels,
                startedAt = System.currentTimeMillis(), external = external)
        }
        try {
            killStale()
            prepareFiles()
            tun = establish()
            startAll()
        } catch (e: Exception) {
            StatusBus.log("app", "start failed: $e")
            fail(e.message ?: e.toString())
            return
        }
        monitor = Thread(::monitorLoop, "monitor").also { it.start() }
    }

    fun stop() {
        if (!running && procs.isEmpty() && tun == null) return
        running = false
        StatusBus.update { it.copy(phase = Phase.STOPPING) }
        monitor?.interrupt()
        synchronized(procs) {
            for (name in processNames.reversed()) procs.remove(name)?.let { stopProcess(it) }
        }
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        StatusBus.update {
            Status(phase = Phase.STOPPED, links = links, expectedTunnels = expectedTunnels, external = external)
        }
    }

    private fun fail(msg: String) {
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
            .setMtu(MTU)
            .addAddress("10.77.0.2", 30)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd77:77::2", 126)
            .addRoute("::", 0)
            .addDnsServer(DNS)
        try {
            b.addDisallowedApplication(svc.packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            throw IllegalStateException(e)
        }
        for (pkg in Settings.ROUTER_PACKAGES) {
            try {
                b.addDisallowedApplication(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        b.setConfigureIntent(MainActivity.pendingIntent(svc))
        return b.establish() ?: throw IllegalStateException(svc.getString(R.string.err_vpn_denied))
    }


    private fun startAll() = processNames.forEach(::startProcess)

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
            "-mtu", MTU.toString(),
        )
        else -> error(name)
    }

    private fun bin(name: String) = File(binDir, name).absolutePath

    private fun startProcess(name: String) {
        val p = ProcessBuilder(command(name))
            .directory(dataDir)
            .redirectErrorStream(true)
            .start()
        synchronized(procs) { procs[name] = p }
        Thread({ readOutput(name, p) }, "out-$name").start()
        if (name == MUX) sendTunFd()
    }

    private fun readOutput(name: String, p: Process) {
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                when {
                    name == MUX && line.startsWith("{") -> onMuxStatus(line)
                    name == SAM && line.startsWith("{") -> onSamStatus(line)
                    else -> StatusBus.log(name, line)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun sendTunFd() {
        val fd = tun?.fileDescriptor ?: return
        val deadline = System.currentTimeMillis() + 20_000
        while (running) {
            try {
                LocalSocket().use { s ->
                    s.connect(LocalSocketAddress(sockPath, LocalSocketAddress.Namespace.FILESYSTEM))
                    s.setFileDescriptorsForSend(arrayOf(fd))
                    s.outputStream.write(1)
                    s.inputStream.read()
                }
                return
            } catch (e: Exception) {
                if (System.currentTimeMillis() > deadline) throw IllegalStateException("i2pmux: $e")
                Thread.sleep(100)
            }
        }
    }

    private fun stopProcess(p: Process) {
        p.destroy()
        for (i in 0 until 50) {
            if (!p.isAliveCompat()) return
            Thread.sleep(100)
        }
        p.destroyForcibly()
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


    private fun monitorLoop() {
        while (running) {
            try {
                Thread.sleep(if (StatusBus.status.phase == Phase.READY) 10_000 else 2000)
            } catch (_: InterruptedException) {
                return
            }
            checkProcesses()
            if (!external) pollI2pd()
        }
    }

    private fun checkProcesses() {
        for (name in processNames) {
            val p = synchronized(procs) { procs[name] } ?: continue
            if (p.isAliveCompat() || !running) continue
            val n = (restarts[name] ?: 0) + 1
            restarts[name] = n
            StatusBus.log("app", "$name exited (${p.exitValue()}), restart #$n")
            if (n > 20) {
                fail(svc.getString(R.string.err_crashing, name))
                return
            }
            try {
                Thread.sleep(minOf(1000L * n, 10_000L))
                if (running) startProcess(name)
            } catch (e: Exception) {
                StatusBus.log("app", "restart $name failed: $e")
            }
        }
    }

    private fun pollI2pd() {
        val html = try {
            val c = URL("http://127.0.0.1:${settings.webPort}/").openConnection() as HttpURLConnection
            c.connectTimeout = 1500
            c.readTimeout = 3000
            val token = Base64.encodeToString("app:${settings.webPassword}".toByteArray(), Base64.NO_WRAP)
            c.setRequestProperty("Authorization", "Basic $token")
            c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
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
            val ready = state == "ready"
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
        const val MTU = 1500
        const val DNS = "1.1.1.1"
    }
}

private fun Process.isAliveCompat(): Boolean = try {
    exitValue(); false
} catch (_: IllegalThreadStateException) {
    true
}
