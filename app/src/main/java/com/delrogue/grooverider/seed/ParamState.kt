package com.delrogue.grooverider.seed

import com.delrogue.grooverider.ui.GrainState
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fixed-layout pack/unpack for the grain engine's continuous+discrete params
 * (spec 3.3's `params: ByteArray`). Deliberately dumb and versioned by
 * position, not reflection -- a Seed's bytes must decode the same way forever.
 *
 * Version 3 appends the Observatory's params to version 2's layout. A version 2
 * blob still decodes: it gets the Observatory switched off, which is the engine
 * it was saved on.
 */
object ParamState {
    private const val MAGIC = 0x47525653   // "GRVS"
    private const val VERSION = 3
    private const val SIZE_V2 = 4 + 4 + 11 * 4 + 4 + 2 * 4 + 4 + 4   // ... chaosRate float + chaosEnabled int
    private const val SIZE_V3 = SIZE_V2 + 12 * 4                     // + observatory int + 11 Observatory params

    fun pack(g: GrainState): ByteArray {
        val buf = ByteBuffer.allocate(SIZE_V3).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MAGIC)
        buf.putInt(VERSION)
        buf.putFloat(g.density)
        buf.putFloat(g.timingJitter)
        buf.putFloat(g.grainSizeMs)
        buf.putFloat(g.sizeJitter)
        buf.putFloat(g.position)
        buf.putFloat(g.sprayMs)
        buf.putFloat(g.drift)
        buf.putFloat(g.pitchSt)
        buf.putFloat(g.pitchSpraySt)
        buf.putFloat(g.reverseProb)
        buf.putFloat(g.spread)
        buf.putInt(g.windowType)
        buf.putFloat(g.outputWidth)
        buf.putFloat(g.outputGain)
        buf.putFloat(g.chaosRate)
        buf.putInt(if (g.chaosEnabled) 1 else 0)
        // --- version 3
        buf.putInt(if (g.observatory) 1 else 0)
        buf.putFloat(g.chaos)
        buf.putFloat(g.pitchAmount)
        buf.putInt(g.key)
        buf.putInt(g.scale)
        buf.putFloat(g.register)
        buf.putFloat(g.detune)
        buf.putInt(if (g.drone) 1 else 0)
        buf.putFloat(g.space)
        buf.putFloat(g.shimmer)
        buf.putFloat(g.tone)
        buf.putFloat(g.scan)
        return buf.array()
    }

    /** Falls back to defaults for anything that fails to decode, rather than crashing on load. */
    fun unpack(bytes: ByteArray): GrainState {
        if (bytes.size < 8) return GrainState()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int
        val version = buf.int
        if (magic != MAGIC) return GrainState()
        val expectedSize = when (version) { 2 -> SIZE_V2; 3 -> SIZE_V3; else -> return GrainState() }
        if (bytes.size != expectedSize) return GrainState()
        val base = GrainState(
            density = buf.float,
            timingJitter = buf.float,
            grainSizeMs = buf.float,
            sizeJitter = buf.float,
            position = buf.float,
            sprayMs = buf.float,
            drift = buf.float,
            pitchSt = buf.float,
            pitchSpraySt = buf.float,
            reverseProb = buf.float,
            spread = buf.float,
            windowType = buf.int,
            outputWidth = buf.float,
            outputGain = buf.float,
            chaosRate = buf.float,
            chaosEnabled = buf.int != 0,
        )
        if (version < 3) return base   // observatory = false and the rest at their defaults
        return base.copy(
            observatory = buf.int != 0,
            chaos = buf.float,
            pitchAmount = buf.float,
            key = buf.int,
            scale = buf.int,
            register = buf.float,
            detune = buf.float,
            drone = buf.int != 0,
            space = buf.float,
            shimmer = buf.float,
            tone = buf.float,
            scan = buf.float,
        )
    }
}
