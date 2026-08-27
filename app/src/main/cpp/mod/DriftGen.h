#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>

#include "../rand/SeedRng.h"

namespace grvr {

/// Band-limited random walk (spec 4.6): pick a random target, cubic-ease
/// toward it, pick the next. Organic and slow -- not a filtered noise source.
/// Deterministic via its own hashed stream, so drift is part of the
/// reproducible universe (spec 3.1).
class DriftGen {
public:
    void seed(uint64_t masterSeed, uint32_t streamId) noexcept {
        masterSeed_ = masterSeed;
        streamId_   = streamId;
        step_ = 0;
        prev_ = SeedRng::bipolar(masterSeed_, streamId_, step_);
        target_ = SeedRng::bipolar(masterSeed_, streamId_, step_ + 1);
        phase_ = 0.0f;
    }

    /// `rateHz` spans 0.005-2 Hz (spec 4.6). Call once per control tick.
    void step(float rateHz, float controlRateHz) noexcept {
        if (controlRateHz <= 0.0f) return;
        phase_ += rateHz / controlRateHz;
        while (phase_ >= 1.0f) {
            phase_ -= 1.0f;
            prev_ = target_;
            ++step_;
            target_ = SeedRng::bipolar(masterSeed_, streamId_, step_ + 1);
        }
    }

    /// [-1, 1]
    float value() const noexcept {
        const float t = smoothstep(std::clamp(phase_, 0.0f, 1.0f));
        return prev_ + (target_ - prev_) * t;
    }

private:
    static float smoothstep(float t) noexcept { return t * t * (3.0f - 2.0f * t); }

    uint64_t masterSeed_ = 1;
    uint32_t streamId_   = kStreamDriftA;
    uint64_t step_       = 0;
    float    prev_       = 0.0f;
    float    target_     = 0.0f;
    float    phase_      = 0.0f;
};

} // namespace grvr
