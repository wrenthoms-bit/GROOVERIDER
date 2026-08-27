#pragma once
#include <cstdint>
#include "Grain.h"

namespace grvr {

constexpr int32_t kMaxGrains = 256;

/// Fixed-capacity grain array with swap-remove on death (spec 2.2).
/// Zero allocation after construction.
class VoicePool {
public:
    Grain   grains[kMaxGrains];
    int32_t activeCount = 0;

    Grain* spawn() noexcept {
        if (activeCount >= kMaxGrains) return nullptr;
        Grain* g = &grains[activeCount++];
        *g = Grain{};
        g->active = true;
        return g;
    }

    /// O(1) removal: move the last active grain into the freed slot.
    void kill(int32_t index) noexcept {
        if (index < 0 || index >= activeCount) return;
        grains[index] = grains[--activeCount];
    }

    void clear() noexcept { activeCount = 0; }
};

} // namespace grvr
