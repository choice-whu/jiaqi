package com.example.dateapp.data

import org.junit.Assert.*
import org.junit.Test

class WuhanKnowledgeConfigTest {

    @Test
    fun whuCoreAreas_nonEmpty() {
        assertTrue(WuhanKnowledgeConfig.whuCoreAreas.isNotEmpty())
    }

    @Test
    fun hubuCoreAreas_nonEmpty() {
        assertTrue(WuhanKnowledgeConfig.hubuCoreAreas.isNotEmpty())
    }

    @Test
    fun cityCoreAreas_nonEmpty() {
        assertTrue(WuhanKnowledgeConfig.cityCoreAreas.isNotEmpty())
    }

    @Test
    fun tripWorthySignals_nonEmpty() {
        assertTrue(WuhanKnowledgeConfig.tripWorthySignals.isNotEmpty())
    }

    @Test
    fun aiAvoidDefaultSignals_nonEmpty() {
        assertTrue(WuhanKnowledgeConfig.aiAvoidDefaultSignals.isNotEmpty())
    }

    @Test
    fun specialtySnacks_nonEmpty() {
        assertTrue(WuhanKnowledgeConfig.specialtySnacks.isNotEmpty())
    }

    @Test
    fun mallKeywords_nonEmpty() {
        assertTrue(WuhanKnowledgeConfig.mallKeywords.isNotEmpty())
    }

    @Test
    fun metroLines_containsWuhanLines() {
        assertTrue(WuhanKnowledgeConfig.metroLines.any { it == "2号线" })
        assertTrue(WuhanKnowledgeConfig.metroLines.any { it == "4号线" })
    }

    @Test
    fun tripWorthySignals_containsKeyLandmarks() {
        assertTrue(WuhanKnowledgeConfig.tripWorthySignals.any { it == "东湖" })
        assertTrue(WuhanKnowledgeConfig.tripWorthySignals.any { it == "黄鹤楼" })
    }

    @Test
    fun isWuhanCoreArea_whu_returnsTrue() {
        assertTrue(WuhanKnowledgeConfig.isWuhanCoreArea("街道口一家店"))
    }

    @Test
    fun isWuhanCoreArea_hubu_returnsTrue() {
        assertTrue(WuhanKnowledgeConfig.isWuhanCoreArea("徐东大街"))
    }

    @Test
    fun isWuhanCoreArea_unknown_returnsFalse() {
        assertFalse(WuhanKnowledgeConfig.isWuhanCoreArea("北京王府井"))
    }

    @Test
    fun isRemoteDistrict_returnsTrue_forRemote() {
        assertTrue(WuhanKnowledgeConfig.isRemoteDistrict("黄陂某景区"))
    }

    @Test
    fun isRemoteDistrict_returnsFalse_forCore() {
        assertFalse(WuhanKnowledgeConfig.isRemoteDistrict("街道口"))
    }

    @Test
    fun zoneId_isAsiaShanghai() {
        assertEquals("Asia/Shanghai", WuhanKnowledgeConfig.zoneId.id)
    }

    @Test
    fun emergencyCoords_areValid() {
        assertTrue(WuhanKnowledgeConfig.EMERGENCY_LAT in 30.0..31.0)
        assertTrue(WuhanKnowledgeConfig.EMERGENCY_LNG in 114.0..115.0)
    }

    @Test
    fun transitKeywords_containsMetroAndBus() {
        assertTrue(WuhanKnowledgeConfig.transitKeywords.any { it == "地铁" })
        assertTrue(WuhanKnowledgeConfig.transitKeywords.any { it == "公交" })
    }

    @Test
    fun noDuplicateEntriesInCoreAreas() {
        val combined = WuhanKnowledgeConfig.whuCoreAreas +
            WuhanKnowledgeConfig.hubuCoreAreas +
            WuhanKnowledgeConfig.cityCoreAreas
        assertEquals(combined.size, combined.distinct().size)
    }
}
