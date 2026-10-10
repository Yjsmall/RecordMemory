package dev.local.record.ai

import dev.local.record.data.ConversationRepository
import dev.local.record.data.MemoryPlanningRepository
import dev.local.record.domain.TurnStatus
import dev.local.record.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-owned admission for newly committed answers. It never scans historical turns.
 * The planner persists each admitted request; its authorized outbox can resume after restart.
 * Call [onAnswered] only from the live answer command after its transaction has committed.
 */
class AutomaticMemoryLearning internal constructor(
    private val authorization: Flow<Boolean>,
    private val request: suspend (String) -> Job?,
    private val cancelAutomatic: suspend () -> Unit,
    private val scope: CoroutineScope
) {
    constructor(
        conversations: ConversationRepository,
        plans: MemoryPlanningRepository,
        planner: MemoryPlanner,
        settings: SettingsRepository,
        scope: CoroutineScope
    ) : this(
        settings.settings.map { it.agent.autoLearning },
        { turnId -> if (conversations.turn(turnId)?.status == TurnStatus.ANSWERED) planner.request(turnId, automatic = true) else null },
        {
            planner.cancelAutomatic()
            plans.cancelAutomatic(System.currentTimeMillis())
        },
        scope
    )

    private val admitted = mutableMapOf<String, Job>()
    private var observer: Job? = null
    private var enabled = false

    /** Start after recovery; subscribing to authorization cannot create paid work. */
    fun start(): Job = synchronized(admitted) {
        observer?.takeIf { it.isActive } ?: scope.launch(start = CoroutineStart.LAZY) {
            try {
                authorization.distinctUntilChanged().collect { allowed ->
                    synchronized(admitted) {
                        enabled = allowed
                        if (!allowed) admitted.values.toList().forEach { it.cancel() }
                    }
                    if (!allowed) cancelAutomatic()
                }
            } finally {
                synchronized(admitted) {
                    enabled = false
                    admitted.values.toList().forEach { it.cancel() }
                }
                withContext(NonCancellable) { cancelAutomatic() }
            }
        }.also {
            observer = it
            it.start()
        }
    }

    /** Persist every live source; the executor enforces the daily paid-attempt budget. */
    fun onAnswered(turnId: String) {
        synchronized(admitted) {
            if (!enabled || observer?.isActive != true || turnId in admitted) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    request(turnId)?.join()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The memory settings page reports missing MEMORY setup. Preflight failures
                    // create no task and must not turn an already committed answer into a failure.
                }
            }
            admitted[turnId] = job
            // A queued coroutine can be cancelled before its body (and finally) ever runs.
            job.invokeOnCompletion {
                synchronized(admitted) { if (admitted[turnId] == job) admitted.remove(turnId) }
            }
            job.start()
        }
    }
}
