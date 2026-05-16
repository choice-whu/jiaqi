package com.example.dateapp.ui.decision

import com.example.dateapp.data.WuhanKnowledgeConfig
import com.example.dateapp.data.local.WishItem
import com.example.dateapp.data.decision.DecisionEngineCandidate
import com.example.dateapp.data.decision.DecisionEngineResult
import com.example.dateapp.data.decision.DecisionWeatherProfile
import com.example.dateapp.data.environment.DecisionEnvironmentSnapshot
import com.example.dateapp.data.remote.AiDecisionRecommendation
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class DecisionCardAssembler(
    private val zoneId: ZoneId = WuhanKnowledgeConfig.zoneId
) {
    private val shortTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun currentHour(): Int = ZonedDateTime.now(zoneId).hour

    fun toAiCard(
        result: DecisionEngineResult,
        targetCategory: String
    ): DecisionCardUiModel {
        return toAiCards(result, targetCategory).first()
    }

    fun toAiCards(
        result: DecisionEngineResult,
        targetCategory: String
    ): List<DecisionCardUiModel> {
        val candidates = result.rankedCandidates.ifEmpty {
            listOf(
                DecisionEngineCandidate(
                    recommendation = result.recommendation,
                    resolvedPlace = result.resolvedPlace,
                    score = 0
                )
            )
        }
        return candidates.mapIndexed { index, candidate ->
            toCandidateCard(
                candidate = candidate,
                targetCategory = targetCategory,
                environment = result.environment,
                weatherProfile = result.weatherProfile,
                index = index
            )
        }
    }

    fun toCandidateCard(
        candidate: DecisionEngineCandidate,
        targetCategory: String,
        environment: DecisionEnvironmentSnapshot,
        weatherProfile: DecisionWeatherProfile,
        index: Int
    ): DecisionCardUiModel {
        val recommendation = candidate.recommendation
        val resolvedPlace = candidate.resolvedPlace
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

    fun toLocalCard(item: WishItem, hour: Int = currentHour()): DecisionCardUiModel {
        return DecisionCardUiModel(
            id = "local_${item.id}",
            localWishId = item.id,
            title = item.title,
            category = item.category,
            locationLabel = item.locationKeyword,
            routeKeyword = item.locationKeyword ?: item.title,
            distanceDescription = null,
            tag = null,
            imageUrl = null,
            latitude = item.latitude,
            longitude = item.longitude,
            source = DecisionSource.LOCAL,
            sourceLabel = "心愿池",
            momentLabel = buildMomentLabel(hour, item.category),
            supportingText = buildLocalSupportingText(item),
            contextLine = item.locationKeyword?.let { "$it · 你们记下过的想去清单" }
        )
    }

    fun buildLocalSupportingText(item: WishItem): String {
        val place = item.locationKeyword ?: item.title
        return if (item.category == "meal") {
            when {
                item.title.contains("咖啡") -> "$place 一带适合坐下来慢慢聊一会儿的咖啡去处，节奏会很松弛。"
                item.title.contains("烧烤") -> "$place 一带适合热热闹闹吃一顿的烧烤去处，越到晚上越有气氛。"
                item.title.contains("火锅") || item.title.contains("寿喜锅") -> "$place 一带适合吃一顿热腾腾锅物的地方，尤其适合饭点去。"
                item.title.contains("小酒馆") || item.title.contains("酒吧") -> "$place 一带适合微醺放松的小酒馆去处，晚上会更对味。"
                else -> "$place 一带一个值得去试试的餐饮去处，适合把这顿饭认真安排上。"
            }
        } else {
            when {
                item.title.contains("东湖") || item.title.contains("公园") || item.title.contains("江滩") || item.title.contains("散步") ->
                    "$place 是个适合慢慢散步、拍照和放松的地方，不会太费力。"

                item.title.contains("展") || item.title.contains("美术馆") || item.title.contains("博物馆") ->
                    "$place 适合慢慢逛一圈，轻松看点东西，也方便边走边聊。"

                item.title.contains("酒馆") || item.title.contains("livehouse", ignoreCase = true) ->
                    "$place 更适合天色暗下来以后去待一会儿，氛围会比白天更完整。"

                else -> "$place 是个适合顺路去打卡、短暂停留放松一下的去处。"
            }
        }
    }

    fun buildMomentLabel(hour: Int, category: String): String {
        return when {
            hour in 0..4 -> if (category == "meal") "宵夜时刻" else "夜色去处"
            hour in 5..9 -> if (category == "meal") "早餐灵感" else "清晨去处"
            hour in 10..13 -> if (category == "meal") "饭点推荐" else "午间去处"
            hour in 14..17 -> if (category == "meal") "餐饮灵感" else "下午安排"
            hour in 18..20 -> if (category == "meal") "晚餐安排" else "傍晚去处"
            hour in 21..23 -> if (category == "meal") "夜宵备选" else "夜晚安排"
            else -> if (category == "meal") "这一顿" else "这一站"
        }
    }

    fun buildAiSupportingText(
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

    fun refineCachedSupportingText(card: DecisionCardUiModel, currentHour: Int): String {
        val text = preferenceSignalText(card).lowercase(Locale.ROOT)
        return when {
            card.category == DecisionMode.PLAY.category &&
                containsAnySignal(text, "艺术空间", "美术馆", "艺术馆", "画廊", "展览", "展馆", "sart") ->
                "展陈安静，适合慢慢看。"
            card.category == DecisionMode.PLAY.category &&
                containsAnySignal(text, "桌游", "私影", "ps5", "switch", "电玩", "密室", "剧本", "vr") ->
                "互动感更强，适合一起玩。"
            card.category == DecisionMode.PLAY.category &&
                containsAnySignal(text, "唱片", "黑胶", "中古", "买手", "生活方式", "潮玩", "盲盒") ->
                "小物件好逛，适合随手发现。"
            card.category == DecisionMode.MEAL.category &&
                currentHour in 14..17 &&
                containsAnySignal(text, "饺子", "云饺", "水饺") ->
                "热乎轻松，想吃点咸口也稳。"
            else -> card.supportingText
        }
    }

    fun buildAiContextLine(
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
            "室内", "商场", "购物中心", "百货", "影院", "电影",
            "书店", "展", "馆", "避雨", "遮蔽", "空调", "步行", "可达"
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

    fun recommendationSignalText(recommendation: AiDecisionRecommendation): String {
        return buildString {
            append(recommendation.name)
            append(' ')
            append(recommendation.amapSearchKeyword.orEmpty())
            append(' ')
            append(recommendation.distanceDescription.orEmpty())
            append(' ')
            append(recommendation.tag.orEmpty())
            append(' ')
            append(recommendation.intro.orEmpty())
        }
    }

    fun buildContextLine(environment: DecisionEnvironmentSnapshot): String {
        val timeText = environment.currentTime.format(shortTimeFormatter)
        return "$timeText · ${environment.weatherCondition} · ${environment.userLocationLabel}"
    }

    fun preferenceSignalText(card: DecisionCardUiModel): String {
        return listOfNotNull(
            card.tag,
            card.supportingText,
            card.locationLabel,
            card.routeKeyword,
            card.contextLine
        )
            .joinToString(" ")
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: card.title
    }

    companion object {
        fun containsAnySignal(
            text: String,
            vararg keywords: String
        ): Boolean {
            val normalizedText = text.lowercase(Locale.ROOT)
            return keywords.any(normalizedText::contains)
        }
    }
}
