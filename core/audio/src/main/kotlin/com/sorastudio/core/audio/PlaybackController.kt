package com.sorastudio.core.audio

import android.content.Context
import com.sorastudio.core.dsp.DecodedAudio
import com.sorastudio.core.dsp.DrumType
import com.sorastudio.core.dsp.MeterState
import com.sorastudio.core.project.ProjectSession
import com.sorastudio.core.project.SProject
import com.sorastudio.core.storage.ProjectStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decoded-asset cache. Decoding happens on IO (preload when the project
 * changes); the audio thread only ever reads ready buffers.
 */
class AssetCache {
    private val decoded = HashMap<String, DecodedAudio>()
    private val stereo = HashMap<String, FloatArray>()
    private val mono = HashMap<String, FloatArray>()

    fun keyFor(file: File): String = file.absolutePath + "@" + file.length() + ":" + file.lastModified()

    suspend fun ensureLoaded(files: List<File>) {
        for (file in files) {
            val key = keyFor(file)
            if (decoded.containsKey(key) || !file.exists()) continue
            runCatching { AudioDecoder.decode(file) }.getOrNull()?.let { audio ->
                synchronized(this) {
                    if (decoded.size > 48) decoded.clear()
                    decoded[key] = audio
                }
            }
        }
    }

    fun stereoAt(file: File, rate: Int): FloatArray? {
        val baseKey = keyFor(file)
        val key = "$baseKey/stereo@$rate"
        synchronized(this) {
            stereo[key]?.let { return it }
            val source = decoded[baseKey] ?: return null
            val converted = source.toStereoAt(rate)
            stereo[key] = converted
            return converted
        }
    }

    fun monoAt(file: File, rate: Int): FloatArray? {
        val baseKey = keyFor(file)
        val key = "$baseKey/mono@$rate"
        synchronized(this) {
            mono[key]?.let { return it }
            val source = decoded[baseKey] ?: return null
            val converted = source.toMonoAt(rate)
            mono[key] = converted
            return converted
        }
    }

    suspend fun decodeNow(file: File): DecodedAudio? = withContext(Dispatchers.IO) {
        val key = keyFor(file)
        synchronized(this@AssetCache) { decoded[key] }?.let { return@withContext it }
        runCatching { AudioDecoder.decode(file) }.getOrNull()?.also { audio ->
            synchronized(this@AssetCache) { decoded[key] = audio }
        }
    }

    fun clear() = synchronized(this) {
        decoded.clear(); stereo.clear(); mono.clear()
    }
}

data class TransportState(
    val playing: Boolean = false,
    val recording: Boolean = false,
    val engineError: String? = null
)

/**
 * Wires ProjectSession <-> ProjectRenderer <-> AudioEngine.
 * Transport state lives here; the project document itself stays in the session.
 */
