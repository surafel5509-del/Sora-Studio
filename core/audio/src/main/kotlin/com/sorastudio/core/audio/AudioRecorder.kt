package com.sorastudio.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.sorastudio.core.dsp.WaveformPeaks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

data class RecorderState(
    val recording: Boolean = false,
    val peakLevel: Float = 0f,
    val elapsedMs: Long = 0,
    val peaks: FloatArray = FloatArray(0),
    val error: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecorderState) return false
        return recording == other.recording && elapsedMs == other.elapsedMs && peakLevel == other.peakLevel
    }
    override fun hashCode(): Int = elapsedMs.hashCode() * 31 + recording.hashCode()
}

data class RecordingResult(
    val file: File,
    val sampleRate: Int,
    val durationMs: Long,
    val frames: Long
)

/**
 * PCM recording via AudioRecord, streamed straight to a 16-bit WAV file
 * (header patched up on stop). Optional monitoring plays the input back on a
 * second AudioTrack — expect noticeable latency (~100-300 ms) on most phones;
 * wired headphones are recommended in the UI.
 *
 * The caller must hold RECORD_AUDIO permission before [start].
 */
class AudioRecorder {

    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var result: RecordingResult? = null

    var inputGainDb: Float = 0f
    var monitorEnabled: Boolean = false

    val isRecording: Boolean get() = running

    /** Supported capture rates to probe, most-preferred first. */
    companion object {
        fun probeSampleRate(preferred: Int): Int {
            val candidates = listOf(preferred, 48000, 44100, 32000, 22050, 16000).distinct()
            for (rate in candidates) {
                val minBuffer = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer > 0) return rate
            }
            return 44100
        }
    }

    @SuppressLint("MissingPermission")
    fun start(outputFile: File, requestedSampleRate: Int): Boolean {
        if (running) return false
        val sampleRate = probeSampleRate(requestedSampleRate)
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            _state.value = RecorderState(error = "This device does not support PCM recording at $sampleRate Hz.")
            return false
        }
        val bufferSize = max(minBuffer * 2, 4096)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            _state.value = RecorderState(error = "Microphone permission is required to record.")
            return false
        } catch (e: Exception) {
            _state.value = RecorderState(error = "Microphone unavailable: ${e.message}")
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            _state.value = RecorderState(error = "Microphone could not be initialised. Another app may be using it.")
            return false
        }

        outputFile.parentFile?.mkdirs()
        running = true
        result = null
        _state.value = RecorderState(recording = true)

        thread = Thread({
            captureLoop(record, outputFile, sampleRate, bufferSize)
        }, "SoraRecordThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    private fun captureLoop(record: AudioRecord, outputFile: File, sampleRate: Int, bufferSize: Int) {
        var monitor: AudioTrack? = null
        var raf: RandomAccessFile? = null
        var totalFrames = 0L
        val startTime = System.currentTimeMillis()
        val monoAccumulator = ArrayList<Float>(sampleRate * 10)
        try {
            raf = RandomAccessFile(outputFile, "rw")
            raf.setLength(0)
            raf.write(ByteArray(44)) // header placeholder

            if (monitorEnabled) {
                monitor = createMonitorTrack(sampleRate, bufferSize)
                monitor?.play()
            }

            record.startRecording()
            val buffer = ShortArray(bufferSize / 2)
            val byteBuffer = ByteBuffer.allocate(buffer.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            val gain = com.sorastudio.core.common.LevelMath.dbToGain(inputGainDb)
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                byteBuffer.clear()
                var peak = 0f
                for (i in 0 until read) {
                    val v = (buffer[i] / 32768f * gain).coerceIn(-1f, 1f)
                    peak = max(peak, abs(v))
                    byteBuffer.putShort((v * 32767f).toInt().toShort())
                    if (monoAccumulator.size < sampleRate * 600) monoAccumulator.add(v)
                }
                raf.write(byteBuffer.array(), 0, read * 2)
                monitor?.write(buffer, 0, read)
                totalFrames += read
                val peaksSource = FloatArray(monoAccumulator.size) { monoAccumulator[it] }
                _state.value = RecorderState(
                    recording = true,
                    peakLevel = peak,
                    elapsedMs = System.currentTimeMillis() - startTime,
                    peaks = WaveformPeaks.computeMonoPeaks(peaksSource, 160)
                )
            }
        } catch (e: IOException) {
            _state.value = RecorderState(error = "Recording failed: ${e.message}")
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching {
                monitor?.stop()
                monitor?.release()
            }
            runCatching {
                if (raf != null) writeWavHeader(raf, totalFrames, sampleRate)
                raf?.close()
            }
            result = RecordingResult(
                file = outputFile,
                sampleRate = sampleRate,
                durationMs = if (sampleRate > 0) totalFrames * 1000 / sampleRate else 0,
                frames = totalFrames
            )
            _state.value = _state.value.copy(recording = false, peakLevel = 0f)
        }
    }

    private fun createMonitorTrack(sampleRate: Int, bufferSize: Int): AudioTrack? = try {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        AudioTrack(
            android.media.AudioManager.STREAM_MUSIC,
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer, bufferSize),
            AudioTrack.MODE_STREAM
        )
    } catch (_: Exception) {
        null
    }

    private fun writeWavHeader(raf: RandomAccessFile, totalFrames: Long, sampleRate: Int) {
        val dataBytes = totalFrames * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + dataBytes).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(1) // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(header.array())
    }

    /** Stop and return the finished take (blocks briefly while the file is finalised). */
    fun stop(): RecordingResult? {
        if (!running) return result
        running = false
        try {
            thread?.join(2000)
        } catch (_: InterruptedException) {
        }
        thread = null
        return result
    }
}
