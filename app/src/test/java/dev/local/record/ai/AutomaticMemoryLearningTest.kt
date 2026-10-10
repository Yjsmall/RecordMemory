package dev.local.record.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutomaticMemoryLearningTest {
    @Test fun startupAndAuthorizationChangesNeverScheduleHistoricalSources() = runTest {
        val enabled = MutableStateFlow(false)
        val requests = mutableListOf<String>()
        val learning = AutomaticMemoryLearning(enabled, {
            requests += it
            null
        }, {}, backgroundScope)
        learning.start()
        runCurrent()
        learning.onAnswered("disabled-source")
        runCurrent()
        enabled.value = true
        runCurrent()
        assertTrue(requests.isEmpty())
        learning.onAnswered("current-source")
        runCurrent()
        assertEquals(listOf("current-source"), requests)
    }

    @Test fun liveCallbacksPersistEveryDistinctSourceAndLeavePaidBudgetToExecutor() = runTest {
        val enabled = MutableStateFlow(true)
        val attempts = mutableListOf<Pair<String, CompletableJob>>()
        val learning = AutomaticMemoryLearning(enabled, { id -> Job().also { attempts += id to it } }, {}, backgroundScope)
        learning.start()
        runCurrent()
        repeat(8) { learning.onAnswered("same-source") }
        repeat(8) { learning.onAnswered("source-$it") }
        runCurrent()
        assertEquals(listOf("same-source") + List(8) { "source-$it" }, attempts.map { it.first })
        attempts.forEach { it.second.complete() }
        runCurrent()
        learning.onAnswered("later-source")
        runCurrent()
        assertEquals("later-source", attempts.last().first)
        attempts.last().second.complete()
    }

    @Test fun disableCancelsCurrentAdmissionAndAllowsOnlyFreshCallbacksAfterReenable() = runTest {
        val enabled = MutableStateFlow(true)
        val entered = CompletableDeferred<Unit>()
        val pending = CompletableDeferred<Job?>()
        var cancelled = false
        val learning = AutomaticMemoryLearning(enabled, {
            entered.complete(Unit)
            pending.await()
        }, { cancelled = true }, backgroundScope)
        learning.start()
        runCurrent()
        learning.onAnswered("source")
        runCurrent()
        assertTrue(entered.isCompleted)
        enabled.value = false
        runCurrent()
        assertTrue(cancelled)
        assertFalse(pending.isCompleted)
        enabled.value = true
        runCurrent()
        assertFalse(pending.isCompleted)
    }
}
