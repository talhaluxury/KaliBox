package com.kalibox.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.kalibox.app.service.KaliService
import com.kalibox.app.service.SessionHub
import com.kalibox.app.util.AppLog

class KaliApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        SessionHub.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(KaliService.CHANNEL, "KaliBox background", NotificationManager.IMPORTANCE_LOW),
        )
    }
}
