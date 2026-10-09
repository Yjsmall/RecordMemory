package dev.local.record.settings

import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Explicit diagnostic uses fixed synthetic text, never a recording or a user's prompt. */
class TextModelChecker internal constructor(
    private val open: (String) -> HttpsURLConnection = { URL(it).openConnection() as HttpsURLConnection }
) {
    suspend fun check(connection: AiConnection, binding: CapabilityBinding, apiKey: String?): String = withContext(Dispatchers.IO) {
        validateConnection(connection)
        require(connection.supports(binding.capability) && binding.capability != AiCapability.ASR) { "请选择文本模型 provider" }
        require(binding.model.isNotBlank() && binding.model.length <= 200) { "请填写模型名" }
        require(!connection.bearerAuth || !apiKey.isNullOrBlank()) { "请先在 provider 中保存 API Key" }
        require(apiKey == null || apiKey.none { it == '\r' || it == '\n' }) { "密钥格式无效" }
        val path = if (connection.protocol == RESPONSES) connection.responsesPath else connection.chatPath
        val request = open(endpoint(connection, path)).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (connection.bearerAuth) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { request.disconnect() }
            try {
                val body = modelDiagnosticBody(connection, binding).toByteArray()
                request.setFixedLengthStreamingMode(body.size)
                request.outputStream.use { it.write(body) }
                val status = request.responseCode
                check(status in 200..299) { httpError(status) }
                val source = request.inputStream.use(::readLimited).decodeToString()
                parseDiagnosticOutput(connection.protocol, source)
                continuation.resume("所选模型已通过 ${if (connection.protocol == RESPONSES) "Responses" else "Chat Completions"} 文本测试。尚未验证真实转写、标题或总结的质量。")
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                request.disconnect()
            }
        }
    }
}

internal fun modelDiagnosticBody(connection: AiConnection, binding: CapabilityBinding): String = buildJsonObject {
    put("model", binding.model.trim())
    put("stream", false)
    if (connection.protocol == RESPONSES) {
        put("input", "Connection test. Reply with OK only.")
        put("instructions", "This is a connection diagnostic using synthetic text. Reply with OK only.")
        put("store", false)
        put("max_output_tokens", 512)
        if (binding.reasoningEffort.isNotEmpty()) putJsonObject("reasoning") { put("effort", binding.reasoningEffort) }
    } else {
        put(
            "messages",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("role", "user")
                        put("content", "Connection test. Reply with OK only.")
                    }
                )
            )
        )
        put("max_tokens", 512)
    }
}.toString()

/** Read final user-visible text, excluding reasoning and tool-call items. */
internal fun parseDiagnosticOutput(protocol: String, source: String): String {
    val parsed = try {
        Json.parseToJsonElement(source).jsonObject
    } catch (_: Exception) {
        throw IllegalArgumentException("模型响应不是兼容的 JSON")
    }
    return try {
        if (protocol == RESPONSES) {
            require(parsed["status"]?.jsonPrimitive?.content == "completed") { "模型返回未完成或失败，请调整推理强度后重试" }
            parsed.getValue("output").jsonArray.flatMap { item ->
                val output = item.jsonObject
                if (output["type"]?.jsonPrimitive?.content == "message") output.getValue("content").jsonArray else emptyList()
            }.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "output_text" }
                .joinToString("\n") { it.jsonObject.getValue("text").jsonPrimitive.content }
        } else {
            val choice = parsed.getValue("choices").jsonArray.first().jsonObject
            require(choice["finish_reason"]?.jsonPrimitive?.content == "stop") { "模型返回未完成，请检查模型及接口协议" }
            choice.getValue("message").jsonObject.getValue("content").jsonPrimitive.content
        }.also { require(it.isNotBlank() && it != "null") { "模型没有返回可用文本，请检查模型及接口协议" } }
    } catch (error: IllegalArgumentException) {
        // Only our own status errors should be displayed, never raw response fragments.
        if (error.message?.startsWith("模型") == true) throw error
        throw IllegalArgumentException("模型响应结构与所选协议不兼容")
    } catch (_: Exception) {
        throw IllegalArgumentException("模型响应结构与所选协议不兼容")
    }
}
