package com.sumitgouthaman.busdash.wear.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.sumitgouthaman.busdash.wear.data.LocationHelper
import com.sumitgouthaman.busdash.wear.data.ObaArrivalAndDeparture
import com.sumitgouthaman.busdash.wear.data.ObaStop
import com.sumitgouthaman.busdash.wear.data.OneBusAwayApi
import com.sumitgouthaman.busdash.wear.data.WearPreferences
import com.sumitgouthaman.busdash.wear.ui.theme.TransitAmber
import com.sumitgouthaman.busdash.wear.ui.theme.TransitOnSurfaceDim
import com.sumitgouthaman.busdash.wear.ui.utils.FormatUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

// --- ViewModel ---

data class WearStopWithDistance(val stop: ObaStop, val distanceMeters: Float)

sealed class WearDashboardUiState {
    object Loading : WearDashboardUiState()
    data class Success(
        val stops: List<WearStopWithDistance>,
        val starredIds: Set<String>,
        val starredRoutes: Set<String>
    ) : WearDashboardUiState()
    data class Error(val message: String) : WearDashboardUiState()
}

class WearDashboardViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<WearDashboardUiState>(WearDashboardUiState.Loading)
    val uiState: StateFlow<WearDashboardUiState> = _uiState

    companion object {
        private const val TAG = "WearDashVM"
    }

    private var obaApi: OneBusAwayApi? = null
    private var obaApiKey: String? = null

    // Arrivals cache
    private val arrivalsCache = mutableMapOf<String, CachedArrivals>()
    private val ARRIVALS_CACHE_TTL = 60 * 1000L

    private data class CachedArrivals(
        val arrivals: List<ObaArrivalAndDeparture>,
        val timestamp: Long
    )

    @SuppressLint("MissingPermission")
    fun loadData(wearPreferences: WearPreferences, locationHelper: LocationHelper) {
        Log.d(TAG, "loadData called")
        viewModelScope.launch {
            _uiState.value = WearDashboardUiState.Loading
            try {
                val apiKey = wearPreferences.apiKey.first()
                val baseUrl = wearPreferences.baseUrl.first()
                Log.d(TAG, "Config: apiKey=${if (apiKey.isNullOrBlank()) "EMPTY" else "set(${apiKey.length} chars)"}, baseUrl=$baseUrl")

                if (apiKey.isNullOrBlank()) {
                    Log.e(TAG, "API key is blank, showing not configured")
                    _uiState.value = WearDashboardUiState.Error("Not configured")
                    return@launch
                }

                if (obaApi == null) {
                    Log.d(TAG, "Creating Retrofit instance for $baseUrl")
                    obaApi = Retrofit.Builder()
                        .baseUrl(baseUrl)
                        .addConverterFactory(GsonConverterFactory.create())
                        .build()
                        .create(OneBusAwayApi::class.java)
                }
                val api = obaApi!!
                obaApiKey = apiKey

                Log.d(TAG, "Getting location...")
                val location = locationHelper.getCurrentLocation()
                if (location == null) {
                    Log.e(TAG, "Location is null!")
                    _uiState.value = WearDashboardUiState.Error("No location")
                    return@launch
                }
                Log.d(TAG, "Location: lat=${location.latitude}, lon=${location.longitude}")

                // Fetch stops with backoff
                var backoff = 1000L
                var sortedStops: List<WearStopWithDistance>? = null
                while (sortedStops == null) {
                    try {
                        Log.d(TAG, "Fetching stops for lat=${location.latitude}, lon=${location.longitude}")
                        val response = api.getStopsForLocation(
                            key = apiKey,
                            lat = location.latitude,
                            lon = location.longitude
                        )
                        Log.d(TAG, "API response: code=${response.code}, limitExceeded=${response.data.limitExceeded}")
                        if (response.data.limitExceeded) {
                            Log.w(TAG, "Rate limited, backing off ${backoff}ms")
                            delay(backoff)
                            backoff = (backoff * 2).coerceAtMost(60_000L)
                            continue
                        }
                        val rawStops = response.data.list ?: emptyList()
                        Log.d(TAG, "Got ${rawStops.size} raw stops from API")
                        sortedStops = rawStops.map { stop ->
                            val stopLocation = android.location.Location("").apply {
                                latitude = stop.lat
                                longitude = stop.lon
                            }
                            WearStopWithDistance(stop, location.distanceTo(stopLocation))
                        }.sortedBy { it.distanceMeters }
                    } catch (e: retrofit2.HttpException) {
                        Log.e(TAG, "HTTP error: ${e.code()} ${e.message()}")
                        if (e.code() == 429) {
                            delay(backoff)
                            backoff = (backoff * 2).coerceAtMost(60_000L)
                        } else throw e
                    }
                }

                Log.d(TAG, "Total sorted stops: ${sortedStops.size}")

                // Observe starred stops & routes to filter
                wearPreferences.starredStops.collectLatest { starredIds ->
                    val starredRoutes = wearPreferences.starredRoutes.first()
                    val starredStops = sortedStops.filter { it.stop.id in starredIds }
                    val otherStops = sortedStops.filterNot { it.stop.id in starredIds }.take(3)
                    val displayStops = if (starredStops.isNotEmpty()) starredStops else otherStops
                    Log.d(TAG, "Display: starred=${starredStops.size}, other=${otherStops.size}, showing=${displayStops.size}, starredIds=$starredIds")
                    _uiState.value = WearDashboardUiState.Success(displayStops, starredIds, starredRoutes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadData failed", e)
                _uiState.value = WearDashboardUiState.Error(e.message ?: "Error")
            }
        }
    }

    fun getCachedArrivals(stopId: String): Pair<List<ObaArrivalAndDeparture>, Long>? {
        val cached = arrivalsCache[stopId]
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < ARRIVALS_CACHE_TTL) {
            return cached.arrivals to cached.timestamp
        }
        return null
    }

    suspend fun getArrivalsForStop(stopId: String, force: Boolean = false): Pair<List<ObaArrivalAndDeparture>, Long>? {
        if (!force) {
            val cached = getCachedArrivals(stopId)
            if (cached != null) return cached
        }
        
        val api = obaApi ?: return null
        val key = obaApiKey ?: return null
        return try {
            val response = api.getArrivalsAndDeparturesForStop(stopId = stopId, key = key)
            val arrivals = response.data.entry?.arrivalsAndDepartures ?: emptyList()
            val now = System.currentTimeMillis()
            arrivalsCache[stopId] = CachedArrivals(arrivals, now)
            arrivals to now
        } catch (e: Exception) {
            val oldCached = arrivalsCache[stopId]
            if (oldCached != null) oldCached.arrivals to oldCached.timestamp else null
        }
    }
}

