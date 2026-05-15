package com.example.dateapp.data.recommendation

import android.content.Context
import java.util.Locale
import kotlin.math.roundToInt

data class RecommendationPreferenceProfile(
    val category: String,
    val likedTraits: List<String>,
    val dislikedTraits: List<String>,
    val traitScores: Map<String, Int>,
    val eventCount: Int
) {
    val isEmpty: Boolean
        get() = likedTraits.isEmpty() && dislikedTraits.isEmpty()

    fun scoreText(
        text: String,
        fallbackCategory: String = category
    ): Int {
        if (isEmpty) {
            return 0
        }

        val traits = RecommendationTraitAnalyzer.extractTraits(
            text = text,
            category = fallbackCategory
        )
        if (traits.isEmpty()) {
            return 0
        }

        return traits.sumOf { trait ->
            (traitScores[trait] ?: 0).coerceIn(-6, 6)
        }.coerceIn(-10, 10)
    }

    fun promptHint(): String? {
        if (isEmpty) {
            return null
        }

        val liked = likedTraits
            .take(3)
            .joinToString(" / ") { RecommendationTraitAnalyzer.labelFor(it) }
        val disliked = dislikedTraits
            .take(3)
            .joinToString(" / ") { RecommendationTraitAnalyzer.labelFor(it) }
        val strengthLabel = when {
            eventCount >= 8 -> "Strong preference"
            eventCount >= 4 -> "Clear preference"
            else -> "Soft preference"
        }

        return buildString {
            if (liked.isNotBlank()) {
                append(strengthLabel)
                append(": likes ")
                append(liked)
                append(". ")
            }
            if (disliked.isNotBlank()) {
                append("Avoids ")
                append(disliked)
                append(".")
            }
        }.trim().takeIf { it.isNotBlank() }
    }
}

object RecommendationTraitAnalyzer {
    fun extractTraits(
        text: String,
        category: String
    ): Set<String> {
        val normalizedText = text.lowercase(Locale.ROOT)
        val traits = linkedSetOf<String>()

        fun hasAny(vararg keywords: String): Boolean = keywords.any(normalizedText::contains)
        fun addIf(trait: String, vararg keywords: String) {
            if (hasAny(*keywords)) {
                traits += trait
            }
        }

        if (category.normalizeCategoryForTraits() == "meal") {
            addIf("hotpot", "火锅", "寿喜锅", "锅物", "海底捞")
            addIf("bbq", "烧烤", "烤肉", "烤串", "烤鱼")
            addIf("coffee", "咖啡", "coffee", "m stand", "星巴克")
            addIf("dessert", "甜品", "蛋糕", "面包", "烘焙", "冰淇淋", "奶茶", "茶饮")
            addIf("noodle", "面", "粉", "拉面", "热干面", "蔡林记")
            addIf("western", "西餐", "牛排", "披萨", "意面", "brunch", "汉堡")
            addIf("japanese", "日料", "寿司", "拉面", "居酒屋", "一风堂")
            addIf("bar", "酒吧", "小酒馆", "清吧", "bistro")
            addIf("mall", "商场", "广场", "mall", "恒隆", "群光", "万象城", "武商")
            addIf("breakfast", "早餐", "早茶", "豆浆", "包子")
        } else {
            addIf("bookstore", "书店", "书城", "书屋", "西西弗", "卓尔", "德芭")
            addIf("cafe", "咖啡", "coffee", "m stand", "甜品", "奶茶", "茶饮", "下午茶")
            addIf("arcade", "电玩", "游戏厅", "汤姆熊", "arcade", "密室", "桌游", "保龄球", "台球")
            addIf("craft", "手作", "陶艺", "工坊", "香薰", "diy", "创意工坊")
            addIf("small_shop", "小店", "买手", "主理人", "杂货", "生活方式", "潮玩", "盲盒", "玩具", "唱片", "黑胶", "中古")
            addIf("mall", "商场", "购物中心", "广场", "mall", "恒隆", "群光", "万象城", "武商", "武汉天地")
            addIf("museum", "博物馆", "博物院", "纪念馆")
            addIf("gallery", "美术馆", "艺术中心", "画廊", "展览", "艺术馆")
            addIf("park", "公园", "绿道", "园博园", "植物园")
            addIf("riverside", "江滩", "江边", "湖边", "东湖", "汉口江滩")
            addIf("historic", "寺", "黄鹤楼", "古德寺", "晴川阁", "巴公房子", "历史", "老街")
            addIf("live", "livehouse", "剧场", "剧院", "演出", "电影", "影院")
            addIf("market", "市集", "集市", "夜市", "街区", "步行街")
        }

        return traits
    }

