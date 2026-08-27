package com.delrogue.grooverider.seed

import com.delrogue.grooverider.ui.GrainState
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fixed-layout pack/unpack for the grain engine's continuous+discrete params
 * (spec 3.3's `params: ByteArray`). Deliberately dumb and versioned by
 * position, not reflection -- a Seed's bytes must decode the same way forever.
 */
object ParamState {
    private const val MAGIC = 0x47525653   // "GRVS"
    private const val VERSION = 2
    private const val SIZE = 4 + 4 + 11 * 4 + 4 + 2 * 4 + 4 + 4   // + chaosRate float + chaosEnabled int

    fun pack(g: GrainState): ByteArray {
        val buf = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
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
        return buf.array()
    }

    /** Falls back to defaults for anything that fails to decode, rather than crashing on load. */
    fun unpack(bytes: ByteArray): GrainState {
        if (bytes.size != SIZE) return GrainState()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int
        val version = buf.int
        if (magic != MAGIC || version != VERSION) return GrainState()
        return GrainState(
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
    }
}
