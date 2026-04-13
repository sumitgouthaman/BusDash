package com.sumitgouthaman.busdash.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

enum class LogLevel { DEBUG, WARN, ERROR }

data class DebugLogEntry(
    val timestampMs: Long,
    val level: LogLevel,
    val message: String
)

private val debugGson = Gson()
private val debugListType = object : TypeToken<List<DebugLogEntry>>() {}.type

fun List<DebugLogEntry>.toDebugLogJson(): String = debugGson.toJson(this)

fun String.toDebugLogList(): List<DebugLogEntry> = try {
    debugGson.fromJson(this, debugListType) ?: emptyList()
} catch (e: Exception) {
    emptyList()
}
