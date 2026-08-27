#pragma once
#include <cmath>
#include <cstdint>
#include <vector>

namespace grvr {

enum WindowType : uint16_t {
    kWindowGaussian = 0,
    kWindowTukey    = 1,
    kWindowHann     = 2,
    kWindowCount    = 3,
};

/// Precomputed 4096-point window tables (spec 2.4). Every window must be
/// exactly zero at phase 0 and 1 -- a per-grain click at 40 grains/second is a
/// 40 Hz buzz, not a texture. Linear interpolation on the table is inaudible;
/// cubic here is wasted CPU.
class WindowSet {
public:
    static constexpr int32_t kTableSize = 4096;

    WindowSet() {
        buildGaussian(0.18f);
        buildTukey(0.5f);
        buildHann();
    }

    /// Linearly-interpolated read, phase in [0, 1].
    inline float sample(uint16_t windowId, float phase) const noexcept {
        const Table& t = tables_[windowId < kWindowCount ? windowId : kWindowGaussian];
        float p = phase < 0.0f ? 0.0f : (phase > 1.0f ? 1.0f : phase);
        const float f = p * static_cast<float>(kTableSize - 1);
        const int32_t i0 = static_cast<int32_t>(f);
        const int32_t i1 = i0 + 1 < kTableSize ? i0 + 1 : kTableSize - 1;
        const float frac = f - static_cast<float>(i0);
        return t.data[static_cast<size_t>(i0)] +
               (t.data[static_cast<size_t>(i1)] - t.data[static_cast<size_t>(i0)]) * frac;
    }

    /// Feeds the sqrt-overlap gain compensation (spec 2.4): window shape
    /// changes output level independently of overlap, so this must be baked in.
    inline float rms(uint16_t windowId) const noexcept {
        return tables_[windowId < kWindowCount ? windowId : kWindowGaussian].rms;
    }

private:
    struct Table {
        std::vector<float> data;
        float rms = 1.0f;
    };
    Table tables_[kWindowCount];

    void finalizeRms(Table& t) {
        double sumSq = 0.0;
        for (float v : t.data) sumSq += static_cast<double>(v) * v;
        t.rms = static_cast<float>(std::sqrt(sumSq / static_cast<double>(t.data.size())));
    }

    // A truncated Gaussian does not reach zero at the edges on its own -- the
    // pedestal at sigma=0.18 is about -34 dBFS, and every one of those clicks.
    // Subtract the edge value and renormalise (spec 2.4).
    void buildGaussian(float sigma) {
        Table& t = tables_[kWindowGaussian];
        t.data.resize(kTableSize);
        const float g0 = std::exp(-0.5f * (0.5f / sigma) * (0.5f / sigma));
        for (int32_t i = 0; i < kTableSize; ++i) {
            const float x = static_cast<float>(i) / static_cast<float>(kTableSize - 1);
            const float d = (x - 0.5f) / sigma;
            const float g = std::exp(-0.5f * d * d);
            t.data[static_cast<size_t>(i)] = (g - g0) / (1.0f - g0);
        }
        t.data[0] = 0.0f;
        t.data[static_cast<size_t>(kTableSize - 1)] = 0.0f;
        finalizeRms(t);
    }

    // Flat plateau of width `plateau` (0=Hann, 0.9=mostly flat with short
    // cosine tapers), exactly zero at both edges by construction.
    void buildTukey(float plateau) {
        Table& t = tables_[kWindowTukey];
        t.data.resize(kTableSize);
        const float a = plateau < 0.0f ? 0.0f : (plateau > 0.999f ? 0.999f : plateau);
        const float taper = (1.0f - a) * 0.5f;   // fraction of the window on each side
        for (int32_t i = 0; i < kTableSize; ++i) {
            const float x = static_cast<float>(i) / static_cast<float>(kTableSize - 1);
            float w;
            if (taper <= 0.0f) {
                w = 1.0f;
            } else if (x < taper) {
                w = 0.5f * (1.0f + std::cos(static_cast<float>(M_PI) * (x / taper - 1.0f)));
            } else if (x > 1.0f - taper) {
                w = 0.5f * (1.0f + std::cos(static_cast<float>(M_PI) * ((x - 1.0f + taper) / taper)));
            } else {
                w = 1.0f;
            }
            t.data[static_cast<size_t>(i)] = w;
        }
        t.data[0] = 0.0f;
        t.data[static_cast<size_t>(kTableSize - 1)] = 0.0f;
        finalizeRms(t);
    }

    void buildHann() {
        Table& t = tables_[kWindowHann];
        t.data.resize(kTableSize);
        for (int32_t i = 0; i < kTableSize; ++i) {
            const float x = static_cast<float>(i) / static_cast<float>(kTableSize - 1);
            t.data[static_cast<size_t>(i)] = 0.5f * (1.0f - std::cos(2.0f * static_cast<float>(M_PI) * x));
        }
        t.data[0] = 0.0f;
        t.data[static_cast<size_t>(kTableSize - 1)] = 0.0f;
        finalizeRms(t);
    }
};

} // namespace grvr
