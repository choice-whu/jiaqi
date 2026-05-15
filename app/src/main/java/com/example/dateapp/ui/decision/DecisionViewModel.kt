package com.example.dateapp.ui.decision

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.dateapp.data.WishRepository
import com.example.dateapp.data.decision.DecisionEngine
import com.example.dateapp.data.decision.DecisionEngineCandidate
import com.example.dateapp.data.decision.DecisionEngineRequest
import com.example.dateapp.data.decision.DecisionWeatherProfile
import com.example.dateapp.data.environment.DecisionEnvironmentRepository
import com.example.dateapp.data.environment.DecisionEnvironmentSnapshot
import com.example.dateapp.data.local.WishItem
import com.example.dateapp.data.remote.AiDecisionRecommendation
import com.example.dateapp.data.recommendation.RecommendationFeedbackStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.os.SystemClock
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class DecisionSource {
    LOCAL,
    AI
}

enum class DecisionMode(
    val category: String,
    val label: String
) {
    MEAL("meal", "餐厅"),
    PLAY("play", "玩乐")
}

data class DecisionCardUiModel(
    val id: String,
    val localWishId: Int? = null,
    val title: String,
    val category: String,
    val locationLabel: String?,
    val routeKeyword: String?,
    val distanceDescription: String?,
    val tag: String?,
    val imageUrl: String?,
    val latitude: Double?,
    val longitude: Double?,
    val source: DecisionSource,
    val sourceLabel: String,
    val momentLabel: String,
    val supportingText: String,
    val contextLine: String? = null
)

data class DecisionUiState(
    val selectedCard: DecisionCardUiModel? = null,
    val availableCount: Int = 0,
    val isAiSearching: Boolean = false,
    val decisionMode: DecisionMode = DecisionMode.PLAY,
    val savedCardIds: Set<String> = emptySet()
)

