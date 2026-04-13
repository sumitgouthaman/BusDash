package com.sumitgouthaman.busdash.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.Wearable
import com.sumitgouthaman.busdash.data.AppPreferences
import com.sumitgouthaman.busdash.data.CommuteEntry
import com.sumitgouthaman.busdash.data.DebugLogger
import com.sumitgouthaman.busdash.data.LogLevel
import com.sumitgouthaman.busdash.data.ObaApiClient
import com.sumitgouthaman.busdash.data.effectiveDepartureTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class CommuteNotificationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_COMMUTE_ID = "commute_id"
        const val KEY_SCHEDULED_TIME_MS = "scheduled_time_ms"
        const val COMMUTE_ALERT_PATH = "/busdash-commute-alert"
        private val EXPIRY_MS = TimeUnit.MINUTES.toMillis(30)
        private val TIME_FORMAT = SimpleDateFormat("h:mm a", Locale.getDefault())
        private const val TAG = "CommuteNotificationWorker"
    }

    override suspend fun doWork(): Result {
        val commuteId = inputData.getString(KEY_COMMUTE_ID) ?: run {
            DebugLogger.log(
                applicationContext, LogLevel.ERROR, TAG,
                "Internal error: notification work started without a commute identifier"
            )
            return Result.failure()
        }
        val scheduledTimeMs = inputData.getLong(KEY_SCHEDULED_TIME_MS, 0L)

        val prefs = AppPreferences(applicationContext)

        // Fetch commute first so all subsequent log messages can include route/stop context
        val commute = prefs.getCommuteById(commuteId) ?: run {
            DebugLogger.log(
                applicationContext, LogLevel.WARN, TAG,
                "Could not find the commute to send a notification — it may have been deleted"
            )
            return Result.failure()
        }

        val label = "Route ${commute.routeShortName} at ${commute.stopName}"

        if (!commute.enabled) {
            DebugLogger.log(
                applicationContext, LogLevel.DEBUG, TAG,
                "Notification skipped — $label is disabled"
            )
            return Result.success()
        }

        val ageMin = (System.currentTimeMillis() - scheduledTimeMs) / 60_000
        if (System.currentTimeMillis() - scheduledTimeMs > EXPIRY_MS) {
            DebugLogger.log(
                applicationContext, LogLevel.WARN, TAG,
                "Notification skipped for $label — the alarm was triggered ${ageMin} min ago, which is too late to be useful"
            )
            return Result.success()
        }

        val apiKey = prefs.apiKey.first()
        val baseUrl = prefs.baseUrl.first()
        if (apiKey.isNullOrBlank()) {
            DebugLogger.log(
                applicationContext, LogLevel.WARN, TAG,
                "Notification skipped for $label — no API server key is configured in Settings"
            )
            return Result.failure()
        }

        val formattedArrivals = try {
            val api = ObaApiClient.create(baseUrl)
            val response = api.getArrivalsAndDeparturesForStop(commute.stopId, apiKey)
            val allArrivals = response.data.entry?.arrivalsAndDepartures ?: emptyList()
            allArrivals
                .filter { it.routeId == commute.routeId }
                .mapNotNull { it.effectiveDepartureTime() }
                .filter { it > System.currentTimeMillis() }
                .sorted()
                .take(3)
                .map { formatDepartureTime(it) }
        } catch (e: Exception) {
            DebugLogger.log(
                applicationContext, LogLevel.ERROR, TAG,
                "Could not fetch bus times for $label: ${e.message}"
            )
            return Result.retry()
        }

        NotificationHelper.postCommuteNotification(applicationContext, commute, formattedArrivals)
        DebugLogger.log(
            applicationContext, LogLevel.DEBUG, TAG,
            "Notification sent for $label (${formattedArrivals.size} upcoming times)"
        )
        sendWatchMessage(commute, formattedArrivals)
        return Result.success()
    }

    private fun formatDepartureTime(epochMs: Long): String =
        TIME_FORMAT.format(Date(epochMs))

    private suspend fun sendWatchMessage(commute: CommuteEntry, arrivals: List<String>) {
        try {
            val payload = JSONObject().apply {
                put("stopId", commute.stopId)
                put("stopName", commute.stopName)
                put("routeShortName", commute.routeShortName)
                put("arrivals", JSONArray(arrivals))
            }.toString()
            val nodes = Wearable.getNodeClient(applicationContext).connectedNodes.await()
            nodes.forEach { node ->
                Wearable.getMessageClient(applicationContext)
                    .sendMessage(node.id, COMMUTE_ALERT_PATH, payload.toByteArray())
                    .await()
            }
        } catch (e: Exception) {
            // Watch sync failure is non-critical; no debug log entry needed
        }
    }
}
