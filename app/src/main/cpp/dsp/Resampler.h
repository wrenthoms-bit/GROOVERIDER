#pragma once
#include <cstdint>
#include <vector>
#include "../io/SourceBuffer.h"

namespace grvr {

/// Windowed-sinc sample-rate conversion, used once at import time (spec 2.1),
/// never on the audio thread. Band-limited: on downsampling the sinc cutoff
/// scales down with the ratio, so it does not alias.
///
/// `halfWidth` is the number of zero-crossings of the sinc on each side; 16
/// (32 taps) is transparent for musical material and cheap enough offline.
class Resampler {
public:
    static SourceBuffer resample(const SourceBuffer& in, int32_t dstRate,
                                 int halfWidth = 16);

    /// Single-channel convenience, exposed for testing.
    static std::vector<float> resampleChannel(const std::vector<float>& in,
                                              int32_t srcRate, int32_t dstRate,
                                              int halfWidth = 16);
};

} // namespace grvr
