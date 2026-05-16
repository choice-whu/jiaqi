package com.example.dateapp.data.remote

import com.example.dateapp.data.remote.AiJsonSanitizer.getStringOrNull
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class AiJsonSanitizerTest {

    @Test
    fun extractChatMessageContent_withMessage_returnsContent() {
        val body = """
            {"choices":[{"message":{"content":"你好"}}]}
        """.trimIndent()
        assertEquals("你好", AiJsonSanitizer.extractChatMessageContent(body))
    }

    @Test
    fun extractChatMessageContent_withDelta_returnsContent() {
        val body = """
            {"choices":[{"delta":{"content":"增量内容"}}]}
        """.trimIndent()
        assertEquals("增量内容", AiJsonSanitizer.extractChatMessageContent(body))
    }

    @Test
    fun extractChatMessageContent_withText_returnsContent() {
        val body = """
            {"choices":[{"text":"直接文本"}]}
        """.trimIndent()
        assertEquals("直接文本", AiJsonSanitizer.extractChatMessageContent(body))
    }

    @Test
    fun extractChatMessageContent_emptyChoices_returnsNull() {
        val body = """{"choices":[]}"""
        assertNull(AiJsonSanitizer.extractChatMessageContent(body))
    }

    @Test
    fun extractJsonValue_nakedJsonObject_returnsContent() {
        val result = AiJsonSanitizer.extractJsonValue("""{"name":"测试"}""")
        assertEquals("""{"name":"测试"}""", result)
    }

    @Test
    fun extractJsonValue_nakedJsonArray_returnsContent() {
        val result = AiJsonSanitizer.extractJsonValue("""[{"name":"测试"}]""")
        assertEquals("""[{"name":"测试"}]""", result)
    }

    @Test
    fun extractJsonValue_withFencedJson_returnsContent() {
        val content = """
            这是回答
            ```json
            {"display_name":"咖啡馆"}
            ```
        """.trimIndent()
        val result = AiJsonSanitizer.extractJsonValue(content)
        assertEquals("""{"display_name":"咖啡馆"}""", result)
    }

    @Test
    fun extractJsonValue_withFencedNoLanguage_returnsContent() {
        val content = """
            ```
            {"display_name":"茶馆"}
            ```
        """.trimIndent()
        val result = AiJsonSanitizer.extractJsonValue(content)
        assertEquals("""{"display_name":"茶馆"}""", result)
    }

    @Test
    fun extractJsonValue_prefersNakedOverFenced() {
        val content = """
            {"display_name":"优先这个"}
            其他内容
            ```json
            {"display_name":"不要这个"}
            ```
        """.trimIndent()
        val result = AiJsonSanitizer.extractJsonValue(content)
        // The first balanced JSON found wins
        assertEquals("""{"display_name":"优先这个"}""", result)
    }

    @Test(expected = IllegalStateException::class)
    fun extractJsonValue_noJson_throws() {
        AiJsonSanitizer.extractJsonValue("这是一段没有JSON的文本")
    }

    @Test
    fun extractJsonValue_withNestedBraces_returnsOuter() {
        val content = """
            {"outer":{"inner":"value"}}
        """.trimIndent()
        val result = AiJsonSanitizer.extractJsonValue(content)
        assertEquals("""{"outer":{"inner":"value"}}""", result)
    }

    @Test
    fun getStringOrNull_withPrimitive_returnsString() {
        val json = JsonParser.parseString("""{"key":"value"}""").asJsonObject
        assertEquals("value", json.getStringOrNull("key"))
    }

    @Test
    fun getStringOrNull_missingKey_returnsNull() {
        val json = JsonParser.parseString("""{"key":"value"}""").asJsonObject
        assertNull(json.getStringOrNull("missing"))
    }
}
