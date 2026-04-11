package com.sumitgouthaman.busdash.data

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

data class ObaResponse<T>(
    val code: Int,
    val text: String,
    val data: ObaData<T>
)

data class ObaData<T>(
    val limitExceeded: Boolean,
    val references: ObaReferences?,
    val list: T? = null,
    val entry: T? = null
)

data class ObaReferences(
    val agencies: List<ObaAgency>?,
    val routes: List<ObaRoute>?,
    val stops: List<ObaStop>?
)

data class ObaAgency(
    val id: String,
    val name: String
)

data class ObaRoute(
    val id: String,
    val shortName: String,
    val longName: String,
    val description: String?,
    val agencyId: String
)

data class ObaStop(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val direction: String?,
    val routeIds: List<String>
)

data class ObaArrivalAndDeparture(
    val routeId: String,
    val routeShortName: String,
    val tripId: String,
    val serviceDate: Long,
    val stopId: String,
    val stopSequence: Int,
    val predictedDepartureTime: Long,
    val scheduledDepartureTime: Long,
    val tripHeadsign: String,
    val status: String?
)

/** Returns the predicted departure time if available, falling back to the scheduled time. */
fun ObaArrivalAndDeparture.effectiveDepartureTime(): Long? = when {
    predictedDepartureTime > 0 -> predictedDepartureTime
    scheduledDepartureTime > 0 -> scheduledDepartureTime
    else -> null
}

data class ArrivalsAndDeparturesEntry(
    val stopId: String,
    val arrivalsAndDepartures: List<ObaArrivalAndDeparture>,
    val nearbys: List<String>?
)

interface OneBusAwayApi {
    @GET("where/stops-for-location.json")
    suspend fun getStopsForLocation(
        @Query("key") key: String,
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("radius") radius: Int = 1000 // default 1000m
    ): ObaResponse<List<ObaStop>>

    @GET("where/arrivals-and-departures-for-stop/{stopId}.json")
    suspend fun getArrivalsAndDeparturesForStop(
        @Path("stopId") stopId: String,
        @Query("key") key: String
    ): ObaResponse<ArrivalsAndDeparturesEntry>
}
