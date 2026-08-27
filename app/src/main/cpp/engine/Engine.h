#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "../dsp/OutputStage.h"
#include "../dsp/Smoother.h"
#include "../io/SourceBuffer.h"
#include "../io/ParamRing.h"
#include "../io/TripleBuffer.h"
#include "../io/CaptureRing.h"
#include "GrainScheduler.h"
#include "ModEngine.h"
#include "ParamId.h"

namespace grvr {

/// Published from the audio thread, read by the UI (spec 1.2).
struct Meters {
    float peakL         = 0.0f;
    float peakR         = 0.0f;
    float latencyMs     = 0.0f;
    float cpuLoad       = 0.0f;   // fraction of the callback deadline used
    int32_t xruns       = 0;
    int32_t bufferFrames= 0;
    int32_t bufferGrows = 0;      // times we widened the buffer after an xrun
    int32_t running     = 0;
    int32_t activeVoices= 0;      // concurrent grains (spec 1.2)
};

/// M0 engine: opens an Oboe stream and renders a smoothed test tone.
/// The grain engine replaces renderBlock() in M2 -- everything around it
/// (threading, transport, metering, recovery) is meant to stay put.
class Engine : public oboe::AudioStreamDataCallback,
               public oboe::AudioStreamErrorCallback {
public:
    Engine();
    ~Engine() override;

    bool start();
    void stop();
    bool isRunning() const noexcept { return running_.load(std::memory_order_acquire); }

    /// UI thread. Never blocks; drops the message if the ring is full.
    void setParam(uint16_t id, float value) noexcept { paramRing_.push(id, value); }

    /// UI thread. The whole random universe for a Seed (spec 3.1-3.3) --
    /// reseeds the grain streams and every mod source together, since Lorenz
    /// and Drift are as much a part of the deterministic output as grains.
    /// Deferred to the audio thread (like everything else Lorenz/Drift touch)
    /// rather than mutated here, since their state is not lock-free-safe to
    /// write from two threads at once.
    void setMasterSeed(int64_t seed) noexcept {
        pendingMasterSeed_.store(seed, std::memory_order_relaxed);
        masterSeedDirty_.store(true, std::memory_order_release);
    }

    /// UI thread. A/B switch for the whole modulation matrix (spec M4 debug).
    void setChaosEnabled(bool on) noexcept {
        pendingChaosAction_.store(on ? 1 : 2, std::memory_order_release);
    }

    /// Runs a short synthetic benchmark and picks a voice-cap tier -- 256
    /// flagship / 128 mid-tier / 64 floor (spec 8.4, M8). Call off the audio
    /// thread: it renders ~1s of throwaway audio on its own scratch
    /// GrainScheduler to measure, then applies the result to grainEngine_'s
    /// voice cap (safe to call while the real engine is running -- the cap
    /// is atomic).
    int32_t profileAndSetVoiceCap();

    void pollMeters(Meters& out) noexcept { meters_.read(out); }
    void pollCloud(GrainCloudSnapshot& out) noexcept { cloudSnapshot_.read(out); }

    /// UI thread, after freezing the moment (spec 6.1). Up to the last
    /// [seconds] of what was actually heard, oldest-first, interleaved int16.
    std::vector<int16_t> captureSnapshot(double seconds) const { return captureRing_.snapshot(seconds); }
    int32_t captureSampleRate() const noexcept { return captureRing_.sampleRate(); }

    // --- source preview (M1). UI/IO thread builds the buffer; the audio thread
    //     picks it up through a one-deep retirement handoff (no lock, no free
    //     on the audio thread).
    void setSource(std::shared_ptr<SourceBuffer> buf) noexcept;
    void clearSource() noexcept { setSource(nullptr); }
    void previewSetRegion(int64_t inFrame, int64_t outFrame) noexcept;
    void previewSeek(int64_t frame) noexcept { previewSeek_.store(frame, std::memory_order_release); }
    void previewPlay(bool on) noexcept { previewPlaying_.store(on ? 1 : 0, std::memory_order_release); }
    void previewSetLoop(bool on) noexcept { previewLoop_.store(on ? 1 : 0, std::memory_order_release); }
    void previewSetGain(float g) noexcept { previewGainTarget_.store(g, std::memory_order_release); }
    int64_t previewPosition() const noexcept { return previewPosOut_.load(std::memory_order_acquire); }
    bool    previewIsPlaying() const noexcept { return previewPlaying_.load(std::memory_order_acquire) != 0; }

    /// Human-readable description of what the device actually granted --
    /// requested and granted configurations differ more often than you expect.
    std::string configDescription();
    int32_t     engineSampleRate() const noexcept { return engineRate_.load(std::memory_order_acquire); }

    // --- oboe callbacks ---
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream,
                                          void* audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool openStream();
    void closeStream();
    void applyPendingParams() noexcept;
    void configureSmoothers(float sampleRate) noexcept;
    void renderPreview(float* out, int32_t numFrames, float& peakL, float& peakR) noexcept;

    std::mutex                        lifecycleLock_;   // UI thread only
    std::shared_ptr<oboe::AudioStream> stream_;

    ParamRing<256>       paramRing_;
    TripleBuffer<Meters> meters_;
    TripleBuffer<GrainCloudSnapshot> cloudSnapshot_;

    // --- audio-thread state ---
    Smoother toneHz_;
    Smoother toneGain_;
    Smoother toneEnv_;   // on/off envelope, separate from the user gain
    Smoother masterGain_;
    Smoother fade_;
    double   phase_        = 0.0;
    float    sampleRate_   = 48000.0f;
    bool     ftzDone_      = false;
    int32_t  lastXRun_     = 0;
    int32_t  bufferGrows_  = 0;
    int32_t  burstFrames_  = 192;
    int      tuneCountdown_= 0;
    float    peakL_        = 0.0f;
    float    peakR_        = 0.0f;
    float    cpuLoad_      = 0.0f;

    // --- source preview state ---
    std::shared_ptr<SourceBuffer> sourceHold_;      // current (keeps memory alive)
    std::shared_ptr<SourceBuffer> sourceRetired_;   // previous (freed at next load)
    std::atomic<SourceBuffer*>    source_    {nullptr};
    std::atomic<int64_t> previewIn_       {0};
    std::atomic<int64_t> previewOut_      {0};
    std::atomic<int64_t> previewSeek_     {-1};
    std::atomic<int64_t> previewPosOut_   {0};      // published playhead, for the UI
    std::atomic<int32_t> previewPlaying_  {0};
    std::atomic<int32_t> previewLoop_     {1};
    std::atomic<float>   previewGainTarget_ {0.9f};
    double   previewPos_  = 0.0;                     // audio-thread playhead
    Smoother previewGain_;

    std::atomic<int32_t> engineRate_ {48000};
    std::atomic<bool>  running_    {false};
    std::atomic<bool>  restarting_ {false};
    std::atomic<float> stopFade_   {1.0f};   // driven to 0 for a clickless stop

    // --- grain engine (M2). Grains render into grainMix_ and pass through
    // their own OutputStage before being added to `out`; the M0 test tone and
    // the M1 raw preview stay outside it so their diagnostic level readouts
    // remain exactly linear.
    GrainScheduler grainEngine_;
    ModEngine      modEngine_;
    OutputStage    outputStage_;
    CaptureRing    captureRing_;
    Smoother       outputWidth_;
    Smoother       outputGain_;
    static constexpr int32_t kMaxBlockFrames = 8192;
    float grainMix_[kMaxBlockFrames * 2] = {};

    int32_t controlPeriodSamples_ = 48;   // 1 kHz control rate (spec 4.3)
    int32_t controlCountdown_     = 0;

    std::atomic<int64_t> pendingMasterSeed_ {1};
    std::atomic<bool>    masterSeedDirty_   {false};
    std::atomic<int32_t> pendingChaosAction_ {0};   // 0=none 1=enable 2=disable

    static constexpr int   kBurstsAtStart = 4;   // spec 8.1: stability over latency
    static constexpr int   kBurstsMax     = 8;
    static constexpr int   kTuneInterval  = 32;  // callbacks between xrun checks
};

} // namespace grvr
