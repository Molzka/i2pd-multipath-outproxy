package org.multipath.outproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger

class OutproxyVpnService : VpnService() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "vpn-engine") }.apply {
        setRemoveOnCancelPolicy(true)
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
    }
    private val requests = AtomicInteger()
    @Volatile private var engine: Engine? = null
    @Volatile private var destroyed = false
    @Volatile private var latestStartId = 0
    private var engineRequest = 0
    private var engineStartId = 0
    private val listener: (Status) -> Unit = {
        refreshProtection()
        updateNotification(it)
    }
    private val networks = mutableSetOf<Network>()
    private var hadNetwork = false
    private var monitoringNetwork = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(next: Network) {
            if (!networks.add(next)) return
            val changed = hadNetwork
            hadNetwork = true
            notifyNetwork(true, changed)
        }
        override fun onLost(lost: Network) {
            if (!networks.remove(lost)) return
            val available = networks.isNotEmpty()
            notifyNetwork(available, available)
        }
    }

    private fun notifyNetwork(available: Boolean, changed: Boolean) {
        val current = engine ?: return
        try {
            worker.execute { if (!destroyed && engine === current) current.networkChanged(available, changed) }
        } catch (_: RejectedExecutionException) {}
    }

    private fun refreshProtection() {
        if (Build.VERSION.SDK_INT >= 29) {
            StatusBus.update { it.copy(alwaysOn = isAlwaysOn, lockdown = isLockdownEnabled) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, networkCallback, main)
            monitoringNetwork = true
        } catch (_: RuntimeException) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val request = requests.incrementAndGet()
        if (intent?.action == ACTION_STOP) {
            stopConnection(request, startId)
            return START_NOT_STICKY
        }
        val connectionSettings = Settings(this).snapshot()
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val initialNetwork = connectivity.activeNetwork
        val initialCapabilities = connectivity.getNetworkCapabilities(initialNetwork)
        val networkAvailable = networks.isNotEmpty() || (
            initialCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                initialCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            )
        goForeground(StatusBus.status)
        worker.execute {
            if (destroyed || request != requests.get()) return@execute
            if (engine?.isBlocking == true) {
                engineRequest = request
                engineStartId = startId
                engine?.start()
                return@execute
            }
            if (engine?.isRunning == true) {
                engineRequest = request
                engineStartId = startId
                return@execute
            }
            engine?.stop()
            engine = null
            try {
                if (!Settings.isValidAddress(connectionSettings.serverAddress)) {
                    throw IllegalStateException(getString(R.string.err_no_address))
                }
                if (prepare(this) != null) throw IllegalStateException(getString(R.string.err_permission))
                StatusBus.listen(listener)
                engineRequest = request
                engineStartId = startId
                lateinit var next: Engine
                next = Engine(this, worker, connectionSettings) {
                    if (engine === next) {
                        engine = null
                        finishRequest(engineRequest, engineStartId)
                    }
                }
                engine = next
                next.networkChanged(networkAvailable, false)
                if (destroyed || request != requests.get()) next.requestStop()
                next.start()
            } catch (e: Exception) {
                engine?.stop()
                engine = null
                StatusBus.log("app", "VPN start failed: $e")
                StatusBus.update { it.copy(phase = Phase.ERROR, error = e.message ?: e.toString()) }
                finishRequest(request, startId)
            }
        }
        return if (connectionSettings.autoReconnect) START_STICKY else START_NOT_STICKY
    }

    private fun stopConnection(request: Int, startId: Int) {
        engine?.requestStop()
        try {
            worker.execute {
                if (destroyed || request != requests.get()) return@execute
                engine?.stop()
                engine = null
                StatusBus.update { Status(links = it.links, expectedTunnels = it.expectedTunnels, external = it.external) }
                finishRequest(request, startId)
            }
        } catch (_: RejectedExecutionException) {}
    }

    private fun finishRequest(request: Int, startId: Int) {
        main.post {
            if (!destroyed && request == requests.get()) {
                StatusBus.unlisten(listener)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
        }
    }

    override fun onRevoke() {
        if (!destroyed) stopConnection(requests.incrementAndGet(), latestStartId)
    }

    override fun onDestroy() {
        destroyed = true
        if (monitoringNetwork) {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
            monitoringNetwork = false
        }
        requests.incrementAndGet()
        StatusBus.unlisten(listener)
        engine?.requestStop()
        worker.execute {
            try {
                engine?.stop()
            } finally {
                engine = null
                worker.shutdown()
            }
        }
        super.onDestroy()
    }

    private fun goForeground(s: Status) {
        val n = buildNotification(s)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private var lastTitle: String? = null
    private var lastDetail: String? = null
    private var lastNotificationAt = 0L

    private fun updateNotification(s: Status) {
        if (destroyed || s.phase == Phase.STOPPED || s.phase == Phase.ERROR) return
        val title = Texts.headline(this, s)
        val detail = Texts.detail(this, s)
        val now = SystemClock.elapsedRealtime()
        if (title == lastTitle && (detail == lastDetail || now - lastNotificationAt < 10_000)) return
        lastTitle = title
        lastDetail = detail
        lastNotificationAt = now
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(s))
    }

    private fun buildNotification(s: Status): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OutproxyVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val retry = PendingIntent.getService(
            this, 2, Intent(this, OutproxyVpnService::class.java).setAction(ACTION_START),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(Texts.headline(this, s))
            .setContentText(Texts.detail(this, s))
            .setContentIntent(MainActivity.pendingIntent(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.action_disconnect), stop).build())
        if (s.phase == Phase.BLOCKED) {
            notification.addAction(Notification.Action.Builder(null, getString(R.string.action_retry), retry).build())
        }
        return notification.build()
    }

    companion object {
        private const val CHANNEL = "vpn"
        private const val NOTIF_ID = 1
        const val ACTION_START = "org.multipath.outproxy.START"
        const val ACTION_STOP = "org.multipath.outproxy.STOP"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, OutproxyVpnService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, OutproxyVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
