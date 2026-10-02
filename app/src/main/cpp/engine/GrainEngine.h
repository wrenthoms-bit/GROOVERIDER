#pragma once
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>

#include "GrainCore.h"   // core/ -- the grain engine shared with the web app

#include "../io/SourceBuffer.h"
#include "GrainCloudSnapshot.h"

namespace grvr {

static_assert(kMaxGrains == grv::MAX_GRAINS, "GrainCloudSnapshot and GrainCore disagree on the voice count");

/// Window ids as the app, ParamId and saved Seeds know them. GrainCore numbers
/// Hann and Tukey the other way round; GrainEngine translates.
enum WindowType : uint16_t {
    kWindowGaussian = 0,
    kWindowTukey    = 1,
    kWindowHann     = 2,
    kWindowCount    = 3,
};

/// Android's seat on the shared grain engine (spec 2): a thin adapter that
/// feeds core/GrainCore.h a SourceBuffer and Android-side param values, and
/// pulls stereo blocks out. Everything that decides the sound -- scheduling,
/// windows, interpolation, gain compensation, the output stage (DC blocker /
/// width / soft saturation / gain) -- lives in the core, so a Seed renders the
/// same grains here as in the browser. Owned by Engine and driven once per
/// audio callback; OfflineRenderer owns a second instance.
class GrainEngine {
public:
    void configure(float sampleRate) noexcept {
        core_.init(sampleRate);
        core_.setParamNow(grv::P_PLAYING, 1.0f);      // no transport gate here: a loaded source plays
        core_.setParamNow(grv::P_ANTI_ALIAS, 1.0f);
        boundGen_ = -1;                               // rebind the source on the next block
    }

    /// UI/IO thread. Raw pointer, lifetime owned by Engine (one-deep
    /// retirement); the audio thread picks it up at the top of its next block.
    void setSource(const SourceBuffer* src) noexcept {
        source_.store(src, std::memory_order_release);
        sourceGen_.fetch_add(1, std::memory_order_release);
    }

    // --- continuous params: audio-thread-only, called from ModEngine::tick()
    // and Engine's param dispatch. The core one-pole smooths each of them at
    // sample rate (spec 2.8); only the *target* is set here.
    void setDensity(float grainsPerSec) noexcept      { core_.setParam(grv::P_DENSITY, grainsPerSec); }
    void setTimingJitter(float v01) noexcept           { core_.setParam(grv::P_TIMING_JITTER, v01); }
    void setGrainSizeMs(float ms) noexcept             { core_.setParam(grv::P_GRAIN_MS, ms); }
    void setSizeJitter(float v01) noexcept             { core_.setParam(grv::P_SIZE_JITTER, v01); }
    void setPosition(float norm01) noexcept            { core_.setParam(grv::P_POSITION, norm01); }
    void setSprayMs(float ms) noexcept                 { core_.setParam(grv::P_SPRAY_MS, ms); }
    void setDrift(float rate) noexcept                 { core_.setParam(grv::P_DRIFT, rate); }
    void setPitchSemitones(float st) noexcept          { core_.setParam(grv::P_PITCH, st); }
    void setPitchSpraySemitones(float st) noexcept     { core_.setParam(grv::P_PITCH_SPRAY, st); }
    void setReverseProb(float v01) noexcept            { core_.setParam(grv::P_REVERSE_PROB, v01); }
    void setSpread(float v01) noexcept                 { core_.setParam(grv::P_SPREAD, v01); }
    void setOutputWidth(float width) noexcept          { core_.setParam(grv::P_OUT_WIDTH, width); }
    void setOutputGain(float gain) noexcept            { core_.setParam(grv::P_OUT_GAIN, gain); }

    // --- discrete: applied at the next grain spawn, never mid-grain (spec 2.8)
    void setWindowType(uint16_t w) noexcept { core_.setParam(grv::P_WINDOW, static_cast<float>(coreWindow(w))); }

    /// The whole random universe (spec 3.1-3.2). Any thread; the audio thread
    /// hands it to the core at the top of its next block.
    void setMasterSeed(uint64_t seed) noexcept {
        masterSeed_.store(seed, std::memory_order_relaxed);
    }
    uint64_t masterSeed() const noexcept { return masterSeed_.load(std::memory_order_relaxed); }

