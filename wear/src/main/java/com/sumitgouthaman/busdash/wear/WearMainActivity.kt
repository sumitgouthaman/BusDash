package com.sumitgouthaman.busdash.wear

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.sumitgouthaman.busdash.wear.data.WearPreferences
import com.sumitgouthaman.busdash.wear.ui.screens.SetupPromptScreen
import com.sumitgouthaman.busdash.wear.ui.screens.WearDashboardScreen
import com.sumitgouthaman.busdash.wear.ui.theme.BusDashWearTheme

class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BusDashWearTheme {
                WearApp()
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
