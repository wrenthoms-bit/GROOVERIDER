package com.delrogue.grooverider.engine

/**
 * Thin, stateless facade over the C++ audio core.
 *
 * Holds no engine state of its own -- the C++ side is the single source of
 * truth, so nothing here can drift out of sync with it.
 */
object GrooveriderEngine {

    init {
        System.loadLibrary("grooverider")
    }

    private val meterScratch = FloatArray(9)

    // ---- lifecycle / transport -------------------------------------------
    fun create() = nativeCreate()
    fun destroy() = nativeDestroy()
    fun start(): Boolean = nativeStart()
    fun stop() = nativeStop()
    fun isRunning(): Boolean = nativeIsRunning()
    fun engineSampleRate(): Int = nativeEngineSampleRate()

    // ---- test tone (M0 debug) --------------------------------------------
    fun setParam(id: Int, value: Float) = nativeSetParam(id, value)
    fun setToneEnabled(on: Boolean) = setParam(ParamId.TONE_ENABLED, if (on) 1f else 0f)
    fun setToneHz(hz: Float) = setParam(ParamId.TONE_HZ, hz)
    fun setToneGain(gain: Float) = setParam(ParamId.TONE_GAIN, gain)
    fun setMasterGain(gain: Float) = setParam(ParamId.MASTER_GAIN, gain)

    // ---- grain engine (M2) -------------------------------------------------
    fun setGrainDensity(perSec: Float) = setParam(ParamId.GRAIN_DENSITY, perSec)
    fun setGrainTimingJitter(v01: Float) = setParam(ParamId.GRAIN_TIMING_JITTER, v01)
    fun setGrainSizeMs(ms: Float) = setParam(ParamId.GRAIN_SIZE_MS, ms)
    fun setGrainSizeJitter(v01: Float) = setParam(ParamId.GRAIN_SIZE_JITTER, v01)
    fun setGrainPosition(norm01: Float) = setParam(ParamId.GRAIN_POSITION, norm01)
    fun setGrainSprayMs(ms: Float) = setParam(ParamId.GRAIN_SPRAY_MS, ms)
    fun setGrainDrift(rate: Float) = setParam(ParamId.GRAIN_DRIFT, rate)
    fun setGrainPitchSt(semitones: Float) = setParam(ParamId.GRAIN_PITCH_ST, semitones)
    fun setGrainPitchSpraySt(semitones: Float) = setParam(ParamId.GRAIN_PITCH_SPRAY_ST, semitones)
    fun setGrainReverseProb(v01: Float) = setParam(ParamId.GRAIN_REVERSE_PROB, v01)
    fun setGrainSpread(v01: Float) = setParam(ParamId.GRAIN_SPREAD, v01)
    fun setGrainWindowType(type: Int) = setParam(ParamId.GRAIN_WINDOW_TYPE, type.toFloat())
    fun setOutputWidth(width: Float) = setParam(ParamId.OUTPUT_WIDTH, width)
    fun setOutputGain(gain: Float) = setParam(ParamId.OUTPUT_GAIN, gain)

    /** The whole random universe for a Seed (spec 3.1-3.3). */
    fun setMasterSeed(seed: Long) = nativeSetMasterSeed(seed)

    // ---- modulation & chaos (M4) -------------------------------------------
    fun setChaosRate(v01: Float) = setParam(ParamId.CHAOS_RATE, v01)
    fun setChaosEnabled(on: Boolean) = nativeSetChaosEnabled(on)

    // ---- source preview (M1) ---------------------------------------------
    /**
     * Load interleaved float PCM as the preview source, resampling from
     * [srcRate] to the engine rate. Returns the resampled frame count.
     */
    fun loadSource(pcm: FloatArray, channels: Int, srcRate: Int): Long {
        val dst = engineSampleRate().let { if (it > 0) it else srcRate }
        return nativeLoadSource(pcm, channels, srcRate, dst)
    }
    fun clearSource() = nativeClearSource()
    fun previewPlay(on: Boolean) = nativePreviewPlay(on)
    fun previewSeek(frame: Long) = nativePreviewSeek(frame)
    fun previewRegion(inFrame: Long, outFrame: Long) = nativePreviewRegion(inFrame, outFrame)
    fun previewLoop(on: Boolean) = nativePreviewLoop(on)
    fun previewGain(g: Float) = nativePreviewGain(g)
    fun previewPosition(): Long = nativePreviewPosition()
    fun previewIsPlaying(): Boolean = nativePreviewIsPlaying()

    // ---- mic capture (M1) ------------------------------------------------
    fun micStart(maxSeconds: Double = 60.0): Boolean = nativeMicStart(maxSeconds)
    fun micStop() = nativeMicStop()
    fun micIsRecording(): Boolean = nativeMicIsRecording()
    fun micLevel(): Float = nativeMicLevel()
    fun micFrames(): Long = nativeMicFrames()
    fun micSampleRate(): Int = nativeMicSampleRate()
    fun micChannels(): Int = nativeMicChannels()
    fun micExtract(): FloatArray = nativeMicExtract()

