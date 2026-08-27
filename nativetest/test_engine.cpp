#include "engine/Engine.h"
#include "engine/GrainScheduler.h"
#include "engine/ModEngine.h"
#include "engine/OfflineRenderer.h"
#include "mod/Lorenz.h"
#include "io/ParamRing.h"
#include "io/SourceBuffer.h"
#include "io/TripleBuffer.h"
#include "dsp/OutputStage.h"
#include "dsp/Smoother.h"
#include "dsp/Window.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <random>
#include <thread>
#include <vector>

using namespace grvr;
static int failures = 0;
static void check(bool ok, const char* name, const char* detail="") {
    printf("%-52s %s %s\n", name, ok ? "PASS" : "**FAIL**", detail);
    if (!ok) ++failures;
}

// ---------------------------------------------------- 1. ParamRing SPSC
static void testParamRing() {
    ParamRing<256> ring;
    constexpr int N = 200000;
    std::atomic<bool> go{false};
    std::vector<float> got; got.reserve(N);

    std::thread producer([&]{
        while(!go.load()) {}
        for (int i = 0; i < N; ) {
            if (ring.push(1, (float)i)) ++i;       // retry on full
        }
    });
    std::thread consumer([&]{
        while(!go.load()) {}
        ParamMsg m;
        while ((int)got.size() < N) { if (ring.pop(m)) got.push_back(m.value); }
    });
    go.store(true);
    producer.join(); consumer.join();

    bool ordered = true;
    for (int i = 0; i < N; ++i) if (got[i] != (float)i) { ordered = false; break; }
    check(ordered && (int)got.size() == N, "ParamRing: 200k msgs, FIFO order, no loss");

    // full-ring behaviour: push must fail, never corrupt
    ParamRing<4> tiny; int accepted = 0;
    for (int i = 0; i < 10; ++i) if (tiny.push(0, (float)i)) ++accepted;
    check(accepted == 3, "ParamRing: refuses overflow (capacity N-1)");
}

// ---------------------------------------------------- 2. TripleBuffer tearing
struct Wide { int a[16]; };
static void testTripleBuffer() {
    TripleBuffer<Wide> tb;
    std::atomic<bool> stop{false};
    std::atomic<int> torn{0}, reads{0};

    std::thread writer([&]{
        int v = 0;
        while (!stop.load()) {
            Wide& w = tb.writeSlot();
            for (int i = 0; i < 16; ++i) w.a[i] = v;
            tb.publish();
            ++v;
        }
    });
    std::thread reader([&]{
        Wide w{};
        while (!stop.load()) {
            tb.read(w);
            for (int i = 1; i < 16; ++i) if (w.a[i] != w.a[0]) { ++torn; break; }
            ++reads;
        }
    });
    std::this_thread::sleep_for(std::chrono::milliseconds(400));
    stop.store(true); writer.join(); reader.join();
    char d[64]; snprintf(d, sizeof d, "(%d reads)", reads.load());
    check(torn.load() == 0, "TripleBuffer: no torn reads under contention", d);
}

// ---------------------------------------------------- 3. Smoother
static void testSmoother() {
    Smoother s; s.configure(0.020f, 48000.0f); s.snap(0.0f); s.setTarget(1.0f);
    for (int i = 0; i < 960; ++i) s.next();          // 20 ms == 1 tau
    const float afterTau = s.current();
    check(afterTau > 0.60f && afterTau < 0.68f, "Smoother: reaches ~63% after one tau");
    for (int i = 0; i < 48000; ++i) s.next();
    check(std::fabs(s.current() - 1.0f) < 1e-4f, "Smoother: settles at target");
    Smoother z; z.configure(0.0f, 48000.0f); z.snap(0.0f); z.setTarget(1.0f);
    check(z.next() == 1.0f, "Smoother: zero tau degrades to instant, no NaN");
}

// ---------------------------------------------------- 4. Engine render
static void renderBlocks(Engine& e, oboe::AudioStream* s, std::vector<float>& out,
                         int blocks, int frames) {
    std::vector<float> buf(frames * 2);
    for (int b = 0; b < blocks; ++b) {
        e.onAudioReady(s, buf.data(), frames);
        out.insert(out.end(), buf.begin(), buf.end());
    }
}
static float maxStep(const std::vector<float>& x) {
    float m = 0.0f;
    for (size_t i = 2; i < x.size(); i += 2) {
        const float d = std::fabs(x[i] - x[i-2]);
        if (d > m) m = d;
    }
    return m;
}

