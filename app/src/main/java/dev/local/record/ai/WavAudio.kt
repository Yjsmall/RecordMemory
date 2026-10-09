package dev.local.record.ai

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decode archived M4A to a temporary 16 kHz mono WAV. The original file is left unchanged. */
internal object WavAudio {
    private const val TARGET_RATE = 16_000
    private const val MAX_SAMPLES = TARGET_RATE * 60 * 8

    fun transcode(source: File, target: File) {
        require(source.isFile) { "找不到录音文件" }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("音频没有可解码音轨")
            extractor.selectTrack(index)
            val inputFormat = extractor.getTrackFormat(index)
            val mime = requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME)) { "无法识别音频编码" }
            val decoder = MediaCodec.createDecoderByType(mime)
            try {
                decoder.configure(inputFormat, null, null, 0)
                decoder.start()
                val pcm = decode(extractor, decoder)
                writeWav(target, pcm)
            } finally {
                runCatching { decoder.stop() }
                decoder.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun decode(extractor: MediaExtractor, decoder: MediaCodec): ShortArray {
        val bufferInfo = MediaCodec.BufferInfo()
        val samples = ArrayList<Short>(TARGET_RATE * 30)
        var inputDone = false
        var outputRate = TARGET_RATE
        var channels = 1
        val deadline = System.nanoTime() + 60_000_000_000L
        while (System.nanoTime() < deadline) {
            if (!inputDone) {
                val inputIndex = decoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val input = decoder.getInputBuffer(inputIndex) ?: ByteBuffer.allocate(0)
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = decoder.outputFormat
                outputRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else if (outputIndex >= 0) {
                val output = decoder.getOutputBuffer(outputIndex)
                if (output != null && bufferInfo.size > 0) {
                    output.position(bufferInfo.offset)
                    output.limit(bufferInfo.offset + bufferInfo.size)
                    output.order(ByteOrder.nativeOrder())
                    val shorts = ShortArray(bufferInfo.size / 2)
                    output.asShortBuffer().get(shorts)
                    appendDownsampled(samples, shorts, outputRate, channels)
                    require(samples.size <= MAX_SAMPLES) { "录音超过 8 分钟，请分段后再转写" }
                }
                decoder.releaseOutputBuffer(outputIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        require(samples.isNotEmpty()) { "没有解码出可用音频" }
        return samples.toShortArray()
    }

    private fun appendDownsampled(target: ArrayList<Short>, input: ShortArray, rate: Int, channels: Int) {
        val frames = input.size / channels
        if (frames <= 0) return
        val ratio = rate.toDouble() / TARGET_RATE
        var frame = 0.0
        while (frame < frames) {
            val start = frame.toInt()
            val end = (frame + ratio).toInt().coerceAtMost(frames)
            var sum = 0L
            var count = 0
            for (index in start until end.coerceAtLeast(start + 1).coerceAtMost(frames)) {
                var mixed = 0
                for (channel in 0 until channels) mixed += input[index * channels + channel]
                sum += mixed / channels
                count++
            }
            if (count > 0) target.add((sum / count).toInt().toShort())
            frame += ratio
        }
    }

    private fun writeWav(target: File, samples: ShortArray) {
        val dataBytes = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(TARGET_RATE)
        header.putInt(TARGET_RATE * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(dataBytes)
        val pcm = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { pcm.putShort(it) }
        target.writeBytes(header.array() + pcm.array())
    }
}
