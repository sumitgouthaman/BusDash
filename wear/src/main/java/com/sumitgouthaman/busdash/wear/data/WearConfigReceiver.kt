package com.sumitgouthaman.busdash.wear.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.sumitgouthaman.busdash.wear.WearMainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Listens for DataItem changes and Messages from the phone app via the Wearable Data Layer.
 * - DATA_CHANGED at /busdash-config: writes config + commutes to WearPreferences
 * - MESSAGE_RECEIVED at /busdash-commute-alert: creates a local watch notification
 */
class WearConfigReceiver : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val CONFIG_PATH = "/busdash-config"
        const val COMMUTE_ALERT_PATH = "/busdash-commute-alert"
        const val COMMUTE_CHANNEL_ID = "wear_commute_alerts"
        private const val TAG = "WearConfigReceiver"

        suspend fun applyDataMap(context: Context, dataMap: DataMap) {
            val apiKey = dataMap.getString("api_key", "")
            val baseUrl = dataMap.getString("base_url", "")
            val useMetric = dataMap.getBoolean("use_metric", false)
            val starredStops = dataMap.getStringArray("starred_stops")?.toSet() ?: emptySet()
            val starredRoutes = dataMap.getStringArray("starred_routes")?.toSet() ?: emptySet()
            val commutesJson = dataMap.getString("commutes_json", "[]")
            Log.d(TAG, "Applying: metric=$useMetric, apiKey=${if (apiKey.isBlank()) "EMPTY" else "set(${apiKey.length})"}, baseUrl=$baseUrl, stops=${starredStops.size}, routes=${starredRoutes.size}")
            WearPreferences(context).updateFromPhone(
                apiKey = apiKey,
                baseUrl = baseUrl,
                useMetricDistance = useMetric,
                starredStops = starredStops,
                starredRoutes = starredRoutes,
                commutesJson = commutesJson
            )
            Log.d(TAG, "Config saved to DataStore")
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d(TAG, "onDataChanged: ${dataEvents.count} events")
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == CONFIG_PATH) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                scope.launch {
                    applyDataMap(applicationContext, dataMap)
                }
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == COMMUTE_ALERT_PATH) {
            val payload = String(messageEvent.data)
            Log.d(TAG, "Received commute alert message: $payload")
            scope.launch {
                handleCommuteAlert(applicationContext, payload)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            COMMUTE_CHANNEL_ID,
            "Commute Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Upcoming bus departure notifications"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun handleCommuteAlert(context: Context, payload: String) {
        try {
            val json = JSONObject(payload)
            val stopId = json.getString("stopId")
            val stopName = json.getString("stopName")
            val routeShortName = json.getString("routeShortName")
            val arrivalsArray = json.getJSONArray("arrivals")
            val arrivals = (0 until arrivalsArray.length()).map { arrivalsArray.getString(it) }

            val contentText = if (arrivals.isEmpty()) "No upcoming departures"
            else "Next: ${arrivals.joinToString(", ")}"

            val tapIntent = Intent(context, WearMainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(WearMainActivity.EXTRA_PRIORITY_STOP_ID, stopId)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                stopId.hashCode(),
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, COMMUTE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Route $routeShortName – $stopName")
                .setContentText(contentText)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(context).notify(stopId.hashCode(), notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle commute alert", e)
        }
    }
}
