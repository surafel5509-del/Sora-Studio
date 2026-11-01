package com.sorastudio.core.midi

import com.sorastudio.core.common.ChordQuality
import com.sorastudio.core.common.Progressions
import com.sorastudio.core.common.newId
import kotlin.math.round
import kotlin.random.Random

/** A single MIDI note. Times are in beats (quarter notes) relative to its clip. */
data class MidiNote(
    val id: String = newId("note"),
    val startBeats: Double,
    val lengthBeats: Double,
    val pitch: Int,
    val velocity: Int = 100 // 1..127
) {
    val endBeats: Double get() = startBeats + lengthBeats
    fun velocityFloat(): Float = (velocity.coerceIn(1, 127) / 127f)
}

sealed class ScheduledEvent {
    abstract val atBeats: Double
    data class NoteOn(override val atBeats: Double, val note: MidiNote) : ScheduledEvent()
    data class NoteOff(override val atBeats: Double, val pitch: Int, val noteId: String) : ScheduledEvent()
}

/**
 * Produces sample-block scheduling data: for a block covering [fromBeats, toBeats)
 * of a (possibly looping) clip, returns events sorted by beat position. The audio
 * renderer converts each event's beat into an exact frame offset inside the block.
 */
object MidiScheduler {

    fun eventsBetween(
        notes: List<MidiNote>,
        clipLengthBeats: Double,
        fromBeats: Double,
        toBeats: Double,
        clipStartOffsetBeats: Double = 0.0
    ): List<ScheduledEvent> {
        if (notes.isEmpty() || toBeats <= fromBeats || clipLengthBeats <= 0) return emptyList()
        val out = mutableListOf<ScheduledEvent>()
        // Support clip looping by iterating loop iterations intersecting the window.
        val firstLoop = kotlin.math.floor((fromBeats - clipStartOffsetBeats) / clipLengthBeats).toInt()
        val lastLoop = kotlin.math.floor((toBeats - clipStartOffsetBeats) / clipLengthBeats).toInt()
        for (loop in firstLoop..lastLoop) {
            val base = clipStartOffsetBeats + loop * clipLengthBeats
            for (note in notes) {
                val on = base + note.startBeats
                val off = base + note.endBeats
                if (on >= fromBeats && on < toBeats) out.add(ScheduledEvent.NoteOn(on, note))
                if (off >= fromBeats && off < toBeats) out.add(ScheduledEvent.NoteOff(off, note.pitch, note.id))
            }
        }
        return out.sortedBy { it.atBeats }
    }

    /** Notes that should be sounding at a position (for seek / chase). */
    fun notesActiveAt(notes: List<MidiNote>, clipLengthBeats: Double, positionBeats: Double, clipStartOffsetBeats: Double = 0.0): List<MidiNote> {
        val local = ((positionBeats - clipStartOffsetBeats) % clipLengthBeats + clipLengthBeats) % clipLengthBeats
        return notes.filter { local >= it.startBeats && local < it.endBeats }
    }
}

/** Musical editing transforms. All return new lists; inputs are never mutated. */
object MidiTransform {

    fun quantize(notes: List<MidiNote>, gridBeats: Double, strength: Float = 1f, quantizeEnds: Boolean = false): List<MidiNote> {
        if (gridBeats <= 0) return notes
        val s = strength.coerceIn(0f, 1f)
        return notes.map { n ->
            val snappedStart = round(n.startBeats / gridBeats) * gridBeats
            val newStart = n.startBeats + (snappedStart - n.startBeats) * s
            val newLength = if (quantizeEnds) {
                val snappedEnd = round(n.endBeats / gridBeats) * gridBeats
                val end = n.endBeats + (snappedEnd - n.endBeats) * s
                (end - newStart).coerceAtLeast(gridBeats / 2)
            } else n.lengthBeats
            n.copy(startBeats = newStart, lengthBeats = newLength)
        }
    }

    /** Random timing/velocity offsets. [timingBeats] is max ± offset; [velocityAmount] 0..1 ± range. */
    fun humanize(notes: List<MidiNote>, timingBeats: Double = 0.03, velocityAmount: Float = 0.15f, random: Random = Random): List<MidiNote> =
        notes.map { n ->
            val timing = (random.nextDouble() * 2 - 1) * timingBeats
            val velDelta = ((random.nextFloat() * 2 - 1) * velocityAmount * 127).toInt()
            n.copy(
                startBeats = (n.startBeats + timing).coerceAtLeast(0.0),
                velocity = (n.velocity + velDelta).coerceIn(1, 127)
            )
        }

