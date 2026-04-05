package com.sumitgouthaman.busdash.wear.data

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
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

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d("WearConfig", "onDataChanged: ${dataEvents.count} events")
        val wearPreferences = WearPreferences(applicationContext)

        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path
                Log.d("WearConfig", "Data changed at path: $path")
                if (path == "/busdash-config") {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap

                    val apiKey = dataMap.getString("api_key", "")
                    val baseUrl = dataMap.getString("base_url", "")
                    val useMetric = dataMap.getBoolean("use_metric", false)
                    val starredStops = dataMap.getStringArray("starred_stops")?.toSet() ?: emptySet()
                    val starredRoutes = dataMap.getStringArray("starred_routes")?.toSet() ?: emptySet()

                    Log.d("WearConfig", "Received: metric=$useMetric, apiKey=${if (apiKey.isBlank()) "EMPTY" else "set(${apiKey.length})"}, baseUrl=$baseUrl, stops=${starredStops.size}, routes=${starredRoutes.size}")

                    scope.launch {
                        wearPreferences.updateFromPhone(
                            apiKey = apiKey,
                            baseUrl = baseUrl,
                            useMetricDistance = useMetric,
                            starredStops = starredStops,
                            starredRoutes = starredRoutes
                        )
                        Log.d("WearConfig", "Config saved to DataStore")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