// --- Dashboard Screen ---

@Composable
fun WearDashboardScreen() {
    val context = LocalContext.current
    val wearPreferences = remember { WearPreferences(context) }
    val locationHelper = remember { LocationHelper(context) }
    val viewModel: WearDashboardViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val useMetric by wearPreferences.useMetricDistance.collectAsState(initial = false)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            val granted = permissions.entries.all { it.value }
            Log.d("WearDash", "Permission result: granted=$granted, details=$permissions")
            if (granted) {
                viewModel.loadData(wearPreferences, locationHelper)
            } else {
                Log.e("WearDash", "Location permissions denied!")
            }
        }
    )

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    when (val state = uiState) {
        is WearDashboardUiState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        is WearDashboardUiState.Error -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }
        is WearDashboardUiState.Success -> {
            WearDashboardContent(
                stops = state.stops,
                starredIds = state.starredIds,
                starredRoutes = state.starredRoutes,
                useMetric = useMetric,
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun WearDashboardContent(
    stops: List<WearStopWithDistance>,
    starredIds: Set<String>,
    starredRoutes: Set<String>,
    useMetric: Boolean,
    viewModel: WearDashboardViewModel
) {
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize()
    ) {
        // Title
        item {
            Text(
                text = if (stops.any { it.stop.id in starredIds }) "★ Starred" else "Nearby",
                style = MaterialTheme.typography.titleLarge,
                color = if (stops.any { it.stop.id in starredIds }) TransitAmber else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        if (stops.isEmpty()) {
            item {
                Text(
                    text = "No stops found",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TransitOnSurfaceDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                )
            }
        } else {
            items(
                items = stops,
                key = { it.stop.id }
            ) { stopWD ->
                WearStopCard(
                    stopWithDistance = stopWD,
                    isStarred = stopWD.stop.id in starredIds,
                    starredRoutes = starredRoutes,
                    useMetric = useMetric,
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
private fun WearStopCard(
    stopWithDistance: WearStopWithDistance,
    isStarred: Boolean,
    starredRoutes: Set<String>,
    useMetric: Boolean,
    viewModel: WearDashboardViewModel
) {
    val initialCache = viewModel.getCachedArrivals(stopWithDistance.stop.id)
    var arrivals by remember(stopWithDistance.stop.id) { 
        mutableStateOf<List<ObaArrivalAndDeparture>?>(initialCache?.first) 
    }
    var lastFetchTime by remember(stopWithDistance.stop.id) {
        mutableStateOf<Long?>(initialCache?.second)
    }
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        refreshTrigger++
        var lastTriggerTime = System.currentTimeMillis()
        while (true) {
            delay(1_000L)
            currentTime = System.currentTimeMillis()
            if (currentTime - lastTriggerTime >= 60_000L) {
                refreshTrigger++
                lastTriggerTime = currentTime
            }
        }
    }

    LaunchedEffect(refreshTrigger) {
        val isForced = refreshTrigger > 1
        val result = viewModel.getArrivalsForStop(stopWithDistance.stop.id, isForced)
        if (result != null) {
            arrivals = result.first
            lastFetchTime = result.second
        }
    }

    val haptic = LocalHapticFeedback.current

    Card(
        onClick = {},
        onLongClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            refreshTrigger += 2
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Direction label (matched to phone app)
            val direction = stopWithDistance.stop.direction
            val directionFull = when (direction?.uppercase()) {
                "N" -> "Northbound"
                "S" -> "Southbound"
                "E" -> "Eastbound"
                "W" -> "Westbound"
                "NE" -> "Northeast"
                "NW" -> "Northwest"
                "SE" -> "Southeast"
                "SW" -> "Southwest"
                else -> direction
            }
            if (!directionFull.isNullOrBlank()) {
                Text(
                    text = directionFull.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
            }

            // Stop name row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isStarred) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = TransitAmber
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = stopWithDistance.stop.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            // Distance
            Text(
                text = FormatUtils.formatDistance(stopWithDistance.distanceMeters, useMetric),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Arrival times
            val arrivalsList = arrivals
            if (arrivalsList == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp).align(Alignment.CenterHorizontally)
                )
            } else if (arrivalsList.isEmpty()) {
                Text(
                    text = "No arrivals",
                    style = MaterialTheme.typography.bodySmall,
                    color = TransitOnSurfaceDim
                )
            } else {
                // Group by route, show top 3 routes with next arrival each
                val groupedByRoute = remember(arrivalsList, starredRoutes) {
                    arrivalsList
                        .groupBy { it.routeId }
                        .map { (routeId, group) ->
                            val sorted = group.sortedBy {
                                if (it.predictedDepartureTime > 0) it.predictedDepartureTime
                                else it.scheduledDepartureTime
                            }
                            val isRouteStarred = starredRoutes.contains("${stopWithDistance.stop.id}_${routeId}")
                            Triple(sorted.first(), sorted.take(2), isRouteStarred)
                        }
                        .sortedWith(
                            compareByDescending<Triple<ObaArrivalAndDeparture, List<ObaArrivalAndDeparture>, Boolean>> { it.third }
                                .thenBy {
                                    val a = it.first
                                    if (a.predictedDepartureTime > 0) a.predictedDepartureTime
                                    else a.scheduledDepartureTime
                                }
                        )
                        .take(3)
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    groupedByRoute.forEach { (_, routeArrivals, isRouteStarred) ->
                        WearArrivalRow(
                            arrivals = routeArrivals,
                            isStarred = isRouteStarred
                        )
                    }
                }
            }

            // Footer
            if (lastFetchTime != null) {
                Spacer(modifier = Modifier.height(10.dp))
                val diff: Long = currentTime - lastFetchTime!!
                val staleness = when {
                    diff < 60000L -> "${diff / 1000L}s ago"
                    diff < 3600000L -> "${diff / 60000L}m ago"
                    else -> "${diff / 3600000L}h ago"
                }
                Text(
                    text = "Updated $staleness",
                    style = MaterialTheme.typography.labelSmall,
                    color = TransitOnSurfaceDim,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun WearArrivalRow(
    arrivals: List<ObaArrivalAndDeparture>,
    isStarred: Boolean
) {
    val first = arrivals.first()

    Column(modifier = Modifier.fillMaxWidth()) {
        // Route badge + destination
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isStarred) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = first.routeShortName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isStarred) MaterialTheme.colorScheme.onPrimaryContainer
                           else MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = first.tripHeadsign,
                style = MaterialTheme.typography.labelSmall,
                color = TransitOnSurfaceDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(3.dp))

        // Arrival times row
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(start = 2.dp)
        ) {
            arrivals.forEach { arrival ->
                val time = if (arrival.predictedDepartureTime > 0)
                    arrival.predictedDepartureTime else arrival.scheduledDepartureTime
                val minutes = ((time - System.currentTimeMillis()) / 60000).coerceAtLeast(0)

                Text(
                    text = "${minutes}m",
                    fontSize = 14.sp,
                    fontWeight = if (minutes <= 5) FontWeight.ExtraBold else FontWeight.Bold,
                    color = if (minutes <= 5) MaterialTheme.colorScheme.error
                           else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
