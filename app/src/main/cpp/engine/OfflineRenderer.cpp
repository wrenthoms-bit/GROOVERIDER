#include "OfflineRenderer.h"

#include <algorithm>
#include <cmath>
#include <memory>

#include "../dsp/Resampler.h"
#include "GrainEngine.h"
#include "ModEngine.h"

namespace grvr {

namespace {

void applyBaseParams(GrainEngine& grains, ModEngine& mod, const RenderRequest& req) {
    grains.setMasterSeed(req.masterSeed);
    grains.setWindowType(req.windowType);
    grains.setOutputGain(req.outputGain);

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

// The Observatory path: the live signal chain at the output rate, then the tail.
std::vector<float> renderWithObservatory(const SourceBuffer& source, const RenderRequest& req, int32_t dstRate) {
    const SourceBuffer rateSource = source.sampleRate() == dstRate
        ? source
        : Resampler::resample(source, dstRate);

    const auto grainsOwner = std::make_unique<GrainEngine>();
    GrainEngine& grains = *grainsOwner;
    grains.configure(static_cast<float>(dstRate));
    grains.setSource(&rateSource);

    // Set up exactly as the web's own export sets up (docs/index.html,
    // renderOffline): Observatory from the first sample, every param in place
    // rather than gliding in. With the same source audio at the same rate, the
    // two then render the same samples.
    grains.setMasterSeed(req.masterSeed);
    grains.startInObservatory();

    ModEngine mod;
    mod.configure(req.masterSeed);
    applyBaseParams(grains, mod, req);
    mod.setRoutesMuted(true);
    grains.setObsParam(grv::O_CHAOS, req.chaos);       grains.setObsParam(grv::O_PITCH, req.pitchAmount);
    grains.setObsParam(grv::O_KEY, req.key);           grains.setObsParam(grv::O_SCALE, req.scale);
    grains.setObsParam(grv::O_REGISTER, req.registerSt); grains.setObsParam(grv::O_DETUNE, req.detune);
    grains.setObsParam(grv::O_DRONE, req.drone);       grains.setObsParam(grv::O_SPACE, req.space);
    grains.setObsParam(grv::O_SHIMMER, req.shimmer);   grains.setObsParam(grv::O_TONE, req.tone);
    grains.setObsParam(grv::O_SCAN, req.scan);
    grains.setNotes(req.notes, req.noteCount);
    mod.tick(grains);                 // pushes every base value: grain params to the core, the rest to the Observatory
    grains.snapParams();

    // Same 1 kHz control rate as the live engine.
    const int32_t controlPeriod = std::max(1, static_cast<int32_t>(static_cast<float>(dstRate) / 1000.0f));
    int32_t countdown = controlPeriod;
    auto renderInto = [&](float* dst, int64_t frames) {
        int64_t offset = 0;
        while (offset < frames) {
            if (countdown <= 0) { mod.tick(grains); countdown = controlPeriod; }
            const int32_t chunk = static_cast<int32_t>(std::min<int64_t>(countdown, frames - offset));
            grains.renderBlock(dst + offset * 2, chunk);
            countdown -= chunk;
            offset += chunk;
        }
    };

    // Pre-roll, as the web export does: three seconds for the cloud to fill
    // and the reverb to arrive, discarded.
    {
        const int64_t preFrames = 3LL * dstRate;
        std::vector<float> pre(static_cast<size_t>(preFrames) * 2, 0.0f);
        renderInto(pre.data(), preFrames);
    }

    const int64_t bodyFrames = static_cast<int64_t>(req.durationSeconds * dstRate);

    if (req.seamlessLoop) {
        // A loop has no tail to append: the overhang is folded back over the head.
        const int64_t tailFrames = static_cast<int64_t>(req.crossfadeSeconds * dstRate);
        std::vector<float> mix(static_cast<size_t>(bodyFrames + tailFrames) * 2, 0.0f);
        renderInto(mix.data(), bodyFrames + tailFrames);
        for (int64_t i = 0; i < tailFrames; ++i) {
            const double t = static_cast<double>(i) / static_cast<double>(tailFrames);
            const float fadeIn  = static_cast<float>(std::sin(t * M_PI * 0.5));
            const float fadeOut = static_cast<float>(std::cos(t * M_PI * 0.5));
            const size_t hi = static_cast<size_t>(i) * 2, ti = static_cast<size_t>(bodyFrames + i) * 2;
            mix[hi]     = mix[hi]     * fadeIn + mix[ti]     * fadeOut;
            mix[hi + 1] = mix[hi + 1] * fadeIn + mix[ti + 1] * fadeOut;
        }
        mix.resize(static_cast<size_t>(bodyFrames) * 2);
        return mix;
    }

    // Body, then stop spawning grains and let the space ring out: until it has
    // been quiet (peak under -60 dBFS) for 0.4 s, or 20 s at most.
    constexpr int64_t kBlock = 128;
    const int64_t maxTail = 20LL * dstRate;
    std::vector<float> mix(static_cast<size_t>(bodyFrames + maxTail) * 2, 0.0f);
    renderInto(mix.data(), bodyFrames);
    grains.setPlaying(false);
    int64_t written = bodyFrames, quiet = 0;
    while (written < bodyFrames + maxTail) {
        const int64_t n = std::min<int64_t>(kBlock, bodyFrames + maxTail - written);
        float* block = mix.data() + written * 2;
        renderInto(block, n);
        written += n;
        float peak = 0.0f;
        for (int64_t i = 0; i < n * 2; ++i) peak = std::max(peak, std::fabs(block[i]));
        if (written > bodyFrames + dstRate / 4) {
            quiet = peak < 0.001f ? quiet + n : 0;
            if (quiet > static_cast<int64_t>(0.4 * dstRate)) break;
        }
    }
    mix.resize(static_cast<size_t>(written) * 2);

    // 20 ms in; a smooth fade over the tail (1.5 s at most) out.
    const int64_t fadeIn = std::min<int64_t>(static_cast<int64_t>(0.02 * dstRate), written);
    const int64_t fadeOut = std::min<int64_t>(static_cast<int64_t>(1.5 * dstRate), written - bodyFrames);
    for (int64_t i = 0; i < fadeIn; ++i) {
        const float g = static_cast<float>(i) / static_cast<float>(fadeIn);
        mix[static_cast<size_t>(i) * 2] *= g; mix[static_cast<size_t>(i) * 2 + 1] *= g;
    }
    for (int64_t i = 0; i < fadeOut; ++i) {
        const float g = static_cast<float>(i) / static_cast<float>(fadeOut), q = g * g * (3.0f - 2.0f * g);
        const size_t k = static_cast<size_t>(written - 1 - i) * 2;
        mix[k] *= q; mix[k + 1] *= q;
    }
    return mix;
}

} // namespace

std::vector<float> OfflineRenderer::render(const SourceBuffer& source, const RenderRequest& req, int32_t dstRate) {
    if (req.observatory) return renderWithObservatory(source, req, dstRate);

    const int32_t osRate = dstRate * 2;   // 2x oversample (spec 6.3)

    const SourceBuffer osSource = source.sampleRate() == osRate
        ? source
        : Resampler::resample(source, osRate);

    const auto grainsOwner = std::make_unique<GrainEngine>();
    GrainEngine& grains = *grainsOwner;
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
