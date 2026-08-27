#include "OfflineRenderer.h"

#include <algorithm>
#include <cmath>

#include "../dsp/OutputStage.h"
#include "../dsp/Resampler.h"
#include "GrainScheduler.h"
#include "ModEngine.h"

namespace grvr {

namespace {

void applyBaseParams(GrainScheduler& grains, ModEngine& mod, const RenderRequest& req) {
    grains.setMasterSeed(req.masterSeed);
    grains.setWindowType(req.windowType);

    mod.setMasterSeed(req.masterSeed);
    if (req.chaosEnabled) mod.loadFirstLight(); else mod.clearRoutes();

    mod.setBase(kDestDensity, req.density);
    mod.setBase(kDestTimingJitter, req.timingJitter);
    mod.setBase(kDestGrainSize, req.grainSizeMs);
    mod.setBase(kDestSizeJitter, req.sizeJitter);
    mod.setBase(kDestPosition, req.position);
    mod.setBase(kDestSprayMs, req.sprayMs);
    mod.setBase(kDestDrift, req.drift);
    mod.setBase(kDestPitch, req.pitchSt);
    mod.setBase(kDestPitchSpray, req.pitchSpraySt);
    mod.setBase(kDestReverseProb, req.reverseProb);
    mod.setBase(kDestSpread, req.spread);
    mod.setBase(kDestOutputWidth, req.outputWidth);
    mod.setBase(kDestChaosRate, req.chaosRate);
}

} // namespace

std::vector<float> OfflineRenderer::render(const SourceBuffer& source, const RenderRequest& req, int32_t dstRate) {
    const int32_t osRate = dstRate * 2;   // 2x oversample (spec 6.3)

    const SourceBuffer osSource = source.sampleRate() == osRate
        ? source
        : Resampler::resample(source, osRate);

    GrainScheduler grains;
    grains.configure(static_cast<float>(osRate));
    grains.setSource(&osSource);

    ModEngine mod;
    mod.configure(req.masterSeed);
    applyBaseParams(grains, mod, req);

    const int32_t controlPeriod = std::max(1, static_cast<int32_t>(osRate / 4000.0f));   // 4 kHz offline (spec 6.3)
    int32_t countdown = controlPeriod;

    auto renderInto = [&](float* dst, int64_t frames) {
        int64_t offset = 0;
        while (offset < frames) {
            if (countdown <= 0) {
                mod.tick(grains);
                countdown = controlPeriod;
            }
            const int32_t chunk = static_cast<int32_t>(
                std::min<int64_t>(countdown, frames - offset));
            grains.renderBlock(dst + offset * 2, chunk);
            countdown -= chunk;
            offset += chunk;
        }
    };

    // Warm-up: let every smoother (slowest tau 80ms) settle onto the
    // requested params before the real render starts, so this matches a
    // capture taken well into a held performance rather than fading in from
    // scratch (the offline instance has no history of its own).
    {
        const int64_t warmupFrames = static_cast<int64_t>(0.5 * osRate);
        std::vector<float> warmup(static_cast<size_t>(warmupFrames) * 2, 0.0f);
        renderInto(warmup.data(), warmupFrames);
    }

    const int64_t totalFrames = static_cast<int64_t>(req.durationSeconds * osRate);
    const int64_t tailFrames = req.seamlessLoop
        ? static_cast<int64_t>(req.crossfadeSeconds * osRate) : 0;
    const int64_t renderFrames = totalFrames + tailFrames;

    std::vector<float> mix(static_cast<size_t>(renderFrames) * 2, 0.0f);
    renderInto(mix.data(), renderFrames);

    OutputStage stage;
    stage.configure(static_cast<float>(osRate));
    for (int64_t i = 0; i < renderFrames; ++i) {
        stage.process(mix[static_cast<size_t>(i) * 2], mix[static_cast<size_t>(i) * 2 + 1],
                      req.outputWidth, req.outputGain);
    }

    // Seamless loop wrap (spec 6.4): equal-power crossfade the tail overhang
    // back over the head. Grains are long, so a naive cut visibly and
    // audibly amputates the cloud at the loop point.
    if (req.seamlessLoop && tailFrames > 0) {
        for (int64_t i = 0; i < tailFrames; ++i) {
            const double t = static_cast<double>(i) / static_cast<double>(tailFrames);
            const float fadeIn  = static_cast<float>(std::sin(t * M_PI * 0.5));
            const float fadeOut = static_cast<float>(std::cos(t * M_PI * 0.5));
            const int64_t tailIdx = totalFrames + i;
            const size_t hi = static_cast<size_t>(i) * 2, ti = static_cast<size_t>(tailIdx) * 2;
            mix[hi]     = mix[hi]     * fadeIn + mix[ti]     * fadeOut;
            mix[hi + 1] = mix[hi + 1] * fadeIn + mix[ti + 1] * fadeOut;
        }
        mix.resize(static_cast<size_t>(totalFrames) * 2);
    }

    // Decimate back down from the 2x oversampled render (spec 6.3's "proper
    // decimation filter", not just the per-grain one-pole used realtime).
    std::vector<float> chL(mix.size() / 2), chR(mix.size() / 2);
    for (size_t i = 0; i < chL.size(); ++i) {
        chL[i] = mix[i * 2];
        chR[i] = mix[i * 2 + 1];
    }
    const std::vector<float> outL = Resampler::resampleChannel(chL, osRate, dstRate);
    const std::vector<float> outR = Resampler::resampleChannel(chR, osRate, dstRate);

    std::vector<float> out(outL.size() * 2, 0.0f);
    for (size_t i = 0; i < outL.size(); ++i) {
        out[i * 2] = outL[i];
        out[i * 2 + 1] = i < outR.size() ? outR[i] : 0.0f;
    }
    return out;
}

} // namespace grvr
