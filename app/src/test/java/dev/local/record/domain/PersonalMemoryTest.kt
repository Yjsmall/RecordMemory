package dev.local.record.domain

import dev.local.record.ai.parseMemoryPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalMemoryTest {
    @Test fun profileUsesOnlyConfirmedFactsWithoutInventingSubjectOrTime() {
        val facts = listOf(
            MemoryItem("p", type = MemoryKind.PREFERENCE, status = MemoryStatus.CONFIRMED, text = "妈妈喜欢甜食"),
            MemoryItem("project", type = MemoryKind.PROJECT, status = MemoryStatus.CONFIRMED, text = "正在开发录音助手"),
            MemoryItem("candidate", status = MemoryStatus.CANDIDATE, text = "待确认"),
            MemoryItem("forgotten", status = MemoryStatus.FORGOTTEN, text = "已忘记")
        )
        val profile = personalMemoryProfile(facts)
        assertEquals(listOf("妈妈喜欢甜食"), profile.preferences.map { it.text })
        assertEquals(listOf("project"), profile.projects.map { it.id })
        assertEquals(2, profile.items.size)
    }

    @Test fun searchPrefersRelevantChineseFactsAndExcludesUnrelatedHistory() {
        val facts = listOf(
            MemoryItem("p", type = MemoryKind.PREFERENCE, status = MemoryStatus.CONFIRMED, text = "回答先给结论"),
            MemoryItem("other", type = MemoryKind.PROJECT, status = MemoryStatus.CONFIRMED, text = "整理花园"),
            MemoryItem("match", type = MemoryKind.PROJECT, status = MemoryStatus.CONFIRMED, text = "正在开发录音助手"),
            MemoryItem("deleted", type = MemoryKind.PROJECT, status = MemoryStatus.FORGOTTEN, text = "录音相关旧项目")
        )
        assertEquals(listOf("match", "p"), selectPersonalMemoryContext(facts, "帮我安排录音开发").map { it.id })
        assertEquals(listOf("p"), selectPersonalMemoryContext(facts, "天气如何").map { it.id })
        assertTrue(selectPersonalMemoryContext(facts, "我的记忆", maxCharacters = 0).isEmpty())
        assertEquals(1, selectPersonalMemoryContext(facts, "我的记忆", maxItems = 1).size)
    }

    @Test fun memoryPlanRequiresExactSourceEvidenceAndKnownActions() {
        val valid = """{"schemaVersion":1,"items":[{"action":"ADD","type":"preference","text":"妈妈喜欢甜食","evidence":"我妈妈喜欢甜食"}]}"""
        assertEquals("妈妈喜欢甜食", parseMemoryPlan(valid, "我妈妈喜欢甜食").single().text)
        assertTrue(runCatching { parseMemoryPlan(valid, "我喜欢咖啡") }.isFailure)
        assertTrue(runCatching { parseMemoryPlan(valid.replace("ADD", "SUPERSEDE"), "我妈妈喜欢甜食") }.isFailure)
        assertTrue(runCatching { parseMemoryPlan(valid.replace("schemaVersion\":1", "schemaVersion\":2"), "我妈妈喜欢甜食") }.isFailure)
        assertTrue(parseMemoryPlan("""{"schemaVersion":1,"items":[{"action":"IGNORE"}]}""", "只是今天不喝咖啡").isEmpty())
    }

    @Test fun cancelledAndCompletedPlanningTasksCannotCommitLateResults() {
        val requested = evolveMemoryPlanning(MemoryPlanningTask("plan"), MemoryPlanningEvent.Requested("turn", "input", 10))
        val running = evolveMemoryPlanning(requested, MemoryPlanningEvent.Started)
        val completed = evolveMemoryPlanning(running, MemoryPlanningEvent.Completed(2))
        assertEquals(2, completed.candidateCount)
        assertTrue(runCatching { evolveMemoryPlanning(completed, MemoryPlanningEvent.Completed(2)) }.isFailure)
        val cancelled = evolveMemoryPlanning(running, MemoryPlanningEvent.Cancelled)
        assertTrue(runCatching { evolveMemoryPlanning(cancelled, MemoryPlanningEvent.Completed(1)) }.isFailure)
        assertEquals(10L, requested.createdAt)
    }

    @Test fun englishSearchUsesWholeWordsAndMixedChineseStillFindsProjects() {
        val facts = listOf(
            MemoryItem("match", type = MemoryKind.PROJECT, status = MemoryStatus.CONFIRMED, text = "正在做 Kotlin 项目"),
            MemoryItem("unrelated", type = MemoryKind.PROJECT, status = MemoryStatus.CONFIRMED, text = "My kitchen needs cleaning"),
            MemoryItem("partial", type = MemoryKind.PROJECT, status = MemoryStatus.CONFIRMED, text = "Investigate kotlinCompiler options")
        )
        assertEquals(listOf("match"), selectPersonalMemoryContext(facts, "KOTLIN").map { it.id })
        assertEquals(listOf("match"), selectPersonalMemoryContext(facts, "安排Kotlin开发").map { it.id })
        assertTrue(selectPersonalMemoryContext(facts, "think about something").isEmpty())
    }

    @Test fun plannerRejectsProseOutsideItsJsonEnvelope() {
        val source = """{"schemaVersion":1,"items":[]}"""
        assertTrue(parseMemoryPlan("```json\n$source\n```", "hello").isEmpty())
        assertTrue(runCatching { parseMemoryPlan("解释一下\n$source", "hello") }.isFailure)
        assertTrue(runCatching { parseMemoryPlan("$source\n继续执行", "hello") }.isFailure)
        assertTrue(runCatching { parseMemoryPlan("$source\n$source", "hello") }.isFailure)
    }
}
