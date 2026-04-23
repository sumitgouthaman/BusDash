package com.sumitgouthaman.busdash.data

import android.content.Context
import kotlinx.coroutines.flow.first

object GeofenceMigration {

    private const val TAG = "GeofenceMigration"

    /**
     * Backfills STARRED_STOP_DETAILS for any starred stops that lack coordinates.
     * Calls getArrivalsAndDeparturesForStop for each missing stop and extracts the
     * stop's lat/lon/name from the response references. Safe to call repeatedly —
     * it is a no-op when all starred stops already have details.
     */
    suspend fun backfillIfNeeded(context: Context, appPreferences: AppPreferences) {
        val starredIds = appPreferences.starredStops.first()
        if (starredIds.isEmpty()) return

        val resolvedIds = appPreferences.getStarredStopDetails().map { it.stopId }.toSet()
        val missing = starredIds - resolvedIds
        if (missing.isEmpty()) return

        val apiKey = appPreferences.apiKey.first()
        if (apiKey.isNullOrBlank()) {
            DebugLogger.log(
                context, LogLevel.WARN, TAG,
                "Cannot backfill stop coords — no API key configured"
            )
            return
        }
        val baseUrl = appPreferences.baseUrl.first()
        val api = ObaApiClient.create(baseUrl)

        DebugLogger.log(
            context, LogLevel.DEBUG, TAG,
            "Backfilling coords for ${missing.size} starred stop(s)"
        )

        for (stopId in missing) {
            try {
                val response = api.getArrivalsAndDeparturesForStop(stopId, apiKey)
                val stop = response.data.references?.stops?.find { it.id == stopId }
                if (stop != null) {
                    appPreferences.saveStarredStopCoords(stop.id, stop.lat, stop.lon, stop.name)
                    DebugLogger.log(
                        context, LogLevel.DEBUG, TAG,
                        "Backfilled coords for ${stop.name} ($stopId)"
                    )
                } else {
                    DebugLogger.log(
                        context, LogLevel.WARN, TAG,
                        "Stop $stopId not found in API references — skipping"
                    )
                }
            } catch (e: Exception) {
                DebugLogger.log(
                    context, LogLevel.WARN, TAG,
                    "Could not backfill coords for stop $stopId: ${e.message}"
                )
            }
        }
    }
}
