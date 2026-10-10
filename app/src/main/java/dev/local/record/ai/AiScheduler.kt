package dev.local.record.ai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.local.record.AppGraph
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Wakes processing after a commit and again after process restart. Replay does not call this. */
class AiScheduler(private val context: Context, private val processor: AiProcessor) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()

    fun kick() {
        scope.launch {
            runCatching { enqueueWork() }
            gate.withLock {
                runCatching { processor.drain(System.currentTimeMillis()) }
            }
        }
    }

    private fun enqueueWork() {
        val request = OneTimeWorkRequestBuilder<AiRefreshWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("ai-drain", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

class AiRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = EntryPointAccessors.fromApplication(applicationContext, GraphEntry::class.java).graph()
        graph.awaitRecovery()
        return try {
            graph.processor.drain(System.currentTimeMillis())
            // A foreground drain can leave a retry delay or an active lease in the outbox.
            // Keep a durable wake-up until that pending work has actually finished.
            if (graph.processing.hasPendingWork()) Result.retry() else Result.success()
        } catch (_: TransientAiException) {
            Result.retry()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface GraphEntry {
        fun graph(): AppGraph
    }
}
