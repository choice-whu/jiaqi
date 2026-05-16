package com.example.dateapp.ui.decision

import org.junit.Assert.*
import org.junit.Test

class DecisionNameTrackerTest {

    private fun createTracker(): DecisionNameTracker {
        val mockStore = object : NameTrackerStore {
            override fun recentRecommendedNames(): List<String> = emptyList()
            override fun dislikedNames(): List<String> = emptyList()
            override fun recordRecommended(name: String) {}
        }
        return DecisionNameTracker(mockStore)
    }

    @Test
    fun rememberRecentName_storesName() {
        val tracker = createTracker()
        tracker.rememberRecentName("星巴克")
        assertTrue(tracker.getRecentNames().contains("星巴克"))
    }

    @Test
    fun rememberDislikedName_storesName() {
        val tracker = createTracker()
        tracker.rememberDislikedName("海底捞")
        assertTrue(tracker.isDisliked("海底捞"))
    }

    @Test
    fun isDisliked_returnsFalse_whenNotDisliked() {
        val tracker = createTracker()
        assertFalse(tracker.isDisliked("不存在"))
    }

    @Test
    fun recentAvoidNames_excludesEmpty() {
        val tracker = createTracker()
        val avoidNames = tracker.recentAvoidNames(null)
        assertTrue(avoidNames.isEmpty())
    }

    @Test
    fun recentAvoidNames_includesCurrentTitle() {
        val tracker = createTracker()
        val avoidNames = tracker.recentAvoidNames("当前卡片")
        assertEquals("当前卡片", avoidNames.first())
    }

    @Test
    fun rememberDecision_callsRememberRecentName() {
        val tracker = createTracker()
        val card = createCard("测试地点")
        tracker.rememberDecision(card)
        assertTrue(tracker.getRecentNames().contains("测试地点"))
    }

    @Test
    fun rememberNotInterested_callsBoth() {
        val tracker = createTracker()
        val card = createCard("不喜欢的地方")
        tracker.rememberNotInterested(card)
        assertTrue(tracker.isDisliked("不喜欢的地方"))
        assertTrue(tracker.getRecentNames().contains("不喜欢的地方"))
    }

    @Test
    fun hardAvoidNames_returnsDislikedOnly() {
        val tracker = createTracker()
        tracker.rememberDislikedName("讨厌A")
        tracker.rememberDislikedName("讨厌B")
        tracker.rememberRecentName("近期A")

        val hard = tracker.hardAvoidNames()
        assertTrue(hard.contains("讨厌A"))
        assertTrue(hard.contains("讨厌B"))
    }

    @Test
    fun getRecentNames_initiallyEmpty() {
        val tracker = createTracker()
        assertTrue(tracker.getRecentNames().isEmpty())
    }

    @Test
    fun getDislikedNames_initiallyEmpty() {
        val tracker = createTracker()
        assertTrue(tracker.getDislikedNames().isEmpty())
    }

    @Test
    fun isSimilarPlaceName_sameName_returnsTrue() {
        assertTrue(
            DecisionNameTracker.isSimilarPlaceName("星巴克", "星巴克")
        )
    }

    @Test
    fun isSimilarPlaceName_differentNames_returnsFalse() {
        assertFalse(
            DecisionNameTracker.isSimilarPlaceName("星巴克", "麦当劳")
        )
    }

    @Test
    fun normalizePlaceName_trimsWhitespace() {
        assertEquals(
            "测试", DecisionNameTracker.normalizePlaceName(" 测试 ")
        )
    }

    companion object {
        fun createCard(title: String): DecisionCardUiModel {
            return DecisionCardUiModel(
                id = "test_1",
                title = title,
                category = "meal",
                locationLabel = null,
                routeKeyword = null,
                distanceDescription = null,
                tag = null,
                imageUrl = null,
                latitude = null,
                longitude = null,
                source = DecisionSource.AI,
                sourceLabel = "测试",
                momentLabel = "测试时刻",
                supportingText = "测试说明"
            )
        }
    }
}
