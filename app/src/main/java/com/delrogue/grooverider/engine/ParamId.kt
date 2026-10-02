package com.delrogue.grooverider.engine

/**
 * Keep in exact sync with cpp/engine/ParamId.h.
 * The wire format is just this integer, so a mismatch is silent and maddening.
 */
object ParamId {
    const val TONE_ENABLED = 0   // 0 or 1
    const val TONE_HZ      = 1   // 20 .. 20000
    const val TONE_GAIN    = 2   // linear 0 .. 1
    const val MASTER_GAIN  = 3   // linear 0 .. 1

    // --- Grain engine (spec 2.3, M2) ---
    const val GRAIN_DENSITY        = 4    // 0.5 .. 200 grains/s
    const val GRAIN_TIMING_JITTER  = 5    // 0 .. 1
    const val GRAIN_SIZE_MS        = 6    // 5 .. 2000 ms
    const val GRAIN_SIZE_JITTER    = 7    // 0 .. 1
    const val GRAIN_POSITION       = 8    // 0 .. 1 normalised
    const val GRAIN_SPRAY_MS       = 9    // 0 .. 5000 ms
    const val GRAIN_DRIFT          = 10   // -2 .. +2
    const val GRAIN_PITCH_ST       = 11   // -24 .. +24 semitones
    const val GRAIN_PITCH_SPRAY_ST = 12   // 0 .. 24 semitones
    const val GRAIN_REVERSE_PROB   = 13   // 0 .. 1
    const val GRAIN_SPREAD         = 14   // 0 .. 1
    const val GRAIN_WINDOW_TYPE    = 15   // 0=Gaussian 1=Tukey 2=Hann

    // --- Output stage (spec 2.9) ---
    const val OUTPUT_WIDTH = 16  // 0 .. 2
    const val OUTPUT_GAIN  = 17  // linear

    // --- Modulation & chaos (spec 4, M4) ---
    const val CHAOS_RATE = 18    // 0 .. 1, log-mapped to Lorenz dt

    // --- Observatory (core/Observatory.h) ---
    const val CHAOS        = 19  // 0 .. 1, Lorenz rate and depth together
    const val PITCH_AMOUNT = 20  // 0 .. 1, scatter range (or spray when the scale is free)
    const val KEY          = 21  // 0 .. 11, C .. B
    const val SCALE        = 22  // 0=free 1=chromatic 2=major 3=minor 4=pent-major 5=pent-minor 6=octaves+fifths
    const val REGISTER     = 23  // -24 .. +24 semitones
    const val DETUNE       = 24  // 0 .. 1 semitones
    const val DRONE        = 25  // 0 or 1
    const val SPACE        = 26  // 0 .. 1, room .. ocean
    const val SHIMMER      = 27  // 0 .. 1
    const val TONE         = 28  // 0 .. 1, 500 Hz .. 18 kHz
    const val SCAN         = 29  // -1 .. +1
    const val OBSERVATORY  = 30  // 0 or 1: off = the core alone, as Seeds saved before the Observatory expect
}
