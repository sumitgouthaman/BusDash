package com.sumitgouthaman.busdash.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sumitgouthaman.busdash.data.AppPreferences
import com.sumitgouthaman.busdash.data.ObaArrivalAndDeparture
import com.sumitgouthaman.busdash.data.OneBusAwayApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.*

class StopDetailsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<StopDetailsUiState>(StopDetailsUiState.Loading)
    val uiState: StateFlow<StopDetailsUiState> = _uiState

    fun loadData(appPreferences: AppPreferences, stopId: String) {
        viewModelScope.launch {
            _uiState.value = StopDetailsUiState.Loading
            try {
                val apiKey = appPreferences.apiKey.first()
                val baseUrl = appPreferences.baseUrl.first()

                if (apiKey.isNullOrBlank()) {
                    _uiState.value = StopDetailsUiState.Error("API Key not configured")
                    return@launch
                }

                val api = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(OneBusAwayApi::class.java)

                val response = api.getArrivalsAndDeparturesForStop(stopId = stopId, key = apiKey)
                val ads = response.data.entry?.arrivalsAndDepartures ?: emptyList()

                appPreferences.starredRoutes.collectLatest { starred ->
                    _uiState.value = StopDetailsUiState.Success(ads, starred, stopId)
                }

            } catch (e: Exception) {
                _uiState.value = StopDetailsUiState.Error(e.message ?: "Unknown Error")
            }
        }
    }

    fun toggleRouteStar(appPreferences: AppPreferences, stopId: String, routeId: String) {
        viewModelScope.launch {
            appPreferences.toggleStarredRoute(stopId, routeId)
        }
    }
}

sealed class StopDetailsUiState {
    object Loading : StopDetailsUiState()
    data class Success(val ads: List<ObaArrivalAndDeparture>, val starredRoutes: Set<String>, val stopId: String) : StopDetailsUiState()
    data class Error(val message: String) : StopDetailsUiState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopDetailsScreen(
    stopId: String,
    lat: Double,
    lon: Double,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val appPreferences = remember { AppPreferences(context) }
    val viewModel: StopDetailsViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(stopId) {
        viewModel.loadData(appPreferences, stopId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Stop Details",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon")
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Map,
                            contentDescription = "Open in maps"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val state = uiState) {
                is StopDetailsUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is StopDetailsUiState.Error -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = { viewModel.loadData(appPreferences, stopId) }) {
                            Text("Retry")
                        }
                    }
                }
                is StopDetailsUiState.Success -> {
                    if (state.ads.isEmpty()) {
                        Text(
                            text = "No upcoming arrivals.",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        // Group by route
                        val grouped = remember(state.ads) {
                            state.ads
                                .sortedBy { if (it.predictedDepartureTime > 0) it.predictedDepartureTime else it.scheduledDepartureTime }
                                .groupBy { it.routeId }
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            grouped.forEach { (routeId, arrivals) ->
                                val routeKey = "${state.stopId}_${routeId}"
                                val isStarred = state.starredRoutes.contains(routeKey)
                                val firstArrival = arrivals.first()

                                // Route header
                                item(key = "header_$routeId") {
                                    RouteGroupCard(
                                        routeShortName = firstArrival.routeShortName,
                                        headsign = firstArrival.tripHeadsign,
                                        arrivals = arrivals,
                                        isStarred = isStarred,
                                        onStarClick = { viewModel.toggleRouteStar(appPreferences, state.stopId, routeId) }
                                    )
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
fun RouteGroupCard(
    routeShortName: String,
    headsign: String,
    arrivals: List<ObaArrivalAndDeparture>,
    isStarred: Boolean,
    onStarClick: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Route header row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Route badge
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (isStarred) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = routeShortName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isStarred) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Headsign — right-aligned, fills remaining space
                Text(
                    text = headsign,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(onClick = onStarClick) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Star Route",
                        modifier = Modifier.size(28.dp),
                        tint = if (isStarred) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(16.dp))

            // Arrival times list
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                arrivals.forEach { ad ->
                    val time = if (ad.predictedDepartureTime > 0) ad.predictedDepartureTime else ad.scheduledDepartureTime
                    val minutes = ((time - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
                    val exactTime = timeFormat.format(Date(time))
                    val isPredicted = ad.predictedDepartureTime > 0

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Minutes countdown — hero element
                        Text(
                            text = "${minutes} min",
                            fontSize = 24.sp,
                            fontWeight = if (minutes <= 5) FontWeight.ExtraBold else FontWeight.Bold,
                            color = when {
                                minutes <= 1 -> MaterialTheme.colorScheme.error
                                minutes <= 5 -> MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.widthIn(min = 100.dp)
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        // Exact time
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = exactTime,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isPredicted) "Real-time" else "Scheduled",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isPredicted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }

                        // Status badge
                        if (ad.status != null) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = ad.status ?: "",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
