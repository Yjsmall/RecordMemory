package dev.local.record.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.local.record.AppGraph
import dev.local.record.data.HistorySearchRepository
import dev.local.record.data.LearningFeedbackRepository
import dev.local.record.data.MemoryDraft
import dev.local.record.domain.AssistantTurn
import dev.local.record.domain.Conversation
import dev.local.record.domain.HistoryHit
import dev.local.record.domain.HistoryQuery
import dev.local.record.domain.LearningFeedback
import dev.local.record.domain.MemoryItem
import dev.local.record.domain.MemoryKind
import dev.local.record.domain.MemoryPlanningTask
import dev.local.record.domain.TurnStatus
import dev.local.record.settings.AiCapability
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
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
    val memoryConfigured: Boolean = false,
    val memoryProvider: String = "",
    val memoryTasks: List<MemoryPlanningTask> = emptyList(),
    val activeRecallAvailable: Boolean = false,
    val autoLearning: Boolean = false,
    val ready: Boolean = false,
    val busy: Boolean = false,
    val problem: String? = null,
    val historyQuery: HistoryQuery = HistoryQuery(),
    val historyHits: List<HistoryHit> = emptyList(),
    val historySearched: Boolean = false,
    val selectedHistory: Set<String> = emptySet(),
    val learningFeedback: List<LearningFeedback> = emptyList(),
    val historyMessage: String? = null,
    val focusedTurnId: String? = null
) {
    val turns get() = allTurns.filter { it.conversationId == conversationId }.sortedBy { it.sequence }
    val activeTurn get() = turns.firstOrNull { it.status in setOf(TurnStatus.REQUESTED, TurnStatus.RUNNING) }
}

