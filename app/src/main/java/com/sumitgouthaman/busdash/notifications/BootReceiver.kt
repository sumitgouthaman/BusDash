package com.sumitgouthaman.busdash.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sumitgouthaman.busdash.data.AppPreferences
import com.sumitgouthaman.busdash.data.DebugLogger
import com.sumitgouthaman.busdash.data.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val commutes = AppPreferences(context).typicalCommutes.first()
                CommuteAlarmScheduler.rescheduleAll(context, commutes)
                val count = commutes.count { it.enabled }
                DebugLogger.log(
                    context, LogLevel.DEBUG, "BootReceiver",
                    "Device restarted — rescheduled $count upcoming commute alarm${if (count == 1) "" else "s"}"
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
