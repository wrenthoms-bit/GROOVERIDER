#include "Engine.h"

#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cmath>
#include <thread>

#include "../dsp/Denormal.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "grvr", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  "grvr", __VA_ARGS__)

namespace grvr {

namespace {
constexpr float kTwoPi = 6.283185307179586f;

const char* sharingName(oboe::SharingMode m) {
    return m == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared";
}
const char* perfName(oboe::PerformanceMode m) {
    switch (m) {
        case oboe::PerformanceMode::LowLatency: return "LowLatency";
        case oboe::PerformanceMode::PowerSaving: return "PowerSaving";
        default: return "None";
    }
}
} // namespace

Engine::Engine()  = default;
Engine::~Engine() { stop(); }

// ---------------------------------------------------------------- lifecycle

bool Engine::start() {
    std::lock_guard<std::mutex> lock(lifecycleLock_);
    if (stream_) return true;
    if (!openStream()) return false;
    stopFade_.store(1.0f, std::memory_order_release);
    running_.store(true, std::memory_order_release);
    return true;
}

void Engine::stop() {
    std::lock_guard<std::mutex> lock(lifecycleLock_);
    if (!stream_) return;

    // Fade out before closing. Cutting a live stream mid-cycle is a click,
    // and a click on every stop makes the whole app feel broken.
    stopFade_.store(0.0f, std::memory_order_release);
    std::this_thread::sleep_for(std::chrono::milliseconds(40));

    running_.store(false, std::memory_order_release);
    closeStream();
}

bool Engine::openStream() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setSampleRate(oboe::kUnspecified)          // take the device native rate
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    const oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK || !stream_) {
        LOGW("openStream failed: %s", oboe::convertToText(result));
        stream_.reset();
        return false;
    }

    sampleRate_  = static_cast<float>(stream_->getSampleRate());
    engineRate_.store(stream_->getSampleRate(), std::memory_order_release);
    burstFrames_ = stream_->getFramesPerBurst();
    if (burstFrames_ <= 0) burstFrames_ = 192;

    // Stability over latency (spec 8.1). A pad instrument does not notice
    // 20 ms; it very much notices an underrun.
    stream_->setBufferSizeInFrames(burstFrames_ * kBurstsAtStart);

    configureSmoothers(sampleRate_);
    phase_        = 0.0;
    lastXRun_     = 0;
    bufferGrows_  = 0;
    tuneCountdown_= 0;
    ftzDone_      = false;

    const oboe::Result startResult = stream_->requestStart();
    if (startResult != oboe::Result::OK) {
        LOGW("requestStart failed: %s", oboe::convertToText(startResult));
        closeStream();
        return false;
    }

    LOGI("stream open: %d Hz, burst %d, buffer %d, %s/%s, api=%s",
         stream_->getSampleRate(), burstFrames_, stream_->getBufferSizeInFrames(),
         sharingName(stream_->getSharingMode()), perfName(stream_->getPerformanceMode()),
         oboe::convertToText(stream_->getAudioApi()));
    return true;
}

void Engine::closeStream() {
    if (!stream_) return;
    stream_->requestStop();
    stream_->close();
    stream_.reset();
}

void Engine::configureSmoothers(float sr) noexcept {
    toneHz_.configure(0.050f, sr);      // 50 ms  (spec 2.8, pitch class)
    toneGain_.configure(0.020f, sr);    // 20 ms  (gain class)
    toneEnv_.configure(0.015f, sr);
    masterGain_.configure(0.020f, sr);
    fade_.configure(0.010f, sr);
    previewGain_.configure(0.020f, sr);
    previewGain_.snap(previewGainTarget_.load(std::memory_order_acquire));
    toneHz_.snap(220.0f);
    toneGain_.snap(0.25f);
    toneEnv_.snap(0.0f);
    masterGain_.snap(0.8f);
    fade_.snap(1.0f);

    grainEngine_.configure(sr);
    modEngine_.configure(static_cast<uint64_t>(pendingMasterSeed_.load(std::memory_order_relaxed)));
    outputStage_.configure(sr);
    outputWidth_.configure(0.020f, sr);
    outputGain_.configure(0.020f, sr);
    outputWidth_.snap(modEngine_.combinedOutputWidth());
    outputGain_.snap(0.9f);

    controlPeriodSamples_ = std::max(1, static_cast<int32_t>(sr / 1000.0f));
    controlCountdown_ = controlPeriodSamples_;

    captureRing_.configure(static_cast<int32_t>(sr));
}

