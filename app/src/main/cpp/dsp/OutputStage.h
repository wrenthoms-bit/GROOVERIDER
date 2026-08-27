#pragma once
#include <cmath>

namespace grvr {

/// Minimal, fixed output stage (spec 2.9). Not an effects rack -- DC blocker,
/// width, gentle soft saturation, gain. That's it.
class OutputStage {
public:
    void configure(float sampleRate) noexcept {
        r_ = 1.0f - (2.0f * static_cast<float>(M_PI) * kHighpassHz / sampleRate);
    }

    /// Processes one stereo frame in place.
    inline void process(float& l, float& r, float width, float gain) noexcept {
        // 1. DC blocker -- one-pole highpass at 12 Hz. Non-negotiable: reverse
        // grains and asymmetric windows accumulate DC, and DC eats headroom
        // in Logic invisibly.
        const float dcL = l - prevInL_ + r_ * dcStateL_;
        const float dcR = r - prevInR_ + r_ * dcStateR_;
        prevInL_ = l; prevInR_ = r;
        dcStateL_ = dcL; dcStateR_ = dcR;

        // 2. Width -- mid/side gain, 0-200%.
        const float mid  = (dcL + dcR) * 0.5f;
        const float side = (dcL - dcR) * 0.5f;
        const float wL = mid + side * width;
        const float wR = mid - side * width;

        // 3. Gain + gentle soft saturation at -3 dBFS. Catches stochastic
        // peaks without a limiter's pumping.
        l = std::tanh(wL * gain * kInvSatScale) * kSatScale;
        r = std::tanh(wR * gain * kInvSatScale) * kSatScale;
    }

private:
    static constexpr float kHighpassHz  = 12.0f;
    static constexpr float kSatScale    = 0.70794578f;   // -3 dBFS
    static constexpr float kInvSatScale = 1.0f / kSatScale;

    float r_ = 0.995f;
    float dcStateL_ = 0.0f, dcStateR_ = 0.0f;
    float prevInL_  = 0.0f, prevInR_  = 0.0f;
};

} // namespace grvr
