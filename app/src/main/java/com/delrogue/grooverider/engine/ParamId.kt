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
}
