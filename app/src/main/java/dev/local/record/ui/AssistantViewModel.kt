package dev.local.record.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.local.record.AppGraph
import dev.local.record.data.MemoryDraft
import dev.local.record.domain.AssistantTurn
import dev.local.record.domain.Conversation
import dev.local.record.domain.MemoryItem
import dev.local.record.domain.MemoryKind
import dev.local.record.domain.TurnStatus
import dev.local.record.settings.AiCapability
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

data class AssistantUiState(
    val conversations: List<Conversation> = emptyList(),
    val allTurns: List<AssistantTurn> = emptyList(),
    val memories: List<MemoryItem> = emptyList(),
    val conversationId: String? = null,
    val draft: String = "",
    val provider: String = "",
    val configured: Boolean = false,
    val ready: Boolean = false,
    val busy: Boolean = false,
    val problem: String? = null
) {
    val turns get() = allTurns.filter { it.conversationId == conversationId }.sortedBy { it.sequence }
    val activeTurn get() = turns.firstOrNull { it.status in setOf(TurnStatus.REQUESTED, TurnStatus.RUNNING) }
}

/** Drafts and selected conversation survive window changes without entering system saved state. */
class AssistantViewModel(private val graph: AppGraph) : ViewModel() {
    val state = MutableStateFlow(AssistantUiState())
    private val drafts = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val operations = Mutex()

    init {
        action {
            graph.awaitRecovery()
            state.update { it.copy(ready = true) }
        }
        viewModelScope.launch {
            graph.conversations.conversations.collect { conversations ->
                state.update { current -> current.copy(conversations = conversations, conversationId = current.conversationId?.takeIf { id -> conversations.any { it.id == id } } ?: conversations.firstOrNull()?.id) }
            }
        }
        viewModelScope.launch { graph.conversations.turns.collect { turns -> state.update { it.copy(allTurns = turns) } } }
        viewModelScope.launch { graph.processing.memories.collect { memories -> state.update { it.copy(memories = memories) } } }
        viewModelScope.launch {
            graph.settingsRepository.settings.collect { settings ->
                val binding = settings.configuration.binding(AiCapability.ANSWER)
                val connection = settings.configuration.connections.firstOrNull { it.id == binding.connectionId }
                val configured = connection != null && connection.supports(AiCapability.ANSWER) && binding.model.isNotBlank() && (!connection.bearerAuth || !settings.apiKeys[connection.id].isNullOrBlank())
                state.update { it.copy(configured = configured, provider = connection?.let { provider -> "${provider.name} · ${binding.model}" }.orEmpty()) }
            }
        }
    }

    fun draft(value: String) {
        state.update { it.copy(draft = value.take(4_000)) }
    }

    fun select(id: String) {
        if (state.value.busy) return
        drafts[state.value.conversationId ?: "new"] = state.value.draft
        state.update { it.copy(conversationId = id, draft = drafts[id].orEmpty(), problem = null) }
    }

    fun newConversation() {
        if (!state.value.ready || state.value.busy) return
        drafts[state.value.conversationId ?: "new"] = state.value.draft
        val id = "conversation:${UUID.randomUUID()}"
        action {
            graph.conversations.create(id, System.currentTimeMillis())
            state.update { it.copy(conversationId = id, draft = drafts[id].orEmpty(), problem = null) }
        }
    }

    fun send() {
        val current = state.value
        if (!current.ready || current.busy || current.activeTurn != null || !current.configured || current.draft.isBlank()) return
        val text = current.draft
        val id = current.conversationId ?: "conversation:${UUID.randomUUID()}"
        val turnId = "turn:${UUID.randomUUID()}"
        action {
            graph.conversations.create(id, System.currentTimeMillis())
            graph.conversations.request(turnId, id, text, System.currentTimeMillis())
            drafts[id] = ""
            state.update { it.copy(conversationId = id, draft = if (it.draft == text) "" else it.draft) }
            graph.assistant.respond(turnId)
        }
    }

    fun retry(id: String) = action { graph.assistant.respond(id) }
    fun cancel(id: String) = action { graph.assistant.cancel(id) }
    fun deleteConversation() {
        val id = state.value.conversationId ?: return
        action {
            graph.assistant.delete(id)
            drafts.remove(id)
            state.update { current ->
                val next = current.conversations.firstOrNull { it.id != id }?.id
                current.copy(conversationId = next, draft = drafts[next ?: "new"].orEmpty())
            }
        }
    }

    fun remember(id: String, text: String, kind: MemoryKind) = action {
        graph.processing.proposeConversationMemories(id, listOf(MemoryDraft(kind, text.trim().take(500), "用户主动保存")), System.currentTimeMillis(), explicit = true)
    }

    private fun action(block: suspend () -> Unit) {
        if (!operations.tryLock()) return
        state.update { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                state.update { it.copy(problem = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.update { it.copy(problem = error.message ?: "操作未完成，请重试") }
            } finally {
                state.update { it.copy(busy = false) }
                operations.unlock()
            }
        }
    }

    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AssistantViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return AssistantViewModel(graph) as T
        }
    }
}
