#pragma once
#include <cstdint>
#include <memory>
#include <vector>

namespace grvr {

/// Deinterleaved float32 audio, fully resident in RAM (spec 2.1).
/// One vector per channel; mono keeps a single channel and the reader
/// duplicates on demand. Immutable once built.
class SourceBuffer {
public:
    SourceBuffer() = default;

    SourceBuffer(std::vector<std::vector<float>> channels, int32_t sampleRate)
        : channels_(std::move(channels)), sampleRate_(sampleRate) {
        frames_ = channels_.empty() ? 0 : static_cast<int64_t>(channels_[0].size());
    }

    int32_t channelCount() const noexcept { return static_cast<int32_t>(channels_.size()); }
    int64_t frames()       const noexcept { return frames_; }
    int32_t sampleRate()   const noexcept { return sampleRate_; }
    bool    empty()        const noexcept { return frames_ == 0; }

    /// Read one channel with clamping at the edges. `ch` is clamped into range,
    /// so a stereo read on a mono source returns the mono channel for both.
    inline float sample(int32_t ch, int64_t frame) const noexcept {
        if (frames_ == 0) return 0.0f;
        if (ch < 0) ch = 0;
        if (ch >= channelCount()) ch = channelCount() - 1;
        if (frame < 0) frame = 0;
        if (frame >= frames_) frame = frames_ - 1;
        return channels_[ch][static_cast<size_t>(frame)];
    }

    const std::vector<float>& channel(int32_t ch) const { return channels_[ch]; }

private:
    std::vector<std::vector<float>> channels_;
    int64_t frames_     = 0;
    int32_t sampleRate_ = 0;
};

} // namespace grvr
