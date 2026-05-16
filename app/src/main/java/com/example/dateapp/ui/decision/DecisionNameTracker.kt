package com.example.dateapp.ui.decision

import com.example.dateapp.data.decision.DecisionNameMatcher

interface NameTrackerStore {
    fun recentRecommendedNames(): List<String>
    fun dislikedNames(): List<String>
    fun recordRecommended(name: String)
}

class DecisionNameTracker(
    private val store: NameTrackerStore,
    private val recentMemory: Int = 36,
    private val dislikedMemory: Int = 64
) {
    private val recentDecisionNames = ArrayDeque<String>().apply {
        addAll(store.recentRecommendedNames())
    }
    private val dislikedDecisionNames = ArrayDeque<String>().apply {
        addAll(store.dislikedNames())
    }

    fun rememberRecentName(name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return

        recentDecisionNames.removeAll { isSimilarPlaceName(trimmedName, it) }
        recentDecisionNames.addLast(trimmedName)
        store.recordRecommended(trimmedName)
        while (recentDecisionNames.size > recentMemory) {
            recentDecisionNames.removeFirst()
        }
    }

    fun rememberDislikedName(name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return

        dislikedDecisionNames.removeAll { isSimilarPlaceName(trimmedName, it) }
        dislikedDecisionNames.addLast(trimmedName)
        while (dislikedDecisionNames.size > dislikedMemory) {
            dislikedDecisionNames.removeFirst()
        }
    }

    fun recentAvoidNames(currentTitle: String?, recentPromptMemory: Int = 3, dislikedPromptMemory: Int = 5, promptLimit: Int = 8): List<String> {
        val recentExactRepeats = recentDecisionNames.toList().takeLast(recentPromptMemory)
        val explicitDislikes = dislikedDecisionNames.toList().takeLast(dislikedPromptMemory)
        return (listOfNotNull(currentTitle) + explicitDislikes + recentExactRepeats)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .takeLast(promptLimit)
    }

    fun hardAvoidNames(memory: Int = 8): List<String> {
        return dislikedDecisionNames.toList().takeLast(memory)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    fun rememberDecision(card: DecisionCardUiModel) {
        rememberRecentName(card.title)
    }

    fun rememberNotInterested(card: DecisionCardUiModel) {
        rememberDislikedName(card.title)
        rememberRecentName(card.title)
    }

    fun isDisliked(name: String): Boolean {
        return dislikedDecisionNames.any { isSimilarPlaceName(name, it) }
    }

    fun getRecentNames(): List<String> = recentDecisionNames.toList()
    fun getDislikedNames(): List<String> = dislikedDecisionNames.toList()

    fun dropMatching(name: String) {
        recentDecisionNames.removeAll { isSimilarPlaceName(name, it) }
    }

    companion object {
        fun isSimilarPlaceName(first: String, second: String): Boolean {
            return DecisionNameMatcher.isSimilarPlaceName(first, second)
        }

        fun normalizePlaceName(name: String): String {
            return DecisionNameMatcher.normalizePlaceName(name)
        }
    }
}
