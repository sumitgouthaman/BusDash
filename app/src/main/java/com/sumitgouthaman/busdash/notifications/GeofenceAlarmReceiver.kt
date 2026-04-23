package com.sumitgouthaman.busdash.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

class GeofenceAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_STOP_ID = "stop_id"
        const val EXTRA_ENQUEUE_TIME = "enqueue_time"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val stopId = intent.getStringExtra(EXTRA_STOP_ID) ?: return
        val enqueueTime = intent.getLongExtra(EXTRA_ENQUEUE_TIME, System.currentTimeMillis())

        val request = OneTimeWorkRequestBuilder<GeofenceNotificationWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(workDataOf(
                GeofenceNotificationWorker.KEY_STOP_ID to stopId,
                GeofenceNotificationWorker.KEY_ENQUEUE_TIME to enqueueTime
            ))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                GeofenceBroadcastReceiver.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
    }
}
