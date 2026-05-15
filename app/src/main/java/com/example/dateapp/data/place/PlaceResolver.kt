package com.example.dateapp.data.place

import android.util.Log
import com.example.dateapp.data.environment.DecisionEnvironmentSnapshot
import com.example.dateapp.data.environment.UserLocationSnapshot
import com.example.dateapp.data.remote.AiDecisionRecommendation
import com.example.dateapp.data.route.RoutePlanningRepository
import com.example.dateapp.data.route.RouteTargetRequest
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

enum class PlaceConfidence {
    HIGH,
    MEDIUM,
    LOW,
    UNRESOLVED
}

data class ResolvedPlace(
    val displayName: String,
    val routeKeyword: String,
    val latitude: Double?,
    val longitude: Double?,
    val directDistanceMeters: Int?,
    val distanceLabel: String?,
    val confidence: PlaceConfidence,
    val source: String,
    val isSuspiciousDistanceMismatch: Boolean,
    val isOpenNow: Boolean? = null,
    val openingHours: String? = null
)

class PlaceResolver(
    private val routePlanningRepository: RoutePlanningRepository
) {

    suspend fun resolveAiRecommendation(
        recommendation: AiDecisionRecommendation,
        category: String,
        environment: DecisionEnvironmentSnapshot,
        timeoutMillis: Long = DEFAULT_RESOLUTION_TIMEOUT_MS
    ): ResolvedPlace {
        val describedDistance = parseDistanceMeters(recommendation.distanceDescription)
        val origin = UserLocationSnapshot(
            label = environment.userLocationLabel,
            latitude = environment.latitude,
            longitude = environment.longitude,
            source = environment.locationSource
        )
        val displayName = recommendation.name.trim()
        val searchKeyword = recommendation.amapSearchKeyword
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: displayName
        val routeTarget = RouteTargetRequest(
            title = displayName,
            category = category,
            displayLocation = displayName,
            searchKeyword = searchKeyword,
            latitude = null,
            longitude = null,
            sourceLabel = "AI探索",
            tag = recommendation.tag
        )
        val routeResolvedPlace = withTimeoutOrNull(timeoutMillis) {
            routePlanningRepository.resolvePlaceForDecision(
                target = routeTarget,
                origin = origin,
                currentTime = environment.currentTime
            )
        }

        val measuredDistance = routeResolvedPlace?.directDistanceMeters
        val suspiciousMismatch = category == "meal" &&
            describedDistance != null &&
            describedDistance <= MEAL_MAX_DIRECT_DISTANCE_METERS &&
            measuredDistance != null &&
            measuredDistance > MEAL_MAX_DIRECT_DISTANCE_METERS * 4

        if (suspiciousMismatch) {
            Log.d(
                TAG,
                "place source=suspicious_distance name=$displayName keyword=$searchKeyword aiDistance=$describedDistance measuredDistance=$measuredDistance"
            )
        }

        val distanceMeters = when {
            suspiciousMismatch -> describedDistance
            measuredDistance != null -> measuredDistance
            else -> describedDistance
        }
        val distanceLabel = when {
            suspiciousMismatch -> recommendation.distanceDescription
            measuredDistance != null -> formatVerifiedDistance(measuredDistance)
            else -> recommendation.distanceDescription
        }
        val confidence = when {
            routeResolvedPlace != null && !suspiciousMismatch -> PlaceConfidence.HIGH
            routeResolvedPlace != null -> PlaceConfidence.LOW
            describedDistance != null -> PlaceConfidence.MEDIUM
            else -> PlaceConfidence.UNRESOLVED
        }

        return ResolvedPlace(
            displayName = routeResolvedPlace?.label ?: displayName,
            routeKeyword = routeResolvedPlace?.label ?: searchKeyword,
            latitude = routeResolvedPlace?.latitude,
            longitude = routeResolvedPlace?.longitude,
            directDistanceMeters = distanceMeters,
            distanceLabel = distanceLabel,
            confidence = confidence,
            source = routeResolvedPlace?.resolutionSource ?: "ai_distance",
            isSuspiciousDistanceMismatch = suspiciousMismatch,
            isOpenNow = routeResolvedPlace?.isOpenNow,
            openingHours = routeResolvedPlace?.openingHours
        )
    }

    fun parseDistanceMeters(distanceText: String?): Int? {
        val text = distanceText?.lowercase(Locale.ROOT)?.replace(" ", "").orEmpty()
        if (text.isBlank()) {
            return null
        }

        Regex("(\\d+(?:\\.\\d+)?)(公里|千米|km)").find(text)?.let { match ->
            return (match.groupValues[1].toDoubleOrNull()?.times(1000))?.toInt()
        }
        Regex("(\\d+(?:\\.\\d+)?)(米|m)").find(text)?.let { match ->
            return match.groupValues[1].toDoubleOrNull()?.toInt()
        }
        return null
    }

    private fun formatVerifiedDistance(distanceMeters: Int): String {
        return if (distanceMeters < 1000) {
            val roundedMeters = ((distanceMeters + 49) / 50) * 50
            "约${roundedMeters}米"
        } else {
            val distanceKm = distanceMeters / 1000.0
            "约${String.format(Locale.US, "%.1f", distanceKm)}公里"
        }
    }

    companion object {
        private const val TAG = "PlaceResolver"
        private const val DEFAULT_RESOLUTION_TIMEOUT_MS = 2_400L
        const val MEAL_MAX_DIRECT_DISTANCE_METERS = 4_200
    }
}
