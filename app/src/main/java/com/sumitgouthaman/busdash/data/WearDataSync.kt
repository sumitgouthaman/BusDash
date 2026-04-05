package com.sumitgouthaman.busdash.data

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Syncs the phone app's configuration to connected Wear OS devices
 * via the Wearable Data Layer API.
 *
 * Call [startSync] from the phone's MainActivity to begin observing
 * preference changes and automatically pushing them to the watch.
 */
class WearDataSync(
    private val context: Context,
    private val appPreferences: AppPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataClient = Wearable.getDataClient(context)

    companion object {
        private const val TAG = "WearDataSync"
        private const val CONFIG_PATH = "/busdash-config"
    }

    /**
     * Starts observing preference changes and syncing to the watch.
     * Should be called once from the phone's activity lifecycle.
     */
    fun startSync() {
        scope.launch {
            // Combine all preference flows and push whenever any changes
            combine(
                appPreferences.apiKey,
                appPreferences.baseUrl,
                appPreferences.useMetricDistance,
                appPreferences.starredStops,
                appPreferences.starredRoutes
            ) { apiKey, baseUrl, useMetric, starredStops, starredRoutes ->
                ConfigSnapshot(
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    useMetricDistance = useMetric,
                    starredStops = starredStops,
                    starredRoutes = starredRoutes
                )
            }.collect { snapshot ->
                pushToWear(snapshot)
            }
        }
    }

    private suspend fun pushToWear(snapshot: ConfigSnapshot) {
        try {
            val putDataMapReq = PutDataMapRequest.create(CONFIG_PATH).apply {
                dataMap.putString("api_key", snapshot.apiKey ?: "")
                dataMap.putString("base_url", snapshot.baseUrl)
                dataMap.putBoolean("use_metric", snapshot.useMetricDistance)
                dataMap.putStringArray("starred_stops", snapshot.starredStops.toTypedArray())
                dataMap.putStringArray("starred_routes", snapshot.starredRoutes.toTypedArray())
                // Force update even if data hasn't changed by including a timestamp
                dataMap.putLong("timestamp", System.currentTimeMillis())
            }
            val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
            dataClient.putDataItem(putDataReq).await()
            Log.d(TAG, "Synced config to watch: stops=${snapshot.starredStops.size}, routes=${snapshot.starredRoutes.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync config to watch", e)
        }
    }

    private data class ConfigSnapshot(
        val apiKey: String?,
        val baseUrl: String,
        val useMetricDistance: Boolean,
        val starredStops: Set<String>,
        val starredRoutes: Set<String>
    )
}
