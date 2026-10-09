package dev.local.record.ai

import dev.local.record.data.FollowUp
import dev.local.record.data.MemoryDraft
import dev.local.record.data.ProcessingRepository
import dev.local.record.data.RecordingRepository
import dev.local.record.data.StoredResult
import dev.local.record.domain.JobStatus
import dev.local.record.domain.cleanSummary
import dev.local.record.domain.cleanTitle
import dev.local.record.settings.AiCapability
import dev.local.record.settings.AiConnection
import dev.local.record.settings.CapabilityBinding
import dev.local.record.settings.DOUBAO_ASR
import dev.local.record.settings.PrivateSettings
import dev.local.record.settings.ProcessingMode
import dev.local.record.settings.SettingsRepository
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.flow.first

/** Executes claimed outbox jobs. Replay never calls this type. */
class AiProcessor(
    private val recordings: RecordingRepository,
    private val processing: ProcessingRepository,
    private val settingsRepository: SettingsRepository,
    private val audioDirectory: File,
    private val cacheDirectory: File,
    private val gateway: AiGateway = AiGateway()
) {
    suspend fun enqueueSaved(recordingId: String, now: Long): Boolean {
        val settings = settingsRepository.settings.first()
        if (settings.configuration.processingMode != ProcessingMode.AUTO) return false
        return enqueue(recordingId, AiCapability.ASR, now, settings, force = false)
    }

    suspend fun request(recordingId: String, capability: AiCapability, now: Long) {
        val settings = settingsRepository.settings.first()
        check(enqueue(recordingId, capability, now, settings, force = true)) { notReady(capability) }
    }

    suspend fun drain(now: Long) {
        while (true) {
            val claim = processing.claim(now, now + 120_000) ?: return
            try {
                val prepared = prepare(claim.job.id)
                processing.applySuccess(claim.job.id, claim.attempt, prepared.result, prepared.followUps, now)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: TransientAiException) {
                recordFailure(claim.job.id, claim.attempt, error.message ?: "网络暂时失败", false, now, error)
                throw error
            } catch (error: IOException) {
                val transient = TransientAiException(error.message ?: "网络暂时失败", error)
                recordFailure(claim.job.id, claim.attempt, "网络暂时失败", false, now, transient)
                throw transient
            } catch (error: Exception) {
                recordFailure(claim.job.id, claim.attempt, error.message ?: "处理失败", true, now, error)
            }
        }
    }

    private suspend fun enqueue(recordingId: String, capability: AiCapability, now: Long, settings: PrivateSettings, force: Boolean): Boolean {
        val recording = recordings.get(recordingId) ?: return false
        if (recording.fileName == null && capability == AiCapability.ASR) return false
        val binding = configured(settings, capability) ?: return false
        val text = processing.text(recordingId)
        if (capability != AiCapability.ASR && text.transcriptContentId == null) {
            check(!force) { "请先完成转写" }
            return false
        }
        val active = processing.jobsFor(recordingId).filter { it.capability == capability.name && it.status in setOf(JobStatus.REQUESTED, JobStatus.RUNNING) }
        if (!force && active.isNotEmpty()) return true
        if (force) active.forEach { processing.cancel(it.id, now) }
        val generation = processing.jobsFor(recordingId).filter { it.capability == capability.name }.maxOfOrNull { it.generation }?.plus(1) ?: 1
        val source = if (capability == AiCapability.ASR) null else text.transcriptContentId
        processing.enqueue("$recordingId:${capability.name.lowercase()}:$generation", recordingId, capability.name, generation, source, now)
        return binding.connectionId != null
    }

    private suspend fun prepare(jobId: String): Prepared {
        val job = requireNotNull(processing.job(jobId)) { "任务不存在" }
        val settings = settingsRepository.settings.first()
        val capability = AiCapability.valueOf(job.capability)
        val binding = configured(settings, capability) ?: error(notReady(capability))
        val connection = settings.configuration.connections.first { it.id == binding.connectionId }
        val key = settings.apiKeys[connection.id]
        val attempt = processing.job(jobId)?.attempt ?: 1
        val output = when (capability) {
            AiCapability.ASR -> transcribe(job.recordingId, connection, binding, key, "$jobId:$attempt")
            AiCapability.TITLE -> StoredResult.Title(cleanTitle(complete(connection, binding, key, transcript(job.recordingId))))
            AiCapability.SUMMARY -> StoredResult.Summary(cleanSummary(complete(connection, binding, key, transcript(job.recordingId))))
            AiCapability.MEMORY -> StoredResult.Memories(
                parseMemoryItems(complete(connection, binding.copy(prompt = memoryPrompt(binding)), key, memoryInput(job.recordingId))).map {
                    MemoryDraft(it.type, it.text, it.evidence)
                }
            )
            AiCapability.ANSWER -> error("记忆问答尚未接通")
        }
        val followUps = if (capability == AiCapability.ASR && settings.configuration.processingMode == ProcessingMode.AUTO) {
            val existing = processing.jobsFor(job.recordingId)
            listOf(AiCapability.TITLE, AiCapability.SUMMARY, AiCapability.MEMORY).mapNotNull { next ->
                if (configured(settings, next) == null) return@mapNotNull null
                if (existing.any { it.capability == next.name && it.status in setOf(JobStatus.REQUESTED, JobStatus.RUNNING) }) return@mapNotNull null
                val generation = existing.filter { it.capability == next.name }.maxOfOrNull { it.generation }?.plus(1) ?: 1
                FollowUp("${job.recordingId}:${next.name.lowercase()}:$generation", next.name, generation)
            }
        } else {
            emptyList()
        }
        return Prepared(output, followUps)
    }

    private suspend fun transcribe(recordingId: String, connection: AiConnection, binding: CapabilityBinding, key: String?, requestId: String): StoredResult.Transcript {
        val recording = requireNotNull(recordings.get(recordingId)) { "录音不存在" }
        val fileName = requireNotNull(recording.fileName) { "没有可转写的音频" }
        val audio = File(audioDirectory, fileName)
        require(audio.isFile) { "录音文件缺失" }
        val text = if (connection.protocol == DOUBAO_ASR) {
            val wav = File(cacheDirectory, requestId.replace(Regex("[^A-Za-z0-9._-]"), "-") + ".wav")
            try {
                WavAudio.transcode(audio, wav)
                gateway.transcribe(connection, binding, key, wav, requestId)
            } finally {
                wav.delete()
            }
        } else {
            gateway.transcribe(connection, binding, key, audio, requestId)
        }
        return StoredResult.Transcript(text)
    }

    private suspend fun complete(connection: AiConnection, binding: CapabilityBinding, key: String?, input: String): String = try {
        gateway.complete(connection, binding, key, input)
    } catch (error: SocketTimeoutException) {
        throw TransientAiException("模型响应超时", error)
    } catch (error: UnknownHostException) {
        throw TransientAiException("无法连接服务器", error)
    }

    private suspend fun recordFailure(jobId: String, attempt: Int, reason: String, terminal: Boolean, now: Long, primary: Exception) {
        try {
            processing.fail(jobId, attempt, reason, terminal, now)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (record: Exception) {
            primary.addSuppressed(record)
            throw primary
        }
    }

    private suspend fun transcript(recordingId: String): String = processing.text(recordingId).transcript?.takeIf { it.isNotBlank() } ?: error("没有转写文本")

    private suspend fun memoryInput(recordingId: String): String {
        val related = processing.memories().filter { it.visible }.take(20).joinToString("\n") { "- ${it.type.label}：${it.text}" }
        val transcript = transcript(recordingId)
        return if (related.isBlank()) transcript else "已有记忆：\n$related\n\n本次转写：\n$transcript"
    }

    private fun configured(settings: PrivateSettings, capability: AiCapability): CapabilityBinding? {
        val binding = settings.configuration.binding(capability)
        val connection = settings.configuration.connections.firstOrNull { it.id == binding.connectionId } ?: return null
        if (binding.model.isBlank() || !connection.supports(capability)) return null
        val key = settings.apiKeys[connection.id]
        if ((connection.bearerAuth || connection.protocol == DOUBAO_ASR) && key.isNullOrBlank()) return null
        return binding
    }

    private fun notReady(capability: AiCapability) = "请先在设置中为${capability.label}选择服务、模型并保存密钥"

    private fun memoryPrompt(binding: CapabilityBinding) = binding.prompt + "\n只输出 JSON：{\"items\":[{\"type\":\"person|project|preference|agreement|todo|idea\",\"text\":\"候选内容\",\"evidence\":\"原文短句\"}]}。没有可靠候选时输出 {\"items\":[]}。不要把一次想法写成长期习惯。"

    private data class Prepared(val result: StoredResult, val followUps: List<FollowUp>)
}
