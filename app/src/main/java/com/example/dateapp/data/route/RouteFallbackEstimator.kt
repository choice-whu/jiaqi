package com.example.dateapp.data.route

import android.util.Log
import com.example.dateapp.data.environment.UserLocationSnapshot
import java.util.Locale
import kotlin.math.roundToInt

internal class RouteFallbackEstimator {
    fun estimate(
        origin: UserLocationSnapshot,
        destination: RouteDestination,
        reason: String?
    ): RoutePlanMetrics {
        val distanceKm = RouteMath.haversineKm(
            lat1 = origin.latitude,
            lon1 = origin.longitude,
            lat2 = destination.latitude,
            lon2 = destination.longitude
        )
        val transportMode = if (distanceKm <= RouteMath.WALKING_DISTANCE_THRESHOLD_KM) {
            RouteTransportMode.WALK
        } else {
            RouteTransportMode.DRIVE
        }
        val durationMinutes = when (transportMode) {
            RouteTransportMode.WALK ->
                (distanceKm / WALKING_SPEED_KMH * 60.0).roundToInt().coerceAtLeast(6)

            RouteTransportMode.DRIVE ->
                (distanceKm / CITY_DRIVE_SPEED_KMH * 60.0).roundToInt().coerceAtLeast(10)
        }

        Log.d(
            TAG,
            "route source=local_estimate reason=$reason transport=${transportMode.name} duration=$durationMinutes distanceKm=${"%.2f".format(Locale.US, distanceKm)} destination=${destination.label}"
        )

        return RoutePlanMetrics(
            transportMode = transportMode,
            distanceMeters = (distanceKm * 1000.0).roundToInt(),
            durationMinutes = durationMinutes,
            sourceBadge = buildFallbackSourceBadge(destination)
        )
    }

    private fun buildFallbackSourceBadge(destination: RouteDestination): String {
        return when (destination.resolutionSource) {
            "amap_sdk_poi", "amap_place_text", "amap_geocode" -> "高德定位 + 估时"
            "direct_coordinates" -> "精确坐标 + 估时"
            "fallback_landmark" -> "地点锚点 + 估时"
            "category_anchor" -> "附近锚点 + 估时"
            else -> "当前位置 + 估时"
        }
    }

    private companion object {
        private const val TAG = "RoutePlanning"
        private const val WALKING_SPEED_KMH = 4.5
        private const val CITY_DRIVE_SPEED_KMH = 24.0
    }
}
