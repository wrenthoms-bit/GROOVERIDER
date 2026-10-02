package com.delrogue.grooverider.ui.cloud.gl

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Observatory's motes, ported from the web app (docs/index.html,
 * `buildMotes` / `ghostCloud`). Every active grain becomes a small translucent
 * body -- ovum or fry -- plus a few followers, so a dense cloud reads as a
 * shoal; plankton drifts behind for depth. A body's place is a pure function of
 * its grain's own data and the time, so bodies swim smoothly with no per-grain
 * state kept here.
 *
 * Pure Kotlin, no Android or GL types: the renderer uploads [data] as it is.
 */
class MoteField {

    /** What the motes need to know about the patch that is playing. */
    class Patch(
        val grainMs: Float,
        val density: Float,
        val transposeSt: Float,          // the cloud's pitch centre, drawn at mid-height
        val pitchSpreadSt: Float,        // how far grains scatter round it (used for the idle preview)
        val scaleSet: IntArray?,         // semitones of the locked scale, or null when free
        val spread: Float,
        val reverse: Float,
        val position: Float,
    )

    /** [count] bodies, [STRIDE] floats each: x, y (clip space), r, g, b, a, size (dp), stretch, angle. */
    val data = FloatArray(MAX_POINTS * STRIDE)
    var count = 0
        private set

    // Shed, in this order, when the frame rate sags (see the renderer).
    var followers = 3
    var plankton = 170
    var quality = 1

    /**
     * [cloud] holds [grains] grains of [CLOUD_STRIDE] floats: source position
     * 0..1, playback rate (negative = reverse), amplitude, age 0..1, right-pan
     * gain. [chaosX]/[chaosY] are the current that everything leans on.
     */
    fun build(t: Double, cloud: FloatArray, grains: Int, patch: Patch, aspect: Float, chaosX: Float, chaosY: Float) {
        val n = min(grains, 256)
        val centre = patch.transposeSt / 12f
        val sizeK = sqrt(patch.grainMs / 900f).coerceIn(0.75f, 1.35f)
        // sparse, short-grain textures get more followers per grain so the water never looks empty
        val fol = if (quality > 1) followers else (360f / max(1, n)).roundToInt().coerceIn(followers, 8)
        val bodies = n * (1 + fol)
        val dim = min(1f, sqrt(260f / max(1, bodies)))
        val ax = min(1f, 1.25f / aspect)
        val d = data
        var o = 0
        var i = 0
        while (i < n && o < (MAX_POINTS - plankton - 9) * STRIDE) {
            val rate = cloud[i * CLOUD_STRIDE + 1]
            val amp = min(1f, cloud[i * CLOUD_STRIDE + 2])
            val age = cloud[i * CLOUD_STRIDE + 3]
            val panR = cloud[i * CLOUD_STRIDE + 4]
            val id = panR * 91.7 + rate * 13.37
            val h1 = hash(id * 12.9898); val h2 = hash(id * 78.233 + 1.7); val h3 = hash(id * 37.719 + 4.1)
            val pan = asin(panR.coerceIn(0f, 1f)) * 0.6366198f
            val pit = log2(if (rate == 0f) 1f else abs(rate)) - centre
            val dir = if (rate < 0f) -1f else 1f
            val z = h3; val par = 0.4f + z
            var x = (pan - 0.5f) * 1.3f + (h1 - 0.5f) * 0.36f + dir * (age - 0.5f) * (0.10f + 0.18f * h2) +
                sin(t * (0.21 + 0.3 * h1) + h2 * 6.28).toFloat() * 0.014f + chaosX * 0.11f * par
            val y = 0.13f + pit * 0.3f + (h2 - 0.5f) * 0.62f +
                sin(t * (0.17 + 0.25 * h2) + h1 * 6.28).toFloat() * 0.022f + chaosY * 0.08f * par
            x *= ax
            val tc = (0.5f + pit * 0.5f + (pan - 0.5f) * 0.7f).coerceIn(0f, 1f)       // amber -> silver -> cyan
            val r: Float; val g: Float; val b: Float
            if (tc < 0.5f) { val f = tc * 2f; r = 0.93f + (0.80f - 0.93f) * f; g = 0.64f + (0.86f - 0.64f) * f; b = 0.30f + (0.90f - 0.30f) * f }
            else { val f = (tc - 0.5f) * 2f; r = 0.80f + (0.34f - 0.80f) * f; g = 0.86f + (0.82f - 0.86f) * f; b = 0.90f + (0.88f - 0.90f) * f }
            val fry = h1 > 0.42f
            val stretch = if (fry) 0.3f + 0.4f * h2 else 0f
            val ang = (if (dir < 0f) PI else 0f) + sin(t * (0.4 + h1) + h2 * 9.0).toFloat() * 0.22f
            val size = (5f + 17f * z * z) * sizeK * (0.75f + 0.25f * amp) * (if (fry) 1.5f else 1f)
            val a = amp.pow(0.8f) * (0.28f + 0.72f * z) * dim
            d[o] = x; d[o + 1] = y; d[o + 2] = r; d[o + 3] = g; d[o + 4] = b; d[o + 5] = a; d[o + 6] = size; d[o + 7] = stretch; d[o + 8] = ang
            o += STRIDE
            for (f in 1..fol) {
                val q1 = hash(id * 3.3 + f * 17.1); val q2 = hash(id * 9.1 + f * 5.7)
                d[o] = x - dir * (0.014f + 0.07f * q1) * (1f + f * 0.12f) * ax * (if (f and 1 == 1) 1f else -0.6f) +
                    sin(t * (0.3 + q2) + q1 * 6.28).toFloat() * 0.008f
                d[o + 1] = y + (q2 - 0.5f) * (0.15f + f * 0.012f) + sin(t * (0.26 + q1) + q2 * 6.28).toFloat() * 0.012f
                d[o + 2] = r * 0.9f + 0.08f; d[o + 3] = g * 0.9f + 0.08f; d[o + 4] = b * 0.9f + 0.09f; d[o + 5] = a * 0.62f
                d[o + 6] = size * (0.42f + 0.3f * q1); d[o + 7] = stretch; d[o + 8] = ang + (q2 - 0.5f) * 0.5f
                o += STRIDE
            }
            i++
        }
        // plankton: faint dust for depth, drifting on the same chaos current
        for (k in 0 until plankton) {
            val h1 = hash(k * 1.91 + 0.3); val h2 = hash(k * 2.77 + 1.1); val h3 = hash(k * 4.13 + 2.9)
            d[o] = (fr(h1 + t * 0.0035 * (h3 - 0.35) + chaosX * 0.02 * (0.3 + h3)) * 2f - 1f) * 1.05f
            d[o + 1] = (fr(h2 + t * 0.0022 * (h1 - 0.2) + chaosY * 0.015 * (0.3 + h3)) * 2f - 1f) * 1.05f
            d[o + 2] = 0.62f; d[o + 3] = 0.80f; d[o + 4] = 0.84f
            d[o + 5] = (0.05f + 0.16f * h3) * (0.6f + 0.4f * sin(t * (0.3 + h1) + h2 * 20.0).toFloat())
            d[o + 6] = 1.6f + 3.2f * h3 * h3; d[o + 7] = 0f; d[o + 8] = 0f
            o += STRIDE
        }
        count = o / STRIDE
    }

