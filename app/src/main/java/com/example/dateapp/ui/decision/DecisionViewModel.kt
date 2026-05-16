package com.example.dateapp.ui.decision

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.dateapp.data.WishRepository
import com.example.dateapp.data.WuhanKnowledgeConfig
import com.example.dateapp.data.decision.DecisionEngine
import com.example.dateapp.data.decision.DecisionEngineCandidate
import com.example.dateapp.data.decision.DecisionEngineRequest
import com.example.dateapp.data.decision.DecisionNameMatcher
import com.example.dateapp.data.decision.DecisionWeatherProfile
import com.example.dateapp.data.environment.DecisionEnvironmentRepository
import com.example.dateapp.data.environment.DecisionEnvironmentSnapshot
import com.example.dateapp.data.local.WishItem
import com.example.dateapp.data.remote.AiDecisionRecommendation
import com.example.dateapp.data.recommendation.RecommendationFeedbackStore
import com.example.dateapp.data.recommendation.RecommendationPreferenceProfile
import com.example.dateapp.data.recommendation.RecommendationTopic
import com.example.dateapp.data.recommendation.RecommendationTopicProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
    private val recommendationFeedbackStore: RecommendationFeedbackStore,
    private val recommendationTopicProvider: RecommendationTopicProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(DecisionUiState())
    val uiState: StateFlow<DecisionUiState> = _uiState.asStateFlow()

    private var latestLocalWishes: List<WishItem> = emptyList()
    private val environmentCache = DecisionEnvironmentCache(
        environmentRepository = environmentRepository,
        decisionWeatherLabelProvider = { snapshot -> decisionEngine.buildWeatherProfile(snapshot).label },
        fastTimeoutMs = ENVIRONMENT_TIMEOUT_MS,
        memoryTtlMs = VIEWMODEL_ENVIRONMENT_CACHE_TTL_MS,
        fallbackTtlMs = FALLBACK_ENVIRONMENT_CACHE_TTL_MS
    )
    private val nameTracker = DecisionNameTracker(recommendationFeedbackStore)
    private val recentTopicIds = ArrayDeque<String>()
    private val recentAiCards = ArrayDeque<DecisionCardUiModel>()
    private val readyPool = DecisionReadyPool(
        maxQueueSize = ACTIVE_CATEGORY_CACHE_TARGET,
        ttlMs = AI_CACHE_TTL_MS
    )
    private val cardAssembler = DecisionCardAssembler()
    private val fallbackHandler = DecisionFallbackHandler(
        decisionEngine = decisionEngine,
        feedbackStore = recommendationFeedbackStore,
        cardAssembler = cardAssembler
    )
    private var prefetchJob: Job? = null
    private var prefetchDeferred: Deferred<DecisionCardUiModel?>? = null
    private var idlePrefetchJob: Job? = null
    private var prefetchRetryJob: Job? = null
    private var prefetchCategory: String? = null
    private var prefetchBackoffUntilMs: Long = 0L
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

        cancelBackgroundPrefetch()
        _uiState.update { it.copy(decisionMode = mode) }
        scheduleAiPrefetchAfterIdle(force = true)
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
            category = card.category,
            tag = card.preferenceSignalText()
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
            schedulePrefetchAfterForegroundDecision()
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
            schedulePrefetchAfterForegroundDecision()
            return
        }

        cancelBackgroundPrefetch()

        val environment = getFastEnvironmentSnapshot()

        Log.d(
            TAG,
            "decision source=AI_START time=${environment.currentTimeLabel} weather=${environment.weatherCondition} location=${environment.userLocationLabel} targetCategory=$targetCategory locationSource=${environment.locationSource} weatherSource=${environment.weatherSource}"
        )
        val preferenceProfile = preferenceProfileFor(
            category = targetCategory,
            hour = environment.currentTime.hour
        )
        val recommendationTopic = pickRecommendationTopic(
            category = targetCategory,
            environment = environment,
            preferenceProfile = preferenceProfile
        )

        val decisionResult = decisionEngine.generateAiDecision(
            DecisionEngineRequest(
                environment = environment,
                targetCategory = targetCategory,
                avoidNames = recentAvoidNames(),
                currentCardName = _uiState.value.selectedCard?.title,
                hardAvoidNames = hardAvoidNames(),
                nearbyMallName = null,
                preferenceProfile = preferenceProfile,
                recommendationTopic = recommendationTopic
            )
        ).getOrElse { throwable ->
            handleAiFailure(
                environment = environment,
                targetCategory = targetCategory,
                reason = throwable.message ?: throwable.javaClass.simpleName
            )
            return
        }

        // Track unresolvable AI-suggested names to avoid them in future cycles
        decisionResult.unresolvableNames.forEach { name ->
            rememberRecentName(name)
            Log.d(TAG, "decision source=UNRESOLVABLE_TRACK name=$name targetCategory=$targetCategory")
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
            "decision source=AI_SUCCESS time=${environment.currentTimeLabel} weather=${environment.weatherCondition} location=${environment.userLocationLabel} targetCategory=$targetCategory topic=${recommendationTopic?.label} name=${recommendation.name} tag=${recommendation.tag} aiDistance=${recommendation.distanceDescription} verifiedDistance=${resolvedPlace.distanceLabel} placeConfidence=${resolvedPlace.confidence} candidates=${decisionResult.candidateCount}"
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
        schedulePrefetchAfterForegroundDecision()
    }

    private fun schedulePrefetchAfterForegroundDecision() {
        prefetchBackoffUntilMs = SystemClock.elapsedRealtime() + FOREGROUND_DECISION_PREFETCH_DELAY_MS
        scheduleAiPrefetchAfterIdle(delayMillis = FOREGROUND_DECISION_PREFETCH_DELAY_MS)
    }

    private fun cancelBackgroundPrefetch() {
        idlePrefetchJob?.cancel()
        idlePrefetchJob = null
        prefetchRetryJob?.cancel()
        prefetchRetryJob = null
        prefetchDeferred = null
        prefetchJob?.cancel()
        prefetchJob = null
        prefetchCategory = null
    }

    private fun prewarmEnvironmentSnapshot() {
        viewModelScope.launch {
            environmentCache.prewarm(ENVIRONMENT_PREWARM_TIMEOUT_MS)
        }
    }

    private fun scheduleAiPrefetch(force: Boolean = false) {
        pruneExpiredCachedCards()
        val now = SystemClock.elapsedRealtime()
        val canBypassBackoff = force && cacheSize(_uiState.value.decisionMode.category) == 0
        if (!canBypassBackoff && now < prefetchBackoffUntilMs) {
            Log.d(
                TAG,
                "decision source=READY_POOL_BACKOFF remaining=${prefetchBackoffUntilMs - now}ms"
            )
            return
        }
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
            val preferenceProfile = preferenceProfileFor(
                category = targetCategory,
                hour = environment.currentTime.hour
            )
            val recommendationTopic = pickRecommendationTopic(
                category = targetCategory,
                environment = environment,
                preferenceProfile = preferenceProfile
            )
            val result = decisionEngine.generateAiDecision(
                DecisionEngineRequest(
                    environment = environment,
                    targetCategory = targetCategory,
                    avoidNames = recentAvoidNames(),
                    currentCardName = _uiState.value.selectedCard?.title,
                    hardAvoidNames = hardAvoidNames(),
                    nearbyMallName = null,
                    useCandidateBatch = true,
                    preferenceProfile = preferenceProfile,
                    recommendationTopic = recommendationTopic
                )
            )

            val cards = result.getOrNull()?.toAiCards(targetCategory).orEmpty()
            var firstStoredCard: DecisionCardUiModel? = null
            result.onSuccess { decisionResult ->
                decisionResult.unresolvableNames.forEach { name ->
                    rememberRecentName(name)
                    Log.d(TAG, "decision source=UNRESOLVABLE_TRACK name=$name targetCategory=$targetCategory")
                }
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
                prefetchBackoffUntilMs = SystemClock.elapsedRealtime() + if (cacheSize(targetCategory) >= cacheTargetSize(targetCategory)) {
                    PREFETCH_FULL_COOLDOWN_MS
                } else if (storedCount > 0) {
                    PREFETCH_SUCCESS_COOLDOWN_MS
                } else {
                    PREFETCH_EMPTY_RESULT_COOLDOWN_MS
                }
                Log.d(
                    TAG,
                    "decision source=READY_POOL_FILLED targetCategory=$targetCategory topic=${recommendationTopic.label} stored=$storedCount batch=${cards.size} candidates=${decisionResult.candidateCount} ready=${cacheSize(targetCategory)}"
                )
            }.onFailure { throwable ->
                schedulePrefetchRetryAfterFailure()
                Log.d(
                    TAG,
                    "decision source=READY_POOL_FAILED targetCategory=$targetCategory reason=${throwable.message ?: throwable.javaClass.simpleName}"
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

    private fun scheduleAiPrefetchAfterIdle(
        force: Boolean = false,
        delayMillis: Long = MODE_SWITCH_PREFETCH_DELAY_MS
    ) {
        idlePrefetchJob?.cancel()
        idlePrefetchJob = viewModelScope.launch {
            delay(delayMillis)
            if (!_uiState.value.isAiSearching) {
                scheduleAiPrefetch(force = force)
            }
        }
    }

    private fun schedulePrefetchRetryAfterFailure() {
        val now = SystemClock.elapsedRealtime()
        prefetchBackoffUntilMs = now + PREFETCH_FAILURE_BACKOFF_MS
        if (prefetchRetryJob?.isActive == true) {
            return
        }
        prefetchRetryJob = viewModelScope.launch {
            delay(PREFETCH_FAILURE_BACKOFF_MS)
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
        val underfilledCategory = categories.firstOrNull { category ->
            val minimumReadySize = if (category == activeCategory) {
                ACTIVE_CATEGORY_MIN_READY
            } else {
                BACKGROUND_CATEGORY_MIN_READY
            }
            cacheSize(category) < minimumReadySize
        }
        if (underfilledCategory != null) {
            return underfilledCategory
        }

        return if (force) {
            categories.minByOrNull { category -> cacheSize(category) }
        } else {
            null
        }
    }

    private fun cacheTargetSize(category: String): Int {
        return if (category == _uiState.value.decisionMode.category) {
            ACTIVE_CATEGORY_CACHE_TARGET
        } else {
            BACKGROUND_CATEGORY_CACHE_TARGET
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
            !nameTracker.isDisliked(card.title)
        ) {
            consumePrefetchedAiCard(targetCategory) ?: card.copy(
                id = "ai_prefetch_${System.currentTimeMillis()}",
                momentLabel = buildMomentLabel(currentHour(), targetCategory),
                supportingText = refineCachedSupportingText(card)
            )
        } else {
            null
        }
    }

    private fun consumePrefetchedAiCard(targetCategory: String): DecisionCardUiModel? {
        val card = readyPool.consume(
            targetCategory = targetCategory,
            currentTitle = _uiState.value.selectedCard?.title,
            dislikedNames = nameTracker.getDislikedNames(),
            activeEnvironmentKey = environmentCache.activeEnvironmentKey(),
            currentHour = currentHour()
        ) ?: return null

        return card.copy(
            id = "ai_cache_${System.currentTimeMillis()}",
            momentLabel = buildMomentLabel(currentHour(), targetCategory),
            supportingText = refineCachedSupportingText(card)
        )
    }

    private fun storeCachedAiCard(card: DecisionCardUiModel): Boolean {
        return readyPool.store(
            card = card.copy(supportingText = refineCachedSupportingText(card)),
            currentTitle = _uiState.value.selectedCard?.title,
            recentNames = nameTracker.getRecentNames(),
            dislikedNames = nameTracker.getDislikedNames(),
            environmentKey = environmentCache.activeEnvironmentKey(),
            currentHour = currentHour()
        )
    }

    private fun recordPositivePreference(
        card: DecisionCardUiModel,
        action: String
    ) {
        recommendationFeedbackStore.recordPositiveFeedback(
            title = card.title,
            category = card.category,
            tag = card.preferenceSignalText(),
            action = action
        )
    }

    private fun DecisionCardUiModel.preferenceSignalText(): String =
        cardAssembler.preferenceSignalText(this)

    private fun preferenceProfileFor(
        category: String,
        hour: Int? = null
    ): RecommendationPreferenceProfile {
        val profile = recommendationFeedbackStore.preferenceProfile(
            category = category,
            hour = hour
        )
        profile.promptHint()?.let { hint ->
            Log.d(
                TAG,
                "decision source=PREFERENCE_PROFILE category=$category hour=${hour ?: "any"} events=${profile.eventCount} hint=$hint"
            )
        }
        return profile
    }

    private fun pickRecommendationTopic(
        category: String,
        environment: DecisionEnvironmentSnapshot,
        preferenceProfile: RecommendationPreferenceProfile
    ): RecommendationTopic {
        val selection = recommendationTopicProvider.pickTopicSelection(
            category = category,
            recentTopicIds = recentTopicIds,
            hour = environment.currentTime.hour,
            weatherCondition = environment.weatherCondition,
            preferenceProfile = preferenceProfile
        )
        val topic = selection.topic
        rememberTopic(topic)
        Log.d(
            TAG,
            "decision source=TOPIC_PICK category=$category topic=${topic.label} weight=${selection.weight} candidates=${selection.candidateCount} cooled=${selection.cooledTopicCount} recent=${recentTopicIds.joinToString(limit = 6)}"
        )
        return topic
    }

    private fun rememberTopic(topic: RecommendationTopic) {
        recentTopicIds.removeAll { topicId -> topicId == topic.id }
        recentTopicIds.addLast(topic.id)
        while (recentTopicIds.size > RECENT_TOPIC_MEMORY) {
            recentTopicIds.removeFirst()
        }
    }

    private fun dropCachedCardsMatching(name: String) {
        readyPool.dropMatching(name)
    }

    private fun pruneExpiredCachedCards() {
        readyPool.pruneExpired(environmentCache.activeEnvironmentKey())
    }

    private fun cacheSize(category: String): Int {
        return readyPool.cacheSize(category)
    }

    private suspend fun getFastEnvironmentSnapshot(): DecisionEnvironmentSnapshot {
        return environmentCache.getFastSnapshot()
    }

    private fun com.example.dateapp.data.decision.DecisionEngineResult.toAiCard(
        targetCategory: String
    ): DecisionCardUiModel {
        return cardAssembler.toAiCard(this, targetCategory)
    }

    private fun com.example.dateapp.data.decision.DecisionEngineResult.toAiCards(
        targetCategory: String
    ): List<DecisionCardUiModel> {
        return cardAssembler.toAiCards(this, targetCategory)
    }

    private fun DecisionEngineCandidate.toAiCard(
        targetCategory: String,
        environment: DecisionEnvironmentSnapshot,
        weatherProfile: DecisionWeatherProfile,
        index: Int
    ): DecisionCardUiModel {
        return cardAssembler.toCandidateCard(this, targetCategory, environment, weatherProfile, index)
    }

    private fun handleAiFailure(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String,
        reason: String
    ) {
        val fallbackCard = fallbackHandler.handleAiFailure(
            environment = environment,
            targetCategory = targetCategory,
            reason = reason,
            nameTracker = nameTracker,
            recentAiCards = recentAiCards,
            latestLocalWishes = latestLocalWishes,
            selectedCardTitle = _uiState.value.selectedCard?.title
        )
        rememberDecision(fallbackCard)
        _uiState.update {
            it.copy(
                selectedCard = fallbackCard,
                availableCount = latestLocalWishes.size,
                isAiSearching = false
            )
        }
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
        nameTracker.rememberDecision(card)
    }

    private fun rememberNotInterested(card: DecisionCardUiModel) {
        nameTracker.rememberNotInterested(card)
    }

    private fun rememberRecentName(name: String) {
        nameTracker.rememberRecentName(name)
    }

    private fun rememberDislikedName(name: String) {
        nameTracker.rememberDislikedName(name)
    }

    private fun recentAvoidNames(): List<String> {
        val currentTitle = _uiState.value.selectedCard?.title
        return nameTracker.recentAvoidNames(
            currentTitle = currentTitle,
            recentPromptMemory = RECENT_PROMPT_AVOID_MEMORY,
            dislikedPromptMemory = DISLIKED_PROMPT_AVOID_MEMORY,
            promptLimit = PROMPT_AVOID_NAME_LIMIT
        )
    }

    private fun hardAvoidNames(): List<String> {
        return nameTracker.hardAvoidNames(memory = HARD_DISLIKED_PROMPT_MEMORY)
    }

    private fun isSimilarPlaceName(first: String, second: String): Boolean {
        return DecisionNameTracker.isSimilarPlaceName(first, second)
    }

    private fun normalizePlaceName(name: String): String {
        return DecisionNameTracker.normalizePlaceName(name)
    }

    private fun buildMomentLabel(hour: Int, category: String): String =
        cardAssembler.buildMomentLabel(hour, category)

    private fun buildAiSupportingText(
        recommendation: AiDecisionRecommendation,
        targetCategory: String
    ): String = cardAssembler.buildAiSupportingText(recommendation, targetCategory)

    private fun refineCachedSupportingText(card: DecisionCardUiModel): String =
        cardAssembler.refineCachedSupportingText(card, cardAssembler.currentHour())

    private fun buildAiContextLine(
        recommendation: AiDecisionRecommendation,
        weatherProfile: DecisionWeatherProfile
    ): String? = cardAssembler.buildAiContextLine(recommendation, weatherProfile)

    private fun recommendationSignalText(recommendation: AiDecisionRecommendation): String =
        cardAssembler.recommendationSignalText(recommendation)

    private fun containsAnySignal(text: String, vararg keywords: String): Boolean =
        DecisionCardAssembler.containsAnySignal(text, *keywords)

    private fun buildContextLine(environment: DecisionEnvironmentSnapshot): String =
        cardAssembler.buildContextLine(environment)

    private fun WishItem.toLocalCard(hour: Int = cardAssembler.currentHour()): DecisionCardUiModel {
        return cardAssembler.toLocalCard(this, hour)
    }

    private fun WishItem.buildLocalSupportingText(): String =
        cardAssembler.buildLocalSupportingText(this)

    private fun currentHour(): Int = cardAssembler.currentHour()

    companion object {
        private const val TAG = "DecisionViewModel"
        private const val ENVIRONMENT_PREWARM_TIMEOUT_MS = 8_000L
        private const val ENVIRONMENT_TIMEOUT_MS = 900L
        private const val ACTIVE_PREFETCH_AWAIT_MS = 650L
        private const val VIEWMODEL_ENVIRONMENT_CACHE_TTL_MS = 8 * 60 * 1000L
        private const val FALLBACK_ENVIRONMENT_CACHE_TTL_MS = 8_000L
        private const val AI_CACHE_TTL_MS = 12 * 60 * 1000L
        private const val ACTIVE_CATEGORY_CACHE_TARGET = 8
        private const val BACKGROUND_CATEGORY_CACHE_TARGET = 4
        private const val ACTIVE_CATEGORY_MIN_READY = 4
        private const val BACKGROUND_CATEGORY_MIN_READY = 2
        private const val FOREGROUND_DECISION_PREFETCH_DELAY_MS = 2_000L
        private const val MODE_SWITCH_PREFETCH_DELAY_MS = 700L
        private const val PREFETCH_SUCCESS_COOLDOWN_MS = 4_000L
        private const val PREFETCH_FULL_COOLDOWN_MS = 20_000L
        private const val PREFETCH_EMPTY_RESULT_COOLDOWN_MS = 12_000L
        private const val PREFETCH_FAILURE_BACKOFF_MS = 18_000L
        private const val RECENT_RECOMMENDATION_MEMORY = 36
        private const val DISLIKED_RECOMMENDATION_MEMORY = 64
        private const val RECENT_PROMPT_AVOID_MEMORY = 3
        private const val DISLIKED_PROMPT_AVOID_MEMORY = 5
        private const val HARD_DISLIKED_PROMPT_MEMORY = 8
        private const val PROMPT_AVOID_NAME_LIMIT = 8
        private const val RECENT_TOPIC_MEMORY = 4
        private const val RECENT_AI_CARD_POOL_SIZE = 8

        private val appZoneId = WuhanKnowledgeConfig.zoneId
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
            recommendationFeedbackStore: RecommendationFeedbackStore,
            recommendationTopicProvider: RecommendationTopicProvider
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
                        recommendationFeedbackStore = recommendationFeedbackStore,
                        recommendationTopicProvider = recommendationTopicProvider
                    ) as T
                }
            }
        }
    }
}
