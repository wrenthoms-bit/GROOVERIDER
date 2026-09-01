package com.delrogue.grooverider.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.delrogue.grooverider.audio.AudioEngineService
import com.delrogue.grooverider.engine.EngineMeters
import com.delrogue.grooverider.engine.GrainCloudSnapshot
import com.delrogue.grooverider.engine.GrooveriderEngine
import com.delrogue.grooverider.render.RenderRepository
import com.delrogue.grooverider.seed.SeedNaming
import java.io.File
import com.delrogue.grooverider.seed.Seed
import com.delrogue.grooverider.seed.SeedRepository
import com.delrogue.grooverider.source.SourceRepository
import com.delrogue.grooverider.telemetry.XrunTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.random.Random

data class ToneState(
    val enabled: Boolean = false,
    val hz: Float = 220f,
    val gain: Float = 0.25f,
    val masterGain: Float = 0.8f,
)

/** Grain engine defaults mirror the pad preset (spec 2.3). */
data class GrainState(
    val density: Float = 40f,
    val timingJitter: Float = 0.15f,
    val grainSizeMs: Float = 400f,
    val sizeJitter: Float = 0.25f,
    val position: Float = 0.5f,
    val sprayMs: Float = 250f,
    val drift: Float = 0.05f,
    val pitchSt: Float = 0f,
    val pitchSpraySt: Float = 0.15f,
    val reverseProb: Float = 0.2f,
    val spread: Float = 0.8f,
    val windowType: Int = 0,
    val outputWidth: Float = 1.0f,
    val outputGain: Float = 0.9f,
    val chaosRate: Float = 0.3f,
    val chaosEnabled: Boolean = true,
)

/** A frozen 60 s ring-buffer snapshot, awaiting keep/discard (spec 6.1). */
data class CaptureState(
    val pcm: ShortArray,       // interleaved stereo int16
    val sampleRate: Int,
    val trimStart: Float = 0f,   // 0..1 of the captured range
    val trimEnd: Float = 1f,
)

/** The four curated performance macros (spec 5.4), each 0..1. */
data class MacroState(
    val texture: Float = 0.5f,   // default position 0.5 == the default cloud
    val drift: Float = 0.3f,
    val pitch: Float = 0.15f / 24f,
    val space: Float = 0.8f,
)

class EngineViewModel(app: Application) : AndroidViewModel(app) {

    private val _meters = MutableStateFlow(EngineMeters())
    val meters: StateFlow<EngineMeters> = _meters.asStateFlow()

    private val _tone = MutableStateFlow(ToneState())
    val tone: StateFlow<ToneState> = _tone.asStateFlow()

    private val _grain = MutableStateFlow(GrainState())
    val grain: StateFlow<GrainState> = _grain.asStateFlow()

    private val seedRepo = SeedRepository(app)
    private val sourceRepo = SourceRepository(app)
    private val _masterSeed = MutableStateFlow(1L)
    val masterSeed: StateFlow<Long> = _masterSeed.asStateFlow()
    val seeds: StateFlow<List<Seed>> = seedRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _seedLoadError = MutableStateFlow<String?>(null)
    val seedLoadError: StateFlow<String?> = _seedLoadError.asStateFlow()

    private val _config = MutableStateFlow("stream closed")
    val config: StateFlow<String> = _config.asStateFlow()

    private val _cloud = MutableStateFlow(GrainCloudSnapshot(emptyList()))
    val cloud: StateFlow<GrainCloudSnapshot> = _cloud.asStateFlow()

    private val _macro = MutableStateFlow(MacroState())
    val macro: StateFlow<MacroState> = _macro.asStateFlow()

    private val _macroLocks = MutableStateFlow(setOf<String>())
    val macroLocks: StateFlow<Set<String>> = _macroLocks.asStateFlow()

    private val _frozen = MutableStateFlow(false)
    val frozen: StateFlow<Boolean> = _frozen.asStateFlow()
    private var driftBeforeFreeze = 0.05f

    private val _lineage = MutableStateFlow(listOf<Long>())
    val canGoBackInLineage: StateFlow<Boolean> = MutableStateFlow(false).also { flow ->
        viewModelScope.launch { _lineage.collect { flow.value = it.isNotEmpty() } }
    }

