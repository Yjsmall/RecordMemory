package dev.local.record.ai

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException

/** A durable wake after a command commits. The planning projection itself is the outbox. */
class MemoryPlanningScheduler(private val context: Context) {
    fun kick() {
        runCatching {
            val work = OneTimeWorkRequestBuilder<MemoryPlanningWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("memory-planning-drain", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }
    }
}

class MemoryPlanningWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = EntryPointAccessors.fromApplication(applicationContext, AiRefreshWorker.GraphEntry::class.java).graph()
        graph.awaitRecovery()
        return try {
            graph.memoryPlanner.drain()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Only REQUESTED is drained. A provider attempt is terminal even after uncertainty.
            Result.retry()
        }
    }
}
