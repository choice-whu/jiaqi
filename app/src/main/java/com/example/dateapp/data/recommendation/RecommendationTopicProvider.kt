package com.example.dateapp.data.recommendation

import kotlin.random.Random

data class RecommendationTopic(
    val id: String,
    val category: String,
    val label: String,
    val promptHint: String,
    val compactHint: String = promptHint,
    val disallowHours: Set<Int> = emptySet(),
    val baseWeight: Int = 10,
    val traits: Set<String> = emptySet(),
    val outdoorSensitive: Boolean = false,
    val classicRomance: Boolean = false,
    val surpriseBox: Boolean = false,
    val campusDepth: Boolean = false
)

data class RecommendationTopicSelection(
    val topic: RecommendationTopic,
    val weight: Int,
    val candidateCount: Int,
    val cooledTopicCount: Int
)

class RecommendationTopicProvider(
    private val random: Random = Random.Default
) {
    fun pickTopic(
        category: String,
        recentTopicIds: Collection<String>,
        hour: Int,
        weatherCondition: String,
        preferenceProfile: RecommendationPreferenceProfile?
    ): RecommendationTopic {
        return pickTopicSelection(
            category = category,
            recentTopicIds = recentTopicIds,
            hour = hour,
            weatherCondition = weatherCondition,
            preferenceProfile = preferenceProfile
        ).topic
    }

    fun pickTopicSelection(
        category: String,
        recentTopicIds: Collection<String>,
        hour: Int,
        weatherCondition: String,
        preferenceProfile: RecommendationPreferenceProfile?
    ): RecommendationTopicSelection {
        val pool = if (category == MEAL_CATEGORY) {
            mealTopics
        } else {
            playTopics
        }
        val timeFitPool = pool.filterNot { topic -> hour in topic.disallowHours }.ifEmpty { pool }
        val freshPool = timeFitPool
            .filterNot { topic -> topic.id in recentTopicIds }
            .ifEmpty { timeFitPool }
        val recentOutdoorTopicCount = recentTopicIds
            .toList()
            .takeLast(RECENT_OUTDOOR_TOPIC_WINDOW)
            .count { topicId ->
                playTopics.firstOrNull { topic -> topic.id == topicId }?.outdoorSensitive == true
            }
        val surprisePool = freshPool.filter { topic -> topic.surpriseBox }
        if (category != MEAL_CATEGORY && surprisePool.isNotEmpty() && random.nextDouble() < SURPRISE_BOX_RATE) {
            val surpriseWeightedTopics = surprisePool.map { topic ->
                WeightedTopic(
                    topic = topic,
                    weight = topicWeight(
                        topic = topic,
                        hour = hour,
                        weatherCondition = weatherCondition,
                        preferenceProfile = preferenceProfile,
                        recentlyUsed = topic.id in recentTopicIds,
                        recentOutdoorTopicCount = recentOutdoorTopicCount
                    ) + SURPRISE_BOX_WEIGHT_BONUS
                )
            }
            val surpriseTopic = pickWeightedTopic(surpriseWeightedTopics)
            return RecommendationTopicSelection(
                topic = surpriseTopic.topic,
                weight = surpriseTopic.weight,
                candidateCount = surpriseWeightedTopics.size,
                cooledTopicCount = timeFitPool.size - freshPool.size
            )
        }

        val weightedTopics = freshPool.map { topic ->
            WeightedTopic(
                topic = topic,
                weight = topicWeight(
                    topic = topic,
                    hour = hour,
                    weatherCondition = weatherCondition,
                    preferenceProfile = preferenceProfile,
                    recentlyUsed = topic.id in recentTopicIds,
                    recentOutdoorTopicCount = recentOutdoorTopicCount
                )
            )
        }
        val totalWeight = weightedTopics.sumOf { it.weight }
        if (totalWeight <= 0) {
            val fallbackTopic = freshPool.maxBy { it.baseWeight }
            return RecommendationTopicSelection(
                topic = fallbackTopic,
                weight = fallbackTopic.baseWeight,
                candidateCount = freshPool.size,
                cooledTopicCount = timeFitPool.size - freshPool.size
            )
        }

        val fallbackWeightedTopic = pickWeightedTopic(weightedTopics)
        return RecommendationTopicSelection(
            topic = fallbackWeightedTopic.topic,
            weight = fallbackWeightedTopic.weight,
            candidateCount = weightedTopics.size,
            cooledTopicCount = timeFitPool.size - freshPool.size
        )
    }

    private fun topicWeight(
        topic: RecommendationTopic,
        hour: Int,
        weatherCondition: String,
        preferenceProfile: RecommendationPreferenceProfile?,
        recentlyUsed: Boolean,
        recentOutdoorTopicCount: Int
    ): Int {
        var weight = topic.baseWeight.coerceAtLeast(1)

        val profileScore = preferenceProfile?.scoreText(
            text = topic.scoringText(),
            fallbackCategory = topic.category
        ) ?: 0
        weight += profileScore * 2
        if (profileScore <= STRONG_NEGATIVE_PROFILE_SCORE) {
            weight /= 4
        }

        if (recentlyUsed) {
            weight /= RECENT_TOPIC_PENALTY_DIVISOR
        }

        if (topic.category == MEAL_CATEGORY) {
            weight += mealTimeBoost(topic.id, hour)
        } else {
            weight += playTimeBoost(topic.id, hour)
            weight += weatherBoost(topic, weatherCondition)
        }
        if (topic.classicRomance) {
            weight += CLASSIC_ROMANCE_BASE_BOOST
        }
        if (topic.campusDepth) {
            weight += CAMPUS_DEPTH_BASE_BOOST
        }
        if (topic.outdoorSensitive && recentOutdoorTopicCount > 0) {
            weight -= recentOutdoorTopicCount * OUTDOOR_STREAK_PENALTY
        }

        return weight.coerceIn(MIN_TOPIC_WEIGHT, MAX_TOPIC_WEIGHT)
    }

    private fun pickWeightedTopic(weightedTopics: List<WeightedTopic>): WeightedTopic {
        val totalWeight = weightedTopics.sumOf { it.weight.coerceAtLeast(0) }
        if (totalWeight <= 0) {
            return weightedTopics.maxBy { it.weight }
        }

        var cursor = random.nextInt(totalWeight)
        for (weightedTopic in weightedTopics) {
            cursor -= weightedTopic.weight.coerceAtLeast(0)
            if (cursor < 0) {
                return weightedTopic
            }
        }
        return weightedTopics.maxBy { it.weight }
    }

    private fun RecommendationTopic.scoringText(): String {
        return buildString {
            append(label)
            append(' ')
            append(promptHint)
            traits.forEach { trait ->
                append(' ')
                append(RecommendationTraitAnalyzer.labelFor(trait))
            }
        }
    }

    private fun weatherBoost(
        topic: RecommendationTopic,
        weatherCondition: String
    ): Int {
        val weatherText = weatherCondition.lowercase()
        val isRainy = weatherText.contains("雨") ||
            weatherText.contains("rain") ||
            weatherText.contains("降水")
        val temperature = Regex("(-?\\d+(?:\\.\\d+)?)")
            .find(weatherText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
        val isHot = temperature?.let { it >= 30.0 } == true || weatherText.contains("热")
        val isCold = temperature?.let { it <= 12.0 } == true || weatherText.contains("冷")
        val isComfortable = !isRainy && !isHot && !isCold &&
            (weatherText.contains("晴") || weatherText.contains("多云") || weatherText.contains("cloud"))

        return when {
            topic.outdoorSensitive && (isRainy || isHot || isCold) -> -8
            !topic.outdoorSensitive && (isRainy || isHot || isCold) -> 5
            topic.outdoorSensitive && isComfortable -> 4
            else -> 0
        }
    }

    private fun playTimeBoost(topicId: String, hour: Int): Int {
        return when (hour) {
            in 5..10 -> when (topicId) {
                "craft_workshop",
                "pottery_diy",
                "tufting_diy",
                "silver_fragrance_diy",
                "baking_diy",
                "vintage_photo",
                "art_exhibition",
                "small_gallery",
                "pet_cafe" -> 5
                "retro_arcade",
                "boardgame_escape",
                "indoor_sport" -> 2
                "city_viewpoint",
                "riverside_walk",
                "lakeside_walk",
                "small_park_corner",
                "garden_plants" -> 1
                else -> 0
            }
            in 11..15 -> when (topicId) {
                "indoor_sport",
                "retro_arcade",
                "boardgame_escape",
                "art_exhibition",
                "small_gallery",
                "creative_block_shop",
                "lifestyle_boutique" -> 4
                else -> 1
            }
            in 16..19 -> when (topicId) {
                "craft_workshop",
                "pottery_diy",
                "silver_fragrance_diy",
                "retro_arcade",
                "boardgame_escape",
                "indoor_sport",
                "art_exhibition",
                "small_gallery",
                "vintage_photo",
                "vintage_record_shop",
                "lifestyle_boutique",
                "creative_block_shop",
                "pet_cafe" -> 5
                "riverside_walk",
                "lakeside_walk",
                "small_park_corner",
                "city_viewpoint" -> -4
                "historic_architecture",
                "old_street_corner",
                "public_art" -> 3
                else -> 1
            }
            in 20..23 -> when (topicId) {
                "retro_arcade",
                "boardgame_escape",
                "indoor_sport",
                "talkshow_small_theater" -> 5
                "riverside_walk",
                "city_viewpoint" -> 2
                else -> -2
            }
            else -> when (topicId) {
                "retro_arcade",
                "boardgame_escape",
                "indoor_sport" -> 2
                else -> -5
            }
        }
    }

    private fun mealTimeBoost(topicId: String, hour: Int): Int {
        return when (hour) {
            in 5..10 -> when (topicId) {
                "local_snack",
                "dessert_coffee",
                "light_food",
                "western_brunch" -> 6
                "hotpot_bbq",
                "bistro_bar",
                "late_supper" -> -7
                else -> 0
            }
            in 11..14 -> when (topicId) {
                "foreign_food",
                "japanese_korean",
                "western_brunch",
                "claypot_homecooking",
                "local_snack" -> 5
                "student_friendly_chain" -> -4
                else -> 1
            }
            in 15..17 -> when (topicId) {
                "dessert_coffee",
                "light_food",
                "foreign_food" -> 5
                "local_snack",
                "snack_street",
                "hotpot_bbq" -> -4
                else -> 0
            }
            in 18..21 -> when (topicId) {
                "hotpot_bbq",
                "japanese_korean",
                "foreign_food",
                "bistro_bar",
                "claypot_homecooking" -> 6
                "student_friendly_chain" -> -3
                else -> 1
            }
            else -> when (topicId) {
                "late_supper",
                "hotpot_bbq",
                "local_snack" -> 6
                "student_friendly_chain" -> -2
                else -> -3
            }
        }
    }

    private data class WeightedTopic(
        val topic: RecommendationTopic,
        val weight: Int
    )

    companion object {
        private const val MEAL_CATEGORY = "meal"
        private const val MIN_TOPIC_WEIGHT = 1
        private const val MAX_TOPIC_WEIGHT = 40
        private const val RECENT_TOPIC_PENALTY_DIVISOR = 4
        private const val STRONG_NEGATIVE_PROFILE_SCORE = -7
        private const val SURPRISE_BOX_RATE = 0.15
        private const val SURPRISE_BOX_WEIGHT_BONUS = 8
        private const val CLASSIC_ROMANCE_BASE_BOOST = 3
        private const val CAMPUS_DEPTH_BASE_BOOST = 4
        private const val RECENT_OUTDOOR_TOPIC_WINDOW = 2
        private const val OUTDOOR_STREAK_PENALTY = 6

        private val nightOnlyHours = (5..16).toSet()
        private val eveningOnlyHours = (5..15).toSet()

        val playTopics = listOf(
            RecommendationTopic(
                id = "craft_workshop",
                category = "play",
                label = "手作坊",
                promptHint = "找具体手作体验店，如陶艺、银饰、香薰、皮具、木作、流体熊或奶油胶，必须是可预约/可到店体验的真实店。",
                baseWeight = 18,
                traits = setOf("craft"),
                surpriseBox = true
            ),
            RecommendationTopic(
                id = "pottery_diy",
                category = "play",
                label = "陶艺DIY",
                promptHint = "推荐陶艺拉坯、捏塑或釉下彩体验店，优先独立工作室，不要泛泛写创意园。",
                baseWeight = 16,
                traits = setOf("craft"),
                surpriseBox = true
            ),
            RecommendationTopic(
                id = "tufting_diy",
                category = "play",
                label = "Tufting簇绒",
                promptHint = "推荐武汉真实簇绒/地毯DIY工作室，店名要具体，适合情侣一起做成品。",
                baseWeight = 15,
                traits = setOf("craft")
            ),
            RecommendationTopic(
                id = "silver_fragrance_diy",
                category = "play",
                label = "银饰/香薰DIY",
                promptHint = "推荐银饰打制、香薰蜡烛、调香或香牌体验店，必须是具体店名。",
                baseWeight = 15,
                traits = setOf("craft", "small_shop")
            ),
            RecommendationTopic(
                id = "baking_diy",
                category = "play",
                label = "烘焙DIY",
                promptHint = "推荐蛋糕、曲奇、巧克力或甜品烘焙DIY店，重点是体验，不要只推荐普通餐饮。",
                baseWeight = 12,
                traits = setOf("craft", "cafe")
            ),
            RecommendationTopic(
                id = "immersive_theater",
                category = "play",
                label = "沉浸式话剧",
                promptHint = "推荐沉浸式戏剧、小剧场或白天可看的演出空间；若当前时间太早，选择可预约/有日场的场馆。",
                disallowHours = nightOnlyHours,
                baseWeight = 8,
                traits = setOf("live"),
                surpriseBox = true
            ),
            RecommendationTopic(
                id = "talkshow_small_theater",
                category = "play",
                label = "脱口秀/小剧场",
                promptHint = "推荐脱口秀、小剧场、即兴喜剧或独立演出空间，必须是具体场馆，不要夜店。",
                baseWeight = 9,
                traits = setOf("live"),
                surpriseBox = true
            ),
            RecommendationTopic(
                id = "pet_cafe",
                category = "play",
                label = "猫咖狗咖",
                promptHint = "推荐猫咖、狗咖、宠物互动馆或小动物互动空间，必须是真实店名，避免普通咖啡馆。",
                baseWeight = 14,
                traits = setOf("pet_cafe"),
                surpriseBox = true
            ),
            RecommendationTopic(
                id = "indoor_sport",
                category = "play",
                label = "室内运动/射箭",
                promptHint = "推荐射箭馆、攀岩馆、保龄球、台球、滑冰、VR或室内运动体验馆，店名要具体。",
                baseWeight = 13,
                traits = setOf("arcade"),
                classicRomance = true,
                surpriseBox = true
            ),
            RecommendationTopic(
                id = "retro_arcade",
                category = "play",
                label = "街机电玩城",
                promptHint = "推荐复古街机、电玩城、主机游戏体验、VR或互动娱乐店，避免只写大型商场。",
                baseWeight = 14,
                traits = setOf("arcade"),
                classicRomance = true
            ),
            RecommendationTopic(
                id = "boardgame_escape",
                category = "play",
                label = "桌游/密室",
                promptHint = "推荐桌游店、密室、剧本娱乐或轻推理体验馆，优先适合两个人的小店。",
                baseWeight = 11,
                traits = setOf("arcade"),
                classicRomance = true,
                surpriseBox = true
            ),
            RecommendationTopic(
                id = "art_exhibition",
                category = "play",
                label = "艺术展",
                promptHint = "推荐当前适合看的小众展览、艺术空间、画廊或摄影展，必须是具体展馆/空间。",
                baseWeight = 13,
                traits = setOf("gallery"),
                surpriseBox = true
            ),
            RecommendationTopic(
                id = "small_gallery",
                category = "play",
                label = "小众画廊",
                promptHint = "推荐独立画廊、艺术中心、小型展厅或设计空间，避免省博这类默认答案。",
                baseWeight = 12,
                traits = setOf("gallery")
            ),
            RecommendationTopic(
                id = "vintage_photo",
                category = "play",
                label = "复古照相馆",
                promptHint = "推荐复古照相馆、拍立得写真馆、大头贴或胶片写真店，必须可搜索。",
                baseWeight = 13,
                traits = setOf("photo_studio"),
                classicRomance = true,
                surpriseBox = true
            ),
            RecommendationTopic(
                id = "vintage_record_shop",
                category = "play",
                label = "中古店/唱片店",
                promptHint = "推荐中古店、唱片店、复古玩具店、胶片店或黑胶空间，店名要具体。",
                baseWeight = 13,
                traits = setOf("small_shop"),
                surpriseBox = true
            ),
            RecommendationTopic(
                id = "toy_blindbox_shop",
                category = "play",
                label = "潮玩/盲盒店",
                promptHint = "推荐潮玩、谷子店、盲盒、模型、玩具或二次元周边店，必须是具体店铺。",
                baseWeight = 12,
                traits = setOf("small_shop"),
                surpriseBox = true
            ),
            RecommendationTopic(
                id = "lifestyle_boutique",
                category = "play",
                label = "买手店/生活方式店",
                promptHint = "推荐买手店、生活方式集合店、香水体验店、植物店或文创小店，必须有逛逛或体验价值。",
                baseWeight = 12,
                traits = setOf("small_shop"),
                surpriseBox = true
            ),
            RecommendationTopic(
                id = "creative_block_shop",
                category = "play",
                label = "创意园区里的具体小店",
                promptHint = "可以在创意园区里找，但必须推荐具体店铺、工作室或展览空间，不要只返回园区名。",
                baseWeight = 9,
                traits = setOf("small_shop", "gallery"),
                surpriseBox = true,
                campusDepth = true
            ),
            RecommendationTopic(
                id = "city_viewpoint",
                category = "play",
                label = "小众城市观景点",
                promptHint = "推荐具体观景平台、天台、桥梁视角或城市建筑点，不要泛泛写东湖/江滩。",
                baseWeight = 7,
                traits = setOf("riverside"),
                outdoorSensitive = true,
                classicRomance = true
            ),
            RecommendationTopic(
                id = "riverside_walk",
                category = "play",
                label = "江边小众散步点",
                promptHint = "推荐具体江滩段、码头、桥下空间或江边入口，必须写清具体地点。",
                baseWeight = 8,
                traits = setOf("riverside"),
                outdoorSensitive = true,
                classicRomance = true
            ),
            RecommendationTopic(
                id = "lakeside_walk",
                category = "play",
                label = "湖边轻散步点",
                promptHint = "推荐具体湖边入口、栈道、绿道支线或小众湖边点，不要只写东湖。",
                baseWeight = 7,
                traits = setOf("riverside", "park"),
                outdoorSensitive = true,
                classicRomance = true
            ),
            RecommendationTopic(
                id = "historic_architecture",
                category = "play",
                label = "老租界建筑街区",
                promptHint = "推荐具体历史建筑、老租界街角、名人故居或近代建筑点，适合短暂停留拍照。",
                baseWeight = 9,
                traits = setOf("historic"),
                outdoorSensitive = true
            ),
            RecommendationTopic(
                id = "small_park_corner",
                category = "play",
                label = "小众公园角落",
                promptHint = "推荐高德可搜到的正式公园、花园、广场或公园内正式景点；不要编校园后侧小花园、后街樱花林、入口角落这类非官方地点。",
                disallowHours = eveningOnlyHours,
                baseWeight = 6,
                traits = setOf("park"),
                outdoorSensitive = true
            ),
            RecommendationTopic(
                id = "garden_plants",
                category = "play",
                label = "植物园/花园",
                promptHint = "推荐花园、植物园、花市、植物店或季节性花景点，地点必须具体。",
                disallowHours = eveningOnlyHours,
                baseWeight = 8,
                traits = setOf("park", "small_shop"),
                outdoorSensitive = true
            ),
            RecommendationTopic(
                id = "old_street_corner",
                category = "play",
                label = "文艺老街巷",
                promptHint = "推荐具体老街巷里的小店、建筑、咖啡馆或拍照点，不要只写昙华林。",
                baseWeight = 8,
                traits = setOf("historic", "small_shop"),
                outdoorSensitive = true,
                campusDepth = true
            ),
            RecommendationTopic(
                id = "public_art",
                category = "play",
                label = "城市雕塑/公共艺术点",
                promptHint = "推荐城市雕塑、公共艺术装置、设计街角或可拍照的建筑外立面，必须具体。",
                baseWeight = 7,
                traits = setOf("gallery", "historic"),
                outdoorSensitive = true
            ),
            RecommendationTopic(
                id = "non_hot_museum",
                category = "play",
                label = "非热门博物馆/纪念馆",
                promptHint = "推荐非省博的小型博物馆、纪念馆、名人故居或专题展馆，地点要具体。",
                baseWeight = 7,
                traits = setOf("museum")
            ),
            RecommendationTopic(
                id = "temple_heritage",
                category = "play",
                label = "寺庙/古迹",
                promptHint = "推荐具体寺庙、古迹或历史遗址，适合安静散步和短暂停留。",
                baseWeight = 5,
                traits = setOf("historic"),
                outdoorSensitive = true
            )
        )

        val mealTopics = listOf(
            RecommendationTopic(
                id = "foreign_food",
                category = "meal",
                label = "异国料理",
                promptHint = "推荐真实具体的异国料理店，如泰餐、越南粉、墨西哥菜、中东菜或东南亚菜，优先不远。",
                surpriseBox = true
            ),
            RecommendationTopic(
                id = "japanese_korean",
                category = "meal",
                label = "日料/韩餐",
                promptHint = "推荐日料、寿司、拉面、烧鸟、韩餐、烤肉或部队锅，必须是具体分店。",
                classicRomance = true
            ),
            RecommendationTopic(
                id = "western_brunch",
                category = "meal",
                label = "西餐/Brunch",
                promptHint = "推荐西餐、Brunch、汉堡、披萨、意面或小酒馆午餐，优先适合两个人坐下。",
                classicRomance = true
            ),
            RecommendationTopic(
                id = "bistro_bar",
                category = "meal",
                label = "小酒馆/Bistro",
                promptHint = "推荐 Bistro、小酒馆、清吧餐食或精酿餐吧；上午不要推荐夜间才营业的店。",
                disallowHours = eveningOnlyHours
            ),
            RecommendationTopic(
                id = "hotpot_bbq",
                category = "meal",
                label = "火锅/烤肉",
                promptHint = "推荐近一点的火锅、烤肉、烧烤或锅物店，不要太远，不要只写商圈。",
                classicRomance = true
            ),
            RecommendationTopic(
                id = "dessert_coffee",
                category = "meal",
                label = "咖啡甜品",
                promptHint = "推荐咖啡、甜品、蛋糕、面包烘焙或新中式茶饮，优先有特色但不太远的店。",
                classicRomance = true
            ),
            RecommendationTopic(
                id = "local_snack",
                category = "meal",
                label = "武汉本地小吃",
                promptHint = "推荐具体武汉小吃店，如热干面、豆皮、汤包、牛肉粉、烧麦，但要近且真实。",
                campusDepth = true
            ),
            RecommendationTopic(
                id = "snack_street",
                category = "meal",
                label = "特色小吃街",
                promptHint = "可以推荐小吃街里的具体摊位/店铺，不要只返回整条街或泛泛区域。",
                campusDepth = true
            ),
            RecommendationTopic(
                id = "late_supper",
                category = "meal",
                label = "宵夜烧烤",
                promptHint = "推荐夜宵、烧烤、砂锅、精酿或深夜食堂；非夜间时优先选择全天营业店。",
                disallowHours = (5..16).toSet()
            ),
            RecommendationTopic(
                id = "claypot_homecooking",
                category = "meal",
                label = "砂锅/私房菜",
                promptHint = "推荐砂锅、煲仔饭、私房菜、家常菜或舒服小馆，重点是通勤别折腾。",
                campusDepth = true
            ),
            RecommendationTopic(
                id = "student_friendly_chain",
                category = "meal",
                label = "学生友好连锁",
                promptHint = "推荐老乡鸡、尊宝比萨、袁记云饺、塔斯汀等学生友好、真实可达、不容易踩雷的连锁或轻松小店，优先湖大-武昌万象城、徐东、街道口。",
                baseWeight = 3,
                traits = setOf("student_chain"),
                campusDepth = true
            ),
            RecommendationTopic(
                id = "light_food",
                category = "meal",
                label = "轻食/茶馆",
                promptHint = "推荐轻食沙拉、茶馆、新中式茶饮或安静好坐的小店。"
            )
        )
    }
}
