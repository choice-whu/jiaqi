package com.example.dateapp.data.remote

import android.util.Log
import java.util.Locale

data class AiChatRequest(
    val systemPrompt: String? = null,
    val prompt: String,
    val maxCompletionTokens: Int,
    val reasoningEffort: String,
    val model: String,
    val temperature: Double = 0.08,
    val presencePenalty: Double? = null,
    val preferJsonResponseFormat: Boolean = true
)

class AiChatClient(
    private val apiService: AiApiService
) {
    suspend fun requestJsonContent(request: AiChatRequest): String {
        val startedAtMillis = System.currentTimeMillis()
        Log.d(
            TAG,
            "ai source=http_start model=${request.model} promptLength=${request.prompt.length} maxTokens=${request.maxCompletionTokens} temperature=${request.temperature} presencePenalty=${request.presencePenalty ?: "none"} jsonMode=${request.preferJsonResponseFormat}"
        )

        val rawBody = try {
            executeChatCompletion(
                request = request,
                useJsonResponseFormat = request.preferJsonResponseFormat
            )
        } catch (throwable: AiHttpException) {
            if (shouldRetryWithoutAdvancedParams(throwable, request)) {
                Log.d(
                    TAG,
                    "ai source=http_retry_legacy code=${throwable.code} reason=${throwable.message?.take(AI_ERROR_BODY_LOG_LIMIT)}"
                )
                executeChatCompletion(
                    request = request.copy(
                        temperature = request.temperature.coerceAtMost(0.7),
                        presencePenalty = null
                    ),
                    useJsonResponseFormat = false
                )
            } else {
                throw throwable
            }
        }

        Log.d(
            TAG,
            "ai source=http_body elapsed=${System.currentTimeMillis() - startedAtMillis}ms rawLength=${rawBody.length}"
        )

        val content = AiJsonSanitizer.extractChatMessageContent(rawBody)
            ?.takeIf { it.isNotBlank() }
            ?: error("AI response did not contain message content: ${rawBody.take(AI_ERROR_BODY_LOG_LIMIT)}")

        Log.d(
            TAG,
            "ai source=http_success elapsed=${System.currentTimeMillis() - startedAtMillis}ms contentLength=${content.length}"
        )

        return AiJsonSanitizer.extractJsonValue(content)
    }

    private suspend fun executeChatCompletion(
        request: AiChatRequest,
        useJsonResponseFormat: Boolean
    ): String {
        val messages = buildList {
            request.systemPrompt
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { add(ChatMessage(role = "system", content = it)) }
            add(ChatMessage(role = "user", content = request.prompt))
        }

        val response = apiService.createChatCompletion(
            ChatRequest(
                model = request.model,
                messages = messages,
                temperature = request.temperature,
                presencePenalty = request.presencePenalty,
                maxTokens = request.maxCompletionTokens,
                responseFormat = if (useJsonResponseFormat) {
                    ChatResponseFormat(type = JSON_RESPONSE_FORMAT_TYPE)
                } else {
                    null
                },
                thinking = if (shouldDisableThinking(request.model)) {
                    ChatThinkingConfig(type = DISABLED_THINKING_TYPE)
                } else {
                    null
                }
            )
        )

        return if (response.isSuccessful) {
            response.body()?.string()
        } else {
            val errorBody = response.errorBody()?.string().orEmpty()
            throw AiHttpException(
                code = response.code(),
                detail = errorBody.take(AI_ERROR_BODY_LOG_LIMIT)
            )
        }
            ?.takeIf { it.isNotBlank() }
            ?: error("AI response body is empty")
    }

    private fun shouldRetryWithoutAdvancedParams(
        throwable: AiHttpException,
        request: AiChatRequest
    ): Boolean {
        return throwable.code in setOf(400, 422) &&
            (request.preferJsonResponseFormat || request.presencePenalty != null)
    }

    private class AiHttpException(
        val code: Int,
        detail: String
    ) : IllegalStateException("AI HTTP $code: $detail")

    private fun shouldDisableThinking(modelName: String): Boolean {
        return modelName.lowercase(Locale.ROOT).startsWith("deepseek")
    }

    private companion object {
        private const val TAG = "AiChatClient"
        private const val JSON_RESPONSE_FORMAT_TYPE = "json_object"
        private const val DISABLED_THINKING_TYPE = "disabled"
        private const val AI_ERROR_BODY_LOG_LIMIT = 400
    }
}
