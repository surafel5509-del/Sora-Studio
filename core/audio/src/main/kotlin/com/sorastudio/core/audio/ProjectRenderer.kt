package com.sorastudio.core.audio

import com.sorastudio.core.common.LevelMath
import com.sorastudio.core.common.TempoMath
import com.sorastudio.core.dsp.AudioBufferOps
import com.sorastudio.core.dsp.DrumSynth
import com.sorastudio.core.dsp.DrumType
import com.sorastudio.core.dsp.EffectCatalog
import com.sorastudio.core.dsp.EffectChain
import com.sorastudio.core.dsp.EffectType
import com.sorastudio.core.dsp.InstrumentLibrary
import com.sorastudio.core.dsp.Limiter
import com.sorastudio.core.dsp.MeterState
import com.sorastudio.core.dsp.MetronomeClick
import com.sorastudio.core.dsp.MixerCore
import com.sorastudio.core.dsp.PolySynth
import com.sorastudio.core.dsp.SamplePlayer
import com.sorastudio.core.dsp.SynthPatch
import com.sorastudio.core.project.ClipType
import com.sorastudio.core.project.SEffect
import com.sorastudio.core.project.SProject
import com.sorastudio.core.project.STrack
import com.sorastudio.core.project.TrackType
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Live (audition) events injected from the UI thread and applied on the audio thread. */
sealed class LiveEvent {
    data class NoteOn(val key: String, val presetId: String, val midi: Int, val velocity: Float, val synthParams: Map<String, Float> = emptyMap()) : LiveEvent()
    data class NoteOff(val key: String, val midi: Int) : LiveEvent()
    data class AllOff(val key: String) : LiveEvent()
    data class DrumHit(val kitId: String, val drum: DrumType, val velocity: Float, val customData: FloatArray? = null) : LiveEvent()
    data class SampleHit(val data: FloatArray, val stereo: Boolean, val rate: Double = 1.0, val gain: Float = 0.9f) : LiveEvent()
    data class StopAllLive(val unused: Int = 0) : LiveEvent()
}

/**
 * Pull-based multitrack renderer: the heart of the audio engine.
 *
 * Rendering is a pure function of (project, position, live events) — the same code
 * path drives real-time playback (via AudioTrack) and offline exports, so what
 * you hear is exactly what exports. All buffers are preallocated; `render()` does
 * no I/O and minimal allocation (event lists per block).
 *
 * Threading: `render()` runs on the audio thread. Project updates and live events
 * arrive via volatile references / lock-free queues.
 */
private data class NoteEvent(val frame: Int, val isOn: Boolean, val pitch: Int, val velocity: Float)

