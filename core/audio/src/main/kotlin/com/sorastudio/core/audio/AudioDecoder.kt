package com.sorastudio.core.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.sorastudio.core.dsp.DecodedAudio
import com.sorastudio.core.dsp.WavCodec
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes audio files to float PCM using Android platform APIs only
 * (MediaExtractor + MediaCodec). WAV files take the pure-Kotlin fast path.
 * Supports whatever the device ships codecs for (AAC/M4A, MP3, Ogg Vorbis/Opus,
 * FLAC on newer devices) — nothing is faked: unsupported content throws.
 */
object AudioDecoder {

    @Throws(IOException::class)
    fun decode(file: File): DecodedAudio {
        if (!file.exists()) throw IOException("File not found: ${file.name}")
        if (file.extension.equals("wav", ignoreCase = true) || isWav(file)) {
            return WavCodec.read(file)
        }
        return decodeWithMediaCodec(file)
    }

    private fun isWav(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(4)
            input.read(head)
            String(head, Charsets.US_ASCII) == "RIFF"
        }
    }.getOrDefault(false)

    @Throws(IOException::class)
    private fun decodeWithMediaCodec(file: File): DecodedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) throw IOException("No audio track in ${file.name}")
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcm = ArrayList<ByteArray>()
            var pcmBytes = 0
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            val timeoutUs = 10_000L

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outputIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val outBuffer = codec.getOutputBuffer(outputIndex)!!
                        val chunk = ByteArray(bufferInfo.size)
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outBuffer.get(chunk)
                        pcm.add(chunk)
                        pcmBytes += chunk.size
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                }
            }

            // MediaCodec audio decoders output 16-bit PCM.
            val totalSamples = pcmBytes / 2
            val samples = FloatArray(totalSamples)
            var sampleIndex = 0
            val tmp = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            for (chunk in pcm) {
                var i = 0
                while (i + 1 < chunk.size && sampleIndex < totalSamples) {
                    tmp.clear()
                    tmp.put(chunk[i]); tmp.put(chunk[i + 1])
                    samples[sampleIndex++] = tmp.getShort(0) / 32768f
                    i += 2
                }
            }
            return DecodedAudio(sampleRate, channels, samples)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
