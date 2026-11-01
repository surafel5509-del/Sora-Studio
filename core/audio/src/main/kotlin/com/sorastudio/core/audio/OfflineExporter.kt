package com.sorastudio.core.audio

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import com.sorastudio.core.common.LevelMath
import com.sorastudio.core.common.TempoMath
import com.sorastudio.core.dsp.WavBitDepth
import com.sorastudio.core.project.SProject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

enum class ExportFormat(val displayName: String, val extension: String) {
    WAV("WAV", "wav"),
    M4A("M4A (AAC)", "m4a"),
    MP3("MP3", "mp3"),
    FLAC("FLAC", "flac")
}

data class ExportOptions(
    val format: ExportFormat = ExportFormat.WAV,
    val sampleRate: Int = 44100,
    val bitDepth: WavBitDepth = WavBitDepth.PCM16,
    val stereo: Boolean = true,
    val normalize: Boolean = true,
    val normalizeTargetDb: Float = -1f,
    val bitrateKbps: Int = 256,
    /** null = full mix; otherwise only this track (stems render loops all tracks). */
    val onlyTrackId: String? = null,
    val includeTailSeconds: Double = 1.5
)

/**
 * Offline rendering + encoding.
 *
 * The project is rendered with the exact same [ProjectRenderer] used for
 * playback, streamed to a 32-bit float WAV (peak tracked on the way), then
 * converted/encoded to the requested format. Compressed formats use
 * MediaCodec; if the device has no encoder for a format, the export fails
 * loudly with a clear reason instead of pretending.
 */
object OfflineExporter {