static void testEngine() {
    Engine e;
    oboe::AudioStream fake;             // shim stream: 48 kHz, burst 192
    e.start();                          // opens the shim stream internally
    const int F = 192;

    // -- silence before the tone is enabled
    std::vector<float> pre; renderBlocks(e, &fake, pre, 20, F);
    float prePeak = 0; for (float v : pre) prePeak = std::max(prePeak, std::fabs(v));
    check(prePeak < 1e-6f, "Engine: silent until the tone is enabled");

    // -- enable, let the envelope settle, then measure
    e.setParam(kToneEnabled, 1.0f);
    e.setParam(kToneHz, 440.0f);
    e.setParam(kToneGain, 0.5f);
    e.setParam(kMasterGain, 1.0f);
    std::vector<float> warm; renderBlocks(e, &fake, warm, 60, F);   // ~240 ms

    std::vector<float> tone; renderBlocks(e, &fake, tone, 250, F);  // ~1 s
    float peak = 0; for (float v : tone) peak = std::max(peak, std::fabs(v));
    char d1[64]; snprintf(d1, sizeof d1, "(peak %.3f, expected 0.500)", peak);
    check(std::fabs(peak - 0.5f) < 0.01f, "Engine: output level matches gain", d1);

    // -- frequency accuracy from zero crossings (left channel)
    int crossings = 0;
    for (size_t i = 2; i < tone.size(); i += 2)
        if ((tone[i-2] <= 0.0f) != (tone[i] <= 0.0f)) ++crossings;
    const double seconds = (double)(tone.size()/2) / 48000.0;
    const double hz = (crossings / 2.0) / seconds;
    char d2[64]; snprintf(d2, sizeof d2, "(measured %.2f Hz)", hz);
    check(std::fabs(hz - 440.0) < 1.0, "Engine: renders the requested frequency", d2);

    // -- the M0 acceptance claim: hard frequency sweep must not zipper.
    // A discontinuity shows up as a sample-to-sample step far larger than the
    // largest step a clean 20 kHz sine could produce at this amplitude.
    std::vector<float> sweep;
    for (int step = 0; step < 200; ++step) {
        const float f = 100.0f + (float)step * 15.0f;   // 100 Hz -> ~3.1 kHz, fast
        e.setParam(kToneHz, f);
        renderBlocks(e, &fake, sweep, 1, F);
    }
    const float worst = maxStep(sweep);
    const float ceiling = 0.5f * 2.0f * 3.14159f * 3100.0f / 48000.0f * 1.6f;
    char d3[80]; snprintf(d3, sizeof d3, "(max step %.4f, ceiling %.4f)", worst, ceiling);
    check(worst < ceiling, "Engine: hard frequency sweep produces no zipper", d3);

    // -- gain step must not click either
    std::vector<float> gstep;
    e.setParam(kToneHz, 220.0f); renderBlocks(e, &fake, gstep, 30, F);
    gstep.clear();
    e.setParam(kToneGain, 0.0f); renderBlocks(e, &fake, gstep, 4, F);
    e.setParam(kToneGain, 0.9f); renderBlocks(e, &fake, gstep, 4, F);
    const float gWorst = maxStep(gstep);
    char d4[80]; snprintf(d4, sizeof d4, "(max step %.4f)", gWorst);
    check(gWorst < 0.05f, "Engine: abrupt gain change is smoothed, not clicked", d4);

    // -- DC offset
    double sum = 0; for (size_t i = 0; i < tone.size(); i += 2) sum += tone[i];
    const double dc = sum / (double)(tone.size()/2);
    char d5[64]; snprintf(d5, sizeof d5, "(DC %.2e)", dc);
    check(std::fabs(dc) < 1e-3, "Engine: no DC offset in output", d5);

    // -- stereo channels identical (mono tone in M0)
    bool stereoOk = true;
    for (size_t i = 0; i + 1 < tone.size(); i += 2) if (tone[i] != tone[i+1]) { stereoOk = false; break; }
    check(stereoOk, "Engine: both channels written");

    e.stop();
}

