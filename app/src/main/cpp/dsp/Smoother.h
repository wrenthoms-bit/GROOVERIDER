#pragma once
#include <cmath>

namespace grvr {

/// One-pole parameter slew (spec 2.8). Without this, every knob zippers.
class Smoother {
public:
    void configure(float tauSeconds, float sampleRate) noexcept {
        if (tauSeconds <= 0.0f || sampleRate <= 0.0f) { coeff_ = 1.0f; return; }
        coeff_ = 1.0f - std::exp(-1.0f / (tauSeconds * sampleRate));
    }

    void  snap(float v)      noexcept { current_ = target_ = v; }
    void  setTarget(float v) noexcept { target_ = v; }
    float target()  const noexcept { return target_; }
    float current() const noexcept { return current_; }

    inline float next() noexcept {
        current_ += (target_ - current_) * coeff_;
        return current_;
    }

private:
    float coeff_   {1.0f};
    float current_ {0.0f};
    float target_  {0.0f};
};

} // namespace grvr
