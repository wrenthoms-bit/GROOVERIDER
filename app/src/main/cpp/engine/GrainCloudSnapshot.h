#pragma once
#include <cstdint>

#include "VoicePool.h"

namespace grvr {

/// One grain, as the UI needs it (spec 1.2, 5.2): enough to plot a particle
/// without the UI ever touching audio-thread state directly.
struct GrainVisual {
    float sourcePosNorm = 0.0f;   // 0..1 -- X position
    float pitchRatio    = 1.0f;   // signed playback rate -- Y position (centre = unity)
    float amp           = 0.0f;   // current window envelope -- opacity
    float age01         = 0.0f;   // age/life -- radius shrinks as the grain dies
    float pan           = 0.0f;   // panR - panL, roughly -1..1 -- hue
};

/// Published from the audio thread at callback rate, read by the UI at 60 Hz
/// (spec 1.2). Fixed capacity, zero allocation.
struct GrainCloudSnapshot {
    int32_t     count = 0;
    GrainVisual grains[kMaxGrains];
};

} // namespace grvr
