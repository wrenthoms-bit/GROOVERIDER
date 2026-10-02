#pragma once
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>

#include "GrainCore.h"     // core/ -- the grain engine shared with the web app
#include "Observatory.h"   // core/ -- chaos, drone, scale-lock and the space around it

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
///
/// Two modes. With the Observatory off (every Seed saved before it existed)
/// the core is driven directly, as it always was. With it on, core/Observatory.h
/// sits around the core exactly as in the web app: it steers position, spray,
/// pitch, spread and width itself and adds the space, so the values the
/// setters below receive for those become its base values instead.
class GrainEngine {
public:
    GrainEngine() {
        for (int i = 0; i < grv::O_COUNT; ++i) obsParams_[i] = 0.0f;
        obsParams_[grv::O_POSITION] = 0.5f; obsParams_[grv::O_SPRAY_MS] = 250.0f;
        obsParams_[grv::O_SPREAD] = 0.8f;   obsParams_[grv::O_WIDTH] = 1.0f;
        obsParams_[grv::O_CHAOS] = 0.1f;    obsParams_[grv::O_PITCH] = 0.15f;
        obsParams_[grv::O_DETUNE] = 0.05f;  obsParams_[grv::O_SPACE] = 0.5f;
        obsParams_[grv::O_SHIMMER] = 0.3f;  obsParams_[grv::O_TONE] = 0.7f;
    }