class PlaybackController(
    context: Context,
    val session: ProjectSession,
    private val storage: ProjectStorage,
    bufferFrames: Int = 512
) {
    val deviceInfo: AudioDeviceInfo = AudioCapabilities.detect(context.applicationContext)
    val sampleRate: Int = deviceInfo.nativeSampleRate.coerceIn(22050, 192000)

    val assetCache = AssetCache()

    val renderer = ProjectRenderer(sampleRate, assetProvider = { path ->
        val file = resolvePath(path)
        if (file == null) null else this.assetCache.stereoAt(file, sampleRate)
    })

    private val engine = AudioEngine(renderer, bufferFrames)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _transport = MutableStateFlow(TransportState())
    val transport: StateFlow<TransportState> = _transport.asStateFlow()

    @Volatile
    private var engineStarted = false

    init {
        scope.launch {
            session.project.collect { project ->
                renderer.setProject(project)
                val files = referencedFiles(project)
                withContext(Dispatchers.IO) { assetCache.ensureLoaded(files) }
                // Re-set so renderer picks up any asset-dependent state after decode
                renderer.setProject(project)
            }
        }
    }

    fun resolvePath(path: String): File? {
        if (path.isBlank()) return null
        val direct = File(path)
        if (direct.isAbsolute) return direct
        val projectId = session.current.id
        return File(storage.projectDir(projectId), path)
    }

    fun referencedFiles(project: SProject): List<File> {
        val paths = mutableSetOf<String>()
        project.clips.forEach { it.audioPath?.let(paths::add) }
        project.tracks.forEach { t -> t.samplePath?.let(paths::add) }
        project.patterns.forEach { p -> p.lanes.forEach { l -> l.customSamplePath?.let(paths::add) } }
        return paths.mapNotNull { resolvePath(it) }
    }

    // ------------------------------------------------------------- transport

    fun ensureEngineStarted(): Boolean {
        if (engineStarted) return true
        val ok = engine.start()
        engineStarted = ok
        _transport.value = _transport.value.copy(engineError = engine.lastError)
        return ok
    }

    fun play() {
        if (!ensureEngineStarted()) return
        renderer.playing = true
        _transport.value = _transport.value.copy(playing = true)
    }

    fun pause() {
        renderer.playing = false
        renderer.postLive(LiveEvent.StopAllLive())
        _transport.value = _transport.value.copy(playing = false)
    }

    fun stop() {
        pause()
        renderer.seek(0.0)
    }

    fun toggle() {
        if (renderer.playing) pause() else play()
    }

    val isPlaying: Boolean get() = renderer.playing

    fun positionBeats(): Double = renderer.positionBeats()

    fun seekBeats(beats: Double) = renderer.seek(beats)

    fun setRecording(recording: Boolean) {
        _transport.value = _transport.value.copy(recording = recording)
    }

    fun setMasterVolumePercent(percent: Int) {
        renderer.masterVolumeLinear = (percent.coerceIn(0, 100) / 100f)
    }

    // ------------------------------------------------------------- audition

    fun noteOn(key: String, presetId: String, midi: Int, velocity: Float = 0.85f, synthParams: Map<String, Float> = emptyMap()) {
        ensureEngineStarted()
        renderer.postLive(LiveEvent.NoteOn(key, presetId, midi, velocity, synthParams))
    }

    fun noteOff(key: String, midi: Int) {
        renderer.postLive(LiveEvent.NoteOff(key, midi))
    }

    fun allNotesOff(key: String) {
        renderer.postLive(LiveEvent.AllOff(key))
    }

    fun drumHit(kitId: String, drum: DrumType, velocity: Float = 0.9f, customPath: String? = null) {
        ensureEngineStarted()
        if (customPath != null) {
            val file = resolvePath(customPath)
            val data = file?.let { assetCache.monoAt(it, sampleRate) }
            if (data != null) {
                renderer.postLive(LiveEvent.DrumHit(kitId, drum, velocity, data))
                return
            }
            if (file != null) {
                scope.launch {
                    withContext(Dispatchers.IO) { assetCache.ensureLoaded(listOf(file)) }
                    assetCache.monoAt(file, sampleRate)?.let { mono ->
                        renderer.postLive(LiveEvent.DrumHit(kitId, drum, velocity, mono))
                    }
                }
                return
            }
        }
        renderer.postLive(LiveEvent.DrumHit(kitId, drum, velocity, null))
    }

    fun previewFile(file: File, rate: Double = 1.0) {
        ensureEngineStarted()
        stopPreviews()
        scope.launch {
            val decoded = assetCache.decodeNow(file) ?: return@launch
            val data = decoded.toStereoAt(sampleRate)
            renderer.postLive(LiveEvent.SampleHit(data, stereo = true, rate = rate, gain = 0.9f))
        }
    }

    fun stopPreviews() {
        renderer.postLive(LiveEvent.StopAllLive())
    }

    // ------------------------------------------------------------- meters

    fun trackMeter(trackId: String): MeterState? = renderer.meterFor(trackId)
    fun masterMeter(): MeterState = renderer.masterMeter

    fun release() {
        pause()
        engine.stop()
        engineStarted = false
        scope.cancel()
    }
}
