package com.delrogue.grooverider.seed

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/** The golden-ratio fixed-point constant used throughout the spec's hashing (3.2, 3.6). */
const val PHI64 = -0x61c8864680b583ebL   // 0x9E3779B97F4A7C15

/** Kotlin-side SplitMix64 (spec 3.2) for everything that doesn't need to
 * touch the audio signal path: seed naming, mutation, lineage. */
object Hash64 {
    fun splitmix64(seed: Long): Long {
        var x = seed + PHI64
        x = (x xor (x ushr 30)) * -0x40a7b892e31b1a47L   // 0xBF58476D1CE4E5B9
        x = (x xor (x ushr 27)) * -0x6b2fb644ecceee15L   // 0x94D049BB133111EB
        return x xor (x ushr 31)
    }

    /** [0, 1) */
    fun uniform01(seed: Long, stream: Int): Float {
        val h = splitmix64(splitmix64(seed) xor (stream.toLong() shl 32))
        return ((h ushr 40) and 0xFFFFFF).toFloat() * (1.0f / (1 shl 24))
    }

    /** Standard normal via Box-Muller, deterministic per (seed, stream) pair. */
    fun gaussian(seed: Long, stream: Int): Float {
        val u1 = uniform01(seed, stream * 2).coerceAtLeast(1e-7f)
        val u2 = uniform01(seed, stream * 2 + 1)
        return (sqrt(-2.0 * ln(u1.toDouble())) * cos(2.0 * Math.PI * u2)).toFloat()
    }
}
