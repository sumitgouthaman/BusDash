package com.sumitgouthaman.busdash.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.sumitgouthaman.busdash.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CommuteAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_COMMUTE_ID = "commute_id"
        private const val TAG = "CommuteAlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val commuteId = intent.getStringExtra(EXTRA_COMMUTE_ID) ?: run {
            Log.w(TAG, "onReceive: missing commute_id extra")
            return
        }
        Log.d(TAG, "onReceive: commute_id=$commuteId")

        enqueueNotificationWork(context, commuteId)
        rescheduleAlarm(context, commuteId)
    }

    private fun enqueueNotificationWork(context: Context, commuteId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val inputData = workDataOf(
            CommuteNotificationWorker.KEY_COMMUTE_ID to commuteId,
            CommuteNotificationWorker.KEY_SCHEDULED_TIME_MS to System.currentTimeMillis()
        )
        val request = OneTimeWorkRequestBuilder<CommuteNotificationWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(commuteId, ExistingWorkPolicy.REPLACE, request)
        Log.d(TAG, "Enqueued CommuteNotificationWorker for $commuteId")
    }

    private fun rescheduleAlarm(context: Context, commuteId: String) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val prefs = AppPreferences(context)
                val commute = prefs.getCommuteById(commuteId)
                when {
                    commute == null -> Log.d(TAG, "Commute $commuteId not found, skipping reschedule")
                    !commute.enabled -> Log.d(TAG, "Commute $commuteId disabled, skipping reschedule")
                    else -> {
                        CommuteAlarmScheduler.schedule(context, commute)
                        Log.d(TAG, "Rescheduled alarm for $commuteId")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule alarm for $commuteId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
