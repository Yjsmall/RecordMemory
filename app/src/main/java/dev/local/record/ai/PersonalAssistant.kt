package dev.local.record.ai

import dev.local.record.data.ConversationRepository
import dev.local.record.data.MemoryDraft
import dev.local.record.data.ProcessingRepository
import dev.local.record.domain.AssistantContext
import dev.local.record.domain.MemoryReference
import dev.local.record.domain.MemoryStatus
import dev.local.record.domain.TurnStatus
import dev.local.record.settings.AiCapability
import dev.local.record.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Process-owned requests survive Activity recreation. Interrupted requests require manual retry. */
class PersonalAssistant(
    private val conversations: ConversationRepository,
    private val processing: ProcessingRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val gateway: AiGateway = AiGateway()
) {
    private val active = java.util.concurrent.ConcurrentHashMap<String, Job>()

    fun respond(turnId: String) {
        synchronized(active) {
            if (active[turnId]?.isActive == true) return
            active[turnId] = scope.launch(start = CoroutineStart.LAZY) { execute(turnId) }.also { it.start() }
        }
    }

    private suspend fun execute(turnId: String) {
        var attempt = conversations.turn(turnId)?.attempt ?: return
        try {
            val turn = conversations.turn(turnId) ?: return
            val privateSettings = settings.settings.first()
            val binding = privateSettings.configuration.binding(AiCapability.ANSWER)
            val connection = privateSettings.configuration.connections.firstOrNull { it.id == binding.connectionId }
            require(connection != null && connection.supports(AiCapability.ANSWER) && binding.model.isNotBlank())
            val key = privateSettings.apiKeys[connection.id]
            require(!connection.bearerAuth || !key.isNullOrBlank())
            val allMemories = processing.memories()
            val memories = selectAssistantMemories(allMemories, turn.userText)
            val history = conversations.turns(turn.conversationId).filter { prior ->
                if (prior.sequence >= turn.sequence || prior.status != TurnStatus.ANSWERED) return@filter false
                val sources = allMemories.filter { it.sourceTurnId == prior.id }
                if (sources.any { !it.visible || it.version > 2 }) return@filter false
                val context = prior.contextContentId?.let { conversations.context(it) }
                context != null && context.memories.all { ref -> allMemories.any { it.id == ref.id && it.version == ref.version && it.status == MemoryStatus.CONFIRMED } } &&
                    context.historyMemories.all { ref -> allMemories.any { it.id == ref.id && it.version == ref.version && it.visible } }
            }
            val recent = boundedConversationHistory(history)
            val dependencies = mutableListOf<MemoryReference>()
            recent.forEach { prior ->
                prior.contextContentId?.let { conversations.context(it) }?.let { dependencies.addAll(it.memories + it.historyMemories) }
                dependencies.addAll(allMemories.filter { it.sourceTurnId == prior.id }.map { MemoryReference(it.id, it.version) })
            }
            val snapshot = AssistantContext(connection.id, connection.name, binding.model, connection.protocol, binding.prompt, memories.map { MemoryReference(it.id, it.version) }, recent.map { it.id }, dependencies.distinct())
            attempt = conversations.start(turnId, snapshot, System.currentTimeMillis()).attempt
            val messages = recent.flatMap { listOf(AssistantMessage("user", it.userText), AssistantMessage("assistant", it.reply)) } + AssistantMessage("user", turn.userText)
            val output = gateway.converse(connection, binding.copy(prompt = assistantInstructions(binding.prompt, memories)), key, messages)
            val result = parseAssistantReply(output, turn.userText)
            conversations.answer(turnId, attempt, result.text, result.memories.map { MemoryDraft(it.type, it.text, it.evidence) }, System.currentTimeMillis())
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { conversations.cancel(turnId, System.currentTimeMillis(), attempt) }
            throw cancelled
        } catch (error: Exception) {
            val reason = when (error) {
                is java.io.IOException, is TransientAiException -> "NETWORK"
                is IllegalArgumentException -> "CONFIG_OR_FORMAT"
                else -> "PROVIDER"
            }
            conversations.fail(turnId, attempt, reason, System.currentTimeMillis())
        } finally {
            // Completed entries are removed only by the owner to avoid racing a later attempt.
            val owner = kotlinx.coroutines.currentCoroutineContext()[Job]
            synchronized(active) { if (active[turnId] == owner) active.remove(turnId) }
        }
    }

    suspend fun cancel(turnId: String) {
        conversations.cancel(turnId, System.currentTimeMillis())
        active[turnId]?.cancel()
    }

    suspend fun delete(conversationId: String) {
        val turns = conversations.turns(conversationId)
        conversations.delete(conversationId, System.currentTimeMillis())
        turns.forEach { active[it.id]?.cancel() }
    }
}
