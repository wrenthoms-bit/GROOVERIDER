#pragma once
#include <cstdint>

namespace grvr {

/// Keep in exact sync with ParamId.kt. The wire format is just this integer,
/// so a mismatch is silent and maddening -- both sides carry the same comment.
enum ParamId : uint16_t {
    kToneEnabled = 0,   // 0 or 1
    kToneHz      = 1,   // 20 .. 20000
    kToneGain    = 2,   // linear 0 .. 1
    kMasterGain  = 3,   // linear 0 .. 1

    // --- Grain engine (spec 2.3, M2) ---
    kGrainDensity       = 4,    // 0.5 .. 200 grains/s
    kGrainTimingJitter  = 5,    // 0 .. 1
    kGrainSizeMs        = 6,    // 5 .. 2000 ms
    kGrainSizeJitter    = 7,    // 0 .. 1
    kGrainPosition      = 8,    // 0 .. 1 normalised
    kGrainSprayMs       = 9,    // 0 .. 5000 ms
    kGrainDrift         = 10,   // -2 .. +2
    kGrainPitchSt       = 11,   // -24 .. +24 semitones
    kGrainPitchSpraySt  = 12,   // 0 .. 24 semitones
    kGrainReverseProb   = 13,   // 0 .. 1
    kGrainSpread        = 14,   // 0 .. 1
    kGrainWindowType    = 15,   // 0=Gaussian 1=Tukey 2=Hann (discrete)

    // --- Output stage (spec 2.9) ---
    kOutputWidth = 16,  // 0 .. 2 (mid/side)
    kOutputGain  = 17,  // linear

    // --- Modulation & chaos (spec 4, M4) ---
    kChaosRate = 18,    // 0 .. 1, log-mapped to Lorenz dt

    kParamCount  = 19
};

} // namespace grvr
