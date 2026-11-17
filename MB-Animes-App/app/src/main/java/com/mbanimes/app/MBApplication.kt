package com.mbanimes.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.mbanimes.app.download.DownloadStore

class MBApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Net.init(this)
        Session.init(this)
        DownloadStore.init(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_DOWNLOADS,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
    }
}
