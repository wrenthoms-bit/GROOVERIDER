#pragma once
#include <cmath>

namespace grvr {

/// Tempo-syncable LFO (spec 4.2): free-rate in Hz, or locked to a note
/// division of a host BPM. Phase resets only on transport restart, matching
/// the grain index's own reset rule (spec 3.2) -- the mod universe is just
/// as reproducible as the grain universe.
class Lfo {
public:
    void reset() noexcept { phase_ = 0.0f; }

    void setRateHz(float hz) noexcept { rateHz_ = hz; }
    void setTempoSync(bool on, float noteDivision = 0.25f) noexcept {
        synced_ = on;
        division_ = noteDivision;   // e.g. 1.0 = whole note's worth of beats, 0.25 = quarter
    }
    void setBpm(float bpm) noexcept { bpm_ = bpm; }

    /// Call once per control tick.
    void step(float controlRateHz) noexcept {
        if (controlRateHz <= 0.0f) return;
        const float hz = synced_ ? (bpm_ / 60.0f) * division_ : rateHz_;
        phase_ += hz / controlRateHz;
        phase_ -= std::floor(phase_);
    }

    /// [-1, 1]
    float value() const noexcept { return std::sin(2.0f * static_cast<float>(M_PI) * phase_); }

private:
    float phase_    = 0.0f;
    float rateHz_   = 0.5f;
    float bpm_      = 120.0f;
    float division_ = 0.25f;
    bool  synced_   = false;
};

} // namespace grvr
