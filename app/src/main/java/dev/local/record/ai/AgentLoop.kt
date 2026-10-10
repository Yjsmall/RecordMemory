package dev.local.record.ai

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Only native model calls are dispatched. Receipts are reusable within this attempt. */
internal suspend fun runAgentLoop(
    step: suspend (List<AgentExchange>, Boolean) -> AgentModelReply,
    dispatch: suspend (AgentToolCall) -> String,
    isCurrent: suspend () -> Boolean,
    maxRounds: Int = 3,
    maxCalls: Int = 6,
    maxCharacters: Int = 12_000
): String {
    require(maxRounds in 1..3 && maxCalls in 1..6 && maxCharacters in 1..12_000)
    val exchanges = mutableListOf<AgentExchange>()
    val receipts = mutableMapOf<String, AgentToolResult>()
    var characters = 0
    var exhausted = false
    repeat(maxRounds + 1) { round ->
        coroutineContext.ensureActive()
        check(isCurrent()) { "请求上下文已变化" }
        val allowed = round < maxRounds && !exhausted
        val reply = step(exchanges.toList(), allowed)
        check(isCurrent()) { "请求上下文已变化" }
        if (reply.calls.isEmpty()) {
            require(reply.text.isNotBlank()) { "助手回复为空" }
            return reply.text
        }
        if (!allowed) return reply.text.ifBlank { "本轮回忆已达到上限，请缩小范围后继续。" }
        val results = reply.calls.map { call ->
            check(isCurrent()) { "请求上下文已变化" }
            val previous = receipts[call.id]?.also { previous ->
                require(previous.call == call) { "工具调用 ID 被重复用于不同参数" }
            }
            val remaining = maxCharacters - characters
            val budgetError = "{\"error\":\"本轮工具预算已用完，请使用已有结果回答\"}"
            val result = if (remaining < 1_800 || previous == null && receipts.size >= maxCalls) {
                exhausted = true
                AgentToolResult(call, budgetError)
            } else {
                val output = previous?.output ?: dispatch(call)
                if (output.length > remaining - 600) {
                    exhausted = true
                    AgentToolResult(call, budgetError)
                } else {
                    AgentToolResult(call, output)
                }
            }
            characters += result.output.length
            if (previous == null) receipts[call.id] = result
            result
        }
        exchanges += AgentExchange(reply, results)
        exhausted = exhausted || receipts.size >= maxCalls || characters >= maxCharacters
    }
    return "本轮回忆已达到上限，请缩小范围后继续。"
}
