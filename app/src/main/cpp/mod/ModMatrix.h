#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>

namespace grvr {

enum ModSource : uint8_t {
    kModLorenzX = 0, kModLorenzY = 1, kModLorenzZ = 2,
    kModDriftA  = 3, kModDriftB  = 4, kModDriftC  = 5,
    kModLfo1    = 6, kModLfo2    = 7,
    kModMacro1  = 8, kModMacro2  = 9, kModMacro3  = 10, kModMacro4 = 11,
    kModSourceCount = 12,
};

/// Any scheduler parameter, plus output width and chaosRate itself --
/// deliberately, so chaos can modulate its own speed (spec 4.4).
enum ModDest : uint8_t {
    kDestPosition      = 0,
    kDestPitchSpray    = 1,
    kDestSpread        = 2,
    kDestGrainSize     = 3,
    kDestDensity       = 4,
    kDestTimingJitter  = 5,
    kDestSizeJitter    = 6,
    kDestPitch         = 7,
    kDestReverseProb   = 8,
    kDestDrift         = 9,
    kDestSprayMs       = 10,
    kDestOutputWidth   = 11,
    kDestChaosRate     = 12,
    kDestCount         = 13,
};

enum ModCurve : uint8_t { kCurveLinear = 0, kCurveExp = 1, kCurveSCurve = 2, kCurveQuantised = 3 };

/// One of 16 fixed slots (spec 4.4). `depth` is bipolar; `active == false` or
/// `depth == 0` contributes exactly 0.0f, so a matrix with everything at
/// zero depth is bit-identical to no routing at all.
struct ModRoute {
    uint8_t source = 0;
    uint8_t dest   = 0;
    float   depth  = 0.0f;
    uint8_t curve  = kCurveLinear;
    bool    active = false;
};

constexpr int kModMatrixSlots = 16;

/// Shapes a [-1,1] source value before it is scaled by depth. Odd-symmetric
/// so a bipolar source stays bipolar after shaping.
inline float applyModCurve(float x, uint8_t curve) noexcept {
    const float c = std::clamp(x, -1.0f, 1.0f);
    switch (curve) {
        case kCurveExp: {
            const float a = std::fabs(c);
            const float shaped = a * a;
            return c < 0.0f ? -shaped : shaped;
        }
        case kCurveSCurve:
            // Odd-symmetric S-curve: 0 at 0, +-1 at +-1, gentle near the
            // centre where most modulation sits, steeper near the extremes.
            return 0.5f * (3.0f * c - c * c * c);
        case kCurveQuantised: {
            constexpr float kSteps = 5.0f;   // 5 discrete levels across [-1,1]
            return std::round(c * kSteps) / kSteps;
        }
        case kCurveLinear:
        default:
            return c;
    }
}

} // namespace grvr
