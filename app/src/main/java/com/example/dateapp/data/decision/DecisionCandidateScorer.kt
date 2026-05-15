package com.example.dateapp.data.decision

import com.example.dateapp.data.place.PlaceConfidence
import com.example.dateapp.data.place.PlaceResolver
import com.example.dateapp.data.place.ResolvedPlace
import com.example.dateapp.data.recommendation.RecommendationPreferenceProfile
import com.example.dateapp.data.recommendation.RecommendationTraitAnalyzer
import com.example.dateapp.data.recommendation.RecommendationTopic
import com.example.dateapp.data.remote.AiDecisionRecommendation
import com.example.dateapp.data.route.DecisionPoiCandidate
import java.util.Locale
import kotlin.math.roundToInt

data class PersonalizationScoreBreakdown(
    val score: Int,
    val traits: Set<String>,
    val eventCount: Int
)

data class StudentCoupleScoreBreakdown(
    val transitScore: Int,
    val classicScore: Int,
    val surpriseScore: Int,
    val campusScore: Int,
    val budgetScore: Int
) {
    val totalScore: Int
        get() = transitScore + classicScore + surpriseScore + campusScore + budgetScore
}

class DecisionCandidateScorer(
    private val placePolicy: DecisionPlacePolicy
) {
    fun buildBaseScore(
        timeScore: Int,
        weatherScore: Int,
        resolvedPlace: ResolvedPlace,
        category: String
    ): Int {
        val confidenceScore = when (resolvedPlace.confidence) {
            PlaceConfidence.HIGH -> 4
            PlaceConfidence.MEDIUM -> 2
            PlaceConfidence.LOW -> 0
            PlaceConfidence.UNRESOLVED -> -2
        }
        val distanceScore = resolvedPlace.directDistanceMeters?.let { meters ->
            if (category == "meal") {
                when {
                    meters <= 800 -> 4
                    meters <= 1_500 -> 3
                    meters <= PlaceResolver.MEAL_MAX_DIRECT_DISTANCE_METERS -> 1
                    else -> -6
                }
            } else {
                val isTripWorthy = placePolicy.isTripWorthyPlayDestination(resolvedPlace)
                when {
                    meters <= 3_000 -> 4
                    meters <= 5_000 -> 3
                    meters <= DecisionPlacePolicy.PLAY_MAX_DIRECT_DISTANCE_METERS -> 1
                    isTripWorthy && meters <= DecisionPlacePolicy.PLAY_TRIP_WORTHY_MAX_DIRECT_DISTANCE_METERS -> 0
                    else -> -8
                }
            }
        } ?: 0

        return timeScore * 3 + weatherScore * 3 + confidenceScore + distanceScore
    }

    fun personalizationScore(
        recommendation: AiDecisionRecommendation,
        resolvedPlace: ResolvedPlace,
        category: String,
        profile: RecommendationPreferenceProfile?
    ): PersonalizationScoreBreakdown {
        if (profile == null) {
            return PersonalizationScoreBreakdown(score = 0, traits = emptySet(), eventCount = 0)
        }
        val text = buildString {
            append(placePolicy.recommendationSignalText(recommendation))
            append(' ')
            append(resolvedPlace.displayName)
            append(' ')
            append(resolvedPlace.routeKeyword)
        }
        val traits = RecommendationTraitAnalyzer.extractTraits(
            text = text,
            category = category
        )
        val rawTraitScore = traits.sumOf { trait ->
            (profile.traitScores[trait] ?: 0).coerceIn(-9, 9)
        }
        val multiplier = when {
            profile.eventCount >= 10 -> 1.65
            profile.eventCount >= 6 -> 1.4
            profile.eventCount >= 3 -> 1.2
            else -> 1.0
        }
        val score = (rawTraitScore * multiplier)
            .roundToInt()
            .coerceIn(-18, 18)

        return PersonalizationScoreBreakdown(
            score = score,
            traits = traits,
            eventCount = profile.eventCount
        )
    }

    fun diningAreaPreferenceScore(
        category: String,
        resolvedPlace: ResolvedPlace
    ): Int {
        if (category != "meal") {
            return 0
        }

        val text = buildString {
            append(resolvedPlace.displayName)
            append(' ')
            append(resolvedPlace.routeKeyword)
            append(' ')
            append(resolvedPlace.source)
        }.lowercase(Locale.ROOT)

        val specialtySnackScore = if (listOf(
                "锅盔",
                "馄饨",
                "羊肉粉",
                "牛肉粉",
                "牛肉面",
                "水煎包",
                "烧鸭",
                "鱼鲜",
                "小吃",
                "热干面",
                "豆皮"
            ).any(text::contains)
        ) {
            2
        } else {
            0
        }

        return when {
            text.contains("huda_mixc") -> 8 + specialtySnackScore
            listOf(
                "湖北大学",
                "湖大",
                "武昌万象城",
                "万象城",
                "群星城",
                "徐东",
                "水岸星城",
                "团结大道",
                "沙湖"
            ).any(text::contains) -> 4 + specialtySnackScore
            else -> specialtySnackScore
        }
    }

    fun poiTypeReliabilityScore(
        category: String,
        poi: DecisionPoiCandidate?
    ): Int {
        val typeText = poi?.typeDescription.orEmpty().lowercase(Locale.ROOT)
        val nameText = poi?.displayName.orEmpty().lowercase(Locale.ROOT)
        if (category == "meal") {
            return when {
                typeText.contains("餐饮相关") -> -5
                typeText.contains("快餐") -> -4
                typeText.contains("外国餐厅") -> 3
                typeText.contains("中餐厅") -> 2
                typeText.contains("火锅") || typeText.contains("烧烤") -> 2
                typeText.contains("餐饮服务") -> 1
                else -> 0
            } + if (listOf("万象城", "群星城", "湖大", "湖北大学").any(nameText::contains)) 1 else 0
        }

        return 0
    }

    fun dateAppealScore(
        category: String,
        recommendation: AiDecisionRecommendation,
        resolvedPlace: ResolvedPlace
    ): Int {
        if (category != "play") {
            return 0
        }

        val text = buildString {
            append(placePolicy.recommendationSignalText(recommendation))
            append(' ')
            append(resolvedPlace.displayName)
            append(' ')
            append(resolvedPlace.routeKeyword)
        }.lowercase(Locale.ROOT)

        val specificExperienceBonus = listOf(
            "diy",
            "手作",
            "陶艺",
            "银饰",
            "调香",
            "vr",
            "密室",
            "桌游",
            "剧本",
            "攀岩",
            "射箭",
            "电玩城",
            "体验馆",
            "美术馆",
            "艺术空间",
            "画廊",
            "展览",
            "展馆",
            "展陈",
            "看展",
            "蜡像馆",
            "码头"
        ).count(text::contains).coerceAtMost(3) * 2

        val genericPlacePenalty = listOf(
            "街区",
            "商业街",
            "步行街",
            "广场",
            "入口",
            "闸口",
            "凉亭",
            "发展林",
            "纪念林",
            "主题林",
            "红枫林",
            "海棠林",
            "树林"
        ).count(text::contains).coerceAtMost(2) * 3

        val resolvedDistance = resolvedPlace.directDistanceMeters ?: 0
        val tripWorthy = placePolicy.isTripWorthyPlayDestination(resolvedPlace)
        val specialExperience = placePolicy.isSpecialExperiencePlayDestination(recommendation, resolvedPlace)
        val distancePenalty = when {
            tripWorthy -> 0
            specialExperience && resolvedDistance <= DecisionPlacePolicy.PLAY_MAX_DIRECT_DISTANCE_METERS -> 1
            resolvedDistance > 6_000 -> 8
            resolvedDistance > 4_000 -> 5
            resolvedDistance > 3_000 -> 2
            else -> 0
        }

        return specificExperienceBonus - genericPlacePenalty - distancePenalty
    }

    fun studentCoupleProfileScore(
        category: String,
        recommendation: AiDecisionRecommendation,
        resolvedPlace: ResolvedPlace,
        request: DecisionEngineRequest
    ): StudentCoupleScoreBreakdown {
        val text = studentCoupleSignalText(recommendation, resolvedPlace)
        return StudentCoupleScoreBreakdown(
            transitScore = transportAccessibilityScore(
                category = category,
                text = text,
                resolvedPlace = resolvedPlace,
                request = request
            ),
            classicScore = classicRomanceScore(text, request.recommendationTopic),
            surpriseScore = surpriseBoxScore(text, request.recommendationTopic),
            campusScore = campusDepthScore(category, text),
            budgetScore = studentBudgetScore(category, text)
        )
    }

    fun timeSuitability(
        recommendation: AiDecisionRecommendation,
        category: String,
        hour: Int
    ): Int {
        return timeSuitabilityScore(
            text = placePolicy.recommendationSignalText(recommendation),
            category = category,
            hour = hour
        )
    }

    fun weatherSuitability(
        recommendation: AiDecisionRecommendation,
        category: String,
        weatherProfile: DecisionWeatherProfile
    ): Int {
        return weatherSuitabilityScore(
            text = placePolicy.recommendationSignalText(recommendation),
            category = category,
            weatherProfile = weatherProfile
        )
    }

    private fun studentCoupleSignalText(
        recommendation: AiDecisionRecommendation,
        resolvedPlace: ResolvedPlace
    ): String {
        return buildString {
            append(placePolicy.recommendationSignalText(recommendation))
            append(' ')
            append(resolvedPlace.displayName)
            append(' ')
            append(resolvedPlace.routeKeyword)
            append(' ')
            append(resolvedPlace.source)
        }.lowercase(Locale.ROOT)
    }

    private fun transportAccessibilityScore(
        category: String,
        text: String,
        resolvedPlace: ResolvedPlace,
        request: DecisionEngineRequest
    ): Int {
        val distance = resolvedPlace.directDistanceMeters ?: return 0
        val transitFriendly = containsAny(
            text,
            "地铁",
            "metro",
            "2号线",
            "4号线",
            "7号线",
            "8号线",
            "公交",
            "车站",
            "街道口",
            "广埠屯",
            "珞喻路",
            "珞珈",
            "武汉大学",
            "武大",
            "湖北大学",
            "湖大",
            "徐东",
            "沙湖",
            "楚河汉街",
            "汉街",
            "光谷",
            "武昌万象城",
            "群光",
            "银泰创意城",
            "凯德1818",
            "武汉天地"
        )
        val remoteSignal = containsAny(
            text,
            "黄陂",
            "新洲",
            "蔡甸",
            "汉南",
            "郊野",
            "农庄",
            "度假村",
            "山庄",
            "村"
        )
        val weekendLongTrip = request.environment.currentTime.dayOfWeek.value >= 6 &&
            category == "play" &&
            placePolicy.isTripWorthyPlayDestination(resolvedPlace)

        if (!weekendLongTrip && remoteSignal && distance > REMOTE_SIGNAL_DISTANCE_METERS) {
            return -14
        }

        return when {
            distance <= 1_500 -> 4
            distance <= 3_500 -> if (transitFriendly) 5 else 2
            distance <= 6_000 -> if (transitFriendly) 2 else -2
            distance <= 10_000 -> when {
                weekendLongTrip -> 2
                transitFriendly && category == "play" -> -1
                else -> -6
            }
            weekendLongTrip -> 0
            transitFriendly && category == "play" -> -5
            else -> -12
        }
    }

    private fun classicRomanceScore(
        text: String,
        topic: RecommendationTopic?
    ): Int {
        val topicBoost = if (topic?.classicRomance == true) 3 else 0
        val signalScore = listOf(
            "大头贴",
            "自拍",
            "写真",
            "照相",
            "电影院",
            "影院",
            "电影",
            "ktv",
            "电玩城",
            "抓娃娃",
            "娃娃机",
            "保龄球",
            "台球",
            "烤肉",
            "火锅",
            "西餐",
            "brunch",
            "江滩",
            "湖边",
            "公园",
            "绿道",
            "汉街",
            "光谷步行街"
        ).count(text::contains).coerceAtMost(3) * 2
        return topicBoost + signalScore
    }

    private fun surpriseBoxScore(
        text: String,
        topic: RecommendationTopic?
    ): Int {
        val topicBoost = if (topic?.surpriseBox == true) 4 else 0
        val signalScore = listOf(
            "快闪",
            "市集",
            "集市",
            "脱口秀",
            "即兴",
            "室内冲浪",
            "密室",
            "沉浸",
            "手作",
            "diy",
            "tufting",
            "陶艺",
            "银饰",
            "小众",
            "展览",
            "展馆",
            "展陈",
            "看展",
            "潮玩",
            "盲盒",
            "中古",
            "黑胶",
            "主理人"
        ).count(text::contains).coerceAtMost(3) * 2
        return topicBoost + signalScore
    }

    private fun campusDepthScore(
        category: String,
        text: String
    ): Int {
        val coreAreaScore = when {
            containsAny(text, "街道口", "广埠屯", "珞珈", "珞喻路", "武大", "武汉大学") -> 5
            containsAny(text, "湖大", "湖北大学", "徐东", "沙湖", "武昌万象城", "群星城", "团结大道") -> 5
            containsAny(text, "楚河汉街", "汉街", "中南路", "洪山广场", "光谷") -> 3
            else -> 0
        }
        val hiddenGemScore = if (containsAny(text, "私房", "小馆", "主理人", "小店", "地下", "livehouse", "画廊", "工作室")) {
            2
        } else {
            0
        }
        val mealCampusBoost = if (category == "meal" && coreAreaScore > 0) 2 else 0
        return coreAreaScore + hiddenGemScore + mealCampusBoost
    }

    private fun studentBudgetScore(
        category: String,
        text: String
    ): Int {
        val studentFriendly = containsAny(
            text,
            "老乡鸡",
            "尊宝",
            "袁记",
            "塔斯汀",
            "简餐",
            "小吃",
            "家常",
            "砂锅",
            "牛肉粉",
            "热干面",
            "披萨",
            "云饺"
        )
        val luxuryTrap = containsAny(text, "高端", "奢华", "会所", "私宴", "黑珍珠", "人均500", "人均800")
        return when {
            luxuryTrap -> -8
            category == "meal" && studentFriendly -> -2
            studentFriendly -> 2
            else -> 0
        }
    }

    private fun weatherSuitabilityScore(
        text: String,
        category: String,
        weatherProfile: DecisionWeatherProfile
    ): Int {
        val normalizedText = text.lowercase(Locale.ROOT)
        fun containsAny(vararg keywords: String): Boolean = keywords.any(normalizedText::contains)

        val indoorFriendly = containsAny(
            "室内",
            "商场",
            "购物中心",
            "百货",
            "mall",
            "买手店",
            "主理人",
            "唱片",
            "黑胶",
            "潮玩",
            "盲盒",
            "玩具",
            "书店",
            "电影",
            "影院",
            "剧场",
            "小剧场",
            "密室",
            "桌游",
            "电玩",
            "游戏厅",
            "保龄球",
            "台球",
            "攀岩",
            "手作",
            "陶艺",
            "文创",
            "市集",
            "集市",
            "小店",
            "中古",
            "生活方式",
            "沉浸",
            "ktv",
            "博物馆",
            "美术馆",
            "展览",
            "展馆",
            "看展",
            "咖啡",
            "甜品",
            "餐厅",
            "火锅",
            "茶",
            "酒吧"
        )
        val outdoorHeavy = containsAny(
            "公园",
            "江滩",
            "东湖",
            "江边",
            "湖边",
            "湖畔",
            "山",
            "校园",
            "骑行",
            "徒步",
            "露营",
            "日落",
            "晚霞",
            "露天",
            "观景"
        )

        var score = 0
        if (weatherProfile.isSevere || weatherProfile.isRainy || weatherProfile.isHot || weatherProfile.isCold) {
            if (indoorFriendly || category == "meal") {
                score += if (weatherProfile.isSevere) 4 else 2
            }
            if (outdoorHeavy) {
                score -= if (weatherProfile.isSevere || weatherProfile.isRainy) 5 else 3
            }
        }

        if (weatherProfile.isFoggy && containsAny("观景", "日落", "夜景", "高处")) {
            score -= 3
        }

        if (category == "play" && weatherProfile.isComfortableOutdoor && outdoorHeavy) {
            score += 2
        }

        return score
    }

    private fun timeSuitabilityScore(
        text: String,
        category: String,
        hour: Int
    ): Int {
        val normalizedText = text.lowercase(Locale.ROOT)
        fun containsAny(vararg keywords: String): Boolean = keywords.any(normalizedText::contains)

        var score = if (category == "meal") {
            when (hour) {
                in 6..9, in 11..13, in 17..20 -> 2
                in 21..23, in 0..2 -> 1
                else -> 0
            }
        } else {
            when (hour) {
                in 6..11, in 14..18 -> 2
                in 12..13, in 19..21 -> 1
                else -> 0
            }
        }

        if (containsAny("日落", "晚霞", "傍晚", "sunset")) {
            score += when (hour) {
                in 16..19 -> 4
                in 14..15, in 20..21 -> 1
                else -> -4
            }
        }

        if (containsAny("夜景", "酒吧", "小酒馆", "清吧", "livehouse")) {
            score += when (hour) {
                in 19..23 -> 3
                in 17..18 -> 1
                else -> -3
            }
        }

        if (containsAny("宵夜", "烧烤", "火锅")) {
            score += when (hour) {
                in 17..23, in 0..1 -> 2
                in 11..13 -> 1
                else -> -1
            }
        }

        if (containsAny("早餐", "早茶", "豆浆", "包子")) {
            score += when (hour) {
                in 6..10 -> 3
                else -> -2
            }
        }

        if (
            category == "meal" &&
            containsAny("小吃", "水煎包", "牛杂", "拉面", "牛肉面", "牛肉粉", "羊肉粉", "米粉", "粉面", "面馆") &&
            !containsAny("云饺", "饺子", "披萨", "市集", "豆皮", "锅盔", "炸炸", "泰", "印度", "越南", "墨西哥", "韩", "日料", "咖啡", "甜品", "茶")
        ) {
            score += when (hour) {
                in 6..13, in 18..20 -> 0
                else -> -4
            }
        }

        if (containsAny("咖啡", "书店", "展览", "美术馆", "博物馆", "买手店", "主理人", "唱片", "黑胶", "潮玩", "盲盒", "玩具", "手作", "陶艺", "文创", "小店", "市集", "集市", "中古", "生活方式")) {
            score += when (hour) {
                in 9..18 -> 2
                in 19..20 -> 1
                else -> -1
            }
        }

        if (containsAny("公园", "散步", "江滩", "东湖")) {
            score += when (hour) {
                in 6..10, in 16..20 -> 2
                in 11..15 -> 1
                else -> 0
            }
        }

        return score
    }

    private fun containsAny(
        text: String,
        vararg keywords: String
    ): Boolean {
        return keywords.any(text::contains)
    }

    private companion object {
        private const val REMOTE_SIGNAL_DISTANCE_METERS = 8_000
    }
}
