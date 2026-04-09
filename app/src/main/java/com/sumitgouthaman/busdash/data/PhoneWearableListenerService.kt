package com.sumitgouthaman.busdash.data

import android.util.Log
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Phone-side Wearable listener that detects when the BusDash Wear OS app comes online
 * and proactively pushes the current configuration to it.
 *
 * This handles the gap in [WearDataSync], which only pushes when preferences *change*
 * while the phone app is open. Without this service, a user who installs the watch app
 * after last using the phone app would rely solely on the Data Layer having a previously
 * persisted item — which may not always be the case (e.g. after a phone factory reset).
 *
 * The Wear OS app advertises the "busdash_wear" capability (declared in its wear.xml).
 * When that capability appears on a connected node, [onCapabilityChanged] fires here
 * and triggers an immediate config push — even if the phone app is closed.
 */
class PhoneWearableListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "PhoneWearListener"
        private const val WEAR_CAPABILITY = "busdash_wear"
        private const val CONFIG_PATH = "/busdash-config"
    }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        if (capabilityInfo.name == WEAR_CAPABILITY && capabilityInfo.nodes.isNotEmpty()) {
            Log.d(TAG, "busdash_wear capability appeared on ${capabilityInfo.nodes.size} node(s) — pushing config")
            scope.launch { pushCurrentConfig() }
        }
    }

    private suspend fun pushCurrentConfig() {
        val appPreferences = AppPreferences(applicationContext)
        val apiKey = appPreferences.apiKey.first() ?: ""
        val baseUrl = appPreferences.baseUrl.first()
        val useMetric = appPreferences.useMetricDistance.first()
        val starredStops = appPreferences.starredStops.first()
        val starredRoutes = appPreferences.starredRoutes.first()

        try {
            val putDataMapReq = PutDataMapRequest.create(CONFIG_PATH).apply {
                dataMap.putString("api_key", apiKey)
                dataMap.putString("base_url", baseUrl)
                dataMap.putBoolean("use_metric", useMetric)
                dataMap.putStringArray("starred_stops", starredStops.toTypedArray())
                dataMap.putStringArray("starred_routes", starredRoutes.toTypedArray())
                dataMap.putLong("timestamp", System.currentTimeMillis())
            }
            val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
            Wearable.getDataClient(applicationContext).putDataItem(putDataReq).await()
            Log.d(TAG, "Config pushed to watch on capability change")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push config on capability change", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
