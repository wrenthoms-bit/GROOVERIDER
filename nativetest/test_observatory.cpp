// Checks for core/Observatory.h -- the shared chaos / drone / scale-lock /
// space layer -- driven the way a host drives it: a GrainCore for the grains,
// an Observatory wrapped around it. Most cases mirror webtests/engine.test.mjs,
// which runs the same checks against the web app's JavaScript ObservatoryEngine
// (the reference this is a port of).
#include "Observatory.h"

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <set>
#include <vector>

using namespace grv;

static int failures = 0;
static void check(bool ok, const char* name, const char* detail = "") {
    printf("%-62s %s %s\n", name, ok ? "PASS" : "**FAIL**", detail);
    if (!ok) ++failures;
}

constexpr float kSr = 48000.0f;

// ------------------------------------------------------------------ sources
struct Source { std::vector<float> l, r; int frames = 0; };

// Six harmonics of f0, each rounded to a whole number of cycles so the source loops cleanly.
static Source demoSource(double f0 = 130.81, double seconds = 6.0) {
    Source s; s.frames = static_cast<int>(kSr * seconds);
    s.l.assign(static_cast<size_t>(s.frames), 0.0f);
    for (int h = 1; h <= 6; ++h) {
        const double f = std::round(f0 * h * seconds) / seconds;
        for (int i = 0; i < s.frames; ++i)
            s.l[static_cast<size_t>(i)] += static_cast<float>(0.25 / h * std::sin(2.0 * M_PI * f * i / kSr + h));
    }
    s.r = s.l;
    return s;
}
static Source sineSource(double hz, double seconds = 4.0) {
    Source s; s.frames = static_cast<int>(kSr * seconds);
    s.l.resize(static_cast<size_t>(s.frames));
    for (int i = 0; i < s.frames; ++i) s.l[static_cast<size_t>(i)] = static_cast<float>(0.4 * std::sin(2.0 * M_PI * hz * i / kSr));
    s.r = s.l;
    return s;
}

// ------------------------------------------------------------------ the rig
// Everything the web page's `p` object holds: the plain grain params go to the
// core, the rest to the Observatory.
struct State {
    float playing = 1, density = 60, grainMs = 2000, timingJitter = 0.5f, sizeJitter = 0.35f, sprayMs = 900,
          reverse = 0.3f, window = 0, spread = 0.868f, width = 1.4f, gain = 0.8f, position = 0.42f, scan = 0,
          chaos = 0.14f, pitch = 0.30f, key = 3, registerSt = -12, detune = 0.06f, scale = 3, drone = 1,
          space = 0.86f, shimmer = 0.42f, tone = 0.52f;
};
static const State SRO;   // "Standing Room Only", the flagship preset
constexpr uint64_t kSroSeed = 0x0051A9D005700A11ull;

struct Rig {
    GrainCore core;
    Observatory obs;
    Source src;
    bool spaceAvailable = false;

    Rig(const Source& source, const State& st, uint64_t seed = 0x123456789ABCDEF0ull, float sr = kSr) : src(source) {
        core.setBuffers(src.l.data(), src.r.data(), src.frames);
        core.init(sr);
        spaceAvailable = obs.init(sr);
        core.setSeed(seed); obs.setSeed(seed);
        set(st, true);
        core.setParamNow(P_POSITION, st.position);
        core.setSource(2, src.frames);
        obs.sourceChanged(st.position);
    }
    void set(const State& s, bool now = false) {
        auto put = [&](int id, float v) { if (now) core.setParamNow(id, v); else core.setParam(id, v); };
        put(P_DENSITY, s.density); put(P_TIMING_JITTER, s.timingJitter); put(P_GRAIN_MS, s.grainMs);
        put(P_SIZE_JITTER, s.sizeJitter); put(P_REVERSE_PROB, s.reverse); put(P_WINDOW, s.window);
        put(P_OUT_GAIN, s.gain); put(P_PLAYING, s.playing);
        obs.setParam(O_POSITION, s.position); obs.setParam(O_SCAN, s.scan); obs.setParam(O_SPRAY_MS, s.sprayMs);
        obs.setParam(O_SPREAD, s.spread); obs.setParam(O_WIDTH, s.width); obs.setParam(O_CHAOS, s.chaos);
        obs.setParam(O_PITCH, s.pitch); obs.setParam(O_KEY, s.key); obs.setParam(O_SCALE, s.scale);
        obs.setParam(O_REGISTER, s.registerSt); obs.setParam(O_DETUNE, s.detune); obs.setParam(O_DRONE, s.drone);
        obs.setParam(O_SPACE, s.space); obs.setParam(O_SHIMMER, s.shimmer); obs.setParam(O_TONE, s.tone);
    }
    double playheadNorm() const { return core.playhead() / core.frames(); }
};