/** Drafts and selected conversation survive window changes without entering system saved state. */
class AssistantViewModel(private val graph: AppGraph) : ViewModel() {
    val state = MutableStateFlow(AssistantUiState())
    private val drafts = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val operations = Mutex()
    private val history = HistorySearchRepository(graph.database)

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
        viewModelScope.launch { graph.memoryPlanning.tasks.collect { tasks -> state.update { it.copy(memoryTasks = tasks) } } }
        viewModelScope.launch {
            LearningFeedbackRepository(graph.database).changes.collect { changes -> state.update { it.copy(learningFeedback = changes) } }
        }
        viewModelScope.launch {
            graph.database.recordings().observeEvents().collectLatest {
                val current = state.value
                if (current.historySearched) {
                    val hits = withContext(Dispatchers.IO) { history.search(current.historyQuery) }
                    state.update { latest ->
                        if (latest.historyQuery == current.historyQuery && latest.historySearched) latest.copy(historyHits = hits, selectedHistory = latest.selectedHistory.intersect(hits.map { it.ownerId }.toSet())) else latest
                    }
                }
            }
        }
        viewModelScope.launch {
            graph.settingsRepository.settings.collect { settings ->
                val binding = settings.configuration.binding(AiCapability.ANSWER)
                val connection = settings.configuration.connections.firstOrNull { it.id == binding.connectionId }
                val configured = connection != null && connection.supports(AiCapability.ANSWER) && binding.model.isNotBlank() && (!connection.bearerAuth || !settings.apiKeys[connection.id].isNullOrBlank())
                val memoryBinding = settings.configuration.binding(AiCapability.MEMORY)
                val memoryConnection = settings.configuration.connections.firstOrNull { it.id == memoryBinding.connectionId }
                val memoryConfigured = memoryConnection != null && memoryConnection.supports(AiCapability.MEMORY) && memoryBinding.model.isNotBlank() && (!memoryConnection.bearerAuth || !settings.apiKeys[memoryConnection.id].isNullOrBlank())
                val toolsAvailable = connection != null && settings.agent.toolChecks[connection.id + ":" + binding.model] == dev.local.record.ai.toolConfigurationFingerprint(connection, binding)
                state.update { it.copy(configured = configured, provider = connection?.let { provider -> "${provider.name} · ${binding.model}" }.orEmpty(), memoryConfigured = memoryConfigured, memoryProvider = memoryConnection?.let { provider -> "${provider.name} · ${memoryBinding.model}" }.orEmpty(), activeRecallAvailable = toolsAvailable, autoLearning = settings.agent.autoLearning) }
            }
        }
    }

    fun draft(value: String) {
        state.update { it.copy(draft = value.take(4_000)) }
    }

    fun select(id: String) {
        if (state.value.busy) return
        drafts[state.value.conversationId ?: "new"] = state.value.draft
        state.update { it.copy(conversationId = id, draft = drafts[id].orEmpty(), problem = null, focusedTurnId = null) }
    }

    fun newConversation() {
        if (!state.value.ready || state.value.busy) return
        drafts[state.value.conversationId ?: "new"] = state.value.draft
        val id = "conversation:${UUID.randomUUID()}"
        action {
            graph.conversations.create(id, System.currentTimeMillis())
            state.update { it.copy(conversationId = id, draft = drafts[id].orEmpty(), problem = null, focusedTurnId = null) }
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
            state.update { it.copy(conversationId = id, draft = if (it.draft == text) "" else it.draft, focusedTurnId = null) }
            graph.assistant.respond(turnId)
        }
    }

    fun retry(id: String) = action { graph.assistant.respond(id) }
    fun cancel(id: String) = action { graph.assistant.cancel(id) }
    fun planMemory(id: String) = action { graph.memoryPlanner.request(id) }
    fun cancelMemoryPlanning(id: String) = action { graph.memoryPlanner.cancel(id) }

    fun updateHistory(query: HistoryQuery) {
        state.update { it.copy(historyQuery = query, historySearched = false, historyHits = emptyList(), selectedHistory = emptySet(), historyMessage = null) }
    }

    fun searchHistory() = action {
        val query = state.value.historyQuery
        val hits = history.search(query)
        state.update { if (it.historyQuery == query) it.copy(historyHits = hits, historySearched = true, selectedHistory = emptySet(), historyMessage = null) else it }
    }

    fun selectHistory(hit: HistoryHit, selected: Boolean) {
        if (hit.origin != "USER_MESSAGE" || state.value.allTurns.none { it.id == hit.ownerId && it.status == TurnStatus.ANSWERED }) return
        state.update { current ->
            val ids = if (!selected) {
                current.selectedHistory - hit.ownerId
            } else if (current.selectedHistory.size < 10) {
                current.selectedHistory + hit.ownerId
            } else {
                current.selectedHistory
            }
            current.copy(selectedHistory = ids, historyMessage = null)
        }
    }

    fun planHistory() = action {
        val ids = state.value.selectedHistory.toList()
        require(ids.isNotEmpty() && ids.size <= 10) { "请选择最多 10 条聊天原文" }
        val count = graph.memoryPlanner.requestHistory(ids)
        state.update { it.copy(selectedHistory = emptySet(), historyMessage = "已排队 $count 条；已有整理记录的来源不会重复调用") }
    }

    fun openHistorySource(contentId: String, onChat: (String) -> Unit, onRecording: (String) -> Unit) = action {
        val source = requireNotNull(history.read(contentId)) { "来源已修改、删除或屏蔽，请重新检索" }
        if (source.origin in setOf("USER_MESSAGE", "ASSISTANT_REPLY")) {
            val turn = requireNotNull(graph.conversations.turn(source.ownerId))
            withContext(Dispatchers.Main) {
                drafts[state.value.conversationId ?: "new"] = state.value.draft
                state.update { it.copy(conversationId = turn.conversationId, draft = drafts[turn.conversationId].orEmpty(), focusedTurnId = turn.id) }
                onChat(turn.conversationId)
            }
        } else {
            withContext(Dispatchers.Main) { onRecording(source.ownerId) }
        }
    }
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
