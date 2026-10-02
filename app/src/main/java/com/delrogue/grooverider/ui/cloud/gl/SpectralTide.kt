package com.delrogue.grooverider.ui.cloud.gl

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min

/**
 * The "spectral tide": a waterfall of the output's spectrum, one column per
 * step, 40 Hz at the bottom to 16 kHz at the top on a log scale. Ported from
 * the web app's `drawTide`, including what its AnalyserNode does on the way
 * (smoothing 0.55, -100 .. -30 dB mapped to 0 .. 255).
 *
 * Pure Kotlin: [column] turns a magnitude spectrum into one column of RGBA
 * pixels; the renderer puts it in a texture.
 */
class SpectralTide(val rows: Int = ROWS) {

    private val smoothed = FloatArray(BINS)
    private val rowBin = FloatArray(rows)
    private var mappedRate = 0

    /**
     * [magnitudes] is [BINS] linear magnitudes from GrooveriderEngine.spectrum,
     * or null when there is no audio (the tide then ebbs). Writes rows * 4
     * bytes into [rgba], bottom row first.
     */
    fun column(magnitudes: FloatArray?, sampleRate: Int, rgba: ByteArray) {
        if (sampleRate != mappedRate && sampleRate > 0) {
            val nyquist = sampleRate / 2.0
            val lo = ln(40.0); val hi = ln(16000.0)
            for (y in 0 until rows) rowBin[y] = (exp(lo + (hi - lo) * y / (rows - 1)) / nyquist * BINS).toFloat()
            mappedRate = sampleRate
        }
        for (k in 0 until BINS) smoothed[k] = SMOOTHING * smoothed[k] + (1f - SMOOTHING) * (magnitudes?.get(k) ?: 0f)
        for (y in 0 until rows) {
            val b = rowBin[y]
            val i0 = min(BINS - 2, b.toInt())
            val f = (b - i0).coerceIn(0f, 1f)
            val level = min(255f, (byteLevel(smoothed[i0]) * (1f - f) + byteLevel(smoothed[i0 + 1]) * f) * 1.05f).toInt()
            rgba[y * 4] = LUT[level * 3]; rgba[y * 4 + 1] = LUT[level * 3 + 1]; rgba[y * 4 + 2] = LUT[level * 3 + 2]
            rgba[y * 4 + 3] = 255.toByte()
        }
    }

    companion object {
        const val ROWS = 128
        const val BINS = 1024
        private const val SMOOTHING = 0.55f
        private const val MIN_DB = -100f
        private const val MAX_DB = -30f

        /** A linear magnitude as the 0 .. 255 an AnalyserNode's getByteFrequencyData gives. */
        fun byteLevel(magnitude: Float): Float {
            if (magnitude <= 0f) return 0f
            val db = 20f * log10(magnitude)
            return (255f * (db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 255f)
        }

        // deep water -> teal -> cyan -> amber -> warm white
        private val STOPS = arrayOf(
            floatArrayOf(0f, 3f, 5f, 6f), floatArrayOf(0.3f, 6f, 48f, 58f), floatArrayOf(0.58f, 60f, 190f, 205f),
            floatArrayOf(0.8f, 232f, 168f, 87f), floatArrayOf(1f, 255f, 244f, 224f),
        )

        /** 256 RGB triples. */
        val LUT = ByteArray(256 * 3).also { lut ->
            for (i in 0 until 256) {
                val v = i / 255f
                var k = 0
                while (k < STOPS.size - 2 && v > STOPS[k + 1][0]) k++
                val a = STOPS[k]; val b = STOPS[k + 1]
                val f = ((v - a[0]) / (b[0] - a[0])).coerceIn(0f, 1f)
                for (c in 0 until 3) lut[i * 3 + c] = (a[c + 1] + (b[c + 1] - a[c + 1]) * f).toInt().toByte()
            }
        }
    }
}
