package com.delrogue.grooverider.onboarding

import kotlin.math.min
import kotlin.math.sin

/**
 * A synthetic pad, generated on-device (spec M8's "straight into a demo
 * source" needs *a* source to exist at first run, and this build has no
 * licensed audio asset to bundle). A soft four-note drone with slow vibrato
 * -- not meant to be a great sound on its own, just good raw material for
 * the grain cloud to chew on.
 */
object DemoSourceGenerator {
    fun generate(sampleRate: Int = 48000, seconds: Double = 8.0): FloatArray {
        val n = (sampleRate * seconds).toInt()
        val out = FloatArray(n)
        val tones = listOf(220.0, 277.18, 329.63, 440.0)   // Am-ish
        val fadeSeconds = 0.5

        for (i in 0 until n) {
            val t = i / sampleRate.toDouble()
            var s = 0.0
            for ((idx, f) in tones.withIndex()) {
                val vibrato = 1.0 + 0.004 * sin(2.0 * Math.PI * (0.12 + idx * 0.04) * t)
                s += sin(2.0 * Math.PI * f * vibrato * t) * (0.22 / tones.size)
            }
            val fadeIn = min(1.0, t / fadeSeconds)
            val fadeOut = min(1.0, (seconds - t) / fadeSeconds)
            out[i] = (s * fadeIn * fadeOut).toFloat().coerceIn(-1f, 1f)
        }
        return out
    }
}
