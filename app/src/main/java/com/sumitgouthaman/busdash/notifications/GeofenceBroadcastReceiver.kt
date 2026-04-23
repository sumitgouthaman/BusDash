package com.sumitgouthaman.busdash.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val WORK_NAME = "geofence_notification"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        val stopIds = event.triggeringGeofences?.map { it.requestId } ?: return

        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                val stopId = stopIds.firstOrNull() ?: return
                // Fire an immediate setExactAndAllowWhileIdle alarm so the WorkManager job
                // runs inside the Doze exemption window that alarm opens, matching the
                // commute notification pattern that is known to work with Doze + DNS.
                val alarmIntent = Intent(context, GeofenceAlarmReceiver::class.java).apply {
                    putExtra(GeofenceAlarmReceiver.EXTRA_STOP_ID, stopId)
                    putExtra(GeofenceAlarmReceiver.EXTRA_ENQUEUE_TIME, System.currentTimeMillis())
                }
                val pi = PendingIntent.getBroadcast(
                    context,
                    stopId.hashCode(),
                    alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val am = context.getSystemService(AlarmManager::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime(),
                        pi
                    )
                } else {
                    am.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime(),
                        pi
                    )
                }
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                NotificationHelper.cancelGeofenceNotification(context)
                val pr = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                        nodes.forEach { node ->
                            Wearable.getMessageClient(context)
                                .sendMessage(node.id, GeofenceNotificationWorker.GEOFENCE_CANCEL_PATH, ByteArray(0))
                                .await()
                        }
                    } catch (_: Exception) {}
                    pr.finish()
                }
            }
        }
    }
}
