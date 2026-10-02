package com.delrogue.grooverider.engine

/**
 * The web app's control curves (docs/index.html), so an Observatory patch
 * means the same thing on the phone as in the browser.
 */
object ObservatoryMacros {

    data class Texture(val grainMs: Float, val density: Float, val timingJitter: Float)

    // TEXTURE, 0 .. 1: long and sparse -> short and dense. Rows are (grain ms, grains/s, timing jitter).
    private val texturePoints = listOf(
        Texture(2000f, 60f, 0.50f),
        Texture(1100f, 30f, 0.25f),
        Texture(450f, 40f, 0.18f),
        Texture(140f, 80f, 0.35f),
        Texture(45f, 200f, 0.60f),
    )

    fun texture(v01: Float): Texture {
        val v = v01.coerceIn(0f, 1f)
        val seg = minOf(3, (v * 4f).toInt())
        val f = v * 4f - seg
        val a = texturePoints[seg]
        val b = texturePoints[seg + 1]
        return Texture(
            grainMs = Math.round(a.grainMs + (b.grainMs - a.grainMs) * f).toFloat(),
            density = Math.round(a.density + (b.density - a.density) * f).toFloat(),
            timingJitter = a.timingJitter + (b.timingJitter - a.timingJitter) * f,
        )
    }

    data class Grains(val grainMs: Float, val density: Float, val timingJitter: Float, val sizeJitter: Float, val scan: Float)

    /**
     * What the engine is actually given. Drone pushes grains long and overlap
     * high, stops the scan (the playhead is latched) and loosens the timing so
     * the cloud never pulses.
     */
    fun effective(grainMs: Float, density: Float, timingJitter: Float, scan: Float, drone: Boolean): Grains {
        if (!drone) return Grains(grainMs, density, timingJitter, sizeJitter = 0.25f, scan = scan)
        val ms = maxOf(grainMs, 1500f)
        val seconds = ms / 1000f
        return Grains(
            grainMs = ms,
            density = density.coerceIn(70f / seconds, 190f / seconds),
            timingJitter = maxOf(timingJitter, 0.5f),
            sizeJitter = 0.35f,
            scan = 0f,
        )
    }

    /** The web links stereo spread to its width control. */
    fun spreadForWidth(width: Float): Float = minOf(1f, width * 0.62f)
}
