package com.sumitgouthaman.busdash.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Calendar

data class CommuteEntry(
    val id: String,
    val stopId: String,
    val stopName: String,
    val routeId: String,
    val routeShortName: String,
    val hour: Int,
    val minute: Int,
    // Uses Calendar day-of-week constants: Calendar.MONDAY=2 .. Calendar.SATURDAY=7, Calendar.SUNDAY=1
    val daysOfWeek: Set<Int>,
    val enabled: Boolean = true
) {
    fun formattedTime(): String {
        val h = if (hour % 12 == 0) 12 else hour % 12
        val m = minute.toString().padStart(2, '0')
        val amPm = if (hour < 12) "AM" else "PM"
        return "$h:$m $amPm"
    }

    fun formattedDays(): String {
        val all = setOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        )
        if (daysOfWeek == all) return "Every day"
        val weekdays = setOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY
        )
        if (daysOfWeek == weekdays) return "Weekdays"
        val weekend = setOf(Calendar.SATURDAY, Calendar.SUNDAY)
        if (daysOfWeek == weekend) return "Weekends"
        val order = listOf(
            Calendar.MONDAY to "Mon",
            Calendar.TUESDAY to "Tue",
            Calendar.WEDNESDAY to "Wed",
            Calendar.THURSDAY to "Thu",
            Calendar.FRIDAY to "Fri",
            Calendar.SATURDAY to "Sat",
            Calendar.SUNDAY to "Sun"
        )
        return order.filter { daysOfWeek.contains(it.first) }.joinToString(", ") { it.second }
    }
}

private val gson = Gson()
private val listType = object : TypeToken<List<CommuteEntry>>() {}.type

fun List<CommuteEntry>.toCommuteJson(): String = gson.toJson(this)

fun String.toCommuteList(): List<CommuteEntry> = try {
    gson.fromJson(this, listType) ?: emptyList()
} catch (e: Exception) {
    emptyList()
}
