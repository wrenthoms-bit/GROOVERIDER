#pragma once
#include <atomic>
#include <cstdint>
#include <algorithm>
#include <vector>

namespace grvr {

/// Fixed-capacity capture target for the mic recorder. The audio (input)
/// thread appends interleaved frames; the UI thread reads the level and, after
/// stop, copies the captured range out. Preallocated to the 60 s cap so the
/// input callback never allocates.
class CaptureBuffer {
public:
    void prepare(int32_t channels, int32_t sampleRate, double maxSeconds) {
        channels_   = channels < 1 ? 1 : channels;
        sampleRate_ = sampleRate;
        capFrames_  = static_cast<int64_t>(maxSeconds * sampleRate_);
        data_.assign(static_cast<size_t>(capFrames_) * channels_, 0.0f);
        writeFrames_.store(0, std::memory_order_release);
        peak_.store(0.0f, std::memory_order_release);
    }

    /// Input callback. Appends up to the cap; returns frames actually stored.
    int32_t append(const float* interleaved, int32_t numFrames) noexcept {
        int64_t w = writeFrames_.load(std::memory_order_relaxed);
        int32_t n = static_cast<int32_t>(std::min<int64_t>(numFrames, capFrames_ - w));
        float pk = 0.0f;
        for (int32_t i = 0; i < n * channels_; ++i) {
            const float v = interleaved[i];
            data_[static_cast<size_t>(w * channels_ + i)] = v;
            const float a = v < 0 ? -v : v;
            if (a > pk) pk = a;
        }
        if (pk > peak_.load(std::memory_order_relaxed))
            peak_.store(pk, std::memory_order_release);
        writeFrames_.store(w + n, std::memory_order_release);
        return n;
    }

    int64_t capturedFrames() const noexcept { return writeFrames_.load(std::memory_order_acquire); }
    int32_t channels()       const noexcept { return channels_; }
    int32_t sampleRate()     const noexcept { return sampleRate_; }
    bool    isFull()         const noexcept { return capturedFrames() >= capFrames_; }

    /// Instantaneous peak since the last read (metering); resets on read.
    float takePeak() noexcept { return peak_.exchange(0.0f, std::memory_order_acq_rel); }

    /// Deinterleave the captured range into per-channel vectors (UI thread).
    std::vector<std::vector<float>> extractChannels() const {
        const int64_t f = capturedFrames();
        std::vector<std::vector<float>> ch(static_cast<size_t>(channels_));
        for (int c = 0; c < channels_; ++c) {
            ch[c].resize(static_cast<size_t>(f));
            for (int64_t i = 0; i < f; ++i)
                ch[c][static_cast<size_t>(i)] = data_[static_cast<size_t>(i * channels_ + c)];
        }
        return ch;
    }

private:
    std::vector<float>   data_;
    int32_t              channels_   = 1;
    int32_t              sampleRate_ = 48000;
    int64_t              capFrames_  = 0;
    std::atomic<int64_t> writeFrames_ {0};
    std::atomic<float>   peak_        {0.0f};
};

} // namespace grvr