class DecisionViewModel(
    private val repository: WishRepository,
    private val decisionEngine: DecisionEngine,
    private val environmentRepository: DecisionEnvironmentRepository,
    private val recommendationFeedbackStore: RecommendationFeedbackStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(DecisionUiState())
    val uiState: StateFlow<DecisionUiState> = _uiState.asStateFlow()

    private var latestLocalWishes: List<WishItem> = emptyList()
    private var cachedEnvironmentSnapshot: DecisionEnvironmentSnapshot? = null
    private val recentDecisionNames = ArrayDeque<String>().apply {
        addAll(recommendationFeedbackStore.recentRecommendedNames())
    }
    private val dislikedDecisionNames = ArrayDeque<String>().apply {
        addAll(recommendationFeedbackStore.dislikedNames())
    }
    private val recentAiCards = ArrayDeque<DecisionCardUiModel>()
    private val aiCacheQueues = mutableMapOf(
        DecisionMode.MEAL.category to ArrayDeque<CachedAiCard>(),
        DecisionMode.PLAY.category to ArrayDeque<CachedAiCard>()
    )
    private var prefetchJob: Job? = null
    private var prefetchDeferred: Deferred<DecisionCardUiModel?>? = null
    private var prefetchCategory: String? = null
    private var cachedEnvironmentCapturedAtMs: Long = 0L
    private var dislikedMealFeedbackCount = recommendationFeedbackStore.recentCategoryDislikeCount("meal")
    private var dislikedPlayFeedbackCount = recommendationFeedbackStore.recentCategoryDislikeCount("play")

    init {
        viewModelScope.launch {
            repository.getAllUnvisitedWishItems().collect { wishItems ->
                latestLocalWishes = wishItems
                _uiState.update { currentState ->
                    currentState.copy(availableCount = wishItems.size)
                }
            }
        }

        prewarmEnvironmentSnapshot()
        scheduleAiPrefetch()
    }

    fun drawAnotherWish() {
        if (_uiState.value.isAiSearching) {
            return
        }

        viewModelScope.launch {
            requestAiDecision(_uiState.value.decisionMode.category)
        }
    }

    fun setDecisionMode(mode: DecisionMode) {
        if (_uiState.value.decisionMode == mode) {
            return
        }

        _uiState.update { it.copy(decisionMode = mode) }
        scheduleAiPrefetch(force = true)
    }

    fun insertDemoWishes() {
        viewModelScope.launch {
            if (latestLocalWishes.isNotEmpty()) {
                return@launch
            }

            val now = System.currentTimeMillis()
            demoWishTemplates.forEachIndexed { index, template ->
                repository.insertWishItem(
                    template.copy(
                        addedTimestamp = now - index * 60_000L
                    )
                )
            }
        }
    }

    fun saveDecisionCardToWishPool(card: DecisionCardUiModel) {
        if (card.source == DecisionSource.LOCAL || _uiState.value.savedCardIds.contains(card.id)) {
            _uiState.update { it.copy(savedCardIds = it.savedCardIds + card.id) }
            recordPositivePreference(card, RecommendationFeedbackStore.ACTION_SAVE)
            return
        }

        viewModelScope.launch {
            val duplicated = latestLocalWishes.any { wish ->
                wish.title == card.title && wish.category == card.category
            }
            if (!duplicated) {
                repository.insertWishItem(
                    WishItem(
                        title = card.title,
                        category = card.category,
                        locationKeyword = card.routeKeyword ?: card.locationLabel ?: card.title,
                        latitude = card.latitude,
                        longitude = card.longitude,
                        source = "ai_discovery",
                        addedTimestamp = System.currentTimeMillis()
                    )
                )
                Log.d(TAG, "decision source=WISH_POOL_SAVE title=${card.title} category=${card.category}")
            } else {
                Log.d(TAG, "decision source=WISH_POOL_DUPLICATE title=${card.title}")
            }
            recordPositivePreference(card, RecommendationFeedbackStore.ACTION_SAVE)

            _uiState.update {
                it.copy(savedCardIds = it.savedCardIds + card.id)
            }
        }
    }

    fun markDecisionNavigation(card: DecisionCardUiModel) {
        recordPositivePreference(card, RecommendationFeedbackStore.ACTION_NAVIGATE)
        Log.d(TAG, "decision source=PREFERENCE_NAVIGATE title=${card.title} category=${card.category}")
    }

    fun markDecisionExternalSearch(card: DecisionCardUiModel) {
        recordPositivePreference(card, RecommendationFeedbackStore.ACTION_EXTERNAL_SEARCH)
        Log.d(TAG, "decision source=PREFERENCE_EXTERNAL_SEARCH title=${card.title} category=${card.category}")
    }

    fun markDecisionNotInterested(card: DecisionCardUiModel) {
        if (_uiState.value.isAiSearching) {
            return
        }

        rememberNotInterested(card)
        recommendationFeedbackStore.recordNotInterested(
            title = card.title,
            category = card.category
        )
        dislikedMealFeedbackCount = recommendationFeedbackStore.recentCategoryDislikeCount("meal")
        dislikedPlayFeedbackCount = recommendationFeedbackStore.recentCategoryDislikeCount("play")

        Log.d(
            TAG,
            "decision source=NOT_INTERESTED title=${card.title} category=${card.category} mealDislikes=$dislikedMealFeedbackCount playDislikes=$dislikedPlayFeedbackCount"
        )

        viewModelScope.launch {
            val preferredCategory = _uiState.value.decisionMode.category
            dropCachedCardsMatching(card.title)
            _uiState.update {
                it.copy(
                    selectedCard = null,
                    isAiSearching = true,
                    availableCount = latestLocalWishes.size
                )
            }
            requestAiDecision(preferredCategory)
        }
    }

    private suspend fun requestAiDecision(targetCategory: String) {
        _uiState.update {
            it.copy(
                isAiSearching = true,
                availableCount = latestLocalWishes.size
            )
        }

        consumePrefetchedAiCard(targetCategory)?.let { prefetchedCard ->
            rememberDecision(prefetchedCard)
            _uiState.update {
                it.copy(
                    selectedCard = prefetchedCard,
                    availableCount = latestLocalWishes.size,
                    isAiSearching = false
                )
            }
            scheduleAiPrefetch(force = true)
            return
        }

        awaitActivePrefetch(targetCategory)?.let { prefetchedCard ->
            Log.d(TAG, "decision source=AI_CACHE_AWAIT_HIT targetCategory=$targetCategory title=${prefetchedCard.title}")
            rememberDecision(prefetchedCard)
            _uiState.update {
                it.copy(
                    selectedCard = prefetchedCard,
                    availableCount = latestLocalWishes.size,
                    isAiSearching = false
                )
            }
            scheduleAiPrefetch(force = true)
            return
        }

        val environment = getFastEnvironmentSnapshot()

        Log.d(
            TAG,
            "decision source=AI_START time=${environment.currentTimeLabel} weather=${environment.weatherCondition} location=${environment.userLocationLabel} targetCategory=$targetCategory locationSource=${environment.locationSource} weatherSource=${environment.weatherSource}"
        )

        val decisionResult = decisionEngine.generateAiDecision(
            DecisionEngineRequest(
                environment = environment,
                targetCategory = targetCategory,
                avoidNames = recentAvoidNames(),
                currentCardName = _uiState.value.selectedCard?.title,
                hardAvoidNames = hardAvoidNames(),
                nearbyMallName = null,
                preferenceProfile = preferenceProfileFor(targetCategory)
            )
        ).getOrElse { throwable ->
            handleAiFailure(
                environment = environment,
                targetCategory = targetCategory,
                reason = throwable.message ?: throwable.javaClass.simpleName
            )
            return
        }

        val recommendation = decisionResult.recommendation
        val resolvedPlace = decisionResult.resolvedPlace
        val aiCard = DecisionCardUiModel(
            id = "ai_${System.currentTimeMillis()}",
            title = recommendation.name,
            category = targetCategory,
            locationLabel = resolvedPlace.displayName,
            routeKeyword = resolvedPlace.routeKeyword,
            distanceDescription = resolvedPlace.distanceLabel,
            tag = recommendation.tag,
            imageUrl = recommendation.imageUrl,
            latitude = resolvedPlace.latitude,
            longitude = resolvedPlace.longitude,
            source = DecisionSource.AI,
            sourceLabel = "AI探索",
            momentLabel = buildMomentLabel(environment.currentTime.hour, targetCategory),
            supportingText = buildAiSupportingText(recommendation, targetCategory),
            contextLine = buildAiContextLine(
                recommendation = recommendation,
                weatherProfile = decisionResult.weatherProfile
            )
        )

        Log.d(
            TAG,
            "decision source=AI_SUCCESS time=${environment.currentTimeLabel} weather=${environment.weatherCondition} location=${environment.userLocationLabel} targetCategory=$targetCategory name=${recommendation.name} tag=${recommendation.tag} aiDistance=${recommendation.distanceDescription} verifiedDistance=${resolvedPlace.distanceLabel} placeConfidence=${resolvedPlace.confidence} candidates=${decisionResult.candidateCount}"
        )

        rememberAiCard(aiCard)
        rememberDecision(aiCard)
        _uiState.update {
            it.copy(
                selectedCard = aiCard,
                availableCount = latestLocalWishes.size,
                isAiSearching = false
            )
        }
        scheduleAiPrefetch(force = true)
    }

    private fun prewarmEnvironmentSnapshot() {
        viewModelScope.launch {
            val warmedSnapshot = withTimeoutOrNull(ENVIRONMENT_PREWARM_TIMEOUT_MS) {
                environmentRepository.getEnvironmentSnapshot()
            } ?: environmentRepository.getCachedOrFallbackEnvironmentSnapshot()
            cacheEnvironmentSnapshot(warmedSnapshot)
            scheduleAiPrefetch(force = true)
        }
    }

    private fun scheduleAiPrefetch(force: Boolean = false) {
        pruneExpiredCachedCards()
        if (_uiState.value.isAiSearching) {
            return
        }
        if (prefetchJob?.isActive == true) {
            return
        }

        val targetCategory = nextPrefetchCategory(force) ?: return
        prefetchCategory = targetCategory
        val deferred = viewModelScope.async {
            val environment = getFastEnvironmentSnapshot()
            val result = decisionEngine.generateAiDecision(
                DecisionEngineRequest(
                    environment = environment,
                    targetCategory = targetCategory,
                    avoidNames = recentAvoidNames(),
                    currentCardName = _uiState.value.selectedCard?.title,
                    hardAvoidNames = hardAvoidNames(),
                    nearbyMallName = null,
                    useCandidateBatch = true,
                    preferenceProfile = preferenceProfileFor(targetCategory)
                )
            )

            val cards = result.getOrNull()?.toAiCards(targetCategory).orEmpty()
            var firstStoredCard: DecisionCardUiModel? = null
            result.onSuccess { decisionResult ->
                var storedCount = 0
                cards.forEach { candidateCard ->
                    if (storeCachedAiCard(candidateCard)) {
                        rememberAiCard(candidateCard)
                        if (firstStoredCard == null) {
                            firstStoredCard = candidateCard
                        }
                        storedCount += 1
                    }
                }
                Log.d(
                    TAG,
                    "decision source=AI_CACHE_READY targetCategory=$targetCategory stored=$storedCount batch=${cards.size} candidates=${decisionResult.candidateCount} cache=${cacheSize(targetCategory)}"
                )
            }.onFailure { throwable ->
                Log.d(
                    TAG,
                    "decision source=AI_CACHE_FAILED targetCategory=$targetCategory reason=${throwable.message ?: throwable.javaClass.simpleName}"
                )
            }
            firstStoredCard
        }
        prefetchDeferred = deferred
        prefetchJob = deferred
        deferred.invokeOnCompletion {
            if (prefetchDeferred === deferred) {
                prefetchDeferred = null
            }
            if (prefetchJob === deferred) {
                prefetchJob = null
            }
            if (prefetchCategory == targetCategory) {
                prefetchCategory = null
            }
            if (!_uiState.value.isAiSearching) {
                scheduleAiPrefetch()
            }
        }
    }

    private fun nextPrefetchCategory(force: Boolean): String? {
        val activeCategory = _uiState.value.decisionMode.category
        val categories = listOf(
            activeCategory,
            DecisionMode.MEAL.category,
            DecisionMode.PLAY.category
        ).distinct()
        return categories.firstOrNull { category ->
            val targetSize = if (category == activeCategory) {
                ACTIVE_CATEGORY_CACHE_TARGET
            } else {
                BACKGROUND_CATEGORY_CACHE_TARGET
            }
            force || cacheSize(category) < targetSize
        }
    }

    private suspend fun awaitActivePrefetch(targetCategory: String): DecisionCardUiModel? {
        val deferred = prefetchDeferred ?: return null
        if (!deferred.isActive) {
            return null
        }
        if (prefetchCategory != targetCategory) {
            return null
        }
        val card = withTimeoutOrNull(ACTIVE_PREFETCH_AWAIT_MS) {
            deferred.await()
        } ?: return null
        return if (
            card.category == targetCategory &&
            dislikedDecisionNames.none { dislikedName -> isSimilarPlaceName(card.title, dislikedName) }
        ) {
            consumePrefetchedAiCard(targetCategory) ?: card.copy(
                id = "ai_prefetch_${System.currentTimeMillis()}",
                momentLabel = buildMomentLabel(currentHour(), targetCategory)
            )
        } else {
            null
        }
    }

    private fun consumePrefetchedAiCard(targetCategory: String): DecisionCardUiModel? {
        pruneExpiredCachedCards()
        val currentTitle = _uiState.value.selectedCard?.title
        val queue = aiCacheQueues.getOrPut(targetCategory) { ArrayDeque() }
        val index = queue.indexOfFirst { cachedCard ->
            val card = cachedCard.card
            card.category == targetCategory &&
                !isSimilarPlaceName(card.title, currentTitle.orEmpty()) &&
                dislikedDecisionNames.none { dislikedName -> isSimilarPlaceName(card.title, dislikedName) }
        }
        if (index < 0) {
            return null
        }

        val card = queue.removeAt(index).card
        Log.d(
            TAG,
            "decision source=AI_CACHE_HIT targetCategory=$targetCategory title=${card.title} remaining=${queue.size}"
        )
        return card.copy(
            id = "ai_cache_${System.currentTimeMillis()}",
            momentLabel = buildMomentLabel(currentHour(), targetCategory)
        )
    }

    private fun storeCachedAiCard(card: DecisionCardUiModel): Boolean {
        val queue = aiCacheQueues.getOrPut(card.category) { ArrayDeque() }
        if (
            queue.any { isSimilarPlaceName(card.title, it.card.title) } ||
            recentDecisionNames.any { isSimilarPlaceName(card.title, it) } ||
            dislikedDecisionNames.any { isSimilarPlaceName(card.title, it) }
        ) {
            Log.d(TAG, "decision source=AI_CACHE_SKIP_REPEAT targetCategory=${card.category} title=${card.title}")
            return false
        }

        queue.addLast(
            CachedAiCard(
                card = card,
                cachedAtMs = SystemClock.elapsedRealtime(),
                environmentKey = cacheEnvironmentKey(cachedEnvironmentSnapshot)
            )
        )
        while (queue.size > ACTIVE_CATEGORY_CACHE_TARGET) {
            queue.removeFirst()
        }
        return true
    }

    private fun recordPositivePreference(
        card: DecisionCardUiModel,
        action: String
    ) {
        recommendationFeedbackStore.recordPositiveFeedback(
            title = card.title,
            category = card.category,
            tag = card.tag,
            action = action
        )
    }

    private fun preferenceProfileFor(category: String): com.example.dateapp.data.recommendation.RecommendationPreferenceProfile {
        val profile = recommendationFeedbackStore.preferenceProfile(category)
        profile.promptHint()?.let { hint ->
            Log.d(
                TAG,
                "decision source=PREFERENCE_PROFILE category=$category events=${profile.eventCount} hint=$hint"
            )
        }
        return profile
    }

    private fun dropCachedCardsMatching(name: String) {
        aiCacheQueues.values.forEach { queue ->
            queue.removeAll { isSimilarPlaceName(it.card.title, name) }
        }
    }

    private fun pruneExpiredCachedCards() {
        val now = SystemClock.elapsedRealtime()
        val activeEnvironmentKey = cacheEnvironmentKey(cachedEnvironmentSnapshot)
        aiCacheQueues.values.forEach { queue ->
            queue.removeAll { cachedCard ->
                now - cachedCard.cachedAtMs > AI_CACHE_TTL_MS ||
                    cachedCard.environmentKey != activeEnvironmentKey
            }
        }
    }

    private fun cacheSize(category: String): Int {
        return aiCacheQueues[category]?.size ?: 0
    }

    private fun cacheEnvironmentKey(snapshot: DecisionEnvironmentSnapshot?): String {
        if (snapshot == null) {
            return "unknown"
        }
        val latBucket = (snapshot.latitude * 100).toInt()
        val lonBucket = (snapshot.longitude * 100).toInt()
        val hourBucket = snapshot.currentTime.hour / 3
        val weatherBucket = decisionEngine.buildWeatherProfile(snapshot).label
        return "$latBucket:$lonBucket:$hourBucket:$weatherBucket"
    }

    private data class CachedAiCard(
        val card: DecisionCardUiModel,
        val cachedAtMs: Long,
        val environmentKey: String
    )

    private suspend fun getFastEnvironmentSnapshot(): DecisionEnvironmentSnapshot {
        cachedEnvironmentSnapshot
            ?.takeIf { SystemClock.elapsedRealtime() - cachedEnvironmentCapturedAtMs <= VIEWMODEL_ENVIRONMENT_CACHE_TTL_MS }
            ?.let { cachedSnapshot ->
                val currentTime = ZonedDateTime.now(appZoneId)
                return cachedSnapshot.copy(
                    currentTime = currentTime,
                    currentTimeLabel = currentTime.format(fullTimeFormatter)
                )
            }

        val fallbackEnvironment = cachedEnvironmentSnapshot
            ?: environmentRepository.getCachedOrFallbackEnvironmentSnapshot()
        val environment = withTimeoutOrNull(ENVIRONMENT_TIMEOUT_MS) {
            environmentRepository.getEnvironmentSnapshot()
        } ?: fallbackEnvironment
        cacheEnvironmentSnapshot(environment)
        return environment
    }

    private fun cacheEnvironmentSnapshot(snapshot: DecisionEnvironmentSnapshot) {
        cachedEnvironmentSnapshot = snapshot
        cachedEnvironmentCapturedAtMs = SystemClock.elapsedRealtime()
    }

    private fun com.example.dateapp.data.decision.DecisionEngineResult.toAiCard(
        targetCategory: String
    ): DecisionCardUiModel {
        return toAiCards(targetCategory).first()
    }

    private fun com.example.dateapp.data.decision.DecisionEngineResult.toAiCards(
        targetCategory: String
    ): List<DecisionCardUiModel> {
        val candidates = rankedCandidates.ifEmpty {
            listOf(
                DecisionEngineCandidate(
                    recommendation = recommendation,
                    resolvedPlace = resolvedPlace,
                    score = 0
                )
            )
        }
        return candidates.mapIndexed { index, candidate ->
            candidate.toAiCard(
                targetCategory = targetCategory,
                environment = environment,
                weatherProfile = weatherProfile,
                index = index
            )
        }
    }

    private fun DecisionEngineCandidate.toAiCard(
        targetCategory: String,
        environment: DecisionEnvironmentSnapshot,
        weatherProfile: DecisionWeatherProfile,
        index: Int
    ): DecisionCardUiModel {
        val recommendation = recommendation
        val resolvedPlace = resolvedPlace
        return DecisionCardUiModel(
            id = "ai_prefetched_${System.currentTimeMillis()}_$index",
            title = recommendation.name,
            category = targetCategory,
            locationLabel = resolvedPlace.displayName,
            routeKeyword = resolvedPlace.routeKeyword,
            distanceDescription = resolvedPlace.distanceLabel,
            tag = recommendation.tag,
            imageUrl = recommendation.imageUrl,
            latitude = resolvedPlace.latitude,
            longitude = resolvedPlace.longitude,
            source = DecisionSource.AI,
            sourceLabel = "AI探索",
            momentLabel = buildMomentLabel(environment.currentTime.hour, targetCategory),
            supportingText = buildAiSupportingText(recommendation, targetCategory),
            contextLine = buildAiContextLine(
                recommendation = recommendation,
                weatherProfile = weatherProfile
            )
        )
    }

    private fun handleAiFailure(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String,
        reason: String
    ) {
        Log.d(TAG, "decision source=AI_FAILED reason=$reason")

        val fallbackCard = recentAiCardFallback(
            environment = environment,
            targetCategory = targetCategory
        ) ?: localWishFallback(
            environment = environment,
            targetCategory = targetCategory
        ) ?: emergencyAiFallback(environment, targetCategory)
        rememberDecision(fallbackCard)
        _uiState.update {
            it.copy(
                selectedCard = fallbackCard,
                availableCount = latestLocalWishes.size,
                isAiSearching = false
            )
        }
        scheduleAiPrefetch()
    }

    private fun rememberAiCard(card: DecisionCardUiModel) {
        if (card.source != DecisionSource.AI || card.id.startsWith("ai_fallback_")) {
            return
        }

        recentAiCards.removeAll { isSimilarPlaceName(card.title, it.title) }
        recentAiCards.addLast(card)
        while (recentAiCards.size > RECENT_AI_CARD_POOL_SIZE) {
            recentAiCards.removeFirst()
        }
    }

    private fun rememberDecision(card: DecisionCardUiModel) {
        rememberRecentName(card.title)
    }

    private fun rememberNotInterested(card: DecisionCardUiModel) {
        rememberDislikedName(card.title)
        rememberRecentName(card.title)
    }

    private fun rememberRecentName(name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            return
        }

        recentDecisionNames.removeAll { isSimilarPlaceName(trimmedName, it) }
        recentDecisionNames.addLast(trimmedName)
        recommendationFeedbackStore.recordRecommended(trimmedName)
        while (recentDecisionNames.size > RECENT_RECOMMENDATION_MEMORY) {
            recentDecisionNames.removeFirst()
        }
    }

    private fun rememberDislikedName(name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            return
        }

        dislikedDecisionNames.removeAll { isSimilarPlaceName(trimmedName, it) }
        dislikedDecisionNames.addLast(trimmedName)
        while (dislikedDecisionNames.size > DISLIKED_RECOMMENDATION_MEMORY) {
            dislikedDecisionNames.removeFirst()
        }
    }

    private fun recentAvoidNames(): List<String> {
        val currentTitle = _uiState.value.selectedCard?.title
        return (recentDecisionNames.toList() + dislikedDecisionNames.toList() + listOfNotNull(currentTitle))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .takeLast(RECENT_RECOMMENDATION_MEMORY + DISLIKED_RECOMMENDATION_MEMORY)
    }

    private fun hardAvoidNames(): List<String> {
        return dislikedDecisionNames.toList().takeLast(HARD_DISLIKED_RECOMMENDATION_MEMORY)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
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
        return name.lowercase()
            .replace(Regex("[\\s\\p{Punct}（）()【】\\[\\]「」『』《》“”‘’、，。！？；：·-]"), "")
            .replace("武汉", "")
            .replace("湖北", "")
            .replace("旗舰", "")
            .replace("总", "")
            .replace("分", "")
            .replace("店", "")
    }

    private fun buildMomentLabel(hour: Int, category: String): String {
        return when {
            hour in 0..4 -> if (category == "meal") "宵夜时刻" else "夜色去处"
            hour in 5..9 -> if (category == "meal") "早餐灵感" else "清晨去处"
            hour in 10..13 -> if (category == "meal") "饭点推荐" else "午间去处"
            hour in 14..17 -> if (category == "meal") "下午茶时刻" else "下午安排"
            hour in 18..20 -> if (category == "meal") "晚餐安排" else "傍晚去处"
            hour in 21..23 -> if (category == "meal") "夜宵备选" else "夜晚安排"
            else -> if (category == "meal") "这一顿" else "这一站"
        }
    }

    private fun buildAiSupportingText(
        recommendation: AiDecisionRecommendation,
        targetCategory: String
    ): String {
        recommendation.intro?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        return if (targetCategory == "meal") {
            "一家适合当下坐下来吃饭的店，节奏轻松，适合慢慢把这一餐吃完。"
        } else {
            "一个适合现在去逛逛的去处，拍照、走走或短暂停留都会很舒服。"
        }
    }

    private fun buildAiContextLine(
        recommendation: AiDecisionRecommendation,
        weatherProfile: DecisionWeatherProfile
    ): String? {
        if (
            !weatherProfile.isSevere &&
            !weatherProfile.isRainy &&
            !weatherProfile.isHot &&
            !weatherProfile.isCold &&
            !weatherProfile.isFoggy
        ) {
            return null
        }

        val text = recommendationSignalText(recommendation)
        val indoorOrSheltered = containsAnySignal(
            text,
            "室内",
            "商场",
            "购物中心",
            "百货",
            "影院",
            "电影",
            "书店",
            "展",
            "馆",
            "避雨",
            "遮蔽",
            "空调",
            "步行",
            "可达"
        )
        if (!indoorOrSheltered) {
            return null
        }

        return when {
            weatherProfile.isRainy || weatherProfile.isSevere -> "室内更稳"
            weatherProfile.isHot -> "少晒一点"
            weatherProfile.isCold -> "暖一点"
            weatherProfile.isFoggy -> "不靠景观"
            else -> null
        }
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

    private fun containsAnySignal(
        text: String,
        vararg keywords: String
    ): Boolean {
        val normalizedText = text.lowercase()
        return keywords.any(normalizedText::contains)
    }

    private fun buildContextLine(environment: DecisionEnvironmentSnapshot): String {
        val timeText = environment.currentTime.format(shortTimeFormatter)
        return "$timeText · ${environment.weatherCondition} · ${environment.userLocationLabel}"
    }

    private fun recentAiCardFallback(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String
    ): DecisionCardUiModel? {
        val currentTitle = _uiState.value.selectedCard?.title
        val card = recentAiCards
            .asReversed()
            .firstOrNull { cachedCard ->
                cachedCard.category == targetCategory &&
                    !isSimilarPlaceName(cachedCard.title, currentTitle.orEmpty()) &&
                    dislikedDecisionNames.none { dislikedName -> isSimilarPlaceName(cachedCard.title, dislikedName) }
            }
            ?: return null

        Log.d(
            TAG,
            "decision source=AI_CACHED_FALLBACK time=${environment.currentTimeLabel} title=${card.title}"
        )

        return card.copy(
            id = "ai_cached_${System.currentTimeMillis()}",
            sourceLabel = "AI探索",
            momentLabel = buildMomentLabel(environment.currentTime.hour, targetCategory),
            contextLine = card.contextLine ?: buildContextLine(environment)
        )
    }

    private fun localWishFallback(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String
    ): DecisionCardUiModel? {
        val currentTitle = _uiState.value.selectedCard?.title
        val profile = preferenceProfileFor(targetCategory)
        data class ScoredWish(
            val wish: WishItem,
            val personalizationScore: Int,
            val matchesTargetCategory: Boolean,
            val recentlyRecommended: Boolean
        )

        val fallbackCandidates = latestLocalWishes
            .filterNot { wish -> isSimilarPlaceName(wish.title, currentTitle.orEmpty()) }
            .map { wish ->
                ScoredWish(
                    wish = wish,
                    personalizationScore = profile.scoreText(
                        text = listOfNotNull(wish.title, wish.locationKeyword).joinToString(" "),
                        fallbackCategory = wish.category
                    ),
                    matchesTargetCategory = wish.category == targetCategory,
                    recentlyRecommended = recentDecisionNames.any { recentName ->
                        isSimilarPlaceName(wish.title, recentName)
                    }
                )
            }
            .sortedWith(
                compareByDescending<ScoredWish> { it.personalizationScore }
                    .thenByDescending { it.matchesTargetCategory }
                    .thenBy { it.recentlyRecommended }
                    .thenByDescending { it.wish.addedTimestamp }
            )
            .map { it.wish }

        val localWish = fallbackCandidates
            .firstOrNull { wish -> !isStrongDislikedLocalMatch(wish, dislikedDecisionNames) }
            ?: fallbackCandidates.firstOrNull()
            ?: return null

        Log.d(
            TAG,
            "decision source=LOCAL_FALLBACK time=${environment.currentTimeLabel} title=${localWish.title} category=${localWish.category}"
        )

        return localWish.toLocalCard(environment.currentTime.hour).copy(
            id = "local_fallback_${localWish.id}_${System.currentTimeMillis()}",
            sourceLabel = "心愿池",
            contextLine = listOfNotNull(
                localWish.locationKeyword,
                "AI暂时慢了半拍，先从你们想去的地方挑一个"
            ).joinToString(" · ")
        )
    }

    private fun isStrongDislikedLocalMatch(
        wish: WishItem,
        dislikedNames: Iterable<String>
    ): Boolean {
        val wishSignals = listOfNotNull(wish.title, wish.locationKeyword)
        return dislikedNames.any { dislikedName ->
            wishSignals.any { signal -> isSimilarPlaceName(signal, dislikedName) }
        }
    }

    private fun emergencyAiFallback(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String
    ): DecisionCardUiModel {
        val weatherProfile = decisionEngine.buildWeatherProfile(environment)
        val indoorPreferred = weatherProfile.isSevere ||
            weatherProfile.isRainy ||
            weatherProfile.isHot ||
            weatherProfile.isCold ||
            weatherProfile.isFoggy
        val area = environment.userLocationLabel
        val nearbyArea = area.removeSuffix("附近")
        val fallbackTitle = when {
            targetCategory == "meal" -> "在${nearbyArea}附近找一家近一点的舒服小店"
            indoorPreferred -> "去${nearbyArea}附近找个室内小去处"
            environment.currentTime.hour in 5..10 -> "去${nearbyArea}附近找个清爽的早间去处"
            environment.currentTime.hour in 16..19 -> "去${nearbyArea}附近找个适合傍晚逛逛的去处"
            environment.currentTime.hour in 20..23 -> "去${nearbyArea}附近找个晚上也热闹的小去处"
            else -> "去${nearbyArea}附近找个轻松好玩的去处"
        }
        val fallbackSupportingText = when {
            targetCategory == "meal" -> "AI 暂时没返回稳定结果，先把范围收回到当前位置附近，优先选少走路、能坐下来的具体小店。"
            indoorPreferred -> "当前天气更适合室内或遮蔽好的安排，先避开长距离户外，把体验留给舒服的部分。"
            else -> "AI 暂时没返回稳定结果，先给一个就近、轻松、适合当前时间的方向。"
        }

        Log.d(
            TAG,
            "decision source=AI_EMERGENCY_FALLBACK time=${environment.currentTimeLabel} title=$fallbackTitle"
        )

        return DecisionCardUiModel(
            id = "ai_fallback_${System.currentTimeMillis()}",
            title = fallbackTitle,
            category = targetCategory,
            locationLabel = area,
            routeKeyword = area,
            distanceDescription = if (targetCategory == "meal") "尽量就近" else "就近出发",
            tag = if (indoorPreferred && targetCategory == "play") "室内优先" else "当下合适",
            imageUrl = null,
            latitude = environment.latitude,
            longitude = environment.longitude,
            source = DecisionSource.AI,
            sourceLabel = "AI探索",
            momentLabel = buildMomentLabel(environment.currentTime.hour, targetCategory),
            supportingText = fallbackSupportingText,
            contextLine = buildContextLine(environment)
        )
    }

    private fun WishItem.toLocalCard(hour: Int = currentHour()): DecisionCardUiModel {
        return DecisionCardUiModel(
            id = "local_$id",
            localWishId = id,
            title = title,
            category = category,
            locationLabel = locationKeyword,
            routeKeyword = locationKeyword ?: title,
            distanceDescription = null,
            tag = null,
            imageUrl = null,
            latitude = latitude,
            longitude = longitude,
            source = DecisionSource.LOCAL,
            sourceLabel = "心愿池",
            momentLabel = buildMomentLabel(hour, category),
            supportingText = buildLocalSupportingText(),
            contextLine = locationKeyword?.let { "$it · 你们记下过的想去清单" }
        )
    }

    private fun WishItem.buildLocalSupportingText(): String {
        val place = locationKeyword ?: title
        return if (category == "meal") {
            when {
                title.contains("咖啡") -> "$place 一带适合坐下来慢慢聊一会儿的咖啡去处，节奏会很松弛。"
                title.contains("烧烤") -> "$place 一带适合热热闹闹吃一顿的烧烤去处，越到晚上越有气氛。"
                title.contains("火锅") || title.contains("寿喜锅") -> "$place 一带适合吃一顿热腾腾锅物的地方，尤其适合饭点去。"
                title.contains("小酒馆") || title.contains("酒吧") -> "$place 一带适合微醺放松的小酒馆去处，晚上会更对味。"
                else -> "$place 一带一个值得去试试的餐饮去处，适合把这顿饭认真安排上。"
            }
        } else {
            when {
                title.contains("东湖") || title.contains("公园") || title.contains("江滩") || title.contains("散步") ->
                    "$place 是个适合慢慢散步、拍照和放松的地方，不会太费力。"

                title.contains("展") || title.contains("美术馆") || title.contains("博物馆") ->
                    "$place 适合慢慢逛一圈，轻松看点东西，也方便边走边聊。"

                title.contains("酒馆") || title.contains("livehouse", ignoreCase = true) ->
                    "$place 更适合天色暗下来以后去待一会儿，氛围会比白天更完整。"

                else -> "$place 是个适合顺路去打卡、短暂停留放松一下的去处。"
            }
        }
    }

    private fun currentHour(): Int = ZonedDateTime.now(appZoneId).hour

    companion object {
        private const val TAG = "DecisionViewModel"
        private const val ENVIRONMENT_PREWARM_TIMEOUT_MS = 4_200L
        private const val ENVIRONMENT_TIMEOUT_MS = 900L
        private const val ACTIVE_PREFETCH_AWAIT_MS = 7_500L
        private const val VIEWMODEL_ENVIRONMENT_CACHE_TTL_MS = 8 * 60 * 1000L
        private const val AI_CACHE_TTL_MS = 12 * 60 * 1000L
        private const val ACTIVE_CATEGORY_CACHE_TARGET = 3
        private const val BACKGROUND_CATEGORY_CACHE_TARGET = 1
        private const val RECENT_RECOMMENDATION_MEMORY = 36
        private const val DISLIKED_RECOMMENDATION_MEMORY = 64
        private const val HARD_DISLIKED_RECOMMENDATION_MEMORY = 20
        private const val RECENT_AI_CARD_POOL_SIZE = 8

        private val appZoneId = ZoneId.of("Asia/Shanghai")
        private val fullTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val shortTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        private val demoWishTemplates = listOf(
            WishItem(
                title = "去江汉路吃一顿热腾腾的寿喜锅",
                category = "meal",
                locationKeyword = "江汉路",
                latitude = null,
                longitude = null,
                source = "manual_nlp",
                addedTimestamp = 0L
            ),
            WishItem(
                title = "傍晚去东湖边慢慢散步看晚霞",
                category = "play",
                locationKeyword = "东湖",
                latitude = null,
                longitude = null,
                source = "manual_nlp",
                addedTimestamp = 0L
            ),
            WishItem(
                title = "找一家安静的小酒馆坐到夜里",
                category = "play",
                locationKeyword = "黎黄陂路",
                latitude = null,
                longitude = null,
                source = "manual_nlp",
                addedTimestamp = 0L
            )
        )

        fun provideFactory(
            repository: WishRepository,
            decisionEngine: DecisionEngine,
            environmentRepository: DecisionEnvironmentRepository,
            recommendationFeedbackStore: RecommendationFeedbackStore
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(DecisionViewModel::class.java)) {
                        "Unknown ViewModel class: ${modelClass.name}"
                    }
                    return DecisionViewModel(
                        repository = repository,
                        decisionEngine = decisionEngine,
                        environmentRepository = environmentRepository,
                        recommendationFeedbackStore = recommendationFeedbackStore
                    ) as T
                }
            }
        }
    }
}
