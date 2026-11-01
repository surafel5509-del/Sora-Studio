package com.sorastudio.core.project

import com.sorastudio.core.common.Progressions
import com.sorastudio.core.midi.Arpeggiator
import com.sorastudio.core.midi.ArpPattern
import com.sorastudio.core.midi.ChordGenerator

data class ProjectTemplate(val id: String, val name: String, val description: String)

/** Starter projects and built-in drum grooves. Everything is generated in code. */
object ProjectFactory {

    val TEMPLATES: List<ProjectTemplate> = listOf(
        ProjectTemplate("empty", "Empty Project", "Four blank tracks, ready to build"),
        ProjectTemplate("beat-starter", "Beat Starter", "Drums, 808 and chords at 100 BPM"),
        ProjectTemplate("song-starter", "Song Starter", "Vocal, piano, bass and drums"),
        ProjectTemplate("lofi-sketch", "Lo-Fi Sketch", "Dusty kit with mellow keys at 80 BPM"),
        ProjectTemplate("dance-starter", "Dance Starter", "Four-on-the-floor at 124 BPM"),
    )

    fun create(templateId: String, name: String): SProject = when (templateId) {
        "beat-starter" -> beatStarter(name)
        "song-starter" -> songStarter(name)
        "lofi-sketch" -> lofiSketch(name)
        "dance-starter" -> danceStarter(name)
        else -> empty(name)
    }

    fun empty(name: String): SProject {
        val pattern = SDrumPattern.emptyPattern()
        var project = SProject(name = name, patterns = listOf(pattern))
        project = ProjectOps.addTrack(project, TrackType.DRUM, "Drums").first
        project = ProjectOps.addTrack(project, TrackType.INSTRUMENT, "Keys").first
        val (withBass, bass) = ProjectOps.addTrack(project, TrackType.INSTRUMENT, "Bass")
        project = ProjectOps.updateTrack(withBass, bass.id) { it.copy(presetId = "bass-sub") }
        project = ProjectOps.addTrack(project, TrackType.VOCAL, "Vocal").first
        return project
    }

    /** 16-step grooves as step indices per drum. */
    object Grooves {
        fun hipHop(kitId: String = "kit-studio"): SDrumPattern = groove(
            "Hip-Hop Groove", kitId, swing = 0.12f,
            mapOf(
                "KICK" to setOf(0, 7, 10),
                "SNARE" to setOf(4, 12),
                "HAT_CLOSED" to (0..15 step 2).toSet() + setOf(15),
                "HAT_OPEN" to setOf(14),
                "PERC" to setOf(3, 11),
            )
        )

        fun fourFloor(kitId: String = "kit-electro"): SDrumPattern = groove(
            "Four on the Floor", kitId, swing = 0f,
            mapOf(
                "KICK" to setOf(0, 4, 8, 12),
                "CLAP" to setOf(4, 12),
                "HAT_CLOSED" to (2..15 step 4).toSet(),
                "HAT_OPEN" to setOf(2, 10),
                "BASS_808" to setOf(0, 4, 8, 12),
            )
        )

        fun lofi(kitId: String = "kit-lofi"): SDrumPattern = groove(
            "Lo-Fi Pocket", kitId, swing = 0.22f,
            mapOf(
                "KICK" to setOf(0, 10),
                "SNARE" to setOf(4, 12),
                "HAT_CLOSED" to (0..15 step 2).toSet(),
                "RIM" to setOf(7),
            )
        )

        fun trap(kitId: String = "kit-808"): SDrumPattern = groove(
            "Trap Skeleton", kitId, swing = 0.05f,
            mapOf(
                "KICK" to setOf(0, 6, 10),
                "SNARE" to setOf(8),
                "HAT_CLOSED" to (0..15).toSet(),
                "BASS_808" to setOf(0, 6, 10, 14),
            )
        )

        fun pop(kitId: String = "kit-studio"): SDrumPattern = groove(
            "Pop Backbeat", kitId, swing = 0f,
            mapOf(
                "KICK" to setOf(0, 8, 10),
                "SNARE" to setOf(4, 12),
                "HAT_CLOSED" to (0..15 step 2).toSet(),
                "CLAP" to setOf(12),
            )
        )

        val ALL: List<(String) -> SDrumPattern> = listOf(::hipHop, ::fourFloor, ::lofi, ::trap, ::pop)

        private fun groove(name: String, kitId: String, swing: Float, hits: Map<String, Set<Int>>): SDrumPattern {
            val base = SDrumPattern.emptyPattern(name = name, steps = 16, kitId = kitId)
            val lanes = base.lanes.map { lane ->
                val on = hits[lane.drum] ?: emptySet()
                lane.copy(steps = lane.steps.mapIndexed { i, s -> s.copy(active = i in on) })
            }
            return base.copy(swing = swing, lanes = lanes)
        }
    }

