package com.sorastudio.core.common

import kotlin.math.pow

/** MIDI note helpers and music-theory primitives shared by every module. */
object NoteUtils {
    private val NAMES_SHARP = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** Equal-temperament frequency for a MIDI note number (A4 = 69 = 440 Hz). */
    fun midiToFreq(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)

    fun midiToFreq(midi: Double): Double = 440.0 * 2.0.pow((midi - 69.0) / 12.0)

    /** Nearest MIDI note for an arbitrary frequency. Returns -1 for silence/invalid input. */
    fun freqToMidi(freq: Double): Int {
        if (freq <= 0.0) return -1
        val exact = 69.0 + 12.0 * (kotlin.math.log2(freq / 440.0))
        return kotlin.math.round(exact).toInt()
    }

    fun freqToMidiExact(freq: Double): Double {
        if (freq <= 0.0) return -1.0
        return 69.0 + 12.0 * kotlin.math.log2(freq / 440.0)
    }

    fun noteName(midi: Int): String {
        if (midi < 0) return "—"
        val name = NAMES_SHARP[midi % 12]
        val octave = midi / 12 - 1
        return "$name$octave"
    }

    fun pitchClassName(pitchClass: Int): String = NAMES_SHARP[((pitchClass % 12) + 12) % 12]

    fun isBlackKey(midi: Int): Boolean = when (midi % 12) {
        1, 3, 6, 8, 10 -> true
        else -> false
    }
}

/** A musical scale defined by its semitone intervals from the root. */
enum class ScaleType(val displayName: String, val intervals: IntArray) {
    MAJOR("Major", intArrayOf(0, 2, 4, 5, 7, 9, 11)),
    NATURAL_MINOR("Natural Minor", intArrayOf(0, 2, 3, 5, 7, 8, 10)),
    HARMONIC_MINOR("Harmonic Minor", intArrayOf(0, 2, 3, 5, 7, 8, 11)),
    MELODIC_MINOR("Melodic Minor", intArrayOf(0, 2, 3, 5, 7, 9, 11)),
    DORIAN("Dorian", intArrayOf(0, 2, 3, 5, 7, 9, 10)),
    PHRYGIAN("Phrygian", intArrayOf(0, 1, 3, 5, 7, 8, 10)),
    LYDIAN("Lydian", intArrayOf(0, 2, 4, 6, 7, 9, 11)),
    MIXOLYDIAN("Mixolydian", intArrayOf(0, 2, 4, 5, 7, 9, 10)),
    MAJOR_PENTATONIC("Major Pentatonic", intArrayOf(0, 2, 4, 7, 9)),
    MINOR_PENTATONIC("Minor Pentatonic", intArrayOf(0, 3, 5, 7, 10)),
    BLUES("Blues", intArrayOf(0, 3, 5, 6, 7, 10)),
    CHROMATIC("Chromatic", intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11));

    fun contains(rootPitchClass: Int, midi: Int): Boolean {
        val pc = (((midi - rootPitchClass) % 12) + 12) % 12
        return intervals.contains(pc)
    }

    /** Nearest in-scale MIDI note to [midi] (ties resolve upward). Used by pitch correction. */
    fun snap(rootPitchClass: Int, midi: Int): Int {
        if (contains(rootPitchClass, midi)) return midi
        for (d in 1..6) {
            if (contains(rootPitchClass, midi + d)) return midi + d
            if (contains(rootPitchClass, midi - d)) return midi - d
        }
        return midi
    }
}

enum class ChordQuality(val displayName: String, val suffix: String, val intervals: IntArray) {
    MAJOR("Major", "", intArrayOf(0, 4, 7)),
    MINOR("Minor", "m", intArrayOf(0, 3, 7)),
    MAJ7("Major 7", "maj7", intArrayOf(0, 4, 7, 11)),
    MIN7("Minor 7", "m7", intArrayOf(0, 3, 7, 10)),
    DOM7("Dominant 7", "7", intArrayOf(0, 4, 7, 10)),
    DIM("Diminished", "dim", intArrayOf(0, 3, 6)),
    AUG("Augmented", "aug", intArrayOf(0, 4, 8)),
    SUS2("Suspended 2", "sus2", intArrayOf(0, 2, 7)),
    SUS4("Suspended 4", "sus4", intArrayOf(0, 5, 7)),
    ADD9("Add 9", "add9", intArrayOf(0, 4, 7, 14)),
    MIN9("Minor 9", "m9", intArrayOf(0, 3, 7, 10, 14)),
    POWER("Power", "5", intArrayOf(0, 7, 12));

    fun notes(rootMidi: Int): List<Int> = intervals.map { rootMidi + it }
    fun label(rootMidi: Int): String = NoteUtils.pitchClassName(rootMidi) + suffix
}

/** Common chord progressions expressed as scale degrees (0-based) + qualities relative to key. */
object Progressions {
    data class Progression(val name: String, val chords: List<Pair<Int, ChordQuality>>)

    /** Degrees are semitone offsets from the key root so they work in any key. */
    val ALL: List<Progression> = listOf(
        Progression("I–V–vi–IV (Pop)", listOf(0 to ChordQuality.MAJOR, 7 to ChordQuality.MAJOR, 9 to ChordQuality.MINOR, 5 to ChordQuality.MAJOR)),
        Progression("vi–IV–I–V (Sensitive)", listOf(9 to ChordQuality.MINOR, 5 to ChordQuality.MAJOR, 0 to ChordQuality.MAJOR, 7 to ChordQuality.MAJOR)),
        Progression("I–vi–IV–V (50s)", listOf(0 to ChordQuality.MAJOR, 9 to ChordQuality.MINOR, 5 to ChordQuality.MAJOR, 7 to ChordQuality.MAJOR)),
        Progression("i–VI–III–VII (Andalusian-ish)", listOf(0 to ChordQuality.MINOR, 8 to ChordQuality.MAJOR, 3 to ChordQuality.MAJOR, 10 to ChordQuality.MAJOR)),
        Progression("i–iv–v (Minor loop)", listOf(0 to ChordQuality.MINOR, 5 to ChordQuality.MINOR, 7 to ChordQuality.MINOR)),
        Progression("ii–V–I (Jazz)", listOf(2 to ChordQuality.MIN7, 7 to ChordQuality.DOM7, 0 to ChordQuality.MAJ7)),
        Progression("I–IV–V (Blues/Rock)", listOf(0 to ChordQuality.MAJOR, 5 to ChordQuality.MAJOR, 7 to ChordQuality.MAJOR)),
        Progression("i–VII–VI–VII (Dark)", listOf(0 to ChordQuality.MINOR, 10 to ChordQuality.MAJOR, 8 to ChordQuality.MAJOR, 10 to ChordQuality.MAJOR)),
    )
}
