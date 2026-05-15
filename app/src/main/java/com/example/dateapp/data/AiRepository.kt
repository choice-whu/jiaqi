package com.example.dateapp.data

import com.example.dateapp.data.environment.DecisionEnvironmentSnapshot
import com.example.dateapp.data.remote.AiApiService
import com.example.dateapp.data.remote.AiDecisionRecommendation
import com.example.dateapp.data.remote.AiDecisionRecommendationDto
import com.example.dateapp.data.remote.AiDecisionRecommendationsDto
import com.example.dateapp.data.remote.AiNetworkModule
import com.example.dateapp.data.remote.ChatMessage
import com.example.dateapp.data.remote.ChatRequest
import com.example.dateapp.data.remote.ParsedWish
import com.example.dateapp.data.remote.ParsedWishDto
import com.example.dateapp.data.recommendation.RecommendationPreferenceProfile
import com.google.gson.Gson
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class AiRepository(
    private val apiService: AiApiService,
    private val gson: Gson = Gson(),
    private val model: String = AiNetworkModule.defaultModel,
    private val decisionModel: String = AiNetworkModule.decisionModel
) {

    suspend fun parseWishIntent(rawText: String): Result<ParsedWish> {
        val normalizedText = rawText.trim()
        if (normalizedText.isEmpty()) {
            return Result.failure(IllegalArgumentException("rawText is empty"))
        }

        return withContext(Dispatchers.IO) {
            runAiCatching {
                val json = requestJsonContent(
                    prompt = buildWishIntentPrompt(normalizedText),
                    maxCompletionTokens = 96,
                    reasoningEffort = "low"
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
        fastMode: Boolean = true,
        rescueMode: Boolean = false
    ): Result<AiDecisionRecommendation> {
        return withContext(Dispatchers.IO) {
            runAiCatching {
                val json = requestJsonContent(
                    prompt = if (rescueMode) {
                        buildRescueDecisionPromptV2(
                            environment = environment,
                            targetCategory = targetCategory,
                            avoidNames = avoidNames,
                            preferenceProfile = preferenceProfile
                        )
                    } else if (fastMode) {
                        buildFastDecisionPromptV2(
                            environment = environment,
                            targetCategory = targetCategory,
                            strictTimeMatch = strictTimeMatch,
                            avoidNames = avoidNames,
                            nearbyMallName = nearbyMallName,
                            preferenceProfile = preferenceProfile
                        )
                    } else {
                        buildDecisionPromptV2(
                            environment = environment,
                            targetCategory = targetCategory,
                            strictTimeMatch = strictTimeMatch,
                            avoidNames = avoidNames,
                            nearbyMallName = nearbyMallName,
                            preferenceProfile = preferenceProfile
                        )
                    },
                    maxCompletionTokens = when {
                        rescueMode -> 70
                        fastMode -> 80
                        else -> 150
                    },
                    reasoningEffort = "low",
                    overrideModel = decisionModel
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
        fastMode: Boolean = true,
        rescueMode: Boolean = false
    ): Result<List<AiDecisionRecommendation>> {
        return withContext(Dispatchers.IO) {
            runAiCatching {
                val json = requestJsonContent(
                    prompt = if (rescueMode) {
                        buildRescueDecisionCandidatesPromptV2(
                            environment = environment,
                            targetCategory = targetCategory,
                            avoidNames = avoidNames,
                            preferenceProfile = preferenceProfile
                        )
                    } else if (fastMode) {
                        buildFastDecisionCandidatesPromptV2(
                            environment = environment,
                            targetCategory = targetCategory,
                            strictTimeMatch = strictTimeMatch,
                            avoidNames = avoidNames,
                            nearbyMallName = nearbyMallName,
                            preferenceProfile = preferenceProfile
                        )
                    } else {
                        buildDecisionCandidatesPromptV2(
                            environment = environment,
                            targetCategory = targetCategory,
                            strictTimeMatch = strictTimeMatch,
                            avoidNames = avoidNames,
                            nearbyMallName = nearbyMallName,
                            preferenceProfile = preferenceProfile
                        )
                    },
                    maxCompletionTokens = when {
                        rescueMode -> 260
                        fastMode -> 180
                        else -> 400
                    },
                    reasoningEffort = "low",
                    overrideModel = decisionModel
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

    private fun buildDecisionPrompt(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String
    ): String {
        val categoryLabel = if (targetCategory == "meal") "餐饮" else "游玩"
        return "你是一个极度理性的约会推荐引擎。" +
            "当前时间是[${environment.currentTimeLabel}]，" +
            "天气是[${environment.weatherCondition}]，" +
            "用户位于武汉市[${environment.userLocationLabel} (${String.format(Locale.US, "%.4f", environment.latitude)}, ${String.format(Locale.US, "%.4f", environment.longitude)})]。" +
            "请结合当前时段氛围，为情侣推荐唯一一个最适合现在立刻前往的[$categoryLabel]目的地。" +
            "餐饮时段请明显偏向餐厅、咖啡馆、甜品店、小酒馆、烧烤、火锅等真实消费场所；" +
            "非饭点请明显偏向景点、公园、江滩、展览、书店、文创小店、商场里的有趣店铺、散步打卡点、livehouse 等轻松好去处。" +
            "如果目标是餐饮，不允许返回整条商圈、步行街或公园；如果目标是游玩，可以返回景点、公园、书店、展览、江滩、商场内具体店铺或明确名称的打卡点。" +
            "结果必须是一个可以真正前往的具体地点，不要返回模糊区域。" +
            "你只能返回一个 JSON 对象，不要返回数组、解释文字或 Markdown。" +
            "必须字段包括：" +
            "'name'(店名或地点名)、" +
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
        preferenceProfile: RecommendationPreferenceProfile?
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
        return """
Wuhan couple date picker.
Time: ${environment.currentTimeLabel} Asia/Shanghai.
Weather: ${environment.weatherCondition}.
Start area: ${environment.userLocationLabel} (${String.format(Locale.US, "%.4f", environment.latitude)}, ${String.format(Locale.US, "%.4f", environment.longitude)}).
Need: one real specific $categoryLabel place they can go to right now.

$retryGuard
$avoidSection
$personalizationSection
$explorationSection

Weather fit:
${buildWeatherGuardrails(environment.weatherCondition, targetCategory)}

Time fit:
${buildTimeMatchGuardrails(environment.currentTime.hour, targetCategory)}

Rules:
- meal => restaurant / cafe / dessert / hotpot / bbq / bar / supper spot only.
- meal => never park / bookstore / riverside / scenic area / vague district.
- meal => must be close to the given coordinates; prefer within 1.5 km, absolute max 2.5 km. Never recommend a far branch or distant business district.
- meal => if a brand has multiple branches, pick the nearest branch around the coordinates and put the branch name in "name".
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
{"name":"","image_url":"","distance_desc":"","tag":"","intro":""}
Use short natural Chinese for "tag" and "intro". "intro" only describes the place itself.
""".trimIndent()
    }

    private fun buildFastDecisionPromptV2(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String,
        strictTimeMatch: Boolean,
        avoidNames: List<String>,
        nearbyMallName: String?,
        preferenceProfile: RecommendationPreferenceProfile?
    ): String {
        val categoryLabel = if (targetCategory == "meal") "meal" else "play"
        val strictLine = if (strictTimeMatch) {
            "Strict retry: fix the previous mismatch. Time/weather/nearby fit must be exact."
        } else {
            "First pass: answer quickly with the best fitting real place."
        }
        val avoidLine = avoidNames
            .distinct()
            .takeLast(18)
            .joinToString(" | ")
            .ifBlank { "none" }
        val personalizationLine = buildCompactPersonalizationLine(preferenceProfile)
        return """
Return JSON only: {"name":"","image_url":"","distance_desc":"","tag":"","intro":""}
You are a Wuhan date-place picker. Values must be natural Chinese.
Context: time=${environment.currentTimeLabel} Asia/Shanghai; weather=${environment.weatherCondition}; area=${environment.userLocationLabel}; coords=${String.format(Locale.US, "%.4f", environment.latitude)},${String.format(Locale.US, "%.4f", environment.longitude)}.
Need: one real specific $categoryLabel place usable right now.
$strictLine
Avoid recent/disliked: $avoidLine.
$personalizationLine
Never choose the same place, same branch, or renamed alias from the avoid list.
Time rule: ${buildCompactTimeRule(environment.currentTime.hour, targetCategory)}
Weather rule: ${buildCompactWeatherRule(environment.weatherCondition, targetCategory)}
Rules: do not overfit to nearby malls, but concrete mall shops are allowed. meal=nearby restaurant/cafe/dessert/hotpot/bbq/bar/supper only, prefer <=1.5km and max 2.5km; never park/scenic/vague district. play=concrete fun/scenic/cultural/lifestyle place; vary across attractions, parks, riverside/lakeside, museums, galleries, exhibitions, old streets, creative blocks, markets, landmarks, theaters, livehouses, studios, arcades, small theaters, bookstores, toy/blind-box shops, record/vintage stores, buyer shops, lifestyle stores, dessert/cafe stops, and owner-run small shops. If mall/shop, name the specific store/spot, not a vague mall. No markdown, no explanation. intro<=28 Chinese chars and only describes the place itself.
""".trimIndent()
    }

    private fun buildRescueDecisionPromptV2(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String,
        avoidNames: List<String>,
        preferenceProfile: RecommendationPreferenceProfile?
    ): String {
        val categoryLabel = if (targetCategory == "meal") "meal" else "play"
        val avoidLine = avoidNames
            .distinct()
            .takeLast(12)
            .joinToString(" | ")
            .ifBlank { "none" }
        val personalizationLine = buildUltraCompactPersonalizationLine(preferenceProfile)

        return """
JSON only: {"name":"","distance_desc":"","tag":"","intro":""}
Wuhan $categoryLabel now. t=${environment.currentTime.hour} w=${environment.weatherCondition} ll=${String.format(Locale.US, "%.3f", environment.latitude)},${String.format(Locale.US, "%.3f", environment.longitude)}
Avoid: $avoidLine
$personalizationLine
Time: ${buildUltraCompactTimeRule(environment.currentTime.hour, targetCategory)}
meal=nearby food/drink<=2.5km. play=prefer named shop/lifestyle/interactive/cafe/bookstore/arcade/studio over famous scenic repeats; no vague district. intro<=10 Chinese chars.
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
            in 16..19 -> "open now; dusk/dinner/cafe/outdoor ok."
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
        preferenceProfile: RecommendationPreferenceProfile?
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
                .takeLast(28)
                .joinToString(
                    separator = "\n",
                    prefix = "Avoid these recent/disliked places, same branches, aliases, and close variants:\n"
                ) { "- $it" }
        } else {
            "Avoid recent/disliked places: none."
        }
        val mallLine = nearbyMallName
            ?.takeIf { it.isNotBlank() }
            ?.let { "Nearby mall signal exists ($it). You may use a concrete interesting shop/arcade/cinema/bookstore/cafe inside it, but avoid vague mall-only answers." }
            ?: "No mall preference. Malls and shops are allowed only as concrete named mini-destinations."
        val personalizationSection = buildPersonalizationSection(preferenceProfile)

        return """
Return JSON only:
{"candidates":[{"name":"","image_url":"","distance_desc":"","tag":"","intro":""}]}

Wuhan couple date picker. Give 4 diverse real specific $categoryLabel places usable right now.
Time: ${environment.currentTimeLabel} Asia/Shanghai.
Weather: ${environment.weatherCondition}.
Start area: ${environment.userLocationLabel} (${String.format(Locale.US, "%.4f", environment.latitude)}, ${String.format(Locale.US, "%.4f", environment.longitude)}).
$retryGuard
$avoidSection
$mallLine
$personalizationSection

Time rule: ${buildCompactTimeRule(environment.currentTime.hour, targetCategory)}
Weather rule: ${buildCompactWeatherRule(environment.weatherCondition, targetCategory)}

Rules:
- Candidates must be meaningfully different place types or neighborhoods.
- meal => real nearby restaurant/cafe/dessert/hotpot/bbq/bar/supper only; prefer <=1.5km and max 2.5km; include nearest branch name when relevant.
- meal => no park, scenic area, vague district, broad food street, or far famous branch.
- play => mix scenic/cultural/fun/lifestyle places: attractions, parks, riverside/lakeside, museums, galleries, exhibitions, old streets, creative blocks, bookstores, theaters, livehouses, studios, markets, viewpoints, arcades, escape rooms, bowling, pool halls, craft studios, toy/blind-box shops, record/vintage stores, buyer shops, lifestyle stores, dessert/cafe stops, and owner-run small shops.
- play => include more playful and varied options; do not repeat the same obvious top places or the same mall/shop style.
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
        preferenceProfile: RecommendationPreferenceProfile?
    ): String {
        val categoryLabel = if (targetCategory == "meal") "meal" else "play"
        val strictLine = if (strictTimeMatch) {
            "Strict retry: every candidate must fit the current hour/weather/distance and avoid list."
        } else {
            "First pass: answer quickly with diverse strong candidates."
        }
        val avoidLine = avoidNames
            .distinct()
            .takeLast(22)
            .joinToString(" | ")
            .ifBlank { "none" }
        val mallLine = if (nearbyMallName.isNullOrBlank()) {
            "No mall preference; concrete shops/malls are allowed, but avoid vague mall-only answers."
        } else {
            "Nearby mall=$nearbyMallName; concrete interesting shops inside it are allowed, but avoid mall overfitting."
        }
        val personalizationLine = buildCompactPersonalizationLine(preferenceProfile)

        return """
Return JSON only: {"candidates":[{"name":"","image_url":"","distance_desc":"","tag":"","intro":""}]}
Give 4 diverse real Wuhan $categoryLabel places usable right now.
Context: time=${environment.currentTimeLabel} Asia/Shanghai; weather=${environment.weatherCondition}; area=${environment.userLocationLabel}; coords=${String.format(Locale.US, "%.4f", environment.latitude)},${String.format(Locale.US, "%.4f", environment.longitude)}.
$strictLine
Avoid same/alias/recent/disliked: $avoidLine.
$mallLine
$personalizationLine
Time rule: ${buildCompactTimeRule(environment.currentTime.hour, targetCategory)}
Weather rule: ${buildCompactWeatherRule(environment.weatherCondition, targetCategory)}
Rules: meal=nearby concrete food/drink only, prefer <=1.5km and max 2.5km, nearest branch. play=concrete fun/scenic/cultural/lifestyle place; vary across attractions, parks, riverside/lakeside, museums, galleries, exhibitions, old streets, creative blocks, landmarks, theaters, livehouses, studios, arcades, escape rooms, bookstores, toy/blind-box shops, record/vintage stores, buyer shops, dessert/cafe stops, and owner-run small shops. If mall/shop, name the specific spot. Bad weather => indoor/sheltered. intro<=24 Chinese chars. No markdown, no explanation.
For play batches: include at least 2 shop/lifestyle/interactive micro-destinations, and at most 1 classic park/museum/scenic landmark.
""".trimIndent()
    }

    private fun buildRescueDecisionCandidatesPromptV2(
        environment: DecisionEnvironmentSnapshot,
        targetCategory: String,
        avoidNames: List<String>,
        preferenceProfile: RecommendationPreferenceProfile?
    ): String {
        val categoryLabel = if (targetCategory == "meal") "meal" else "play"
        val avoidLine = avoidNames
            .distinct()
            .takeLast(14)
            .joinToString(" | ")
            .ifBlank { "none" }
        val personalizationLine = buildUltraCompactPersonalizationLine(preferenceProfile)

        return """
JSON only: {"candidates":[{"name":"","distance_desc":"","tag":"","intro":""}]}
4 diverse Wuhan $categoryLabel places. t=${environment.currentTime.hour}:00 w=${environment.weatherCondition} ll=${String.format(Locale.US, "%.3f", environment.latitude)},${String.format(Locale.US, "%.3f", environment.longitude)}
Avoid: $avoidLine
$personalizationLine
meal=nearby food/drink<=2.5km, different cuisines. play=varied real places; include >=2 named shops/lifestyle/interactive/cafe/bookstore/arcade/studio options and <=1 classic park/museum/scenic spot. No vague mall/district/nearby area. intro<=12 Chinese chars.
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
            ?.take(160)
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

    private suspend fun requestJsonContent(
        prompt: String,
        maxCompletionTokens: Int,
        reasoningEffort: String,
        overrideModel: String = model
    ): String {
        val startedAtMillis = System.currentTimeMillis()
        Log.d(
            TAG,
            "ai source=http_start model=$overrideModel promptLength=${prompt.length} maxTokens=$maxCompletionTokens"
        )
        val response = apiService.createChatCompletion(
            ChatRequest(
                model = overrideModel,
                messages = listOf(
                    ChatMessage(
                        role = "user",
                        content = prompt
                    )
                ),
                maxCompletionTokens = maxCompletionTokens,
                reasoningEffort = reasoningEffort
            )
        )

        val rawBody = if (response.isSuccessful) {
            response.body()?.string()
        } else {
            val errorBody = response.errorBody()?.string().orEmpty()
            error("AI HTTP ${response.code()}: ${errorBody.take(AI_ERROR_BODY_LOG_LIMIT)}")
        }
            ?.takeIf { it.isNotBlank() }
            ?: error("AI response body is empty")

        Log.d(
            TAG,
            "ai source=http_body elapsed=${System.currentTimeMillis() - startedAtMillis}ms rawLength=${rawBody.length}"
        )

        val content = extractChatMessageContent(rawBody)
            ?.takeIf { it.isNotBlank() }
            ?: error("AI response did not contain message content: ${rawBody.take(AI_ERROR_BODY_LOG_LIMIT)}")

        Log.d(
            TAG,
            "ai source=http_success elapsed=${System.currentTimeMillis() - startedAtMillis}ms contentLength=${content.length}"
        )

        return extractJsonObject(content)
    }

    private fun extractChatMessageContent(rawBody: String): String? {
        val root = JsonParser.parseString(rawBody).asJsonObject
        val choices = root.getAsJsonArray("choices") ?: return null
        val firstChoice = choices.firstOrNull()?.asJsonObject ?: return null

        firstChoice.getAsJsonObjectOrNull("message")
            ?.getStringOrNull("content")
            ?.let { return it }

        firstChoice.getStringOrNull("text")?.let { return it }

        firstChoice.getAsJsonObjectOrNull("delta")
            ?.getStringOrNull("content")
            ?.let { return it }

        return null
    }

    private fun JsonObject.getAsJsonObjectOrNull(memberName: String): JsonObject? {
        val element = get(memberName) ?: return null
        return if (element.isJsonObject) element.asJsonObject else null
    }

    private fun JsonObject.getStringOrNull(memberName: String): String? {
        val element = get(memberName) ?: return null
        return when {
            element.isJsonPrimitive -> element.asString
            element.isJsonObject || element.isJsonArray -> element.toString()
            else -> null
        }
    }

    private fun extractJsonObject(rawContent: String): String {
        val trimmedContent = rawContent.trim()
        val fencedBlocks = Regex(
            pattern = "```(?:json)?\\s*([\\s\\S]*?)\\s*```",
            option = RegexOption.IGNORE_CASE
        ).findAll(trimmedContent)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
            .toList()

        val candidates = buildList {
            add(trimmedContent)
            addAll(fencedBlocks)
        }

        return candidates.firstNotNullOfOrNull(::findBalancedJsonValue) ?: error(
            "AI response did not contain a JSON object or array"
        )
    }

    private fun findBalancedJsonValue(content: String): String? {
        var valueStart = -1
        var openingChar = ' '
        var closingChar = ' '
        var depth = 0
        var inString = false
        var isEscaped = false

        for (index in content.indices) {
            val currentChar = content[index]

            if (valueStart == -1) {
                if (currentChar == '{' || currentChar == '[') {
                    valueStart = index
                    openingChar = currentChar
                    closingChar = if (currentChar == '{') '}' else ']'
                    depth = 1
                    inString = false
                    isEscaped = false
                }
                continue
            }

            if (isEscaped) {
                isEscaped = false
                continue
            }

            when (currentChar) {
                '\\' -> if (inString) {
                    isEscaped = true
                }

                '"' -> inString = !inString

                openingChar -> if (!inString) {
                    depth += 1
                }

                closingChar -> if (!inString) {
                    depth -= 1
                    if (depth == 0) {
                        return content.substring(valueStart, index + 1)
                    }
                }
            }
        }

        return null
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
            .replace("武汉", "")
            .trim()
    }

    private fun AiDecisionRecommendationDto.toRecommendation(): AiDecisionRecommendation {
        val parsedName = name?.trim().orEmpty()
        require(parsedName.isNotEmpty()) {
            "AI decision recommendation name is empty"
        }

        return AiDecisionRecommendation(
            name = parsedName,
            imageUrl = imageUrl?.trim()?.takeIf { it.isNotEmpty() },
            distanceDescription = cleanDistanceDescription(distanceDescription),
            tag = cleanRecommendationTag(tag),
            intro = intro?.trim()?.takeIf { it.isNotEmpty() }
        )
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
        private const val AI_ERROR_BODY_LOG_LIMIT = 400
    }
}
