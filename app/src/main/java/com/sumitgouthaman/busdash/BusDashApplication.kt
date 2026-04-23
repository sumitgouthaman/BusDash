package com.sumitgouthaman.busdash

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.sumitgouthaman.busdash.notifications.NotificationHelper

class BusDashApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                NotificationHelper.COMMUTE_CHANNEL_ID,
                "Commute Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Notifications for your typical commute times" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                NotificationHelper.GEOFENCE_CHANNEL_ID,
                "Nearby Stop Arrivals",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Live bus arrivals when you're near a starred stop" }
        )
    }
}
