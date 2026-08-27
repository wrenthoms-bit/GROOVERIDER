package com.delrogue.grooverider.onboarding

import com.delrogue.grooverider.seed.ParamState
import com.delrogue.grooverider.seed.Seed
import com.delrogue.grooverider.seed.SeedNaming
import com.delrogue.grooverider.ui.GrainState
import java.util.UUID

/**
 * Factory presets (spec M8). Spec's own words: "budget real listening time
 * for them, they are not config" -- what ships here is mechanically varied
 * across the param space so a fresh install has something to browse, not a
 * substitute for the ear-tuned curation a real release needs.
 */
object FactoryContent {

    fun presets(sourceHash: String): List<Seed> = listOf(
        // masterSeed, density, grainSizeMs, position, spray, drift, pitchSt, pitchSpray, reverse, spread, chaosRate
        preset(1001L, 12f, 1200f, 0.5f, 400f, 0.02f, 0f, 0.1f, 0.05f, 0.6f, 0.15f),      // glassy drone
        preset(1002L, 24f, 700f, 0.5f, 300f, 0.05f, 0f, 0.15f, 0.1f, 0.7f, 0.25f),        // smooth pad
        preset(1003L, 40f, 400f, 0.5f, 250f, 0.05f, 0f, 0.15f, 0.2f, 0.8f, 0.3f),         // default cloud
        preset(1004L, 80f, 140f, 0.5f, 200f, 0.1f, 0f, 0.3f, 0.3f, 0.9f, 0.4f),           // shimmering
        preset(1005L, 160f, 60f, 0.5f, 100f, 0.15f, 0f, 0.5f, 0.4f, 1.0f, 0.5f),          // dense sand
        preset(1006L, 30f, 500f, 0.3f, 600f, -0.3f, -12f, 3f, 0.6f, 1.0f, 0.35f),         // reverse octave wash
        preset(1007L, 50f, 350f, 0.5f, 150f, 0.05f, 7f, 8f, 0.15f, 0.9f, 0.6f),           // wide chromatic scatter
        preset(1008L, 20f, 900f, 0.5f, 350f, 0.0f, 0f, 0.1f, 0.05f, 0.5f, 0.05f),         // frozen-ish glass
    ).map { it.copy(sourceHash = sourceHash) }

    private fun preset(
        masterSeed: Long, density: Float, grainSizeMs: Float, position: Float, sprayMs: Float,
        drift: Float, pitchSt: Float, pitchSpraySt: Float, reverseProb: Float, spread: Float, chaosRate: Float,
    ): Seed {
        val grain = GrainState(
            density = density, grainSizeMs = grainSizeMs, position = position, sprayMs = sprayMs,
            drift = drift, pitchSt = pitchSt, pitchSpraySt = pitchSpraySt, reverseProb = reverseProb,
            spread = spread, chaosRate = chaosRate,
        )
        return Seed(
            id = UUID.randomUUID().toString(),
            name = SeedNaming.nameFor(masterSeed),
            masterSeed = masterSeed,
            params = ParamState.pack(grain),
            modRoutes = ByteArray(0),
            sourceHash = "",
            inPointMs = 0,
            outPointMs = 0,
            parentId = null,
            mutationDistance = 0f,
            lockedParams = 0L,
            favourite = false,
            renderCount = 0,
            createdAt = System.currentTimeMillis(),
            waveformThumb = ByteArray(0),
        )
    }
}
