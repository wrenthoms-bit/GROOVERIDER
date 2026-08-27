package com.delrogue.grooverider.source

import kotlin.math.abs
import kotlin.math.max

/** Peak-envelope generation for the waveform display (spec M1). */
object Waveform {

    /**
     * Reduce interleaved PCM to [buckets] peak values in [0,1]: each bucket is
     * the maximum absolute sample (across all channels) in that slice.
     */
    fun peaks(samples: FloatArray, channels: Int, buckets: Int): FloatArray {
        if (channels <= 0 || samples.isEmpty() || buckets <= 0) return FloatArray(buckets.coerceAtLeast(0))
        val frames = samples.size / channels
        val out = FloatArray(buckets)
        if (frames == 0) return out
        for (b in 0 until buckets) {
            val start = (b.toLong() * frames / buckets).toInt()
            val end = ((b + 1).toLong() * frames / buckets).toInt().coerceAtMost(frames)
            var pk = 0f
            var i = start
            while (i < end) {
                val base = i * channels
                var c = 0
                while (c < channels) { pk = max(pk, abs(samples[base + c])); c++ }
                i++
            }
            out[b] = pk
        }
        return out
    }

    /** Downsample a stored high-res peak array to a smaller display width. */
    fun downsample(peaks: FloatArray, target: Int): FloatArray {
        if (target <= 0 || peaks.isEmpty()) return FloatArray(target.coerceAtLeast(0))
        if (peaks.size <= target) return peaks
        val out = FloatArray(target)
        for (b in 0 until target) {
            val start = b * peaks.size / target
            val end = ((b + 1) * peaks.size / target).coerceAtMost(peaks.size)
            var pk = 0f
            for (i in start until end) pk = max(pk, peaks[i])
            out[b] = pk
        }
        return out
    }
}