template <class OnBlock>
static std::vector<float> run(Rig& rig, double seconds, OnBlock onBlock, int block = 128) {
    const int total = static_cast<int>(std::lround(seconds * kSr / block)) * block;
    std::vector<float> out(static_cast<size_t>(total) * 2);
    for (int done = 0, i = 0; done < total; done += block, ++i) {
        rig.obs.render(rig.core, out.data() + static_cast<size_t>(done) * 2, block);
        onBlock(i);
    }
    return out;
}
static std::vector<float> run(Rig& rig, double seconds, int block = 128) { return run(rig, seconds, [](int) {}, block); }

// ------------------------------------------------------------------ measuring
static double rmsDb(const std::vector<float>& x, double fromSec, double toSec, int channel = 0) {
    const auto a = static_cast<size_t>(fromSec * kSr), b = static_cast<size_t>(toSec * kSr);
    double sum = 0.0;
    for (size_t i = a; i < b; ++i) { const double v = x[i * 2 + static_cast<size_t>(channel)]; sum += v * v; }
    return 20.0 * std::log10(std::sqrt(sum / static_cast<double>(b - a)) + 1e-12);
}
static double meanDb(const std::vector<float>& x, double fromSec, double toSec) {
    const auto a = static_cast<size_t>(fromSec * kSr), b = static_cast<size_t>(toSec * kSr);
    double sum = 0.0;
    for (size_t i = a; i < b; ++i) sum += x[i * 2];
    return 20.0 * std::log10(std::fabs(sum / static_cast<double>(b - a)) + 1e-12);
}
static double peakOf(const std::vector<float>& x) { double m = 0; for (float v : x) m = std::max(m, static_cast<double>(std::fabs(v))); return m; }
static bool anyNonFinite(const std::vector<float>& x) { for (float v : x) if (!std::isfinite(v)) return true; return false; }
static long countSubnormal(const std::vector<float>& x) { long n = 0; for (float v : x) if (std::fpclassify(v) == FP_SUBNORMAL) ++n; return n; }
static double goertzelDb(const std::vector<float>& x, double hz, double fromSec, double toSec) {
    const auto a = static_cast<size_t>(fromSec * kSr), b = static_cast<size_t>(toSec * kSr);
    const double c = 2.0 * std::cos(2.0 * M_PI * hz / kSr); double s1 = 0, s2 = 0;
    for (size_t i = a; i < b; ++i) { const double s0 = x[i * 2] + c * s1 - s2; s2 = s1; s1 = s0; }
    return 20.0 * std::log10(std::sqrt(std::max(0.0, s1 * s1 + s2 * s2 - c * s1 * s2)) / static_cast<double>(b - a) * 2.0 + 1e-12);
}
static double grainSemitones(const Rig& rig, int i) { return 12.0 * std::log2(std::fabs(rig.core.grainAt(i).rate)); }
static double secondsSince(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
}

