package com.example.dateapp.data.remote

import com.google.gson.annotations.SerializedName

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.08,
    @SerializedName("max_tokens")
    val maxTokens: Int? = null,
    @SerializedName("max_completion_tokens")
    val maxCompletionTokens: Int? = null,
    @SerializedName("reasoning_effort")
    val reasoningEffort: String? = null,
    val stream: Boolean = false
)

data class ChatMessage(
    val role: String,
    val content: String
)

data class ChatResponse(
    val id: String? = null,
    val choices: List<ChatChoice> = emptyList()
)

data class ChatChoice(
    val index: Int? = null,
    val message: ChatMessage? = null,
    @SerializedName("finish_reason")
    val finishReason: String? = null
)

data class ParsedWish(
    val title: String,
    val category: String,
    val locationKeyword: String?
)

data class AiDecisionRecommendation(
    val name: String,
    val imageUrl: String?,
    val distanceDescription: String?,
    val tag: String?,
    val intro: String?
)

internal data class ParsedWishDto(
    val title: String? = null,
    val category: String? = null,
    @SerializedName("location_keyword")
    val locationKeyword: String? = null
)

internal data class AiDecisionRecommendationDto(
    val name: String? = null,
    @SerializedName("image_url")
    val imageUrl: String? = null,
    @SerializedName("distance_desc")
    val distanceDescription: String? = null,
    val tag: String? = null,
    val intro: String? = null
)

internal data class AiDecisionRecommendationsDto(
    val candidates: List<AiDecisionRecommendationDto>? = null
)
