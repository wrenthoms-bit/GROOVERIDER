package com.delrogue.grooverider.seed

import com.delrogue.grooverider.ui.GrainState
import java.util.UUID

/**
 * Two parents -> child (spec 3.7): params linearly interpolated at a
 * user-set blend; masterSeed and mod routes inherited wholesale from
 * whichever parent the blend favours -- interpolating a routing matrix
 * produces nonsense, not a hybrid.
 */
object SeedBreeding {
    fun breed(parentA: Seed, parentB: Seed, blend: Float): Seed {
        val b = blend.coerceIn(0f, 1f)
        val gA = ParamState.unpack(parentA.params)
        val gB = ParamState.unpack(parentB.params)
        fun lerp(a: Float, c: Float) = a + (c - a) * b

        val child = GrainState(
            density = lerp(gA.density, gB.density),
            timingJitter = lerp(gA.timingJitter, gB.timingJitter),
            grainSizeMs = lerp(gA.grainSizeMs, gB.grainSizeMs),
            sizeJitter = lerp(gA.sizeJitter, gB.sizeJitter),
            position = lerp(gA.position, gB.position),
            sprayMs = lerp(gA.sprayMs, gB.sprayMs),
            drift = lerp(gA.drift, gB.drift),
            pitchSt = lerp(gA.pitchSt, gB.pitchSt),
            pitchSpraySt = lerp(gA.pitchSpraySt, gB.pitchSpraySt),
            reverseProb = lerp(gA.reverseProb, gB.reverseProb),
            spread = lerp(gA.spread, gB.spread),
            windowType = if (b < 0.5f) gA.windowType else gB.windowType,
            outputWidth = lerp(gA.outputWidth, gB.outputWidth),
            outputGain = lerp(gA.outputGain, gB.outputGain),
            chaosRate = lerp(gA.chaosRate, gB.chaosRate),
            chaosEnabled = if (b < 0.5f) gA.chaosEnabled else gB.chaosEnabled,
        )

        val favoured = if (b < 0.5f) parentA else parentB
        return Seed(
            id = UUID.randomUUID().toString(),
            name = SeedNaming.nameFor(favoured.masterSeed),
            masterSeed = favoured.masterSeed,
            params = ParamState.pack(child),
            modRoutes = favoured.modRoutes,
            sourceHash = favoured.sourceHash,
            inPointMs = favoured.inPointMs,
            outPointMs = favoured.outPointMs,
            parentId = favoured.id,
            mutationDistance = 0f,
            lockedParams = 0L,
            favourite = false,
            renderCount = 0,
            createdAt = System.currentTimeMillis(),
            waveformThumb = favoured.waveformThumb,
        )
    }
}