// ---------------------------------------------------------------- audio thread

void Engine::applyPendingParams() noexcept {
    // Deferred from the UI thread: reseeding Lorenz/Drift touches state that
    // is only safe to mutate from the thread that also reads it (spec 8.3).
    if (masterSeedDirty_.exchange(false, std::memory_order_acq_rel)) {
        const auto seed = static_cast<uint64_t>(pendingMasterSeed_.load(std::memory_order_relaxed));
        grainEngine_.setMasterSeed(seed);
        modEngine_.setMasterSeed(seed);
    }
    switch (pendingChaosAction_.exchange(0, std::memory_order_acq_rel)) {
        case 1: modEngine_.loadFirstLight(); break;
        case 2: modEngine_.clearRoutes();    break;
        default: break;
    }

    ParamMsg msg;
    while (paramRing_.pop(msg)) {
        switch (msg.id) {
            case kToneEnabled: toneEnv_.setTarget(msg.value > 0.5f ? 1.0f : 0.0f); break;
            case kToneHz:      toneHz_.setTarget(msg.value);   break;
            case kToneGain:    toneGain_.setTarget(msg.value); break;
            case kMasterGain:  masterGain_.setTarget(msg.value); break;

            // Grain params set the *base* value; ModEngine::tick() layers
            // modulation on top each control tick and pushes the combined
            // value into the scheduler (spec 4.4). A route at depth 0 leaves
            // the base untouched, so this is bit-identical to the M2/M3 path.
            case kGrainDensity:      modEngine_.setBase(kDestDensity, msg.value); break;
            case kGrainTimingJitter: modEngine_.setBase(kDestTimingJitter, msg.value); break;
            case kGrainSizeMs:       modEngine_.setBase(kDestGrainSize, msg.value); break;
            case kGrainSizeJitter:   modEngine_.setBase(kDestSizeJitter, msg.value); break;
            case kGrainPosition:     modEngine_.setBase(kDestPosition, msg.value); break;
            case kGrainSprayMs:      modEngine_.setBase(kDestSprayMs, msg.value); break;
            case kGrainDrift:        modEngine_.setBase(kDestDrift, msg.value); break;
            case kGrainPitchSt:      modEngine_.setBase(kDestPitch, msg.value); break;
            case kGrainPitchSpraySt: modEngine_.setBase(kDestPitchSpray, msg.value); break;
            case kGrainReverseProb:  modEngine_.setBase(kDestReverseProb, msg.value); break;
            case kGrainSpread:       modEngine_.setBase(kDestSpread, msg.value); break;
            case kGrainWindowType:   grainEngine_.setWindowType(static_cast<uint16_t>(msg.value + 0.5f)); break;

            case kOutputWidth: modEngine_.setBase(kDestOutputWidth, msg.value); break;
            case kOutputGain:  outputGain_.setTarget(msg.value); break;
            case kChaosRate:   modEngine_.setBase(kDestChaosRate, msg.value); break;
            default: break;
        }
    }
}

