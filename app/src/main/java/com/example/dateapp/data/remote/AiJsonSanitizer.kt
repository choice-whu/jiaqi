package com.example.dateapp.data.remote

import com.google.gson.JsonObject
import com.google.gson.JsonParser

object AiJsonSanitizer {
    fun extractChatMessageContent(rawBody: String): String? {
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

    fun extractJsonValue(rawContent: String): String {
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

        return candidates.firstNotNullOfOrNull(::findBalancedJsonValue)
            ?: error("AI response did not contain a JSON object or array")
    }

    fun JsonObject.getAsJsonObjectOrNull(memberName: String): JsonObject? {
        val element = get(memberName) ?: return null
        return if (element.isJsonObject) element.asJsonObject else null
    }

    fun JsonObject.getStringOrNull(memberName: String): String? {
        val element = get(memberName) ?: return null
        return when {
            element.isJsonPrimitive -> element.asString
            element.isJsonObject || element.isJsonArray -> element.toString()
            else -> null
        }
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
}
