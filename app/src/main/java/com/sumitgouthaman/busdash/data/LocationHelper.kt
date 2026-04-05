package com.sumitgouthaman.busdash.data

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
        return try {
            val lastLocation = fusedLocationClient.lastLocation.await()
            if (lastLocation != null) {
                lastLocation
            } else {
                kotlinx.coroutines.withTimeoutOrNull(8000L) {
                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