// ------------------------------------------------------------------ 1. the long run
static void testStandingRoomOnly() {
    auto rig = std::make_unique<Rig>(demoSource(), SRO, kSroSeed);
    int maxVoices = 0; float cxMin = 1, cxMax = -1;
    const auto t0 = std::chrono::steady_clock::now();
    const auto out = run(*rig, 600.0, [&](int i) {
        if (i % 50 == 0) {
            maxVoices = std::max(maxVoices, rig->core.activeGrains());
            cxMin = std::min(cxMin, rig->obs.chaosX()); cxMax = std::max(cxMax, rig->obs.chaosX());
        }
    });
    const double playSeconds = secondsSince(t0);
    char d[160];

    check(!anyNonFinite(out), "SRO 10 min: no NaN or Inf");
    snprintf(d, sizeof d, "(peak %.3f)", peakOf(out));
    check(peakOf(out) <= 1.0, "SRO 10 min: never over full scale", d);

    const double r5 = rmsDb(out, 4, 6), r300 = rmsDb(out, 298, 300), r600 = rmsDb(out, 598, 600);
    snprintf(d, sizeof d, "(rms @5 s %.1f, @5 min %.1f, @10 min %.1f dBFS)", r5, r300, r600);
    check(r600 > -30 && r600 < -6 && std::fabs(r600 - r300) < 6, "SRO 10 min: musical level, no runaway build-up", d);

    double worstDc = -200;
    for (int minute = 0; minute < 10; ++minute) worstDc = std::max(worstDc, meanDb(out, minute * 60.0, minute * 60.0 + 60.0));
    snprintf(d, sizeof d, "(first minute %.1f, last minute %.1f, worst minute %.1f dBFS)", meanDb(out, 0, 60), meanDb(out, 540, 600), worstDc);
    check(worstDc < -80, "SRO 10 min: no DC creep", d);

    snprintf(d, sizeof d, "(max voices %d)", maxVoices);
    check(maxVoices > 60 && maxVoices <= MAX_GRAINS, "SRO: the cloud is dense", d);
    snprintf(d, sizeof d, "(chaos x %.2f .. %.2f)", cxMin, cxMax);
    check(cxMax - cxMin > 0.05f, "SRO: chaos keeps moving at low DRIFT", d);

    // ---- stop the grains and listen to the tail
    State stopped = SRO; stopped.playing = 0;
    rig->set(stopped);
    const auto t1 = std::chrono::steady_clock::now();
    const auto tail = run(*rig, 300.0);
    const double tailSeconds = secondsSince(t1);
    const double at3 = rmsDb(tail, 2.5, 3.5);
    double below90At = -1;
    for (int s = 0; s < 299; ++s) if (rmsDb(tail, s, s + 1) < -90 && rmsDb(tail, s, s + 1, 1) < -90) { below90At = s; break; }
    snprintf(d, sizeof d, "(%.1f dBFS 3 s after the grains stop)", at3);
    check(at3 > -50, "tail: the space rings on after the grains stop", d);
    snprintf(d, sizeof d, "(below -90 dBFS from %.0f s; %.1f dBFS at 5 min)", below90At, rmsDb(tail, 299, 300));
    check(below90At > 0 && rmsDb(tail, 299, 300) < -90, "tail: decays below -90 dBFS", d);

    // ---- denormals: none reach the output, and silence is not slower to render than sound
    const long sub = countSubnormal(out) + countSubnormal(tail);
    snprintf(d, sizeof d, "(%ld denormal samples in 15 min of output)", sub);
    check(sub == 0 && !anyNonFinite(tail), "no denormals in the output, playing or decaying", d);
    snprintf(d, sizeof d, "(playing %.0fx realtime, silent tail %.0fx)", 600.0 / playSeconds, 300.0 / tailSeconds);
    check(300.0 / tailSeconds > 600.0 / playSeconds, "no denormal stall: the silent tail renders faster than the cloud", d);
}

// ------------------------------------------------------------------ 2. determinism
static void testDeterminism() {
    auto render = [](uint64_t seed, int block) {
        auto rig = std::make_unique<Rig>(demoSource(), SRO, seed);
        return run(*rig, 20.0, block);
    };
    const auto a = render(kSroSeed, 128), b = render(kSroSeed, 128);
    check(a.size() == b.size() && std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) == 0,
          "same seed + state rendered twice is bit-identical");
    bool invariant = true;
    for (int block : {96, 192, 384, 960}) {
        const auto c = render(kSroSeed, block);
        const size_t n = std::min(a.size(), c.size());
        if (std::memcmp(a.data(), c.data(), n * sizeof(float)) != 0) invariant = false;
    }
    check(invariant, "bit-identical at block sizes 96/192/384/960 vs 128");
    const auto other = render(kSroSeed + 1, 128);
    check(std::memcmp(a.data(), other.data(), a.size() * sizeof(float)) != 0, "a different seed gives a different render");
}

