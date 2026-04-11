package com.sumitgouthaman.busdash.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sumitgouthaman.busdash.data.AppPreferences
import com.sumitgouthaman.busdash.data.LocationHelper
import com.sumitgouthaman.busdash.data.ObaApiClient
import com.sumitgouthaman.busdash.data.ObaStop
import com.sumitgouthaman.busdash.data.ObaArrivalAndDeparture
import com.sumitgouthaman.busdash.data.OneBusAwayApi
import com.sumitgouthaman.busdash.data.effectiveDepartureTime
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.ui.unit.sp
import com.sumitgouthaman.busdash.ui.utils.FormatUtils
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.location.Location

class DashboardViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private var globalRefreshTrigger = 0

    private var obaApi: OneBusAwayApi? = null
    private var obaApiKey: String? = null

    // Stops cache
    private var cachedStops: List<StopWithDistance>? = null
    private var stopsCacheTime: Long = 0L
    private val STOPS_CACHE_TTL = 5 * 60 * 1000L // 5 minutes
    private var lastLocation: Location? = null
    private var loadDataJob: kotlinx.coroutines.Job? = null

    // Arrivals cache
    private val arrivalsCache = mutableMapOf<String, CachedArrivals>()
    private val ARRIVALS_CACHE_TTL = 60 * 1000L // 60 seconds

    private data class CachedArrivals(
        val arrivals: List<ObaArrivalAndDeparture>,
        val timestamp: Long
    )

    @SuppressLint("MissingPermission")
    fun loadData(appPreferences: AppPreferences, locationHelper: LocationHelper, force: Boolean = false) {
        loadDataJob?.cancel()
        loadDataJob = viewModelScope.launch {
            _isRefreshing.value = true
            if (force) {
                globalRefreshTrigger++
                arrivalsCache.clear()
            }
            val cached = cachedStops
            if (cached != null) {
                val starredIds = appPreferences.starredStops.first()
                val starredStops = cached.filter { it.stop.id in starredIds }
                val otherStops = cached.filterNot { it.stop.id in starredIds }
                _uiState.value = DashboardUiState.Success(starredStops, otherStops, starredIds, globalRefreshTrigger)
                
                if (!force && (System.currentTimeMillis() - stopsCacheTime) < STOPS_CACHE_TTL) {
                    // Only start collection if we're not hitting the API
                    _isRefreshing.value = false
                    appPreferences.starredStops.collectLatest { freshStarredIds ->
                        val fStarredStops = cached.filter { it.stop.id in freshStarredIds }
                        val fOtherStops = cached.filterNot { it.stop.id in freshStarredIds }
                        _uiState.value = DashboardUiState.Success(fStarredStops, fOtherStops, freshStarredIds, globalRefreshTrigger)
                    }
                    return@launch
                }
            } else {
                if (_uiState.value !is DashboardUiState.Success) {
                    _uiState.value = DashboardUiState.Loading
                }
            }

            try {
                val apiKey = appPreferences.apiKey.first()
                val baseUrl = appPreferences.baseUrl.first()

                if (apiKey.isNullOrBlank()) {
                    _uiState.value = DashboardUiState.Error("API Key not configured")
                    return@launch
                }

                if (obaApi == null) {
                    obaApi = ObaApiClient.create(baseUrl)
                }
                val api = obaApi!!
                obaApiKey = apiKey

                val location = locationHelper.getCurrentLocation()
                if (location == null) {
                    if (cached == null) _uiState.value = DashboardUiState.Error("Could not get location")
                    return@launch
                }
                if (!force && lastLocation != null && location.distanceTo(lastLocation!!) < 50f) {
                    if (cached != null && (System.currentTimeMillis() - stopsCacheTime) < STOPS_CACHE_TTL) {
                        // Re-bind collection
                        _isRefreshing.value = false
                        appPreferences.starredStops.collectLatest { sIds ->
                            _uiState.value = DashboardUiState.Success(
                                cached.filter { it.stop.id in sIds },
                                cached.filterNot { it.stop.id in sIds },
                                sIds,
                                globalRefreshTrigger
                            )
                        }
                        return@launch
                    }
                }
                lastLocation = location

                // Fetch stops with backoff on 429
                var backoff = 1000L
                var sortedStops: List<StopWithDistance>? = null
                while (sortedStops == null) {
                    try {
                        val response = api.getStopsForLocation(key = apiKey, lat = location.latitude, lon = location.longitude)
                        if (response.data.limitExceeded) {
                            kotlinx.coroutines.delay(backoff)
                            backoff = (backoff * 2).coerceAtMost(60_000L)
                            continue
                        }
                        val rawStops = response.data.list ?: emptyList()
                        sortedStops = rawStops.map { stop ->
                            val stopLocation = android.location.Location("").apply {
                                latitude = stop.lat
                                longitude = stop.lon
                            }
                            StopWithDistance(stop, location.distanceTo(stopLocation))
                        }.sortedBy { it.distanceMeters }
                    } catch (e: retrofit2.HttpException) {
                        if (e.code() == 429) {
                            kotlinx.coroutines.delay(backoff)
                            backoff = (backoff * 2).coerceAtMost(60_000L)
                        } else {
                            throw e
                        }
                    }
                }

                // Cache the result
                cachedStops = sortedStops
                stopsCacheTime = System.currentTimeMillis()

                _isRefreshing.value = false
                val starredFlow = appPreferences.starredStops
                starredFlow.collectLatest { starredIds ->
                    val starredStops = sortedStops.filter { it.stop.id in starredIds }
                    val otherStops = sortedStops.filterNot { it.stop.id in starredIds }
                    _uiState.value = DashboardUiState.Success(starredStops, otherStops, starredIds, globalRefreshTrigger)
                }

            } catch (e: Exception) {
                _uiState.value = DashboardUiState.Error(e.message ?: "Unknown Error")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun toggleStar(appPreferences: AppPreferences, stopId: String) {
        viewModelScope.launch {
            appPreferences.toggleStarredStop(stopId)
        }
    }

    suspend fun getArrivalsForStop(stopId: String, force: Boolean = false): FetchResult {
        // Check cache first if not forced
        val cached = arrivalsCache[stopId]
        if (!force && cached != null && (System.currentTimeMillis() - cached.timestamp) < ARRIVALS_CACHE_TTL) {
            return FetchResult.Success(cached.arrivals, cached.timestamp)
        }

        val api = obaApi ?: return FetchResult.Error
        val key = obaApiKey ?: return FetchResult.Error
        return try {
            val response = api.getArrivalsAndDeparturesForStop(stopId = stopId, key = key)
            if (response.data.limitExceeded) {
                // Return cached data if available, otherwise rate limited
                if (cached != null) FetchResult.Success(cached.arrivals, cached.timestamp) else FetchResult.RateLimited
            } else {
                val arrivals = response.data.entry?.arrivalsAndDepartures ?: emptyList()
                val now = System.currentTimeMillis()
                arrivalsCache[stopId] = CachedArrivals(arrivals, now)
                FetchResult.Success(arrivals, now)
            }
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 429) {
                if (cached != null) FetchResult.Success(cached.arrivals, cached.timestamp) else FetchResult.RateLimited
            } else {
                FetchResult.Error
            }
        } catch (e: Exception) {
            e.printStackTrace()
            FetchResult.Error
        }
    }
}

data class StopWithDistance(val stop: ObaStop, val distanceMeters: Float)

sealed class FetchResult {
    data class Success(val arrivals: List<ObaArrivalAndDeparture>, val timestamp: Long) : FetchResult()
    object RateLimited : FetchResult()
    object Error : FetchResult()
}

sealed class DashboardUiState {
    object Loading : DashboardUiState()
    data class Success(
        val starredStops: List<StopWithDistance>,
        val otherStops: List<StopWithDistance>,
        val starredIds: Set<String>,
        val refreshTrigger: Int = 0
    ) : DashboardUiState()
    data class Error(val message: String) : DashboardUiState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onStopClick: (String, String, Double, Double) -> Unit,
    onSettingsClick: () -> Unit,
    onCommuteListClick: () -> Unit
) {
    val context = LocalContext.current
    val appPreferences = remember { AppPreferences(context) }
    val locationHelper = remember { LocationHelper(context) }
    val viewModel: DashboardViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val useMetric by appPreferences.useMetricDistance.collectAsState(initial = false)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            val granted = permissions.entries.all { it.value }
            if (granted) {
                viewModel.loadData(appPreferences, locationHelper)
            }
        }
    )

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (hasFine || hasCoarse) {
                    viewModel.loadData(appPreferences, locationHelper, force = true)
                } else {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "BusDash",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary
                ),
                actions = {
                    IconButton(onClick = onCommuteListClick) {
                        Icon(
                            imageVector = Icons.Filled.Schedule,
                            contentDescription = "Typical Commutes",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        val pullToRefreshState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = isRefreshing && uiState !is DashboardUiState.Loading,
            onRefresh = { viewModel.loadData(appPreferences, locationHelper, force = true) },
            state = pullToRefreshState,
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            when (val state = uiState) {
                is DashboardUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is DashboardUiState.Error -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = { viewModel.loadData(appPreferences, locationHelper) }) {
                            Text("Retry")
                        }
                    }
                }
                is DashboardUiState.Success -> {
                    var visibleOtherCount by remember { mutableIntStateOf(6) }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (state.starredStops.isNotEmpty()) {
                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                                ) {
                                    Text(
                                        text = "★",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Starred Stops Nearby",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                            items(
                                count = state.starredStops.size,
                                key = { state.starredStops[it].stop.id }
                            ) { index ->
                                val stopWD = state.starredStops[index]
                                StopItemRow(
                                    stopWithDistance = stopWD,
                                    isStarred = true,
                                    appPreferences = appPreferences,
                                    useMetric = useMetric,
                                    loadPriority = index,
                                    externalRefreshTrigger = state.refreshTrigger,
                                    fetchArrivals = { force -> viewModel.getArrivalsForStop(stopWD.stop.id, force) },
                                    onClick = { onStopClick(stopWD.stop.id, stopWD.stop.name, stopWD.stop.lat, stopWD.stop.lon) },
                                    onStarClick = { viewModel.toggleStar(appPreferences, stopWD.stop.id) }
                                )
                            }
                        }

                        if (state.otherStops.isNotEmpty()) {
                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                                ) {
                                    if (state.starredStops.isEmpty()) {
                                        Text(
                                            text = "◎",
                                            style = MaterialTheme.typography.titleLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(
                                        text = "Nearby Stops",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            
                            val visibleOtherStops = state.otherStops.take(visibleOtherCount)
                            val starredCount = state.starredStops.size
                            items(
                                count = visibleOtherStops.size,
                                key = { visibleOtherStops[it].stop.id }
                            ) { index ->
                                val stopWD = visibleOtherStops[index]
                                StopItemRow(
                                    stopWithDistance = stopWD,
                                    isStarred = false,
                                    appPreferences = appPreferences,
                                    useMetric = useMetric,
                                    loadPriority = starredCount + index,
                                    externalRefreshTrigger = state.refreshTrigger,
                                    fetchArrivals = { force -> viewModel.getArrivalsForStop(stopWD.stop.id, force) },
                                    onClick = { onStopClick(stopWD.stop.id, stopWD.stop.name, stopWD.stop.lat, stopWD.stop.lon) },
                                    onStarClick = { viewModel.toggleStar(appPreferences, stopWD.stop.id) }
                                )
                            }

                            if (visibleOtherCount < state.otherStops.size) {
                                item {
                                    TextButton(
                                        onClick = { visibleOtherCount += 6 },
                                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                                    ) {
                                        Text("Load More Nearby Stops...")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StopItemRow(
    stopWithDistance: StopWithDistance,
    isStarred: Boolean,
    appPreferences: AppPreferences,
    useMetric: Boolean,
    loadPriority: Int = 0,
    externalRefreshTrigger: Int = 0,
    fetchArrivals: suspend (force: Boolean) -> FetchResult,
    onClick: () -> Unit,
    onStarClick: () -> Unit
) {
    val starredRoutes by appPreferences.starredRoutes.collectAsState(initial = emptySet())

    var refreshTrigger by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var arrivalsState by remember { mutableStateOf<List<ObaArrivalAndDeparture>?>(null) }
    var lastFetchTime by remember { mutableStateOf<Long?>(null) }
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        // Stagger initial load: closer stops load first
        kotlinx.coroutines.delay(loadPriority * 300L)
        refreshTrigger++ // force initial fetch after delay
        var lastTriggerTime = System.currentTimeMillis()
        while (true) {
            kotlinx.coroutines.delay(1_000L)
            currentTime = System.currentTimeMillis()
            if (currentTime - lastTriggerTime >= 60_000L) {
                refreshTrigger++
                lastTriggerTime = currentTime
            }
        }
    }

    LaunchedEffect(refreshTrigger, externalRefreshTrigger) {
        isLoading = true
        var backoff = 1000L
        val isForced = refreshTrigger > 1 || externalRefreshTrigger > 0
        while (true) {
            when (val result = fetchArrivals(isForced)) {
                is FetchResult.Success -> {
                    arrivalsState = result.arrivals
                    lastFetchTime = result.timestamp
                    isLoading = false
                    break
                }
                is FetchResult.RateLimited -> {
                    kotlinx.coroutines.delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                }
                is FetchResult.Error -> {
                    if (arrivalsState == null) arrivalsState = emptyList()
                    isLoading = false
                    break
                }
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Header row: stop name + actions
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // Direction as a prominent label
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
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary,
                            letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                    Text(
                        text = stopWithDistance.stop.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        maxLines = 2
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${FormatUtils.formatDistance(stopWithDistance.distanceMeters, useMetric)} away",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(4.dp).size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    IconButton(onClick = { refreshTrigger++ }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onStarClick, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Star",
                        modifier = Modifier.size(22.dp),
                        tint = if (isStarred) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(12.dp))

            // Arrivals section
            val arrivalsList = arrivalsState
            if (isLoading && arrivalsList == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                }
            } else if (arrivalsList?.isEmpty() == true) {
                Text(
                    text = "No upcoming arrivals",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                val nonNullArrivals = arrivalsList!!
                val groupedArrivals = remember(nonNullArrivals, starredRoutes) {
                    nonNullArrivals.groupBy { it.routeId }
                        .map { (routeId, groupArrivals) ->
                            val sortedGroup = groupArrivals.sortedBy { it.effectiveDepartureTime() }
                            val starred = starredRoutes.contains("${stopWithDistance.stop.id}_${routeId}")
                            Triple(sortedGroup.first(), sortedGroup.take(3), starred)
                        }
                        .sortedWith(
                            compareByDescending<Triple<ObaArrivalAndDeparture, List<ObaArrivalAndDeparture>, Boolean>> { it.third }
                                .thenBy { it.first.effectiveDepartureTime() }
                        )
                        .take(4)
                }

                val timeFormat = remember { java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()) }

                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    groupedArrivals.forEach { (firstArrival, arrivals, routeStarred) ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Route name line with destination
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = if (routeStarred) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        text = firstArrival.routeShortName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        color = if (routeStarred) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                                if (routeStarred) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Filled.Star,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = firstArrival.tripHeadsign,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Arrival times row below the route name
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 4.dp)
                            ) {
                                arrivals.forEach { arrival ->
                                    val time = arrival.effectiveDepartureTime() ?: return@forEach
                                    val minutes = ((time - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
                                    val exactTime = timeFormat.format(java.util.Date(time))

                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "${minutes} min",
                                            fontSize = 22.sp,
                                            fontWeight = if (minutes <= 5) androidx.compose.ui.text.font.FontWeight.ExtraBold else androidx.compose.ui.text.font.FontWeight.Bold,
                                            color = if (minutes <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = exactTime,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (lastFetchTime != null && !isLoading) {
                Spacer(modifier = Modifier.height(14.dp))
                val diff = currentTime - lastFetchTime!!
                val staleness = when {
                    diff < 60000 -> "${diff / 1000}s ago"
                    diff < 3600000 -> "${diff / 60000}m ago"
                    else -> "${diff / 3600000}h ago"
                }
                Text(
                    text = "Updated $staleness",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}
