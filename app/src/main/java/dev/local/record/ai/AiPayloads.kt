package dev.local.record.ai

import dev.local.record.domain.MemoryKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class ParsedMemory(val type: MemoryKind, val text: String, val evidence: String)

private val payloadJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class MemoryDocument(val items: List<MemoryDraft> = emptyList())

@Serializable
private data class MemoryDraft(val type: String = "", val text: String = "", val evidence: String = "")

fun parseMemoryItems(source: String): List<ParsedMemory> {
    val document = try {
        payloadJson.decodeFromString<MemoryDocument>(extractJsonObject(source))
    } catch (_: Exception) {
        throw IllegalArgumentException("模型没有返回可解析的记忆 JSON")
    }
    return document.items.mapNotNull { item ->
        val kind = memoryKind(item.type) ?: return@mapNotNull null
        val text = item.text.trim()
        if (text.isBlank()) return@mapNotNull null
        ParsedMemory(kind, text.take(500), item.evidence.trim().take(200))
    }
}

fun parseOpenAiTranscript(source: String): String = try {
    val text = payloadJson.parseToJsonElement(source).jsonObject.getValue("text").jsonPrimitive.content.trim()
    require(text.isNotBlank()) { "转写结果为空" }
    text
} catch (error: IllegalArgumentException) {
    throw error
} catch (_: Exception) {
    throw IllegalArgumentException("转写响应结构不兼容")
}

/** Doubao reports success in a header. The body is accepted only for that status. */
fun parseDoubaoTranscript(source: String, statusCode: String?): String {
    require(statusCode == null || statusCode == "20000000") { "豆包识别失败（$statusCode）" }
    return try {
        val result = payloadJson.parseToJsonElement(source).jsonObject.getValue("result").jsonObject
        val text = result.getValue("text").jsonPrimitive.content.trim()
        require(text.isNotBlank()) { "转写结果为空" }
        text
    } catch (error: IllegalArgumentException) {
        if (error.message == "转写结果为空") throw error
        throw IllegalArgumentException("豆包识别响应结构不兼容")
    } catch (_: Exception) {
        throw IllegalArgumentException("豆包识别响应结构不兼容")
    }
}

internal fun extractJsonObject(source: String): String {
    val fenced = Regex("```(?:json)?\\s*(\\{.*\\})\\s*```", RegexOption.DOT_MATCHES_ALL).find(source)
    val raw = fenced?.groupValues?.get(1) ?: source
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    require(start >= 0 && end > start) { "模型没有返回可解析的记忆 JSON" }
    return raw.substring(start, end + 1)
}

private fun memoryKind(type: String): MemoryKind? = when (type.trim().lowercase()) {
    "person", "人物" -> MemoryKind.PERSON
    "project", "项目" -> MemoryKind.PROJECT
    "preference", "偏好" -> MemoryKind.PREFERENCE
    "agreement", "约定" -> MemoryKind.AGREEMENT
    "todo", "待办" -> MemoryKind.TODO
    "idea", "想法" -> MemoryKind.IDEA
    else -> null
}

fun doubaoBody(audioBase64: String, model: String, language: String): String = buildJsonObject {
    putJsonObject("user") { put("uid", "record-memory") }
    putJsonObject("audio") {
        put("format", "wav")
        put("data", audioBase64)
    }
    putJsonObject("request") {
        put("model_name", model.ifBlank { "bigmodel" })
        put("enable_itn", true)
        put("enable_punc", true)
        put("show_utterances", false)
        put("language", if (language.equals("zh", ignoreCase = true)) "zh-CN" else language)
    }
}.toString()

fun openAiMultipart(boundary: String, model: String, language: String, prompt: String, fileName: String, mime: String, audio: ByteArray): ByteArray {
    val builder = StringBuilder()
    fun field(name: String, value: String) {
        builder.append("--").append(boundary).append("\r\n")
        builder.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
        builder.append(value).append("\r\n")
    }
    field("model", model)
    if (language.isNotBlank()) field("language", language)
    if (prompt.isNotBlank()) field("prompt", prompt.take(200))
    field("response_format", "json")
    builder.append("--").append(boundary).append("\r\n")
    builder.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\n")
    builder.append("Content-Type: ").append(mime).append("\r\n\r\n")
    val prefix = builder.toString().toByteArray()
    val suffix = "\r\n--$boundary--\r\n".toByteArray()
    return prefix + audio + suffix
}
