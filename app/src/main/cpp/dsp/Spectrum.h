#pragma once
#include <cmath>
#include <cstdint>

namespace grvr {

/// Magnitude spectrum of one block of audio, for the visuals only -- nothing
/// in the signal path uses it. Blackman window and 1/N scaling, the same
/// conventions as the Web Audio AnalyserNode the web app's "spectral tide"
/// reads, so both tides are fed alike.
class Spectrum {
public:
    static constexpr int32_t kSize = 2048;          // frames in, kSize / 2 bins out
    static constexpr int32_t kBins = kSize / 2;

    /// `samples` holds kSize frames and is used as scratch. `magnitudes` gets
    /// kBins linear magnitudes; a full-scale sine reads about 0.21.
    static void magnitudes(float* samples, float* magnitudes) noexcept {
        float re[kSize], im[kSize];
        for (int32_t i = 0; i < kSize; ++i) {
            const float x = static_cast<float>(i) / static_cast<float>(kSize);
            const float window = 0.42f - 0.5f * std::cos(2.0f * kPi * x) + 0.08f * std::cos(4.0f * kPi * x);
            re[i] = samples[i] * window;
            im[i] = 0.0f;
        }
        fft(re, im);
        for (int32_t k = 0; k < kBins; ++k)
            magnitudes[k] = std::sqrt(re[k] * re[k] + im[k] * im[k]) * (1.0f / static_cast<float>(kSize));
    }

private:
    static constexpr float kPi = 3.14159265358979323846f;

    // in-place radix-2
    static void fft(float* re, float* im) noexcept {
        for (int32_t i = 1, j = 0; i < kSize; ++i) {
            int32_t bit = kSize >> 1;
            for (; j & bit; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { const float tr = re[i]; re[i] = re[j]; re[j] = tr; const float ti = im[i]; im[i] = im[j]; im[j] = ti; }
        }
        for (int32_t len = 2; len <= kSize; len <<= 1) {
            const float angle = -2.0f * kPi / static_cast<float>(len);
            const float wr = std::cos(angle), wi = std::sin(angle);
            for (int32_t i = 0; i < kSize; i += len) {
                float cr = 1.0f, ci = 0.0f;
                for (int32_t j = 0; j < len / 2; ++j) {
                    const int32_t a = i + j, b = i + j + len / 2;
                    const float vr = re[b] * cr - im[b] * ci, vi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - vr; im[b] = im[a] - vi;
                    re[a] += vr;        im[a] += vi;
                    const float nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr; cr = nr;
                }
            }
        }
    }
};

} // namespace grvr