// ------------------------------------------------------------------ 3. worst case
static void testEverythingMaxed() {
    State s = SRO; s.chaos = 1; s.space = 1; s.shimmer = 1; s.tone = 1; s.pitch = 1; s.gain = 1.5f; s.density = 128; s.position = 0.5f;
    auto rig = std::make_unique<Rig>(demoSource(), s);
    const auto out = run(*rig, 90.0);
    char d[96]; snprintf(d, sizeof d, "(peak %.3f, rms @88 s %.1f dBFS)", peakOf(out), rmsDb(out, 86, 88));
    check(!anyNonFinite(out) && peakOf(out) <= 1.0, "max space + shimmer + chaos for 90 s: bounded", d);
}

// ------------------------------------------------------------------ 4. shimmer and reverb
static void testShimmerAndReverb() {
    auto octaveLevels = [](float shimmer) {
        State s = SRO; s.drone = 0; s.chaos = 0; s.pitch = 0; s.key = 0; s.registerSt = 0; s.scale = 0; s.detune = 0;
        s.reverse = 0; s.shimmer = shimmer; s.space = 0.7f; s.tone = 1; s.sprayMs = 0; s.position = 0.5f;
        auto rig = std::make_unique<Rig>(sineSource(220), s);
        const auto out = run(*rig, 12.0);
        return std::make_pair(goertzelDb(out, 440, 8, 12), goertzelDb(out, 880, 8, 12));
    };
    const auto off = octaveLevels(0.0f), on = octaveLevels(0.8f);
    char d[128]; snprintf(d, sizeof d, "(440 Hz %.1f -> %.1f dB; 880 Hz %.1f -> %.1f dB)", off.first, on.first, off.second, on.second);
    check(on.first - off.first > 15, "shimmer: energy appears an octave up", d);

    auto tailLevel = [](float space) {
        State s = SRO; s.drone = 0; s.chaos = 0; s.shimmer = 0; s.space = space; s.tone = 1; s.grainMs = 200; s.density = 40; s.position = 0.5f;
        auto rig = std::make_unique<Rig>(sineSource(220), s);
        run(*rig, 4.0);
        s.playing = 0; rig->set(s);
        const auto out = run(*rig, 3.0);
        return rmsDb(out, 2, 3);
    };
    const double dry = tailLevel(0.0f), wet = tailLevel(0.8f);
    snprintf(d, sizeof d, "(2-3 s after stop: dry %.0f dB, wet %.1f dB)", dry, wet);
    check(dry < -100 && wet > -45, "reverb: space 0 is dry, space 0.8 rings", d);
}

// ------------------------------------------------------------------ 5. scale-lock
static bool inSet(const std::vector<int>& set, int semis, int key) {
    const int n = (((semis - key) % 12) + 12) % 12;
    for (int v : set) if (v == n) return true;
    return false;
}
static void testScaleLock() {
    const std::vector<std::vector<int>> sets = { {}, {0,1,2,3,4,5,6,7,8,9,10,11}, {0,2,4,5,7,9,11}, {0,2,3,5,7,8,10}, {0,2,4,7,9}, {0,3,5,7,10}, {0,7} };
    for (int scale : {2, 3, 5, 6}) {
        State s = SRO; s.scale = static_cast<float>(scale); s.key = 3; s.registerSt = 0; s.detune = 0; s.pitch = 0.8f; s.chaos = 0.6f;
        s.grainMs = 300; s.density = 80; s.position = 0.5f;
        auto rig = std::make_unique<Rig>(demoSource(), s);
        std::set<int> seen; int off = 0, total = 0;
        run(*rig, 10.0, [&](int i) {
            if (i % 20) return;
            for (int k = 0; k < rig->core.activeGrains(); ++k) {
                const double st = grainSemitones(*rig, k); const int n = static_cast<int>(std::lround(st)); ++total;
                if (std::fabs(st - n) > 0.02 || !inSet(sets[static_cast<size_t>(scale)], n, 3)) ++off; else seen.insert(n);
            }
        });
        char name[64], d[96]; snprintf(name, sizeof name, "scale-lock, scale %d: every grain in key", scale);
        snprintf(d, sizeof d, "(%d grains sampled, %zu distinct notes, %d off-scale)", total, seen.size(), off);
        check(off == 0 && seen.size() >= 3, name, d);
    }
    State s = SRO; s.scale = 0; s.pitch = 0.6f; s.detune = 0; s.position = 0.5f;
    auto rig = std::make_unique<Rig>(demoSource(), s);
    int offSemitone = 0, total = 0;
    run(*rig, 6.0, [&](int i) {
        if (i % 20) return;
        for (int k = 0; k < rig->core.activeGrains(); ++k) { const double st = grainSemitones(*rig, k); ++total; if (std::fabs(st - std::round(st)) > 0.05) ++offSemitone; }
    });
    char d[64]; snprintf(d, sizeof d, "(%d%% of grains off the semitone grid)", total ? 100 * offSemitone / total : 0);
    check(total > 0 && offSemitone * 2 > total, "free scale: continuous pitch spray", d);
}