// ---------------------------------------------------- 5. Grain engine (M2)

static SourceBuffer makeNoiseSource(int64_t frames, int32_t sampleRate, uint32_t seed) {
    std::mt19937 rng(seed);
    std::uniform_real_distribution<float> dist(-1.0f, 1.0f);
    std::vector<float> ch(static_cast<size_t>(frames));
    for (auto& v : ch) v = dist(rng);
    return SourceBuffer({ch}, sampleRate);
}

static SourceBuffer makeToneSource(int64_t frames, int32_t sampleRate, float hz) {
    std::vector<float> ch(static_cast<size_t>(frames));
    for (int64_t i = 0; i < frames; ++i)
        ch[static_cast<size_t>(i)] = std::sin(2.0f * static_cast<float>(M_PI) * hz *
                                               static_cast<float>(i) / static_cast<float>(sampleRate));
    return SourceBuffer({ch}, sampleRate);
}

static float rmsOf(const std::vector<float>& interleaved) {
    double sum = 0.0;
    for (float v : interleaved) sum += static_cast<double>(v) * v;
    return static_cast<float>(std::sqrt(sum / std::max<size_t>(1, interleaved.size())));
}

static float dbfs(float linear) {
    return 20.0f * std::log10(std::max(linear, 1e-9f));
}

static std::vector<float> renderGrains(GrainScheduler& sched, int32_t sampleRate, float seconds,
                                       int32_t block = 192) {
    std::vector<float> out;
    const int32_t totalFrames = static_cast<int32_t>(seconds * static_cast<float>(sampleRate));
    std::vector<float> buf(static_cast<size_t>(block) * 2);
    for (int32_t done = 0; done < totalFrames; done += block) {
        const int32_t n = std::min(block, totalFrames - done);
        std::fill(buf.begin(), buf.begin() + n * 2, 0.0f);
        sched.renderBlock(buf.data(), n);
        out.insert(out.end(), buf.begin(), buf.begin() + n * 2);
    }
    return out;
}

static void testWindowTables() {
    WindowSet windows;
    const char* names[] = {"Gaussian", "Tukey", "Hann"};
    for (uint16_t w = 0; w < kWindowCount; ++w) {
        char n0[80]; snprintf(n0, sizeof n0, "Window %s: exactly 0.0 at phase 0", names[w]);
        check(windows.sample(w, 0.0f) == 0.0f, n0);
        char n1[80]; snprintf(n1, sizeof n1, "Window %s: exactly 0.0 at phase 1", names[w]);
        check(windows.sample(w, 1.0f) == 0.0f, n1);
    }
}

static void testDensityLevelTrend() {
    constexpr int32_t sr = 48000;
    SourceBuffer noise = makeNoiseSource(sr * 2, sr, 12345);

    auto measureAt = [&](float density) {
        double sumDb = 0.0;
        constexpr int seeds = 8;
        for (int s = 0; s < seeds; ++s) {
            GrainScheduler sched;
            sched.configure(sr);
            sched.setSource(&noise);
            sched.setMasterSeed(1000 + static_cast<uint64_t>(s));
            sched.setDensity(density);
            sched.setGrainSizeMs(400.0f);
            sched.setPitchSpraySemitones(0.15f);
            sched.setDrift(0.0f);
            sched.setSprayMs(250.0f);
            auto audio = renderGrains(sched, sr, 1.0f);
            const size_t skip = static_cast<size_t>(sr * 0.2) * 2;   // let smoothers settle
            std::vector<float> steady(audio.begin() + std::min(skip, audio.size()), audio.end());
            sumDb += dbfs(rmsOf(steady));
        }
        return static_cast<float>(sumDb / seeds);
    };

    const float trend = std::fabs(measureAt(200.0f) - measureAt(5.0f));
    char detail[64]; snprintf(detail, sizeof detail, "(trend %.2f dB)", trend);
    check(trend < 3.0f, "Grain: density sweep 5->200/s trends <3 dB on noise (gain comp)", detail);
}