    private fun beatStarter(name: String): SProject {
        val pattern = Grooves.trap("kit-808")
        var project = SProject(name = name, bpm = 100.0, patterns = listOf(pattern), templateName = "Beat Starter")
        lateinit var drums: STrack; lateinit var bass: STrack; lateinit var keys: STrack
        project = ProjectOps.addTrack(project, TrackType.DRUM, "Drums").also { drums = it.second }.first
        project = ProjectOps.updateTrack(project, drums.id) { it.copy(drumKitId = "kit-808") }
        project = ProjectOps.addTrack(project, TrackType.INSTRUMENT, "808 Bass").also { bass = it.second }.first
        project = ProjectOps.updateTrack(project, bass.id) { it.copy(presetId = "808-classic") }
        project = ProjectOps.addTrack(project, TrackType.INSTRUMENT, "Keys").also { keys = it.second }.first
        project = ProjectOps.updateTrack(project, keys.id) { it.copy(presetId = "ep-rhodes") }

        project = ProjectOps.addClip(project, SClip(
            trackId = drums.id, name = pattern.name, type = ClipType.PATTERN,
            startBeats = 0.0, lengthBeats = 16.0, patternId = pattern.id))

        // A minor 808 line following i–VI–III–VII
        val roots = listOf(33, 29, 36, 31) // A1 F1 C2 G1-ish roots
        val bassNotes = mutableListOf<SNote>()
        roots.forEachIndexed { bar, root ->
            bassNotes.add(SNote(startBeats = bar * 4.0, lengthBeats = 1.5, pitch = root, velocity = 110))
            bassNotes.add(SNote(startBeats = bar * 4.0 + 2.5, lengthBeats = 1.0, pitch = root, velocity = 95))
        }
        project = ProjectOps.addClip(project, SClip(
            trackId = bass.id, name = "808 Line", type = ClipType.MIDI,
            startBeats = 0.0, lengthBeats = 16.0, notes = bassNotes))

        val chordNotes = ChordGenerator.progressionNotes(57, Progressions.ALL[3], beatsPerChord = 4.0)
            .map { it.toS() }
        project = ProjectOps.addClip(project, SClip(
            trackId = keys.id, name = "Chords", type = ClipType.MIDI,
            startBeats = 0.0, lengthBeats = 16.0, notes = chordNotes))
        return project
    }

    private fun songStarter(name: String): SProject {
        val pattern = Grooves.pop()
        var project = SProject(name = name, bpm = 96.0, patterns = listOf(pattern), templateName = "Song Starter")
        lateinit var vocal: STrack; lateinit var piano: STrack; lateinit var bass: STrack; lateinit var drums: STrack
        project = ProjectOps.addTrack(project, TrackType.VOCAL, "Lead Vocal").also { vocal = it.second }.first
        project = ProjectOps.updateTrack(project, vocal.id) {
            it.copy(sendReverb = 0.25f, fxChain = listOf(
                SEffect(type = "HIGH_PASS", params = mapOf("freq" to 90f, "res" to 0.71f)),
                SEffect(type = "COMPRESSOR", params = mapOf("threshold" to -20f, "ratio" to 3f, "attack" to 15f, "release" to 180f, "makeup" to 3f, "knee" to 8f)),
                SEffect(type = "THREE_BAND_EQ", params = mapOf("low" to -2f, "mid" to 2f, "high" to 3.5f, "lowFreq" to 120f, "highFreq" to 9000f)),
            ))
        }
        project = ProjectOps.addTrack(project, TrackType.INSTRUMENT, "Piano").also { piano = it.second }.first
        project = ProjectOps.addTrack(project, TrackType.INSTRUMENT, "Bass").also { bass = it.second }.first
        project = ProjectOps.updateTrack(project, bass.id) { it.copy(presetId = "bass-sub") }
        project = ProjectOps.addTrack(project, TrackType.DRUM, "Drums").also { drums = it.second }.first

        val chords = ChordGenerator.progressionNotes(60, Progressions.ALL[0], beatsPerChord = 4.0).map { it.toS() }
        project = ProjectOps.addClip(project, SClip(
            trackId = piano.id, name = "Verse Chords", type = ClipType.MIDI,
            startBeats = 0.0, lengthBeats = 16.0, notes = chords))
        val bassNotes = listOf(36, 43, 45, 41).mapIndexed { bar, root ->
            SNote(startBeats = bar * 4.0, lengthBeats = 3.8, pitch = root, velocity = 95)
        }
        project = ProjectOps.addClip(project, SClip(
            trackId = bass.id, name = "Bass Roots", type = ClipType.MIDI,
            startBeats = 0.0, lengthBeats = 16.0, notes = bassNotes))
        project = ProjectOps.addClip(project, SClip(
            trackId = drums.id, name = pattern.name, type = ClipType.PATTERN,
            startBeats = 0.0, lengthBeats = 16.0, patternId = pattern.id))
        return project
    }

