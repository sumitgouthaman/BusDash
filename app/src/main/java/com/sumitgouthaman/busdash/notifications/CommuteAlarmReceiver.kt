package com.sumitgouthaman.busdash.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.Wearable
import com.sumitgouthaman.busdash.data.AppPreferences
import com.sumitgouthaman.busdash.data.CommuteEntry
import com.sumitgouthaman.busdash.data.ObaArrivalAndDeparture
import com.sumitgouthaman.busdash.data.OneBusAwayApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CommuteAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_COMMUTE_ID = "commute_id"
        private const val TAG = "CommuteAlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val commuteId = intent.getStringExtra(EXTRA_COMMUTE_ID) ?: run {
            Log.w(TAG, "onReceive: missing commute_id extra")
            return
        }
        Log.d(TAG, "onReceive: commute_id=$commuteId")
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handleAlarm(context, commuteId)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling commute alarm for $commuteId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleAlarm(context: Context, commuteId: String) {
        Log.d(TAG, "handleAlarm: $commuteId")
        val prefs = AppPreferences(context)
        val commute = prefs.getCommuteById(commuteId) ?: run {
            Log.w(TAG, "Commute $commuteId not found in prefs")
            return
        }
        if (!commute.enabled) {
            Log.d(TAG, "Commute $commuteId is disabled, skipping")
            return
        }

        val apiKey = prefs.apiKey.first()
        val baseUrl = prefs.baseUrl.first()
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "API key is blank, skipping notification")
            return
        }

        val formattedArrivals = try {
            val api = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(OneBusAwayApi::class.java)
            val response = api.getArrivalsAndDeparturesForStop(commute.stopId, apiKey)
            val allArrivals = response.data.entry?.arrivalsAndDepartures ?: emptyList()
            allArrivals
                .filter { it.routeId == commute.routeId }
                .mapNotNull { departureTimeMs(it) }
                .filter { it > System.currentTimeMillis() }
                .sorted()
                .take(3)
                .map { formatDepartureTime(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch arrivals for commute $commuteId", e)
            emptyList()
        }

        Log.d(TAG, "Posting notification with ${formattedArrivals.size} arrivals: $formattedArrivals")
        NotificationHelper.postCommuteNotification(context, commute, formattedArrivals)
        sendWatchMessage(context, commute, formattedArrivals)

        // Reschedule for the next valid day
        CommuteAlarmScheduler.schedule(context, commute)
    }

    private fun departureTimeMs(ad: ObaArrivalAndDeparture): Long? {
        val predicted = ad.predictedDepartureTime
        val scheduled = ad.scheduledDepartureTime
        return when {
            predicted > 0 -> predicted
            scheduled > 0 -> scheduled
            else -> null
        }
    }

    private fun formatDepartureTime(epochMs: Long): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(epochMs))

    private suspend fun sendWatchMessage(
        context: Context,
        commute: CommuteEntry,
        arrivals: List<String>
    ) {
        try {
            val arrivalsJson = arrivals.joinToString(",") { "\"$it\"" }
            val payload = """{"stopId":"${commute.stopId}","stopName":"${commute.stopName}","routeShortName":"${commute.routeShortName}","arrivals":[$arrivalsJson]}"""
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, "/busdash-commute-alert", payload.toByteArray())
                    .await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send watch message", e)
        }
    }
}
