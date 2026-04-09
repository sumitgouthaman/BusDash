package com.sumitgouthaman.busdash.wear

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.sumitgouthaman.busdash.wear.data.WearConfigReceiver
import com.sumitgouthaman.busdash.wear.data.WearPreferences
import com.sumitgouthaman.busdash.wear.ui.screens.SetupPromptScreen
import com.sumitgouthaman.busdash.wear.ui.screens.WearDashboardScreen
import com.sumitgouthaman.busdash.wear.ui.theme.BusDashWearTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class WearMainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "WearMain"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Proactively pull any existing config from the Data Layer on startup.
        //
        // WearConfigReceiver.onDataChanged only fires when data changes *after* the listener
        // is registered. A freshly installed watch app won't receive DataItems that the phone
        // already pushed before installation. Calling getDataItems() reads the locally cached
        // replica directly — no push event needed, and no dependency on the phone being online.
        pullConfigFromDataLayer()

        setContent {
            BusDashWearTheme {
                WearApp()
            }
        }
    }

    private fun pullConfigFromDataLayer() {
        val uri = Uri.parse("wear://*${WearConfigReceiver.CONFIG_PATH}")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dataItems = Wearable.getDataClient(applicationContext).getDataItems(uri).await()
                dataItems.use { buffer ->
                    buffer.forEach { item ->
                        Log.d(TAG, "Found existing config in Data Layer — applying on startup")
                        WearConfigReceiver.applyDataMap(
                            applicationContext,
                            DataMapItem.fromDataItem(item).dataMap
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not pull config from Data Layer on startup", e)
            }
        }
    }
}

@Composable
fun WearApp() {
    val context = LocalContext.current
    val wearPreferences = remember { WearPreferences(context) }
    val isConfigured by wearPreferences.isConfigured.collectAsState(initial = null)
    Log.d("WearApp", "isConfigured=$isConfigured")

    when (isConfigured) {
        null -> {
            // Still loading preferences, show nothing
        }
        false -> {
            SetupPromptScreen()
        }
        true -> {
            WearDashboardScreen()
        }
    }
}
