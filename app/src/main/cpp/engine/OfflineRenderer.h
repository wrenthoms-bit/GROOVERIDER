#pragma once
#include <cstdint>
#include <vector>

#include "../io/SourceBuffer.h"

namespace grvr {

/// Everything needed to reproduce a texture exactly (spec 3.1, 6.2-6.3):
/// the Seed's whole random universe plus the base param values that were
/// live at capture time.
struct RenderRequest {
    uint64_t masterSeed = 1;

    float density = 40.0f, timingJitter = 0.15f, grainSizeMs = 400.0f, sizeJitter = 0.25f;
    float position = 0.5f, sprayMs = 250.0f, drift = 0.05f;
    float pitchSt = 0.0f, pitchSpraySt = 0.15f, reverseProb = 0.2f, spread = 0.8f;
    uint16_t windowType = 0;

    float outputWidth = 1.0f, outputGain = 0.9f;

    bool  chaosEnabled = true;
    float chaosRate = 0.3f;

    double durationSeconds = 60.0;

    // Seamless loop rendering (spec 6.4): render `durationSeconds` plus a
    // tail overhang, then crossfade the tail back over the head.
    bool   seamlessLoop = false;
    double crossfadeSeconds = 0.05;
};

/// A second engine instance, same C++ code, on a virtual clock -- no Oboe
/// stream, no realtime deadline (spec 6.3). Runs at 2x oversample with a
/// proper decimation filter on the way back down, and ticks the mod matrix
/// at 4 kHz instead of realtime's 1 kHz.
class OfflineRenderer {
public:
    /// `source` at any rate; internally resampled to 2x `dstRate`. Returns
    /// interleaved float32 stereo at `dstRate`.
    static std::vector<float> render(const SourceBuffer& source, const RenderRequest& req, int32_t dstRate);
};

} // namespace grvr
