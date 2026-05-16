package com.example.dateapp.data.decision

import android.util.Log
import com.example.dateapp.data.AiPoiChoice
import com.example.dateapp.data.AiRepository
import com.example.dateapp.data.WuhanKnowledgeConfig
import com.example.dateapp.data.environment.DecisionEnvironmentSnapshot
import com.example.dateapp.data.place.PlaceConfidence
import com.example.dateapp.data.place.PlaceResolver
import com.example.dateapp.data.place.ResolvedPlace
import com.example.dateapp.data.recommendation.RecommendationPreferenceProfile
import com.example.dateapp.data.recommendation.RecommendationTopic
import com.example.dateapp.data.remote.AiDecisionRecommendation
import com.example.dateapp.data.route.DecisionPoiCandidate
import com.example.dateapp.data.route.RoutePlanningRepository
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
    val preferenceProfile: RecommendationPreferenceProfile? = null,
    val recommendationTopic: RecommendationTopic? = null
)

data class DecisionEngineResult(
    val recommendation: AiDecisionRecommendation,
    val resolvedPlace: ResolvedPlace,
    val category: String,
    val environment: DecisionEnvironmentSnapshot,
    val weatherProfile: DecisionWeatherProfile,
    val candidateCount: Int,
    val attemptIndex: Int,
    val rankedCandidates: List<DecisionEngineCandidate>,
    val unresolvableNames: List<String> = emptyList()
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
    private val placeResolver: PlaceResolver,
    private val routePlanningRepository: RoutePlanningRepository,
    private val placePolicy: DecisionPlacePolicy = DecisionPlacePolicy(),
    private val candidateScorer: DecisionCandidateScorer = DecisionCandidateScorer(placePolicy)
) {

    suspend fun generateAiDecision(request: DecisionEngineRequest): Result<DecisionEngineResult> {
        return runCatching {
            val weatherProfile = buildWeatherProfile(request.environment)
            val avoidNames = (request.avoidNames + request.hardAvoidNames).distinct()
            val startedAtMillis = System.currentTimeMillis()
            val timeoutMillis = if (request.useCandidateBatch) {
                POI_FIRST_BACKGROUND_TIMEOUT_MS
            } else {
                POI_FIRST_FAST_TIMEOUT_MS
            }

            Log.d(
                TAG,
                "decision source=ENGINE_AMAP_FIRST timeout=${timeoutMillis}ms targetCategory=${request.targetCategory} weather=${weatherProfile.label} avoid=${avoidNames.size} topic=${request.recommendationTopic?.label}"
            )

            val candidates = withTimeoutOrNull(timeoutMillis) {
                requestAmapFirstCandidateScores(
                    request = request,
                    avoidNames = avoidNames,
                    weatherProfile = weatherProfile
                ).getOrElse { throwable ->
                    Log.d(
                        TAG,
                        "decision source=ENGINE_AMAP_FAILED reason=${throwable.message ?: throwable.javaClass.simpleName}"
                    )
                    null
                }
            }

            if (candidates == null) {
                error("AMap POI search timed out after ${timeoutMillis}ms")
            }
            require(candidates.isNotEmpty()) {
                "AMap returned no usable POI candidates after filtering"
            }

            val selected = candidates.first()
            Log.d(
                TAG,
                "decision source=ENGINE_SELECTED mode=AMAP_FIRST name=${selected.recommendation.name} score=${selected.score} confidence=${selected.resolvedPlace.confidence} distance=${selected.resolvedPlace.distanceLabel} candidates=${candidates.size}"
            )
            DecisionEngineResult(
                recommendation = selected.recommendation,
                resolvedPlace = selected.resolvedPlace,
                category = request.targetCategory,
                environment = request.environment,
                weatherProfile = weatherProfile,
                candidateCount = candidates.size,
                attemptIndex = 0,
                rankedCandidates = candidates.map { candidateScore ->
                    DecisionEngineCandidate(
                        recommendation = candidateScore.recommendation,
                        resolvedPlace = candidateScore.resolvedPlace,
                        score = candidateScore.score
                    )
                }
            )
        }
    }

    private suspend fun requestAmapFirstCandidateScores(
        request: DecisionEngineRequest,
        avoidNames: List<String>,
        weatherProfile: DecisionWeatherProfile
    ): Result<List<CandidateScore>> {
        return runCatching {
            val origin = com.example.dateapp.data.environment.UserLocationSnapshot(
                label = request.environment.userLocationLabel,
                latitude = request.environment.latitude,
                longitude = request.environment.longitude,
                source = request.environment.locationSource
            )
            val limit = if (request.useCandidateBatch) {
                BACKGROUND_CANDIDATE_LIMIT * 2
            } else {
                FRONT_CANDIDATE_LIMIT
            }
            val poiCandidates = routePlanningRepository.searchDecisionPoiCandidates(
                category = request.targetCategory,
                topicId = request.recommendationTopic?.id,
                topicLabel = request.recommendationTopic?.label,
                topicHint = request.recommendationTopic?.compactHint,
                origin = origin,
                currentTime = request.environment.currentTime,
                avoidNames = (avoidNames + request.hardAvoidNames).distinct(),
                limit = limit
            )
            require(poiCandidates.isNotEmpty()) {
                "AMap returned no usable POI candidates"
            }
            val locallyRankedCandidates = poiCandidates
                .mapNotNull { poiCandidate ->
                    scoreAmapFirstCandidate(
                        recommendation = poiCandidate.toFallbackRecommendation(request.targetCategory),
                        poi = poiCandidate,
                        request = request,
                        weatherProfile = weatherProfile,
                        avoidNames = avoidNames
                    )
                }
                .sortedByDescending { it.score }

            require(locallyRankedCandidates.isNotEmpty()) {
                "AMap candidates were filtered out by local scoring"
            }

            val aiSelectedCandidate = chooseCandidateWithAi(
                request = request,
                rankedCandidates = locallyRankedCandidates,
                weatherProfile = weatherProfile,
                avoidNames = avoidNames
            )
            val batchCardLimit = if (request.useCandidateBatch) {
                BACKGROUND_CANDIDATE_LIMIT
            } else {
                1
            }
            val polishLimit = if (request.useCandidateBatch) {
                BACKGROUND_AI_POLISH_LIMIT
            } else {
                FRONT_AI_POLISH_LIMIT
            }
            val orderedCandidates = buildList {
                aiSelectedCandidate?.let(::add)
                addAll(
                    locallyRankedCandidates.filterNot { rankedCandidate ->
                        aiSelectedCandidate?.let { selectedCandidate ->
                            isSimilarPlaceName(
                                rankedCandidate.recommendation.name,
                                selectedCandidate.recommendation.name
                            )
                        } == true
                    }
                )
            }

            val candidates = orderedCandidates
                .take(batchCardLimit)
                .mapIndexed { index, rankedCandidate ->
                    if (
                        aiSelectedCandidate != null &&
                        isSimilarPlaceName(
                            rankedCandidate.recommendation.name,
                            aiSelectedCandidate.recommendation.name
                        )
                    ) {
                        return@mapIndexed rankedCandidate
                    }
                    if (index >= polishLimit) {
                        return@mapIndexed rankedCandidate
                    }
                    val poiCandidate = rankedCandidate.poi ?: return@mapIndexed rankedCandidate
                    val recommendation = polishPoiCandidate(
                        request = request,
                        poi = poiCandidate
                    )
                    scoreAmapFirstCandidate(
                        recommendation = recommendation,
                        poi = poiCandidate,
                        request = request,
                        weatherProfile = weatherProfile,
                        avoidNames = avoidNames
                    ) ?: rankedCandidate
                }
                .filter { candidateScore ->
                    isCandidateGoodEnoughForDisplay(
                        candidate = candidateScore,
                        request = request
                    )
                }

            if (aiSelectedCandidate != null) {
                candidates
            } else {
                candidates.sortedByDescending { it.score }
            }
        }
    }

    private fun isCandidateGoodEnoughForDisplay(
        candidate: CandidateScore,
        request: DecisionEngineRequest
    ): Boolean {
        if (request.useCandidateBatch) {
            return true
        }
        if (candidate.score < FRONT_MIN_DISPLAY_SCORE) {
            Log.d(
                TAG,
                "decision source=ENGINE_FRONT_SCORE_REJECT name=${candidate.recommendation.name} score=${candidate.score}"
            )
            return false
        }
        val distanceMeters = candidate.resolvedPlace.directDistanceMeters
        if (
            request.targetCategory == "play" &&
            distanceMeters != null &&
            !isFrontPlayDistanceAllowed(candidate)
        ) {
            Log.d(
                TAG,
                "decision source=ENGINE_FRONT_DISTANCE_REJECT name=${candidate.recommendation.name} distance=$distanceMeters score=${candidate.score}"
            )
            return false
        }
        return true
    }

    private fun isFrontPlayDistanceAllowed(candidate: CandidateScore): Boolean {
        return placePolicy.isFrontPlayDistanceAllowed(candidate.recommendation, candidate.resolvedPlace)
    }

    private suspend fun chooseCandidateWithAi(
        request: DecisionEngineRequest,
        rankedCandidates: List<CandidateScore>,
        weatherProfile: DecisionWeatherProfile,
        avoidNames: List<String>
    ): CandidateScore? {
        val poiCandidates = rankedCandidates
            .mapNotNull { it.poi }
            .take(if (request.useCandidateBatch) AI_CHOICE_BACKGROUND_CANDIDATE_LIMIT else AI_CHOICE_FRONT_CANDIDATE_LIMIT)
        if (poiCandidates.size <= 1) {
            return null
        }

        val timeoutMillis = if (request.useCandidateBatch) {
            AI_CHOICE_BACKGROUND_TIMEOUT_MS
        } else {
            AI_CHOICE_FAST_TIMEOUT_MS
        }
        val choice = withTimeoutOrNull(timeoutMillis) {
            aiRepository.chooseDecisionPoiFromCandidates(
                environment = request.environment,
                targetCategory = request.targetCategory,
                candidates = poiCandidates,
                recommendationTopic = request.recommendationTopic,
                preferenceProfile = request.preferenceProfile,
                avoidNames = (avoidNames + request.hardAvoidNames).distinct()
            ).getOrElse { throwable ->
                Log.d(
                    TAG,
                    "decision source=AI_CHOICE_FAILED reason=${throwable.message ?: throwable.javaClass.simpleName}"
                )
                null
            }
        }

        if (choice == null) {
            Log.d(
                TAG,
                "decision source=AI_CHOICE_TIMEOUT timeout=${timeoutMillis}ms candidates=${poiCandidates.size}"
            )
            return null
        }

        val selectedPoi = poiCandidates.getOrNull(choice.index)
        if (selectedPoi == null) {
            Log.d(TAG, "decision source=AI_CHOICE_INVALID_INDEX index=${choice.index}")
            return null
        }

        val selectedRecommendation = choice.toRecommendation(
            poi = selectedPoi,
            targetCategory = request.targetCategory
        )
        val scoredCandidate = scoreAmapFirstCandidate(
            recommendation = selectedRecommendation,
            poi = selectedPoi,
            request = request,
            weatherProfile = weatherProfile,
            avoidNames = avoidNames
        )
        if (scoredCandidate == null) {
            Log.d(
                TAG,
                "decision source=AI_CHOICE_REJECTED name=${selectedRecommendation.name}"
            )
            return null
        }
        val localBestScore = rankedCandidates.firstOrNull()?.score ?: scoredCandidate.score
        if (scoredCandidate.score + AI_CHOICE_MAX_LOCAL_SCORE_GAP < localBestScore) {
            Log.d(
                TAG,
                "decision source=AI_CHOICE_TOO_WEAK name=${selectedRecommendation.name} score=${scoredCandidate.score} localBest=$localBestScore"
            )
            return null
        }

        val boostedScore = scoredCandidate.score + AI_CHOICE_BONUS
        Log.d(
            TAG,
            "decision source=AI_CHOICE_SELECTED name=${selectedRecommendation.name} base=${scoredCandidate.score} boosted=$boostedScore tag=${selectedRecommendation.tag} reason=${choice.reason.orEmpty()}"
        )
        return scoredCandidate.copy(score = boostedScore)
    }

    private fun AiPoiChoice.toRecommendation(
        poi: DecisionPoiCandidate,
        targetCategory: String
    ): AiDecisionRecommendation {
        return AiDecisionRecommendation(
            name = poi.displayName,
            amapSearchKeyword = poi.routeKeyword,
            imageUrl = null,
            distanceDescription = poi.distanceLabel,
            tag = tag?.trim()?.takeIf { it.isNotEmpty() } ?: poi.tag,
            intro = intro?.trim()?.takeIf { it.isNotEmpty() } ?: poi.fallbackIntro(targetCategory)
        )
    }

    private suspend fun polishPoiCandidate(
        request: DecisionEngineRequest,
        poi: DecisionPoiCandidate
    ): AiDecisionRecommendation {
        val aiPolished = withTimeoutOrNull(AI_POLISH_TIMEOUT_MS) {
            aiRepository.polishDecisionPoi(
                environment = request.environment,
                targetCategory = request.targetCategory,
                poi = poi,
                recommendationTopic = request.recommendationTopic,
                preferenceProfile = request.preferenceProfile
            ).getOrNull()
        }
        return aiPolished ?: poi.toFallbackRecommendation(request.targetCategory)
    }

    private fun DecisionPoiCandidate.toFallbackRecommendation(targetCategory: String): AiDecisionRecommendation {
        return AiDecisionRecommendation(
            name = displayName,
            amapSearchKeyword = routeKeyword,
            imageUrl = null,
            distanceDescription = distanceLabel,
            tag = tag,
            intro = fallbackIntro(targetCategory)
        )
    }

    private fun DecisionPoiCandidate.fallbackIntro(targetCategory: String): String {
        val text = listOf(displayName, tag.orEmpty(), typeDescription.orEmpty(), address.orEmpty())
            .joinToString(" ")
            .lowercase(Locale.ROOT)

        fun hasAny(vararg keywords: String): Boolean = keywords.any(text::contains)

        if (targetCategory == "meal") {
            return when {
                hasAny("披萨", "pizza") -> "热披萨和小食都很稳。"
                hasAny("泰", "越南", "东南亚", "墨西哥", "印度", "韩", "日料", "寿司") ->
                    "异国风味更有新鲜感。"
                hasAny("牛肉粉", "米粉", "螺蛳粉", "热干面", "面馆", "粉面") ->
                    "粉面热乎，吃起来顺口。"
                hasAny("汤包", "锅贴", "生煎", "馄饨", "小吃") ->
                    "小吃轻松不重，边聊边吃。"
                hasAny("烤肉", "烧烤", "火锅", "串") -> "烟火气很足，越吃越热闹。"
                hasAny("咖啡", "甜品", "蛋糕", "面包", "brunch") ->
                    "甜口和饮品都有，能慢慢坐。"
                else -> pickFallbackIntro(
                    "小馆子烟火气更足。",
                    "一顿饭的节奏刚刚好。",
                    "口味日常，不用太纠结。"
                )
            }
        }

        return when {
            hasAny("猫咖", "狗咖", "宠物", "萌宠", "柯基", "柴犬") ->
                "小动物很多，气氛软乎。"
            hasAny("diy", "手作", "烘焙", "银饰", "陶艺", "簇绒", "tufting", "拼豆") ->
                "一起动手，能带走成品。"
            hasAny("剧场", "脱口秀", "喜剧", "小剧场", "演出") ->
                "小剧场现场感更近。"
            hasAny("公园", "江滩", "码头", "散步", "湖", "绿道") ->
                "水边视野开阔，风也舒服。"
            hasAny("博物馆", "纪念馆", "美术馆", "展览", "艺术", "空间", "画廊") ->
                "展陈安静，细节值得慢看。"
            hasAny("文创", "买手", "唱片", "黑胶", "潮玩", "盲盒", "玩具", "生活馆") ->
                "小物件很多，容易有发现。"
            hasAny("花市", "花艺", "鲜花", "植物", "花园", "绿植") ->
                "花草气息轻，拍照也自然。"
            hasAny("照相", "写真", "自拍", "大头贴", "摄影") ->
                "顺手留下一张新照片。"
            hasAny("电玩城", "街机", "桌游", "密室", "剧本", "vr", "保龄球", "射箭") ->
                "互动感更强，一起玩更有劲。"
            else -> pickFallbackIntro(
                "小地方有自己的气质。",
                "过去逛一圈也有收获。",
                "轻轻松松换个新场景。"
            )
        }
    }

    private fun DecisionPoiCandidate.pickFallbackIntro(vararg options: String): String {
        val index = (displayName.hashCode().ushr(1)) % options.size
        return options[index]
    }

    private fun scoreAmapFirstCandidate(
        recommendation: AiDecisionRecommendation,
        poi: DecisionPoiCandidate,
        request: DecisionEngineRequest,
        weatherProfile: DecisionWeatherProfile,
        avoidNames: List<String>
    ): CandidateScore? {
        if (isInvalidRecommendationName(recommendation, request.targetCategory)) {
            Log.d(
                TAG,
                "decision source=ENGINE_INVALID_NAME_SKIP targetCategory=${request.targetCategory} name=${recommendation.name}"
            )
            return null
        }
        val isCurrentCardRepeat = request.currentCardName?.let { currentName ->
            isSimilarPlaceName(recommendation.name, currentName)
        } ?: false
        val isDislikedRepeat = isRecentlyRecommended(recommendation.name, request.hardAvoidNames)
        val isSoftRepeat = isRecentlyRecommended(recommendation.name, avoidNames)
        if (isDislikedRepeat) {
            Log.d(
                TAG,
                "decision source=ENGINE_DISLIKED_HARD_SKIP targetCategory=${request.targetCategory} name=${recommendation.name}"
            )
            return null
        }
        if (request.useCandidateBatch && (isCurrentCardRepeat || isSoftRepeat)) {
            Log.d(
                TAG,
                "decision source=ENGINE_BATCH_REPEAT_SKIP targetCategory=${request.targetCategory} name=${recommendation.name}"
            )
            return null
        }

        val timeScore = recommendationTimeSuitability(
            recommendation = recommendation,
            category = request.targetCategory,
            hour = request.environment.currentTime.hour
        )
        if (timeScore < 0) {
            Log.d(
                TAG,
                "decision source=ENGINE_TIME_MISMATCH time=${request.environment.currentTimeLabel} targetCategory=${request.targetCategory} name=${recommendation.name}"
            )
            return null
        }
        val weatherScore = recommendationWeatherSuitability(
            recommendation = recommendation,
            category = request.targetCategory,
            weatherProfile = weatherProfile
        )
        val weatherRiskActive = weatherProfile.isSevere ||
            weatherProfile.isRainy ||
            weatherProfile.isHot ||
            weatherProfile.isCold
        if (weatherScore < -1 || (weatherRiskActive && request.targetCategory == "play" && weatherScore < 0)) {
            Log.d(
                TAG,
                "decision source=ENGINE_WEATHER_MISMATCH weather=${weatherProfile.label} score=$weatherScore targetCategory=${request.targetCategory} name=${recommendation.name}"
            )
            return null
        }

        val resolvedPlace = ResolvedPlace(
            displayName = poi.routeLabel,
            routeKeyword = poi.routeKeyword,
            latitude = poi.latitude,
            longitude = poi.longitude,
            directDistanceMeters = poi.directDistanceMeters,
            distanceLabel = poi.distanceLabel,
            confidence = PlaceConfidence.HIGH,
            source = poi.source,
            isSuspiciousDistanceMismatch = false,
            isOpenNow = poi.isOpenNow,
            openingHours = poi.openingHours
        )
        if (isLikelyClosedDayOnlyVenue(request.targetCategory, recommendation, resolvedPlace, request.environment.currentTime.hour)) {
            Log.d(
                TAG,
                "decision source=ENGINE_DAY_VENUE_UNKNOWN_SKIP targetCategory=${request.targetCategory} name=${recommendation.name} hour=${request.environment.currentTime.hour}"
            )
            return null
        }
        if (!isDistanceAcceptable(recommendation, request.targetCategory, resolvedPlace)) {
            Log.d(
                TAG,
                "decision source=ENGINE_DISTANCE_MISMATCH targetCategory=${request.targetCategory} name=${recommendation.name} resolvedDistance=${resolvedPlace.directDistanceMeters}"
            )
            return null
        }

        val repeatPenalty = when {
            isCurrentCardRepeat -> CURRENT_CARD_REPEAT_PENALTY
            isSoftRepeat -> SOFT_REPEAT_PENALTY
            else -> 0
        }
        val personalScore = personalizationScore(
            recommendation = recommendation,
            resolvedPlace = resolvedPlace,
            request = request,
            poiTypeHint = poi.typeDescription
        )
        if (personalScore <= PERSONALIZATION_REJECTION_THRESHOLD) {
            Log.d(
                TAG,
                "decision source=ENGINE_PERSONAL_REJECT targetCategory=${request.targetCategory} name=${recommendation.name} score=$personalScore"
            )
            return null
        }

        val baseScore = buildCandidateScore(
            timeScore = timeScore,
            weatherScore = weatherScore,
            resolvedPlace = resolvedPlace,
            category = request.targetCategory
        )
        val areaScore = diningAreaPreferenceScore(
            category = request.targetCategory,
            resolvedPlace = resolvedPlace
        )
        val poiTypeScore = poiTypeReliabilityScore(
            category = request.targetCategory,
            poi = poi
        )
        val dateAppealScore = dateAppealScore(
            category = request.targetCategory,
            recommendation = recommendation,
            resolvedPlace = resolvedPlace
        )
        val studentCoupleScore = studentCoupleProfileScore(
            category = request.targetCategory,
            recommendation = recommendation,
            resolvedPlace = resolvedPlace,
            request = request
        )
        if (studentCoupleScore <= STUDENT_COUPLE_REJECTION_THRESHOLD) {
            Log.d(
                TAG,
                "decision source=ENGINE_STUDENT_PROFILE_REJECT targetCategory=${request.targetCategory} name=${recommendation.name} score=$studentCoupleScore distance=${resolvedPlace.directDistanceMeters}"
            )
            return null
        }
        val finalScore = baseScore + personalScore + areaScore + poiTypeScore + dateAppealScore + studentCoupleScore - repeatPenalty
        Log.d(
            TAG,
            "decision source=ENGINE_SCORE targetCategory=${request.targetCategory} name=${recommendation.name} base=$baseScore time=$timeScore weather=$weatherScore personal=$personalScore area=$areaScore poiType=$poiTypeScore appeal=$dateAppealScore student=$studentCoupleScore repeatPenalty=$repeatPenalty final=$finalScore distance=${resolvedPlace.directDistanceMeters} source=${resolvedPlace.source}"
        )

        return CandidateScore(
            recommendation = recommendation,
            resolvedPlace = resolvedPlace,
            poi = poi,
            score = finalScore
        )
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

    private fun personalizationScore(
        recommendation: AiDecisionRecommendation,
        resolvedPlace: ResolvedPlace,
        request: DecisionEngineRequest,
        poiTypeHint: String? = null
    ): Int {
        val score = candidateScorer.personalizationScore(
            recommendation = recommendation,
            resolvedPlace = resolvedPlace,
            category = request.targetCategory,
            profile = request.preferenceProfile,
            poiTypeHint = poiTypeHint
        )
        if (score.score != 0) {
            Log.d(
                TAG,
                "decision source=ENGINE_PERSONAL_SCORE targetCategory=${request.targetCategory} name=${recommendation.name} score=${score.score} traits=${score.traits.joinToString(limit = 6)} events=${score.eventCount}"
            )
        }
        return score.score
    }

    private fun diningAreaPreferenceScore(
        category: String,
        resolvedPlace: ResolvedPlace
    ): Int {
        return candidateScorer.diningAreaPreferenceScore(category, resolvedPlace)
    }

    private fun poiTypeReliabilityScore(
        category: String,
        poi: DecisionPoiCandidate?
    ): Int {
        return candidateScorer.poiTypeReliabilityScore(category, poi)
    }

    private fun dateAppealScore(
        category: String,
        recommendation: AiDecisionRecommendation,
        resolvedPlace: ResolvedPlace
    ): Int {
        return candidateScorer.dateAppealScore(category, recommendation, resolvedPlace)
    }

    private fun studentCoupleProfileScore(
        category: String,
        recommendation: AiDecisionRecommendation,
        resolvedPlace: ResolvedPlace,
        request: DecisionEngineRequest
    ): Int {
        val score = candidateScorer.studentCoupleProfileScore(
            category = category,
            recommendation = recommendation,
            resolvedPlace = resolvedPlace,
            request = request
        )

        if (score.totalScore != 0) {
            Log.d(
                TAG,
                "decision source=ENGINE_STUDENT_PROFILE_SCORE targetCategory=$category name=${recommendation.name} transit=${score.transitScore} classic=${score.classicScore} surprise=${score.surpriseScore} campus=${score.campusScore} budget=${score.budgetScore} final=${score.totalScore}"
            )
        }
        return score.totalScore
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
            val obviousDefaultSignals = WuhanKnowledgeConfig.aiAvoidDefaultSignals.any(normalizedName::contains)
            if (obviousDefaultSignals) {
                return true
            }

            val ordinaryBookstore = normalizedName.contains("书店") ||
                normalizedName.contains("书城") ||
                normalizedName.contains("书屋")
            if (ordinaryBookstore) {
                return true
            }

            val vagueOnly = listOf(
                "商圈",
                "商业区",
                "商业圈",
                "片区",
                "区域",
                "后侧",
                "后街",
                "小花园",
                "樱花林",
                "角落",
                "入口",
                "附近"
            ).any(normalizedName::contains)
            if (vagueOnly) {
                return true
            }

            val parkInfrastructure = listOf(
                "休息亭",
                "凉亭",
                "纪念林",
                "发展林",
                "主题林",
                "红枫林",
                "海棠林",
                "树林",
                "公厕",
                "厕所",
                "洗手间",
                "停车场",
                "管理处"
            ).any(normalizedName::contains)
            if (parkInfrastructure) {
                return true
            }

            val lowDateAppealDestination = listOf(
                "惨案",
                "遗址",
                "纪念碑",
                "烈士",
                "军械库",
                "车站遗址",
                "志愿者",
                "义务植树",
                "培训",
                "辅导",
                "留学",
                "教学",
                "生育"
            ).any(normalizedName::contains)
            if (lowDateAppealDestination) {
                return true
            }

            val serviceVendorDestination = listOf(
                "广告",
                "快印",
                "打印",
                "图文",
                "喷绘",
                "传媒",
                "会展",
                "展览服务",
                "策划",
                "工程",
                "设备",
                "3d打印",
                "建设和发展成就",
                "社区民俗馆",
                "群众艺术馆",
                "文化馆",
                "展厅",
                "闸口",
                "烟酒",
                "酒陈香",
                "陈香烟酒",
                "1栋"
            ).any(normalizedName::contains)
            if (serviceVendorDestination) {
                return true
            }

            val recommendationText = buildString {
                append(name)
                append(' ')
                append(recommendation.tag.orEmpty())
                append(' ')
                append(recommendation.intro.orEmpty())
            }.lowercase(Locale.ROOT)
            val foodOnlySignals = listOf(
                "热干面",
                "蔡林记",
                "面馆",
                "粉面",
                "牛肉粉",
                "汤包",
                "包子",
                "早餐",
                "宵夜",
                "烧烤",
                "烤肉",
                "火锅",
                "串串",
                "餐厅",
                "饭店",
                "小吃",
                "卤味",
                "炸鸡",
                "寿喜锅",
                "拉面",
                "牛排"
            ).any(recommendationText::contains)
            if (foodOnlySignals) {
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
        return candidateScorer.buildBaseScore(timeScore, weatherScore, resolvedPlace, category)
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

    private fun isLikelyClosedDayOnlyVenue(
        category: String,
        recommendation: AiDecisionRecommendation,
        resolvedPlace: ResolvedPlace,
        hour: Int
    ): Boolean {
        return placePolicy.isLikelyClosedDayOnlyVenue(category, recommendation, resolvedPlace, hour)
    }

    private fun recommendationTimeSuitability(
        recommendation: AiDecisionRecommendation,
        category: String,
        hour: Int
    ): Int {
        return candidateScorer.timeSuitability(recommendation, category, hour)
    }

    private fun recommendationWeatherSuitability(
        recommendation: AiDecisionRecommendation,
        category: String,
        weatherProfile: DecisionWeatherProfile
    ): Int {
        return candidateScorer.weatherSuitability(recommendation, category, weatherProfile)
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
        return DecisionNameMatcher.isSimilarPlaceName(first, second)
    }

    private data class CandidateScore(
        val recommendation: AiDecisionRecommendation,
        val resolvedPlace: ResolvedPlace,
        val poi: DecisionPoiCandidate? = null,
        val score: Int
    )

    companion object {
        private const val TAG = "DecisionEngine"
        private const val POI_FIRST_FAST_TIMEOUT_MS = 9_000L
        private const val POI_FIRST_BACKGROUND_TIMEOUT_MS = 15_000L
        private const val AI_POLISH_TIMEOUT_MS = 1_500L
        private const val AI_CHOICE_FAST_TIMEOUT_MS = 2_200L
        private const val AI_CHOICE_BACKGROUND_TIMEOUT_MS = 7_500L
        private const val AI_CHOICE_FRONT_CANDIDATE_LIMIT = 5
        private const val AI_CHOICE_BACKGROUND_CANDIDATE_LIMIT = 6
        private const val AI_CHOICE_BONUS = 8
        private const val AI_CHOICE_MAX_LOCAL_SCORE_GAP = 8
        private const val FRONT_AI_POLISH_LIMIT = 0
        private const val BACKGROUND_AI_POLISH_LIMIT = 2
        private const val BACKGROUND_CANDIDATE_LIMIT = 2
        private const val FRONT_CANDIDATE_LIMIT = 5
        private const val FRONT_MIN_DISPLAY_SCORE = 12
        private const val SOFT_REPEAT_PENALTY = 5
        private const val CURRENT_CARD_REPEAT_PENALTY = 8
        private const val PERSONALIZATION_REJECTION_THRESHOLD = -8
        private const val STUDENT_COUPLE_REJECTION_THRESHOLD = -11
    }
}
