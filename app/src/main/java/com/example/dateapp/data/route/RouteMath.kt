package com.example.dateapp.data.route

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal object RouteMath {
    fun haversineKm(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val startLat = Math.toRadians(lat1)
        val endLat = Math.toRadians(lat2)

        val a = sin(dLat / 2).pow(2) +
            sin(dLon / 2).pow(2) * cos(startLat) * cos(endLat)
        val c = 2 * asin(sqrt(a))
        return EARTH_RADIUS_KM * c
    }

    fun formatDistance(distanceMeters: Int): String {
        return if (distanceMeters >= 1000) {
            String.format(Locale.US, "%.1f km", distanceMeters / 1000f)
        } else {
            "$distanceMeters m"
        }
    }

    const val WALKING_DISTANCE_THRESHOLD_KM = 2.2
    private const val EARTH_RADIUS_KM = 6371.0
}