oboe::DataCallbackResult Engine::onAudioReady(oboe::AudioStream* stream,
                                              void* audioData,
                                              int32_t numFrames) {
    const auto t0 = std::chrono::steady_clock::now();

    if (!ftzDone_) { enableFlushToZero(); ftzDone_ = true; }

    applyPendingParams();
    fade_.setTarget(stopFade_.load(std::memory_order_acquire));

    auto* out = static_cast<float*>(audioData);
    const float invSr = 1.0f / sampleRate_;

    float peakL = 0.0f, peakR = 0.0f;

    for (int32_t i = 0; i < numFrames; ++i) {
        const float hz = toneHz_.next();
        phase_ += static_cast<double>(hz * invSr);
        if (phase_ >= 1.0) phase_ -= 1.0;

        const float s = std::sin(kTwoPi * static_cast<float>(phase_))
                      * toneGain_.next() * toneEnv_.next()
                      * masterGain_.next() * fade_.next();

        out[i * 2]     = s;
        out[i * 2 + 1] = s;

        const float a = s < 0.0f ? -s : s;
        if (a > peakL) peakL = a;
    }
    peakR = peakL;   // mono test tone

    // --- source preview mixed on top (M1) ---
    renderPreview(out, numFrames, peakL, peakR);

    // --- grain cloud (M2). Rendered into its own scratch buffer so its
    // output stage (DC blocker / width / saturation) never touches the
    // diagnostic tone or preview paths, whose exact linearity the M0 tests
    // depend on.
    {
        const int32_t n = numFrames <= kMaxBlockFrames ? numFrames : kMaxBlockFrames;
        std::fill(grainMix_, grainMix_ + static_cast<size_t>(n) * 2, 0.0f);

        // Mod engine ticks at a fixed 1 kHz control rate (spec 4.3) regardless
        // of the audio callback's burst size, so render in control-period
        // chunks; the countdown persists across callbacks for phase continuity.
        int32_t offset = 0;
        while (offset < n) {
            if (controlCountdown_ <= 0) {
                modEngine_.tick(grainEngine_);
                outputWidth_.setTarget(modEngine_.combinedOutputWidth());
                controlCountdown_ = controlPeriodSamples_;
            }
            const int32_t chunk = std::min(controlCountdown_, n - offset);
            grainEngine_.renderBlock(grainMix_ + static_cast<size_t>(offset) * 2, chunk);
            controlCountdown_ -= chunk;
            offset += chunk;
        }

        for (int32_t i = 0; i < n; ++i) {
            const float width = outputWidth_.next();
            const float gain  = outputGain_.next();
            float gl = grainMix_[i * 2];
            float gr = grainMix_[i * 2 + 1];
            outputStage_.process(gl, gr, width, gain);
            out[i * 2]     += gl;
            out[i * 2 + 1] += gr;
            const float al = gl < 0.0f ? -gl : gl;
            const float ar = gr < 0.0f ? -gr : gr;
            if (al > peakL) peakL = al;
            if (ar > peakR) peakR = ar;

            // Always recording (spec 6.1): the ring holds exactly what the
            // final mixed output was, tone/preview/grain/output-stage all
            // included -- "what was actually heard".
            captureRing_.write(out[i * 2], out[i * 2 + 1]);
        }
    }

    grainEngine_.writeSnapshot(cloudSnapshot_.writeSlot());
    cloudSnapshot_.publish();

    peakL_ = peakL;
    peakR_ = peakR;

    // --- adaptive buffer sizing, checked occasionally rather than every block
    if (--tuneCountdown_ <= 0) {
        tuneCountdown_ = kTuneInterval;
        auto xr = stream->getXRunCount();
        if (xr) {
            const int32_t count = xr.value();
            if (count > lastXRun_) {
                lastXRun_ = count;
                const int32_t cur  = stream->getBufferSizeInFrames();
                const int32_t next = cur + burstFrames_;
                if (next <= burstFrames_ * kBurstsMax) {
                    stream->setBufferSizeInFrames(next);
                    ++bufferGrows_;
                }
            }
        }
    }

    const auto t1 = std::chrono::steady_clock::now();
    const double elapsedSec =
        std::chrono::duration_cast<std::chrono::duration<double>>(t1 - t0).count();
    const double budgetSec = static_cast<double>(numFrames) / sampleRate_;
    if (budgetSec > 0.0) {
        const float load = static_cast<float>(elapsedSec / budgetSec);
        cpuLoad_ += (load - cpuLoad_) * 0.1f;    // smoothed, so the readout is legible
    }

    Meters& m      = meters_.writeSlot();
    m.peakL        = peakL_;
    m.peakR        = peakR_;
    m.cpuLoad      = cpuLoad_;
    m.xruns        = lastXRun_;
    m.bufferFrames = stream->getBufferSizeInFrames();
    m.bufferGrows  = bufferGrows_;
    m.running      = 1;
    m.activeVoices = grainEngine_.activeVoices();
    auto latency   = stream->calculateLatencyMillis();
    m.latencyMs    = latency ? static_cast<float>(latency.value()) : 0.0f;
    meters_.publish();

    return oboe::DataCallbackResult::Continue;
}