    fun labelFor(trait: String): String {
        return when (trait) {
            "hotpot" -> "火锅/锅物"
            "bbq" -> "烧烤/烤肉"
            "coffee" -> "咖啡"
            "dessert" -> "甜品/茶饮"
            "noodle" -> "面食小吃"
            "western" -> "西餐/Brunch"
            "japanese" -> "日料"
            "bar" -> "小酒馆"
            "mall" -> "商场内具体去处"
            "breakfast" -> "早餐"
            "bookstore" -> "书店"
            "cafe" -> "咖啡甜品"
            "arcade" -> "电玩互动"
            "craft" -> "手作工坊"
            "small_shop" -> "主理人小店"
            "museum" -> "博物馆"
            "gallery" -> "展览/美术馆"
            "park" -> "公园绿道"
            "riverside" -> "江滩湖边"
            "historic" -> "历史地标"
            "live" -> "演出影院"
            "market" -> "市集街区"
            else -> trait
        }
    }

    private fun String.normalizeCategoryForTraits(): String {
        return when (trim().lowercase(Locale.ROOT)) {
            "meal" -> "meal"
            "play" -> "play"
            else -> "play"
        }
    }
}

class RecommendationFeedbackStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun dislikedNames(): List<String> {
        return readList(KEY_DISLIKED_NAMES)
            .filter { it.isNotBlank() }
            .takeLast(MAX_DISLIKED_NAMES)
    }

    fun recentRecommendedNames(): List<String> {
        val cutoff = System.currentTimeMillis() - RECENT_RECOMMENDATION_COOLDOWN_MS
        val recentEvents = readList(KEY_RECENT_RECOMMENDATIONS)
            .mapNotNull(::parseTitleEvent)
            .filter { event -> event.timestamp >= cutoff }

        if (recentEvents.size != readList(KEY_RECENT_RECOMMENDATIONS).size) {
            prefs.edit()
                .putString(
                    KEY_RECENT_RECOMMENDATIONS,
                    recentEvents
                        .map { "${it.timestamp}|${it.title}" }
                        .joinToString(LIST_SEPARATOR)
                )
                .apply()
        }

        return recentEvents
            .map { it.title }
            .takeLast(MAX_RECENT_RECOMMENDATIONS)
    }

    fun recentCategoryDislikeCount(category: String): Int {
        val normalizedCategory = category.normalizeCategory()
        val cutoff = System.currentTimeMillis() - CATEGORY_MEMORY_MS
        return readList(KEY_CATEGORY_EVENTS)
            .mapNotNull(::parseCategoryEvent)
            .count { event ->
                event.category == normalizedCategory && event.timestamp >= cutoff
            }
    }

    fun preferenceProfile(category: String): RecommendationPreferenceProfile {
        val normalizedCategory = category.normalizeCategory()
        val cutoff = System.currentTimeMillis() - PREFERENCE_MEMORY_MS
        val events = readList(KEY_PREFERENCE_EVENTS)
            .mapNotNull(::parsePreferenceEvent)
            .filter { event ->
                event.category == normalizedCategory &&
                    event.timestamp >= cutoff
            }

        val traitScores = mutableMapOf<String, Int>()
        events.forEach { event ->
            val actionWeight = when (event.action) {
                ACTION_SAVE -> 5
                ACTION_NAVIGATE -> 6
                ACTION_EXTERNAL_SEARCH -> 3
                ACTION_NOT_INTERESTED -> -6
                else -> 0
            }
            if (actionWeight == 0) {
                return@forEach
            }

            val recencyMultiplier = recencyMultiplier(
                ageMillis = System.currentTimeMillis() - event.timestamp
            )
            val weightedAction = (actionWeight * recencyMultiplier)
                .roundToInt()
                .coerceIn(-8, 8)
            if (weightedAction == 0) {
                return@forEach
            }

            RecommendationTraitAnalyzer.extractTraits(
                text = listOf(event.title, event.tag).joinToString(" "),
                category = event.category
            ).forEach { trait ->
                traitScores[trait] = (traitScores[trait] ?: 0) + weightedAction
            }
        }

        val likedTraits = traitScores
            .filterValues { it >= POSITIVE_TRAIT_THRESHOLD }
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
        val dislikedTraits = traitScores
            .filterValues { it <= NEGATIVE_TRAIT_THRESHOLD }
            .entries
            .sortedBy { it.value }
            .map { it.key }

        return RecommendationPreferenceProfile(
            category = normalizedCategory,
            likedTraits = likedTraits,
            dislikedTraits = dislikedTraits,
            traitScores = traitScores,
            eventCount = events.size
        )
    }

    fun recordNotInterested(
        title: String,
        category: String
    ) {
        val cleanTitle = title.cleanFeedbackText()
        if (cleanTitle.isBlank()) {
            return
        }

        val normalizedCategory = category.normalizeCategory()
        val names = (readList(KEY_DISLIKED_NAMES)
            .filterNot { it.equals(cleanTitle, ignoreCase = true) } + cleanTitle)
            .takeLast(MAX_DISLIKED_NAMES)

        val cutoff = System.currentTimeMillis() - CATEGORY_MEMORY_MS
        val categoryEvents = (readList(KEY_CATEGORY_EVENTS)
            .mapNotNull(::parseCategoryEvent)
            .filter { it.timestamp >= cutoff }
            .map { "${it.timestamp}|${it.category}" } +
            "${System.currentTimeMillis()}|$normalizedCategory")
            .takeLast(MAX_CATEGORY_EVENTS)

        prefs.edit()
            .putString(KEY_DISLIKED_NAMES, names.joinToString(LIST_SEPARATOR))
            .putString(KEY_CATEGORY_EVENTS, categoryEvents.joinToString(LIST_SEPARATOR))
            .apply()

        recordPreferenceEvent(
            title = cleanTitle,
            category = normalizedCategory,
            tag = null,
            action = ACTION_NOT_INTERESTED
        )
    }

    private fun recencyMultiplier(ageMillis: Long): Double {
        val ageDays = (ageMillis / DAY_MILLIS.toDouble()).coerceAtLeast(0.0)
        return when {
            ageDays <= 1.0 -> 1.25
            ageDays <= 3.0 -> 1.15
            ageDays <= 7.0 -> 1.0
            ageDays <= 14.0 -> 0.85
            ageDays <= 30.0 -> 0.7
            else -> 0.55
        }
    }

    fun recordPositiveFeedback(
        title: String,
        category: String,
        tag: String?,
        action: String
    ) {
        val normalizedAction = when (action) {
            ACTION_SAVE, ACTION_NAVIGATE, ACTION_EXTERNAL_SEARCH -> action
            else -> return
        }
        recordPreferenceEvent(
            title = title,
            category = category.normalizeCategory(),
            tag = tag,
            action = normalizedAction
        )
    }

    fun recordRecommended(title: String) {
        val cleanTitle = title.cleanFeedbackText()
        if (cleanTitle.isBlank()) {
            return
        }

        val cutoff = System.currentTimeMillis() - RECENT_RECOMMENDATION_COOLDOWN_MS
        val recentEvents = (readList(KEY_RECENT_RECOMMENDATIONS)
            .mapNotNull(::parseTitleEvent)
            .filter { event ->
                event.timestamp >= cutoff &&
                    !event.title.equals(cleanTitle, ignoreCase = true)
            }
            .map { "${it.timestamp}|${it.title}" } +
            "${System.currentTimeMillis()}|$cleanTitle")
            .takeLast(MAX_RECENT_RECOMMENDATIONS)

        prefs.edit()
            .putString(KEY_RECENT_RECOMMENDATIONS, recentEvents.joinToString(LIST_SEPARATOR))
            .apply()
    }

    private fun readList(key: String): List<String> {
        return prefs.getString(key, null)
            ?.split(LIST_SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
    }

    private fun recordPreferenceEvent(
        title: String,
        category: String,
        tag: String?,
        action: String
    ) {
        val cleanTitle = title.cleanFeedbackText()
        if (cleanTitle.isBlank()) {
            return
        }

        val timestamp = System.currentTimeMillis()
        val cutoff = timestamp - PREFERENCE_MEMORY_MS
        val keptEvents = readList(KEY_PREFERENCE_EVENTS)
            .mapNotNull(::parsePreferenceEvent)
            .filter { it.timestamp >= cutoff }
            .map(::serializePreferenceEvent)

        val newEvent = serializePreferenceEvent(
            PreferenceEvent(
                timestamp = timestamp,
                category = category.normalizeCategory(),
                action = action,
                title = cleanTitle,
                tag = tag?.cleanFeedbackText().orEmpty()
            )
        )

        prefs.edit()
            .putString(
                KEY_PREFERENCE_EVENTS,
                (keptEvents + newEvent)
                    .takeLast(MAX_PREFERENCE_EVENTS)
                    .joinToString(LIST_SEPARATOR)
            )
            .apply()
    }

    private fun parseCategoryEvent(raw: String): CategoryEvent? {
        val parts = raw.split("|")
        if (parts.size != 2) {
            return null
        }

        val timestamp = parts[0].toLongOrNull() ?: return null
        return CategoryEvent(
            timestamp = timestamp,
            category = parts[1].normalizeCategory()
        )
    }

    private fun parseTitleEvent(raw: String): TitleEvent? {
        val separatorIndex = raw.indexOf('|')
        if (separatorIndex <= 0 || separatorIndex >= raw.lastIndex) {
            return null
        }

        val timestamp = raw.substring(0, separatorIndex).toLongOrNull() ?: return null
        val title = raw.substring(separatorIndex + 1).cleanFeedbackText()
        if (title.isBlank()) {
            return null
        }

        return TitleEvent(
            timestamp = timestamp,
            title = title
        )
    }

    private fun parsePreferenceEvent(raw: String): PreferenceEvent? {
        val parts = raw.split("|", limit = 5)
        if (parts.size != 5) {
            return null
        }

        return PreferenceEvent(
            timestamp = parts[0].toLongOrNull() ?: return null,
            category = parts[1].normalizeCategory(),
            action = parts[2].trim(),
            title = parts[3].decodePreferenceField().cleanFeedbackText(),
            tag = parts[4].decodePreferenceField().cleanFeedbackText()
        )
    }

    private fun serializePreferenceEvent(event: PreferenceEvent): String {
        return listOf(
            event.timestamp.toString(),
            event.category.normalizeCategory(),
            event.action,
            event.title.encodePreferenceField(),
            event.tag.encodePreferenceField()
        ).joinToString("|")
    }

    private fun String.cleanFeedbackText(): String {
        return trim()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(MAX_TITLE_LENGTH)
    }

    private fun String.normalizeCategory(): String {
        return when (trim().lowercase()) {
            "meal" -> "meal"
            "play" -> "play"
            else -> "play"
        }
    }

    private data class CategoryEvent(
        val timestamp: Long,
        val category: String
    )

    private data class TitleEvent(
        val timestamp: Long,
        val title: String
    )

    private data class PreferenceEvent(
        val timestamp: Long,
        val category: String,
        val action: String,
        val title: String,
        val tag: String
    )

    private fun String.encodePreferenceField(): String {
        return cleanFeedbackText()
            .replace("|", " ")
    }

    private fun String.decodePreferenceField(): String = this

    companion object {
        const val PREFS_NAME = "recommendation_feedback"
        const val KEY_DISLIKED_NAMES = "disliked_names"
        const val KEY_CATEGORY_EVENTS = "category_events"
        const val KEY_RECENT_RECOMMENDATIONS = "recent_recommendations"
        const val KEY_PREFERENCE_EVENTS = "preference_events"
        const val LIST_SEPARATOR = "\n"
        const val MAX_DISLIKED_NAMES = 48
        const val MAX_CATEGORY_EVENTS = 80
        const val MAX_RECENT_RECOMMENDATIONS = 36
        const val MAX_PREFERENCE_EVENTS = 160
        const val MAX_TITLE_LENGTH = 80
        const val CATEGORY_MEMORY_MS = 14L * 24L * 60L * 60L * 1000L
        const val PREFERENCE_MEMORY_MS = 30L * 24L * 60L * 60L * 1000L
        const val RECENT_RECOMMENDATION_COOLDOWN_MS = 2L * 60L * 60L * 1000L
        const val ACTION_SAVE = "save"
        const val ACTION_NAVIGATE = "navigate"
        const val ACTION_EXTERNAL_SEARCH = "external_search"
        const val ACTION_NOT_INTERESTED = "not_interested"
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
        private const val POSITIVE_TRAIT_THRESHOLD = 4
        private const val NEGATIVE_TRAIT_THRESHOLD = -4
    }
}