    void configure(float sampleRate) noexcept {
        sampleRate_ = sampleRate;
        core_.init(sampleRate);
        core_.setParamNow(grv::P_PLAYING, 1.0f);      // a loaded source plays unless setPlaying(false)
        core_.setParamNow(grv::P_ANTI_ALIAS, 1.0f);
        boundGen_ = -1;                               // rebind the source on the next block
        obsOn_ = false;                               // the next block switches it back on if wanted
        fadeGain_ = 1.0f; fadeDir_ = 0;
        fadeStep_ = 1.0f / std::max(1.0f, 0.010f * sampleRate);   // 10 ms
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
    void setReverseProb(float v01) noexcept            { core_.setParam(grv::P_REVERSE_PROB, v01); }
    void setOutputGain(float gain) noexcept            { core_.setParam(grv::P_OUT_GAIN, gain); }
    void setPlaying(bool on) noexcept                  { core_.setParam(grv::P_PLAYING, on ? 1.0f : 0.0f); }

    // These four are the Observatory's base values while it is on, and plain
    // core params while it is off.
    void setPosition(float norm01) noexcept            { shared(grv::O_POSITION, grv::P_POSITION, norm01); }
    void setSprayMs(float ms) noexcept                 { shared(grv::O_SPRAY_MS, grv::P_SPRAY_MS, ms); }
    void setSpread(float v01) noexcept                 { shared(grv::O_SPREAD, grv::P_SPREAD, v01); }
    void setOutputWidth(float width) noexcept          { shared(grv::O_WIDTH, grv::P_OUT_WIDTH, width); }

    // These three belong to the Observatory while it is on (it servos drift and
    // picks each grain's pitch), so they only reach the core while it is off.
    void setDrift(float rate) noexcept                 { if (!obsOn_) core_.setParam(grv::P_DRIFT, rate); }
    void setPitchSemitones(float st) noexcept          { if (!obsOn_) core_.setParam(grv::P_PITCH, st); }
    void setPitchSpraySemitones(float st) noexcept     { if (!obsOn_) core_.setParam(grv::P_PITCH_SPRAY, st); }

    // --- Observatory. Audio thread, like the setters above.
    /// Takes effect over a 10 ms fade down and back up, at the bottom of which
    /// the cloud is restarted in the new mode.
    void setObservatory(bool on) noexcept { obsWanted_ = on; }
    /// For a render that begins in Observatory mode: no fade, nothing played
    /// first. Call straight after configure(), before any params are set, so
    /// they go where the web host sends them and the first sample is the one
    /// the web's own render would produce.
    void startInObservatory() noexcept {
        obsWanted_ = true; obsOn_ = true;
        obs_->init(sampleRate_);
        for (int i = 0; i < grv::O_COUNT; ++i) obs_->setParam(i, obsParams_[i]);
        obs_->setNotes(notes_, noteCount_);
        obsSeed_ = masterSeed_.load(std::memory_order_relaxed);
        obs_->setSeed(obsSeed_);
        boundGen_ = -1;      // the first block binds the source and starts the cloud in this mode
    }
    /// Every core param jumps to its target instead of gliding there.
    void snapParams() noexcept { core_.snapParams(); }
    bool observatory() const noexcept { return obsOn_; }
    /// Where its chaos is, each -1 .. 1, for the visuals. Audio thread.
    float chaosX() const noexcept { return obs_->chaosX(); }
    float chaosY() const noexcept { return obs_->chaosY(); }
    float chaosZ() const noexcept { return obs_->chaosZ(); }
    /// `id` is a grv::ObsParam. Position, spray, spread and width also arrive
    /// through their own setters above.
    void setObsParam(int id, float v) noexcept {
        if (id < 0 || id >= grv::O_COUNT) return;
        obsParams_[id] = v;
        if (obsOn_) obs_->setParam(id, v);
    }
    /// Held keyboard notes, in semitones from middle C; none = key + register.
    void setNotes(const float* semis, int32_t n) noexcept {
        noteCount_ = std::clamp(n, 0, static_cast<int32_t>(grv::OBS_MAX_NOTES));
        for (int32_t i = 0; i < noteCount_; ++i) notes_[i] = semis[i];
        if (obsOn_) obs_->setNotes(notes_, noteCount_);
    }

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
        const uint64_t seed = masterSeed_.load(std::memory_order_relaxed);
        core_.setSeed(seed);
        if (seed != obsSeed_) { obs_->setSeed(seed); obsSeed_ = seed; }   // setSeed restarts the chaos: only on change
        core_.setParam(grv::P_MAX_VOICES, static_cast<float>(maxVoices_.load(std::memory_order_relaxed)));

        if (obsWanted_ == obsOn_ && fadeDir_ == 0) {
            renderMode(out, numFrames);
            return;
        }
        // Changing mode: fade out, swap at silence, fade back in.
        for (int32_t done = 0; done < numFrames; ) {
            if (fadeDir_ == 0) fadeDir_ = -1;
            const float room = fadeDir_ < 0 ? fadeGain_ : 1.0f - fadeGain_;
            const int32_t steps = std::max(1, static_cast<int32_t>(std::ceil(room / fadeStep_)));
            const int32_t m = std::min(numFrames - done, steps);
            float* o = out + static_cast<size_t>(done) * 2;
            renderMode(o, m);
            for (int32_t i = 0; i < m; ++i) {
                fadeGain_ = std::clamp(fadeGain_ + static_cast<float>(fadeDir_) * fadeStep_, 0.0f, 1.0f);
                o[i * 2] *= fadeGain_; o[i * 2 + 1] *= fadeGain_;
            }
            done += m;
            if (fadeDir_ < 0 && fadeGain_ <= 0.0f) {
                if (obsWanted_ != obsOn_) switchMode(obsWanted_);
                fadeDir_ = 1;
            } else if (fadeDir_ > 0 && fadeGain_ >= 1.0f) {
                fadeDir_ = 0;
                if (obsWanted_ == obsOn_) {       // settled: the rest of the block needs no fade
                    renderMode(out + static_cast<size_t>(done) * 2, numFrames - done);
                    return;
                }
            }
        }
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

    void shared(int obsId, int coreId, float v) noexcept {
        obsParams_[obsId] = v;
        if (obsOn_) obs_->setParam(obsId, v); else core_.setParam(coreId, v);
    }

    void renderMode(float* out, int32_t numFrames) noexcept {
        if (numFrames <= 0) return;
        if (obsOn_) obs_->render(core_, out, numFrames); else core_.render(out, numFrames);
    }

    void bind(const SourceBuffer* src) noexcept {
        if (src == nullptr || src->frames() <= 0) {
            core_.setBuffers(nullptr, nullptr, 0);
            boundChannels_ = 1; boundFrames_ = 0;
        } else {
            const bool stereo = src->channelCount() >= 2;
            const auto frames = static_cast<int32_t>(std::min<int64_t>(src->frames(), INT32_MAX));
            // The core takes non-const pointers because the web host writes PCM
            // through them; it only ever reads the source itself.
            auto* l = const_cast<float*>(src->channel(0).data());
            auto* r = stereo ? const_cast<float*>(src->channel(1).data()) : l;
            core_.setBuffers(l, r, frames);
            boundChannels_ = stereo ? 2 : 1; boundFrames_ = frames;
        }
        startCloud();
    }

    /// (Re)starts the cloud on the bound source: clears the grains and puts the
    /// playhead at the current position. In Observatory mode that position is
    /// then held by its servo rather than by the core.
    void startCloud() noexcept {
        if (obsOn_) core_.setParamNow(grv::P_POSITION, obsParams_[grv::O_POSITION]);
        core_.setSource(boundChannels_, boundFrames_);
        if (obsOn_) obs_->sourceChanged(obsParams_[grv::O_POSITION]);
    }

    /// Called at the silent bottom of the mode-change fade.
    void switchMode(bool on) noexcept {
        obsOn_ = on;
        if (on) {
            obs_->init(sampleRate_);                 // empties the reverb: no tail left over from last time
            for (int i = 0; i < grv::O_COUNT; ++i) obs_->setParam(i, obsParams_[i]);
            obs_->setNotes(notes_, noteCount_);
            obs_->setSeed(obsSeed_);
        }
        // Either way the cloud restarts where the position control says. Coming
        // back from the Observatory, the caller's next control tick restores
        // the drift, pitch and spray it had been steering.
        startCloud();
    }

    grv::GrainCore core_;
    // ~370 KB of reverb memory: on the heap, allocated once here, so a
    // GrainEngine itself stays small enough to live anywhere.
    std::unique_ptr<grv::Observatory> obs_ = std::make_unique<grv::Observatory>();

    std::atomic<const SourceBuffer*> source_ {nullptr};
    std::atomic<int32_t>  sourceGen_ {0};
    std::atomic<int32_t>  maxVoices_ {kMaxGrains};
    std::atomic<uint64_t> masterSeed_ {1};

    // audio-thread state
    int32_t boundGen_      = -1;
    int32_t boundFrames_   = 0;
    int32_t boundChannels_ = 1;
    float   sampleRate_    = 48000.0f;
    bool    obsOn_         = false;
    bool    obsWanted_     = false;
    uint64_t obsSeed_      = 0;
    float   obsParams_[grv::O_COUNT];   // what the Observatory was last asked for, kept across mode changes
    float   notes_[grv::OBS_MAX_NOTES] = {};
    int32_t noteCount_     = 0;
    float   fadeGain_      = 1.0f;
    float   fadeStep_      = 1.0f / 480.0f;
    int     fadeDir_       = 0;          // -1 fading out, +1 fading in, 0 steady
};

} // namespace grvr
