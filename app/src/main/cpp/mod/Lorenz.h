#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>

#include "../rand/SeedRng.h"

namespace grvr {

/// The primary chaos engine (spec 4.3): three correlated, never-repeating
/// streams from one attractor, integrated with RK4 at a 1 kHz control rate.
/// Euler would drift off the attractor on a system this stiff and can
/// diverge to infinity -- a silent bug that becomes a very loud one.
class Lorenz {
public:
    void seed(uint64_t masterSeed) noexcept {
        x0_ = 0.1f + SeedRng::value(masterSeed, kStreamChaosInit, 0);
        y0_ = 0.1f + SeedRng::value(masterSeed, kStreamChaosInit, 1);
        z0_ = 0.1f + SeedRng::value(masterSeed, kStreamChaosInit, 2);
        reset();
    }

    void reset() noexcept { x_ = x0_; y_ = y0_; z_ = z0_; }

    /// `chaosRate` in [0,1] maps dt logarithmically over 0.00002-0.005
    /// time-units per control tick (spec 4.3) -- slow end, a single orbit
    /// takes minutes, which is exactly the timescale a pad wants.
    void step(float chaosRate01) noexcept {
        const float c = std::clamp(chaosRate01, 0.0f, 1.0f);
        const float dt = kDtMin * std::pow(kDtMax / kDtMin, c);

        const State k1 = derivative({x_, y_, z_});
        const State s2 = {x_ + k1.x * dt * 0.5f, y_ + k1.y * dt * 0.5f, z_ + k1.z * dt * 0.5f};
        const State k2 = derivative(s2);
        const State s3 = {x_ + k2.x * dt * 0.5f, y_ + k2.y * dt * 0.5f, z_ + k2.z * dt * 0.5f};
        const State k3 = derivative(s3);
        const State s4 = {x_ + k3.x * dt, y_ + k3.y * dt, z_ + k3.z * dt};
        const State k4 = derivative(s4);

        x_ += (dt / 6.0f) * (k1.x + 2.0f * k2.x + 2.0f * k3.x + k4.x);
        y_ += (dt / 6.0f) * (k1.y + 2.0f * k2.y + 2.0f * k3.y + k4.y);
        z_ += (dt / 6.0f) * (k1.z + 2.0f * k2.z + 2.0f * k3.z + k4.z);

        // Cheap insurance: a numerical excursion should never become a
        // full-scale DC blast in someone's headphones (spec 4.3).
        if (std::isnan(x_) || std::isnan(y_) || std::isnan(z_) ||
            std::isinf(x_) || std::isinf(y_) || std::isinf(z_) ||
            std::fabs(x_) > 100.0f || std::fabs(y_) > 100.0f || std::fabs(z_) > 100.0f) {
            reset();
        }
    }

    /// Exposed for testing the chaosRate -> dt mapping in isolation.
    static float dtForRate(float chaosRate01) noexcept {
        const float c = std::clamp(chaosRate01, 0.0f, 1.0f);
        return kDtMin * std::pow(kDtMax / kDtMin, c);
    }

    float outX() const noexcept { return std::clamp(std::tanh(x_ / 20.0f), -1.0f, 1.0f); }
    float outY() const noexcept { return std::clamp(std::tanh(y_ / 25.0f), -1.0f, 1.0f); }
    float outZ() const noexcept { return std::clamp(std::tanh((z_ - 25.0f) / 25.0f), -1.0f, 1.0f); }

    // Raw state, exposed for the 60-minute stability test.
    float rawX() const noexcept { return x_; }
    float rawY() const noexcept { return y_; }
    float rawZ() const noexcept { return z_; }

private:
    struct State { float x, y, z; };

    State derivative(const State& s) const noexcept {
        return { kSigma * (s.y - s.x), s.x * (kRho - s.z) - s.y, s.x * s.y - kBeta * s.z };
    }

    static constexpr float kSigma = 10.0f;
    static constexpr float kRho   = 28.0f;
    static constexpr float kBeta  = 8.0f / 3.0f;
    static constexpr float kDtMin = 0.00002f;
    static constexpr float kDtMax = 0.005f;

    float x0_ = 0.1f, y0_ = 0.1f, z0_ = 0.1f;
    float x_ = 0.1f, y_ = 0.1f, z_ = 0.1f;
};

} // namespace grvr