    fun encoderAvailable(mime: String): Boolean {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            list.codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        } catch (_: Exception) {
            false
        }
    }

    fun formatAvailable(format: ExportFormat): Boolean = when (format) {
        ExportFormat.WAV -> true
        ExportFormat.M4A -> encoderAvailable(MediaFormat.MIMETYPE_AUDIO_AAC)
        ExportFormat.MP3 -> encoderAvailable("audio/mpeg")
        ExportFormat.FLAC -> encoderAvailable(MediaFormat.MIMETYPE_AUDIO_FLAC)
    }

    fun unavailableReason(format: ExportFormat): String = when (format) {
        ExportFormat.MP3 -> "This device has no MP3 encoder (most Android phones ship AAC only). Export as M4A or WAV instead."
        ExportFormat.FLAC -> "This device has no FLAC encoder. Export as WAV for lossless quality."
        ExportFormat.M4A -> "This device has no AAC encoder. Export as WAV instead."
        ExportFormat.WAV -> ""
    }

    /**
     * Render + encode. [assetProviderForRate] must return stereo audio at the
     * export sample rate. Progress is reported 0..1.
     */
    fun export(
        project: SProject,
        assetProviderForRate: (rate: Int) -> (String) -> FloatArray?,
        options: ExportOptions,
        outputFile: File,
        tempDir: File,
        onProgress: (Float) -> Unit = {}
    ): Result<File> {
        if (!formatAvailable(options.format)) {
            return Result.failure(IOException(unavailableReason(options.format)))
        }
        return runCatching {
            tempDir.mkdirs()
            outputFile.parentFile?.mkdirs()
            val projectToRender = if (options.onlyTrackId != null) {
                project.copy(tracks = project.tracks.map { t ->
                    t.copy(solo = t.id == options.onlyTrackId, mute = false)
                })
            } else project

            val tempWav = File(tempDir, "render-${System.currentTimeMillis()}.wav")
            val peak = renderToFloatWav(projectToRender, assetProviderForRate(options.sampleRate), options, tempWav) { p ->
                onProgress(p * 0.8f)
            }
            val gain = if (options.normalize && peak > 0.000001f) {
                LevelMath.dbToGain(options.normalizeTargetDb) / peak
            } else 1f

            when (options.format) {
                ExportFormat.WAV -> convertFloatWav(tempWav, outputFile, options, gain)
                ExportFormat.M4A -> encodeWithMediaCodec(tempWav, outputFile, options, gain, MediaFormat.MIMETYPE_AUDIO_AAC, mux = true)
                ExportFormat.MP3 -> encodeWithMediaCodec(tempWav, outputFile, options, gain, "audio/mpeg", mux = false)
                ExportFormat.FLAC -> encodeWithMediaCodec(tempWav, outputFile, options, gain, MediaFormat.MIMETYPE_AUDIO_FLAC, mux = false)
            }
            tempWav.delete()
            onProgress(1f)
            if (!outputFile.exists() || outputFile.length() == 0L) {
                throw IOException("Export produced an empty file.")
            }
            outputFile
        }
    }

    /** Render the project to [outFile] as float32 WAV; returns peak. */
    private fun renderToFloatWav(
        project: SProject,
        assetProvider: (String) -> FloatArray?,
        options: ExportOptions,
        outFile: File,
        onProgress: (Float) -> Unit
    ): Float {
        val sampleRate = options.sampleRate
        val renderer = ProjectRenderer(sampleRate, assetProvider)
        renderer.setProject(project.copy(loopEnabled = false))
        val tailBeats = TempoMath.secondsToBeats(options.includeTailSeconds, project.bpm)
        val totalBeats = project.computedLengthBeats() + tailBeats
        val totalFrames = TempoMath.beatsToSamples(totalBeats, project.bpm, sampleRate)
        val block = 4096
        val buffer = FloatArray(block * 2)
        val byteBuffer = ByteBuffer.allocate(block * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        var rendered = 0L
        var peak = 0f
        renderer.playing = true
        RandomAccessFile(outFile, "rw").use { raf ->
            raf.setLength(0)
            raf.write(ByteArray(44))
            while (rendered < totalFrames) {
                val framesNow = minOf(block.toLong(), totalFrames - rendered).toInt()
                renderer.render(buffer, framesNow)
                byteBuffer.clear()
                for (i in 0 until framesNow * 2) {
                    val v = buffer[i]
                    val a = abs(v)
                    if (a > peak) peak = a
                    byteBuffer.putFloat(v)
                }
                raf.write(byteBuffer.array(), 0, framesNow * 2 * 4)
                rendered += framesNow
                onProgress(if (totalFrames == 0L) 1f else rendered.toFloat() / totalFrames)
            }
            writeWavHeader(raf, totalFrames, sampleRate, channels = 2, floatFormat = true, bits = 32)
        }
        renderer.playing = false
        return peak
    }

    /** Convert float32 stereo WAV to the final WAV (depth/channels/gain), chunked. */
    private fun convertFloatWav(input: File, output: File, options: ExportOptions, gain: Float) {
        val totalInFrames = (input.length() - 44) / 8 // 2ch * 4 bytes
        RandomAccessFile(input, "r").use { src ->
            RandomAccessFile(output, "rw").use { dst ->
                dst.setLength(0)
                dst.write(ByteArray(44))
                src.seek(44)
                val chunkFrames = 8192
                val inBytes = ByteArray(chunkFrames * 8)
                val inBuf = ByteBuffer.wrap(inBytes).order(ByteOrder.LITTLE_ENDIAN)
                val bytesPerSample = options.bitDepth.bits / 8
                val outChannels = if (options.stereo) 2 else 1
                val outBuf = ByteBuffer.allocate(chunkFrames * 2 * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN)
                var remaining = totalInFrames
                while (remaining > 0) {
                    val framesNow = minOf(chunkFrames.toLong(), remaining).toInt()
                    src.readFully(inBytes, 0, framesNow * 8)
                    inBuf.position(0)
                    outBuf.clear()
                    for (f in 0 until framesNow) {
                        val l = inBuf.getFloat() * gain
                        val r = inBuf.getFloat() * gain
                        if (outChannels == 2) {
                            writeSample(outBuf, l, options.bitDepth)
                            writeSample(outBuf, r, options.bitDepth)
                        } else {
                            writeSample(outBuf, (l + r) * 0.5f, options.bitDepth)
                        }
                    }
                    dst.write(outBuf.array(), 0, framesNow * outChannels * bytesPerSample)
                    remaining -= framesNow
                }
                writeWavHeader(
                    dst, totalInFrames, options.sampleRate,
                    channels = outChannels, floatFormat = options.bitDepth == WavBitDepth.FLOAT32,
                    bits = options.bitDepth.bits
                )
            }
        }
    }

    private fun writeSample(buffer: ByteBuffer, value: Float, depth: WavBitDepth) {
        val v = value.coerceIn(-1f, 1f)
        when (depth) {
            WavBitDepth.PCM16 -> buffer.putShort((v * 32767f).toInt().toShort())
            WavBitDepth.PCM24 -> {
                val x = (v * 8388607f).toInt()
                buffer.put((x and 0xFF).toByte())
                buffer.put(((x shr 8) and 0xFF).toByte())
                buffer.put(((x shr 16) and 0xFF).toByte())
            }
            WavBitDepth.FLOAT32 -> buffer.putFloat(v)
        }
    }

    /** AAC via muxer (M4A). MP3/FLAC: raw elementary stream to file. */
    private fun encodeWithMediaCodec(
        inputFloatWav: File,
        output: File,
        options: ExportOptions,
        gain: Float,
        mime: String,
        mux: Boolean
    ) {
        val channels = if (options.stereo) 2 else 1
        val format = MediaFormat.createAudioFormat(mime, options.sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, max(32, options.bitrateKbps) * 1000)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384 * 4)
        }
        val codec = MediaCodec.createEncoderByType(mime)
        var muxer: MediaMuxer? = null
        var rawOut: java.io.FileOutputStream? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var trackIndex = -1
            var muxerStarted = false
            if (mux) {
                muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } else {
                rawOut = java.io.FileOutputStream(output)
            }

            RandomAccessFile(inputFloatWav, "r").use { src ->
                src.seek(44)
                val totalInFrames = (inputFloatWav.length() - 44) / 8
                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var framesSent = 0L
                val pcmChunk = ByteArray(8192 * 8)
                val pcmBuf = ByteBuffer.wrap(pcmChunk).order(ByteOrder.LITTLE_ENDIAN)
                val intBuf = ByteBuffer.allocate(8192 * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)

                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(5000)
                        if (inIndex >= 0) {
                            val inBuffer = codec.getInputBuffer(inIndex)!!
                            inBuffer.clear()
                            if (framesSent >= totalInFrames) {
                                codec.queueInputBuffer(inIndex, 0, 0, presentationTimeUs(framesSent, options.sampleRate), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val framesToRead = minOf(4096L, totalInFrames - framesSent).toInt()
                                src.readFully(pcmChunk, 0, framesToRead * 8)
                                pcmBuf.position(0)
                                intBuf.clear()
                                for (f in 0 until framesToRead) {
                                    val l = pcmBuf.getFloat() * gain
                                    val r = pcmBuf.getFloat() * gain
                                    if (channels == 2) {
                                        intBuf.putShort((l.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                                        intBuf.putShort((r.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                                    } else {
                                        intBuf.putShort((((l + r) * 0.5f).coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                                    }
                                }
                                val bytes = framesToRead * channels * 2
                                inBuffer.put(intBuf.array(), 0, bytes)
                                codec.queueInputBuffer(inIndex, 0, bytes, presentationTimeUs(framesSent, options.sampleRate), 0)
                                framesSent += framesToRead
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 5000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (mux) {
                                trackIndex = muxer!!.addTrack(codec.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        outIndex >= 0 -> {
                            val outBuffer = codec.getOutputBuffer(outIndex)!!
                            if (bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0)) {
                                outBuffer.position(bufferInfo.offset)
                                outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                if (mux) {
                                    if (muxerStarted) muxer!!.writeSampleData(trackIndex, outBuffer, bufferInfo)
                                } else {
                                    val bytes = ByteArray(bufferInfo.size)
                                    outBuffer.get(bytes)
                                    rawOut?.write(bytes)
                                }
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            }
        } finally {
            runCatching { rawOut?.close() }
            runCatching { if (muxer != null) muxer.stop() }
            runCatching { muxer?.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun presentationTimeUs(frames: Long, sampleRate: Int): Long =
        frames * 1_000_000L / max(1, sampleRate)

    private fun writeWavHeader(raf: RandomAccessFile, frames: Long, sampleRate: Int, channels: Int, floatFormat: Boolean, bits: Int) {
        val bytesPerSample = bits / 8
        val dataBytes = frames * channels * bytesPerSample
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + dataBytes).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(if (floatFormat) 3 else 1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * channels * bytesPerSample)
        header.putShort((channels * bytesPerSample).toShort())
        header.putShort(bits.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(header.array())
    }
}
