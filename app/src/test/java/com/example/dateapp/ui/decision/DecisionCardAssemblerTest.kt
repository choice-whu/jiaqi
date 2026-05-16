package com.example.dateapp.ui.decision

import org.junit.Assert.*
import org.junit.Test

class DecisionCardAssemblerTest {

    private val assembler = DecisionCardAssembler()

    @Test
    fun buildMomentLabel_meal_morning_returnsBreakfast() {
        assertEquals("早餐灵感", assembler.buildMomentLabel(7, "meal"))
    }

    @Test
    fun buildMomentLabel_meal_midday_returnsMealtime() {
        assertEquals("饭点推荐", assembler.buildMomentLabel(12, "meal"))
    }

    @Test
    fun buildMomentLabel_meal_evening_returnsDinner() {
        assertEquals("晚餐安排", assembler.buildMomentLabel(19, "meal"))
    }

    @Test
    fun buildMomentLabel_meal_latenight_returnsSupper() {
        assertEquals("宵夜时刻", assembler.buildMomentLabel(2, "meal"))
    }

    @Test
    fun buildMomentLabel_play_morning_returnsMorning() {
        assertEquals("清晨去处", assembler.buildMomentLabel(8, "play"))
    }

    @Test
    fun buildMomentLabel_play_afternoon_returnsAfternoon() {
        assertEquals("下午安排", assembler.buildMomentLabel(15, "play"))
    }

    @Test
    fun buildMomentLabel_play_night_returnsNight() {
        assertEquals("夜晚安排", assembler.buildMomentLabel(22, "play"))
    }

    @Test
    fun buildAiSupportingText_withoutIntro_meal_returnsFallback() {
        val rec = createRecommendation(intro = null)
        val result = assembler.buildAiSupportingText(rec, "meal")
        assertTrue(result.contains("坐下来吃饭"))
    }

    @Test
    fun buildAiSupportingText_withoutIntro_play_returnsFallback() {
        val rec = createRecommendation(intro = null)
        val result = assembler.buildAiSupportingText(rec, "play")
        assertTrue(result.contains("去逛逛"))
    }

    @Test
    fun buildAiSupportingText_withIntro_returnsIntro() {
        val rec = createRecommendation(intro = "安静的小咖啡店")
        assertEquals("安静的小咖啡店", assembler.buildAiSupportingText(rec, "meal"))
    }

    @Test
    fun buildContextLine_formatsCorrectly() {
        val env = createEnvironmentSnapshot("14:30", "晴朗", "街道口")
        val result = assembler.buildContextLine(env)
        assertTrue(result.contains("晴朗"))
        assertTrue(result.contains("街道口"))
    }

    @Test
    fun containsAnySignal_matches_returnsTrue() {
        val result = DecisionCardAssembler.containsAnySignal(
            "去东湖边上散步看日落",
            "东湖", "咖啡", "书店"
        )
        assertTrue(result)
    }

    @Test
    fun containsAnySignal_noMatch_returnsFalse() {
        val result = DecisionCardAssembler.containsAnySignal(
            "去商场逛街购物",
            "东湖", "咖啡", "书店"
        )
        assertFalse(result)
    }

    @Test
    fun currentHour_returnsValidHour() {
        val hour = assembler.currentHour()
        assertTrue(hour in 0..23)
    }

    @Test
    fun buildMomentLabel_unknownCategory_treatsAsNonMeal() {
        val result = assembler.buildMomentLabel(12, "unknown")
        assertEquals("午间去处", result)
    }

    companion object {
        fun createRecommendation(
            name: String = "测试咖啡店",
            amapKeyword: String? = null,
            distanceDesc: String? = null,
            tag: String? = null,
            intro: String? = null
        ): com.example.dateapp.data.remote.AiDecisionRecommendation {
            return com.example.dateapp.data.remote.AiDecisionRecommendation(
                name = name,
                amapSearchKeyword = amapKeyword,
                imageUrl = null,
                distanceDescription = distanceDesc,
                tag = tag,
                intro = intro
            )
        }

        fun createEnvironmentSnapshot(
            timeLabel: String,
            weather: String,
            locationLabel: String
        ): com.example.dateapp.data.environment.DecisionEnvironmentSnapshot {
            val now = java.time.ZonedDateTime.now()
            return com.example.dateapp.data.environment.DecisionEnvironmentSnapshot(
                currentTime = now,
                currentTimeLabel = timeLabel,
                weatherCondition = weather,
                userLocationLabel = locationLabel,
                latitude = 30.56,
                longitude = 114.35,
                locationSource = "test",
                weatherSource = "test"
            )
        }
    }
}
