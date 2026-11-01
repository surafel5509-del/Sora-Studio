package com.sorastudio.core.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

data class AudioDeviceInfo(
    val nativeSampleRate: Int,
    val outputFramesPerBuffer: Int,
    val lowLatencySupported: Boolean,
    val proAudioSupported: Boolean
) {
    fun describe(): String {
        val parts = mutableListOf<String>()
        parts.add("Native output: $nativeSampleRate Hz")
        if (outputFramesPerBuffer > 0) parts.add("device buffer ≈ $outputFramesPerBuffer frames")
        parts.add(if (lowLatencySupported) "low-latency path available" else "standard latency path")
        if (proAudioSupported) parts.add("pro-audio capable")
        return parts.joinToString(" · ")
    }
}

object AudioCapabilities {
    fun detect(context: Context): AudioDeviceInfo {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val nativeRate = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            ?: AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        val framesPerBuffer = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: -1
        val pm = context.packageManager
        val lowLatency = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)
        val proAudio = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO)
        return AudioDeviceInfo(nativeRate, framesPerBuffer, lowLatency, proAudio)
    }
}

/**
 * Streaming AudioTrack output. A dedicated high-priority thread pulls blocks
 * from [ProjectRenderer] and writes float PCM. No I/O or UI work on this thread.
 */
class AudioEngine(
    private val renderer: ProjectRenderer,
    private val bufferFrames: Int = 512
) {
    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private var track: AudioTrack? = null
    private var thread: Thread? = null

    fun start(): Boolean {
        if (running) return true
        return try {
            val sampleRate = renderer.sampleRate
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT
            )
            val bufferBytes = maxOf(minBuffer, bufferFrames * 2 * 4 * 3)
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
                lastError = "Audio output could not be initialised on this device."
                audioTrack.release()
                return false
            }
            track = audioTrack
            running = true
            lastError = null
            audioTrack.play()
            thread = Thread({ renderLoop(audioTrack) }, "SoraAudioThread").apply {
                priority = Thread.MAX_PRIORITY
                isDaemon = true
                start()
            }
            true
        } catch (e: Exception) {
            lastError = "Audio output unavailable: ${e.message}"
            running = false
            false
        }
    }

    private fun renderLoop(audioTrack: AudioTrack) {
        val block = FloatArray(bufferFrames * 2)
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Exception) {
            // best effort
        }
        while (running) {
            renderer.render(block, bufferFrames)
            var written = 0
            while (written < block.size && running) {
                val result = audioTrack.write(block, written, block.size - written, AudioTrack.WRITE_BLOCKING)
                if (result < 0) {
                    lastError = "Audio output error ($result)"
                    running = false
                    break
                }
                written += result
            }
        }
    }

    fun stop() {
        running = false
        try {
            thread?.join(500)
        } catch (_: InterruptedException) {
        }
        thread = null
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
    }
}
