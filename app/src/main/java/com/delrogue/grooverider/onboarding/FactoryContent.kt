package com.delrogue.grooverider.onboarding

import com.delrogue.grooverider.engine.ObservatoryMacros
import com.delrogue.grooverider.seed.ParamState
import com.delrogue.grooverider.seed.Seed
import com.delrogue.grooverider.ui.GrainState

/**
 * Factory presets: the web app's four (docs/index.html, PRESETS), given here in
 * the web's own terms and run through the same curves the web runs them
 * through, so each lands on the engine as the same patch.
 */
object FactoryContent {

    const val BIG_RIVER_ASSET = "big-river.m4a"
    const val BIG_RIVER_NAME = "Big River"

    /** Window ids as the web numbers them; Android's Seeds number Tukey and Hann the other way round. */
    private const val WEB_GAUSSIAN = 0
    private const val WEB_HANN = 1
    private const val WEB_TUKEY = 2
    private fun androidWindow(web: Int) = when (web) { WEB_HANN -> 2; WEB_TUKEY -> 1; else -> 0 }

    data class Preset(
        val id: String,
        val name: String,
        val subtitle: String,
        val masterSeed: Long,
        val grain: GrainState,
    )

    const val STANDING_ROOM_ONLY_ID = "factory-standing-room-only"

    val presets: List<Preset> = listOf(
        webPreset(
            STANDING_ROOM_ONLY_ID, "Standing Room Only", "aquatic shoaling drone · translucent, crowding, embryonic",
            seedLo = 0x05700A11, seedHi = 0x0051A9D0, drone = true, key = 3, scale = 3, window = WEB_GAUSSIAN,
            texture = 0.04f, drift = 0.14f, space = 0.86f, pitch = 0.30f, density = 60f, grainMs = 2000f, position = 0.42f,
            scan = 0f, sprayMs = 900f, reverse = 0.30f, register = -12f, shimmer = 0.42f, tone = 0.52f, width = 1.4f, gain = 0.80f,
        ),
        webPreset(
            "factory-glass-tide", "Glass Tide", "slow pentatonic pad · the playhead wanders, shimmer on the crest",
            seedLo = 0x61A55000, seedHi = 0x00071DE5, drone = false, key = 0, scale = 4, window = WEB_HANN,
            texture = 0.30f, drift = 0.34f, space = 0.72f, pitch = 0.48f, density = 38f, grainMs = 640f, position = 0.25f,
            scan = 0.06f, sprayMs = 420f, reverse = 0.15f, register = 0f, shimmer = 0.66f, tone = 0.74f, width = 1.3f, gain = 0.85f,
        ),
        webPreset(
            "factory-ember-churn", "Ember Churn", "fast chaos · short grains boiling in a warm hall",
            seedLo = 0x0E3BE400, seedHi = 0x00C4A057, drone = false, key = 9, scale = 5, window = WEB_TUKEY,
            texture = 0.80f, drift = 0.86f, space = 0.50f, pitch = 0.62f, density = 120f, grainMs = 85f, position = 0.60f,
            scan = 0.30f, sprayMs = 700f, reverse = 0.40f, register = 0f, shimmer = 0.22f, tone = 0.86f, width = 1.5f, gain = 0.85f,
        ),
        webPreset(
            "factory-bare-grains", "Bare Grains", "the core on its own · nearly dry, unlocked, for reference",
            seedLo = 0x9ABCDEF0, seedHi = 0x12345678, drone = false, key = 0, scale = 0, window = WEB_GAUSSIAN,
            texture = 0.50f, drift = 0.04f, space = 0.06f, pitch = 0.10f, density = 40f, grainMs = 400f, position = 0.50f,
            scan = 0.05f, sprayMs = 250f, reverse = 0.20f, register = 0f, shimmer = 0f, tone = 1f, width = 1f, gain = 0.90f,
        ),
    )

    fun subtitleFor(seedId: String): String? = presets.firstOrNull { it.id == seedId }?.subtitle

    /** The presets as Seeds on [sourceHash], newest-first in the order above. */
    fun seeds(sourceHash: String, waveformThumb: ByteArray, now: Long = System.currentTimeMillis()): List<Seed> =
        presets.mapIndexed { index, p ->
            Seed(
                id = p.id,                       // fixed, so installing twice replaces rather than duplicates
                name = p.name,
                masterSeed = p.masterSeed,
                params = ParamState.pack(p.grain),
                modRoutes = ByteArray(0),
                sourceHash = sourceHash,
                inPointMs = 0,
                outPointMs = 0,
                parentId = null,
                mutationDistance = 0f,
                lockedParams = 0L,
                favourite = false,
                renderCount = 0,
                createdAt = now - index,
                waveformThumb = waveformThumb,
            )
        }

    private fun webPreset(
        id: String, name: String, subtitle: String, seedLo: Long, seedHi: Long, drone: Boolean, key: Int, scale: Int, window: Int,
        texture: Float, drift: Float, space: Float, pitch: Float, density: Float, grainMs: Float, position: Float,
        scan: Float, sprayMs: Float, reverse: Float, register: Float, shimmer: Float, tone: Float, width: Float, gain: Float,
    ): Preset {
        // TEXTURE supplies the timing jitter; the preset's own grain size and density win over its curve.
        val jitter = ObservatoryMacros.texture(texture).timingJitter
        val g = ObservatoryMacros.effective(grainMs, density, jitter, scan, drone)
        return Preset(
            id = id, name = name, subtitle = subtitle,
            masterSeed = (seedHi shl 32) or seedLo,
            grain = GrainState(
                density = g.density, timingJitter = g.timingJitter, grainSizeMs = g.grainMs, sizeJitter = g.sizeJitter,
                position = position, sprayMs = sprayMs, reverseProb = reverse,
                spread = ObservatoryMacros.spreadForWidth(width), windowType = androidWindow(window),
                outputWidth = width, outputGain = gain,
                // The Observatory steers these three itself; scan is its drift.
                drift = g.scan, pitchSt = 0f, pitchSpraySt = 0f,
                chaosEnabled = false,   // First Light's mod matrix stands down: the Observatory has its own chaos
                observatory = true,
                chaos = drift, pitchAmount = pitch, key = key, scale = scale, register = register, detune = 0.06f,
                drone = drone, space = space, shimmer = shimmer, tone = tone, scan = g.scan,
            ),
        )
    }
}