// ---------------------------------------------------------------- recovery

void Engine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    // Called on an Oboe-owned thread after the stream is already closed --
    // headphones unplugged, Bluetooth connected, route changed.
    LOGW("stream error after close: %s", oboe::convertToText(error));

    bool expected = false;
    if (!restarting_.compare_exchange_strong(expected, true)) return;

    std::thread([this]() {
        // try_lock rather than lock: if the UI thread is deliberately stopping
        // us, we must not fight it back open.
        if (lifecycleLock_.try_lock()) {
            if (running_.load(std::memory_order_acquire)) {
                stream_.reset();
                if (openStream()) {
                    stopFade_.store(1.0f, std::memory_order_release);
                    LOGI("stream reopened after route change");
                } else {
                    LOGW("reopen failed");
                    running_.store(false, std::memory_order_release);
                }
            }
            lifecycleLock_.unlock();
        }
        restarting_.store(false, std::memory_order_release);
    }).detach();
}


// ---------------------------------------------------------------- source preview

void Engine::setSource(std::shared_ptr<SourceBuffer> buf) noexcept {
    // Called from the UI/IO thread. One-deep retirement: the buffer we drop
    // here is the one from two loads ago, whose last audio-thread reader has
    // long since moved on (loads are seconds apart, user-driven).
    std::lock_guard<std::mutex> lock(lifecycleLock_);
    sourceRetired_ = sourceHold_;
    sourceHold_    = std::move(buf);
    SourceBuffer* raw = sourceHold_ ? sourceHold_.get() : nullptr;
    previewPlaying_.store(0, std::memory_order_release);
    previewSeek_.store(0, std::memory_order_release);
    previewIn_.store(0, std::memory_order_release);
    previewOut_.store(raw ? raw->frames() : 0, std::memory_order_release);
    source_.store(raw, std::memory_order_release);
    grainEngine_.setSource(raw);
}

void Engine::previewSetRegion(int64_t inFrame, int64_t outFrame) noexcept {
    if (outFrame < inFrame) std::swap(inFrame, outFrame);
    previewIn_.store(inFrame < 0 ? 0 : inFrame, std::memory_order_release);
    previewOut_.store(outFrame < 0 ? 0 : outFrame, std::memory_order_release);
}

void Engine::renderPreview(float* out, int32_t numFrames, float& peakL, float& peakR) noexcept {
    SourceBuffer* src = source_.load(std::memory_order_acquire);
    const bool playing = previewPlaying_.load(std::memory_order_acquire) != 0;

    previewGain_.setTarget(previewGainTarget_.load(std::memory_order_acquire));

    if (src == nullptr || src->frames() == 0) {
        previewPosOut_.store(0, std::memory_order_release);
        return;
    }

    int64_t inP  = previewIn_.load(std::memory_order_acquire);
    int64_t outP = previewOut_.load(std::memory_order_acquire);
    const int64_t frames = src->frames();
    if (inP  < 0) inP = 0;  if (inP  > frames) inP = frames;
    if (outP < inP) outP = inP;  if (outP > frames) outP = frames;

    const int64_t seek = previewSeek_.load(std::memory_order_acquire);
    if (seek >= 0) {
        previewPos_ = static_cast<double>(seek < inP ? inP : (seek > outP ? outP : seek));
        previewSeek_.store(-1, std::memory_order_release);
    }
    if (previewPos_ < inP || previewPos_ > outP) previewPos_ = inP;

    const bool loop = previewLoop_.load(std::memory_order_acquire) != 0;
    const bool stereoSrc = src->channelCount() >= 2;
    const float fadeNow = fade_.current();  // follow the stop fade without advancing it twice

    if (!playing) {
        // hold position, publish, emit nothing
        previewPosOut_.store(static_cast<int64_t>(previewPos_), std::memory_order_release);
        return;
    }

    const int64_t region = outP - inP;
    for (int32_t i = 0; i < numFrames; ++i) {
        if (previewPos_ >= outP) {
            if (loop && region > 0) {
                previewPos_ = inP + std::fmod(previewPos_ - inP, static_cast<double>(region));
            } else {
                previewPlaying_.store(0, std::memory_order_release);
                break;
            }
        }
        const int64_t i0 = static_cast<int64_t>(previewPos_);
        const float g = previewGain_.next() * fadeNow;
        const float l = src->sample(0, i0) * g;
        const float r = (stereoSrc ? src->sample(1, i0) : src->sample(0, i0)) * g;
        out[i * 2]     += l;
        out[i * 2 + 1] += r;
        const float al = l < 0 ? -l : l, ar = r < 0 ? -r : r;
        if (al > peakL) peakL = al;
        if (ar > peakR) peakR = ar;
        previewPos_ += 1.0;   // rate 1.0: source is already at engine rate
    }
    previewPosOut_.store(static_cast<int64_t>(previewPos_), std::memory_order_release);
}

