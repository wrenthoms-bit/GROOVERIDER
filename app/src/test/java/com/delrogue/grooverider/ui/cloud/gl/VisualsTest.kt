package com.delrogue.grooverider.ui.cloud.gl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class VisualsTest {

    private val patch = MoteField.Patch(
        grainMs = 2000f, density = 60f, transposeSt = -9f, pitchSpreadSt = 7.2f,
        scaleSet = MoteField.SCALE_SETS[3], spread = 0.868f, reverse = 0.3f, position = 0.42f,
    )

    /** `n` grains in the cloud layout: source position, rate, amplitude, age, right-pan gain. */
    private fun cloud(n: Int) = FloatArray(n * MoteField.CLOUD_STRIDE).also { c ->
        for (i in 0 until n) {
            c[i * 5] = i / n.toFloat(); c[i * 5 + 1] = if (i % 3 == 0) -0.5f else 1f + i * 0.01f
            c[i * 5 + 2] = 0.6f; c[i * 5 + 3] = (i % 10) / 10f; c[i * 5 + 4] = sin(i * 0.1f) * 0.5f + 0.5f
        }
    }

    @Test
    fun `every grain becomes a body with followers, plus the plankton, all drawable`() {
        val field = MoteField()
        field.build(12.5, cloud(120), 120, patch, aspect = 2.2f, chaosX = 0.3f, chaosY = -0.2f)
        assertEquals(120 * (1 + 3) + 170, field.count)           // 3 followers each at full quality
        for (i in 0 until field.count * MoteField.STRIDE) assertTrue(field.data[i].isFinite())
        for (i in 0 until field.count) {
            val o = i * MoteField.STRIDE
            assertTrue("alpha in range", field.data[o + 5] in 0f..1f)
            assertTrue("size positive", field.data[o + 6] > 0f)
            assertTrue("on or near the screen", abs(field.data[o]) < 2f && abs(field.data[o + 1]) < 2f)
        }
    }

    @Test
    fun `the same moment draws the same picture, and bodies move with time`() {
        val a = MoteField().apply { build(3.0, cloud(40), 40, patch, 2f, 0f, 0f) }
        val b = MoteField().apply { build(3.0, cloud(40), 40, patch, 2f, 0f, 0f) }
        assertArrayEquals(a.data, b.data, 0f)
        val later = MoteField().apply { build(4.0, cloud(40), 40, patch, 2f, 0f, 0f) }
        assertTrue(abs(a.data[0] - later.data[0]) > 1e-5f)
    }

    @Test
    fun `a huge cloud never overruns the vertex buffer, and shed quality draws fewer bodies`() {
        val field = MoteField()
        field.build(1.0, cloud(256), 256, patch, 2f, 0f, 0f)
        assertTrue(field.count <= MoteField.MAX_POINTS)
        val lean = MoteField().apply { quality = 4; followers = 0; plankton = 60 }
        lean.build(1.0, cloud(120), 120, patch, 2f, 0f, 0f)
        assertEquals(120 + 60, lean.count)
    }

    @Test
    fun `the idle preview follows the patch - overlap, key and scale`() {
        val field = MoteField()
        val out = FloatArray(256 * MoteField.CLOUD_STRIDE)
        val n = field.ghost(7.0, patch, out)
        assertEquals(120, n)                                     // density x grain length
        val minor = setOf(0, 2, 3, 5, 7, 8, 10)
        for (k in 0 until n) {
            val semitones = 12.0 * Math.log(abs(out[k * 5 + 1]).toDouble()) / Math.log(2.0) + 9.0   // back to the scale's root
            val nearest = Math.round(semitones).toInt()
            assertTrue("within the reverse/detune wobble of a scale note", abs(semitones - nearest) < 0.05)
            assertTrue("degree ${((nearest % 12) + 12) % 12} is in the minor scale", ((nearest % 12) + 12) % 12 in minor)
            assertTrue(out[k * 5 + 2] in 0f..0.56f)
        }
    }

    @Test
    fun `the tide puts a tone on the right row and silence at the bottom of the palette`() {
        val tide = SpectralTide()
        val column = ByteArray(SpectralTide.ROWS * 4)
        val mags = FloatArray(SpectralTide.BINS)
        // a full-scale 1 kHz sine at 48 kHz: bin 43, spread over its neighbours by the analysis window
        mags[41] = 0.02f; mags[42] = 0.12f; mags[43] = 0.21f; mags[44] = 0.12f; mags[45] = 0.02f
        repeat(20) { tide.column(mags, 48000, column) }          // let the smoothing settle
        val brightest = (0 until SpectralTide.ROWS).maxByOrNull { (column[it * 4].toInt() and 0xFF) + (column[it * 4 + 1].toInt() and 0xFF) }!!
        // rows run 40 Hz .. 16 kHz on a log scale: 1 kHz is 53.7% of the way up
        assertEquals(0.537f, brightest / (SpectralTide.ROWS - 1f), 0.03f)
        assertTrue("a loud tone reaches the warm end of the palette", (column[brightest * 4].toInt() and 0xFF) > 200)

        repeat(60) { tide.column(null, 48000, column) }          // no audio: it ebbs away
        for (y in 0 until SpectralTide.ROWS) {
            assertEquals(3, column[y * 4].toInt() and 0xFF); assertEquals(5, column[y * 4 + 1].toInt() and 0xFF)
            assertEquals(6, column[y * 4 + 2].toInt() and 0xFF)
        }
    }

    @Test
    fun `levels map as a Web Audio analyser maps them`() {
        assertEquals(0f, SpectralTide.byteLevel(0f), 0f)
        assertEquals(0f, SpectralTide.byteLevel(1e-6f), 0f)               // -120 dB: below the floor
        assertEquals(255f, SpectralTide.byteLevel(0.1f), 0f)              // -20 dB: above the ceiling
        assertEquals(255f * 40f / 70f, SpectralTide.byteLevel(0.001f), 0.5f)   // -60 dB
    }
}
