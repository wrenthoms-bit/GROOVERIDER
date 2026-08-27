#pragma once
#include <algorithm>
#include <atomic>
#include <cstdint>

#include "../dsp/Smoother.h"
#include "../dsp/Window.h"
#include "../io/SourceBuffer.h"
#include "GrainCloudSnapshot.h"
#include "VoicePool.h"

namespace grvr {

/// The grain engine core (spec 2, M2): a sample-accurate onset scheduler, the
/// voice pool it spawns into, and the per-sample grain mix -- everything
/// between "a loaded source" and "a wet stereo signal ready for the output
/// stage". Owned by Engine and driven once per audio callback.
///
/// Randomness is grain-index-derived from day one (spec 3.2): there is no
/// running PRNG stream here to later rip out for M3. M3 only adds the Seed
/// persistence/UI layer around `masterSeed_`, which already exists below.
class GrainScheduler {
public:
    void configure(float sampleRate) noexcept;

    /// UI/IO thread. Raw pointer, lifetime owned by Engine (same handoff
    /// pattern as preview); atomic because the audio thread reads it
    /// concurrently from renderBlock().
    void setSource(const SourceBuffer* src) noexcept {
        source_.store(src, std::memory_order_release);
    }

    // --- continuous params: audio-thread-only, called from Engine's param
    // dispatch after draining the ring buffer. One-pole smoothed at sample
    // rate per spec 2.8; only the *target* is set here.
    void setDensity(float grainsPerSec) noexcept      { density_.setTarget(grainsPerSec); }
    void setTimingJitter(float v01) noexcept           { timingJitter_.setTarget(v01); }
    void setGrainSizeMs(float ms) noexcept             { grainSizeMs_.setTarget(ms); }
    void setSizeJitter(float v01) noexcept             { sizeJitter_.setTarget(v01); }
    void setPosition(float norm01) noexcept            { position_.setTarget(norm01); }
    void setSprayMs(float ms) noexcept                 { spray_.setTarget(ms); }
    void setDrift(float rate) noexcept                 { drift_.setTarget(rate); }
    void setPitchSemitones(float st) noexcept          { pitch_.setTarget(st); }
    void setPitchSpraySemitones(float st) noexcept     { pitchSpray_.setTarget(st); }
    void setReverseProb(float v01) noexcept            { reverseProb_.setTarget(v01); }
    void setSpread(float v01) noexcept                 { spread_.setTarget(v01); }

    // --- discrete: applied at the next grain spawn, never mid-grain (spec 2.8)
    void setWindowType(uint16_t w) noexcept { windowType_ = w < kWindowCount ? w : kWindowGaussian; }

    /// The whole random universe (spec 3.1-3.2). UI thread; atomic because
    /// the audio thread reads it on every grain spawn.
    void setMasterSeed(uint64_t seed) noexcept {
        masterSeed_.store(seed, std::memory_order_relaxed);
    }
    uint64_t masterSeed() const noexcept { return masterSeed_.load(std::memory_order_relaxed); }

    /// Read-only grain introspection for testing and the M5 cloud snapshot.
    const Grain& grainAt(int32_t i) const noexcept { return pool_.grains[i]; }

    /// Mixes the grain cloud additively into `out` (interleaved stereo).
    void renderBlock(float* out, int32_t numFrames) noexcept;

    int32_t activeVoices() const noexcept { return pool_.activeCount; }
    float   windowRms(uint16_t w) const noexcept { return windows_.rms(w); }

    /// Adaptive voice cap (spec 8.4, M8): device profiling picks a ceiling
    /// below kMaxGrains on weaker hardware. The soft density limiter (spec
    /// 2.5) already respects this everywhere it respects kMaxGrains. Atomic
    /// because profiling may run from a thread other than the one currently
    /// rendering (e.g. re-profiling while the engine is live).
    void setMaxVoices(int32_t n) noexcept {
        maxVoices_.store(std::clamp(n, 8, kMaxGrains), std::memory_order_relaxed);
    }
    int32_t maxVoices() const noexcept { return maxVoices_.load(std::memory_order_relaxed); }

    /// Called once per audio callback, after renderBlock(). Cheap: a fixed
    /// loop over currently-active grains, no allocation (spec 1.2, 5.2).
    void writeSnapshot(GrainCloudSnapshot& out) const noexcept {
        const SourceBuffer* src = source_.load(std::memory_order_relaxed);
        const int64_t frames = src ? src->frames() : 0;
        const int32_t n = pool_.activeCount < kMaxGrains ? pool_.activeCount : kMaxGrains;
        out.count = n;
        for (int32_t i = 0; i < n; ++i) {
            const Grain& g = pool_.grains[i];
            GrainVisual& v = out.grains[i];
            v.sourcePosNorm = frames > 0
                ? static_cast<float>(std::clamp(g.srcPos / static_cast<double>(frames), 0.0, 1.0))
                : 0.0f;
            v.pitchRatio = static_cast<float>(g.rate);
            v.age01      = g.life > 0 ? static_cast<float>(g.age) / static_cast<float>(g.life) : 1.0f;
            v.amp        = windows_.sample(g.windowId, v.age01) * g.amp;
            v.pan        = g.panR - g.panL;
        }
    }

private:
    void  renderSegment(float* out, int32_t from, int32_t to, const SourceBuffer* src) noexcept;
    void  spawnGrain(const SourceBuffer* src) noexcept;
    float effectiveDensity() const noexcept;
    float rawGainComp(float densityNow, float sizeMsNow) const noexcept;

    std::atomic<const SourceBuffer*> source_ {nullptr};
    VoicePool            pool_;
    WindowSet             windows_;

    Smoother density_, timingJitter_, grainSizeMs_, sizeJitter_;
    Smoother position_, spray_, drift_;
    Smoother pitch_, pitchSpray_, reverseProb_, spread_;
    Smoother gainComp_;

    uint16_t windowType_ = kWindowGaussian;
    std::atomic<int32_t> maxVoices_ {kMaxGrains};
    std::atomic<uint64_t> masterSeed_ {1};
    uint64_t grainIndex_ = 0;

    float  sampleRate_       = 48000.0f;
    double clockSamples_     = 0.0;   // running absolute sample clock
    double nextOnsetSamples_ = 0.0;
    double driftOffsetNorm_  = 0.0;   // accumulated playhead scan, wraps [0,1)
};

} // namespace grvr