    val seedName: StateFlow<String> = MutableStateFlow(SeedNaming.nameFor(1L)).also { flow ->
        viewModelScope.launch { _masterSeed.collect { flow.value = SeedNaming.nameFor(it) } }
    }

    private val _captureHint = MutableStateFlow<String?>(null)
    val captureHint: StateFlow<String?> = _captureHint.asStateFlow()

    private val renderRepo = RenderRepository(app)
    private val _captureReview = MutableStateFlow<CaptureState?>(null)
    val captureReview: StateFlow<CaptureState?> = _captureReview.asStateFlow()
    private val _rendering = MutableStateFlow(false)
    val rendering: StateFlow<Boolean> = _rendering.asStateFlow()

    private val xrunTelemetry = XrunTelemetry(app)
    private var pollJob: Job? = null
    private var cloudPollJob: Job? = null

    fun startEngine() {
        AudioEngineService.start(getApplication<Application>())
        startPolling()
        // The stream opens asynchronously in the service; read the granted
        // configuration once it has had a moment to settle.
        viewModelScope.launch {
            delay(400)
            _config.value = GrooveriderEngine.configDescription()
            applyToneToEngine()
            applyGrainToEngine()
            GrooveriderEngine.setMasterSeed(_masterSeed.value)
        }
        // Device profiling (spec 8.4, M8): off the main thread, since it
        // renders ~1s of synthetic audio to measure.
        viewModelScope.launch(Dispatchers.Default) {
            GrooveriderEngine.profileAndSetVoiceCap()
        }
    }

    fun stopEngine() {
        AudioEngineService.stop(getApplication<Application>())
        pollJob?.cancel(); pollJob = null
        cloudPollJob?.cancel(); cloudPollJob = null
        _meters.value = EngineMeters()
        _cloud.value = GrainCloudSnapshot(emptyList())
        _config.value = "stream closed"
        _tone.value = _tone.value.copy(enabled = false)
    }

    private fun startPolling() {
        if (pollJob == null) {
            pollJob = viewModelScope.launch {
                while (true) {
                    val m = GrooveriderEngine.pollMeters()
                    _meters.value = m
                    xrunTelemetry.onMeters(m)
                    delay(33)   // ~30 Hz (spec 1.2)
                }
            }
        }
        if (cloudPollJob == null) {
            cloudPollJob = viewModelScope.launch {
                while (true) {
                    _cloud.value = GrooveriderEngine.pollCloud()
                    delay(16)   // ~60 Hz (spec 1.2, 5.2)
                }
            }
        }
    }

    fun setToneEnabled(on: Boolean) {
        _tone.value = _tone.value.copy(enabled = on)
        GrooveriderEngine.setToneEnabled(on)
    }

    fun setToneHz(hz: Float) {
        _tone.value = _tone.value.copy(hz = hz)
        GrooveriderEngine.setToneHz(hz)
    }

    fun setToneGain(gain: Float) {
        _tone.value = _tone.value.copy(gain = gain)
        GrooveriderEngine.setToneGain(gain)
    }

    fun setMasterGain(gain: Float) {
        _tone.value = _tone.value.copy(masterGain = gain)
        GrooveriderEngine.setMasterGain(gain)
    }

    fun refreshConfig() {
        _config.value = GrooveriderEngine.configDescription()
    }

    private fun applyToneToEngine() {
        val t = _tone.value
        GrooveriderEngine.setToneHz(t.hz)
        GrooveriderEngine.setToneGain(t.gain)
        GrooveriderEngine.setMasterGain(t.masterGain)
        GrooveriderEngine.setToneEnabled(t.enabled)
    }

    // ---- grain engine (M2) --------------------------------------------------

