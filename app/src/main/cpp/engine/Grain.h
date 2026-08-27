#pragma once
#include <cstdint>

namespace grvr {

/// One grain (spec 2.2). Fixed-size, POD-ish, zero allocation after spawn.
struct Grain {
    double   srcPos      = 0.0;   // fractional read position at birth, in source samples
    double   rate        = 1.0;   // playback ratio; negative = reverse
    uint32_t age         = 0;     // samples elapsed since birth
    uint32_t life        = 0;     // total duration, samples
    float    amp         = 1.0f;  // linear gain (window envelope applied separately)
    float    panL        = 0.70710678f;
    float    panR        = 0.70710678f;
    uint16_t windowId    = 0;
    float    chanOffset  = 0.0f;  // Haas: right-ear read offset, in source samples
    bool     active      = false;

    // Per-grain anti-alias one-pole lowpass state (spec 2.6), used only when
    // |rate| > 1.2. aaCoeff == 0 means "bypass".
    float aaCoeff  = 0.0f;
    float aaStateL = 0.0f;
    float aaStateR = 0.0f;
};

} // namespace grvr
