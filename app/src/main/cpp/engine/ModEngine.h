#pragma once
#include <cstdint>

#include "../mod/DriftGen.h"
#include "Lorenz.h"   // core/ -- the one chaos integrator, shared with the Observatory

#include "../mod/Lfo.h"
#include "../mod/ModMatrix.h"
#include "GrainEngine.h"

namespace grvr {

/// Owns every modulation source (spec 4.2) and the 16-slot matrix that routes
/// them to grain params. Ticked at a 1 kHz control rate by Engine; the
/// combined (base + mod) value for each destination is what actually reaches
/// GrainEngine, so a route at depth 0 is bit-identical to no route at all.
class ModEngine {
public:
    void configure(uint64_t masterSeed) noexcept;
    void setMasterSeed(uint64_t seed) noexcept;

    void setBase(uint8_t dest, float value) noexcept {
        if (dest < kDestCount) base_[dest] = value;
    }
    float base(uint8_t dest) const noexcept { return dest < kDestCount ? base_[dest] : 0.0f; }

    void setRoute(int slot, const ModRoute& r) noexcept {
        if (slot >= 0 && slot < kModMatrixSlots) routes_[slot] = r;
    }
    const ModRoute& route(int slot) const noexcept { return routes_[slot]; }

    void setMacro(int idx, float v01) noexcept { if (idx >= 0 && idx < 4) macros_[idx] = v01 * 2.0f - 1.0f; }
    void setLfoRate(int idx, float hz) noexcept { (idx == 0 ? lfo1_ : lfo2_).setRateHz(hz); }

    /// The pad-first default patch (spec 4.5) -- must sound good on any
    /// dropped-in audio within two seconds, unassisted.
    void loadFirstLight() noexcept;
    void clearRoutes() noexcept { for (auto& r : routes_) r = ModRoute{}; }

    /// While muted, every destination gets its base value and nothing else.
    /// The Observatory brings its own chaos, so the matrix stands down while
    /// it is on; the routes themselves are kept for when it goes off again.
    void setRoutesMuted(bool muted) noexcept { routesMuted_ = muted; }

    /// One control tick (call at 1 kHz realtime / 4 kHz offline). Advances
    /// every source and pushes the combined value into `grains` for every
    /// destination.
    void tick(GrainEngine& grains) noexcept;

    /// `chaosRate` in [0,1] maps the Lorenz step logarithmically over
    /// 0.00002-0.005 time-units per control tick (spec 4.3) -- at the slow
    /// end a single orbit takes minutes, which is the timescale a pad wants.
    static float lorenzDt(float chaosRate01) noexcept;

    /// The Lorenz outputs the matrix routes from, each -1 .. 1 (0 = x, 1 = y, 2 = z).
    float lorenzOut(int axis) const noexcept {
        return sourceValue(static_cast<uint8_t>(axis == 0 ? kModLorenzX : (axis == 1 ? kModLorenzY : kModLorenzZ)));
    }

    // Introspection for the 60-minute Lorenz stability test.
    float lorenzRawX() const noexcept { return lorenz_.x; }
    float lorenzRawY() const noexcept { return lorenz_.y; }
    float lorenzRawZ() const noexcept { return lorenz_.z; }

private:
    float sourceValue(uint8_t source) const noexcept;
    void  applyDestination(uint8_t dest, float value, GrainEngine& grains) noexcept;

    grv::Lorenz lorenz_;
    float    lorenzX0_ = 0.1f, lorenzY0_ = 0.1f, lorenzZ0_ = 0.1f;   // seeded start, and where an escape resets to
    DriftGen driftA_, driftB_, driftC_;
    Lfo      lfo1_, lfo2_;
    ModRoute routes_[kModMatrixSlots];
    float    macros_[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    float    base_[kDestCount] = {};
    bool     routesMuted_ = false;

    static constexpr float kControlRateHz = 1000.0f;
};

} // namespace grvr
