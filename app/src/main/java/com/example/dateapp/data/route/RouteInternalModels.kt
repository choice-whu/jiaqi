package com.example.dateapp.data.route

internal data class RouteDestination(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val resolutionSource: String
)

internal data class RouteMetrics(
    val distanceMeters: Int,
    val durationMinutes: Int
)

internal data class RoutePlanMetrics(
    val transportMode: RouteTransportMode,
    val distanceMeters: Int,
    val durationMinutes: Int,
    val sourceBadge: String
)