    /**
     * Before audio starts, or while it is stopped, the scene shows a quiet
     * "ghost" of the current settings. Fills [out] in the cloud layout [build]
     * reads and returns the grain count.
     */
    fun ghost(t: Double, patch: Patch, out: FloatArray): Int {
        val dur = max(0.005, patch.grainMs / 1000.0)
        val n = min(200, (patch.density * dur).roundToInt())
        for (k in 0 until n) {
            val u = t / dur + hash(k * 1.37 + 0.5)
            val c = floor(u); val age = (u - c).toFloat(); val id = k * 7.13 + c * 3.71
            val pan = 0.5f + (hash(id) - 0.5f) * patch.spread
            var st = (hash(id + 1.9) + hash(id + 5.3) - 1f) * patch.pitchSpreadSt
            patch.scaleSet?.let { st = nearestInScale(st, it).toFloat() }
            val rate = 2.0.pow((patch.transposeSt + st) / 12.0).toFloat() *
                (if (hash(id + 8.8) < patch.reverse) -1f else 1f) * (1f + (hash(id + 2.2) - 0.5f) * 0.004f)
            val wn = sin(Math.PI * age).toFloat()
            out[k * CLOUD_STRIDE] = fr(patch.position + (hash(id + 3.3) - 0.5f) * 0.2)
            out[k * CLOUD_STRIDE + 1] = rate
            out[k * CLOUD_STRIDE + 2] = wn * wn * 0.55f
            out[k * CLOUD_STRIDE + 3] = age
            out[k * CLOUD_STRIDE + 4] = sin(pan * 1.5707963f)
        }
        return n
    }

    companion object {
        const val MAX_POINTS = 1280
        const val STRIDE = 9
        const val CLOUD_STRIDE = 5
        private const val PI = Math.PI.toFloat()

        private fun fr(x: Double): Float = (x - floor(x)).toFloat()
        /** The web's `hash`: a cheap, stable scatter of any number into 0..1. */
        fun hash(x: Double): Float = fr(sin(x) * 43758.5453)
        private fun log2(x: Float): Float = (ln(x.toDouble()) / ln(2.0)).toFloat()

        private fun nearestInScale(raw: Float, set: IntArray): Int {
            var best = 0; var bestDistance = 1e9f
            for (octave in -3..3) for (note in set) {
                val candidate = octave * 12 + note
                val distance = abs(candidate - raw)
                if (distance < bestDistance) { bestDistance = distance; best = candidate }
            }
            return best
        }

        /** The Observatory's scales as semitone sets; index 0 (free) has none. */
        val SCALE_SETS: List<IntArray?> = listOf(
            null,
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
            intArrayOf(0, 2, 4, 5, 7, 9, 11),
            intArrayOf(0, 2, 3, 5, 7, 8, 10),
            intArrayOf(0, 2, 4, 7, 9),
            intArrayOf(0, 3, 5, 7, 10),
            intArrayOf(0, 7),
        )
    }
}
