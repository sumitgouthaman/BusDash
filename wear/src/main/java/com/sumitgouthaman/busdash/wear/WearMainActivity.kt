package com.sumitgouthaman.busdash.wear

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
        const val EXTRA_PRIORITY_STOP_ID = "stopId"
    }

    private val priorityStopId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                0
            )
        }

        priorityStopId.value = intent?.getStringExtra(EXTRA_PRIORITY_STOP_ID)

        // Proactively pull any existing config from the Data Layer on startup.
        pullConfigFromDataLayer()

        setContent {
            BusDashWearTheme {
                WearApp(priorityStopId = priorityStopId.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        priorityStopId.value = intent.getStringExtra(EXTRA_PRIORITY_STOP_ID)
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
fun WearApp(priorityStopId: String? = null) {
    val context = LocalContext.current
    val wearPreferences = remember { WearPreferences(context) }
    val isConfigured by wearPreferences.isConfigured.collectAsState(initial = null)
    Log.d("WearApp", "isConfigured=$isConfigured, priorityStopId=$priorityStopId")

    when (isConfigured) {
        null -> {
            // Still loading preferences, show nothing
        }
        false -> {
            SetupPromptScreen()
        }
        true -> {
            WearDashboardScreen(priorityStopId = priorityStopId)
        }
    }
}