static void testPitchSprayDecorrelation() {
    constexpr int32_t sr = 48000;
    SourceBuffer tone = makeToneSource(sr * 2, sr, 220.0f);
    constexpr int seeds = 8;

    auto trendFor = [&](float pitchSpray) {
        auto levelAt = [&](float density) {
            double sumDb = 0.0;
            for (int s = 0; s < seeds; ++s) {
                GrainScheduler sched;
                sched.configure(sr);
                sched.setSource(&tone);
                sched.setMasterSeed(500 + static_cast<uint64_t>(s));
                sched.setDensity(density);
                sched.setGrainSizeMs(400.0f);
                sched.setPitchSpraySemitones(pitchSpray);
                sched.setDrift(0.0f);
                // Keep the pad-default spray (positional decorrelation) so
                // pitch is the only axis being isolated -- zeroing it too
                // would confound the comparison by removing the other
                // decorrelator entirely.
                sched.setSprayMs(250.0f);
                auto audio = renderGrains(sched, sr, 1.0f);
                const size_t skip = static_cast<size_t>(sr * 0.2) * 2;
                std::vector<float> steady(audio.begin() + std::min(skip, audio.size()), audio.end());
                sumDb += dbfs(rmsOf(steady));
            }
            return static_cast<float>(sumDb / seeds);
        };
        return std::fabs(levelAt(200.0f) - levelAt(5.0f));
    };

    const float trendNoSpray   = trendFor(0.0f);
    const float trendWithSpray = trendFor(0.15f);
    char detail[96];
    snprintf(detail, sizeof detail, "(noSpray %.2f dB, withSpray %.2f dB)", trendNoSpray, trendWithSpray);
    check(trendNoSpray > trendWithSpray,
          "Grain: zero pitchSpray shows a larger density trend than default", detail);
}

static void testSpreadSurvivesMonoSum() {
    constexpr int32_t sr = 48000;
    SourceBuffer noise = makeNoiseSource(sr * 2, sr, 777);
    GrainScheduler sched;
    sched.configure(sr);
    sched.setSource(&noise);
    sched.setMasterSeed(9);
    sched.setDensity(40.0f);
    sched.setGrainSizeMs(400.0f);
    sched.setSpread(1.0f);
    sched.setSprayMs(0.0f);
    sched.setDrift(0.0f);
    auto audio = renderGrains(sched, sr, 1.0f);

    double sumL = 0.0, sumR = 0.0, sumMono = 0.0;
    const size_t frames = audio.size() / 2;
    for (size_t i = 0; i < frames; ++i) {
        const float l = audio[i * 2], r = audio[i * 2 + 1];
        sumL += static_cast<double>(l) * l;
        sumR += static_cast<double>(r) * r;
        const float m = (l + r) * 0.5f;
        sumMono += static_cast<double>(m) * m;
    }
    const float rmsL = static_cast<float>(std::sqrt(sumL / static_cast<double>(frames)));
    const float rmsR = static_cast<float>(std::sqrt(sumR / static_cast<double>(frames)));
    const float rmsMono = static_cast<float>(std::sqrt(sumMono / static_cast<double>(frames)));
    const float avgStereo = (rmsL + rmsR) * 0.5f;
    char detail[96]; snprintf(detail, sizeof detail, "(avgStereo %.4f, mono %.4f)", avgStereo, rmsMono);
    // A comb-filtered collapse would crater the mono sum far below the stereo
    // average; a healthy decorrelated cloud loses only a few dB (spec 2.7).
    check(rmsMono > avgStereo * 0.35f, "Grain: spread=1.0 on mono source survives mono summing", detail);
}

static void testReverseDcOffset() {
    constexpr int32_t sr = 48000;
    SourceBuffer noise = makeNoiseSource(sr * 2, sr, 55);
    GrainScheduler sched;
    sched.configure(sr);
    sched.setSource(&noise);
    sched.setMasterSeed(3);
    sched.setDensity(40.0f);
    sched.setGrainSizeMs(300.0f);
    sched.setReverseProb(1.0f);
    sched.setSprayMs(0.0f);
    sched.setDrift(0.0f);
    auto audio = renderGrains(sched, sr, 2.0f);

    // The DC blocker lives in OutputStage, not in the raw grain mix (spec
    // 2.9's "Output DC offset" is measured post output-stage, same as Engine
    // applies it) -- run the cloud through one here to match.
    OutputStage stage;
    stage.configure(static_cast<float>(sr));
    const size_t frames = audio.size() / 2;
    for (size_t i = 0; i < frames; ++i) {
        stage.process(audio[i * 2], audio[i * 2 + 1], 1.0f, 1.0f);
    }

    double sum = 0.0;
    for (size_t i = 0; i < frames; ++i) sum += audio[i * 2];
    const double dc = sum / static_cast<double>(std::max<size_t>(1, frames));
    char detail[64];
    snprintf(detail, sizeof detail, "(DC %.2e = %.1f dBFS)", dc, dbfs(static_cast<float>(std::fabs(dc))));
    check(std::fabs(dc) < 1e-4, "Grain: reverseProb=1.0 keeps DC offset below -80 dBFS post output-stage", detail);
}

