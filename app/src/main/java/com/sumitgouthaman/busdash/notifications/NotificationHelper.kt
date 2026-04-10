package com.sumitgouthaman.busdash.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sumitgouthaman.busdash.MainActivity
import com.sumitgouthaman.busdash.data.CommuteEntry

object NotificationHelper {
    const val COMMUTE_CHANNEL_ID = "commute_alerts"

    fun postCommuteNotification(
        context: Context,
        commute: CommuteEntry,
        formattedArrivals: List<String>
    ) {
        val contentText = if (formattedArrivals.isEmpty()) {
            "No upcoming departures found"
        } else {
            formattedArrivals.joinToString(" · ")
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("stopId", commute.stopId)
            putExtra("stopLat", 0.0)
            putExtra("stopLon", 0.0)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            commute.id.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, COMMUTE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Route ${commute.routeShortName} – ${commute.stopName}")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setLocalOnly(true)
            .build()

        NotificationManagerCompat.from(context).notify(commute.id.hashCode(), notification)
    }
}
