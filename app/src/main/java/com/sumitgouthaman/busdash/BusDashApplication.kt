package com.sumitgouthaman.busdash

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.sumitgouthaman.busdash.notifications.NotificationHelper

class BusDashApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            NotificationHelper.COMMUTE_CHANNEL_ID,
            "Commute Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for your typical commute times"
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
