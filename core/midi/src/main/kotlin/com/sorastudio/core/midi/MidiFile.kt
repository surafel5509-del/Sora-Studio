package com.sorastudio.core.midi

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Minimal Standard MIDI File (SMF) support:
 *  - Write: format 1, one conductor track (tempo) + one track per MIDI clip.
 *  - Read: format 0/1, note on/off + tempo; returns clips per track.
 * PPQ = 480 ticks per quarter note.
 */
object MidiFileIO {
    const val PPQ = 480

    data class ImportedTrack(val name: String, val channel: Int, val notes: List<MidiNote>, val lengthBeats: Double)
    data class ImportedFile(val bpm: Double, val tracks: List<ImportedTrack>)

    // ------------------------------------------------------------- writing

    fun write(file: File, bpm: Double, tracks: List<Pair<String, List<MidiNote>>>) {
        file.parentFile?.mkdirs()
        file.writeBytes(toBytes(bpm, tracks))
    }

    fun toBytes(bpm: Double, tracks: List<Pair<String, List<MidiNote>>>): ByteArray {
        val out = ByteArrayOutputStream()
        // Header
        out.write("MThd".toByteArray(Charsets.US_ASCII))
        writeInt32(out, 6)
        writeInt16(out, 1) // format 1
        writeInt16(out, tracks.size + 1)
        writeInt16(out, PPQ)

        // Conductor track with tempo
        val conductor = ByteArrayOutputStream()
        writeVarLen(conductor, 0)
        conductor.write(0xFF); conductor.write(0x51); conductor.write(0x03)
        val mpq = (60_000_000.0 / bpm.coerceIn(20.0, 400.0)).toInt()
        conductor.write((mpq shr 16) and 0xFF); conductor.write((mpq shr 8) and 0xFF); conductor.write(mpq and 0xFF)
        writeVarLen(conductor, 0)
        conductor.write(0xFF); conductor.write(0x2F); conductor.write(0x00)
        writeTrack(out, conductor.toByteArray())

        tracks.forEachIndexed { index, (name, notes) ->
            val track = ByteArrayOutputStream()
            writeVarLen(track, 0)
            track.write(0xFF); track.write(0x03)
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            writeVarLen(track, nameBytes.size)
            track.write(nameBytes)

            data class Ev(val tick: Int, val isOn: Boolean, val note: MidiNote)
            val events = mutableListOf<Ev>()
            for (n in notes) {
                events.add(Ev((n.startBeats * PPQ).toInt(), true, n))
                events.add(Ev((n.endBeats * PPQ).toInt().coerceAtLeast((n.startBeats * PPQ).toInt() + 1), false, n))
            }
            events.sortWith(compareBy({ it.tick }, { if (it.isOn) 1 else 0 }))
            var lastTick = 0
            val channel = (index % 16)
            for (ev in events) {
                writeVarLen(track, (ev.tick - lastTick).coerceAtLeast(0))
                lastTick = ev.tick
                if (ev.isOn) {
                    track.write(0x90 or channel)
                    track.write(ev.note.pitch.coerceIn(0, 127))
                    track.write(ev.note.velocity.coerceIn(1, 127))
                } else {
                    track.write(0x80 or channel)
                    track.write(ev.note.pitch.coerceIn(0, 127))
                    track.write(0)
                }
            }
            writeVarLen(track, 0)
            track.write(0xFF); track.write(0x2F); track.write(0x00)
            writeTrack(out, track.toByteArray())
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------- reading

    @Throws(IOException::class)
    fun read(file: File): ImportedFile = read(file.readBytes())

    @Throws(IOException::class)
    fun read(bytes: ByteArray): ImportedFile {
        val reader = Reader(bytes)
        if (reader.text(4) != "MThd") throw IOException("Not a MIDI file")
        val headerLen = reader.int32()
        reader.int16() // format
        val trackCount = reader.int16()
        val division = reader.int16()
        if (division and 0x8000 != 0) throw IOException("SMPTE time division not supported")
        val ppq = if (division == 0) PPQ else division
        reader.skip((headerLen - 6).coerceAtLeast(0))

        var bpm = 120.0
        val tracks = mutableListOf<ImportedTrack>()
        repeat(trackCount) { trackIndex ->
            if (reader.remaining() < 8) return@repeat
            val chunkType = reader.text(4)
            val chunkLen = reader.int32()
            if (chunkType != "MTrk") {
                reader.skip(chunkLen)
                return@repeat
            }
            val trackEnd = reader.position + chunkLen
            var tick = 0
            var name = "Track ${trackIndex + 1}"
            var lastStatus = 0
            val active = HashMap<Pair<Int, Int>, Pair<Int, Int>>() // (channel,pitch) -> (startTick, velocity)
            val notes = mutableListOf<MidiNote>()
            var channelSeen = 0
            while (reader.position < trackEnd) {
                tick += reader.varLen()
                var status = reader.peek()
                if (status and 0x80 == 0) {
                    status = lastStatus
                } else {
                    reader.byte()
                    if (status < 0xF0) lastStatus = status
                }
                when {
                    status == 0xFF -> {
                        val metaType = reader.byte()
                        val len = reader.varLen()
                        when (metaType) {
                            0x51 -> if (len >= 3) {
                                val mpq = (reader.byte() shl 16) or (reader.byte() shl 8) or reader.byte()
                                if (mpq > 0) bpm = 60_000_000.0 / mpq
                                reader.skip(len - 3)
                            } else reader.skip(len)
                            0x03 -> {
                                name = String(reader.bytes(len), Charsets.UTF_8).ifBlank { name }
                            }
                            else -> reader.skip(len)
                        }
                    }
                    status == 0xF0 || status == 0xF7 -> {
                        val len = reader.varLen()
                        reader.skip(len)
                    }
                    status and 0xF0 == 0x90 -> {
                        val ch = status and 0x0F; channelSeen = ch
                        val pitch = reader.byte(); val vel = reader.byte()
                        if (vel == 0) {
                            closeNote(active, notes, ch, pitch, tick, ppq)
                        } else {
                            closeNote(active, notes, ch, pitch, tick, ppq)
                            active[ch to pitch] = tick to vel
                        }
                    }
                    status and 0xF0 == 0x80 -> {
                        val ch = status and 0x0F
                        val pitch = reader.byte(); reader.byte()
                        closeNote(active, notes, ch, pitch, tick, ppq)
                    }
                    status and 0xF0 == 0xC0 || status and 0xF0 == 0xD0 -> reader.byte()
                    else -> { reader.byte(); reader.byte() }
                }
            }
            // close hanging notes
            for ((key, value) in active.toList()) closeNote(active, notes, key.first, key.second, tick, ppq)
            if (notes.isNotEmpty()) {
                val length = notes.maxOf { it.endBeats }
                tracks.add(ImportedTrack(name, channelSeen, notes.sortedBy { it.startBeats }, length))
            }
            if (reader.position < trackEnd) reader.skip(trackEnd - reader.position)
        }
        return ImportedFile(bpm, tracks)
    }

    private fun closeNote(
        active: HashMap<Pair<Int, Int>, Pair<Int, Int>>,
        notes: MutableList<MidiNote>,
        channel: Int, pitch: Int, endTick: Int, ppq: Int
    ) {
        val start = active.remove(channel to pitch) ?: return
        val startBeats = start.first.toDouble() / ppq
        val length = ((endTick - start.first).coerceAtLeast(1)).toDouble() / ppq
        notes.add(MidiNote(startBeats = startBeats, lengthBeats = length, pitch = pitch, velocity = start.second))
    }

    private fun writeTrack(out: ByteArrayOutputStream, data: ByteArray) {
        out.write("MTrk".toByteArray(Charsets.US_ASCII))
        writeInt32(out, data.size)
        out.write(data)
    }

    private fun writeInt32(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 24) and 0xFF); out.write((v shr 16) and 0xFF)
        out.write((v shr 8) and 0xFF); out.write(v and 0xFF)
    }

