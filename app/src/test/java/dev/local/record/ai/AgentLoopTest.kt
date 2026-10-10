package dev.local.record.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {
    private fun call(id: String, query: String = "项目") = AgentToolCall(id, "search_memories", buildJsonObject { put("query", query) })

    @Test fun duplicateCallReturnsReceiptWithoutExecutingTwice() = runTest {
        var requests = 0
        var executions = 0
        val result = runAgentLoop(
            step = { exchanges, _ ->
                requests++
                if (requests <= 2) {
                    AgentModelReply("", listOf(call("same")))
                } else {
                    assertEquals(2, exchanges.size)
                    AgentModelReply("根据项目记录回答", emptyList())
                }
            },
            dispatch = {
                executions++
                "项目证据"
            },
            isCurrent = { true }
        )
        assertEquals(1, executions)
        assertEquals("根据项目记录回答", result)
    }

    @Test fun exhaustedBudgetRequestsFinalAnswerWithToolsDisabled() = runTest {
        var calls = 0
        val result = runAgentLoop(
            step = { _, allowed ->
                if (allowed) AgentModelReply("", listOf(call("${++calls}"))) else AgentModelReply("已找到的部分依据", emptyList())
            },
            dispatch = { "证据" },
            isCurrent = { true },
            maxRounds = 1
        )
        assertEquals(1, calls)
        assertEquals("已找到的部分依据", result)
    }

    @Test fun changedCallIdArgumentsAreRejected() = runTest {
        var requests = 0
        val result = runCatching {
            runAgentLoop(
                step = { _, _ -> AgentModelReply("", listOf(call("same", if (++requests == 1) "项目" else "偏好"))) },
                dispatch = { "证据" },
                isCurrent = { true }
            )
        }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun withdrawnContextStopsBeforeToolExecution() = runTest {
        var valid = true
        var executions = 0
        val result = runCatching {
            runAgentLoop(
                step = { _, _ ->
                    valid = false
                    AgentModelReply("", listOf(call("one")))
                },
                dispatch = {
                    executions++
                    "证据"
                },
                isCurrent = { valid }
            )
        }
        assertTrue(result.isFailure)
        assertEquals(0, executions)
    }

    @Test fun repeatedLargeReceiptsCountTowardTheWireBudget() = runTest {
        var executions = 0
        var requests = 0
        val result = runAgentLoop(
            step = { exchanges, allowed ->
                requests++
                assertTrue(exchanges.sumOf { it.results.sumOf { result -> result.output.length } } <= 12_000)
                if (allowed) AgentModelReply("", listOf(call("same"))) else AgentModelReply("依据已有结果回答", emptyList())
            },
            dispatch = {
                executions++
                "证".repeat(6_000)
            },
            isCurrent = { true }
        )
        assertEquals(1, executions)
        assertEquals(3, requests)
        assertEquals("依据已有结果回答", result)
    }
}