static void testWindowTypeLevelMatch() {
    constexpr int32_t sr = 48000;
    SourceBuffer noise = makeNoiseSource(sr * 2, sr, 321);

    auto levelFor = [&](uint16_t windowType) {
        GrainScheduler sched;
        sched.configure(sr);
        sched.setSource(&noise);
        sched.setMasterSeed(7);
        sched.setDensity(40.0f);
        sched.setGrainSizeMs(400.0f);
        sched.setSprayMs(0.0f);
        sched.setDrift(0.0f);
        sched.setWindowType(windowType);
        auto audio = renderGrains(sched, sr, 1.0f);
        const size_t skip = static_cast<size_t>(sr * 0.2) * 2;
        std::vector<float> steady(audio.begin() + std::min(skip, audio.size()), audio.end());
        return dbfs(rmsOf(steady));
    };

    const float gDb = levelFor(kWindowGaussian);
    const float tDb = levelFor(kWindowTukey);
    const float hDb = levelFor(kWindowHann);
    const float spreadDb = std::max({gDb, tDb, hDb}) - std::min({gDb, tDb, hDb});
    char detail[96];
    snprintf(detail, sizeof detail, "(Gaussian %.2f, Tukey %.2f, Hann %.2f dBFS)", gDb, tDb, hDb);
    check(spreadDb < 1.5f, "Grain: switching window type holds level within ~1.5 dB", detail);
}

static void testVoiceCap() {
    constexpr int32_t sr = 48000;
    SourceBuffer noise = makeNoiseSource(sr * 2, sr, 99);
    GrainScheduler sched;
    sched.configure(sr);
    sched.setSource(&noise);
    sched.setMasterSeed(1);
    sched.setDensity(200.0f);
    sched.setGrainSizeMs(2000.0f);   // the extreme corner from spec 2.5
    sched.setSprayMs(0.0f);
    sched.setDrift(0.0f);
    (void)renderGrains(sched, sr, 3.0f);
    check(sched.activeVoices() <= kMaxGrains, "Grain: voice count never exceeds MAX_GRAINS at extreme density/size");
}

static void testGrainSizeSweepNoDropout() {
    constexpr int32_t sr = 48000;
    SourceBuffer noise = makeNoiseSource(sr * 2, sr, 5);
    GrainScheduler sched;
    sched.configure(sr);
    sched.setSource(&noise);
    sched.setMasterSeed(1);
    sched.setDensity(40.0f);
    sched.setSprayMs(0.0f);
    sched.setDrift(0.0f);

    std::vector<float> buf(192 * 2);
    bool anyBad = false;
    for (int step = 0; step < 400; ++step) {
        sched.setGrainSizeMs(5.0f + static_cast<float>(step) * 5.0f);   // 5 -> 2005 ms
        std::fill(buf.begin(), buf.end(), 0.0f);
        sched.renderBlock(buf.data(), 192);
        for (float v : buf) if (std::isnan(v) || std::isinf(v)) anyBad = true;
    }
    check(!anyBad, "Grain: grainSize sweep 5->2000ms produces no NaN/Inf dropout");
}

// ---------------------------------------------------- 6. Determinism (M3)
// The one non-negotiable check in the project (spec risk register, 9/M3).