    /// Adaptive voice cap (spec 8.4, M8): device profiling picks a ceiling
    /// below kMaxGrains on weaker hardware, and the core soft-limits density
    /// to stay under it. Atomic because profiling may run from a thread other
    /// than the one currently rendering.
    void setMaxVoices(int32_t n) noexcept {
        maxVoices_.store(std::clamp(n, 8, kMaxGrains), std::memory_order_relaxed);
    }
    int32_t maxVoices() const noexcept { return maxVoices_.load(std::memory_order_relaxed); }

    /// Writes the finished grain signal (output stage included) to `out`,
    /// interleaved stereo.
    void renderBlock(float* out, int32_t numFrames) noexcept {
        const int32_t gen = sourceGen_.load(std::memory_order_acquire);
        if (gen != boundGen_) {
            bind(source_.load(std::memory_order_acquire));
            boundGen_ = gen;
        }
        core_.setSeed(masterSeed_.load(std::memory_order_relaxed));
        core_.setParam(grv::P_MAX_VOICES, static_cast<float>(maxVoices_.load(std::memory_order_relaxed)));
        core_.render(out, numFrames);
    }

    int32_t activeVoices() const noexcept { return core_.activeGrains(); }

    /// Read-only introspection for testing.
    const grv::Grain& grainAt(int32_t i) const noexcept { return core_.grainAt(i); }
    float windowAt(uint16_t w, float phase) const noexcept { return core_.windowAt(coreWindow(w), phase); }
    float windowRms(uint16_t w) const noexcept { return core_.windowRms(coreWindow(w)); }

    /// Called once per audio callback, after renderBlock(). Cheap: a fixed
    /// loop over currently-active grains, no allocation (spec 1.2, 5.2).
    void writeSnapshot(GrainCloudSnapshot& out) const noexcept {
        const int32_t n = std::min(core_.activeGrains(), kMaxGrains);
        out.count = n;
        for (int32_t i = 0; i < n; ++i) {
            const grv::Grain& g = core_.grainAt(i);
            GrainVisual& v = out.grains[i];
            // Grains read past either end of the source wrap round, so wrap here too.
            double posNorm = boundFrames_ > 0 ? g.srcPos / static_cast<double>(boundFrames_) : 0.0;
            posNorm -= std::floor(posNorm);
            v.sourcePosNorm = static_cast<float>(posNorm);
            v.pitchRatio = static_cast<float>(g.rate);
            v.age01      = g.life > 0 ? static_cast<float>(g.age) / static_cast<float>(g.life) : 1.0f;
            v.amp        = core_.windowAt(g.win, v.age01) * g.amp;
            v.pan        = g.panR - g.panL;
        }
    }

private:
    static int coreWindow(uint16_t w) noexcept {
        return w == kWindowTukey ? 2 : (w == kWindowHann ? 1 : 0);
    }

    void bind(const SourceBuffer* src) noexcept {
        if (src == nullptr || src->frames() <= 0) {
            core_.setBuffers(nullptr, nullptr, 0);
            core_.setSource(1, 0);
            boundFrames_ = 0;
            return;
        }
        const bool stereo = src->channelCount() >= 2;
        const auto frames = static_cast<int32_t>(std::min<int64_t>(src->frames(), INT32_MAX));
        // The core takes non-const pointers because the web host writes PCM
        // through them; it only ever reads the source itself.
        auto* l = const_cast<float*>(src->channel(0).data());
        auto* r = stereo ? const_cast<float*>(src->channel(1).data()) : l;
        core_.setBuffers(l, r, frames);
        core_.setSource(stereo ? 2 : 1, frames);
        boundFrames_ = frames;
    }

    grv::GrainCore core_;

    std::atomic<const SourceBuffer*> source_ {nullptr};
    std::atomic<int32_t>  sourceGen_ {0};
    std::atomic<int32_t>  maxVoices_ {kMaxGrains};
    std::atomic<uint64_t> masterSeed_ {1};

    // audio-thread state
    int32_t boundGen_    = -1;
    int32_t boundFrames_ = 0;
};

} // namespace grvr
