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

    // Observatory (core/Observatory.h). Off = the Seed predates it.
    bool  observatory = false;
    float chaos = 0.1f, pitchAmount = 0.15f, key = 0.0f, scale = 0.0f, registerSt = 0.0f, detune = 0.05f;
    float drone = 0.0f, space = 0.5f, shimmer = 0.3f, tone = 0.7f, scan = 0.0f;

    double durationSeconds = 60.0;

    // Seamless loop rendering (spec 6.4): render `durationSeconds` plus a
    // tail overhang, then crossfade the tail back over the head.
    bool   seamlessLoop = false;
    double crossfadeSeconds = 0.05;
};

/// A second engine instance, same C++ code, on a virtual clock -- no Oboe
/// stream, no realtime deadline (spec 6.3).
///
/// Without the Observatory it runs at 2x oversample with a proper decimation
/// filter on the way back down, and ticks the mod matrix at 4 kHz instead of
/// realtime's 1 kHz. With the Observatory it runs exactly as the live engine
/// does, at the output rate, so the file is what was heard -- and, like the
/// web app's export, it keeps going after `durationSeconds` until the reverb
/// tail has died away (up to 20 s).
class OfflineRenderer {
public:
    /// `source` at any rate; resampled internally. Returns interleaved float32
    /// stereo at `dstRate`.
    static std::vector<float> render(const SourceBuffer& source, const RenderRequest& req, int32_t dstRate);
};

} // namespace grvr
