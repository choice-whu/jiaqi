package com.example.dateapp.data.decision

import android.util.Log
import com.example.dateapp.data.AiRepository
import com.example.dateapp.data.environment.DecisionEnvironmentSnapshot
import com.example.dateapp.data.place.PlaceConfidence
import com.example.dateapp.data.place.PlaceResolver
import com.example.dateapp.data.place.ResolvedPlace
import com.example.dateapp.data.recommendation.RecommendationPreferenceProfile
import com.example.dateapp.data.remote.AiDecisionRecommendation
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

data class DecisionEngineRequest(
    val environment: DecisionEnvironmentSnapshot,
    val targetCategory: String,
    val avoidNames: List<String>,
    val currentCardName: String? = null,
    val hardAvoidNames: List<String> = emptyList(),
    val nearbyMallName: String? = null,
    val useCandidateBatch: Boolean = false,
    val preferenceProfile: RecommendationPreferenceProfile? = null
)

data class DecisionEngineResult(
    val recommendation: AiDecisionRecommendation,
    val resolvedPlace: ResolvedPlace,
    val category: String,
    val environment: DecisionEnvironmentSnapshot,
    val weatherProfile: DecisionWeatherProfile,
    val candidateCount: Int,
    val attemptIndex: Int,
    val rankedCandidates: List<DecisionEngineCandidate>
)

data class DecisionEngineCandidate(
    val recommendation: AiDecisionRecommendation,
    val resolvedPlace: ResolvedPlace,
    val score: Int
)

data class DecisionWeatherProfile(
    val label: String,
    val isRainy: Boolean,
    val isSevere: Boolean,
    val isHot: Boolean,
    val isCold: Boolean,
    val isFoggy: Boolean,
    val isComfortableOutdoor: Boolean,
    val mealProbabilityAdjustment: Double
)

