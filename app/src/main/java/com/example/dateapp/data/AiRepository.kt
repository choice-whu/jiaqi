package com.example.dateapp.data

import com.example.dateapp.data.environment.DecisionEnvironmentSnapshot
import com.example.dateapp.data.remote.AiChatClient
import com.example.dateapp.data.remote.AiChatRequest
import com.example.dateapp.data.remote.AiApiService
import com.example.dateapp.data.remote.AiDecisionRecommendation
import com.example.dateapp.data.remote.AiDecisionRecommendationDto
import com.example.dateapp.data.remote.AiDecisionRecommendationsDto
import com.example.dateapp.data.remote.AiJsonSanitizer.getStringOrNull
import com.example.dateapp.data.remote.AiNetworkModule
import com.example.dateapp.data.remote.ParsedWish
import com.example.dateapp.data.remote.ParsedWishDto
import com.example.dateapp.data.recommendation.RecommendationPreferenceProfile
import com.example.dateapp.data.recommendation.RecommendationTopic
import com.example.dateapp.data.route.DecisionPoiCandidate
import com.google.gson.Gson
import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class AiPoiChoice(
    val index: Int,
    val tag: String?,
    val intro: String?,
    val reason: String?
)

class AiRepository(
    private val apiService: AiApiService,
    private val gson: Gson = Gson(),
    private val model: String = AiNetworkModule.defaultModel,
    private val decisionModel: String = AiNetworkModule.decisionModel,
    private val chatClient: AiChatClient = AiChatClient(apiService)
) {

    suspend fun parseWishIntent(rawText: String): Result<ParsedWish> {
        val normalizedText = rawText.trim()
        if (normalizedText.isEmpty()) {
            return Result.failure(IllegalArgumentException("rawText is empty"))
        }

        return withContext(Dispatchers.IO) {
            runAiCatching {
                val json = requestJsonContent(
                    systemPrompt = buildWishIntentSystemPrompt(),
                    prompt = buildWishIntentPrompt(normalizedText),
                    maxCompletionTokens = 96,
                    reasoningEffort = "low",
                    temperature = 0.05,
                    presencePenalty = null
                )
                val dto = gson.fromJson(json, ParsedWishDto::class.java)
                    ?: error("AI response JSON could not be parsed")
                dto.toParsedWish()
            }
        }
    }

    suspend fun recommendDecision(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String,
        strictTimeMatch: Boolean = false,
        avoidNames: List<String> = emptyList(),
        nearbyMallName: String? = null,
        preferenceProfile: RecommendationPreferenceProfile? = null,
        recommendationTopic: RecommendationTopic? = null,
        fastMode: Boolean = true,
        rescueMode: Boolean = false
    ): Result<AiDecisionRecommendation> {
        return withContext(Dispatchers.IO) {
            runAiCatching {
                val json = requestJsonContent(
                    systemPrompt = buildDecisionSystemPrompt(
                        targetCategory = targetCategory,
                        candidateBatch = false
                    ),
                    prompt = if (rescueMode) {
                        buildRescueDecisionPromptV2(
                            environment = environment,
                            targetCategory = targetCategory,
                            avoidNames = avoidNames,
                            preferenceProfile = preferenceProfile,
                            recommendationTopic = recommendationTopic
                        )
                    } else if (fastMode) {
                        buildFastDecisionPromptV2(
                            environment = environment,
                            targetCategory = targetCategory,
                            strictTimeMatch = strictTimeMatch,
                            avoidNames = avoidNames,
                            nearbyMallName = nearbyMallName,
                            preferenceProfile = preferenceProfile,
                            recommendationTopic = recommendationTopic
                        )
                    } else {
                        buildDecisionPromptV2(
                            environment = environment,
                            targetCategory = targetCategory,
                            strictTimeMatch = strictTimeMatch,
                            avoidNames = avoidNames,
                            nearbyMallName = nearbyMallName,
                            preferenceProfile = preferenceProfile,
                            recommendationTopic = recommendationTopic
                        )
                    },
                    maxCompletionTokens = when {
                        rescueMode -> 70
                        fastMode -> 160
                        else -> 150
                    },
                    reasoningEffort = "low",
                    overrideModel = decisionModel,
                    temperature = decisionTemperature(targetCategory, candidateBatch = false),
                    presencePenalty = if (fastMode) null else decisionPresencePenalty(targetCategory)
                )
                val dto = gson.fromJson(json, AiDecisionRecommendationDto::class.java)
                    ?: error("AI recommendation JSON could not be parsed")
                dto.toRecommendation().also { recommendation ->
                    Log.d(TAG, "ai source=single_success name=${recommendation.name} tag=${recommendation.tag}")
                }
            }
        }
    }

    suspend fun recommendDecisionCandidates(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String,
        strictTimeMatch: Boolean = false,
        avoidNames: List<String> = emptyList(),
        nearbyMallName: String? = null,
        preferenceProfile: RecommendationPreferenceProfile? = null,
        recommendationTopic: RecommendationTopic? = null,
        fastMode: Boolean = true,
        rescueMode: Boolean = false
    ): Result<List<AiDecisionRecommendation>> {
        return withContext(Dispatchers.IO) {
            runAiCatching {
                val json = requestJsonContent(
                    systemPrompt = buildDecisionSystemPrompt(
                        targetCategory = targetCategory,
                        candidateBatch = true
                    ),
                    prompt = if (rescueMode) {
                        buildRescueDecisionCandidatesPromptV2(
                            environment = environment,
                            targetCategory = targetCategory,
                            avoidNames = avoidNames,
                            preferenceProfile = preferenceProfile,
                            recommendationTopic = recommendationTopic
                        )
                    } else if (fastMode) {
                        buildFastDecisionCandidatesPromptV2(
                            environment = environment,
                            targetCategory = targetCategory,
                            strictTimeMatch = strictTimeMatch,
                            avoidNames = avoidNames,
                            nearbyMallName = nearbyMallName,
                            preferenceProfile = preferenceProfile,
                            recommendationTopic = recommendationTopic
                        )
                    } else {
                        buildDecisionCandidatesPromptV2(
                            environment = environment,
                            targetCategory = targetCategory,
                            strictTimeMatch = strictTimeMatch,
                            avoidNames = avoidNames,
                            nearbyMallName = nearbyMallName,
                            preferenceProfile = preferenceProfile,
                            recommendationTopic = recommendationTopic
                        )
                    },
                    maxCompletionTokens = when {
                        rescueMode -> 260
                        fastMode -> 120
                        else -> 400
                    },
                    reasoningEffort = "low",
                    overrideModel = decisionModel,
                    temperature = decisionTemperature(targetCategory, candidateBatch = true),
                    presencePenalty = decisionPresencePenalty(targetCategory)
                )
                parseDecisionCandidates(json).also { recommendations ->
                    Log.d(
                        TAG,
                        "ai source=candidates_success count=${recommendations.size} names=${recommendations.joinToString(limit = 6) { it.name }}"
                    )
                }
            }
        }
    }

    suspend fun polishDecisionPoi(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String,
        poi: DecisionPoiCandidate,
        recommendationTopic: RecommendationTopic? = null,
        preferenceProfile: RecommendationPreferenceProfile? = null
    ): Result<AiDecisionRecommendation> {
        return withContext(Dispatchers.IO) {
            runAiCatching {
                val json = requestJsonContent(
                    systemPrompt = buildPoiPolishSystemPrompt(),
                    prompt = buildPoiPolishPrompt(
                        environment = environment,
                        targetCategory = targetCategory,
                        poi = poi,
                        recommendationTopic = recommendationTopic,
                        preferenceProfile = preferenceProfile
                    ),
                    maxCompletionTokens = 70,
                    reasoningEffort = "low",
                    overrideModel = decisionModel,
                    temperature = 0.32,
                    presencePenalty = null
                )
                val dto = gson.fromJson(json, AiPoiPolishDto::class.java)
                    ?: error("AI POI polish JSON could not be parsed")
                AiDecisionRecommendation(
                    name = poi.displayName,
                    amapSearchKeyword = poi.routeKeyword,
                    imageUrl = null,
                    distanceDescription = poi.distanceLabel,
                    tag = cleanRecommendationTag(dto.tag) ?: poi.tag,
                    intro = cleanPoiIntro(dto.intro)
                ).also { recommendation ->
                    Log.d(
                        TAG,
                        "ai source=poi_polish_success name=${recommendation.name} tag=${recommendation.tag}"
                    )
                }
            }
        }
    }

    suspend fun chooseDecisionPoiFromCandidates(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String,
        candidates: List<DecisionPoiCandidate>,
        recommendationTopic: RecommendationTopic? = null,
        preferenceProfile: RecommendationPreferenceProfile? = null,
        avoidNames: List<String> = emptyList()
    ): Result<AiPoiChoice> {
        val boundedCandidates = candidates.take(AI_CHOICE_CANDIDATE_LIMIT)
        if (boundedCandidates.isEmpty()) {
            return Result.failure(IllegalArgumentException("candidate list is empty"))
        }

        return withContext(Dispatchers.IO) {
            runAiCatching {
                val json = requestJsonContent(
                    systemPrompt = buildPoiChoiceSystemPrompt(),
                    prompt = buildPoiChoicePrompt(
                        environment = environment,
                        targetCategory = targetCategory,
                        candidates = boundedCandidates,
                        recommendationTopic = recommendationTopic,
                        preferenceProfile = preferenceProfile,
                        avoidNames = avoidNames
                    ),
                    maxCompletionTokens = 96,
                    reasoningEffort = "low",
                    overrideModel = decisionModel,
                    temperature = if (targetCategory == "play") 0.45 else 0.28,
                    presencePenalty = null
                )
                parsePoiChoice(json, boundedCandidates).also { choice ->
                    val selectedName = boundedCandidates.getOrNull(choice.index)?.displayName.orEmpty()
                    Log.d(
                        TAG,
                        "ai source=poi_choice_success index=${choice.index} name=$selectedName tag=${choice.tag} reason=${choice.reason.orEmpty().take(60)}"
                    )
                }
            }
        }
    }

    private inline fun <T> runAiCatching(block: () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            Log.d(
                TAG,
                "ai source=request_failed type=${throwable.javaClass.simpleName} message=${throwable.message}"
            )
            Result.failure(throwable)
        }
    }

    private fun buildWishIntentPrompt(rawText: String): String {
        return "你是一个精密的文本解析器。" +
            "请从以下非结构化文本中提取用户的约会意图。" +
            "你必须严格且仅返回合法的 JSON 格式数据。" +
            "必须字段包括：'title'(动作核心短语)、" +
            "'category'(必须且只能是 'meal' 或 'play')、" +
            "'location_keyword'(提取的地名)。" +
            "待解析文本：[$rawText]"
    }

    private fun buildWishIntentSystemPrompt(): String {
        return "You extract dating wish intents. Output strictly valid JSON: {\"title\":\"\",\"category\":\"meal|play\",\"location_keyword\":\"\"}"
    }

    private fun buildDecisionSystemPrompt(
        targetCategory: String,
        candidateBatch: Boolean
    ): String {
        val schema = if (candidateBatch) {
            "{\"candidates\":[{\"display_name\":\"\",\"amap_search_keyword\":\"\",\"distance_desc\":\"\",\"tag\":\"\",\"intro\":\"\"}]}"
        } else {
            "{\"display_name\":\"\",\"amap_search_keyword\":\"\",\"distance_desc\":\"\",\"tag\":\"\",\"intro\":\"\"}"
        }
        return if (targetCategory == "play") {
            "You are a local ${WuhanKnowledgeConfig.CITY} explorer finding niche, real, non-touristy dating spots. Output strictly valid JSON: $schema"
        } else {
            "You are a local ${WuhanKnowledgeConfig.CITY} food scout finding nearby real restaurants, cafes, desserts, and supper spots. Output strictly valid JSON: $schema"
        }
    }

    private fun buildPoiPolishSystemPrompt(): String {
        return "You write concise warm Chinese copy for a real AMap POI. Do not rename the place. Output strictly valid JSON: {\"tag\":\"\",\"intro\":\"\"}"
    }

    private fun buildPoiChoiceSystemPrompt(): String {
        return "You are Jiaqi's personal date recommendation brain. You must choose from verified AMap POI candidates only. Never invent, rename, or output a place outside the list. Output strictly valid JSON: {\"index\":0,\"tag\":\"\",\"intro\":\"\",\"reason\":\"\"}"
    }

    private fun buildPoiChoicePrompt(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String,
        candidates: List<DecisionPoiCandidate>,
        recommendationTopic: RecommendationTopic?,
        preferenceProfile: RecommendationPreferenceProfile?,
        avoidNames: List<String>
    ): String {
        val categoryLabel = if (targetCategory == "meal") "meal" else "play"
        val topicLine = recommendationTopic
            ?.let { "Current exploration theme: ${it.label}. ${it.compactHint}" }
            ?: "Current exploration theme: flexible."
        val preferenceLine = preferenceProfile
            ?.promptHint()
            ?.takeIf { it.isNotBlank() }
            ?.let { "Personal preference profile: $it" }
            ?: "Personal preference profile: still learning, favor variety."
        val avoidLine = avoidNames
            .distinct()
            .takeLast(12)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "Avoid repeating or imitating: ", separator = " | ")
            ?: "Avoid repeating or imitating: none."
        val studentCoupleProfile = buildStudentCoupleProfilePrompt()
        val candidateLines = candidates.mapIndexed { index, poi ->
            val openLabel = when (poi.isOpenNow) {
                true -> "open_now"
                false -> "maybe_closed"
                null -> "opening_unknown"
            }
            "$index. name=${poi.displayName}; type=${poi.typeDescription.orEmpty()}; tag=${poi.tag.orEmpty()}; address=${poi.address.orEmpty()}; distance=${poi.distanceLabel}; opening=$openLabel ${poi.openingHours.orEmpty()}"
        }.joinToString("\n")

        return """
Task: choose the single best real POI for a ${WuhanKnowledgeConfig.CITY} university couple right now.
$studentCoupleProfile
Category: $categoryLabel
Time: ${environment.currentTimeLabel}
Weather: ${environment.weatherCondition}
User location: ${environment.userLocationLabel} (${String.format(Locale.US, "%.4f", environment.latitude)}, ${String.format(Locale.US, "%.4f", environment.longitude)})
$topicLine
$preferenceLine
$avoidLine

Verified AMap candidates:
$candidateLines

Rules:
- Pick one candidate by index only. The index must exist in the list.
- The list is already roughly sorted by local quality from stronger to weaker. Only choose a lower-ranked candidate when it is clearly more personal, fresh, and date-worthy.
- Do not output any new place name.
- Prefer the candidate that best matches time, weather, distance, topic, and personal preference.
- If two candidates are similar, pick the fresher and less recently repeated feeling one.
- Public transport matters a lot. Prefer metro-accessible areas around WHU/HUBU, Jiedaokou, Guangbutun, Xudong, Shahu, Chuhehanjie, Optics Valley, and Wuchang MIXC. Avoid remote suburbs unless it is a genuinely classic long-trip scenic destination. (City: ${WuhanKnowledgeConfig.CITY})
- For meals, prefer student-premium: nearby, tasty, relaxed, concrete restaurants. Avoid stiff luxury traps.
- For play, balance classic romance and surprise: photobooths, cinema/KTV, arcades, scenic walks, niche exhibitions, DIY workshops, pet cafes, pop-up markets, trendy interactive shops, small owner-run stores, or worthwhile scenic spots.
- If candidate quality is close, prefer hidden gems around WHU/HUBU core circles over generic famous defaults. Avoid ${WuhanKnowledgeConfig.aiAvoidDefaultSignals.take(5).joinToString("/")}/etc.
- tag: 2 to 4 Simplified Chinese characters.
- intro: 8 to 18 Simplified Chinese characters. Describe this place itself. Do not mention AI, weather, time, location, route, "real/verified", or "date suitable".
- reason: short internal reason in Chinese, <= 24 characters.
Return JSON only.
""".trimIndent()
    }

    private fun buildStudentCoupleProfilePrompt(): String {
        return """
User profile:
{"identity":"University Student Couple (WHU & HUBU)","vibe":"fun, romantic, curious, surprise-loving","budget":"student-premium; relaxed trendy quality, not stiff luxury","mobility":"subway/bus/cycling; prefer WHU/HUBU, Jiedaokou, Guangbutun, Xudong, Shahu, Chuhehanjie, Optics Valley, Wuchang MIXC","likes":"photobooths, cinema, KTV, arcades, scenic walks, DIY, pet cafes, pop-ups, niche exhibitions, trendy small shops","avoid":"remote suburbs, low-value luxury traps, dull repetitive check-ins","city":"${WuhanKnowledgeConfig.CITY}"}
""".trimIndent()
    }

    private fun buildCompactCoupleProfileLine(): String {
        return "Profile: WHU+HUBU student couple; metro/bus; student-premium; likes romantic, playful, surprising real places; meals nearby, play farther only if worth it."
    }

    private fun buildPoiPolishPrompt(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String,
        poi: DecisionPoiCandidate,
        recommendationTopic: RecommendationTopic?,
        preferenceProfile: RecommendationPreferenceProfile?
    ): String {
        val categoryLabel = if (targetCategory == "meal") "餐饮" else "玩乐"
        val preferenceHint = preferenceProfile?.promptHint()
            ?.takeIf { it.isNotBlank() }
            ?.let { "User taste: $it\n" }
            .orEmpty()
        val topicHint = recommendationTopic
            ?.let { "Theme: ${it.label}. ${it.compactHint}\n" }
            .orEmpty()
        return """
真实高德 POI 已确定，不能改名，不能虚构分店。
地点名：${poi.displayName}
类型：${poi.typeDescription.orEmpty()}
地址：${poi.address.orEmpty()}
距离：${poi.distanceLabel}
当前：${environment.currentTimeLabel}，${environment.weatherCondition}
类别：$categoryLabel
$topicHint$preferenceHint
请只为这个真实地点写一个短标签和一句简明介绍。
要求：
- tag <= 4 个中文字符。
- intro 10 到 24 个中文字符，只写这个地点本身的特点。
- 不要写“根据天气/时间/位置/AI推荐/适合约会”。
- 不要输出地点名，不要输出 Markdown。
返回 JSON：{"tag":"","intro":""}
""".trimIndent()
    }

    private fun decisionTemperature(
        targetCategory: String,
        candidateBatch: Boolean
    ): Double {
        return when {
            candidateBatch && targetCategory == "play" -> 0.85
            candidateBatch -> 0.72
            targetCategory == "play" -> 0.72
            else -> 0.55
        }
    }

    private fun decisionPresencePenalty(targetCategory: String): Double {
        return if (targetCategory == "play") 0.6 else 0.35
    }

    private fun buildDecisionPrompt(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String
    ): String {
        val categoryLabel = if (targetCategory == "meal") "餐饮" else "游玩"
        return "你是一个极度理性的约会推荐引擎。" +
            "当前时间是[${environment.currentTimeLabel}]，" +
            "天气是[${environment.weatherCondition}]，" +
            "用户位于${WuhanKnowledgeConfig.CITY_FULL}[${environment.userLocationLabel} (${String.format(Locale.US, "%.4f", environment.latitude)}, ${String.format(Locale.US, "%.4f", environment.longitude)})]。" +
            "请结合当前时段氛围，为情侣推荐唯一一个最适合现在立刻前往的[$categoryLabel]目的地。" +
            "餐饮时段请明显偏向餐厅、咖啡馆、甜品店、小酒馆、烧烤、火锅等真实消费场所；" +
            "非饭点请明显偏向景点、公园、江滩、展览、书店、文创小店、商场里的有趣店铺、散步打卡点、livehouse 等轻松好去处。" +
            "如果目标是餐饮，不允许返回整条商圈、步行街或公园；如果目标是游玩，可以返回景点、公园、书店、展览、江滩、商场内具体店铺或明确名称的打卡点。" +
            "结果必须是一个可以真正前往的具体地点，不要返回模糊区域。" +
            "你只能返回一个 JSON 对象，不要返回数组、解释文字或 Markdown。" +
            "必须字段包括：" +
            "'display_name'(界面展示用的简短店名或地点名)、" +
            "'amap_search_keyword'(给高德地图搜索用的核心品牌名 + 地段关键词，如 巴厘龙虾 江汉路)、" +
            "'image_url'(相关且有效的网络图片链接)、" +
            "'distance_desc'(距离描述，如 约 1.2km)、" +
            "'tag'(一个很短的标签，如 高分 / 安静 / 夜景)、" +
            "'intro'(一句 18 到 40 字的简明介绍，只写地点本身特点，不要写“适合约会”“AI推荐”“根据时间天气”等说明)。"
    }

    private fun buildDecisionPromptV2(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String,
        strictTimeMatch: Boolean,
        avoidNames: List<String>,
        nearbyMallName: String?,
        preferenceProfile: RecommendationPreferenceProfile?,
        recommendationTopic: RecommendationTopic?
    ): String {
        val categoryLabel = if (targetCategory == "meal") "meal" else "play"
        val retryGuard = if (strictTimeMatch) {
            "Previous answer mismatched time. Be extra strict.\n"
        } else {
            ""
        }
        val avoidSection = if (avoidNames.isNotEmpty()) {
            avoidNames
                .distinct()
                .takeLast(24)
                .joinToString(
                    separator = "\n",
                    prefix = "Recent or disliked picks to avoid strictly. Do not choose the same place, same branch, or an alias of these places:\n"
                ) { "- $it" }
        } else {
            "Recent or disliked picks to avoid strictly: none."
        }
        val explorationSection = """
Exploration mode:
- Do not overfit to nearby shopping malls or the closest commercial complex, but do allow specific interesting mall shops.
- For play, actively diversify across Wuhan scenic spots, parks, riverside/lakeside places, museums, galleries, exhibitions, old streets, creative blocks, bookstores, theaters, livehouses, craft studios, markets, viewpoints, photogenic landmarks, lifestyle stores, buyer shops, toy/blind-box shops, record stores, vintage shops, dessert/cafe stops, and small owner-run shops.
- In rain/heat/cold, prefer indoor or sheltered places: malls with a concrete shop, bookstores, cinemas, galleries, studios, exhibitions, lifestyle shops, dessert/cafe stops, arcades, or sheltered creative blocks are all valid.
- A mall answer must be a concrete named store, floor/zone, cinema, arcade, exhibition, bookstore, dessert/cafe, or shop cluster, not just a vague mall district.
""".trimIndent()
        val personalizationSection = buildPersonalizationSection(preferenceProfile)
        val topicSection = buildTopicSection(recommendationTopic, targetCategory)
        return """
Wuhan couple date picker.
Time: ${environment.currentTimeLabel} Asia/Shanghai.
Weather: ${environment.weatherCondition}.
Start area: ${environment.userLocationLabel} (${String.format(Locale.US, "%.4f", environment.latitude)}, ${String.format(Locale.US, "%.4f", environment.longitude)}).
Need: one real specific $categoryLabel place they can go to right now.

$retryGuard
$avoidSection
$personalizationSection
$topicSection
$explorationSection

Weather fit:
${buildWeatherGuardrails(environment.weatherCondition, targetCategory)}

Time fit:
${buildTimeMatchGuardrails(environment.currentTime.hour, targetCategory)}

Rules:
- meal => restaurant / cafe / dessert / hotpot / bbq / bar / supper spot only.
- meal => never park / bookstore / riverside / scenic area / vague district.
- meal => must be close to the given coordinates; prefer within 1.5 km, absolute max 2.5 km. Never recommend a far branch or distant business district.
- meal => if a brand has multiple branches, pick the nearest branch around the coordinates; keep "display_name" clean and put brand + area/branch hints in "amap_search_keyword".
- meal => vary cuisine and mood; avoid repeating the same chain, mall, food street, or branch style from recent picks.
- play => mix scenic/cultural/fun/lifestyle places: attractions, parks, riverside, viewpoints, historic streets, museums, galleries, exhibitions, creative blocks, bookstores, cinema, livehouse, arcade, escape room, bowling, pool hall, climbing gym, pottery/handcraft studio, night market, photogenic landmark, mall-based fun shops, toy/blind-box shops, record stores, vintage shops, buyer shops, lifestyle stores, dessert/cafe stops, and owner-run small shops.
- play => never vague mall district or generic food street; if choosing a mall/shop, name the concrete store or exact mini destination.
- play => prefer a concrete destination; rotate place types and avoid the same commercial complex, same famous spots, or same shop style from recent picks.
- play => do not only recommend quiet walks; include more playful, interactive, date-like places when the hour and weather allow.
- if the request is play and weather is bad, choose indoor/sheltered places such as galleries, exhibitions, theaters, bookstores, studios, cinemas, arcades, malls with concrete fun shops, dessert/cafe stops, lifestyle shops, or sheltered creative blocks.
- if the weather and hour allow outdoor activity, outdoor places are welcome, but still rotate in small shops and commercial micro-destinations for variety.
- choose a less repetitive real Wuhan place when possible, not always the most obvious top 3 stores.
- no vague areas, no explanations, no markdown.

Return only one JSON object:
{"display_name":"","amap_search_keyword":"","image_url":"","distance_desc":"","tag":"","intro":""}
Use short natural Chinese for "tag" and "intro". "intro" only describes the place itself.
""".trimIndent()
    }

    private fun buildFastDecisionPromptV2(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String,
        strictTimeMatch: Boolean,
        avoidNames: List<String>,
        nearbyMallName: String?,
        preferenceProfile: RecommendationPreferenceProfile?,
        recommendationTopic: RecommendationTopic?
    ): String {
        val categoryLabel = if (targetCategory == "meal") "meal" else "play"
        val strictLine = if (strictTimeMatch) {
            "Strict retry: fix the previous mismatch. Time/weather/nearby fit must be exact."
        } else {
            "First pass: answer quickly with the best fitting real place."
        }
        val avoidLine = avoidNames
            .distinct()
            .takeLast(6)
            .joinToString(" | ")
            .ifBlank { "none" }
        val personalizationLine = buildUltraCompactPersonalizationLine(preferenceProfile)
        val topicLine = buildUltraCompactTopicLine(recommendationTopic, targetCategory)
        return """
Return JSON only: {"display_name":"","amap_search_keyword":"","image_url":"","distance_desc":"","tag":"","intro":""}
You are Jiaqi, a ${WuhanKnowledgeConfig.CITY} date-place picker. Recommend one real specific $categoryLabel place.
${buildCompactCoupleProfileLine()}
Now: ${environment.currentTimeLabel}; weather=${environment.weatherCondition}; area=${environment.userLocationLabel}; ll=${String.format(Locale.US, "%.3f", environment.latitude)},${String.format(Locale.US, "%.3f", environment.longitude)}.
$strictLine
Avoid exact repeats only: $avoidLine.
$personalizationLine
$topicLine
Time: ${buildUltraCompactTimeRule(environment.currentTime.hour, targetCategory)}
Rules: use an official real AMap-searchable POI name you are confident exists. Never invent generic names like "银饰DIY·湖大店", "手作坊·街道口店", "某某体验馆", "后侧小花园", "后街樱花林". display_name=clean UI name; amap_search_keyword=POI+area. meal=nearby concrete food/drink<=2.5km, no vague district. play=concrete date-worthy non-meal spot: DIY/studio/arcade/pet cafe/photo booth/gallery/lifestyle/vintage/toy/record shop/indie cafe, or one worthwhile scenic/cultural spot. Avoid ${WuhanKnowledgeConfig.aiAvoidDefaultSignals.joinToString("/")}/ordinary bookstore. If unsure about a niche shop, choose a real known exact POI instead. If mall, exact shop. intro<=14 Chinese chars.
Location rule: choose near the current coordinates first. Ordinary shops/cafes/arcades/studios should be nearby; only scenic parks, landmarks, riverside/lakeside places, museums/galleries, theaters, or truly special interactive experiences may be farther.
""".trimIndent()
    }

    private fun buildRescueDecisionPromptV2(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String,
        avoidNames: List<String>,
        preferenceProfile: RecommendationPreferenceProfile?,
        recommendationTopic: RecommendationTopic?
    ): String {
        val categoryLabel = if (targetCategory == "meal") "meal" else "play"
        val avoidLine = avoidNames
            .distinct()
            .takeLast(4)
            .joinToString(" | ")
            .ifBlank { "none" }
        val personalizationLine = buildUltraCompactPersonalizationLine(preferenceProfile)
        val topicLine = buildUltraCompactTopicLine(recommendationTopic, targetCategory)

        return """
JSON only: {"display_name":"","amap_search_keyword":"","distance_desc":"","tag":"","intro":""}
${WuhanKnowledgeConfig.CITY} $categoryLabel now. t=${environment.currentTime.hour} w=${environment.weatherCondition} ll=${String.format(Locale.US, "%.3f", environment.latitude)},${String.format(Locale.US, "%.3f", environment.longitude)}
        Do not return these invalid/recent names or aliases: $avoidLine
$personalizationLine
$topicLine
Time: ${buildUltraCompactTimeRule(environment.currentTime.hour, targetCategory)}
        display_name=clean UI name. amap_search_keyword=core name+area for AMap. meal=nearby food/drink<=2.5km. play=nearby hidden-gem non-food leisure; prefer DIY/pottery/tufting/baking/retro arcade/lifestyle/vintage/record/toy/craft/small gallery before parks/riverside/scenic walks unless outdoor is clearly the best fit; only scenic parks/landmarks/museums/galleries/theaters or truly special interactive places may be farther; never ${WuhanKnowledgeConfig.aiAvoidDefaultSignals.joinToString("/")}/ordinary bookstore; no breakfast/noodle/hotpot/bbq/restaurant-only; intro<=10 Chinese chars.
""".trimIndent()
    }

    private fun buildUltraCompactTimeRule(hour: Int, targetCategory: String): String {
        return when (hour) {
            in 5..10 -> if (targetCategory == "meal") {
                "morning open only; no sunset/bar/supper."
            } else {
                "morning open only; no sunset/bar/nightlife."
            }
            in 11..15 -> "open at midday; no sunset/nightlife."
            in 16..19 -> "open now; avoid day-only museums/temples/exhibitions if likely closed after 16:30."
            in 20..23 -> if (targetCategory == "meal") {
                "night open food/bar/supper only; no breakfast/day-only."
            } else {
                "night open only: riverside/night street/cinema/livehouse/bar/late arcade; no museum/exhibition/day-only."
            }
            else -> "late-night open only; no day-only."
        }
    }

    private fun buildDecisionCandidatesPromptV2(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String,
        strictTimeMatch: Boolean,
        avoidNames: List<String>,
        nearbyMallName: String?,
        preferenceProfile: RecommendationPreferenceProfile?,
        recommendationTopic: RecommendationTopic?
    ): String {
        val categoryLabel = if (targetCategory == "meal") "meal" else "play"
        val retryGuard = if (strictTimeMatch) {
            "Previous answer had invalid candidates. Be stricter about time, weather, distance, and repetition.\n"
        } else {
            ""
        }
        val avoidSection = if (avoidNames.isNotEmpty()) {
            avoidNames
                .distinct()
                .takeLast(12)
                .joinToString(
                    separator = "\n",
                    prefix = "Avoid exact recent/disliked place repeats only; do not ban whole place types:\n"
                ) { "- $it" }
        } else {
            "Avoid recent/disliked places: none."
        }
        val mallLine = nearbyMallName
            ?.takeIf { it.isNotBlank() }
            ?.let { "Nearby mall signal exists ($it). You may use a concrete interesting shop/arcade/cinema/bookstore/cafe inside it, but avoid vague mall-only answers." }
            ?: "No mall preference. Malls and shops are allowed only as concrete named mini-destinations."
        val personalizationSection = buildPersonalizationSection(preferenceProfile)
        val topicSection = buildTopicSection(recommendationTopic, targetCategory)

        return """
Return JSON only:
{"candidates":[{"display_name":"","amap_search_keyword":"","image_url":"","distance_desc":"","tag":"","intro":""}]}

Wuhan couple date picker. Give 4 diverse real specific $categoryLabel places usable right now.
${buildCompactCoupleProfileLine()}
Time: ${environment.currentTimeLabel} Asia/Shanghai.
Weather: ${environment.weatherCondition}.
Start area: ${environment.userLocationLabel} (${String.format(Locale.US, "%.4f", environment.latitude)}, ${String.format(Locale.US, "%.4f", environment.longitude)}).
$retryGuard
$avoidSection
$mallLine
$personalizationSection
$topicSection

Time rule: ${buildCompactTimeRule(environment.currentTime.hour, targetCategory)}
Weather rule: ${buildCompactWeatherRule(environment.weatherCondition, targetCategory)}

Rules:
- Candidates must be meaningfully different place types or neighborhoods.
- Each candidate needs display_name for UI and amap_search_keyword for AMap search. amap_search_keyword should be the core brand/POI + area/branch hint, not a sentence.
- meal => real nearby restaurant/cafe/dessert/hotpot/bbq/bar/supper only; prefer <=1.5km and max 2.5km; include nearest branch name when relevant.
- meal => no park, scenic area, vague district, broad food street, or far famous branch.
- play => first look near the current coordinates for specific micro-destinations: DIY workshops, pottery, tufting, baking studios, retro arcades, lifestyle boutiques, vintage/record/toy shops, small galleries, craft studios, owner-run small shops, and indie cafes with a special experience.
- play => ordinary shops, cafes, mall stores, arcades, and studios should be nearby; only scenic parks/landmarks/riverside/lakeside places, museums/galleries/theaters, or a truly special interactive experience may be farther.
- play => do not choose famous tourist defaults or ordinary bookstores: 昙华林, 湖北省博物馆, 东湖, 武汉大学万林艺术博物馆, 西西弗书店, 卓尔书店.
- For play candidate batches, at least 2 of 4 should be lifestyle/interactive/micro-destination options such as a named shop, bookstore, arcade, craft studio, dessert/cafe stop, record/vintage store, buyer shop, or mall mini-destination. At most 1 should be a classic park/museum/scenic landmark.
- Bad weather => choose indoor or sheltered cultural/fun/lifestyle places rather than open-air scenery.
- Comfortable weather => outdoor scenic/cultural places are welcome.
- No vague areas, no explanations, no markdown.
- "intro" <= 28 Chinese chars and only describes the place itself, not weather/time/location reasoning.
""".trimIndent()
    }

    private fun buildFastDecisionCandidatesPromptV2(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String,
        strictTimeMatch: Boolean,
        avoidNames: List<String>,
        nearbyMallName: String?,
        preferenceProfile: RecommendationPreferenceProfile?,
        recommendationTopic: RecommendationTopic?
    ): String {
        val categoryLabel = if (targetCategory == "meal") "meal" else "play"
        val strictLine = if (strictTimeMatch) {
            "Strict retry: every candidate must fit the current hour/weather/distance and avoid list."
        } else {
            "First pass: answer quickly with diverse strong candidates."
        }
        val avoidLine = avoidNames
            .distinct()
            .takeLast(8)
            .joinToString(" | ")
            .ifBlank { "none" }
        val mallLine = if (nearbyMallName.isNullOrBlank()) {
            "No mall preference; concrete shops/malls are allowed, but avoid vague mall-only answers."
        } else {
            "Nearby mall=$nearbyMallName; concrete interesting shops inside it are allowed, but avoid mall overfitting."
        }
        val personalizationLine = buildCompactPersonalizationLine(preferenceProfile)
        val topicLine = buildCompactTopicLine(recommendationTopic, targetCategory)

        return """
Return JSON only: {"candidates":[{"display_name":"","amap_search_keyword":"","image_url":"","distance_desc":"","tag":"","intro":""}]}
Give 2 diverse real Wuhan $categoryLabel places usable right now.
${buildCompactCoupleProfileLine()}
Context: time=${environment.currentTimeLabel} Asia/Shanghai; weather=${environment.weatherCondition}; area=${environment.userLocationLabel}; coords=${String.format(Locale.US, "%.4f", environment.latitude)},${String.format(Locale.US, "%.4f", environment.longitude)}.
$strictLine
Avoid exact recent/disliked repeats: $avoidLine.
$mallLine
$personalizationLine
$topicLine
Time rule: ${buildCompactTimeRule(environment.currentTime.hour, targetCategory)}
Weather rule: ${buildCompactWeatherRule(environment.weatherCondition, targetCategory)}
Rules: display_name=clean UI name. amap_search_keyword=core POI/brand + area/branch for AMap. meal=nearby concrete food/drink, prefer <=1.5km and max 2.5km. play=nearby concrete date-worthy non-meal spot: DIY/studio/arcade/pet cafe/photo booth/gallery/lifestyle/vintage/toy/record shop/indie cafe with experience, or one worthwhile scenic/cultural spot. Ordinary shops/cafes/arcades/studios should be near; only scenic parks/landmarks/museums/galleries/theaters or truly special interactive places may be farther. Never choose 昙华林/湖北省博物馆/东湖/万林/ordinary bookstores. If inside mall, name the exact shop. intro<=18 Chinese chars. No markdown, no explanation.
For play batches: at least 1 should be shop/lifestyle/interactive, not both scenic walks.
""".trimIndent()
    }

    private fun buildRescueDecisionCandidatesPromptV2(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String,
        avoidNames: List<String>,
        preferenceProfile: RecommendationPreferenceProfile?,
        recommendationTopic: RecommendationTopic?
    ): String {
        val categoryLabel = if (targetCategory == "meal") "meal" else "play"
        val avoidLine = avoidNames
            .distinct()
            .takeLast(8)
            .joinToString(" | ")
            .ifBlank { "none" }
        val personalizationLine = buildUltraCompactPersonalizationLine(preferenceProfile)
        val topicLine = buildUltraCompactTopicLine(recommendationTopic, targetCategory)

        return """
JSON only: {"candidates":[{"display_name":"","amap_search_keyword":"","distance_desc":"","tag":"","intro":""}]}
4 diverse Wuhan $categoryLabel places. t=${environment.currentTime.hour}:00 w=${environment.weatherCondition} ll=${String.format(Locale.US, "%.3f", environment.latitude)},${String.format(Locale.US, "%.3f", environment.longitude)}
No exact repeat: $avoidLine
$personalizationLine
$topicLine
display_name=clean UI name; amap_search_keyword=POI+area. meal=nearby food/drink<=2.5km, different cuisines. play=nearby hidden-gem non-food real places; include >=2 DIY/lifestyle/interactive/arcade/studio/vintage/record/toy/small-gallery options. Only scenic parks/landmarks/museums/galleries/theaters or truly special interactive places may be farther. Never 昙华林/博物馆/东湖/万林/ordinary bookstore. No breakfast/noodle/hotpot/bbq/restaurant-only. No vague mall/district. intro<=12 Chinese chars.
""".trimIndent()
    }

    private fun buildPersonalizationSection(
        preferenceProfile: RecommendationPreferenceProfile?
    ): String {
        val hint = preferenceProfile
            ?.promptHint()
            ?.takeIf { it.isNotBlank() }
            ?: return "Personalization: no strong local preference yet."

        return """
Personalization:
$hint
Use this as a soft preference, not a hard rule. Still obey time, weather, distance, and repetition constraints.
""".trimIndent()
    }

    private fun buildTopicSection(
        recommendationTopic: RecommendationTopic?,
        targetCategory: String
    ): String {
        val topic = recommendationTopic ?: return "Android topic focus: balanced discovery."
        val categoryRule = if (targetCategory == "meal") {
            "Stay in this food vertical; pick a concrete nearby restaurant/cafe/stall, not a broad street or mall."
        } else {
            "Stay in this play vertical; pick a concrete real destination, not a broad district or generic tourist default."
        }
        return """
Android-selected topic focus:
- Topic: ${topic.label}
- Probability reason: Android weighted this topic using local preference, time, weather, and recent cooldown.
- Must search inside this topic only: ${topic.promptHint}
- $categoryRule
""".trimIndent()
    }

    private fun buildCompactTopicLine(
        recommendationTopic: RecommendationTopic?,
        targetCategory: String
    ): String {
        val topic = recommendationTopic ?: return "Topic focus: balanced."
        val categoryRule = if (targetCategory == "meal") {
            "stay food-only"
        } else {
            "stay non-food play-only"
        }
        return "Topic focus: 【${topic.label}】, $categoryRule, ${topic.compactHint.take(150)}"
    }

    private fun buildUltraCompactTopicLine(
        recommendationTopic: RecommendationTopic?,
        targetCategory: String
    ): String {
        val topic = recommendationTopic ?: return "Topic: balanced."
        val categoryRule = if (targetCategory == "meal") "food-only" else "play-only"
        return "Topic: 【${topic.label}】 $categoryRule. ${topic.compactHint.take(60)}"
    }

    private fun buildCompactPersonalizationLine(
        preferenceProfile: RecommendationPreferenceProfile?
    ): String {
        val hint = preferenceProfile
            ?.promptHint()
            ?.takeIf { it.isNotBlank() }
            ?: return "Personalization: no strong preference yet."
        return "Personalization: $hint"
    }

    private fun buildUltraCompactPersonalizationLine(
        preferenceProfile: RecommendationPreferenceProfile?
    ): String {
        val hint = preferenceProfile
            ?.promptHint()
            ?.takeIf { it.isNotBlank() }
            ?.take(80)
            ?: return "Pref: none."
        return "Pref: $hint"
    }

    private fun buildCompactTimeRule(hour: Int, targetCategory: String): String {
        return when (hour) {
            in 5..10 -> if (targetCategory == "meal") {
                "morning/breakfast/coffee ok; never sunset, bar, supper, late-night."
            } else {
                "morning-friendly only; never sunset, bar, supper, late-night."
            }
            in 11..15 -> "midday/afternoon only; never sunset or deep-night nightlife."
            in 16..19 -> "late afternoon/dusk is ok; dinner, dessert, cafe, sunset/outdoor only if weather fits."
            in 20..23 -> "evening only; dinner, bar, night view, livehouse ok; never breakfast-only."
            else -> "late night only; prefer supper, bbq, bar, night-open indoor places; never morning/day-only."
        }
    }

    private fun buildCompactWeatherRule(
        weatherCondition: String,
        targetCategory: String
    ): String {
        val profile = buildWeatherProfile(weatherCondition)
        val base = if (targetCategory == "meal") {
            "choose sheltered nearby food/drink."
        } else {
            "choose weather-safe leisure."
        }
        return when {
            profile.isSevere -> "$base Severe weather: indoor/sheltered, short walking only; never open-air park/riverside/lake/viewpoint."
            profile.isRainy -> "$base Rain/wet: indoor/covered and short walking; for play prefer museum/gallery/exhibition/bookstore/cinema/theater/arcade/craft studio over open-air scenery."
            profile.isHot -> "$base Hot: air-conditioned indoor/shaded short route; avoid exposed noon outdoor walks."
            profile.isCold -> "$base Cold: warm indoor/sheltered; avoid windy riverside/lake and long outdoor stay."
            profile.isFoggy -> "$base Poor visibility: avoid viewpoint-dependent places."
            profile.isComfortableOutdoor -> "$base Comfortable: outdoor parks/riverside/lake/viewpoints allowed if time fits."
            else -> "$base Neutral weather: balance indoor/outdoor and keep it realistic nearby."
        }
    }

    private fun buildTimeMatchGuardrails(
        hour: Int,
        targetCategory: String
    ): String {
        val categoryHint = if (targetCategory == "meal") "Choose a place that fits this meal time." else "Choose a leisure place that fits this hour."
        return when (hour) {
            in 5..10 -> """
$categoryHint
- Morning only.
- Never sunset / bar / livehouse / supper / late-night place.
""".trimIndent()
            in 11..15 -> """
$categoryHint
- Midday only.
- Never sunset / deep-night nightlife / midnight snack.
""".trimIndent()
            in 16..19 -> """
$categoryHint
- Late afternoon to dusk.
- Sunset / riverside / park / viewpoint / dinner / dessert / cafe are fine.
""".trimIndent()
            in 20..23 -> """
$categoryHint
- Evening only.
- Dinner / bar / night view / riverside / stroll / livehouse are fine.
- Never breakfast-only places.
""".trimIndent()
            else -> """
$categoryHint
- Late night only.
- Prefer supper / bbq / bar / night stroll / late-opening place.
- Never morning-only or daytime-only place.
""".trimIndent()
        }
    }

    private fun buildWeatherGuardrails(
        weatherCondition: String,
        targetCategory: String
    ): String {
        val profile = buildWeatherProfile(weatherCondition)
        val categoryHint = if (targetCategory == "meal") {
            "For meal, choose a sheltered nearby restaurant/cafe/dessert/hotpot place."
        } else {
            "For play, choose a weather-appropriate leisure destination."
        }

        return when {
            profile.isSevere -> """
$categoryHint
- Current weather is unfriendly: ${profile.label}.
- Must be indoor or strongly sheltered, low walking, easy to reach.
- Never recommend open-air park, riverside, lake walk, mountain, campus walk, sunset viewpoint, outdoor photo spot, or a place requiring long walking.
- Prefer mall, bookstore, cinema, museum, gallery, indoor exhibition, cafe, dessert, hotpot, or a warm sheltered restaurant.
""".trimIndent()

            profile.isRainy -> """
$categoryHint
- It is raining or likely wet: ${profile.label}.
- Prefer indoor or covered places with short walking distance.
- Avoid open-air riverside, lake, park, sunset, lawn, mountain, and outdoor-only spots.
- If recommending play, prioritize bookstore, exhibition, cinema, museum, gallery, mall, or indoor arcade.
""".trimIndent()

            profile.isHot -> """
$categoryHint
- It is hot: ${profile.label}.
- Prefer air-conditioned indoor places, shaded routes, short walking, drinks, dessert, bookstore, cinema, exhibition, mall, or cafe.
- Avoid exposed noon walks, open parks without shade, and long outdoor routes.
""".trimIndent()

            profile.isCold -> """
$categoryHint
- It is cold: ${profile.label}.
- Prefer warm indoor places, hot food, cafe, bookstore, cinema, museum, gallery, or sheltered mall.
- Avoid windy riverside, open lake walks, and long outdoor stays.
""".trimIndent()

            profile.isFoggy -> """
$categoryHint
- Visibility is not ideal: ${profile.label}.
- Prefer indoor places or simple nearby routes.
- Avoid viewpoint-dependent recommendations.
""".trimIndent()

            profile.isComfortableOutdoor -> """
$categoryHint
- Weather is comfortable: ${profile.label}.
- Outdoor scenic spots, parks, riverside/lake walks, campus walks, and viewpoints are allowed when time also fits.
- Still keep route realistic and not too far.
""".trimIndent()

            else -> """
$categoryHint
- Weather is neutral: ${profile.label}.
- Balance indoor and outdoor, but keep the choice realistic for current time and location.
""".trimIndent()
        }
    }

    private fun buildWeatherProfile(weatherCondition: String): AiWeatherProfile {
        val temperature = Regex("(-?\\d+(?:\\.\\d+)?)\\s*°C")
            .find(weatherCondition)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
        val inferredTemperature = temperature ?: Regex("(-?\\d+(?:\\.\\d+)?)")
            .find(weatherCondition)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
        val isRainy = weatherCondition.contains("\u96e8") ||
            weatherCondition.contains("\u964d\u6c34") ||
            weatherCondition.contains("rain", ignoreCase = true)
        val isSnowy = weatherCondition.contains("\u96ea") ||
            weatherCondition.contains("snow", ignoreCase = true)
        val isThunder = weatherCondition.contains("\u96f7")
        val isStrong = weatherCondition.contains("\u5f3a") ||
            weatherCondition.contains("\u51bb") ||
            isThunder ||
            isSnowy
        val isFoggy = weatherCondition.contains("\u96fe")
        val isHot = inferredTemperature?.let { it >= 30.0 } ?: weatherCondition.contains("\u70ed")
        val isCold = inferredTemperature?.let { it <= 12.0 } ?: weatherCondition.contains("\u51b7")
        val isComfortableOutdoor = !isRainy && !isSnowy && !isFoggy && !isHot && !isCold &&
            (weatherCondition.contains("\u6674") || weatherCondition.contains("\u591a\u4e91"))

        return AiWeatherProfile(
            label = weatherCondition,
            isRainy = isRainy,
            isSevere = isStrong,
            isHot = isHot,
            isCold = isCold,
            isFoggy = isFoggy,
            isComfortableOutdoor = isComfortableOutdoor
        )
    }

    private data class AiWeatherProfile(
        val label: String,
        val isRainy: Boolean,
        val isSevere: Boolean,
        val isHot: Boolean,
        val isCold: Boolean,
        val isFoggy: Boolean,
        val isComfortableOutdoor: Boolean
    )

    private data class AiPoiPolishDto(
        val tag: String? = null,
        val intro: String? = null
    )

    private suspend fun requestJsonContent(
        systemPrompt: String? = null,
        prompt: String,
        maxCompletionTokens: Int,
        reasoningEffort: String,
        overrideModel: String = model,
        temperature: Double = 0.08,
        presencePenalty: Double? = null,
        preferJsonResponseFormat: Boolean = true
    ): String {
        return chatClient.requestJsonContent(
            AiChatRequest(
                systemPrompt = systemPrompt,
                prompt = prompt,
                maxCompletionTokens = maxCompletionTokens,
                reasoningEffort = reasoningEffort,
                model = overrideModel,
                temperature = temperature,
                presencePenalty = presencePenalty,
                preferJsonResponseFormat = preferJsonResponseFormat
            )
        )
    }

    private fun ParsedWishDto.toParsedWish(): ParsedWish {
        val parsedTitle = title?.trim().orEmpty()
        require(parsedTitle.isNotEmpty()) {
            "Parsed wish title is empty"
        }

        val parsedCategory = when (category?.trim()?.lowercase()) {
            "meal" -> "meal"
            "play" -> "play"
            else -> "play"
        }

        return ParsedWish(
            title = parsedTitle,
            category = parsedCategory,
            locationKeyword = locationKeyword?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    private fun parsePoiChoice(
        json: String,
        candidates: List<DecisionPoiCandidate>
    ): AiPoiChoice {
        val root = JsonParser.parseString(json).asJsonObject
        val rawIndex = listOf("index", "candidate_index", "choice", "selected_index")
            .firstNotNullOfOrNull { memberName ->
                root.get(memberName)?.let { element ->
                    when {
                        element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asInt
                        element.isJsonPrimitive -> element.asString.toIntOrNull()
                        else -> null
                    }
                }
            }
            ?: error("AI POI choice JSON did not contain index")

        val normalizedIndex = when {
            rawIndex in candidates.indices -> rawIndex
            rawIndex - 1 in candidates.indices -> rawIndex - 1
            else -> error("AI POI choice index out of range: $rawIndex")
        }

        return AiPoiChoice(
            index = normalizedIndex,
            tag = cleanRecommendationTag(root.getStringOrNull("tag")),
            intro = cleanPoiIntro(root.getStringOrNull("intro")),
            reason = root.getStringOrNull("reason")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.take(40)
        )
    }

    private fun parseDecisionCandidates(json: String): List<AiDecisionRecommendation> {
        val wrapperCandidates = runCatching {
            gson.fromJson(json, AiDecisionRecommendationsDto::class.java)
                ?.candidates
                .orEmpty()
        }.getOrDefault(emptyList())

        val dtoCandidates = wrapperCandidates.ifEmpty {
            runCatching {
                gson.fromJson(json, Array<AiDecisionRecommendationDto>::class.java)
                    ?.toList()
                    .orEmpty()
            }.getOrDefault(emptyList())
        }.ifEmpty {
            listOfNotNull(
                runCatching {
                    gson.fromJson(json, AiDecisionRecommendationDto::class.java)
                }.getOrNull()
            )
        }

        val recommendations = dtoCandidates
            .mapNotNull { dto ->
                runCatching { dto.toRecommendation() }.getOrNull()
            }
            .distinctBy { normalizeCandidateName(it.name) }

        require(recommendations.isNotEmpty()) {
            "AI recommendation candidates JSON could not be parsed"
        }
        return recommendations
    }

    private fun normalizeCandidateName(name: String): String {
        return name
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\p{Punct}（）()【】\\[\\]「」『』《》“”‘’、，。！？；：·•-]+"), "")
            .replace(WuhanKnowledgeConfig.CITY, "")
            .trim()
    }

    private fun AiDecisionRecommendationDto.toRecommendation(): AiDecisionRecommendation {
        val parsedName = displayName?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: name?.trim().orEmpty()
        require(parsedName.isNotEmpty()) {
            "AI decision recommendation display_name is empty"
        }
        val parsedSearchKeyword = amapSearchKeyword
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: name?.trim()?.takeIf { it.isNotEmpty() && it != parsedName }

        return AiDecisionRecommendation(
            name = parsedName,
            amapSearchKeyword = parsedSearchKeyword,
            imageUrl = imageUrl?.trim()?.takeIf { it.isNotEmpty() },
            distanceDescription = cleanDistanceDescription(distanceDescription),
            tag = cleanRecommendationTag(tag),
            intro = intro?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    private fun cleanPoiIntro(rawIntro: String?): String? {
        val text = rawIntro
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val bannedGenericSignals = listOf(
            "真实可达",
            "适合约会",
            "适合现在",
            "AI",
            "ai",
            "根据天气",
            "根据时间",
            "根据位置",
            "天气",
            "位置",
            "路线",
            "推荐",
            "轻松逛一会"
        )
        if (bannedGenericSignals.any(text::contains) && text.length <= 18) {
            return null
        }

        val cleaned = text
            .replace("真实可达，", "")
            .replace("真实可达", "")
            .replace("适合约会", "")
            .replace("适合现在", "")
            .replace("AI推荐", "")
            .replace("根据天气", "")
            .replace("根据时间", "")
            .replace("根据位置", "")
            .replace("轻松逛一会", "")
            .trim(' ', '，', '。', '.', ',', ';', '；')

        return cleaned
            .take(42)
            .takeIf { it.isNotBlank() }
    }

    private fun cleanDistanceDescription(rawDistanceDescription: String?): String? {
        val text = rawDistanceDescription
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val parts = text.split('，', ',', '、', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val cleanedParts = parts.filterNot { part ->
            containsAny(
                part,
                "天气",
                "雨天",
                "阵雨",
                "下雨",
                "避雨",
                "防晒",
                "凉快",
                "暖和",
                "室内玩",
                "适合"
            )
        }

        return cleanedParts
            .ifEmpty { parts.take(1) }
            .joinToString("，")
            .take(24)
            .trim('，', ',', '、', ';', '；', ' ')
            .takeIf { it.isNotEmpty() }
    }

    private fun cleanRecommendationTag(rawTag: String?): String? {
        val text = rawTag
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val normalized = when (text.lowercase(Locale.ROOT)) {
            "meal" -> "餐饮"
            "play" -> "游玩"
            else -> text
        }
        val cleanedParts = normalized
            .split('｜', '|', '/', '、', ',', '，', '·', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { part ->
                containsAny(
                    part,
                    "天气",
                    "雨天",
                    "下雨",
                    "阵雨",
                    "避雨",
                    "适合",
                    "根据",
                "推荐",
                "室内"
            )
        }

        return cleanedParts
            .firstOrNull()
            ?.take(6)
            ?: normalized
                .replace("雨天", "")
                .replace("天气", "")
                .replace("室内", "")
                .replace("适合", "")
                .take(6)
                .trim()
                .takeIf { it.isNotEmpty() }
    }

    private fun containsAny(
        text: String,
        vararg keywords: String
    ): Boolean {
        return keywords.any(text::contains)
    }

    private companion object {
        private const val TAG = "AiRepository"
        private const val JSON_RESPONSE_FORMAT_TYPE = "json_object"
        private const val DISABLED_THINKING_TYPE = "disabled"
        private const val AI_ERROR_BODY_LOG_LIMIT = 400
        private const val AI_CHOICE_CANDIDATE_LIMIT = 8
    }
}