    fun setGrainDensity(v: Float) { _grain.value = _grain.value.copy(density = v); GrooveriderEngine.setGrainDensity(v) }
    fun setGrainTimingJitter(v: Float) { _grain.value = _grain.value.copy(timingJitter = v); GrooveriderEngine.setGrainTimingJitter(v) }
    fun setGrainSizeMs(v: Float) { _grain.value = _grain.value.copy(grainSizeMs = v); GrooveriderEngine.setGrainSizeMs(v) }
    fun setGrainSizeJitter(v: Float) { _grain.value = _grain.value.copy(sizeJitter = v); GrooveriderEngine.setGrainSizeJitter(v) }
    fun setGrainPosition(v: Float) { _grain.value = _grain.value.copy(position = v); GrooveriderEngine.setGrainPosition(v) }
    fun setGrainSprayMs(v: Float) { _grain.value = _grain.value.copy(sprayMs = v); GrooveriderEngine.setGrainSprayMs(v) }
    fun setGrainDrift(v: Float) { _grain.value = _grain.value.copy(drift = v); GrooveriderEngine.setGrainDrift(v) }
    fun setGrainPitchSt(v: Float) { _grain.value = _grain.value.copy(pitchSt = v); GrooveriderEngine.setGrainPitchSt(v) }
    fun setGrainPitchSpraySt(v: Float) { _grain.value = _grain.value.copy(pitchSpraySt = v); GrooveriderEngine.setGrainPitchSpraySt(v) }
    fun setGrainReverseProb(v: Float) { _grain.value = _grain.value.copy(reverseProb = v); GrooveriderEngine.setGrainReverseProb(v) }
    fun setGrainSpread(v: Float) { _grain.value = _grain.value.copy(spread = v); GrooveriderEngine.setGrainSpread(v) }
    fun setGrainWindowType(v: Int) { _grain.value = _grain.value.copy(windowType = v); GrooveriderEngine.setGrainWindowType(v) }
    fun setOutputWidth(v: Float) { _grain.value = _grain.value.copy(outputWidth = v); GrooveriderEngine.setOutputWidth(v) }
    fun setOutputGain(v: Float) { _grain.value = _grain.value.copy(outputGain = v); GrooveriderEngine.setOutputGain(v) }
    fun setChaosRate(v: Float) { _grain.value = _grain.value.copy(chaosRate = v); GrooveriderEngine.setChaosRate(v) }
    fun setChaosEnabled(on: Boolean) { _grain.value = _grain.value.copy(chaosEnabled = on); GrooveriderEngine.setChaosEnabled(on) }

    /** Resets the grain/output controls to the neutral recall-default preset. */
    fun recallGrainDefaults() {
        _grain.value = _grain.value.copy(
            density = 0.5f,
            timingJitter = 0.0f,
            grainSizeMs = 2000f,
            sizeJitter = 0.0f,
            position = 0.0f,
            sprayMs = 0.0f,
            drift = 0.0f,
            pitchSt = 0.0f,
            pitchSpraySt = 0.0f,
            reverseProb = 0.0f,
            spread = 0.0f,
            outputWidth = 1.0f,
            outputGain = 0.9f,
        )
        applyGrainToEngine()
    }

    private fun applyGrainToEngine() {
        val g = _grain.value
        GrooveriderEngine.setGrainDensity(g.density)
        GrooveriderEngine.setGrainTimingJitter(g.timingJitter)
        GrooveriderEngine.setGrainSizeMs(g.grainSizeMs)
        GrooveriderEngine.setGrainSizeJitter(g.sizeJitter)
        GrooveriderEngine.setGrainPosition(g.position)
        GrooveriderEngine.setGrainSprayMs(g.sprayMs)
        GrooveriderEngine.setGrainDrift(g.drift)
        GrooveriderEngine.setGrainPitchSt(g.pitchSt)
        GrooveriderEngine.setGrainPitchSpraySt(g.pitchSpraySt)
        GrooveriderEngine.setGrainReverseProb(g.reverseProb)
        GrooveriderEngine.setGrainSpread(g.spread)
        GrooveriderEngine.setGrainWindowType(g.windowType)
        GrooveriderEngine.setOutputWidth(g.outputWidth)
        GrooveriderEngine.setOutputGain(g.outputGain)
        GrooveriderEngine.setChaosRate(g.chaosRate)
        GrooveriderEngine.setChaosEnabled(g.chaosEnabled)
    }

    // ---- Seed persistence (M3) ---------------------------------------------

