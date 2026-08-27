#include "Resampler.h"
#include <cmath>

namespace grvr {

namespace {
constexpr double kPi = 3.14159265358979323846;

inline double sinc(double x) {
    if (x == 0.0) return 1.0;
    const double px = kPi * x;
    return std::sin(px) / px;
}

// Blackman window over [-1, 1]; zero at the edges, low sidelobes.
inline double blackman(double t) {          // t in [-1, 1]
    const double u = (t + 1.0) * 0.5;        // -> [0, 1]
    return 0.42 - 0.5 * std::cos(2.0 * kPi * u) + 0.08 * std::cos(4.0 * kPi * u);
}
} // namespace

std::vector<float> Resampler::resampleChannel(const std::vector<float>& in,
                                              int32_t srcRate, int32_t dstRate,
                                              int halfWidth) {
    if (in.empty() || srcRate <= 0 || dstRate <= 0) return {};
    if (srcRate == dstRate) return in;

    const double ratio  = static_cast<double>(dstRate) / static_cast<double>(srcRate);
    const double step   = 1.0 / ratio;                 // input samples per output sample
    const double cutoff = ratio < 1.0 ? ratio : 1.0;   // anti-alias on downsample
    const int    W      = halfWidth;

    const int64_t inN  = static_cast<int64_t>(in.size());
    const int64_t outN = static_cast<int64_t>(std::llround(inN * ratio));
    std::vector<float> out(static_cast<size_t>(outN), 0.0f);

    // Kernel half-width in input samples widens when downsampling (cutoff<1).
    const double support = static_cast<double>(W) / cutoff;

    for (int64_t n = 0; n < outN; ++n) {
        const double center = n * step;                // fractional input position
        const int64_t first = static_cast<int64_t>(std::ceil(center - support));
        const int64_t last  = static_cast<int64_t>(std::floor(center + support));

        double acc = 0.0;
        double wsum = 0.0;
        for (int64_t i = first; i <= last; ++i) {
            const double dx = center - static_cast<double>(i);        // input samples
            const double win = blackman(dx / support);
            const double h   = cutoff * sinc(cutoff * dx) * win;
            const int64_t idx = i < 0 ? 0 : (i >= inN ? inN - 1 : i); // clamp edges
            acc  += h * static_cast<double>(in[static_cast<size_t>(idx)]);
            wsum += h;
        }
        // Normalise by the summed kernel weight so DC gain is exactly 1 and
        // fractional-phase ripple does not modulate level.
        out[static_cast<size_t>(n)] = static_cast<float>(wsum != 0.0 ? acc / wsum : acc);
    }
    return out;
}

SourceBuffer Resampler::resample(const SourceBuffer& in, int32_t dstRate, int halfWidth) {
    if (in.empty() || in.sampleRate() == dstRate) return in;
    std::vector<std::vector<float>> chans;
    chans.reserve(static_cast<size_t>(in.channelCount()));
    for (int32_t c = 0; c < in.channelCount(); ++c) {
        chans.push_back(resampleChannel(in.channel(c), in.sampleRate(), dstRate, halfWidth));
    }
    return SourceBuffer(std::move(chans), dstRate);
}

} // namespace grvr
