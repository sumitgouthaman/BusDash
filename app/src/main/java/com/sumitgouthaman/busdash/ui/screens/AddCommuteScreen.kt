package com.sumitgouthaman.busdash.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sumitgouthaman.busdash.data.AppPreferences
import com.sumitgouthaman.busdash.data.CommuteEntry
import com.sumitgouthaman.busdash.data.LocationHelper
import com.sumitgouthaman.busdash.data.ObaStop
import com.sumitgouthaman.busdash.data.OneBusAwayApi
import com.sumitgouthaman.busdash.notifications.CommuteAlarmScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Calendar
import java.util.UUID

data class StopOption(val stop: ObaStop, val isStarred: Boolean)

sealed class AddCommuteUiState {
    object Idle : AddCommuteUiState()
    object LoadingStops : AddCommuteUiState()
    data class StopsLoaded(val stops: List<StopOption>) : AddCommuteUiState()
    object LoadingRoutes : AddCommuteUiState()
    object Error : AddCommuteUiState()
    object Saving : AddCommuteUiState()
    object Saved : AddCommuteUiState()
}

class AddCommuteViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<AddCommuteUiState>(AddCommuteUiState.Idle)
    val uiState: StateFlow<AddCommuteUiState> = _uiState

    // Preloaded routes for selected stop (routeId -> routeShortName)
    private val _availableRoutes = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableRoutes: StateFlow<List<Pair<String, String>>> = _availableRoutes

    fun loadNearbyStops(appPreferences: AppPreferences, locationHelper: LocationHelper) {
        viewModelScope.launch {
            _uiState.value = AddCommuteUiState.LoadingStops
            try {
                val apiKey = appPreferences.apiKey.first() ?: run {
                    _uiState.value = AddCommuteUiState.Error; return@launch
                }
                val baseUrl = appPreferences.baseUrl.first()
                val location = locationHelper.getCurrentLocation() ?: run {
                    _uiState.value = AddCommuteUiState.Error; return@launch
                }
                val api = buildApi(baseUrl)
                val response = api.getStopsForLocation(apiKey, location.latitude, location.longitude)
                val stops = response.data.list ?: emptyList()
                val starred = appPreferences.starredStops.first()
                val options = stops
                    .map { StopOption(it, starred.contains(it.id)) }
                    .sortedWith(compareByDescending<StopOption> { it.isStarred }.thenBy { it.stop.name })
                _uiState.value = AddCommuteUiState.StopsLoaded(options)
            } catch (e: Exception) {
                _uiState.value = AddCommuteUiState.Error
            }
        }
    }

    fun loadRoutesForStop(appPreferences: AppPreferences, stopId: String) {
        viewModelScope.launch {
            _uiState.value = AddCommuteUiState.LoadingRoutes
            try {
                val apiKey = appPreferences.apiKey.first() ?: run {
                    _uiState.value = AddCommuteUiState.Error; return@launch
                }
                val baseUrl = appPreferences.baseUrl.first()
                val api = buildApi(baseUrl)
                val response = api.getArrivalsAndDeparturesForStop(stopId, apiKey)
                val ads = response.data.entry?.arrivalsAndDepartures ?: emptyList()
                val routes = ads
                    .distinctBy { it.routeId }
                    .map { it.routeId to it.routeShortName }
                    .sortedBy { it.second }
                _availableRoutes.value = routes
                _uiState.value = AddCommuteUiState.Idle
            } catch (e: Exception) {
                _availableRoutes.value = emptyList()
                _uiState.value = AddCommuteUiState.Error
            }
        }
    }

    fun save(
        context: Context,
        appPreferences: AppPreferences,
        stopId: String,
        stopName: String,
        routeId: String,
        routeShortName: String,
        hour: Int,
        minute: Int,
        daysOfWeek: Set<Int>,
        onSaved: () -> Unit
    ) {
        viewModelScope.launch {
            _uiState.value = AddCommuteUiState.Saving
            val entry = CommuteEntry(
                id = UUID.randomUUID().toString(),
                stopId = stopId,
                stopName = stopName,
                routeId = routeId,
                routeShortName = routeShortName,
                hour = hour,
                minute = minute,
                daysOfWeek = daysOfWeek,
                enabled = true
            )
            appPreferences.addCommute(entry)
            CommuteAlarmScheduler.schedule(context, entry)
            _uiState.value = AddCommuteUiState.Saved
            onSaved()
        }
    }

    private fun buildApi(baseUrl: String) = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(OneBusAwayApi::class.java)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCommuteScreen(
    prefillStopId: String = "",
    prefillStopName: String = "",
    prefillRouteId: String = "",
    prefillRouteShortName: String = "",
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val appPreferences = remember { AppPreferences(context) }
    val locationHelper = remember { LocationHelper(context) }
    val viewModel: AddCommuteViewModel = viewModel()

    val uiState by viewModel.uiState.collectAsState()
    val availableRoutes by viewModel.availableRoutes.collectAsState()

    // Form state
    var selectedStopId by remember { mutableStateOf(prefillStopId) }
    var selectedStopName by remember { mutableStateOf(prefillStopName) }
    var selectedRouteId by remember { mutableStateOf(prefillRouteId) }
    var selectedRouteShortName by remember { mutableStateOf(prefillRouteShortName) }
    var hour by remember { mutableIntStateOf(8) }
    var minute by remember { mutableIntStateOf(0) }
    var daysOfWeek by remember {
        mutableStateOf(
            setOf(
                Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY
            )
        )
    }
    var showTimePicker by remember { mutableStateOf(false) }

    // Load routes if stop was pre-filled
    LaunchedEffect(prefillStopId) {
        if (prefillStopId.isNotBlank() && prefillRouteId.isBlank()) {
            viewModel.loadRoutesForStop(appPreferences, prefillStopId)
        }
    }

    val canSave = selectedStopId.isNotBlank() && selectedRouteId.isNotBlank() && daysOfWeek.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Add Commute",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // ── Section 1: Stop ──────────────────────────────────────
            SectionHeader("Stop")

            if (selectedStopId.isNotBlank()) {
                SelectedChip(
                    label = selectedStopName.ifBlank { selectedStopId },
                    onClear = {
                        selectedStopId = ""
                        selectedStopName = ""
                        selectedRouteId = ""
                        selectedRouteShortName = ""
                    }
                )
            } else {
                when (val state = uiState) {
                    is AddCommuteUiState.LoadingStops -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Searching nearby stops…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    is AddCommuteUiState.StopsLoaded -> {
                        Text(
                            "Nearby stops (starred first)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Show stops in a bounded scrollable list (max ~250dp)
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            tonalElevation = 1.dp
                        ) {
                            LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                                items(state.stops) { option ->
                                    StopOptionRow(
                                        option = option,
                                        onClick = {
                                            selectedStopId = option.stop.id
                                            selectedStopName = option.stop.name
                                            selectedRouteId = ""
                                            selectedRouteShortName = ""
                                            viewModel.loadRoutesForStop(appPreferences, option.stop.id)
                                        }
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                    is AddCommuteUiState.Error -> {
                        Text("Failed to load stops.", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { viewModel.loadNearbyStops(appPreferences, locationHelper) }) {
                            Text("Retry")
                        }
                    }
                    else -> {
                        OutlinedButton(
                            onClick = { viewModel.loadNearbyStops(appPreferences, locationHelper) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Search Nearby Stops")
                        }
                    }
                }
            }

            // ── Section 2: Route ─────────────────────────────────────
            SectionHeader("Route")

            when {
                selectedStopId.isBlank() -> {
                    Text(
                        "Select a stop first",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                uiState is AddCommuteUiState.LoadingRoutes -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Loading routes…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                selectedRouteId.isNotBlank() -> {
                    SelectedChip(
                        label = "Route $selectedRouteShortName",
                        onClear = { selectedRouteId = ""; selectedRouteShortName = "" }
                    )
                }
                availableRoutes.isNotEmpty() -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        availableRoutes.forEach { (rId, rShort) ->
                            FilterChip(
                                selected = selectedRouteId == rId,
                                onClick = { selectedRouteId = rId; selectedRouteShortName = rShort },
                                label = { Text(rShort) }
                            )
                        }
                    }
                }
                else -> {
                    Text(
                        "No routes found for this stop",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── Section 3: Time ──────────────────────────────────────
            SectionHeader("Notification time")

            OutlinedButton(
                onClick = { showTimePicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                val h = if (hour % 12 == 0) 12 else hour % 12
                val m = minute.toString().padStart(2, '0')
                val amPm = if (hour < 12) "AM" else "PM"
                Text(
                    "$h:$m $amPm",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // ── Section 4: Days ──────────────────────────────────────
            SectionHeader("Days")

            val dayLabels = listOf(
                Calendar.MONDAY to "Mon",
                Calendar.TUESDAY to "Tue",
                Calendar.WEDNESDAY to "Wed",
                Calendar.THURSDAY to "Thu",
                Calendar.FRIDAY to "Fri",
                Calendar.SATURDAY to "Sat",
                Calendar.SUNDAY to "Sun"
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dayLabels.forEach { (day, label) ->
                    FilterChip(
                        selected = daysOfWeek.contains(day),
                        onClick = {
                            daysOfWeek = if (daysOfWeek.contains(day))
                                daysOfWeek - day
                            else
                                daysOfWeek + day
                        },
                        label = { Text(label) }
                    )
                }
            }

            // ── Save ─────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    viewModel.save(
                        context, appPreferences,
                        selectedStopId, selectedStopName,
                        selectedRouteId, selectedRouteShortName,
                        hour, minute, daysOfWeek,
                        onSaved = onBackClick
                    )
                },
                enabled = canSave && uiState !is AddCommuteUiState.Saving,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState is AddCommuteUiState.Saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Save Commute")
                }
            }
        }
    }

    // Time picker dialog
    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute,
            is24Hour = false
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    hour = timePickerState.hour
                    minute = timePickerState.minute
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
            text = {
                TimePicker(state = timePickerState)
            }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SelectedChip(label: String, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onClear) { Text("Change") }
    }
}

@Composable
private fun StopOptionRow(option: StopOption, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (option.isStarred) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = "Starred",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Column {
            Text(option.stop.name, style = MaterialTheme.typography.bodyMedium)
            if (option.stop.direction != null) {
                Text(
                    "Direction: ${option.stop.direction}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