// ---------------------------------------------------------------- device profiling (M8)

int32_t Engine::profileAndSetVoiceCap() {
    constexpr float kSr = 48000.0f;
    constexpr int32_t kProfileFrames = static_cast<int32_t>(kSr);   // 1 s of synthetic audio

    // A short synthetic noise source and a worst-case-ish patch: dense,
    // wide, pitched -- close to what 256 real voices actually costs.
    std::vector<std::vector<float>> ch(1, std::vector<float>(4096));
    uint32_t rngState = 0x2545F491u;
    for (auto& v : ch[0]) {
        rngState ^= rngState << 13; rngState ^= rngState >> 17; rngState ^= rngState << 5;
        v = (static_cast<float>(rngState) / 4294967295.0f) * 2.0f - 1.0f;
    }
    SourceBuffer bench(std::move(ch), static_cast<int32_t>(kSr));

    GrainScheduler sched;
    sched.configure(kSr);
    sched.setSource(&bench);
    sched.setMaxVoices(kMaxGrains);
    sched.setDensity(200.0f);
    sched.setGrainSizeMs(1200.0f);
    sched.setSpread(1.0f);
    sched.setPitchSpraySemitones(12.0f);
    sched.setReverseProb(0.5f);

    std::vector<float> buf(192 * 2);
    const auto t0 = std::chrono::steady_clock::now();
    for (int32_t done = 0; done < kProfileFrames; done += 192) {
        std::fill(buf.begin(), buf.end(), 0.0f);
        sched.renderBlock(buf.data(), 192);
    }
    const auto t1 = std::chrono::steady_clock::now();
    const double elapsedSec =
        std::chrono::duration_cast<std::chrono::duration<double>>(t1 - t0).count();
    const double loadFraction = elapsedSec / 1.0;   // rendered exactly 1s of audio

    // Thresholds per spec 8.4's device-class targets (<15% flagship, <35% mid-tier).
    int32_t cap = 64;
    if (loadFraction < 0.15) cap = kMaxGrains;
    else if (loadFraction < 0.35) cap = 128;

    grainEngine_.setMaxVoices(cap);
    LOGI("device profile: %.1f%% CPU at 256 voices -> voice cap %d", loadFraction * 100.0, cap);
    return cap;
}

// ---------------------------------------------------------------- reporting

std::string Engine::configDescription() {
    std::lock_guard<std::mutex> lock(lifecycleLock_);
    if (!stream_) return "stream closed";

    char buf[320];
    snprintf(buf, sizeof(buf),
             "%s | %d Hz | %d ch | burst %d | buffer %d | cap %d | %s | %s",
             oboe::convertToText(stream_->getAudioApi()),
             stream_->getSampleRate(),
             stream_->getChannelCount(),
             stream_->getFramesPerBurst(),
             stream_->getBufferSizeInFrames(),
             stream_->getBufferCapacityInFrames(),
             sharingName(stream_->getSharingMode()),
             perfName(stream_->getPerformanceMode()));
    return std::string(buf);
}

} // namespace grvr
