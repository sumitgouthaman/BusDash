package com.sumitgouthaman.busdash.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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
    }

    val apiKey: Flow<String?> = dataStore.data.map { preferences ->
        preferences[OBA_API_KEY]
    }

    val isConfigured: Flow<Boolean> = dataStore.data.map { preferences ->
        !preferences[OBA_API_KEY].isNullOrBlank()
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

    suspend fun saveConfig(apiKey: String, baseUrl: String) {
        dataStore.edit { preferences ->
            preferences[OBA_API_KEY] = apiKey.trim()
            preferences[OBA_BASE_URL] = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        }
    }

    suspend fun toggleStarredStop(stopId: String) {
        dataStore.edit { preferences ->
            val currentStops = preferences[STARRED_STOPS] ?: emptySet()
            val newStops = currentStops.toMutableSet()
            if (newStops.contains(stopId)) {
                newStops.remove(stopId)
                // Optionally remove routes for this stop
                val currentRoutes = preferences[STARRED_ROUTES] ?: emptySet()
                preferences[STARRED_ROUTES] = currentRoutes.filterNot { it.startsWith("${stopId}_") }.toSet()
            } else {
                newStops.add(stopId)
            }
            preferences[STARRED_STOPS] = newStops
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
}
