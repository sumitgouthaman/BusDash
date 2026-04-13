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
import com.sumitgouthaman.busdash.data.DebugLogger
import com.sumitgouthaman.busdash.data.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CommuteAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_COMMUTE_ID = "commute_id"
        private const val TAG = "CommuteAlarmReceiver"
        private val DATE_FORMAT = SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault())
    }

    override fun onReceive(context: Context, intent: Intent) {
        val commuteId = intent.getStringExtra(EXTRA_COMMUTE_ID) ?: run {
            Log.w(TAG, "onReceive: missing commute_id extra")
            return
        }

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
    }

    private fun rescheduleAlarm(context: Context, commuteId: String) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val prefs = AppPreferences(context)
                val commute = prefs.getCommuteById(commuteId)
                when {
                    commute == null -> {
                        DebugLogger.log(
                            context, LogLevel.WARN, TAG,
                            "Alarm fired for a commute that no longer exists — it may have been deleted"
                        )
                    }
                    !commute.enabled -> {
                        DebugLogger.log(
                            context, LogLevel.DEBUG, TAG,
                            "Alarm skipped — Route ${commute.routeShortName} at ${commute.stopName} is disabled"
                        )
                    }
                    else -> {
                        val nextMs = CommuteAlarmScheduler.nextTriggerMs(commute)
                        CommuteAlarmScheduler.schedule(context, commute)
                        val nextLabel = nextMs?.let { DATE_FORMAT.format(Date(it)) } ?: "unknown"
                        DebugLogger.log(
                            context, LogLevel.DEBUG, TAG,
                            "Next alarm for Route ${commute.routeShortName} at ${commute.stopName} scheduled for $nextLabel"
                        )
                    }
                }
            } catch (e: Exception) {
                DebugLogger.log(
                    context, LogLevel.ERROR, TAG,
                    "Failed to reschedule alarm: ${e.message}"
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