class ProjectRenderer(
    val sampleRate: Int,
    private val assetProvider: (path: String) -> FloatArray?,
    var maxBlockFrames: Int = 4096
) {
    @Volatile
    private var pendingProject: SProject? = null
    private var project: SProject = SProject()

    @Volatile
    var playing: Boolean = false
    @Volatile
    private var pendingSeekBeats: Double? = null
    @Volatile
    var positionFrames: Long = 0
        private set
    @Volatile
    var metronomeAudible: Boolean = false
    @Volatile
    var masterVolumeLinear: Float = 0.9f

    private val liveQueue = ConcurrentLinkedQueue<LiveEvent>()

    // ---- runtime state (audio thread only) ----
    private class TrackRuntime(val trackId: String, blockFrames: Int) {
        val buffer = FloatArray(blockFrames * 2)
        val segment = FloatArray(blockFrames * 2)
        val synth = PolySynth()
        val sampler = SamplePlayer(maxVoices = 32)
        val fxChain = EffectChain(blockFrames)
        val fxIds = mutableListOf<String>()
        var fxSignature: String = ""
        var patchSignature: String = ""
        val meter = MeterState()
        var lastDrumPatternPass = -1L
    }

    private val trackRuntimes = HashMap<String, TrackRuntime>()
    private val liveSynths = HashMap<String, PolySynth>()
    private val liveSampler = SamplePlayer(maxVoices = 32)
    private val kitCache = HashMap<String, Map<DrumType, FloatArray>>()

    private var master = FloatArray(maxBlockFrames * 2)
    private var sendReverbBuf = FloatArray(maxBlockFrames * 2)
    private var sendDelayBuf = FloatArray(maxBlockFrames * 2)
    private val sendReverbChain = EffectChain(maxBlockFrames)
    private val sendDelayChain = EffectChain(maxBlockFrames)
    private val masterChain = EffectChain(maxBlockFrames)
    private val safetyLimiter = Limiter()
    val masterMeter = MeterState()
    private var masterFxSignature = ""

    private val clickAccent: FloatArray by lazy { MetronomeClick.render(sampleRate, true) }
    private val clickNormal: FloatArray by lazy { MetronomeClick.render(sampleRate, false) }

    private val drumRandom = java.util.Random(4242)

    init {
        safetyLimiter.prepare(sampleRate)
        safetyLimiter.setParams(mapOf("ceiling" to -0.5f, "release" to 80f, "input" to 0f))
        val reverb = EffectCatalog.create(EffectType.REVERB, sampleRate)
        reverb.setParams(mapOf("size" to 62f, "damping" to 45f, "width" to 85f, "predelay" to 12f))
        sendReverbChain.slots.add(EffectChain.Slot(reverb, true, 1f))
        val delay = EffectCatalog.create(EffectType.PING_PONG_DELAY, sampleRate)
        delay.setParams(mapOf("time" to 375f, "feedback" to 38f, "tone" to 5500f))
        sendDelayChain.slots.add(EffectChain.Slot(delay, true, 1f))
    }

    // ------------------------------------------------------------------ API

    fun setProject(newProject: SProject) {
        pendingProject = newProject
    }

    fun seek(beats: Double) {
        pendingSeekBeats = beats
    }

    fun positionBeats(): Double {
        val bpm = project.bpm
        return TempoMath.samplesToBeats(positionFrames, bpm, sampleRate)
    }

    fun currentProject(): SProject = pendingProject ?: project

    fun postLive(event: LiveEvent) = liveQueue.offer(event)

    fun meterFor(trackId: String): MeterState? = trackRuntimes[trackId]?.meter

    /** Update send delay time to stay in sync with tempo (dotted 8th). */
    fun syncSendDelayToTempo(bpm: Double) {
        val ms = (TempoMath.secondsPerBeat(bpm) * 0.75 * 1000).toFloat()
        (sendDelayChain.slots.firstOrNull()?.effect)?.setParams(mapOf("time" to ms))
    }

    // ------------------------------------------------------------------ render

    fun render(out: FloatArray, frames: Int) {
        ensureBuffers(frames)
        applyPendingChanges(frames)
        drainLiveEvents()
        AudioBufferOps.clear(master, frames)
        AudioBufferOps.clear(sendReverbBuf, frames)
        AudioBufferOps.clear(sendDelayBuf, frames)

        val proj = project
        if (playing) {
            val blockStartBeats = positionBeats()
            val blockEndBeats = TempoMath.samplesToBeats(positionFrames + frames, proj.bpm, sampleRate)
            val anySolo = proj.tracks.any { it.solo }
            for (track in proj.tracks) {
                val runtime = trackRuntimes.getOrPut(track.id) { TrackRuntime(track.id, maxBlockFrames) }
                renderTrack(track, runtime, proj, frames, blockStartBeats, blockEndBeats, anySolo)
            }
            if (metronomeAudible || proj.metronomeEnabled) {
                renderMetronome(frames, blockStartBeats, proj)
            }
            positionFrames += frames
            handleLoopOrEnd(proj)
        }

        // Sends
        sendReverbChain.process(sendReverbBuf, frames)
        sendDelayChain.process(sendDelayBuf, frames)
        AudioBufferOps.addWithGain(master, sendReverbBuf, frames, 0.9f)
        AudioBufferOps.addWithGain(master, sendDelayBuf, frames, 0.9f)

        // Live audition (keyboards, pads, previews) — always audible.
        for ((_, synth) in liveSynths) {
            synth.sampleRate = sampleRate
            synth.renderAdd(master, frames, 0.85f, 0.85f)
        }
        liveSampler.renderAdd(master, frames)

        // Master chain
        masterChain.process(master, frames)
        val masterGain = LevelMath.dbToGain(proj.master.volumeDb) * masterVolumeLinear
        AudioBufferOps.applyGain(master, frames, masterGain)
        if (proj.master.limiterEnabled) safetyLimiter.process(master, frames)
        AudioBufferOps.clamp(master, frames)
        masterMeter.update(master, frames, sampleRate)

        System.arraycopy(master, 0, out, 0, min(out.size, frames * 2))
    }

    private fun ensureBuffers(frames: Int) {
        if (frames > maxBlockFrames) {
            maxBlockFrames = frames
            master = FloatArray(frames * 2)
            sendReverbBuf = FloatArray(frames * 2)
            sendDelayBuf = FloatArray(frames * 2)
            // Track runtimes reallocate lazily below.
            for ((_, rt) in trackRuntimes) {
                // buffer size is fixed per runtime; recreate if needed
            }
        }
    }

    private fun applyPendingChanges(frames: Int) {
        pendingProject?.let { newProject ->
            project = newProject
            pendingProject = null
            // Drop runtimes for removed tracks
            val ids = newProject.tracks.map { it.id }.toSet()
            val it = trackRuntimes.entries.iterator()
            while (it.hasNext()) if (it.next().key !in ids) it.remove()
            for (track in newProject.tracks) {
                val runtime = trackRuntimes.getOrPut(track.id) { TrackRuntime(track.id, maxBlockFrames) }
                syncTrackRuntime(track, runtime)
            }
            syncMasterChain(newProject.master.fxChain)
            syncSendDelayToTempo(newProject.bpm)
        }
        pendingSeekBeats?.let { beats ->
            positionFrames = TempoMath.beatsToSamples(beats.coerceAtLeast(0.0), project.bpm, sampleRate)
            allTrackNotesOff()
            pendingSeekBeats = null
        }
    }

    private fun allTrackNotesOff() {
        for ((_, rt) in trackRuntimes) {
            rt.synth.allNotesOff()
        }
    }

    private fun handleLoopOrEnd(proj: SProject) {
        if (proj.loopEnabled && proj.loopEndBeats > proj.loopStartBeats) {
            val loopEndFrames = TempoMath.beatsToSamples(proj.loopEndBeats, proj.bpm, sampleRate)
            if (positionFrames >= loopEndFrames) {
                val loopStartFrames = TempoMath.beatsToSamples(proj.loopStartBeats, proj.bpm, sampleRate)
                val overshoot = positionFrames - loopEndFrames
                positionFrames = loopStartFrames + overshoot
                allTrackNotesOff()
            }
        }
    }

    // ------------------------------------------------------------------ runtime sync

    private fun syncTrackRuntime(track: STrack, rt: TrackRuntime) {
        val sig = track.fxChain.joinToString("|") { fxSignatureOf(it) }
        if (sig != rt.fxSignature) {
            rt.fxSignature = sig
            rt.fxChain.slots.clear()
            rt.fxIds.clear()
            for (fx in track.fxChain) {
                val type = runCatching { EffectType.valueOf(fx.type) }.getOrNull() ?: continue
                val effect = EffectCatalog.create(type, sampleRate)
                effect.setParams(EffectCatalog.defaultParams(type) + fx.params)
                rt.fxChain.slots.add(EffectChain.Slot(effect, fx.enabled, fx.wet))
                rt.fxIds.add(fx.id)
            }
        } else {
            // Update params/enable/wet in place (cheap, click-free for most params)
            track.fxChain.forEachIndexed { index, fx ->
                val slot = rt.fxChain.slots.getOrNull(index) ?: return@forEachIndexed
                slot.enabled = fx.enabled
                slot.wet = fx.wet
                slot.effect.setParams(fx.params)
            }
        }
        val patchSig = track.presetId + track.synthParams.entries.joinToString(",") { "${it.key}=${it.value}" }
        if (patchSig != rt.patchSignature) {
            rt.patchSignature = patchSig
            rt.synth.patch = patchFor(track)
            rt.synth.sampleRate = sampleRate
        }
    }

    private fun patchFor(track: STrack): SynthPatch {
        val preset = InstrumentLibrary.byId(track.presetId) ?: InstrumentLibrary.PRESETS.first()
        var patch = preset.patch
        val p = track.synthParams
        if (p.isEmpty()) return patch
        patch = patch.copy(
            cutoffHz = p["cutoff"] ?: patch.cutoffHz,
            resonance = p["resonance"] ?: patch.resonance,
            attack = p["attack"] ?: patch.attack,
            decay = p["decay"] ?: patch.decay,
            sustain = p["sustain"] ?: patch.sustain,
            release = p["release"] ?: patch.release,
            osc2DetuneCents = p["detune"] ?: patch.osc2DetuneCents,
            glideSec = p["glide"] ?: patch.glideSec
        )
        p["waveform"]?.let { w ->
            val wf = com.sorastudio.core.dsp.Waveform.values().getOrNull(w.toInt())
            if (wf != null) patch = patch.copy(osc1 = wf)
        }
        return patch
    }

    private fun fxSignatureOf(fx: SEffect): String =
        fx.id + fx.type // structure only; params sync every project update

    private fun syncMasterChain(chain: List<SEffect>) {
        val sig = chain.joinToString("|") { it.id + it.type }
        if (sig == masterFxSignature) {
            chain.forEachIndexed { index, fx ->
                val slot = masterChain.slots.getOrNull(index) ?: return@forEachIndexed
                slot.enabled = fx.enabled; slot.wet = fx.wet; slot.effect.setParams(fx.params)
            }
            return
        }
        masterFxSignature = sig
        masterChain.slots.clear()
        for (fx in chain) {
            val type = runCatching { EffectType.valueOf(fx.type) }.getOrNull() ?: continue
            val effect = EffectCatalog.create(type, sampleRate)
            effect.setParams(EffectCatalog.defaultParams(type) + fx.params)
            masterChain.slots.add(EffectChain.Slot(effect, fx.enabled, fx.wet))
        }
    }

    private fun kit(kitId: String): Map<DrumType, FloatArray> =
        kitCache.getOrPut(kitId) { DrumSynth.buildKit(kitId, sampleRate) }

    // ------------------------------------------------------------------ live events

    private fun drainLiveEvents() {
        while (true) {
            val event = liveQueue.poll() ?: break
            when (event) {
                is LiveEvent.NoteOn -> {
                    val synth = liveSynths.getOrPut(event.key) { PolySynth().also { it.sampleRate = sampleRate } }
                    val preset = InstrumentLibrary.byId(event.presetId) ?: InstrumentLibrary.PRESETS.first()
                    var patch = preset.patch
                    if (event.synthParams.isNotEmpty()) {
                        val p = event.synthParams
                        patch = patch.copy(
                            cutoffHz = p["cutoff"] ?: patch.cutoffHz,
                            resonance = p["resonance"] ?: patch.resonance,
                            attack = p["attack"] ?: patch.attack,
                            decay = p["decay"] ?: patch.decay,
                            sustain = p["sustain"] ?: patch.sustain,
                            release = p["release"] ?: patch.release
                        )
                    }
                    synth.patch = patch
                    synth.noteOn(event.midi, event.velocity)
                }
                is LiveEvent.NoteOff -> liveSynths[event.key]?.noteOff(event.midi)
                is LiveEvent.AllOff -> liveSynths[event.key]?.allNotesOff()
                is LiveEvent.DrumHit -> {
                    val data = event.customData ?: kit(event.kitId)[event.drum]
                    if (data != null) {
                        val g = 0.9f * event.velocity
                        liveSampler.trigger(data, stereo = false, rate = 1.0, gainL = g, gainR = g)
                    }
                }
                is LiveEvent.SampleHit -> {
                    liveSampler.trigger(event.data, event.stereo, event.rate, event.gain, event.gain)
                }
            }
        }
    }

    // ------------------------------------------------------------------ tracks

    private fun renderTrack(
        track: STrack,
        rt: TrackRuntime,
        proj: SProject,
        frames: Int,
        blockStartBeats: Double,
        blockEndBeats: Double,
        anySolo: Boolean
    ) {
        val buffer = if (rt.buffer.size >= frames * 2) rt.buffer else FloatArray(frames * 2)
        AudioBufferOps.clear(buffer, frames)

        // Automation evaluated at block centre.
        val midBeats = (blockStartBeats + blockEndBeats) / 2.0
        var volumeDb = track.volumeDb
        var pan = track.pan
        var sendRev = track.sendReverb
        var sendDel = track.sendDelay
        for (lane in proj.automation) {
            if (lane.trackId != track.id) continue
            val value = lane.valueAt(midBeats) ?: continue
            when {
                lane.target == "volume" -> volumeDb = value
                lane.target == "pan" -> pan = value
                lane.target == "sendReverb" -> sendRev = value
                lane.target == "sendDelay" -> sendDel = value
                lane.target.startsWith("effect:") -> {
                    val parts = lane.target.split(":")
                    if (parts.size == 3) {
                        val idx = rt.fxIds.indexOf(parts[1])
                        if (idx >= 0) rt.fxChain.slots.getOrNull(idx)?.effect?.setParam(parts[2], value)
                    }
                }
                lane.target.startsWith("synth:") -> {
                    val key = lane.target.removePrefix("synth:")
                    if (key == "cutoff") rt.synth.patch = rt.synth.patch.copy(cutoffHz = value)
                    if (key == "resonance") rt.synth.patch = rt.synth.patch.copy(resonance = value)
                }
            }
        }

        for (clip in proj.clipsForTrack(track.id)) {
            if (clip.muted) continue
            if (clip.endBeats <= blockStartBeats || clip.startBeats >= blockEndBeats) continue
            when (clip.type) {
                ClipType.MIDI -> renderMidiClip(track, rt, proj, clip, buffer, frames, blockStartBeats)
                ClipType.PATTERN -> renderPatternClip(track, rt, proj, clip, buffer, frames, blockStartBeats)
                ClipType.AUDIO -> renderAudioClip(track, proj, clip, buffer, frames)
            }
        }

        // Continuous voices for instrument/sampler tracks (events modify state in renderMidiClip
        // via sub-block rendering, but sustained rendering happens there too; drum sampler sounds here).
        if (track.type == TrackType.DRUM || track.type == TrackType.SAMPLE) {
            rt.sampler.renderAdd(buffer, frames)
        }

        rt.fxChain.process(buffer, frames)
        if (track.mono) MixerCore.toMonoInPlace(buffer, frames)
        MixerCore.applyVolumePan(buffer, frames, volumeDb, pan)

        val audible = MixerCore.isAudible(track.mute, track.solo, anySolo)
        rt.meter.update(buffer, frames, sampleRate)
        if (audible) {
            AudioBufferOps.addWithGain(master, buffer, frames, 1f)
            if (sendRev > 0.001f) AudioBufferOps.addWithGain(sendReverbBuf, buffer, frames, sendRev)
            if (sendDel > 0.001f) AudioBufferOps.addWithGain(sendDelayBuf, buffer, frames, sendDel)
        }
    }

    private fun beatsToFrameOffset(beat: Double, blockStartBeats: Double, bpm: Double, frames: Int): Int {
        val deltaBeats = beat - blockStartBeats
        val offset = (deltaBeats * TempoMath.samplesPerBeat(bpm, sampleRate)).toInt()
        return offset.coerceIn(0, frames)
    }

    private fun renderMidiClip(
        track: STrack,
        rt: TrackRuntime,
        proj: SProject,
        clip: com.sorastudio.core.project.SClip,
        buffer: FloatArray,
        frames: Int,
        blockStartBeats: Double
    ) {
        val bpm = proj.bpm
        // Build (frameOffset, isOn, pitch, velocity) event list for this block.
        val events = mutableListOf<NoteEvent>()
        for (note in clip.notes) {
            val onBeat = clip.startBeats + (note.startBeats - clip.contentOffsetBeats)
            val offBeat = clip.startBeats + (note.endBeats - clip.contentOffsetBeats)
            val onFrame = beatsToFrameOffset(onBeat, blockStartBeats, bpm, frames + 1)
            val offFrame = beatsToFrameOffset(offBeat, blockStartBeats, bpm, frames + 1)
            if (onBeat >= blockStartBeats - 1e-9 && onFrame <= frames) {
                if (onFrame < frames) events.add(NoteEvent(onFrame, true, note.pitch, note.velocity / 127f))
            }
            if (offBeat >= blockStartBeats - 1e-9 && offFrame <= frames) {
                events.add(NoteEvent(min(offFrame, frames), false, note.pitch, 0f))
            }
        }
        events.sortWith(compareBy({ it.frame }, { if (it.isOn) 1 else 0 }))

        // Sub-block render. Segment rendering writes into a scratch chunk then accumulates,
        // because PolySynth/SamplePlayer always write from offset 0.
        val isSamplerTrack = track.type == TrackType.SAMPLE
        var cursor = 0

        fun renderSegment(from: Int, to: Int) {
            val count = to - from
            if (count <= 0) return
            if (isSamplerTrack) {
                // sampler voices render the whole track buffer in renderTrack; segments only need events
                return
            }
            val seg = rt.segment
            AudioBufferOps.clear(seg, count)
            rt.synth.renderAdd(seg, count, 0.9f, 0.9f)
            for (i in 0 until count * 2) {
                val target = from * 2 + i
                if (target < buffer.size) buffer[target] += seg[i]
            }
        }

        for (ev in events) {
            renderSegment(cursor, ev.frame)
            cursor = max(cursor, ev.frame)
            if (isSamplerTrack) {
                val data = track.samplePath?.let { assetProvider(it) }
                if (data != null && ev.isOn) {
                    val rate = LevelMath.semitonesToRatio((ev.pitch - track.sampleRootNote).toDouble())
                    val g = 0.85f * ev.velocity
                    // NOTE: triggered voices start at block start (sample-player renders track-wide);
                    // timing within ±1 block (≤12 ms) for sampler tracks.
                    rt.sampler.trigger(data, stereo = true, rate = rate, gainL = g, gainR = g, noteId = ev.pitch)
                } else if (!ev.isOn) {
                    rt.sampler.release(ev.pitch)
                }
            } else {
                if (ev.isOn) rt.synth.noteOn(ev.pitch, ev.velocity) else rt.synth.noteOff(ev.pitch)
            }
        }
        renderSegment(cursor, frames)
    }

    private fun renderPatternClip(
        track: STrack,
        rt: TrackRuntime,
        proj: SProject,
        clip: com.sorastudio.core.project.SClip,
        buffer: FloatArray,
        frames: Int,
        blockStartBeats: Double
    ) {
        val pattern = proj.patternById(clip.patternId) ?: return
        val patternBeats = pattern.lengthBeats
        if (patternBeats <= 0) return
        val bpm = proj.bpm
        val blockEndBeats = blockStartBeats + frames / TempoMath.samplesPerBeat(bpm, sampleRate)
        val kitSamples = kit(track.drumKitId)
        val stepBeats = patternBeats / pattern.totalSteps

        // Find first pattern iteration touching the block.
        var iteration = kotlin.math.floor((blockStartBeats - clip.startBeats) / patternBeats).toInt()
        if (iteration < 0) iteration = 0
        while (true) {
            val iterStart = clip.startBeats + iteration * patternBeats
            if (iterStart >= min(blockEndBeats, clip.endBeats)) break
            for (lane in pattern.lanes) {
                val drum = runCatching { DrumType.valueOf(lane.drum) }.getOrNull()
                val sampleData: FloatArray? =
                    lane.customSamplePath?.let { assetProvider(it) } ?: drum?.let { kitSamples[it] }
                if (sampleData == null) continue
                val isCustomStereo = lane.customSamplePath != null
                for (stepIndex in lane.steps.indices) {
                    val step = lane.steps[stepIndex]
                    if (!step.active) continue
                    var beat = iterStart + stepIndex * stepBeats
                    if (stepIndex % 2 == 1) beat += pattern.swing * (4.0 / pattern.stepsPerBar)
                    if (beat < blockStartBeats || beat >= blockEndBeats || beat >= clip.endBeats) continue
                    if (step.probability < 1f && drumRandom.nextFloat() > step.probability) continue
                    val frameOffset = beatsToFrameOffset(beat, blockStartBeats, bpm, frames)
                    val rate = LevelMath.semitonesToRatio(step.pitchSemitones.toDouble())
                    val (gl, gr) = LevelMath.panGains(lane.pan)
                    val laneGain = LevelMath.dbToGain(lane.volumeDb) * step.velocity
                    // Mix directly into the track buffer at the exact offset.
                    mixSampleAt(buffer, frames, frameOffset, sampleData, isCustomStereo, rate, gl * laneGain, gr * laneGain)
                }
            }
            iteration++
        }
    }

    private fun renderAudioClip(
        track: STrack,
        proj: SProject,
        clip: com.sorastudio.core.project.SClip,
        buffer: FloatArray,
        frames: Int
    ) {
        val path = clip.audioPath ?: return
        val data = assetProvider(path) ?: return
        val bpm = proj.bpm
        val clipStartFrame = TempoMath.beatsToSamples(clip.startBeats, bpm, sampleRate)
        val offsetFrames = TempoMath.beatsToSamples(clip.contentOffsetBeats, bpm, sampleRate)
        val clipLengthFrames = TempoMath.beatsToSamples(clip.lengthBeats, bpm, sampleRate)
        val fadeInFrames = TempoMath.beatsToSamples(clip.fadeInBeats, bpm, sampleRate)
        val fadeOutFrames = TempoMath.beatsToSamples(clip.fadeOutBeats, bpm, sampleRate)
        val gain = LevelMath.dbToGain(clip.gainDb)
        val rate = if (clip.pitchSemitones == 0f) 1.0 else LevelMath.semitonesToRatio(clip.pitchSemitones.toDouble())
        val dataFrames = data.size / 2

        for (f in 0 until frames) {
            val timelineFrame = positionFrames + f
            val clipPos = timelineFrame - clipStartFrame
            if (clipPos < 0) continue
            if (clipPos >= clipLengthFrames) continue
            val srcPos = (offsetFrames + clipPos) * rate
            val srcFrame = srcPos.toInt()
            if (srcFrame >= dataFrames) continue
            var g = gain
            if (fadeInFrames > 0 && clipPos < fadeInFrames) g *= clipPos.toFloat() / fadeInFrames
            if (fadeOutFrames > 0 && clipPos > clipLengthFrames - fadeOutFrames) {
                g *= (clipLengthFrames - clipPos).toFloat() / fadeOutFrames
            }
            val i = f * 2
            val s = srcFrame * 2
            if (rate == 1.0) {
                buffer[i] += data[s] * g
                buffer[i + 1] += data[s + 1] * g
            } else {
                val frac = (srcPos - srcFrame).toFloat()
                val s1 = min(srcFrame + 1, dataFrames - 1) * 2
                buffer[i] += (data[s] + (data[s1] - data[s]) * frac) * g
                buffer[i + 1] += (data[s + 1] + (data[s1 + 1] - data[s + 1]) * frac) * g
            }
        }
        @Suppress("UNUSED_EXPRESSION") track
    }

    private fun mixSampleAt(
        buffer: FloatArray,
        frames: Int,
        offsetFrames: Int,
        data: FloatArray,
        stereo: Boolean,
        rate: Double,
        gainL: Float,
        gainR: Float
    ) {
        val dataFrames = if (stereo) data.size / 2 else data.size
        var pos = 0.0
        var f = offsetFrames
        while (f < frames && pos < dataFrames - 1) {
            val i0 = pos.toInt()
            val frac = (pos - i0).toFloat()
            val i = f * 2
            if (i + 1 >= buffer.size) break
            if (stereo) {
                val l0 = data[i0 * 2]; val l1 = data[min(i0 + 1, dataFrames - 1) * 2]
                val r0 = data[i0 * 2 + 1]; val r1 = data[min(i0 + 1, dataFrames - 1) * 2 + 1]
                buffer[i] += (l0 + (l1 - l0) * frac) * gainL
                buffer[i + 1] += (r0 + (r1 - r0) * frac) * gainR
            } else {
                val s0 = data[i0]; val s1 = data[min(i0 + 1, dataFrames - 1)]
                val s = s0 + (s1 - s0) * frac
                buffer[i] += s * gainL
                buffer[i + 1] += s * gainR
            }
            pos += rate
            f++
        }
        // Samples longer than the block continue via the sampler below.
        if (pos < dataFrames - 1) {
            // Remainder is short (drum hits) — acceptable truncation at block edge for hits
            // starting near the end; the tail continues through a queued voice.
            val remaining = if (stereo) {
                val rem = FloatArray(((dataFrames - pos.toInt())).coerceAtLeast(0) * 2)
                var count = 0
                var p = pos
                while (p < dataFrames - 1 && count * 2 + 1 < rem.size) {
                    val idx = p.toInt()
                    val fr = (p - idx).toFloat()
                    rem[count * 2] = data[idx * 2] + (data[min(idx + 1, dataFrames - 1) * 2] - data[idx * 2]) * fr
                    rem[count * 2 + 1] = data[idx * 2 + 1] + (data[min(idx + 1, dataFrames - 1) * 2 + 1] - data[idx * 2 + 1]) * fr
                    p += rate; count++
                }
                rem
            } else {
                val startIdx = pos.toInt()
                if (rate == 1.0) data.copyOfRange(startIdx, data.size) else {
                    val out = mutableListOf<Float>()
                    var p = pos
                    while (p < dataFrames - 1) {
                        val idx = p.toInt(); val fr = (p - idx).toFloat()
                        out.add(data[idx] + (data[min(idx + 1, dataFrames - 1)] - data[idx]) * fr)
                        p += rate
                    }
                    out.toFloatArray()
                }
            }
            if (remaining.isNotEmpty()) {
                liveSampler.trigger(remaining, stereo, 1.0, gainL, gainR)
            }
        }
    }

    private fun renderMetronome(frames: Int, blockStartBeats: Double, proj: SProject) {
        val bpm = proj.bpm
        val blockEndBeats = blockStartBeats + frames / TempoMath.samplesPerBeat(bpm, sampleRate)
        var beat = kotlin.math.ceil(blockStartBeats - 1e-9)
        while (beat < blockEndBeats) {
            val offset = beatsToFrameOffset(beat, blockStartBeats, bpm, frames)
            val isBar = abs(beat % proj.beatsPerBar) < 0.0001
            val click = if (isBar) clickAccent else clickNormal
            for (i in click.indices) {
                val f = offset + i
                if (f >= frames) break
                master[f * 2] += click[i]
                master[f * 2 + 1] += click[i]
            }
            beat += 1.0
        }
    }
}
