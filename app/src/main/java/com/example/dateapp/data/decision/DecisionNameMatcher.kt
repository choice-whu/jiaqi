package com.example.dateapp.data.decision

import java.util.Locale

object DecisionNameMatcher {
    fun isSimilarPlaceName(
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

    fun normalizePlaceName(name: String): String {
        return name.lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\p{Punct}（）()【】\\[\\]「」『』《》“”‘’、，。！？；：·•-]+"), "")
            .replace("武汉", "")
            .replace("湖北", "")
            .replace("旗舰", "")
            .replace("总", "")
            .replace("店", "")
            .trim()
    }
}
