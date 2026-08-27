#include "GrainScheduler.h"

#include <algorithm>
#include <cmath>

#include "../dsp/Interp.h"
#include "../rand/SeedRng.h"

namespace grvr {

namespace {
constexpr float  kPi         = 3.14159265358979323846f;
constexpr int32_t kMaxGrainsPerBlock = kMaxGrains;   // hard ceiling on spawns/callback (spec 8.3)

inline float wrap01(float x) noexcept {
    x = x - std::floor(x);
    return x < 0.0f ? 0.0f : x;
}

inline float readHermite(const SourceBuffer& src, int32_t channel, double pos) noexcept {
    const auto i0 = static_cast<int64_t>(std::floor(pos));
    const float frac = static_cast<float>(pos - static_cast<double>(i0));
    return hermite(src.sample(channel, i0 - 1), src.sample(channel, i0),
                   src.sample(channel, i0 + 1), src.sample(channel, i0 + 2), frac);
}
} // namespace

void GrainScheduler::configure(float sampleRate) noexcept {
    sampleRate_ = sampleRate;
    pool_.clear();

    // Per-class time constants (spec 2.8).
    density_.configure(0.080f, sampleRate);
    grainSizeMs_.configure(0.080f, sampleRate);
    timingJitter_.configure(0.080f, sampleRate);
    sizeJitter_.configure(0.080f, sampleRate);
    position_.configure(0.050f, sampleRate);
    drift_.configure(0.050f, sampleRate);
    pitch_.configure(0.050f, sampleRate);
    spray_.configure(0.050f, sampleRate);
    pitchSpray_.configure(0.050f, sampleRate);
    reverseProb_.configure(0.050f, sampleRate);
    spread_.configure(0.020f, sampleRate);
    gainComp_.configure(0.020f, sampleRate);

    density_.snap(40.0f);
    grainSizeMs_.snap(400.0f);
    timingJitter_.snap(0.15f);
    sizeJitter_.snap(0.25f);
    position_.snap(0.5f);
    drift_.snap(0.05f);
    pitch_.snap(0.0f);
    spray_.snap(250.0f);
    pitchSpray_.snap(0.15f);
    reverseProb_.snap(0.2f);
    spread_.snap(0.8f);
    gainComp_.snap(1.0f);

    clockSamples_ = 0.0;
    nextOnsetSamples_ = 0.0;
    driftOffsetNorm_ = 0.0;
    grainIndex_ = 0;
}

float GrainScheduler::effectiveDensity() const noexcept {
    const float d = density_.current();
    const float sizeSec = grainSizeMs_.current() * 0.001f;
    // Soft-limit rather than hard-clip the voice count: refusing to spawn a
    // grain is silent; stealing one mid-flight clicks (spec 2.5). Respects
    // the adaptive voice cap (spec 8.4, M8), not just the hard array size.
    const float cap = sizeSec > 1e-6f ? (static_cast<float>(maxVoices_) / sizeSec) : d;
    return std::max(0.1f, std::min(d, cap));
}

float GrainScheduler::rawGainComp(float densityNow, float sizeMsNow) const noexcept {
    const float overlap = densityNow * (sizeMsNow * 0.001f);
    const float wRms = windows_.rms(windowType_);
    const float denom = wRms * std::sqrt(std::max(1.0f, overlap));
    return denom > 1e-6f ? 1.0f / denom : 1.0f;
}

void GrainScheduler::spawnGrain(const SourceBuffer* src) noexcept {
    if (src == nullptr || src->frames() == 0) return;
    if (pool_.activeCount >= maxVoices_) return;   // adaptive cap (spec 8.4, M8)
    Grain* g = pool_.spawn();
    if (!g) return;   // at MAX_GRAINS; soft-limited density keeps this rare

    const uint64_t idx = grainIndex_;

    // --- size
    float sizeMs = grainSizeMs_.current() *
                   (1.0f + sizeJitter_.current() * SeedRng::bipolar(masterSeed_, kStreamSize, idx));
    sizeMs = std::clamp(sizeMs, 5.0f, 2000.0f);
    g->life = static_cast<uint32_t>(std::max(1.0f, sizeMs * 0.001f * sampleRate_));
    g->age  = 0;

    // --- position: smoothed base + autonomous drift scan + per-grain spray
    const float posNorm = wrap01(position_.current() + static_cast<float>(driftOffsetNorm_));
    const double sprayFrames = static_cast<double>(spray_.current()) * 0.001 * sampleRate_;
    const double sprayDraw = static_cast<double>(SeedRng::bipolar(masterSeed_, kStreamPosition, idx)) * sprayFrames;
    g->srcPos = static_cast<double>(posNorm) * static_cast<double>(src->frames()) + sprayDraw;

    // --- pitch + reverse
    const float semis = pitch_.current() +
                         pitchSpray_.current() * SeedRng::bipolar(masterSeed_, kStreamPitch, idx);
    float rate = std::pow(2.0f, semis / 12.0f);
    const bool reversed = SeedRng::value(masterSeed_, kStreamReverse, idx) < reverseProb_.current();
    if (reversed) rate = -rate;
    g->rate = static_cast<double>(rate);

    // --- stereo: equal-power pan + Haas per-channel source offset (spec 2.7)
    const float spreadNow = spread_.current();
    const float theta = (SeedRng::value(masterSeed_, kStreamPan, idx) - 0.5f) * spreadNow * kPi * 0.5f;
    g->panL = std::cos(theta + kPi * 0.25f);
    g->panR = std::sin(theta + kPi * 0.25f);
    g->chanOffset = SeedRng::value(masterSeed_, kStreamHaas, idx) * spreadNow * 0.010f * sampleRate_;

    g->windowId = windowType_;
    g->amp = 1.0f;

    // --- anti-alias one-pole for sped-up reads (spec 2.6)
    const float absRate = std::fabs(rate);
    if (absRate > 1.2f) {
        const float nyquist = sampleRate_ * 0.5f;
        const float cutoff = std::min(nyquist, nyquist / absRate);
        g->aaCoeff = 1.0f - std::exp(-2.0f * kPi * cutoff / sampleRate_);
    } else {
        g->aaCoeff = 0.0f;
    }
}

void GrainScheduler::renderSegment(float* out, int32_t from, int32_t to, const SourceBuffer* src) noexcept {
    const bool stereoSrc = src != nullptr && src->channelCount() >= 2;

    for (int32_t i = from; i < to; ++i) {
        density_.next(); timingJitter_.next(); grainSizeMs_.next(); sizeJitter_.next();
        position_.next(); spray_.next(); pitch_.next(); pitchSpray_.next();
        reverseProb_.next(); spread_.next();
        const float driftNow = drift_.next();

        if (src != nullptr && sampleRate_ > 0.0f) {
            const double durSec = static_cast<double>(src->frames()) / static_cast<double>(sampleRate_);
            if (durSec > 0.0) {
                driftOffsetNorm_ += static_cast<double>(driftNow) / static_cast<double>(sampleRate_) / durSec;
                driftOffsetNorm_ -= std::floor(driftOffsetNorm_);
            }
        }

        float l = 0.0f, r = 0.0f;
        if (src != nullptr) {
            for (int32_t gi = 0; gi < pool_.activeCount; ) {
                Grain& g = pool_.grains[gi];
                const float phase = static_cast<float>(g.age) /
                                     static_cast<float>(g.life > 0 ? g.life : 1);
                const float env = windows_.sample(g.windowId, phase);

                const double posL = g.srcPos + static_cast<double>(g.age) * g.rate;
                const double posR = posL + static_cast<double>(g.chanOffset);

                float sL = readHermite(*src, 0, posL);
                float sR = readHermite(*src, stereoSrc ? 1 : 0, posR);

                if (g.aaCoeff > 0.0f) {
                    g.aaStateL += (sL - g.aaStateL) * g.aaCoeff;
                    g.aaStateR += (sR - g.aaStateR) * g.aaCoeff;
                    sL = g.aaStateL;
                    sR = g.aaStateR;
                }

                const float amp = g.amp * env;
                l += sL * amp * g.panL;
                r += sR * amp * g.panR;

                ++g.age;
                if (g.age >= g.life) {
                    pool_.kill(gi);   // swap-remove: do not advance gi
                } else {
                    ++gi;
                }
            }
        }

        gainComp_.setTarget(rawGainComp(density_.current(), grainSizeMs_.current()));
        const float gc = gainComp_.next();
        out[i * 2]     += l * gc;
        out[i * 2 + 1] += r * gc;
    }
}

void GrainScheduler::renderBlock(float* out, int32_t numFrames) noexcept {
    // One snapshot for the whole block: a concurrent setSource() mid-block is
    // vanishingly rare (user-driven, seconds apart) and this way every grain
    // spawned or rendered in this callback sees a single consistent buffer.
    const SourceBuffer* src = source_.load(std::memory_order_acquire);

    const double blockEnd = clockSamples_ + numFrames;
    int32_t cursor = 0;
    int32_t spawnsThisBlock = 0;

    while (nextOnsetSamples_ < blockEnd && spawnsThisBlock < kMaxGrainsPerBlock) {
        int32_t onsetOffset = static_cast<int32_t>(nextOnsetSamples_ - clockSamples_);
        onsetOffset = std::clamp(onsetOffset, cursor, numFrames);

        renderSegment(out, cursor, onsetOffset, src);
        cursor = onsetOffset;

        spawnGrain(src);
        ++grainIndex_;
        ++spawnsThisBlock;

        const float jitterAmt  = timingJitter_.current();
        const float jitterDraw = SeedRng::bipolar(masterSeed_, kStreamTiming, grainIndex_);
        double interval = static_cast<double>(sampleRate_) / static_cast<double>(effectiveDensity());
        interval *= (1.0 + static_cast<double>(jitterAmt * jitterDraw));
        if (interval < 1.0) interval = 1.0;   // never spawn faster than one grain per sample
        nextOnsetSamples_ += interval;
    }

    renderSegment(out, cursor, numFrames, src);
    clockSamples_ = blockEnd;
}

} // namespace grvr
