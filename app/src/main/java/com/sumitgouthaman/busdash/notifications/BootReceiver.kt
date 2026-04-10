package com.sumitgouthaman.busdash.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sumitgouthaman.busdash.data.AppPreferences
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
                Log.d("BootReceiver", "Rescheduled ${commutes.count { it.enabled }} commute alarms")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