class DecisionEngine(
    private val aiRepository: AiRepository,
    private val placeResolver: PlaceResolver
) {

    suspend fun generateAiDecision(request: DecisionEngineRequest): Result<DecisionEngineResult> {
        return runCatching {
            val weatherProfile = buildWeatherProfile(request.environment)
            var lastFailureReason = "AI recommendation failed validation"
            var dynamicAvoidNames = request.avoidNames
            var totalCandidates = 0
            val startedAtMillis = System.currentTimeMillis()

            for (attempt in AI_FAST_ATTEMPT_INDICES) {
                val attemptMode = aiRecommendationAttemptMode(attempt)
                val timeoutMillis = if (request.useCandidateBatch) {
                    AI_RECOMMENDATION_BACKGROUND_TIMEOUT_MS
                } else {
                    AI_RECOMMENDATION_FAST_TIMEOUT_MS
                }
                val rescueMode = true

                Log.d(
                    TAG,
                    "decision source=ENGINE_ATTEMPT index=${attempt + 1} mode=$attemptMode timeout=${timeoutMillis}ms targetCategory=${request.targetCategory} weather=${weatherProfile.label} avoid=${dynamicAvoidNames.size} rescue=$rescueMode"
                )

                val result = withTimeoutOrNull(timeoutMillis) {
                    requestRecommendations(
                        request = request,
                        avoidNames = dynamicAvoidNames,
                        attempt = attempt,
                        rescueMode = rescueMode,
                        attemptMode = attemptMode
                    )
                }

                if (result == null) {
                    lastFailureReason = "AI $attemptMode timed out after ${timeoutMillis}ms"
                    continue
                }

                val throwable = result.exceptionOrNull()
                if (throwable != null) {
                    lastFailureReason = throwable.message ?: throwable.javaClass.simpleName
                    continue
                }

                val candidates = result.getOrThrow()
                totalCandidates += candidates.size
                if (candidates.isEmpty()) {
                    lastFailureReason = "AI returned empty candidates"
                    continue
                }
                Log.d(
                    TAG,
                    "decision source=ENGINE_CANDIDATES attempt=${attempt + 1} mode=$attemptMode count=${candidates.size} names=${candidates.joinToString(limit = 6) { it.name }}"
                )

                val selectedCandidates = selectCandidateScores(
                    candidates = candidates,
                    request = request,
                    weatherProfile = weatherProfile,
                    avoidNames = dynamicAvoidNames
                )

                if (selectedCandidates.isNotEmpty()) {
                    val selected = selectedCandidates.first()
                    Log.d(
                        TAG,
                        "decision source=ENGINE_SELECTED attempt=${attempt + 1} name=${selected.recommendation.name} score=${selected.score} confidence=${selected.resolvedPlace.confidence} distance=${selected.resolvedPlace.distanceLabel} candidates=${candidates.size} valid=${selectedCandidates.size}"
                    )
                    return@runCatching DecisionEngineResult(
                        recommendation = selected.recommendation,
                        resolvedPlace = selected.resolvedPlace,
                        category = request.targetCategory,
                        environment = request.environment,
                        weatherProfile = weatherProfile,
                        candidateCount = totalCandidates,
                        attemptIndex = attempt,
                        rankedCandidates = selectedCandidates.map { candidateScore ->
                            DecisionEngineCandidate(
                                recommendation = candidateScore.recommendation,
                                resolvedPlace = candidateScore.resolvedPlace,
                                score = candidateScore.score
                            )
                        }
                    )
                }

                lastFailureReason = "No candidate passed local validation"
                dynamicAvoidNames = (dynamicAvoidNames + candidates.map { it.name })
                    .distinct()
                    .takeLast(AVOID_MEMORY_LIMIT)
            }

            error("$lastFailureReason after ${System.currentTimeMillis() - startedAtMillis}ms")
        }
    }

    private suspend fun requestRecommendations(
        request: DecisionEngineRequest,
        avoidNames: List<String>,
        @Suppress("UNUSED_PARAMETER") attempt: Int,
        rescueMode: Boolean,
        @Suppress("UNUSED_PARAMETER") attemptMode: AiRecommendationAttemptMode
    ): Result<List<AiDecisionRecommendation>> {
        val effectiveAvoidNames = if (rescueMode) {
            val hardAvoidNames = request.hardAvoidNames.takeLast(AI_RESCUE_AVOID_LIMIT)
            val softAvoidNames = avoidNames
                .filterNot { avoidName ->
                    hardAvoidNames.any { hardAvoidName -> isSimilarPlaceName(avoidName, hardAvoidName) }
                }
                .takeLast((AI_RESCUE_AVOID_LIMIT - hardAvoidNames.size).coerceAtLeast(0))
            (softAvoidNames + hardAvoidNames).distinct().takeLast(AI_RESCUE_AVOID_LIMIT)
        } else {
            avoidNames
        }

        return if (request.useCandidateBatch) {
            aiRepository.recommendDecisionCandidates(
                environment = request.environment,
                targetCategory = request.targetCategory,
                strictTimeMatch = true,
                avoidNames = effectiveAvoidNames,
                nearbyMallName = request.nearbyMallName,
                preferenceProfile = request.preferenceProfile,
                fastMode = true,
                rescueMode = true
            ).map { recommendations ->
                recommendations.take(BACKGROUND_CANDIDATE_LIMIT)
            }
        } else {
            aiRepository.recommendDecision(
                environment = request.environment,
                targetCategory = request.targetCategory,
                strictTimeMatch = true,
                avoidNames = effectiveAvoidNames,
                nearbyMallName = request.nearbyMallName,
                preferenceProfile = request.preferenceProfile,
                fastMode = true,
                rescueMode = true
            ).map { listOf(it) }
        }
    }

    fun buildWeatherProfile(environment: DecisionEnvironmentSnapshot): DecisionWeatherProfile {
        val weatherText = environment.weatherCondition
        val inferredTemperature = Regex("(-?\\d+(?:\\.\\d+)?)")
            .find(weatherText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
        val isRainy = weatherText.contains("雨") ||
            weatherText.contains("降水") ||
            weatherText.contains("rain", ignoreCase = true)
        val isSnowy = weatherText.contains("雪") ||
            weatherText.contains("snow", ignoreCase = true)
        val isThunder = weatherText.contains("雷")
        val isSevere = weatherText.contains("强") ||
            weatherText.contains("冻") ||
            isThunder ||
            isSnowy
        val isFoggy = weatherText.contains("雾")
        val isHot = inferredTemperature?.let { it >= 30.0 } ?: weatherText.contains("热")
        val isCold = inferredTemperature?.let { it <= 12.0 } ?: weatherText.contains("冷")
        val isComfortableOutdoor = !isRainy && !isSnowy && !isFoggy && !isHot && !isCold &&
            (weatherText.contains("晴") || weatherText.contains("多云"))
        val mealAdjustment = when {
            isSevere -> 0.10
            isRainy || isHot || isCold -> 0.04
            isFoggy -> 0.02
            isComfortableOutdoor -> -0.18
            else -> 0.0
        }
        val label = when {
            isSevere -> "severe"
            isRainy -> "rainy"
            isHot -> "hot"
            isCold -> "cold"
            isFoggy -> "foggy"
            isComfortableOutdoor -> "outdoor_friendly"
            else -> "neutral"
        }

        return DecisionWeatherProfile(
            label = label,
            isRainy = isRainy,
            isSevere = isSevere,
            isHot = isHot,
            isCold = isCold,
            isFoggy = isFoggy,
            isComfortableOutdoor = isComfortableOutdoor,
            mealProbabilityAdjustment = mealAdjustment
        )
    }

    private suspend fun selectCandidateScores(
        candidates: List<AiDecisionRecommendation>,
        request: DecisionEngineRequest,
        weatherProfile: DecisionWeatherProfile,
        avoidNames: List<String>
    ): List<CandidateScore> {
        val validCandidates = mutableListOf<CandidateScore>()
        val hour = request.environment.currentTime.hour

        for (candidate in candidates.distinctBy { normalizePlaceName(it.name) }) {
            if (isInvalidRecommendationName(candidate, request.targetCategory)) {
                Log.d(
                    TAG,
                    "decision source=ENGINE_INVALID_NAME_SKIP targetCategory=${request.targetCategory} name=${candidate.name}"
                )
                continue
            }

            val isCurrentCardRepeat = request.currentCardName?.let { currentName ->
                isSimilarPlaceName(candidate.name, currentName)
            } ?: false
            val isDislikedRepeat = isRecentlyRecommended(candidate.name, request.hardAvoidNames)
            val isSoftRepeat = isRecentlyRecommended(candidate.name, avoidNames)
            if (isDislikedRepeat) {
                Log.d(
                    TAG,
                    "decision source=ENGINE_DISLIKED_HARD_SKIP targetCategory=${request.targetCategory} name=${candidate.name}"
                )
                continue
            }
            if (request.useCandidateBatch && (isCurrentCardRepeat || isDislikedRepeat || isSoftRepeat)) {
                Log.d(
                    TAG,
                    "decision source=ENGINE_BATCH_REPEAT_SKIP targetCategory=${request.targetCategory} name=${candidate.name}"
                )
                continue
            }
            val repeatPenalty = if (isDislikedRepeat) {
                Log.d(
                    TAG,
                    "decision source=ENGINE_DISLIKED_SOFT_PENALTY targetCategory=${request.targetCategory} name=${candidate.name}"
                )
                DISLIKED_REPEAT_PENALTY
            } else if (isCurrentCardRepeat) {
                Log.d(
                    TAG,
                    "decision source=ENGINE_CURRENT_CARD_SOFT_PENALTY targetCategory=${request.targetCategory} name=${candidate.name}"
                )
                CURRENT_CARD_REPEAT_PENALTY
            } else if (isSoftRepeat) {
                Log.d(
                    TAG,
                    "decision source=ENGINE_REPEAT_SOFT_PENALTY targetCategory=${request.targetCategory} name=${candidate.name}"
                )
                SOFT_REPEAT_PENALTY
            } else {
                0
            }

            val timeScore = recommendationTimeSuitability(
                recommendation = candidate,
                category = request.targetCategory,
                hour = hour
            )
            if (timeScore < 0) {
                Log.d(
                    TAG,
                    "decision source=ENGINE_TIME_MISMATCH time=${request.environment.currentTimeLabel} targetCategory=${request.targetCategory} name=${candidate.name}"
                )
                continue
            }

            val weatherScore = recommendationWeatherSuitability(
                recommendation = candidate,
                category = request.targetCategory,
                weatherProfile = weatherProfile
            )
            val weatherRiskActive = weatherProfile.isSevere ||
                weatherProfile.isRainy ||
                weatherProfile.isHot ||
                weatherProfile.isCold
            val weatherMismatch = weatherScore < -1 ||
                (weatherRiskActive && request.targetCategory == "play" && weatherScore < 0)
            if (weatherMismatch) {
                Log.d(
                    TAG,
                    "decision source=ENGINE_WEATHER_MISMATCH weather=${weatherProfile.label} score=$weatherScore targetCategory=${request.targetCategory} name=${candidate.name}"
                )
                continue
            }

            val placeResolutionTimeoutMillis = if (request.useCandidateBatch) {
                AI_PLACE_RESOLUTION_BACKGROUND_TIMEOUT_MS
            } else {
                AI_PLACE_RESOLUTION_FAST_TIMEOUT_MS
            }
            val resolvedPlace = placeResolver.resolveAiRecommendation(
                recommendation = candidate,
                category = request.targetCategory,
                environment = request.environment,
                timeoutMillis = placeResolutionTimeoutMillis
            )
            if (!isDistanceAcceptable(candidate, request.targetCategory, resolvedPlace)) {
                Log.d(
                    TAG,
                    "decision source=ENGINE_DISTANCE_MISMATCH targetCategory=${request.targetCategory} name=${candidate.name} aiDistance=${candidate.distanceDescription} resolvedDistance=${resolvedPlace.directDistanceMeters} confidence=${resolvedPlace.confidence}"
                )
                continue
            }
            if (
                request.targetCategory == "play" &&
                (resolvedPlace.latitude == null || resolvedPlace.longitude == null)
            ) {
                Log.d(
                    TAG,
                    "decision source=ENGINE_PLACE_UNRESOLVED targetCategory=${request.targetCategory} name=${candidate.name}"
                )
                continue
            }
            if (isPlayDistanceImplausible(request.targetCategory, candidate, resolvedPlace)) {
                Log.d(
                    TAG,
                    "decision source=ENGINE_PLAY_DISTANCE_MISMATCH targetCategory=${request.targetCategory} name=${candidate.name} aiDistance=${candidate.distanceDescription} resolvedDistance=${resolvedPlace.directDistanceMeters}"
                )
                continue
            }

            validCandidates += CandidateScore(
                recommendation = candidate,
                resolvedPlace = resolvedPlace,
                score = buildCandidateScore(
                    timeScore = timeScore,
                    weatherScore = weatherScore,
                    resolvedPlace = resolvedPlace,
                    category = request.targetCategory
                ) + personalizationScore(
                    recommendation = candidate,
                    resolvedPlace = resolvedPlace,
                    request = request
                ) - repeatPenalty
            )

        }

        return validCandidates.sortedByDescending { it.score }
    }

    private fun personalizationScore(
        recommendation: AiDecisionRecommendation,
        resolvedPlace: ResolvedPlace,
        request: DecisionEngineRequest
    ): Int {
        val profile = request.preferenceProfile ?: return 0
        val text = buildString {
            append(recommendationSignalText(recommendation))
            append(' ')
            append(resolvedPlace.displayName)
            append(' ')
            append(resolvedPlace.routeKeyword)
        }
        val score = profile.scoreText(
            text = text,
            fallbackCategory = request.targetCategory
        )
        if (score != 0) {
            Log.d(
                TAG,
                "decision source=ENGINE_PERSONAL_SCORE targetCategory=${request.targetCategory} name=${recommendation.name} score=$score"
            )
        }
        return score
    }

    private fun isInvalidRecommendationName(
        recommendation: AiDecisionRecommendation,
        category: String
    ): Boolean {
        val name = recommendation.name.trim()
        if (name.length < 2) {
            return true
        }

        val normalizedName = name.lowercase(Locale.ROOT)
        val instructionLeak = listOf(
            "不选",
            "不要",
            "以外",
            "除外",
            "可选",
            "任选",
            "附近街区",
            "附近一带",
            "周边",
            "附近"
        ).any(normalizedName::contains)
        if (instructionLeak) {
            return true
        }

        if (category == "play") {
            val vagueOnly = listOf(
                "商圈",
                "商业区",
                "商业圈",
                "片区",
                "区域"
            ).any(normalizedName::contains)
            if (vagueOnly) {
                return true
            }
        }

        return false
    }

    private fun buildCandidateScore(
        timeScore: Int,
        weatherScore: Int,
        resolvedPlace: ResolvedPlace,
        category: String
    ): Int {
        val confidenceScore = when (resolvedPlace.confidence) {
            PlaceConfidence.HIGH -> 4
            PlaceConfidence.MEDIUM -> 2
            PlaceConfidence.LOW -> 0
            PlaceConfidence.UNRESOLVED -> -2
        }
        val distanceScore = resolvedPlace.directDistanceMeters?.let { meters ->
            if (category == "meal") {
                when {
                    meters <= 800 -> 4
                    meters <= 1_500 -> 3
                    meters <= PlaceResolver.MEAL_MAX_DIRECT_DISTANCE_METERS -> 1
                    else -> -6
                }
            } else {
                when {
                    meters <= 5_000 -> 2
                    meters <= 18_000 -> 1
                    else -> -1
                }
            }
        } ?: 0

        return timeScore * 3 + weatherScore * 3 + confidenceScore + distanceScore
    }

    private fun isDistanceAcceptable(
        recommendation: AiDecisionRecommendation,
        category: String,
        resolvedPlace: ResolvedPlace
    ): Boolean {
        if (category != "meal") {
            return true
        }

        val describedDistance = placeResolver.parseDistanceMeters(recommendation.distanceDescription)
        if (describedDistance != null && describedDistance > PlaceResolver.MEAL_MAX_DIRECT_DISTANCE_METERS) {
            return false
        }

        val resolvedDistance = resolvedPlace.directDistanceMeters
        return resolvedDistance != null &&
            resolvedDistance <= PlaceResolver.MEAL_MAX_DIRECT_DISTANCE_METERS
    }

    private fun isPlayDistanceImplausible(
        category: String,
        recommendation: AiDecisionRecommendation,
        resolvedPlace: ResolvedPlace
    ): Boolean {
        if (category != "play") {
            return false
        }

        val resolvedDistance = resolvedPlace.directDistanceMeters ?: return false
        val describedDistance = placeResolver.parseDistanceMeters(recommendation.distanceDescription)
        if (resolvedDistance <= PLAY_REASONABLE_DIRECT_DISTANCE_METERS) {
            return false
        }

        return describedDistance == null || resolvedDistance > describedDistance * 4
    }

    private fun recommendationTimeSuitability(
        recommendation: AiDecisionRecommendation,
        category: String,
        hour: Int
    ): Int {
        return timeSuitabilityScore(
            text = recommendationSignalText(recommendation),
            category = category,
            hour = hour
        )
    }

    private fun recommendationWeatherSuitability(
        recommendation: AiDecisionRecommendation,
        category: String,
        weatherProfile: DecisionWeatherProfile
    ): Int {
        return weatherSuitabilityScore(
            text = recommendationSignalText(recommendation),
            category = category,
            weatherProfile = weatherProfile
        )
    }

    private fun weatherSuitabilityScore(
        text: String,
        category: String,
        weatherProfile: DecisionWeatherProfile
    ): Int {
        val normalizedText = text.lowercase(Locale.ROOT)
        fun containsAny(vararg keywords: String): Boolean = keywords.any(normalizedText::contains)

        val indoorFriendly = containsAny(
            "室内",
            "商场",
            "购物中心",
            "百货",
            "mall",
            "买手店",
            "主理人",
            "杂货",
            "唱片",
            "黑胶",
            "潮玩",
            "盲盒",
            "玩具",
            "书店",
            "电影",
            "影院",
            "剧场",
            "小剧场",
            "密室",
            "桌游",
            "电玩",
            "游戏厅",
            "保龄球",
            "台球",
            "攀岩",
            "手作",
            "陶艺",
            "文创",
            "市集",
            "集市",
            "小店",
            "中古",
            "生活方式",
            "沉浸",
            "ktv",
            "博物馆",
            "美术馆",
            "展",
            "咖啡",
            "甜品",
            "餐厅",
            "火锅",
            "茶",
            "酒吧"
        )
        val outdoorHeavy = containsAny(
            "公园",
            "江滩",
            "东湖",
            "江边",
            "湖边",
            "湖畔",
            "山",
            "校园",
            "骑行",
            "徒步",
            "露营",
            "日落",
            "晚霞",
            "露天",
            "观景"
        )

        var score = 0
        if (weatherProfile.isSevere || weatherProfile.isRainy || weatherProfile.isHot || weatherProfile.isCold) {
            if (indoorFriendly || category == "meal") {
                score += if (weatherProfile.isSevere) 4 else 2
            }
            if (outdoorHeavy) {
                score -= if (weatherProfile.isSevere || weatherProfile.isRainy) 5 else 3
            }
        }

        if (weatherProfile.isFoggy && containsAny("观景", "日落", "夜景", "高处")) {
            score -= 3
        }

        if (weatherProfile.isComfortableOutdoor && outdoorHeavy) {
            score += 2
        }

        return score
    }

    private fun timeSuitabilityScore(
        text: String,
        category: String,
        hour: Int
    ): Int {
        val normalizedText = text.lowercase(Locale.ROOT)
        fun containsAny(vararg keywords: String): Boolean = keywords.any(normalizedText::contains)

        var score = if (category == "meal") {
            when (hour) {
                in 6..9, in 11..13, in 17..20 -> 2
                in 21..23, in 0..2 -> 1
                else -> 0
            }
        } else {
            when (hour) {
                in 6..11, in 14..18 -> 2
                in 12..13, in 19..21 -> 1
                else -> 0
            }
        }

        if (containsAny("日落", "晚霞", "傍晚", "sunset")) {
            score += when (hour) {
                in 16..19 -> 4
                in 14..15, in 20..21 -> 1
                else -> -4
            }
        }

        if (containsAny("夜景", "酒吧", "小酒馆", "清吧", "livehouse")) {
            score += when (hour) {
                in 19..23 -> 3
                in 17..18 -> 1
                else -> -3
            }
        }

        if (containsAny("宵夜", "烧烤", "火锅")) {
            score += when (hour) {
                in 17..23, in 0..1 -> 2
                in 11..13 -> 1
                else -> -1
            }
        }

        if (containsAny("早餐", "早茶", "豆浆", "包子")) {
            score += when (hour) {
                in 6..10 -> 3
                else -> -2
            }
        }

        if (containsAny("咖啡", "书店", "展览", "美术馆", "博物馆", "买手店", "主理人", "杂货", "唱片", "黑胶", "潮玩", "盲盒", "玩具", "手作", "陶艺", "文创", "小店", "市集", "集市", "中古", "生活方式")) {
            score += when (hour) {
                in 9..18 -> 2
                in 19..20 -> 1
                else -> -1
            }
        }

        if (containsAny("公园", "散步", "江滩", "东湖")) {
            score += when (hour) {
                in 6..10, in 16..20 -> 2
                in 11..15 -> 1
                else -> 0
            }
        }

        return score
    }

    private fun isRecentlyRecommended(
        name: String,
        avoidNames: List<String>
    ): Boolean {
        return avoidNames.any { avoidName -> isSimilarPlaceName(name, avoidName) }
    }

    private fun isSimilarPlaceName(
        first: String,
        second: String
    ): Boolean {
        val normalizedFirst = normalizePlaceName(first)
        val normalizedSecond = normalizePlaceName(second)
        if (normalizedFirst.length < 2 || normalizedSecond.length < 2) {
            return false
        }

        return normalizedFirst.contains(normalizedSecond) ||
            normalizedSecond.contains(normalizedFirst)
    }

    private fun normalizePlaceName(name: String): String {
        return name.lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\p{Punct}（）()【】\\[\\]「」『』《》“”‘’、，。！？；：·•-]+"), "")
            .replace("武汉", "")
            .replace("湖北", "")
            .replace("旗舰", "")
            .replace("总", "")
            .replace("店", "")
            .trim()
    }

    private fun recommendationSignalText(recommendation: AiDecisionRecommendation): String {
        return buildString {
            append(recommendation.name)
            append(' ')
            append(recommendation.distanceDescription.orEmpty())
            append(' ')
            append(recommendation.tag.orEmpty())
            append(' ')
            append(recommendation.intro.orEmpty())
        }
    }

    private data class CandidateScore(
        val recommendation: AiDecisionRecommendation,
        val resolvedPlace: ResolvedPlace,
        val score: Int
    )

    private enum class AiRecommendationAttemptMode {
        QUICK_SINGLE
    }

    companion object {
        private const val TAG = "DecisionEngine"
        private val AI_FAST_ATTEMPT_INDICES = 0..0
        private const val AI_RECOMMENDATION_FAST_TIMEOUT_MS = 8_000L
        private const val AI_RECOMMENDATION_BACKGROUND_TIMEOUT_MS = 12_000L
        private const val AI_PLACE_RESOLUTION_FAST_TIMEOUT_MS = 1_800L
        private const val AI_PLACE_RESOLUTION_BACKGROUND_TIMEOUT_MS = 2_800L
        private const val BACKGROUND_CANDIDATE_LIMIT = 4
        private const val AI_RESCUE_AVOID_LIMIT = 12
        private const val AVOID_MEMORY_LIMIT = 100
        private const val PLAY_REASONABLE_DIRECT_DISTANCE_METERS = 35_000
        private const val SOFT_REPEAT_PENALTY = 5
        private const val CURRENT_CARD_REPEAT_PENALTY = 8
        private const val DISLIKED_REPEAT_PENALTY = 10

        private fun aiRecommendationAttemptMode(@Suppress("UNUSED_PARAMETER") attemptIndex: Int): AiRecommendationAttemptMode {
            return AiRecommendationAttemptMode.QUICK_SINGLE
        }
    }
}