    /** Genuinely random -- the master seed is the root of the deterministic
     * tree and has to come from *somewhere* non-deterministic (spec 3.1's
     * prohibition is on the signal path, not on how a fresh seed is chosen). */
    fun regenerateMasterSeed() {
        val s = Random.nextLong()
        _masterSeed.value = s
        GrooveriderEngine.setMasterSeed(s)
    }

    // ---- performance gestures (M5) ------------------------------------------

    /** Double-tap: new master seed, params untouched, pushed onto a lineage
     * stack so it is always navigable back (spec 5.3). */
    fun reRoll() {
        _lineage.value = _lineage.value + _masterSeed.value
        regenerateMasterSeed()
    }

    fun goBackInLineage() {
        val stack = _lineage.value
        val previous = stack.lastOrNull() ?: return
        _lineage.value = stack.dropLast(1)
        _masterSeed.value = previous
        GrooveriderEngine.setMasterSeed(previous)
    }

    /** Long-press: playhead latches, drift -> 0 (spec 5.3). Press again to release. */
    fun setFrozen(frozen: Boolean) {
        if (frozen == _frozen.value) return
        _frozen.value = frozen
        if (frozen) {
            driftBeforeFreeze = _grain.value.drift
            setGrainDrift(0f)
        } else {
            setGrainDrift(driftBeforeFreeze)
        }
    }

    fun toggleMacroLock(name: String) {
        _macroLocks.value = _macroLocks.value.let { if (name in it) it - name else it + name }
    }

    /** Drag on the fullscreen cloud canvas: X -> position, Y -> pitch centre --
     * matches the grain particles' own plot (Y = pitch, up = transposed up). */
    fun onCanvasDrag(x01: Float, y01: Float) {
        setGrainPosition(x01.coerceIn(0f, 1f))
        setGrainPitchSt((y01.coerceIn(0f, 1f) - 0.5f) * 48f)
    }

    fun onCanvasPinch(spread01Delta: Float) {
        setMacroSpace((_macro.value.space + spread01Delta).coerceIn(0f, 1f))
    }

    fun onCanvasRotate(driftDelta: Float) {
        if (_frozen.value) return   // frozen means drift stays at 0
        setGrainDrift((_grain.value.drift + driftDelta).coerceIn(-2f, 2f))
    }

    fun onPadTwoFingerVertical(pitchSpray01Delta: Float) {
        setMacroPitch((_macro.value.pitch + pitchSpray01Delta).coerceIn(0f, 1f))
    }

    /** Three-finger tap / header button: freeze the ring for review (spec 6.1). */
    fun requestCapture() {
        val pcm = GrooveriderEngine.captureSnapshot(60.0)
        if (pcm.isEmpty()) {
            _captureHint.value = "Nothing captured yet -- play something first."
            return
        }
        _captureReview.value = CaptureState(pcm = pcm, sampleRate = GrooveriderEngine.captureSampleRate())
    }

    fun dismissCaptureHint() { _captureHint.value = null }

    fun setCaptureTrim(start: Float, end: Float) {
        val c = _captureReview.value ?: return
        val s = start.coerceIn(0f, 1f)
        val e = end.coerceIn(0f, 1f)
        _captureReview.value = c.copy(trimStart = minOf(s, e), trimEnd = maxOf(s, e))
    }

    fun discardCapture() { _captureReview.value = null }

    /** Offline re-render at HQ over the trimmed range (spec 6.1, 6.3). */
    fun keepCapture(sourceHash: String, onRendered: (File) -> Unit) {
        val c = _captureReview.value ?: return
        if (sourceHash.isEmpty()) {
            _captureHint.value = "No source loaded -- select one in the Sources tab first."
            return
        }
        val totalFrames = c.pcm.size / 2
        val durationSeconds = ((c.trimEnd - c.trimStart) * totalFrames / c.sampleRate.toDouble())
            .coerceAtLeast(0.5)
        _rendering.value = true
        viewModelScope.launch {
            try {
                val file = renderRepo.renderCurrent(
                    sourceHash = sourceHash, masterSeed = _masterSeed.value, grain = _grain.value,
                    durationSeconds = durationSeconds, seamlessLoop = false,
                )
                _captureReview.value = null
                onRendered(file)
            } catch (e: Exception) {
                _captureHint.value = "Render failed: ${e.message}"
            } finally {
                _rendering.value = false
            }
        }
    }

