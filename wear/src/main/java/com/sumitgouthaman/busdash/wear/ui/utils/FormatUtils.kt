package com.sumitgouthaman.busdash.wear.ui.utils

import java.util.Locale

object FormatUtils {
    fun formatDistance(distanceMeters: Float, useMetric: Boolean): String {
        if (useMetric) {
            return if (distanceMeters < 1000f) {
                "~${distanceMeters.toInt()}m"
            } else {
                val km = distanceMeters / 1000f
                String.format(Locale.getDefault(), "~%.1fkm", km)
            }
        } else {
            val feet = distanceMeters * 3.28084f
            return if (feet < 1000f) {
                "~${feet.toInt()}ft"
            } else {
                val miles = feet / 5280f
                String.format(Locale.getDefault(), "~%.1fmi", miles)
            }
        }
    }
}
