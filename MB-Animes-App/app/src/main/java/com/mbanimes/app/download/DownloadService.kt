package com.mbanimes.app.download

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mbanimes.app.MBApplication
import com.mbanimes.app.MainActivity
import com.mbanimes.app.R

/** Serviço em primeiro plano: mantém os downloads vivos com a notificação de progresso. */
class DownloadService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mbanimes:download").apply {
            setReferenceCounted(false)
            acquire(3 * 60 * 60 * 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIF_ID, build(this, getString(R.string.notif_text), 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 1001

        fun ensure(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        }

        /** Pequeno atraso para não parar o serviço antes de ele chamar startForeground. */
        fun stopLater(ctx: Context) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (!Downloads.busy()) ctx.stopService(Intent(ctx, DownloadService::class.java))
            }, 1500)
        }

        @SuppressLint("MissingPermission")
        fun progress(ctx: Context, title: String, pct: Int) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val text = if (title.isEmpty()) ctx.getString(R.string.notif_text) else "$title · $pct%"
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, build(ctx, text, pct))
        }

        private fun build(ctx: Context, text: String, pct: Int): Notification {
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return NotificationCompat.Builder(ctx, MBApplication.CHANNEL_DOWNLOADS)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(ctx.getString(R.string.notif_title))
                .setContentText(text)
                .setProgress(100, pct, pct == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build()
        }
    }
}
