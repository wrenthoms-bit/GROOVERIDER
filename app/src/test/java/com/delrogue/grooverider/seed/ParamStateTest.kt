package com.delrogue.grooverider.seed

import com.delrogue.grooverider.ui.GrainState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ParamStateTest {

    /** A Seed's params exactly as the app wrote them before the Observatory existed (version 2, 72 bytes). */
    private fun version2Blob(): ByteArray =
        ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x47525653); putInt(2)
            putFloat(80f); putFloat(0.35f); putFloat(140f); putFloat(0.4f)      // density, timingJitter, grainSizeMs, sizeJitter
            putFloat(0.3f); putFloat(600f); putFloat(-0.3f); putFloat(-12f)      // position, sprayMs, drift, pitchSt
            putFloat(3f); putFloat(0.6f); putFloat(1.0f)                         // pitchSpraySt, reverseProb, spread
            putInt(2)                                                            // windowType (Hann)
            putFloat(1.6f); putFloat(0.75f); putFloat(0.35f); putInt(1)          // outputWidth, outputGain, chaosRate, chaosEnabled
        }.array()

    @Test
    fun `a Seed saved before the Observatory loads with every value intact and the Observatory off`() {
        val g = ParamState.unpack(version2Blob())
        val expected = GrainState(
            density = 80f, timingJitter = 0.35f, grainSizeMs = 140f, sizeJitter = 0.4f,
            position = 0.3f, sprayMs = 600f, drift = -0.3f, pitchSt = -12f,
            pitchSpraySt = 3f, reverseProb = 0.6f, spread = 1.0f, windowType = 2,
            outputWidth = 1.6f, outputGain = 0.75f, chaosRate = 0.35f, chaosEnabled = true,
        )
        assertEquals(expected, g)
        assertFalse("an old Seed must stay on the plain grain engine", g.observatory)
    }

    @Test
    fun `every field survives pack and unpack`() {
        val g = GrainState(
            density = 60f, timingJitter = 0.5f, grainSizeMs = 2000f, sizeJitter = 0.35f,
            position = 0.42f, sprayMs = 900f, drift = 0f, pitchSt = 1f, pitchSpraySt = 2f,
            reverseProb = 0.3f, spread = 0.868f, windowType = 1, outputWidth = 1.4f, outputGain = 0.8f,
            chaosRate = 0.2f, chaosEnabled = false,
            observatory = true, chaos = 0.14f, pitchAmount = 0.30f, key = 3, scale = 3, register = -12f,
            detune = 0.06f, drone = true, space = 0.86f, shimmer = 0.42f, tone = 0.52f, scan = -0.25f,
        )
        assertEquals(g, ParamState.unpack(ParamState.pack(g)))
    }

    @Test
    fun `damaged or foreign bytes fall back to defaults instead of crashing`() {
        assertEquals(GrainState(), ParamState.unpack(ByteArray(0)))
        assertEquals(GrainState(), ParamState.unpack(ByteArray(72)))                       // no magic
        assertEquals(GrainState(), ParamState.unpack(version2Blob().copyOf(71)))           // truncated
        val future = ParamState.pack(GrainState()).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 99) }
        assertEquals(GrainState(), ParamState.unpack(future))                              // unknown version
    }

    @Test
    fun `mutating an old Seed leaves it on the plain grain engine, and its lock bits where they were`() {
        val parent = Seed(
            id = "p", name = "Old", masterSeed = 42L, params = version2Blob(), modRoutes = ByteArray(0),
            sourceHash = "h", inPointMs = 0, outPointMs = 0, parentId = null, mutationDistance = 0f,
            lockedParams = 0L, favourite = false, renderCount = 0, createdAt = 0L, waveformThumb = ByteArray(0),
        )
        SeedMutation.mutateSix(parent, 0.5f).forEach { assertFalse(ParamState.unpack(it.params).observatory) }
        assertEquals(listOf("DENSITY", "GRAIN_SIZE", "POSITION", "DRIFT", "REVERSE_PROB", "PITCH", "TIMING_JITTER",
            "SIZE_JITTER", "SPRAY", "PITCH_SPRAY", "SPREAD"), MutableParam.entries.take(11).map { it.name })
    }
}
