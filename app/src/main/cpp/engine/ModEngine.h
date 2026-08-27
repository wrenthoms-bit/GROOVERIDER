#pragma once
#include <cstdint>

#include "../mod/DriftGen.h"
#include "../mod/Lfo.h"
#include "../mod/Lorenz.h"
#include "../mod/ModMatrix.h"
#include "GrainScheduler.h"

namespace grvr {

/// Owns every modulation source (spec 4.2) and the 16-slot matrix that routes
/// them to scheduler params. Ticked at a 1 kHz control rate by Engine; the
/// combined (base + mod) value for each destination is what actually reaches
/// GrainScheduler, so a route at depth 0 is bit-identical to no route at all.
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

    /// One control tick (call at 1 kHz realtime / 4 kHz offline). Advances
    /// every source and pushes the combined value into `grains` for every
    /// destination except output width, which the caller applies itself.
    void tick(GrainScheduler& grains) noexcept;

    float combinedOutputWidth() const noexcept { return combinedOutputWidth_; }

    // Introspection for the 60-minute Lorenz stability test.
    float lorenzRawX() const noexcept { return lorenz_.rawX(); }
    float lorenzRawY() const noexcept { return lorenz_.rawY(); }
    float lorenzRawZ() const noexcept { return lorenz_.rawZ(); }

private:
    float sourceValue(uint8_t source) const noexcept;
    void  applyDestination(uint8_t dest, float value, GrainScheduler& grains) noexcept;

    Lorenz   lorenz_;
    DriftGen driftA_, driftB_, driftC_;
    Lfo      lfo1_, lfo2_;
    ModRoute routes_[kModMatrixSlots];
    float    macros_[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    float    base_[kDestCount] = {};
    float    combinedOutputWidth_ = 1.0f;

    static constexpr float kControlRateHz = 1000.0f;
};

} // namespace grvr
