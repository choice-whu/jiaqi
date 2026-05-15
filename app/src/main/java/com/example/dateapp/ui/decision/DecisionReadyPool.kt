package com.example.dateapp.ui.decision

import android.os.SystemClock
import android.util.Log
import com.example.dateapp.data.decision.DecisionNameMatcher
import java.util.Locale

class DecisionReadyPool(
    private val maxQueueSize: Int,
    private val ttlMs: Long
) {
    private val queues = mutableMapOf(
        DecisionMode.MEAL.category to ArrayDeque<CachedAiCard>(),
        DecisionMode.PLAY.category to ArrayDeque<CachedAiCard>()
    )

    fun cacheSize(category: String): Int {
        return queues[category]?.size ?: 0
    }

    fun consume(
        targetCategory: String,
        currentTitle: String?,
        dislikedNames: List<String>,
        activeEnvironmentKey: String,
        currentHour: Int
    ): DecisionCardUiModel? {
        pruneExpired(activeEnvironmentKey)
        val queue = queues.getOrPut(targetCategory) { ArrayDeque() }
        pruneWeakCards(
            queue = queue,
            targetCategory = targetCategory,
            currentTitle = currentTitle,
            dislikedNames = dislikedNames,
            currentHour = currentHour
        )

        val index = queue.indexOfFirst { cachedCard ->
            val card = cachedCard.card
            card.category == targetCategory &&
                !isSimilarPlaceName(card.title, currentTitle.orEmpty()) &&
                dislikedNames.none { dislikedName -> isSimilarPlaceName(card.title, dislikedName) }
        }
        if (index < 0) {
            return null
        }

        val card = queue.removeAt(index).card
        Log.d(
            TAG,
            "decision source=READY_POOL_HIT targetCategory=$targetCategory title=${card.title} remaining=${queue.size}"
        )
        return card
    }

    fun store(
        card: DecisionCardUiModel,
        currentTitle: String?,
        recentNames: List<String>,
        dislikedNames: List<String>,
        environmentKey: String,
        currentHour: Int
    ): Boolean {
        val queue = queues.getOrPut(card.category) { ArrayDeque() }
        if (!isCardStillUsable(card, card.category, currentTitle, dislikedNames, currentHour)) {
            Log.d(TAG, "decision source=READY_POOL_SKIP_WEAK targetCategory=${card.category} title=${card.title}")
            return false
        }
        if (
            queue.any { isSimilarPlaceName(card.title, it.card.title) } ||
            recentNames.any { isSimilarPlaceName(card.title, it) } ||
            dislikedNames.any { isSimilarPlaceName(card.title, it) }
        ) {
            Log.d(TAG, "decision source=READY_POOL_SKIP_REPEAT targetCategory=${card.category} title=${card.title}")
            return false
        }

        queue.addLast(
            CachedAiCard(
                card = card,
                cachedAtMs = SystemClock.elapsedRealtime(),
                environmentKey = environmentKey
            )
        )
        while (queue.size > maxQueueSize) {
            queue.removeFirst()
        }
        return true
    }

    fun dropMatching(name: String) {
        queues.values.forEach { queue ->
            queue.removeAll { isSimilarPlaceName(it.card.title, name) }
        }
    }

    fun pruneExpired(activeEnvironmentKey: String) {
        val now = SystemClock.elapsedRealtime()
        queues.values.forEach { queue ->
            queue.removeAll { cachedCard ->
                now - cachedCard.cachedAtMs > ttlMs ||
                    cachedCard.environmentKey != activeEnvironmentKey
            }
        }
    }

    private fun pruneWeakCards(
        queue: ArrayDeque<CachedAiCard>,
        targetCategory: String,
        currentTitle: String?,
        dislikedNames: List<String>,
        currentHour: Int
    ) {
        val removed = queue.removeAll { cachedCard ->
            !isCardStillUsable(cachedCard.card, targetCategory, currentTitle, dislikedNames, currentHour)
        }
        if (removed) {
            Log.d(TAG, "decision source=READY_POOL_PRUNE_WEAK targetCategory=$targetCategory remaining=${queue.size}")
        }
    }

    private fun isCardStillUsable(
        card: DecisionCardUiModel,
        targetCategory: String,
        currentTitle: String?,
        dislikedNames: List<String>,
        currentHour: Int
    ): Boolean {
        if (card.category != targetCategory) {
            return false
        }
        if (isSimilarPlaceName(card.title, currentTitle.orEmpty())) {
            return false
        }
        if (dislikedNames.any { dislikedName -> isSimilarPlaceName(card.title, dislikedName) }) {
            return false
        }

        val text = listOfNotNull(
            card.title,
            card.locationLabel,
            card.routeKeyword,
            card.tag,
            card.supportingText
        ).joinToString(" ").lowercase(Locale.ROOT)

        if (weakDestinationKeywords.any(text::contains)) {
            return false
        }

        val distanceMeters = parseDistanceMeters(card.distanceDescription)
        if (
            targetCategory == DecisionMode.PLAY.category &&
            distanceMeters != null &&
            distanceMeters > PLAY_CACHE_MAX_DISTANCE_METERS &&
            !tripWorthyPlayKeywords.any(text::contains)
        ) {
            return false
        }

        if (
            targetCategory == DecisionMode.MEAL.category &&
            currentHour in 14..17 &&
            afternoonFunctionalMealKeywords.any(text::contains) &&
            afternoonDateMealKeywords.none(text::contains)
        ) {
            return false
        }

        return true
    }

    private fun parseDistanceMeters(distanceDescription: String?): Int? {
        val text = distanceDescription?.lowercase(Locale.ROOT).orEmpty()
        val match = Regex("(\\d+(?:\\.\\d+)?)\\s*(km|公里|m|米)")
            .find(text)
            ?: return null
        val value = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return null
        val unit = match.groupValues.getOrNull(2).orEmpty()
        return if (unit == "km" || unit == "公里") {
            (value * 1000).toInt()
        } else {
            value.toInt()
        }
    }

    private fun isSimilarPlaceName(
        first: String,
        second: String
    ): Boolean {
        return DecisionNameMatcher.isSimilarPlaceName(first, second)
    }

    private data class CachedAiCard(
        val card: DecisionCardUiModel,
        val cachedAtMs: Long,
        val environmentKey: String
    )

    companion object {
        private const val TAG = "DecisionReadyPool"
        private const val PLAY_CACHE_MAX_DISTANCE_METERS = 10_000

        private val weakDestinationKeywords = listOf(
            "土特产",
            "特产",
            "烟酒",
            "便利店",
            "生活超市",
            "开业花篮",
            "花束定制",
            "鲜花店",
            "经营部",
            "批发部"
        )

        private val tripWorthyPlayKeywords = listOf(
            "黄鹤楼",
            "晴川阁",
            "古德寺",
            "江滩",
            "东湖",
            "植物园",
            "美术馆",
            "博物馆",
            "艺术馆",
            "剧场",
            "livehouse",
            "展览",
            "看展",
            "江汉路",
            "武汉天地",
            "光谷"
        )

        private val afternoonFunctionalMealKeywords = listOf(
            "小吃",
            "热干面",
            "牛肉面",
            "牛肉粉",
            "羊肉粉",
            "米粉",
            "粉面",
            "面馆",
            "拉面",
            "汤包",
            "水煎包",
            "牛杂",
            "包子",
            "早餐",
            "早茶"
        )

        private val afternoonDateMealKeywords = listOf(
            "咖啡",
            "甜品",
            "蛋糕",
            "面包",
            "brunch",
            "披萨",
            "云饺",
            "饺子",
            "市集",
            "豆皮",
            "锅盔",
            "炸炸",
            "泰",
            "印度",
            "越南",
            "墨西哥",
            "韩",
            "日料",
            "西餐",
            "轻食",
            "茶馆",
            "茶舍",
            "茶楼",
            "小酒馆",
            "bistro"
        )
    }
}
