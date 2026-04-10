package com.sumitgouthaman.busdash.wear.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.wearDataStore: DataStore<Preferences> by preferencesDataStore(name = "wear_settings")

class WearPreferences(context: Context) {

    private val dataStore = context.wearDataStore

    companion object {
        val OBA_API_KEY = stringPreferencesKey("oba_api_key")
        val OBA_BASE_URL = stringPreferencesKey("oba_base_url")
        val STARRED_STOPS = stringSetPreferencesKey("starred_stops")
        val STARRED_ROUTES = stringSetPreferencesKey("starred_routes")
        val USE_METRIC_DISTANCE = booleanPreferencesKey("use_metric_distance")
        val COMMUTES_JSON = stringPreferencesKey("commutes_json")
    }

    val apiKey: Flow<String?> = dataStore.data.map { preferences ->
        preferences[OBA_API_KEY]
    }

    val isConfigured: Flow<Boolean> = dataStore.data.map { preferences ->
        !preferences[OBA_API_KEY].isNullOrBlank()
    }

    val baseUrl: Flow<String> = dataStore.data.map { preferences ->
        preferences[OBA_BASE_URL] ?: "https://api.pugetsound.onebusaway.org/api/"
    }

    val starredStops: Flow<Set<String>> = dataStore.data.map { preferences ->
        preferences[STARRED_STOPS] ?: emptySet()
    }

    val starredRoutes: Flow<Set<String>> = dataStore.data.map { preferences ->
        preferences[STARRED_ROUTES] ?: emptySet()
    }

    val useMetricDistance: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[USE_METRIC_DISTANCE] ?: false
    }

    val commutesJson: Flow<String> = dataStore.data.map { preferences ->
        preferences[COMMUTES_JSON] ?: "[]"
    }

    suspend fun updateFromPhone(
        apiKey: String,
        baseUrl: String,
        useMetricDistance: Boolean,
        starredStops: Set<String>,
        starredRoutes: Set<String>,
        commutesJson: String = "[]"
    ) {
        dataStore.edit { preferences ->
            preferences[OBA_API_KEY] = apiKey
            preferences[OBA_BASE_URL] = baseUrl
            preferences[USE_METRIC_DISTANCE] = useMetricDistance
            preferences[STARRED_STOPS] = starredStops
            preferences[STARRED_ROUTES] = starredRoutes
            preferences[COMMUTES_JSON] = commutesJson
        }
    }
}
