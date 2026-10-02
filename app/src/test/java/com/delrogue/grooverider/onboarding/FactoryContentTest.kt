package com.delrogue.grooverider.onboarding

import com.delrogue.grooverider.seed.ParamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FactoryContentTest {

    @Test
    fun `the four web presets, Standing Room Only first`() {
        assertEquals(
            listOf("Standing Room Only", "Glass Tide", "Ember Churn", "Bare Grains"),
            FactoryContent.presets.map { it.name },
        )
        val seeds = FactoryContent.seeds("hash", ByteArray(0), now = 1000L)
        assertEquals(FactoryContent.STANDING_ROOM_ONLY_ID, seeds.maxByOrNull { it.createdAt }!!.id)   // newest = top of the Library
        assertTrue(seeds.all { ParamState.unpack(it.params).observatory })
    }

    /** The state the web app hands its engine for this preset (webtests/engine.test.mjs, `SRO`). */
    @Test
    fun `Standing Room Only reaches the engine as the web sends it`() {
        val p = FactoryContent.presets.first()
        val g = p.grain
        assertEquals(0x0051A9D005700A11L, p.masterSeed)
        assertEquals(60f, g.density, 0f); assertEquals(2000f, g.grainSizeMs, 0f)
        assertEquals(0.5f, g.timingJitter, 1e-6f); assertEquals(0.35f, g.sizeJitter, 1e-6f)
        assertEquals(900f, g.sprayMs, 0f); assertEquals(0.30f, g.reverseProb, 1e-6f)
        assertEquals(0, g.windowType)
        assertEquals(0.868f, g.spread, 1e-4f); assertEquals(1.4f, g.outputWidth, 1e-6f); assertEquals(0.8f, g.outputGain, 1e-6f)
        assertEquals(0.42f, g.position, 1e-6f); assertEquals(0f, g.scan, 0f)
        assertEquals(0.14f, g.chaos, 1e-6f); assertEquals(0.30f, g.pitchAmount, 1e-6f)
        assertEquals(3, g.key); assertEquals(3, g.scale); assertEquals(-12f, g.register, 0f); assertEquals(0.06f, g.detune, 1e-6f)
        assertTrue(g.drone)
        assertEquals(0.86f, g.space, 1e-6f); assertEquals(0.42f, g.shimmer, 1e-6f); assertEquals(0.52f, g.tone, 1e-6f)
    }

    @Test
    fun `the other presets pick up the web's texture jitter and window`() {
        val byName = FactoryContent.presets.associateBy { it.name }
        val glass = byName.getValue("Glass Tide").grain
        assertEquals(0.236f, glass.timingJitter, 1e-4f); assertEquals(0.25f, glass.sizeJitter, 1e-6f)
        assertEquals(2, glass.windowType)                    // the web's Hann
        assertEquals(0.06f, glass.scan, 1e-6f); assertEquals(4, glass.scale)
        val ember = byName.getValue("Ember Churn").grain
        assertEquals(0.40f, ember.timingJitter, 1e-4f); assertEquals(1, ember.windowType)   // the web's Tukey
        assertEquals(0.93f, ember.spread, 1e-4f)
        val bare = byName.getValue("Bare Grains").grain
        assertEquals(0x123456789ABCDEF0L, byName.getValue("Bare Grains").masterSeed)
        assertEquals(0.18f, bare.timingJitter, 1e-4f); assertEquals(0, bare.scale); assertEquals(0.62f, bare.spread, 1e-4f)
    }
}
