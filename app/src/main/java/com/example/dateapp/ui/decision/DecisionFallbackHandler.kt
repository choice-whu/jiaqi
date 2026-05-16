package com.example.dateapp.ui.decision

import android.util.Log
import com.example.dateapp.data.local.WishItem
import com.example.dateapp.data.decision.DecisionEngine
import com.example.dateapp.data.environment.DecisionEnvironmentSnapshot
import com.example.dateapp.data.recommendation.RecommendationFeedbackStore
import com.example.dateapp.data.recommendation.RecommendationPreferenceProfile

class DecisionFallbackHandler(
    private val decisionEngine: DecisionEngine,
    private val feedbackStore: RecommendationFeedbackStore,
    private val cardAssembler: DecisionCardAssembler
) {
    fun handleAiFailure(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String,
        reason: String,
        nameTracker: DecisionNameTracker,
        recentAiCards: ArrayDeque<DecisionCardUiModel>,
        latestLocalWishes: List<WishItem>,
        selectedCardTitle: String?
    ): DecisionCardUiModel {
        Log.d(TAG, "decision source=AI_FAILED reason=$reason")

        return recentAiCardFallback(
            environment = environment,
            targetCategory = targetCategory,
            nameTracker = nameTracker,
            recentAiCards = recentAiCards,
            selectedCardTitle = selectedCardTitle
        ) ?: localWishFallback(
            environment = environment,
            targetCategory = targetCategory,
            nameTracker = nameTracker,
            latestLocalWishes = latestLocalWishes,
            selectedCardTitle = selectedCardTitle
        ) ?: emergencyAiFallback(
            environment = environment,
            targetCategory = targetCategory,
            nameTracker = nameTracker
        )
    }

    private fun recentAiCardFallback(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String,
        nameTracker: DecisionNameTracker,
        recentAiCards: ArrayDeque<DecisionCardUiModel>,
        selectedCardTitle: String?
    ): DecisionCardUiModel? {
        val card = recentAiCards
            .asReversed()
            .firstOrNull { cachedCard ->
                cachedCard.category == targetCategory &&
                    !DecisionNameTracker.isSimilarPlaceName(cachedCard.title, selectedCardTitle.orEmpty()) &&
                    !nameTracker.isDisliked(cachedCard.title)
            }
            ?: return null

        Log.d(
            TAG,
            "decision source=AI_CACHED_FALLBACK time=${environment.currentTimeLabel} title=${card.title}"
        )

        return card.copy(
            id = "ai_cached_${System.currentTimeMillis()}",
            sourceLabel = "AI探索",
            momentLabel = cardAssembler.buildMomentLabel(environment.currentTime.hour, targetCategory),
            contextLine = card.contextLine ?: cardAssembler.buildContextLine(environment)
        )
    }

    private fun localWishFallback(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String,
        nameTracker: DecisionNameTracker,
        latestLocalWishes: List<WishItem>,
        selectedCardTitle: String?
    ): DecisionCardUiModel? {
        val profile = preferenceProfileFor(
            category = targetCategory,
            hour = environment.currentTime.hour
        )
        data class ScoredWish(
            val wish: WishItem,
            val personalizationScore: Int,
            val matchesTargetCategory: Boolean,
            val recentlyRecommended: Boolean
        )

        val fallbackCandidates = latestLocalWishes
            .filter { wish -> wish.category == targetCategory }
            .filterNot { wish ->
                DecisionNameTracker.isSimilarPlaceName(wish.title, selectedCardTitle.orEmpty())
            }
            .map { wish ->
                ScoredWish(
                    wish = wish,
                    personalizationScore = profile.scoreText(
                        text = listOfNotNull(wish.title, wish.locationKeyword).joinToString(" "),
                        fallbackCategory = wish.category
                    ),
                    matchesTargetCategory = wish.category == targetCategory,
                    recentlyRecommended = nameTracker.getRecentNames().any { recentName ->
                        DecisionNameTracker.isSimilarPlaceName(wish.title, recentName)
                    }
                )
            }
            .sortedWith(
                compareByDescending<ScoredWish> { it.matchesTargetCategory }
                    .thenByDescending { it.personalizationScore }
                    .thenBy { it.recentlyRecommended }
                    .thenByDescending { it.wish.addedTimestamp }
            )
            .map { it.wish }

        val localWish = fallbackCandidates
            .firstOrNull { wish ->
                !isStrongDislikedLocalMatch(wish, nameTracker.getDislikedNames(), nameTracker)
            }
            ?: return null

        Log.d(
            TAG,
            "decision source=LOCAL_FALLBACK time=${environment.currentTimeLabel} title=${localWish.title} category=${localWish.category}"
        )

        return cardAssembler.toLocalCard(localWish, environment.currentTime.hour).copy(
            id = "local_fallback_${localWish.id}_${System.currentTimeMillis()}",
            sourceLabel = "心愿池",
            momentLabel = cardAssembler.buildMomentLabel(environment.currentTime.hour, targetCategory),
            contextLine = cardAssembler.buildContextLine(environment)
        )
    }

    private fun emergencyAiFallback(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String,
        nameTracker: DecisionNameTracker
    ): DecisionCardUiModel {
        val weatherProfile = decisionEngine.buildWeatherProfile(environment)
        val indoorPreferred = weatherProfile.isSevere ||
            weatherProfile.isRainy ||
            weatherProfile.isHot ||
            weatherProfile.isCold ||
            weatherProfile.isFoggy
        val area = environment.userLocationLabel
        val nearbyArea = area.removeSuffix("附近")
        val fallbackTitleCandidates = when {
            targetCategory == "meal" -> listOf(
                "在${nearbyArea}附近找一家近一点的舒服小店",
                "在${nearbyArea}附近找一家不用走太远的热乎小店",
                "在${nearbyArea}附近找一家安静好坐的餐饮小店"
            )
            indoorPreferred -> listOf(
                "去${nearbyArea}附近找个室内小去处",
                "去${nearbyArea}附近找个能慢慢逛的室内去处",
                "去${nearbyArea}附近找个遮风避雨的小目的地"
            )
            environment.currentTime.hour in 5..10 -> listOf(
                "去${nearbyArea}附近找个清爽的早间去处",
                "去${nearbyArea}附近找个上午开放的小去处",
                "去${nearbyArea}附近找个适合慢慢醒来的地方"
            )
            environment.currentTime.hour in 16..19 -> listOf(
                "去${nearbyArea}附近找个适合傍晚逛逛的去处",
                "去${nearbyArea}附近找个黄昏前能停一会儿的地方",
                "去${nearbyArea}附近找个轻松散步的小目的地"
            )
            environment.currentTime.hour in 20..23 -> listOf(
                "去${nearbyArea}附近找个晚上也热闹的小去处",
                "去${nearbyArea}附近找个夜里还适合待会儿的地方",
                "去${nearbyArea}附近找个不太折腾的夜间去处"
            )
            else -> listOf(
                "去${nearbyArea}附近找个轻松好玩的去处",
                "去${nearbyArea}附近找个短暂停留的小目的地",
                "去${nearbyArea}附近找个不用计划太多的地方"
            )
        }
        val emergencyAvoidNames = nameTracker.recentAvoidNames(
            currentTitle = null,
            promptLimit = 8
        ) + nameTracker.getDislikedNames()
        val fallbackTitle = fallbackTitleCandidates.firstOrNull { candidate ->
            emergencyAvoidNames.none { avoidName ->
                DecisionNameTracker.isSimilarPlaceName(candidate, avoidName)
            }
        } ?: fallbackTitleCandidates[
            (nameTracker.getRecentNames().size + environment.currentTime.minute) % fallbackTitleCandidates.size
        ]
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
            momentLabel = cardAssembler.buildMomentLabel(environment.currentTime.hour, targetCategory),
            supportingText = fallbackSupportingText,
            contextLine = cardAssembler.buildContextLine(environment)
        )
    }

    private fun isStrongDislikedLocalMatch(
        wish: WishItem,
        dislikedNames: List<String>,
        nameTracker: DecisionNameTracker
    ): Boolean {
        val wishSignals = listOfNotNull(wish.title, wish.locationKeyword)
        return dislikedNames.any { dislikedName ->
            wishSignals.any { signal -> DecisionNameTracker.isSimilarPlaceName(signal, dislikedName) }
        }
    }

    private fun preferenceProfileFor(
        category: String,
        hour: Int?
    ): RecommendationPreferenceProfile {
        val profile = feedbackStore.preferenceProfile(
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

    companion object {
        private const val TAG = "DecisionFallback"
    }
}
