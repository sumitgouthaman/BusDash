package com.sumitgouthaman.busdash

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sumitgouthaman.busdash.data.AppPreferences
import com.sumitgouthaman.busdash.data.WearDataSync
import com.sumitgouthaman.busdash.ui.screens.AddCommuteScreen
import com.sumitgouthaman.busdash.ui.screens.CommuteListScreen
import com.sumitgouthaman.busdash.ui.screens.DashboardScreen
import com.sumitgouthaman.busdash.ui.screens.DebugLogScreen
import com.sumitgouthaman.busdash.ui.screens.SettingsScreen
import com.sumitgouthaman.busdash.ui.screens.StopDetailsScreen
import com.sumitgouthaman.busdash.ui.theme.BusDashTheme
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
                onStopClick = { stopId, stopName, lat, lon ->
                    val encodedName = URLEncoder.encode(stopName, "UTF-8")
                    navController.navigate("stopDetails/$stopId/$lat/$lon?stopName=$encodedName")
                },
                onSettingsClick = {
                    navController.navigate("settings")
                },
                onCommuteListClick = {
                    navController.navigate("commuteList")
                }
            )
        }

        composable("settings") {
            SettingsScreen(
                onSaveSuccess = {
                    navController.navigate("dashboard") {
                        popUpTo(0)
                    }
                }
            )
        }

        composable(
            route = "stopDetails/{stopId}/{lat}/{lon}?stopName={stopName}",
            arguments = listOf(
                navArgument("stopId") { type = NavType.StringType },
                navArgument("lat") { type = NavType.StringType },
                navArgument("lon") { type = NavType.StringType },
                navArgument("stopName") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val stopId = backStackEntry.arguments?.getString("stopId") ?: return@composable
            val lat = backStackEntry.arguments?.getString("lat")?.toDoubleOrNull() ?: 0.0
            val lon = backStackEntry.arguments?.getString("lon")?.toDoubleOrNull() ?: 0.0
            val stopName = URLDecoder.decode(
                backStackEntry.arguments?.getString("stopName") ?: "", "UTF-8"
            )
            StopDetailsScreen(
                stopId = stopId,
                stopName = stopName,
                lat = lat,
                lon = lon,
                onBackClick = { navController.popBackStack() },
                onAddToCommute = { rId, rShort ->
                    val encodedStop = URLEncoder.encode(stopName, "UTF-8")
                    val encodedRoute = URLEncoder.encode(rShort, "UTF-8")
                    navController.navigate(
                        "addCommute?stopId=$stopId&stopName=$encodedStop&routeId=$rId&routeShortName=$encodedRoute"
                    )
                }
            )
        }

        composable("commuteList") {
            CommuteListScreen(
                onBackClick = { navController.popBackStack() },
                onAddClick = { navController.navigate("addCommute") },
                onEditClick = { commuteId -> navController.navigate("editCommute/$commuteId") },
                onDebugClick = { navController.navigate("debugLog") }
            )
        }

        composable("debugLog") {
            DebugLogScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(
            route = "addCommute?stopId={stopId}&stopName={stopName}&routeId={routeId}&routeShortName={routeShortName}",
            arguments = listOf(
                navArgument("stopId") { type = NavType.StringType; defaultValue = "" },
                navArgument("stopName") { type = NavType.StringType; defaultValue = "" },
                navArgument("routeId") { type = NavType.StringType; defaultValue = "" },
                navArgument("routeShortName") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val stopId = backStackEntry.arguments?.getString("stopId") ?: ""
            val stopName = URLDecoder.decode(
                backStackEntry.arguments?.getString("stopName") ?: "", "UTF-8"
            )
            val routeId = backStackEntry.arguments?.getString("routeId") ?: ""
            val routeShortName = URLDecoder.decode(
                backStackEntry.arguments?.getString("routeShortName") ?: "", "UTF-8"
            )
            AddCommuteScreen(
                prefillStopId = stopId,
                prefillStopName = stopName,
                prefillRouteId = routeId,
                prefillRouteShortName = routeShortName,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(
            route = "editCommute/{commuteId}",
            arguments = listOf(
                navArgument("commuteId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val commuteId = backStackEntry.arguments?.getString("commuteId") ?: return@composable
            AddCommuteScreen(
                editCommuteId = commuteId,
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
