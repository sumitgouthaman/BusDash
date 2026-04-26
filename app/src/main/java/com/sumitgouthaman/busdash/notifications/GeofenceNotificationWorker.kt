package com.sumitgouthaman.busdash.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sumitgouthaman.busdash.data.AppPreferences
import com.sumitgouthaman.busdash.data.DebugLogger
import com.sumitgouthaman.busdash.data.LogLevel
import com.sumitgouthaman.busdash.data.ObaApiClient
import com.sumitgouthaman.busdash.data.effectiveDepartureTime
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class GeofenceNotificationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_STOP_ID = "stop_id"
        const val KEY_ENQUEUE_TIME = "enqueue_time"
        const val GEOFENCE_ALERT_PATH = "/busdash-geofence-alert"
        const val GEOFENCE_CANCEL_PATH = "/busdash-geofence-cancel"
        private val LOOKAHEAD_MS = TimeUnit.MINUTES.toMillis(90)
        private val EXPIRY_MS = TimeUnit.MINUTES.toMillis(5)
        private const val TAG = "GeofenceNotificationWorker"
        private val TIME_FORMAT = SimpleDateFormat("h:mm a", Locale.getDefault())
    }

    override suspend fun doWork(): Result {
        val stopId = inputData.getString(KEY_STOP_ID) ?: run {
            DebugLogger.log(applicationContext, LogLevel.ERROR, TAG, "Missing stop_id input")
            return Result.failure()
        }

        val enqueueTime = inputData.getLong(KEY_ENQUEUE_TIME, 0L)
        val now = System.currentTimeMillis()
        if (enqueueTime > 0 && now - enqueueTime > EXPIRY_MS) {
            DebugLogger.log(
                applicationContext, LogLevel.WARN, TAG,
                "Geofence notification for $stopId expired — triggered ${(now - enqueueTime) / 60_000}m ago, skipping"
            )
            return Result.failure()
        }

        val prefs = AppPreferences(applicationContext)
        val apiKey = prefs.apiKey.first()
        if (apiKey.isNullOrBlank()) {
            DebugLogger.log(
                applicationContext, LogLevel.WARN, TAG,
                "Geofence notification skipped for $stopId — no API key configured"
            )
            return Result.failure()
        }
        val baseUrl = prefs.baseUrl.first()
        val stopName = prefs.getStarredStopDetails().find { it.stopId == stopId }?.name ?: stopId

        val formattedLines = try {
            val arrivals = ObaApiClient.create(baseUrl)
                .getArrivalsAndDeparturesForStop(stopId, apiKey, minutesAfter = 90)
                .data.entry?.arrivalsAndDepartures ?: emptyList()

            arrivals
                .filter {
                    val t = it.effectiveDepartureTime() ?: return@filter false
                    t > now && t <= now + LOOKAHEAD_MS
                }
                .groupBy { it.routeShortName }
                .entries
                .sortedBy { e -> e.value.minOf { a -> a.effectiveDepartureTime() ?: Long.MAX_VALUE } }
                .take(4)
                .map { (route, items) ->
                    val times = items
                        .sortedBy { it.effectiveDepartureTime() }
                        .mapNotNull { a ->
                            val t = a.effectiveDepartureTime() ?: return@mapNotNull null
                            TIME_FORMAT.format(Date(t))
                        }
                    "Route $route: ${times.joinToString(", ")}"
                }
        } catch (e: Exception) {
            DebugLogger.log(
                applicationContext, LogLevel.ERROR, TAG,
                "Failed to fetch arrivals for $stopId: ${e.message}"
            )
            return Result.retry()
        }

        NotificationHelper.postGeofenceNotification(applicationContext, stopId, stopName, formattedLines)
        sendWatchAlert(stopId, stopName, formattedLines)
        DebugLogger.log(
            applicationContext, LogLevel.DEBUG, TAG,
            "Geofence notification posted for $stopName ($stopId) — ${formattedLines.size} route(s)"
        )
        return Result.success()
    }

    private suspend fun sendWatchAlert(stopId: String, stopName: String, formattedLines: List<String>) {
        try {
            val payload = JSONObject().apply {
                put("stopId", stopId)
                put("stopName", stopName)
                put("formattedLines", JSONArray(formattedLines))
            }.toString()
            val nodes = Wearable.getNodeClient(applicationContext).connectedNodes.await()
            nodes.forEach { node ->
                Wearable.getMessageClient(applicationContext)
                    .sendMessage(node.id, GEOFENCE_ALERT_PATH, payload.toByteArray())
                    .await()
            }
        } catch (_: Exception) {}
    }
}