static void testBitIdenticalRender() {
    constexpr int32_t sr = 48000;
    SourceBuffer noise = makeNoiseSource(sr * 2, sr, 4242);

    auto configureStandard = [&](GrainScheduler& sched) {
        sched.configure(sr);
        sched.setSource(&noise);
        sched.setMasterSeed(123456789ULL);
        sched.setDensity(60.0f);
        sched.setGrainSizeMs(300.0f);
        sched.setSizeJitter(0.25f);
        sched.setTimingJitter(0.2f);
        sched.setPosition(0.4f);
        sched.setSprayMs(200.0f);
        sched.setDrift(0.1f);
        sched.setPitchSemitones(2.0f);
        sched.setPitchSpraySemitones(0.3f);
        sched.setReverseProb(0.3f);
        sched.setSpread(0.9f);
    };

    GrainScheduler a; configureStandard(a);
    GrainScheduler b; configureStandard(b);
    auto audioA = renderGrains(a, sr, 1.0f, 192);
    auto audioB = renderGrains(b, sr, 1.0f, 192);
    const bool sameRun = audioA.size() == audioB.size() &&
        std::memcmp(audioA.data(), audioB.data(), audioA.size() * sizeof(float)) == 0;
    check(sameRun, "Determinism: same Seed rendered twice is bit-identical (memcmp)");

    GrainScheduler c; configureStandard(c);
    GrainScheduler d; configureStandard(d);
    GrainScheduler e; configureStandard(e);
    auto audio96  = renderGrains(c, sr, 1.0f, 96);
    auto audio384 = renderGrains(d, sr, 1.0f, 384);
    auto audio960 = renderGrains(e, sr, 1.0f, 960);
    const bool blockInvariant =
        audio96.size()  == audioA.size() && std::memcmp(audio96.data(),  audioA.data(), audioA.size()*sizeof(float)) == 0 &&
        audio384.size() == audioA.size() && std::memcmp(audio384.data(), audioA.data(), audioA.size()*sizeof(float)) == 0 &&
        audio960.size() == audioA.size() && std::memcmp(audio960.data(), audioA.data(), audioA.size()*sizeof(float)) == 0;
    check(blockInvariant, "Determinism: bit-identical at block sizes 96/384/960 vs 192");
}

static void testPanIndependentOfPitchSpray() {
    constexpr int32_t sr = 48000;
    SourceBuffer noise = makeNoiseSource(sr * 2, sr, 1);

    auto capturePans = [&](float pitchSpray) {
        GrainScheduler sched;
        sched.configure(sr);
        sched.setSource(&noise);
        sched.setMasterSeed(777);
        // Density is soft-limited to MAX_GRAINS/grainSizeSec (spec 2.5) --
        // 2000ms caps an ask of 200/s down to 128/s, so budget render time
        // (not the raw density request) against that effective rate, well
        // under the 2 s grain life so nothing has died when we sample.
        sched.setDensity(200.0f);
        sched.setGrainSizeMs(2000.0f);
        sched.setSprayMs(0.0f);
        sched.setDrift(0.0f);
        sched.setPitchSpraySemitones(pitchSpray);
        renderGrains(sched, sr, 1.0f);   // ~128 spawns expected at the capped rate
        std::vector<float> pans;
        const int32_t n = std::min<int32_t>(100, sched.activeVoices());
        for (int32_t i = 0; i < n; ++i) pans.push_back(sched.grainAt(i).panL);
        return pans;
    };

    const auto pansLow  = capturePans(0.0f);
    const auto pansHigh = capturePans(24.0f);   // extreme change
    bool identical = pansLow.size() == 100 && pansLow.size() == pansHigh.size();
    for (size_t i = 0; identical && i < pansLow.size(); ++i)
        if (pansLow[i] != pansHigh[i]) identical = false;
    char detail[64]; snprintf(detail, sizeof detail, "(captured %zu grains)", pansLow.size());
    check(identical, "Determinism: changing pitchSpray leaves pan (grains 0-99) unchanged", detail);
}

// ---------------------------------------------------- 7. Modulation & chaos (M4)

