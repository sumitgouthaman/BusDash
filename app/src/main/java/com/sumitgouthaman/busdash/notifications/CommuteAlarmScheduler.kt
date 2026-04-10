package com.sumitgouthaman.busdash.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.sumitgouthaman.busdash.data.CommuteEntry
import java.util.Calendar

object CommuteAlarmScheduler {

    fun schedule(context: Context, entry: CommuteEntry) {
        if (!entry.enabled) return
        val triggerMs = nextTriggerMs(entry) ?: return
        val pi = buildPendingIntent(context, entry)
        val am = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        }
    }

    fun cancel(context: Context, entry: CommuteEntry) {
        val pi = buildPendingIntent(context, entry)
        context.getSystemService(AlarmManager::class.java).cancel(pi)
    }

    fun rescheduleAll(context: Context, commutes: List<CommuteEntry>) {
        commutes.forEach { cancel(context, it) }
        commutes.filter { it.enabled }.forEach { schedule(context, it) }
    }

    /**
     * Returns the next wall-clock epoch ms at which this commute should fire,
     * or null if daysOfWeek is empty.
     */
    fun nextTriggerMs(entry: CommuteEntry): Long? {
        if (entry.daysOfWeek.isEmpty()) return null
        val now = Calendar.getInstance()
        val candidate = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, entry.hour)
            set(Calendar.MINUTE, entry.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Advance day-by-day until we land on a configured day that is in the future
        repeat(8) {
            if (entry.daysOfWeek.contains(candidate.get(Calendar.DAY_OF_WEEK)) &&
                candidate.timeInMillis > now.timeInMillis
            ) {
                return candidate.timeInMillis
            }
            candidate.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    private fun buildPendingIntent(context: Context, entry: CommuteEntry): PendingIntent {
        val intent = Intent(context, CommuteAlarmReceiver::class.java).apply {
            putExtra(CommuteAlarmReceiver.EXTRA_COMMUTE_ID, entry.id)
        }
        return PendingIntent.getBroadcast(
            context,
            entry.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