// ------------------------------------------------------------------ 6. chaos, scan, drone
static void testChaosScanDrone() {
    State s = SRO; s.drone = 0; s.chaos = 0.9f; s.scan = 0; s.position = 0.5f;
    auto rig = std::make_unique<Rig>(demoSource(130.81, 20.0), s);
    double pMin = 1, pMax = 0; std::vector<double> xs, ps;
    run(*rig, 20.0, [&](int i) {
        const double p = rig->playheadNorm(); pMin = std::min(pMin, p); pMax = std::max(pMax, p);
        if (i % 8 == 0) { xs.push_back(rig->obs.chaosX()); ps.push_back(p); }
    });
    double mx = 0, mp = 0; for (size_t i = 0; i < xs.size(); ++i) { mx += xs[i]; mp += ps[i]; } mx /= xs.size(); mp /= ps.size();
    double sxy = 0, sxx = 0, syy = 0;
    for (size_t i = 0; i < xs.size(); ++i) { sxy += (xs[i] - mx) * (ps[i] - mp); sxx += (xs[i] - mx) * (xs[i] - mx); syy += (ps[i] - mp) * (ps[i] - mp); }
    const double corr = sxy / std::sqrt(sxx * syy);
    char d[128]; snprintf(d, sizeof d, "(playhead %.3f .. %.3f, correlation with chaos x %.2f)", pMin, pMax, corr);
    check(pMax - pMin > 0.05 && corr > 0.5, "chaos sways the grain position", d);

    State t = SRO; t.drone = 0; t.chaos = 0; t.scan = 0.5f; t.position = 0.2f;
    auto rig2 = std::make_unique<Rig>(demoSource(130.81, 20.0), t);
    run(*rig2, 4.0);
    const double moved = rig2->playheadNorm();
    t.drone = 1; rig2->set(t); run(*rig2, 4.0);
    const double held = rig2->playheadNorm();
    snprintf(d, sizeof d, "(after 4 s of scan %.3f, after 4 s of drone %.3f)", moved, held);
    check(moved > 0.28 && std::fabs(held - moved) < 0.03, "scan moves the playhead; drone latches it", d);
    t.position = 0.8f; rig2->set(t); run(*rig2, 2.0);
    snprintf(d, sizeof d, "(%.3f)", rig2->playheadNorm());
    check(std::fabs(rig2->playheadNorm() - 0.8) < 0.03, "position re-aims the latched playhead", d);
}