static void testLorenzStability() {
    Lorenz lorenz;
    lorenz.seed(999);
    bool bad = false;
    float maxAbs = 0.0f;
    // 60 simulated minutes at the 1 kHz control rate.
    constexpr int64_t ticks = 60LL * 60LL * 1000LL;
    for (int64_t i = 0; i < ticks; ++i) {
        lorenz.step(0.5f);
        const float x = lorenz.rawX(), y = lorenz.rawY(), z = lorenz.rawZ();
        if (std::isnan(x) || std::isnan(y) || std::isnan(z) ||
            std::isinf(x) || std::isinf(y) || std::isinf(z)) { bad = true; break; }
        maxAbs = std::max({maxAbs, std::fabs(x), std::fabs(y), std::fabs(z)});
        // The reset guard caps excursions at 100; anything above that escaped it.
        if (maxAbs > 100.0f) { bad = true; break; }
    }
    char detail[64]; snprintf(detail, sizeof detail, "(%lld ticks, max |state| %.1f)",
                              static_cast<long long>(ticks), maxAbs);
    check(!bad, "Lorenz: 60 simulated minutes, no NaN, no divergence", detail);
}

static void testChaosRateTimescale() {
    const float dtMin = Lorenz::dtForRate(0.0f);
    const float dtMax = Lorenz::dtForRate(1.0f);
    char detail[64]; snprintf(detail, sizeof detail, "(dtMin %.6f, dtMax %.6f)", dtMin, dtMax);
    // At the low end a single orbit should take minutes: dt this small means
    // thousands of 1kHz ticks (i.e. seconds of wall time) per unit of Lorenz
    // "time", where a full orbit is on the order of tens of time-units.
    check(dtMin > 0.0f && dtMin < 0.0001f && dtMax > 0.001f && dtMax <= 0.005f,
          "ModEngine: chaosRate maps dt across the spec'd 0.00002-0.005 range", detail);
}

static void testModDepthZeroBitIdentical() {
    constexpr int32_t sr = 48000;
    SourceBuffer noise = makeNoiseSource(sr, sr, 88);

    auto render = [&](bool loadRoutesButZeroDepth) {
        ModEngine mod;
        mod.configure(1234);
        if (loadRoutesButZeroDepth) {
            mod.loadFirstLight();
            for (int i = 0; i < kModMatrixSlots; ++i) {
                ModRoute r = mod.route(i);
                if (r.active) { r.depth = 0.0f; mod.setRoute(i, r); }
            }
        } else {
            mod.clearRoutes();
        }

        GrainScheduler sched;
        sched.configure(sr);
        sched.setSource(&noise);
        sched.setMasterSeed(1234);

        std::vector<float> out;
        constexpr int32_t totalFrames = sr;      // 1 s
        const int32_t controlPeriod = std::max(1, sr / 1000);
        int32_t countdown = controlPeriod;
        int32_t offset = 0;
        while (offset < totalFrames) {
            if (countdown <= 0) { mod.tick(sched); countdown = controlPeriod; }
            const int32_t chunk = std::min({countdown, totalFrames - offset, 192});
            std::vector<float> buf(static_cast<size_t>(chunk) * 2, 0.0f);
            sched.renderBlock(buf.data(), chunk);
            out.insert(out.end(), buf.begin(), buf.end());
            countdown -= chunk;
            offset += chunk;
        }
        return out;
    };

    const auto withZeroDepthRoutes = render(true);
    const auto withNoRoutes = render(false);
    const bool identical = withZeroDepthRoutes.size() == withNoRoutes.size() &&
        std::memcmp(withZeroDepthRoutes.data(), withNoRoutes.data(),
                    withZeroDepthRoutes.size() * sizeof(float)) == 0;
    check(identical, "ModMatrix: depth=0 routes render bit-identical to no routing");
}

// ---------------------------------------------------- 8. Capture & render (M6)

static RenderRequest standardRequest() {
    RenderRequest req;
    req.masterSeed = 555111;
    req.density = 50.0f;
    req.grainSizeMs = 250.0f;
    req.sprayMs = 150.0f;
    req.pitchSpraySt = 0.3f;
    req.reverseProb = 0.25f;
    req.spread = 0.9f;
    req.durationSeconds = 1.0;
    return req;
}

