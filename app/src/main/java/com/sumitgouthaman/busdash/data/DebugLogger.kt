package com.sumitgouthaman.busdash.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

object DebugLogger {

    private val DEBUG_LOG_KEY = stringPreferencesKey("debug_log_entries")
    private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(7)
    private const val TAG = "DebugLogger"

    suspend fun log(context: Context, level: LogLevel, tag: String, message: String) {
        // Mirror to logcat so existing debugging workflows are unaffected
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
        }
        val entry = DebugLogEntry(
            timestampMs = System.currentTimeMillis(),
            level = level,
            message = message
        )
        try {
            context.dataStore.edit { prefs ->
                val current = prefs[DEBUG_LOG_KEY]?.toDebugLogList() ?: emptyList()
                val cutoff = System.currentTimeMillis() - MAX_AGE_MS
                prefs[DEBUG_LOG_KEY] = (current.filter { it.timestampMs >= cutoff } + entry).toDebugLogJson()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist debug log entry", e)
        }
    }

    suspend fun readAll(context: Context): List<DebugLogEntry> {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        return context.dataStore.data
            .map { prefs -> prefs[DEBUG_LOG_KEY]?.toDebugLogList() ?: emptyList() }
            .first()
            .filter { it.timestampMs >= cutoff }
            .sortedByDescending { it.timestampMs }
    }

    suspend fun clearAll(context: Context) {
        context.dataStore.edit { prefs ->
            prefs[DEBUG_LOG_KEY] = "[]"
        }
    }
}
