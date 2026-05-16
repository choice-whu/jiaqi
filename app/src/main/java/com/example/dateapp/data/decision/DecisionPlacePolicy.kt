package com.example.dateapp.data.decision

import com.example.dateapp.data.WuhanKnowledgeConfig
import com.example.dateapp.data.place.ResolvedPlace
import com.example.dateapp.data.remote.AiDecisionRecommendation
import java.util.Locale

class DecisionPlacePolicy {
    fun recommendationSignalText(recommendation: AiDecisionRecommendation): String {
        return buildString {
            append(recommendation.name)
            append(' ')
            append(recommendation.amapSearchKeyword.orEmpty())
            append(' ')
            append(recommendation.distanceDescription.orEmpty())
            append(' ')
            append(recommendation.tag.orEmpty())
            append(' ')
            append(recommendation.intro.orEmpty())
        }
    }

    fun isTripWorthyPlayDestination(resolvedPlace: ResolvedPlace): Boolean {
        val text = buildString {
            append(resolvedPlace.displayName)
            append(' ')
            append(resolvedPlace.routeKeyword)
        }.lowercase(Locale.ROOT)

        return tripWorthySignals.any(text::contains)
    }

    fun isSpecialExperiencePlayDestination(
        recommendation: AiDecisionRecommendation,
        resolvedPlace: ResolvedPlace
    ): Boolean {
        val text = buildString {
            append(recommendationSignalText(recommendation))
            append(' ')
            append(resolvedPlace.displayName)
            append(' ')
            append(resolvedPlace.routeKeyword)
        }.lowercase(Locale.ROOT)

        return specialExperienceSignals.any(text::contains)
    }

    fun isLikelyClosedDayOnlyVenue(
        category: String,
        recommendation: AiDecisionRecommendation,
        resolvedPlace: ResolvedPlace,
        hour: Int
    ): Boolean {
        if (category != "play" || hour < 16 || resolvedPlace.isOpenNow == true) {
            return false
        }

        val text = buildString {
            append(recommendationSignalText(recommendation))
            append(' ')
            append(resolvedPlace.displayName)
            append(' ')
            append(resolvedPlace.routeKeyword)
            append(' ')
            append(resolvedPlace.openingHours.orEmpty())
        }.lowercase(Locale.ROOT)

        return dayOnlyVenueSignals.any(text::contains)
    }

    fun isFrontPlayDistanceAllowed(
        recommendation: AiDecisionRecommendation,
        resolvedPlace: ResolvedPlace
    ): Boolean {
        val distanceMeters = resolvedPlace.directDistanceMeters ?: return true
        return when {
            distanceMeters <= PLAY_NEARBY_DIRECT_DISTANCE_METERS -> true
            distanceMeters <= FRONT_PLAY_MAX_DISTANCE_METERS &&
                isSpecialExperiencePlayDestination(recommendation, resolvedPlace) -> true
            distanceMeters <= PLAY_TRIP_WORTHY_MAX_DIRECT_DISTANCE_METERS &&
                isTripWorthyPlayDestination(resolvedPlace) -> true
            else -> false
        }
    }

    fun isTripWorthyLongPlay(
        recommendation: AiDecisionRecommendation,
        resolvedPlace: ResolvedPlace
    ): Boolean {
        val text = buildString {
            append(recommendationSignalText(recommendation))
            append(' ')
            append(resolvedPlace.displayName)
        }.lowercase(Locale.ROOT)

        return longPlaySignals.any(text::contains)
    }

    fun isPlayDistanceImplausible(
        recommendation: AiDecisionRecommendation,
        resolvedPlace: ResolvedPlace,
        describedDistance: Int?
    ): Boolean {
        val resolvedDistance = resolvedPlace.directDistanceMeters ?: return false
        if (describedDistance != null) {
            val allowedDistance = maxOf(
                describedDistance * PLAY_DISTANCE_MISMATCH_MULTIPLIER,
                describedDistance + PLAY_DISTANCE_MISMATCH_BUFFER_METERS
            )
            if (resolvedDistance > allowedDistance) {
                return true
            }
        }

        val isTripWorthy = isTripWorthyPlayDestination(resolvedPlace)
        return resolvedDistance > PLAY_TRIP_WORTHY_MAX_DIRECT_DISTANCE_METERS ||
            (!isTripWorthy && !isSpecialExperiencePlayDestination(recommendation, resolvedPlace) && resolvedDistance > PLAY_NEARBY_DIRECT_DISTANCE_METERS) ||
            (!isTripWorthy && resolvedDistance > PLAY_MAX_DIRECT_DISTANCE_METERS) ||
            (!isTripWorthy && resolvedDistance > PLAY_REASONABLE_DIRECT_DISTANCE_METERS && describedDistance == null)
    }

    companion object {
        const val PLAY_NEARBY_DIRECT_DISTANCE_METERS = 4_500
        const val PLAY_REASONABLE_DIRECT_DISTANCE_METERS = 6_000
        const val PLAY_MAX_DIRECT_DISTANCE_METERS = 8_000
        const val FRONT_PLAY_MAX_DISTANCE_METERS = 10_000
        const val PLAY_TRIP_WORTHY_MAX_DIRECT_DISTANCE_METERS = 25_000

        private const val PLAY_DISTANCE_MISMATCH_MULTIPLIER = 4
        private const val PLAY_DISTANCE_MISMATCH_BUFFER_METERS = 3_000

        private val tripWorthySignals = WuhanKnowledgeConfig.tripWorthySignals

        private val specialExperienceSignals = listOf(
            "diy",
            "手作",
            "陶艺",
            "银饰",
            "调香",
            "玻璃",
            "木作",
            "簇绒",
            "tufting",
            "烘焙",
            "体验馆",
            "沉浸",
            "剧场",
            "小剧场",
            "脱口秀",
            "livehouse",
            "密室",
            "桌游",
            "剧本",
            "电玩城",
            "街机",
            "vr",
            "攀岩",
            "射箭",
            "保龄球",
            "滑冰",
            "美术馆",
            "艺术空间",
            "画廊",
            "展览",
            "展馆",
            "看展",
            "复古照相",
            "大头贴"
        )

        private val dayOnlyVenueSignals = listOf(
            "植物园",
            "博物馆",
            "博物院",
            "美术馆",
            "艺术馆",
            "纪念馆",
            "展览",
            "展馆",
            "画廊",
            "寺",
            "古迹",
            "遗址",
            "动物园"
        )

        private val longPlaySignals = WuhanKnowledgeConfig.longPlaySignals
    }
}