static void testOfflineRenderBasics() {
    constexpr int32_t dstRate = 48000;
    SourceBuffer noise = makeNoiseSource(dstRate * 2, dstRate, 321);
    RenderRequest req = standardRequest();

    const auto audio = OfflineRenderer::render(noise, req, dstRate);
    const size_t expectedFrames = static_cast<size_t>(req.durationSeconds * dstRate);
    const size_t frames = audio.size() / 2;
    char detail[64];
    snprintf(detail, sizeof detail, "(%zu frames, expected ~%zu)", frames, expectedFrames);
    // Resampling rounds to the nearest frame; allow a handful of frames slack.
    const bool durationOk = frames > 0 &&
        std::llabs(static_cast<long long>(frames) - static_cast<long long>(expectedFrames)) < 8;

    bool anyBad = false;
    for (float v : audio) if (std::isnan(v) || std::isinf(v)) anyBad = true;

    check(durationOk && !anyBad, "OfflineRenderer: correct duration, no NaN/Inf", detail);
}

static void testOfflineRenderDeterministic() {
    constexpr int32_t dstRate = 48000;
    SourceBuffer noise = makeNoiseSource(dstRate * 2, dstRate, 321);
    RenderRequest req = standardRequest();

    const auto a = OfflineRenderer::render(noise, req, dstRate);
    const auto b = OfflineRenderer::render(noise, req, dstRate);
    const bool identical = a.size() == b.size() &&
        std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) == 0;
    check(identical, "OfflineRenderer: same request rendered twice is bit-identical");
}

static void testSeamlessLoopJoin() {
    constexpr int32_t dstRate = 48000;
    SourceBuffer noise = makeNoiseSource(dstRate * 2, dstRate, 42);
    RenderRequest req = standardRequest();
    req.durationSeconds = 2.0;
    req.seamlessLoop = true;
    req.crossfadeSeconds = 0.08;

    const auto audio = OfflineRenderer::render(noise, req, dstRate);
    const size_t frames = audio.size() / 2;

    // The join step (last frame -> first frame, wrapping) should not stand
    // out from the ordinary sample-to-sample steps found throughout the take.
    auto stepAt = [&](size_t i0, size_t i1) {
        return std::fabs(audio[i0 * 2] - audio[i1 * 2]);
    };
    double sumSteps = 0.0;
    for (size_t i = 1; i < frames; ++i) sumSteps += stepAt(i - 1, i);
    const double meanStep = sumSteps / static_cast<double>(frames - 1);
    const double joinStep = stepAt(frames - 1, 0);

    char detail[80];
    snprintf(detail, sizeof detail, "(join step %.5f, mean step %.5f)", joinStep, meanStep);
    check(joinStep < meanStep * 20.0 + 1e-4, "OfflineRenderer: seamless loop join has no seam spike", detail);
}

// ---------------------------------------------------- 9. Adaptive voice cap (M8)

static void testAdaptiveVoiceCap() {
    Engine e;
    oboe::AudioStream fake;
    e.start();
    const int32_t cap = e.profileAndSetVoiceCap();
    char detail[48]; snprintf(detail, sizeof detail, "(cap=%d)", cap);
    check(cap == 64 || cap == 128 || cap == 256, "DeviceProfile: voice cap lands on a valid tier", detail);
    e.stop();
}

int main() {
    printf("\n== Grooverider M0 engine checks ==\n\n");
    testParamRing();
    testTripleBuffer();
    testSmoother();
    testEngine();

    printf("\n== Grooverider M2 grain engine checks ==\n\n");
    testWindowTables();
    testDensityLevelTrend();
    testPitchSprayDecorrelation();
    testSpreadSurvivesMonoSum();
    testReverseDcOffset();
    testWindowTypeLevelMatch();
    testVoiceCap();
    testGrainSizeSweepNoDropout();

    printf("\n== Grooverider M3 determinism checks ==\n\n");
    testBitIdenticalRender();
    testPanIndependentOfPitchSpray();

    printf("\n== Grooverider M4 modulation & chaos checks ==\n\n");
    testLorenzStability();
    testChaosRateTimescale();
    testModDepthZeroBitIdentical();

    printf("\n== Grooverider M6 capture & render checks ==\n\n");
    testOfflineRenderBasics();
    testOfflineRenderDeterministic();
    testSeamlessLoopJoin();

    printf("\n== Grooverider M8 polish checks ==\n\n");
    testAdaptiveVoiceCap();

    printf("\n%s (%d failure%s)\n\n", failures ? "FAILURES" : "ALL PASS",
           failures, failures == 1 ? "" : "s");
    return failures ? 1 : 0;
}
