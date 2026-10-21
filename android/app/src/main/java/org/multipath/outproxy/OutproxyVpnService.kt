package org.multipath.outproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import java.util.concurrent.Executors

class OutproxyVpnService : VpnService() {
    private val worker = Executors.newSingleThreadExecutor()
    private var engine: Engine? = null
    private val listener: (Status) -> Unit = { updateNotification(it) }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            worker.execute {
                engine?.stop()
                engine = null
                StatusBus.unlisten(listener)
                stopForegroundCompat()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        goForeground(StatusBus.status)
        worker.execute {
            if (engine != null) return@execute
            if (!Settings.isValidAddress(Settings(this).serverAddress)) {
                StatusBus.update { it.copy(phase = Phase.ERROR, error = getString(R.string.err_no_address)) }
                stopForegroundCompat()
                stopSelf()
                return@execute
            }
            StatusBus.listen(listener)
            engine = Engine(this) {
                worker.execute {
                    engine = null
                    StatusBus.unlisten(listener)
                    stopForegroundCompat()
                    stopSelf()
                }
            }.also { it.start() }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        worker.execute {
            engine?.stop()
            engine = null
            stopForegroundCompat()
            stopSelf()
        }
    }

    override fun onDestroy() {
        StatusBus.unlisten(listener)
        engine?.stop()
        engine = null
        worker.shutdown()
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

    private fun stopForegroundCompat() = stopForeground(STOP_FOREGROUND_REMOVE)

    private var lastText: String? = null

    private fun updateNotification(s: Status) {
        if (s.phase == Phase.STOPPED || s.phase == Phase.ERROR) return
        val text = Texts.headline(this, s)
        if (text == lastText) return
        lastText = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(s))
    }

    private fun buildNotification(s: Status): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OutproxyVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(Texts.headline(this, s))
            .setContentText(Texts.detail(this, s))
            .setContentIntent(MainActivity.pendingIntent(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.action_disconnect), stop).build())
            .build()
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
