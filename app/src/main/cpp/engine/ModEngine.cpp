#include "ModEngine.h"

#include <algorithm>

namespace grvr {

namespace {
// Bipolar mod value -> additive offset scaled to the destination's musical
// range, so a depth of 1.0 reads as "the full useful range of the knob"
// rather than a meaningless raw +-1 addition on e.g. density.
float destRange(uint8_t dest) noexcept {
    switch (dest) {
        case kDestPosition:     return 0.5f;
        case kDestPitchSpray:   return 12.0f;
        case kDestSpread:       return 0.5f;
        case kDestGrainSize:    return 400.0f;
        case kDestDensity:      return 60.0f;
        case kDestTimingJitter: return 0.5f;
        case kDestSizeJitter:   return 0.5f;
        case kDestPitch:        return 12.0f;
        case kDestReverseProb:  return 0.5f;
        case kDestDrift:        return 1.0f;
        case kDestSprayMs:      return 250.0f;
        case kDestOutputWidth:  return 0.5f;
        case kDestChaosRate:    return 0.5f;
        default:                return 0.0f;
    }
}
} // namespace

void ModEngine::configure(uint64_t masterSeed) noexcept {
    setMasterSeed(masterSeed);
    lfo1_.reset();
    lfo2_.reset();

    // Pad defaults (spec 2.3) -- the base values mod routes ride on top of.
    base_[kDestPosition]     = 0.5f;
    base_[kDestPitchSpray]   = 0.15f;
    base_[kDestSpread]       = 0.8f;
    base_[kDestGrainSize]    = 400.0f;
    base_[kDestDensity]      = 40.0f;
    base_[kDestTimingJitter] = 0.15f;
    base_[kDestSizeJitter]   = 0.25f;
    base_[kDestPitch]        = 0.0f;
    base_[kDestReverseProb]  = 0.2f;
    base_[kDestDrift]        = 0.05f;
    base_[kDestSprayMs]      = 250.0f;
    base_[kDestOutputWidth]  = 1.0f;
    base_[kDestChaosRate]    = 0.3f;

    loadFirstLight();
}

void ModEngine::setMasterSeed(uint64_t seed) noexcept {
    driftA_.seed(seed, kStreamDriftA);
    driftB_.seed(seed, kStreamDriftB);
    driftC_.seed(seed, kStreamDriftC);
    lorenz_.seed(seed);
}

void ModEngine::loadFirstLight() noexcept {
    for (auto& r : routes_) r = ModRoute{};
    routes_[0] = ModRoute{kModLorenzX, kDestPosition,   0.18f, kCurveSCurve, true};
    routes_[1] = ModRoute{kModLorenzY, kDestPitchSpray, 0.35f, kCurveExp,    true};
    routes_[2] = ModRoute{kModLorenzZ, kDestSpread,     0.25f, kCurveLinear, true};
    routes_[3] = ModRoute{kModDriftA,  kDestGrainSize,  0.20f, kCurveLinear, true};
    routes_[4] = ModRoute{kModDriftB,  kDestDensity,    0.15f, kCurveLinear, true};
    // Slots 5-6 (Macro TEXTURE / DRIFT curated curves) land with the macro
    // system in M5 -- they need the XY pad to mean anything.
}

float ModEngine::sourceValue(uint8_t source) const noexcept {
    switch (source) {
        case kModLorenzX: return lorenz_.outX();
        case kModLorenzY: return lorenz_.outY();
        case kModLorenzZ: return lorenz_.outZ();
        case kModDriftA:  return driftA_.value();
        case kModDriftB:  return driftB_.value();
        case kModDriftC:  return driftC_.value();
        case kModLfo1:    return lfo1_.value();
        case kModLfo2:    return lfo2_.value();
        case kModMacro1:  return macros_[0];
        case kModMacro2:  return macros_[1];
        case kModMacro3:  return macros_[2];
        case kModMacro4:  return macros_[3];
        default:          return 0.0f;
    }
}

void ModEngine::applyDestination(uint8_t dest, float value, GrainScheduler& grains) noexcept {
    switch (dest) {
        case kDestPosition:     grains.setPosition(std::clamp(value, 0.0f, 1.0f)); break;
        case kDestPitchSpray:   grains.setPitchSpraySemitones(std::clamp(value, 0.0f, 24.0f)); break;
        case kDestSpread:       grains.setSpread(std::clamp(value, 0.0f, 1.0f)); break;
        case kDestGrainSize:    grains.setGrainSizeMs(std::clamp(value, 5.0f, 2000.0f)); break;
        case kDestDensity:      grains.setDensity(std::clamp(value, 0.5f, 200.0f)); break;
        case kDestTimingJitter: grains.setTimingJitter(std::clamp(value, 0.0f, 1.0f)); break;
        case kDestSizeJitter:   grains.setSizeJitter(std::clamp(value, 0.0f, 1.0f)); break;
        case kDestPitch:        grains.setPitchSemitones(std::clamp(value, -24.0f, 24.0f)); break;
        case kDestReverseProb:  grains.setReverseProb(std::clamp(value, 0.0f, 1.0f)); break;
        case kDestDrift:        grains.setDrift(std::clamp(value, -2.0f, 2.0f)); break;
        case kDestSprayMs:      grains.setSprayMs(std::clamp(value, 0.0f, 5000.0f)); break;
        case kDestOutputWidth:  combinedOutputWidth_ = std::clamp(value, 0.0f, 2.0f); break;
        case kDestChaosRate:    break;   // consumed directly in tick(), not a scheduler param
        default: break;
    }
}

void ModEngine::tick(GrainScheduler& grains) noexcept {
    float sums[kDestCount] = {};
    for (const auto& r : routes_) {
        if (!r.active || r.depth == 0.0f) continue;
        const float shaped = applyModCurve(sourceValue(r.source), r.curve);
        sums[r.dest] += shaped * r.depth * destRange(r.dest);
    }

    // Chaos can modulate its own speed (spec 4.4) -- resolve before stepping.
    const float chaosRate = std::clamp(base_[kDestChaosRate] + sums[kDestChaosRate], 0.0f, 1.0f);
    lorenz_.step(chaosRate);
    driftA_.step(0.05f, kControlRateHz);
    driftB_.step(0.07f, kControlRateHz);
    driftC_.step(0.11f, kControlRateHz);
    lfo1_.step(kControlRateHz);
    lfo2_.step(kControlRateHz);

    for (uint8_t d = 0; d < kDestCount; ++d) {
        if (d == kDestChaosRate) continue;
        applyDestination(d, base_[d] + sums[d], grains);
    }
}

} // namespace grvr
