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

    // --- Observatory (core/Observatory.h) ---
    kChaos       = 19,  // 0 .. 1, Lorenz rate and depth together
    kPitchAmount = 20,  // 0 .. 1, scatter range (or spray when the scale is free)
    kKey         = 21,  // 0 .. 11, C .. B
    kScale       = 22,  // 0=free 1=chromatic 2=major 3=minor 4=pent-major 5=pent-minor 6=octaves+fifths
    kRegister    = 23,  // -24 .. +24 semitones
    kDetune      = 24,  // 0 .. 1 semitones
    kDrone       = 25,  // 0 or 1
    kSpace       = 26,  // 0 .. 1, room .. ocean
    kShimmer     = 27,  // 0 .. 1
    kTone        = 28,  // 0 .. 1, 500 Hz .. 18 kHz
    kScan        = 29,  // -1 .. +1
    kObservatory = 30,  // 0 or 1: off = the core alone, as Seeds saved before the Observatory expect

    kParamCount  = 31
};

} // namespace grvr