// ------------------------------------------------------------------ 7. keyboard notes
static void testNotes() {
    State s = SRO; s.scale = 0; s.pitch = 0; s.detune = 0; s.registerSt = 0; s.key = 0; s.reverse = 0; s.grainMs = 300; s.density = 80; s.position = 0.5f;
    auto rig = std::make_unique<Rig>(demoSource(), s);
    const float chord[3] = {0, 7, -12};
    rig->obs.setNotes(chord, 3);
    std::set<int> cents; run(*rig, 2.0);
    run(*rig, 6.0, [&](int i) { if (i % 20 == 0) for (int k = 0; k < rig->core.activeGrains(); ++k) cents.insert(static_cast<int>(std::lround(grainSemitones(*rig, k) * 100))); });
    char d[128]; int n = 0; d[0] = 0; for (int c : cents) n += snprintf(d + n, sizeof d - static_cast<size_t>(n), "%s%.2f", n ? " " : "(", c / 100.0); snprintf(d + n, sizeof d - static_cast<size_t>(n), " st)");
    check(cents == std::set<int>({-1200, 0, 700}), "keys, free scale: grains sit on exactly the held notes", d);

    State m = SRO; m.scale = 3; m.key = 3; m.registerSt = -12; m.pitch = 0.5f; m.detune = 0; m.grainMs = 300; m.density = 80; m.position = 0.5f;
    auto rig2 = std::make_unique<Rig>(demoSource(), m);
    const float chord2[4] = {3, 6, 10, 1};
    rig2->obs.setNotes(chord2, 4);
    int off = 0, total = 0, lo = 99, hi = -99; run(*rig2, 2.0);
    run(*rig2, 8.0, [&](int i) {
        if (i % 20) return;
        for (int k = 0; k < rig2->core.activeGrains(); ++k) {
            const double st = grainSemitones(*rig2, k); const int n2 = static_cast<int>(std::lround(st)); ++total; lo = std::min(lo, n2); hi = std::max(hi, n2);
            if (std::fabs(st - n2) > 0.02 || !inSet({0, 2, 3, 5, 7, 8, 10}, n2, 3)) ++off;
        }
    });
    snprintf(d, sizeof d, "(%d grains, %d off-scale, range %d .. %d st)", total, off, lo, hi);
    check(off == 0 && total > 500, "keys + scale-lock: every grain in E-flat minor, even from an out-of-key note", d);

    rig2->obs.setNotes(nullptr, 0);
    std::set<int> notes; run(*rig2, 3.0);
    run(*rig2, 3.0, [&](int i) { if (i % 20 == 0) for (int k = 0; k < rig2->core.activeGrains(); ++k) notes.insert(static_cast<int>(std::lround(grainSemitones(*rig2, k)))); });
    snprintf(d, sizeof d, "(%d .. %d st around -9)", notes.empty() ? 0 : *notes.begin(), notes.empty() ? 0 : *notes.rbegin());
    check(!notes.empty() && *notes.begin() >= -21 && *notes.rbegin() <= 3 && notes.count(-9), "clearing the chord returns to key + register", d);
}

// ------------------------------------------------------------------ 8. sample rates
static void testSampleRates() {
    auto rigLow = std::make_unique<Rig>(demoSource(), SRO, kSroSeed, 44100.0f);
    const auto low = run(*rigLow, 10.0);
    char d[96]; snprintf(d, sizeof d, "(peak %.3f, rms %.1f dBFS)", peakOf(low), rmsDb(low, 6, 10));
    check(!anyNonFinite(low) && peakOf(low) <= 1.0 && rmsDb(low, 6, 10) > -40, "44.1 kHz: runs and sounds", d);

    auto rigHigh = std::make_unique<Rig>(demoSource(), SRO, kSroSeed, 96000.0f);
    const auto high = run(*rigHigh, 4.0);
    check(rigLow->spaceAvailable && !rigHigh->spaceAvailable && !anyNonFinite(high) && rmsDb(high, 1, 2) > -60,
          "96 kHz (above OBS_MAX_SR): init says no, grains pass through, nothing breaks");
}

int main() {
    printf("\n== Observatory: Standing Room Only, 10 minutes ==\n\n");
    testStandingRoomOnly();
    printf("\n== Observatory: determinism ==\n\n");
    testDeterminism();
    printf("\n== Observatory: behaviour (mirrors webtests/engine.test.mjs) ==\n\n");
    testEverythingMaxed();
    testShimmerAndReverb();
    testScaleLock();
    testChaosScanDrone();
    testNotes();
    testSampleRates();
    printf("\n%s (%d failure%s)\n\n", failures ? "FAILURES" : "ALL PASS", failures, failures == 1 ? "" : "s");
    return failures ? 1 : 0;
}
