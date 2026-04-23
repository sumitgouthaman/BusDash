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
    const val GEOFENCE_CHANNEL_ID = "geofence_proximity"
    const val GEOFENCE_NOTIFICATION_ID = 9001

    fun postCommuteNotification(
        context: Context,
        commute: CommuteEntry,
        formattedArrivals: List<String>
    ) {
        val contentText = if (formattedArrivals.isEmpty()) {
            "No upcoming departures found"
        } else {
            formattedArrivals.take(3).joinToString(" · ")
        }

        val expandedStyle = NotificationCompat.InboxStyle()
        formattedArrivals.forEach { expandedStyle.addLine(it) }
        expandedStyle.setSummaryText("Next hour · Route ${commute.routeShortName}")

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
            .setStyle(expandedStyle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setLocalOnly(true)
            .build()

        NotificationManagerCompat.from(context).notify(commute.id.hashCode(), notification)
    }

    fun postGeofenceNotification(
        context: Context,
        stopId: String,
        stopName: String,
        formattedLines: List<String>
    ) {
        val contentText = if (formattedLines.isEmpty()) {
            "No upcoming departures"
        } else {
            formattedLines.take(2).joinToString(" · ")
        }

        val expandedStyle = NotificationCompat.InboxStyle()
        val lines = if (formattedLines.isEmpty()) listOf("No upcoming departures") else formattedLines
        lines.forEach { expandedStyle.addLine(it) }
        expandedStyle.setSummaryText("Next 90 min · $stopName")

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("stopId", stopId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            GEOFENCE_NOTIFICATION_ID,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, GEOFENCE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Arrivals at $stopName")
            .setContentText(contentText)
            .setStyle(expandedStyle)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .setLocalOnly(true)
            .build()

        NotificationManagerCompat.from(context).cancelAll()
        NotificationManagerCompat.from(context).notify(GEOFENCE_NOTIFICATION_ID, notification)
    }

    fun cancelGeofenceNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(GEOFENCE_NOTIFICATION_ID)
    }
}