    fun transpose(notes: List<MidiNote>, semitones: Int): List<MidiNote> =
        notes.map { it.copy(pitch = (it.pitch + semitones).coerceIn(0, 127)) }

    fun scaleVelocities(notes: List<MidiNote>, factor: Float): List<MidiNote> =
        notes.map { it.copy(velocity = (it.velocity * factor).toInt().coerceIn(1, 127)) }

    fun duplicateNotes(notes: List<MidiNote>, offsetBeats: Double): List<MidiNote> =
        notes.map { it.copy(id = newId("note"), startBeats = it.startBeats + offsetBeats) }

    /** Extend each note to the start of the next note at the same pitch (or clip end). */
    fun legato(notes: List<MidiNote>, clipLengthBeats: Double): List<MidiNote> {
        val sorted = notes.sortedBy { it.startBeats }
        return sorted.mapIndexed { index, note ->
            val nextSamePitch = sorted.drop(index + 1).firstOrNull { it.pitch == note.pitch }
            val end = nextSamePitch?.startBeats ?: clipLengthBeats
            note.copy(lengthBeats = (end - note.startBeats).coerceAtLeast(0.05))
        }
    }
}

enum class ArpPattern { UP, DOWN, UP_DOWN, DOWN_UP, RANDOM }

object Arpeggiator {
    fun generate(
        chordPitches: List<Int>,
        pattern: ArpPattern = ArpPattern.UP,
        rateBeats: Double = 0.25,
        totalBeats: Double = 4.0,
        octaves: Int = 1,
        velocity: Int = 100,
        gate: Float = 0.9f,
        startBeats: Double = 0.0,
        random: Random = Random
    ): List<MidiNote> {
        if (chordPitches.isEmpty() || rateBeats <= 0) return emptyList()
        val expanded = mutableListOf<Int>()
        val sorted = chordPitches.sorted()
        for (o in 0 until octaves.coerceIn(1, 4)) for (p in sorted) expanded.add(p + o * 12)
        val sequence: List<Int> = when (pattern) {
            ArpPattern.UP -> expanded
            ArpPattern.DOWN -> expanded.reversed()
            ArpPattern.UP_DOWN -> if (expanded.size > 2) expanded + expanded.drop(1).dropLast(1).reversed() else expanded
            ArpPattern.DOWN_UP -> if (expanded.size > 2) expanded.reversed() + expanded.drop(1).dropLast(1) else expanded.reversed()
            ArpPattern.RANDOM -> expanded
        }
        val notes = mutableListOf<MidiNote>()
        var t = startBeats
        var idx = 0
        while (t < startBeats + totalBeats - 0.0001) {
            val pitch = if (pattern == ArpPattern.RANDOM) expanded.random(random) else sequence[idx % sequence.size]
            notes.add(MidiNote(startBeats = t, lengthBeats = rateBeats * gate, pitch = pitch.coerceIn(0, 127), velocity = velocity))
            t += rateBeats
            idx++
        }
        return notes
    }
}

object ChordGenerator {
    /** Build block-chord notes for a progression, one chord per [beatsPerChord]. */
    fun progressionNotes(
        rootMidi: Int,
        progression: Progressions.Progression,
        beatsPerChord: Double = 4.0,
        velocity: Int = 92,
        strumBeats: Double = 0.0,
        startBeats: Double = 0.0
    ): List<MidiNote> {
        val notes = mutableListOf<MidiNote>()
        progression.chords.forEachIndexed { chordIndex, (offset, quality) ->
            val chordRoot = rootMidi + offset
            quality.notes(chordRoot).forEachIndexed { noteIndex, pitch ->
                notes.add(
                    MidiNote(
                        startBeats = startBeats + chordIndex * beatsPerChord + noteIndex * strumBeats,
                        lengthBeats = beatsPerChord - noteIndex * strumBeats,
                        pitch = pitch.coerceIn(0, 127),
                        velocity = velocity
                    )
                )
            }
        }
        return notes
    }

    fun chordNotes(rootMidi: Int, quality: ChordQuality, startBeats: Double, lengthBeats: Double, velocity: Int = 100): List<MidiNote> =
        quality.notes(rootMidi).map { pitch ->
            MidiNote(startBeats = startBeats, lengthBeats = lengthBeats, pitch = pitch.coerceIn(0, 127), velocity = velocity)
        }
}
