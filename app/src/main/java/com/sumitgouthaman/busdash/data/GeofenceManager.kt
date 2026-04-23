package com.sumitgouthaman.busdash.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.sumitgouthaman.busdash.notifications.GeofenceBroadcastReceiver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

object GeofenceManager {

    private const val TAG = "GeofenceManager"

    @SuppressLint("MissingPermission")
    suspend fun syncGeofences(context: Context, appPreferences: AppPreferences) {
        val client = LocationServices.getGeofencingClient(context)

        // Always clear existing geofences first for a clean slate
        try {
            client.removeGeofences(buildPendingIntent(context)).await()
        } catch (_: Exception) {}

        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasBackground = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine || !hasBackground) {
            DebugLogger.log(
                context, LogLevel.WARN, TAG,
                "Geofences not registered — background location permission not granted"
            )
            return
        }

        val stops = appPreferences.getStarredStopDetails()
        if (stops.isEmpty()) {
            DebugLogger.log(context, LogLevel.DEBUG, TAG, "No starred stops — geofences cleared")
            return
        }

        val radiusMeters = appPreferences.geofenceRadiusMeters.first().toFloat()

        val geofences = stops.map { stop ->
            Geofence.Builder()
                .setRequestId(stop.stopId)
                .setCircularRegion(stop.lat, stop.lon, radiusMeters)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(
                    Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
                )
                .build()
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()

        try {
            client.addGeofences(request, buildPendingIntent(context)).await()
            DebugLogger.log(
                context, LogLevel.DEBUG, TAG,
                "Registered ${geofences.size} geofence(s) at ${radiusMeters.toInt()}m radius"
            )
        } catch (e: Exception) {
            DebugLogger.log(
                context, LogLevel.ERROR, TAG,
                "Failed to register geofences: ${e.message}"
            )
        }
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        // FLAG_MUTABLE is required: the Geofencing API injects transition type and geofence IDs
        // into the intent at fire time, which requires a mutable PendingIntent.
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
}
