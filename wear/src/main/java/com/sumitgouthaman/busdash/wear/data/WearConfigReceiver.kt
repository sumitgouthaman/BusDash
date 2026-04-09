package com.sumitgouthaman.busdash.wear.data

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Listens for DataItem changes from the phone app via the Wearable Data Layer.
 * When the phone pushes config updates to /busdash-config, this service
 * writes them into the local WearPreferences DataStore.
 */
class WearConfigReceiver : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val CONFIG_PATH = "/busdash-config"

        /**
         * Parses a DataMap from the phone's config payload and writes it to [WearPreferences].
         * Used by both [WearConfigReceiver.onDataChanged] and [WearMainActivity.pullConfigFromDataLayer]
         * to keep parsing in one place.
         *
         * Safe to call from a coroutine: [DataMapItem.fromDataItem] produces an independent copy
         * of the data, not a buffer-backed reference.
         */
        suspend fun applyDataMap(context: Context, dataMap: DataMap) {
            val apiKey = dataMap.getString("api_key", "")
            val baseUrl = dataMap.getString("base_url", "")
            val useMetric = dataMap.getBoolean("use_metric", false)
            val starredStops = dataMap.getStringArray("starred_stops")?.toSet() ?: emptySet()
            val starredRoutes = dataMap.getStringArray("starred_routes")?.toSet() ?: emptySet()
            Log.d("WearConfig", "Applying: metric=$useMetric, apiKey=${if (apiKey.isBlank()) "EMPTY" else "set(${apiKey.length})"}, baseUrl=$baseUrl, stops=${starredStops.size}, routes=${starredRoutes.size}")
            WearPreferences(context).updateFromPhone(
                apiKey = apiKey,
                baseUrl = baseUrl,
                useMetricDistance = useMetric,
                starredStops = starredStops,
                starredRoutes = starredRoutes
            )
            Log.d("WearConfig", "Config saved to DataStore")
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d("WearConfig", "onDataChanged: ${dataEvents.count} events")
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == CONFIG_PATH) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                scope.launch {
                    applyDataMap(applicationContext, dataMap)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
