package com.delrogue.grooverider.seed

import com.delrogue.grooverider.onboarding.FactoryContent
import com.delrogue.grooverider.ui.GrainState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SeedShareFormatTest {

    /** The file the web app's browser tests load as "a seed from the phone" (webtests/browser.test.mjs). */
    private val fixture: String = File("../core/tests/fixtures/standing-room-only.grvr").readText()
    private val sro = FactoryContent.presets.first()

    private fun assertSamePatch(expected: GrainState, actual: GrainState) {
        // field by field, with float tolerance: the file holds decimal text
        val e = ParamState.unpack(ParamState.pack(expected)); val a = ParamState.unpack(ParamState.pack(actual))
        assertEquals(e.density, a.density, 1e-4f); assertEquals(e.grainSizeMs, a.grainSizeMs, 1e-3f)
        assertEquals(e.timingJitter, a.timingJitter, 1e-5f); assertEquals(e.sizeJitter, a.sizeJitter, 1e-5f)
        assertEquals(e.position, a.position, 1e-5f); assertEquals(e.sprayMs, a.sprayMs, 1e-3f)
        assertEquals(e.reverseProb, a.reverseProb, 1e-5f); assertEquals(e.spread, a.spread, 1e-5f)
        assertEquals(e.windowType, a.windowType); assertEquals(e.outputWidth, a.outputWidth, 1e-5f)
        assertEquals(e.outputGain, a.outputGain, 1e-5f); assertEquals(e.observatory, a.observatory)
        assertEquals(e.chaos, a.chaos, 1e-5f); assertEquals(e.pitchAmount, a.pitchAmount, 1e-5f)
        assertEquals(e.key, a.key); assertEquals(e.scale, a.scale); assertEquals(e.register, a.register, 1e-5f)
        assertEquals(e.detune, a.detune, 1e-5f); assertEquals(e.drone, a.drone)
        assertEquals(e.space, a.space, 1e-5f); assertEquals(e.shimmer, a.shimmer, 1e-5f)
        assertEquals(e.tone, a.tone, 1e-5f); assertEquals(e.scan, a.scan, 1e-5f)
    }

    @Test
    fun `the shared fixture reads as Standing Room Only`() {
        val shared = SeedShareFormat.decode(fixture)!!
        assertEquals("Standing Room Only", shared.name)
        assertEquals(sro.masterSeed, shared.masterSeed)
        assertEquals("Big River", shared.sourceName)
        assertSamePatch(sro.grain, shared.grain)
    }

    @Test
    fun `what the phone writes says the same as the fixture the web is tested against`() {
        val written = JSONObject(SeedShareFormat.encode(
            SeedShareFormat.Shared(sro.name, sro.masterSeed, sro.grain, "Big River", "abc123")))
        val expected = JSONObject(fixture)
        assertEquals(expected.getString("format"), written.getString("format"))
        assertEquals(expected.getInt("version"), written.getInt("version"))
        assertEquals(expected.getString("masterSeed"), written.getString("masterSeed"))
        assertEquals(expected.getBoolean("observatory"), written.getBoolean("observatory"))
        val e = expected.getJSONObject("params"); val w = written.getJSONObject("params")
        val expectedKeys = e.keys().asSequence().toSet()
        assertEquals("every setting the web reads is written", expectedKeys, w.keys().asSequence().toSet())
        for (key in expectedKeys) when (val value = e.get(key)) {
            is Number -> assertEquals(key, value.toDouble(), w.getDouble(key), 1e-5)
            else -> assertEquals(key, value, w.get(key))
        }
    }

    @Test
    fun `every setting survives the phone writing and reading its own file, plain-engine seeds included`() {
        val plain = GrainState(
            density = 24f, grainSizeMs = 700f, timingJitter = 0.12f, sizeJitter = 0.31f, position = 0.3f, sprayMs = 4200f,
            drift = -0.3f, pitchSt = -12f, pitchSpraySt = 4.5f, reverseProb = 0.6f, spread = 1f, windowType = 2,
            outputWidth = 1.6f, outputGain = 0.75f, chaosRate = 0.35f, chaosEnabled = true,
        )
        val seed = -0x6543210FEDCBA988L          // top bit set: must not be mangled on the way through text
        val back = SeedShareFormat.decode(SeedShareFormat.encode(SeedShareFormat.Shared("Slow Tar", seed, plain, "Mic take", "h")))!!
        assertEquals(seed, back.masterSeed); assertEquals("Slow Tar", back.name); assertEquals("h", back.sourceHash)
        assertFalse(back.grain.observatory)
        assertSamePatch(plain, back.grain)
        assertEquals(plain.drift, back.grain.drift, 1e-5f); assertEquals(plain.pitchSt, back.grain.pitchSt, 1e-5f)
        assertEquals(plain.pitchSpraySt, back.grain.pitchSpraySt, 1e-5f); assertTrue(back.grain.chaosEnabled)
    }

    @Test
    fun `a seed saved by the web app, which has no plain-engine settings, opens on the Observatory`() {
        val fromWeb = JSONObject(fixture).apply { remove("plain"); getJSONObject("params").put("scan", 0.25) }.toString()
        val shared = SeedShareFormat.decode(fromWeb)!!
        assertTrue(shared.grain.observatory)
        assertFalse("First Light's routes stay off", shared.grain.chaosEnabled)
        assertEquals("scan stands in for drift", 0.25f, shared.grain.drift, 1e-6f)
        assertEquals("", shared.sourceHash)
    }

    @Test
    fun `out-of-range values are clamped, and files that are not seeds are refused`() {
        val wild = JSONObject(fixture).apply {
            getJSONObject("params").put("density", 9000).put("key", 40).put("space", -3).put("window", "triangle")
        }.toString()
        val g = SeedShareFormat.decode(wild)!!.grain
        assertEquals(200f, g.density, 0f); assertEquals(11, g.key); assertEquals(0f, g.space, 0f); assertEquals(0, g.windowType)

        assertNull(SeedShareFormat.decode("not json"))
        assertNull(SeedShareFormat.decode("{\"hello\":\"world\"}"))
        assertNull("a newer format is not guessed at", SeedShareFormat.decode(JSONObject(fixture).put("version", 2).toString()))
        assertNotNull(SeedShareFormat.decode(JSONObject(fixture).put("masterSeed", "0x0051a9d005700a11").toString()))
    }
}
