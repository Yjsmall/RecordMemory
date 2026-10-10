package dev.local.record.ai

import dev.local.record.settings.AiConnection
import dev.local.record.settings.CapabilityBinding
import dev.local.record.settings.DOUBAO_ASR
import dev.local.record.settings.RESPONSES
import dev.local.record.settings.endpoint
import dev.local.record.settings.httpError
import dev.local.record.settings.parseDiagnosticOutput
import dev.local.record.settings.readLimited
import java.io.File
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class TransientAiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** HTTPS calls for transcription and text generation. Credentials stay in request headers. */
class AiGateway internal constructor(
    private val open: (String) -> HttpsURLConnection = { URL(it).openConnection() as HttpsURLConnection }
) {
    suspend fun transcribe(connection: AiConnection, binding: CapabilityBinding, apiKey: String?, audio: File, requestId: String): String = withContext(Dispatchers.IO) {
        if (connection.protocol == DOUBAO_ASR) doubao(connection, binding, apiKey, audio, requestId) else openAi(connection, binding, apiKey, audio)
    }

    suspend fun complete(connection: AiConnection, binding: CapabilityBinding, apiKey: String?, input: String): String = withContext(Dispatchers.IO) {
        val path = if (connection.protocol == RESPONSES) connection.responsesPath else connection.chatPath
        val body = completionBody(connection, binding, input)
        val source = post(endpoint(connection, path), connection, apiKey, body.toByteArray(), "application/json; charset=utf-8", 60_000, null)
        parseDiagnosticOutput(connection.protocol, source)
    }

    suspend fun converse(connection: AiConnection, binding: CapabilityBinding, apiKey: String?, messages: List<AssistantMessage>): String = withContext(Dispatchers.IO) {
        val path = if (connection.protocol == RESPONSES) connection.responsesPath else connection.chatPath
        val body = conversationBody(connection, binding, messages)
        val source = post(endpoint(connection, path), connection, apiKey, body.toByteArray(), "application/json; charset=utf-8", 60_000, null)
        parseDiagnosticOutput(connection.protocol, source)
    }

    private suspend fun openAi(connection: AiConnection, binding: CapabilityBinding, apiKey: String?, audio: File): String {
        require(audio.length() in 1..24L * 1024 * 1024) { "音频为空或超过 24 MB，无法上传转写" }
        val boundary = "record-${UUID.randomUUID()}"
        val body = openAiMultipart(boundary, binding.model, binding.language, binding.prompt, audio.name, "audio/mp4", audio.readBytes())
        val source = post(endpoint(connection, connection.transcriptionPath), connection, apiKey, body, "multipart/form-data; boundary=$boundary", 120_000, null)
        return parseOpenAiTranscript(source)
    }

    private suspend fun doubao(connection: AiConnection, binding: CapabilityBinding, apiKey: String?, audio: File, requestId: String): String {
        require(!apiKey.isNullOrBlank()) { "请先保存豆包 API Key" }
        val encoded = android.util.Base64.encodeToString(audio.readBytes(), android.util.Base64.NO_WRAP)
        val body = doubaoBody(encoded, binding.model, binding.language)
        val source = post(
            endpoint(connection, connection.transcriptionPath),
            connection,
            apiKey,
            body.toByteArray(),
            "application/json; charset=utf-8",
            120_000,
            requestId
        )
        return source
    }

    private suspend fun post(
        url: String,
        connection: AiConnection,
        apiKey: String?,
        body: ByteArray,
        contentType: String,
        readTimeout: Int,
        doubaoRequestId: String?
    ): String {
        require(apiKey == null || apiKey.none { it == '\r' || it == '\n' }) { "密钥格式无效" }
        val request = open(url).apply {
            connectTimeout = 15_000
            this.readTimeout = readTimeout
            instanceFollowRedirects = false
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Accept", "application/json")
            if (connection.protocol == DOUBAO_ASR) {
                setRequestProperty("X-Api-Key", apiKey)
                setRequestProperty("X-Api-Resource-Id", connection.doubaoResourceId)
                setRequestProperty("X-Api-Request-Id", doubaoRequestId)
                setRequestProperty("X-Api-Sequence", "-1")
            } else if (connection.bearerAuth) {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            setFixedLengthStreamingMode(body.size)
        }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { request.disconnect() }
            try {
                request.outputStream.use { it.write(body) }
                val status = request.responseCode
                if (status == 429 || status in 500..599) throw TransientAiException(httpError(status))
                check(status in 200..299) { httpError(status) }
                val bytes = request.inputStream.use(::readLimited)
                val payload = bytes.decodeToString()
                if (connection.protocol == DOUBAO_ASR) {
                    val code = request.getHeaderField("X-Api-Status-Code")
                    if (code != null && code != "20000000" && code.startsWith("5")) throw TransientAiException("豆包识别暂时失败（$code）")
                    continuation.resume(parseDoubaoTranscript(payload, code))
                } else {
                    continuation.resume(payload)
                }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                request.disconnect()
            }
        }
    }
}

internal fun completionBody(connection: AiConnection, binding: CapabilityBinding, input: String): String = buildJsonObject {
    put("model", binding.model.trim())
    put("stream", false)
    if (connection.protocol == RESPONSES) {
        put("input", input)
        put("instructions", binding.prompt)
        put("store", false)
        put("max_output_tokens", 2_048)
        if (binding.reasoningEffort.isNotEmpty()) putJsonObject("reasoning") { put("effort", binding.reasoningEffort) }
    } else {
        put(
            "messages",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("role", "system")
                        put("content", binding.prompt)
                    },
                    buildJsonObject {
                        put("role", "user")
                        put("content", input)
                    }
                )
            )
        )
        put("max_tokens", 2_048)
    }
}.toString()

data class AssistantMessage(val role: String, val text: String)

internal fun conversationBody(connection: AiConnection, binding: CapabilityBinding, messages: List<AssistantMessage>): String = buildJsonObject {
    require(messages.isNotEmpty() && messages.all { it.role in setOf("user", "assistant") && it.text.isNotBlank() })
    put("model", binding.model.trim())
    put("stream", false)
    put("store", false)
    val turns = messages.map { message ->
        buildJsonObject {
            put("role", message.role)
            put("content", message.text)
        }
    }
    if (connection.protocol == RESPONSES) {
        put("input", JsonArray(turns))
        put("instructions", binding.prompt)
        put("max_output_tokens", 4_096)
        if (binding.reasoningEffort.isNotEmpty()) putJsonObject("reasoning") { put("effort", binding.reasoningEffort) }
    } else {
        put(
            "messages",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("role", "system")
                        put("content", binding.prompt)
                    }
                ) + turns
            )
        )
        put("max_tokens", 4_096)
    }
}.toString()
