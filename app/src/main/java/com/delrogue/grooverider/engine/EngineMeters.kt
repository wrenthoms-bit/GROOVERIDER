package com.delrogue.grooverider.engine

/** One grain, as the cloud visualisation needs it (spec 1.2, 5.2). */
data class GrainVisual(
    val sourcePosNorm: Float,
    val pitchRatio: Float,
    val amp: Float,
    val age01: Float,
    val pan: Float,
)

data class GrainCloudSnapshot(val grains: List<GrainVisual>)

/** One published snapshot from the audio thread (spec 1.2). */
data class EngineMeters(
    val peakL: Float = 0f,
    val peakR: Float = 0f,
    val latencyMs: Float = 0f,
    val cpuLoad: Float = 0f,
    val xruns: Int = 0,
    val bufferFrames: Int = 0,
    val bufferGrows: Int = 0,
    val running: Boolean = false,
    val activeVoices: Int = 0,
)
