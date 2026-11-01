package com.sorastudio.core.dsp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class WavBitDepth(val bits: Int) { PCM16(16), PCM24(24), FLOAT32(32) }

/**
 * Pure-Kotlin WAV reader/writer (RIFF). Reads PCM 8/16/24/32-bit and IEEE float,
 * writes 16/24-bit PCM and 32-bit float. Used for recordings, stems and exports
 * so export never depends on a platform encoder existing for WAV.
 */
object WavCodec {

    @Throws(IOException::class)
    fun read(file: File): DecodedAudio = BufferedInputStream(FileInputStream(file)).use { read(it) }

    @Throws(IOException::class)
    fun read(input: InputStream): DecodedAudio {
        val header = ByteArray(12)
        readFully(input, header)
        if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(header, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) throw IOException("Not a WAV file")

        var audioFormat = 1
        var channels = 2
        var sampleRate = 44100
        var bitsPerSample = 16
        var dataBytes: ByteArray? = null

        val chunkHeader = ByteArray(8)
        while (true) {
            val read = readUpTo(input, chunkHeader)
            if (read < 8) break
            val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (size < 0) throw IOException("Corrupt WAV chunk size")
            when (id) {
                "fmt " -> {
                    val fmt = ByteArray(size)
                    readFully(input, fmt)
                    val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    audioFormat = bb.short.toInt()
                    channels = bb.short.toInt()
                    sampleRate = bb.int
                    bb.int // byte rate
                    bb.short // block align
                    bitsPerSample = bb.short.toInt()
                    // WAVE_FORMAT_EXTENSIBLE (65534): real format is in the SubFormat GUID
                    if (audioFormat == 65534 && size >= 26) {
                        bb.position(24)
                        audioFormat = bb.short.toInt()
                    }
                }
                "data" -> {
                    val bytes = ByteArray(size)
                    readFully(input, bytes)
                    dataBytes = bytes
                }
                else -> skipFully(input, size.toLong() + (size % 2))
            }
            if (id == "fmt " && size % 2 == 1) skipFully(input, 1)
            if (dataBytes != null) break
        }
        val bytes = dataBytes ?: throw IOException("WAV has no data chunk")
        if (channels <= 0) throw IOException("WAV has no channels")

        val bytesPerSample = (bitsPerSample / 8).coerceAtLeast(1)
        val totalSamples = bytes.size / bytesPerSample
        val out = FloatArray(totalSamples)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        when {
            audioFormat == 3 && bitsPerSample == 32 -> {
                for (i in 0 until totalSamples) out[i] = bb.getFloat(i * 4)
            }
            bitsPerSample == 8 -> {
                for (i in 0 until totalSamples) out[i] = ((bytes[i].toInt() and 0xFF) - 128) / 128f
            }
            bitsPerSample == 16 -> {
                for (i in 0 until totalSamples) out[i] = bb.getShort(i * 2) / 32768f
            }
            bitsPerSample == 24 -> {
                for (i in 0 until totalSamples) {
                    val o = i * 3
                    var v = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or ((bytes[o + 2].toInt()) shl 16)
                    if (v and 0x800000 != 0) v = v or -0x1000000
                    out[i] = v / 8388608f
                }
            }
            bitsPerSample == 32 -> {
                for (i in 0 until totalSamples) out[i] = bb.getInt(i * 4) / 2147483648f
            }
            else -> throw IOException("Unsupported WAV bit depth: $bitsPerSample")
        }
        return DecodedAudio(sampleRate, channels, out)
    }

    @Throws(IOException::class)
    fun write(file: File, samples: FloatArray, sampleRate: Int, channels: Int, depth: WavBitDepth = WavBitDepth.PCM16) {
        file.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(file)).use { write(it, samples, sampleRate, channels, depth) }
    }

    @Throws(IOException::class)
    fun write(out: OutputStream, samples: FloatArray, sampleRate: Int, channels: Int, depth: WavBitDepth = WavBitDepth.PCM16) {
        val bytesPerSample = depth.bits / 8
        val dataSize = samples.size * bytesPerSample
        val fmt = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        fmt.put("RIFF".toByteArray(Charsets.US_ASCII))
        fmt.putInt(36 + dataSize)
        fmt.put("WAVE".toByteArray(Charsets.US_ASCII))
        fmt.put("fmt ".toByteArray(Charsets.US_ASCII))
        fmt.putInt(16)
        fmt.putShort(if (depth == WavBitDepth.FLOAT32) 3 else 1)
        fmt.putShort(channels.toShort())
        fmt.putInt(sampleRate)
        fmt.putInt(sampleRate * channels * bytesPerSample)
        fmt.putShort((channels * bytesPerSample).toShort())
        fmt.putShort(depth.bits.toShort())
        fmt.put("data".toByteArray(Charsets.US_ASCII))
        fmt.putInt(dataSize)
        out.write(fmt.array())

        val chunk = ByteBuffer.allocate(8192 * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < samples.size) {
            chunk.clear()
            val count = minOf(8192, samples.size - i)
            for (j in 0 until count) {
                val v = samples[i + j].coerceIn(-1f, 1f)
                when (depth) {
                    WavBitDepth.PCM16 -> chunk.putShort((v * 32767f).toInt().toShort())
                    WavBitDepth.PCM24 -> {
                        val x = (v * 8388607f).toInt()
                        chunk.put((x and 0xFF).toByte())
                        chunk.put(((x shr 8) and 0xFF).toByte())
                        chunk.put(((x shr 16) and 0xFF).toByte())
                    }
                    WavBitDepth.FLOAT32 -> chunk.putFloat(v)
                }
            }
            out.write(chunk.array(), 0, count * bytesPerSample)
            i += count
        }
        out.flush()
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val n = input.read(target, offset, target.size - offset)
            if (n < 0) throw IOException("Unexpected end of WAV stream")
            offset += n
        }
    }

    private fun readUpTo(input: InputStream, target: ByteArray): Int {
        var offset = 0
        while (offset < target.size) {
            val n = input.read(target, offset, target.size - offset)
            if (n < 0) break
            offset += n
        }
        return offset
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) break
                remaining--
            } else remaining -= skipped
        }
    }
}
