package com.sumitgouthaman.busdash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sumitgouthaman.busdash.data.AppPreferences
import com.sumitgouthaman.busdash.data.WearDataSync
import com.sumitgouthaman.busdash.ui.screens.DashboardScreen
import com.sumitgouthaman.busdash.ui.screens.SettingsScreen
import com.sumitgouthaman.busdash.ui.screens.StopDetailsScreen
import com.sumitgouthaman.busdash.ui.theme.BusDashTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BusDashTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BusDashApp()
                }
            }
        }
    }
}

@Composable
fun BusDashApp() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val appPreferences = remember { AppPreferences(context) }
    
    val isConfigured by appPreferences.isConfigured.collectAsState(initial = null)

    // Start syncing config to Wear OS companion
    val wearDataSync = remember { WearDataSync(context, appPreferences) }
    LaunchedEffect(Unit) {
        wearDataSync.startSync()
    }

    // Wait until we know if it's configured
    if (isConfigured == null) {
        return
    }

    NavHost(
        navController = navController,
        startDestination = if (isConfigured == true) "dashboard" else "settings"
    ) {
        composable("dashboard") {
            DashboardScreen(
                onStopClick = { stopId, lat, lon ->
                    navController.navigate("stopDetails/$stopId/$lat/$lon")
                },
                onSettingsClick = {
                    navController.navigate("settings")
                }
            )
        }
        
        composable("settings") {
            SettingsScreen(
                onSaveSuccess = {
                    // Navigate back to dashboard and clear backstack
                    navController.navigate("dashboard") {
                        popUpTo(0)
                    }
                }
            )
        }
        
        composable("stopDetails/{stopId}/{lat}/{lon}") { backStackEntry ->
            val stopId = backStackEntry.arguments?.getString("stopId") ?: return@composable
            val lat = backStackEntry.arguments?.getString("lat")?.toDoubleOrNull() ?: 0.0
            val lon = backStackEntry.arguments?.getString("lon")?.toDoubleOrNull() ?: 0.0
            StopDetailsScreen(
                stopId = stopId,
                lat = lat,
                lon = lon,
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }
    }
}
