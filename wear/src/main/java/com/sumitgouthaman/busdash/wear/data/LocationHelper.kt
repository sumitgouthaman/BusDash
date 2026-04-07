package com.sumitgouthaman.busdash.wear.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await

class LocationHelper(context: Context) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Location? {
        // First try a fresh high-accuracy location (up to 8s)
        val fresh = try {
            kotlinx.coroutines.withTimeoutOrNull(8000L) {
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        if (fresh != null) return fresh

        // Fall back to last known location if getCurrentLocation timed out or returned null
        // (common right after Activity restart before FusedLocationProvider warms up)
        return try {
            fusedLocationClient.lastLocation.await()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
