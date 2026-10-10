package dev.local.record.ai

import dev.local.record.domain.MemoryAction
import dev.local.record.domain.MemoryChange
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

@Serializable
internal data class CapturedMemoryOutput(
    val caseId: String,
    val output: String,
    val calls: Int,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null
)

internal data class CapturedMemoryEvaluation(val score: MemoryQualityScore, val parseFailures: Int)

/** Offline only: parse actual model response bodies with the production parser and apply the gate. */
internal fun scoreCapturedMemoryOutputs(input: String, cases: List<SyntheticMemoryCase>): CapturedMemoryEvaluation {
    val captures = Json.decodeFromString<List<CapturedMemoryOutput>>(input)
    require(captures.map { it.caseId }.toSet() == cases.map { it.id }.toSet()) { "Every corpus case needs one captured response, including empty or failed responses" }
    var parseFailures = 0
    val observations = captures.map { capture ->
        val case = cases.first { it.id == capture.caseId }
        val drafts = runCatching { parseMemoryPlan(capture.output, case.source, allowLegacy = false) }.getOrElse {
            parseFailures++
            emptyList()
        }
        MemoryQualityObservation(capture.caseId, drafts.filter { AutomaticMemoryPolicy.eligible(it, case.source) }, capture.calls, capture.inputTokens, capture.outputTokens)
    }
    return CapturedMemoryEvaluation(scoreMemoryQuality(cases, observations), parseFailures)
}

class MemoryQualityEvaluationTest {
    @Test fun policyCorpusReportsFalseSavesMissesOverwritesAndRecallSeparately() {
        val observations = SyntheticMemoryCorpus.cases.map { case ->
            MemoryQualityObservation(case.id, listOf(case.candidate).filter { AutomaticMemoryPolicy.eligible(it, case.source) })
        }
        val score = scoreMemoryQuality(SyntheticMemoryCorpus.cases, observations)
        assertEquals(0, score.falseSaves)
        assertEquals(3, score.misses)
        assertEquals(0, score.wrongOverwrites)
        assertEquals(8, score.matched)
        assertEquals(11, score.expected)
        assertEquals(0.72727, score.recall, 0.0001)
        assertEquals(0, score.calls)
        assertNull(score.inputTokens)
        assertNull(score.outputTokens)
        println("SYNTHETIC_POLICY cases=${SyntheticMemoryCorpus.cases.size} $score recall=${score.recall}")
    }

    @Test fun scorerDetectsUnsafeSavesWrongIdentityDuplicatesAndOverwrites() {
        val cases = SyntheticMemoryCorpus.cases.filter { it.id in setOf("coffee", "quote", "tea") }
        val coffee = preference("我喜欢喝咖啡")
        val observations = listOf(
            MemoryQualityObservation("coffee", listOf(coffee, coffee), calls = 1, inputTokens = 12, outputTokens = 8),
            MemoryQualityObservation("quote", listOf(coffee.copy(change = MemoryChange(MemoryAction.SUPERSEDE, "old", 2))), calls = 1, inputTokens = 13, outputTokens = 9),
            MemoryQualityObservation("tea", listOf(preference("我喜欢喝茶", "drink.coffee")), calls = 1, inputTokens = 14, outputTokens = 10)
        )
        val score = scoreMemoryQuality(cases, observations)
        assertEquals(3, score.falseSaves)
        assertEquals(1, score.misses)
        assertEquals(1, score.wrongOverwrites)
        assertEquals(0.5, score.recall, 0.0001)
        assertEquals(3, score.calls)
        assertEquals(39L, score.inputTokens)
        assertEquals(27L, score.outputTokens)
    }

    @Test fun capturedOutputScorerUsesParserAndPolicyAndCountsMalformedPaidResponses() {
        val cases = SyntheticMemoryCorpus.cases.filter { it.id in setOf("coffee", "quote", "tea") }
        // Deliberately invented response fixtures; no real provider quality is claimed.
        val input = """[
            {"caseId":"coffee","output":"{\"schemaVersion\":2,\"items\":[{\"action\":\"ADD\",\"type\":\"preference\",\"text\":\"我喜欢喝咖啡\",\"evidence\":\"我喜欢喝咖啡\",\"fact\":{\"subject\":\"self\",\"predicate\":\"drink.coffee\"}}]}","calls":1,"inputTokens":12,"outputTokens":8},
            {"caseId":"quote","output":"{\"schemaVersion\":2,\"items\":[{\"action\":\"ADD\",\"type\":\"preference\",\"text\":\"我喜欢喝咖啡\",\"evidence\":\"我喜欢喝咖啡\",\"fact\":{\"subject\":\"self\",\"predicate\":\"drink.coffee\"}}]}","calls":1},
            {"caseId":"tea","output":"invalid response","calls":1}
        ]"""
        val evaluation = scoreCapturedMemoryOutputs(input, cases)
        assertEquals(1, evaluation.parseFailures)
        assertEquals(0, evaluation.score.falseSaves)
        assertEquals(1, evaluation.score.misses)
        assertEquals(0, evaluation.score.wrongOverwrites)
        assertEquals(3, evaluation.score.calls)
        assertNull(evaluation.score.inputTokens)
        assertNull(evaluation.score.outputTokens)
    }

    @Test fun scorerRejectsIncompleteDuplicatedAndUnknownCaptureIds() {
        val cases = SyntheticMemoryCorpus.cases.take(1)
        assertEquals(true, runCatching { scoreCapturedMemoryOutputs("[]", cases) }.isFailure)
        assertEquals(true, runCatching { scoreMemoryQuality(cases, listOf(MemoryQualityObservation("unknown", emptyList()))) }.isFailure)
        val observation = MemoryQualityObservation(cases.single().id, emptyList())
        assertEquals(true, runCatching { scoreMemoryQuality(cases, listOf(observation, observation)) }.isFailure)
    }

    @Test fun exportSyntheticEvaluationInputsWhenRequested() {
        val outputPath = System.getenv("RECORD_MEMORY_EVAL_CASES").orEmpty()
        assumeTrue("Set RECORD_MEMORY_EVAL_CASES to export non-private corpus inputs", outputPath.isNotBlank())
        val values = SyntheticMemoryCorpus.dialogueCases.map { EvaluationInput(it.id, it.source) }
        File(outputPath).writeText(Json.encodeToString(values), Charsets.UTF_8)
        println("SYNTHETIC_DIALOGUE_EXPORT cases=${values.size}")
    }

    @Test fun scoreActualCapturedProviderOutputsWhenRequested() {
        val inputPath = System.getenv("RECORD_MEMORY_EVAL_OUTPUTS").orEmpty()
        assumeTrue("Set RECORD_MEMORY_EVAL_OUTPUTS to score captured provider outputs offline", inputPath.isNotBlank())
        val evaluation = scoreCapturedMemoryOutputs(File(inputPath).readText(Charsets.UTF_8), SyntheticMemoryCorpus.dialogueCases)
        println("CAPTURED_PROVIDER_AFTER_POLICY cases=${SyntheticMemoryCorpus.dialogueCases.size} $evaluation recall=${evaluation.score.recall}")
    }
}

@Serializable
private data class EvaluationInput(val caseId: String, val source: String)