    @Synchronized
    fun pollMeters(): EngineMeters {
        nativePollMeters(meterScratch)
        return EngineMeters(
            peakL = meterScratch[0], peakR = meterScratch[1],
            latencyMs = meterScratch[2], cpuLoad = meterScratch[3],
            xruns = meterScratch[4].toInt(), bufferFrames = meterScratch[5].toInt(),
            bufferGrows = meterScratch[6].toInt(), running = meterScratch[7] > 0.5f,
            activeVoices = meterScratch[8].toInt(),
        )
    }

    fun configDescription(): String = nativeConfigDescription()

    /** Adaptive voice cap (spec 8.4, M8): 256 flagship / 128 mid-tier / 64 floor. */
    fun profileAndSetVoiceCap(): Int = nativeProfileAndSetVoiceCap()

    /** Up to the last [seconds] of what was actually heard (spec 6.1). */
    fun captureSnapshot(seconds: Double): ShortArray = nativeCaptureSnapshot(seconds)
    fun captureSampleRate(): Int = nativeCaptureSampleRate()

    /**
     * Renders offline at 2x oversample (spec 6.3). [params] is exactly 14
     * floats in the order: density, timingJitter, grainSizeMs, sizeJitter,
     * position, sprayMs, drift, pitchSt, pitchSpraySt, reverseProb, spread,
     * outputWidth, outputGain, chaosRate.
     */
    fun offlineRender(
        pcm: FloatArray,
        channels: Int,
        srcRate: Int,
        dstRate: Int,
        params: FloatArray,
        windowType: Int,
        chaosEnabled: Boolean,
        masterSeed: Long,
        durationSeconds: Double,
        seamlessLoop: Boolean,
        crossfadeSeconds: Double,
    ): FloatArray {
        require(params.size == 14) { "offlineRender params must have exactly 14 floats" }
        return nativeOfflineRender(
            pcm, channels, srcRate, dstRate, params, windowType, chaosEnabled,
            masterSeed, durationSeconds, seamlessLoop, crossfadeSeconds,
        )
    }

    private const val MAX_GRAINS = 256
    private const val CLOUD_STRIDE = 5
    private val cloudScratch = FloatArray(1 + MAX_GRAINS * CLOUD_STRIDE)

    /** Up to 256 active grains for the M5 particle canvas (spec 1.2, 5.2). */
    fun pollCloud(): GrainCloudSnapshot {
        nativePollCloud(cloudScratch)
        val count = cloudScratch[0].toInt().coerceIn(0, MAX_GRAINS)
        val grains = ArrayList<GrainVisual>(count)
        for (i in 0 until count) {
            val base = 1 + i * CLOUD_STRIDE
            grains.add(
                GrainVisual(
                    sourcePosNorm = cloudScratch[base],
                    pitchRatio = cloudScratch[base + 1],
                    amp = cloudScratch[base + 2],
                    age01 = cloudScratch[base + 3],
                    pan = cloudScratch[base + 4],
                )
            )
        }
        return GrainCloudSnapshot(grains)
    }

    // ---- native ----------------------------------------------------------
    private external fun nativeCreate()
    private external fun nativeDestroy()
    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeEngineSampleRate(): Int
    private external fun nativeSetParam(id: Int, value: Float)
    private external fun nativeSetMasterSeed(seed: Long)
    private external fun nativeSetChaosEnabled(on: Boolean)
    private external fun nativePollMeters(out: FloatArray)
    private external fun nativePollCloud(out: FloatArray)
    private external fun nativeConfigDescription(): String
    private external fun nativeProfileAndSetVoiceCap(): Int
    private external fun nativeCaptureSnapshot(seconds: Double): ShortArray
    private external fun nativeCaptureSampleRate(): Int
    private external fun nativeOfflineRender(
        pcm: FloatArray, channels: Int, srcRate: Int, dstRate: Int,
        params: FloatArray, windowType: Int, chaosEnabled: Boolean,
        masterSeed: Long, durationSeconds: Double, seamlessLoop: Boolean, crossfadeSeconds: Double,
    ): FloatArray

    private external fun nativeLoadSource(pcm: FloatArray, channels: Int, srcRate: Int, dstRate: Int): Long
    private external fun nativeClearSource()
    private external fun nativePreviewPlay(on: Boolean)
    private external fun nativePreviewSeek(frame: Long)
    private external fun nativePreviewRegion(inFrame: Long, outFrame: Long)
    private external fun nativePreviewLoop(on: Boolean)
    private external fun nativePreviewGain(g: Float)
    private external fun nativePreviewPosition(): Long
    private external fun nativePreviewIsPlaying(): Boolean

    private external fun nativeMicStart(maxSeconds: Double): Boolean
    private external fun nativeMicStop()
    private external fun nativeMicIsRecording(): Boolean
    private external fun nativeMicLevel(): Float
    private external fun nativeMicFrames(): Long
    private external fun nativeMicSampleRate(): Int
    private external fun nativeMicChannels(): Int
    private external fun nativeMicExtract(): FloatArray
}