    // ---- the four curated macros (spec 5.4) ---------------------------------
    // Curated and non-linear so every position on every macro sounds good --
    // the antidote to a macro that can produce a bad sound (spec 5.4, 10).

    fun setMacroTexture(v01: Float) {
        val v = v01.coerceIn(0f, 1f)
        _macro.value = _macro.value.copy(texture = v)
        val (grainSizeMs, density, jitter) = textureCurve(v)
        setGrainSizeMs(grainSizeMs)
        setGrainDensity(density)
        setGrainTimingJitter(jitter)
    }

    fun setMacroDrift(v01: Float) {
        val v = v01.coerceIn(0f, 1f)
        _macro.value = _macro.value.copy(drift = v)
        // Exponential: the interesting territory is all in the bottom 20%.
        setChaosRate(v * v)
    }

    fun setMacroPitch(v01: Float) {
        val v = v01.coerceIn(0f, 1f)
        _macro.value = _macro.value.copy(pitch = v)
        // Detented: 0 unison, 0.25 chorus, 0.5 octaves, 0.75 octave+fifth, 1 wide scatter.
        setGrainPitchSpraySt(v * 24f)
    }

    fun setMacroSpace(v01: Float) {
        val v = v01.coerceIn(0f, 1f)
        _macro.value = _macro.value.copy(space = v)
        setGrainSpread(v)
        setOutputWidth(v * 2f)
    }

    private data class TextureValues(val grainSizeMs: Float, val density: Float, val jitter: Float)

    /** spec 5.4's curated TEXTURE path -- overlap stays in 8-20 across the
     * whole sweep, which is what keeps every position usable. */
    private fun textureCurve(v: Float): TextureValues {
        val points = listOf(
            0.00f to TextureValues(1200f, 12f, 0.05f),
            0.25f to TextureValues(700f, 24f, 0.12f),
            0.50f to TextureValues(400f, 40f, 0.18f),
            0.75f to TextureValues(140f, 80f, 0.35f),
            1.00f to TextureValues(45f, 200f, 0.6f),
        )
        val i = points.indexOfLast { it.first <= v }.coerceIn(0, points.size - 2)
        val (t0, a) = points[i]
        val (t1, b) = points[i + 1]
        val t = if (t1 > t0) ((v - t0) / (t1 - t0)).coerceIn(0f, 1f) else 0f
        return TextureValues(
            grainSizeMs = a.grainSizeMs + (b.grainSizeMs - a.grainSizeMs) * t,
            density = a.density + (b.density - a.density) * t,
            jitter = a.jitter + (b.jitter - a.jitter) * t,
        )
    }

    fun saveSeed(sourceHash: String, waveformThumb: ByteArray) {
        if (sourceHash.isEmpty()) return
        viewModelScope.launch {
            seedRepo.save(masterSeed = _masterSeed.value, grain = _grain.value, sourceHash = sourceHash, waveformThumb = waveformThumb)
        }
    }

    /** Loads a seed's own source + params into the engine and plays it --
     * the engine is always-on once running, so this is also how auditioning
     * works. Ensures the engine is actually running first: without this a
     * seed loaded before the Engine tab/service has ever started would
     * silently do nothing (every native setter no-ops on a null engine). */
    fun loadSeed(seed: Seed) {
        _seedLoadError.value = null
        AudioEngineService.start(getApplication())
        startPolling()
        viewModelScope.launch {
            var attempts = 0
            while (!sourceRepo.loadIntoEngine(seed.sourceHash)) {
                attempts++
                if (attempts >= 20) {
                    _seedLoadError.value = "Couldn't load this seed's source -- " +
                        "it may have been deleted or renamed."
                    return@launch
                }
                delay(50)
            }
            val g = seedRepo.apply(seed)
            _grain.value = g
            _masterSeed.value = seed.masterSeed
        }
    }

    fun dismissSeedLoadError() { _seedLoadError.value = null }

    fun deleteSeed(seed: Seed) {
        viewModelScope.launch { seedRepo.delete(seed) }
    }

    override fun onCleared() {
        pollJob?.cancel()
        cloudPollJob?.cancel()
        super.onCleared()
    }
}
