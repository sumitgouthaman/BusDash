package com.sumitgouthaman.busdash.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class AppPreferences(private val context: Context) {

    private val dataStore = context.dataStore

    companion object {
        val OBA_API_KEY = stringPreferencesKey("oba_api_key")
        val OBA_BASE_URL = stringPreferencesKey("oba_base_url")
        val STARRED_STOPS = stringSetPreferencesKey("starred_stops")
        // Format for starred routes: "stopId_routeId"
        val STARRED_ROUTES = stringSetPreferencesKey("starred_routes")
        val USE_METRIC_DISTANCE = booleanPreferencesKey("use_metric_distance")
        val TYPICAL_COMMUTES = stringPreferencesKey("typical_commutes")
        // Format per entry: "stopId|lat|lon|stopName"
        val STARRED_STOP_DETAILS = stringSetPreferencesKey("starred_stop_details")
        val GEOFENCE_RADIUS_METERS = intPreferencesKey("geofence_radius_meters")
    }

    val apiKey: Flow<String?> = dataStore.data.map { preferences ->
        preferences[OBA_API_KEY]
    }

    val isConfigured: Flow<Boolean> = dataStore.data.map { preferences ->
        !preferences[OBA_API_KEY].isNullOrBlank()
    }

    val useMetricDistance: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[USE_METRIC_DISTANCE] ?: false
    }

    val baseUrl: Flow<String> = dataStore.data.map { preferences ->
        // Default to Puget Sound OneBusAway
        preferences[OBA_BASE_URL] ?: "https://api.pugetsound.onebusaway.org/api/"
    }

    val starredStops: Flow<Set<String>> = dataStore.data.map { preferences ->
        preferences[STARRED_STOPS] ?: emptySet()
    }

    val starredRoutes: Flow<Set<String>> = dataStore.data.map { preferences ->
        preferences[STARRED_ROUTES] ?: emptySet()
    }

    val geofenceRadiusMeters: Flow<Int> = dataStore.data.map { preferences ->
        preferences[GEOFENCE_RADIUS_METERS] ?: 50
    }

    data class StarredStopDetail(val stopId: String, val lat: Double, val lon: Double, val name: String)

    val starredStopDetails: Flow<Set<String>> = dataStore.data.map { preferences ->
        preferences[STARRED_STOP_DETAILS] ?: emptySet()
    }

    suspend fun getStarredStopDetails(): List<StarredStopDetail> =
        starredStopDetails.first().mapNotNull { entry ->
            val parts = entry.split("|", limit = 4)
            if (parts.size == 4) {
                val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                val lon = parts[2].toDoubleOrNull() ?: return@mapNotNull null
                StarredStopDetail(parts[0], lat, lon, parts[3])
            } else null
        }

    suspend fun saveStarredStopCoords(stopId: String, lat: Double, lon: Double, stopName: String) {
        dataStore.edit { preferences ->
            val details = (preferences[STARRED_STOP_DETAILS] ?: emptySet()).toMutableSet()
            details.removeIf { it.startsWith("$stopId|") }
            details.add("$stopId|$lat|$lon|$stopName")
            preferences[STARRED_STOP_DETAILS] = details
        }
    }

    suspend fun saveConfig(apiKey: String, baseUrl: String, useMetric: Boolean, geofenceRadiusMeters: Int = 50) {
        dataStore.edit { preferences ->
            preferences[OBA_API_KEY] = apiKey.trim()
            preferences[OBA_BASE_URL] = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            preferences[USE_METRIC_DISTANCE] = useMetric
            preferences[GEOFENCE_RADIUS_METERS] = geofenceRadiusMeters.coerceIn(50, 500)
        }
    }

    suspend fun toggleStarredStop(stopId: String) {
        dataStore.edit { preferences ->
            val currentStops = preferences[STARRED_STOPS] ?: emptySet()
            val newStops = currentStops.toMutableSet()
            if (newStops.contains(stopId)) {
                newStops.remove(stopId)
                val currentRoutes = preferences[STARRED_ROUTES] ?: emptySet()
                preferences[STARRED_ROUTES] = currentRoutes.filterNot { it.startsWith("${stopId}_") }.toSet()
                val details = preferences[STARRED_STOP_DETAILS] ?: emptySet()
                preferences[STARRED_STOP_DETAILS] = details.filterNot { it.startsWith("${stopId}|") }.toSet()
            } else {
                newStops.add(stopId)
            }
            preferences[STARRED_STOPS] = newStops
        }
    }

    suspend fun toggleStarredStop(stopId: String, lat: Double, lon: Double, stopName: String) {
        dataStore.edit { preferences ->
            val stops = (preferences[STARRED_STOPS] ?: emptySet()).toMutableSet()
            val details = (preferences[STARRED_STOP_DETAILS] ?: emptySet()).toMutableSet()
            if (stops.contains(stopId)) {
                stops.remove(stopId)
                details.removeIf { it.startsWith("$stopId|") }
                val routes = preferences[STARRED_ROUTES] ?: emptySet()
                preferences[STARRED_ROUTES] = routes.filterNot { it.startsWith("${stopId}_") }.toSet()
            } else {
                stops.add(stopId)
                details.removeIf { it.startsWith("$stopId|") }
                details.add("$stopId|$lat|$lon|$stopName")
            }
            preferences[STARRED_STOPS] = stops
            preferences[STARRED_STOP_DETAILS] = details
        }
    }

    suspend fun toggleStarredRoute(stopId: String, routeId: String) {
        dataStore.edit { preferences ->
            val key = "${stopId}_$routeId"
            val currentRoutes = preferences[STARRED_ROUTES] ?: emptySet()
            val newRoutes = currentRoutes.toMutableSet()
            if (newRoutes.contains(key)) {
                newRoutes.remove(key)
            } else {
                newRoutes.add(key)
            }
            preferences[STARRED_ROUTES] = newRoutes
        }
    }

    val typicalCommutes: Flow<List<CommuteEntry>> = dataStore.data.map { preferences ->
        preferences[TYPICAL_COMMUTES]?.toCommuteList() ?: emptyList()
    }

    suspend fun addCommute(entry: CommuteEntry) {
        dataStore.edit { preferences ->
            val current = preferences[TYPICAL_COMMUTES]?.toCommuteList() ?: emptyList()
            preferences[TYPICAL_COMMUTES] = (current + entry).toCommuteJson()
        }
    }

    suspend fun removeCommute(id: String) {
        dataStore.edit { preferences ->
            val current = preferences[TYPICAL_COMMUTES]?.toCommuteList() ?: emptyList()
            preferences[TYPICAL_COMMUTES] = current.filter { it.id != id }.toCommuteJson()
        }
    }

    suspend fun toggleCommuteEnabled(id: String) {
        dataStore.edit { preferences ->
            val current = preferences[TYPICAL_COMMUTES]?.toCommuteList() ?: emptyList()
            preferences[TYPICAL_COMMUTES] = current.map { entry ->
                if (entry.id == id) entry.copy(enabled = !entry.enabled) else entry
            }.toCommuteJson()
        }
    }

    suspend fun updateCommute(entry: CommuteEntry) {
        dataStore.edit { preferences ->
            val current = preferences[TYPICAL_COMMUTES]?.toCommuteList() ?: emptyList()
            preferences[TYPICAL_COMMUTES] = current.map { existing ->
                if (existing.id == entry.id) entry else existing
            }.toCommuteJson()
        }
    }

    suspend fun getCommuteById(id: String): CommuteEntry? =
        typicalCommutes.first().find { it.id == id }
}