    private fun writeInt16(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 8) and 0xFF); out.write(v and 0xFF)
    }

    private fun writeVarLen(out: ByteArrayOutputStream, value: Int) {
        var v = value
        val stack = IntArray(4)
        var n = 0
        stack[n++] = v and 0x7F
        v = v ushr 7
        while (v > 0) {
            stack[n++] = (v and 0x7F) or 0x80
            v = v ushr 7
        }
        for (i in n - 1 downTo 0) out.write(stack[i])
    }

    private class Reader(private val bytes: ByteArray) {
        var position = 0
            private set

        fun remaining(): Int = bytes.size - position
        fun byte(): Int = if (position < bytes.size) bytes[position++].toInt() and 0xFF else 0
        fun peek(): Int = if (position < bytes.size) bytes[position].toInt() and 0xFF else 0
        fun bytes(count: Int): ByteArray {
            val out = bytes.copyOfRange(position, minOf(bytes.size, position + count))
            position += count
            return out
        }
        fun text(count: Int): String = String(bytes(count), Charsets.US_ASCII)
        fun int16(): Int = (byte() shl 8) or byte()
        fun int32(): Int = (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
        fun skip(count: Int) { position = minOf(bytes.size, position + count) }
        fun varLen(): Int {
            var value = 0
            while (true) {
                val b = byte()
                value = (value shl 7) or (b and 0x7F)
                if (b and 0x80 == 0) break
            }
            return value
        }
    }
}
