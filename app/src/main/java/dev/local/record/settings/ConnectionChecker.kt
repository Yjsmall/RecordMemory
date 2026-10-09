package dev.local.record.settings

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ConnectionCheck(val modelIds: List<String>, val message: String)

/** Explicit user-requested metadata check; never sends audio, prompts or text. */
class ConnectionChecker internal constructor(
    private val open: (String) -> HttpsURLConnection = { URL(it).openConnection() as HttpsURLConnection }
) {
    suspend fun check(connection: AiConnection, apiKey: String?): ConnectionCheck = withContext(Dispatchers.IO) {
        validateConnection(connection)
        require(connection.protocol != DOUBAO_ASR) { "豆包语音不提供兼容模型列表，请使用语音配置检查" }
        require(!connection.bearerAuth || !apiKey.isNullOrBlank()) { "此连接需要 API Key，请先填写" }
        require(apiKey == null || (apiKey.length <= 8192 && apiKey.none { it == '\r' || it == '\n' })) { "密钥格式无效" }
        val request = open(endpoint(connection, connection.modelsPath))
        request.connectTimeout = 10_000
        request.readTimeout = 15_000
        request.instanceFollowRedirects = false // Never forward credentials to redirects.
        request.setRequestProperty("Accept", "application/json")
        if (connection.bearerAuth) request.setRequestProperty("Authorization", "Bearer $apiKey")
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { request.disconnect() }
            try {
                val status = request.responseCode
                check(status in 200..299) { httpError(status) }
                val bytes = request.inputStream.use(::readLimited)
                val ids = parseModelIds(bytes.decodeToString())
                continuation.resume(ConnectionCheck(ids, "连接和模型列表可访问（${ids.size} 个模型）。尚未验证转写或文本生成能力。"))
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                request.disconnect()
            }
        }
    }
}

internal fun readLimited(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= MAX_CONFIG_BYTES) { "响应或配置文件不能超过 1 MB" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal fun parseModelIds(source: String): List<String> = try {
    val parsed = Json.parseToJsonElement(source).jsonObject
    parsed.getValue("data").jsonArray.map {
        val id = it.jsonObject.getValue("id").jsonPrimitive
        require(id.isString && id.content.isNotBlank() && id.content.length <= 200)
        id.content
    }.distinct().sorted()
} catch (_: Exception) {
    throw IllegalArgumentException("模型列表格式不兼容，预期 data 数组与 id 字符串")
}

internal fun httpError(code: Int) = when (code) {
    401, 403 -> "鉴权失败（HTTP $code），请检查密钥与访问权限"
    404 -> "找不到请求路径（HTTP 404），请检查接口协议与路径"
    429 -> "请求限流或额度不足（HTTP 429），请稍后重试并检查额度"
    in 300..399 -> "服务返回重定向（HTTP $code），请填写最终 HTTPS 地址"
    in 500..599 -> "服务暂时不可用（HTTP $code）"
    else -> "连接检查失败（HTTP $code）"
}
