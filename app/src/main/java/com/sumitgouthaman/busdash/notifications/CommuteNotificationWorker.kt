package com.sumitgouthaman.busdash.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.Wearable
import com.sumitgouthaman.busdash.data.AppPreferences
import com.sumitgouthaman.busdash.data.CommuteEntry
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
        val commuteId = inputData.getString(KEY_COMMUTE_ID) ?: return Result.failure()
        val scheduledTimeMs = inputData.getLong(KEY_SCHEDULED_TIME_MS, 0L)

        if (System.currentTimeMillis() - scheduledTimeMs > EXPIRY_MS) {
            Log.w(TAG, "Skipping stale notification for $commuteId (scheduled ${Date(scheduledTimeMs)})")
            return Result.success()
        }

        val prefs = AppPreferences(applicationContext)
        val commute = prefs.getCommuteById(commuteId) ?: run {
            Log.w(TAG, "Commute $commuteId not found")
            return Result.failure()
        }
        if (!commute.enabled) {
            Log.d(TAG, "Commute $commuteId is disabled, skipping")
            return Result.success()
        }

        val apiKey = prefs.apiKey.first()
        val baseUrl = prefs.baseUrl.first()
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "API key is blank, skipping notification")
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
            Log.e(TAG, "Failed to fetch arrivals for commute $commuteId", e)
            return Result.retry()
        }

        Log.d(TAG, "Posting notification with ${formattedArrivals.size} arrivals: $formattedArrivals")
        NotificationHelper.postCommuteNotification(applicationContext, commute, formattedArrivals)
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
            Log.w(TAG, "Failed to send watch message", e)
        }
    }
}
