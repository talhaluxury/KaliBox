package com.kalibox.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kalibox.app.MainActivity
import com.kalibox.app.R
import java.util.concurrent.atomic.AtomicInteger

class KaliService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            DesktopController.stop()
            SessionHub.closeAll()
            tasks.set(0)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KaliBox:service").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, KaliService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("KaliBox is running")
            .setContentText("Terminal / desktop / installer active")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Open", open)
            .addAction(0, "Stop all", stop)
            .build()
    }

    companion object {
        const val CHANNEL = "kalibox"
        const val ACTION_STOP_ALL = "com.kalibox.app.STOP_ALL"
        private const val NOTIF_ID = 1
        private val tasks = AtomicInteger(0)

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, KaliService::class.java))
        }
        fun taskStarted(ctx: Context) { tasks.incrementAndGet(); start(ctx) }
        fun taskFinished(ctx: Context) { tasks.decrementAndGet(); refresh(ctx) }

        /** Stops the service when nothing is running anymore. */
        fun refresh(ctx: Context) {
            val idle = tasks.get() <= 0 && SessionHub.sessions.value.isEmpty() &&
                (DesktopController.state.value == DesktopState.STOPPED || DesktopController.state.value == DesktopState.ERROR)
            if (idle) ctx.stopService(Intent(ctx, KaliService::class.java))
        }
    }
}