    private fun lofiSketch(name: String): SProject {
        val pattern = Grooves.lofi()
        var project = SProject(name = name, bpm = 80.0, patterns = listOf(pattern), templateName = "Lo-Fi Sketch")
        lateinit var keys: STrack; lateinit var drums: STrack; lateinit var melody: STrack
        project = ProjectOps.addTrack(project, TrackType.DRUM, "Dusty Drums").also { drums = it.second }.first
        project = ProjectOps.updateTrack(project, drums.id) { it.copy(drumKitId = "kit-lofi") }
        project = ProjectOps.addTrack(project, TrackType.INSTRUMENT, "Wurli Keys").also { keys = it.second }.first
        project = ProjectOps.updateTrack(project, keys.id) {
            it.copy(presetId = "ep-wurli", fxChain = listOf(
                SEffect(type = "SATURATION", params = mapOf("drive" to 30f, "output" to -2f)),
                SEffect(type = "LOW_PASS", params = mapOf("freq" to 4500f, "res" to 0.5f)),
            ), sendReverb = 0.2f)
        }
        project = ProjectOps.addTrack(project, TrackType.INSTRUMENT, "Music Box").also { melody = it.second }.first
        project = ProjectOps.updateTrack(project, melody.id) { it.copy(presetId = "bell-musicbox", sendDelay = 0.15f) }

        project = ProjectOps.addClip(project, SClip(
            trackId = drums.id, name = pattern.name, type = ClipType.PATTERN,
            startBeats = 0.0, lengthBeats = 16.0, patternId = pattern.id))
        val chords = ChordGenerator.progressionNotes(53, Progressions.ALL[5], beatsPerChord = 4.0).map { it.toS() }
        project = ProjectOps.addClip(project, SClip(
            trackId = keys.id, name = "Jazzy Chords", type = ClipType.MIDI,
            startBeats = 0.0, lengthBeats = 12.0, notes = chords))
        val arp = Arpeggiator.generate(listOf(72, 76, 79, 83), ArpPattern.UP_DOWN, rateBeats = 0.5, totalBeats = 12.0, velocity = 75).map { it.toS() }
        project = ProjectOps.addClip(project, SClip(
            trackId = melody.id, name = "Sprinkles", type = ClipType.MIDI,
            startBeats = 0.0, lengthBeats = 12.0, notes = arp))
        return project
    }

    private fun danceStarter(name: String): SProject {
        val pattern = Grooves.fourFloor()
        var project = SProject(name = name, bpm = 124.0, patterns = listOf(pattern), templateName = "Dance Starter")
        lateinit var drums: STrack; lateinit var bass: STrack; lateinit var arpTrack: STrack
        project = ProjectOps.addTrack(project, TrackType.DRUM, "Drums").also { drums = it.second }.first
        project = ProjectOps.updateTrack(project, drums.id) { it.copy(drumKitId = "kit-electro") }
        project = ProjectOps.addTrack(project, TrackType.INSTRUMENT, "Acid Bass").also { bass = it.second }.first
        project = ProjectOps.updateTrack(project, bass.id) { it.copy(presetId = "bass-acid") }
        project = ProjectOps.addTrack(project, TrackType.INSTRUMENT, "Arp Lead").also { arpTrack = it.second }.first
        project = ProjectOps.updateTrack(project, arpTrack.id) { it.copy(presetId = "pluck-soft", sendDelay = 0.3f) }

        project = ProjectOps.addClip(project, SClip(
            trackId = drums.id, name = pattern.name, type = ClipType.PATTERN,
            startBeats = 0.0, lengthBeats = 32.0, patternId = pattern.id))
        val bassNotes = (0 until 32).map { i ->
            val bar = i / 4
            val root = listOf(45, 45, 41, 43)[bar % 4]
            SNote(startBeats = i.toDouble(), lengthBeats = 0.45, pitch = root - 12, velocity = 105)
        }
        project = ProjectOps.addClip(project, SClip(
            trackId = bass.id, name = "Driving Bass", type = ClipType.MIDI,
            startBeats = 0.0, lengthBeats = 32.0, notes = bassNotes))
        val arpNotes = Arpeggiator.generate(listOf(69, 72, 76), ArpPattern.UP, rateBeats = 0.25, totalBeats = 32.0, octaves = 2, velocity = 85).map { it.toS() }
        project = ProjectOps.addClip(project, SClip(
            trackId = arpTrack.id, name = "Arp", type = ClipType.MIDI,
            startBeats = 0.0, lengthBeats = 32.0, notes = arpNotes))
        return project
    }
}
