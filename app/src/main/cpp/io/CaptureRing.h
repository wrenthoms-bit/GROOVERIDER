#pragma once
#include <atomic>
#include <cstdint>
#include <vector>

namespace grvr {

/// Always-recording 60 s circular buffer of engine output (spec 6.1). You
/// cannot hit record fast enough for a happy accident, so the engine never
/// stops capturing. int16 interleaved stereo: 48000*60*2*2 = 11.5 MB.
/// Single writer (audio thread), single reader (UI, after freeze) -- the
/// reader only runs once the ring has been logically frozen by the caller,
/// so there is no concurrent-read/write hazard to guard against here.
class CaptureRing {
public:
    void configure(int32_t sampleRate, double maxSeconds = 60.0) {
        sampleRate_ = sampleRate;
        capacityFrames_ = static_cast<int64_t>(maxSeconds * sampleRate);
        buffer_.assign(static_cast<size_t>(capacityFrames_) * 2, 0);
        writeIndex_.store(0, std::memory_order_relaxed);
        totalWritten_.store(0, std::memory_order_relaxed);
    }

    /// Audio thread. `l`/`r` are the final post-output-stage samples.
    inline void write(float l, float r) noexcept {
        if (capacityFrames_ <= 0) return;
        const int64_t w = writeIndex_.load(std::memory_order_relaxed);
        buffer_[static_cast<size_t>(w) * 2]     = floatToI16(l);
        buffer_[static_cast<size_t>(w) * 2 + 1] = floatToI16(r);
        const int64_t next = (w + 1) % capacityFrames_;
        writeIndex_.store(next, std::memory_order_release);
        totalWritten_.fetch_add(1, std::memory_order_relaxed);
    }

    int32_t sampleRate() const noexcept { return sampleRate_; }
    int64_t capacityFrames() const noexcept { return capacityFrames_; }

    /// UI thread, after the engine has been told to hold still (or just
    /// tolerating the tiny race of one in-flight write -- a single frame
    /// glitch in an 11.5 MB capture is inaudible). Returns up to the last
    /// `seconds` of audio, oldest-first, interleaved int16.
    std::vector<int16_t> snapshot(double seconds) const {
        if (capacityFrames_ <= 0) return {};
        const int64_t total = totalWritten_.load(std::memory_order_acquire);
        const int64_t available = total < capacityFrames_ ? total : capacityFrames_;
        int64_t wantFrames = static_cast<int64_t>(seconds * sampleRate_);
        if (wantFrames > available) wantFrames = available;
        if (wantFrames <= 0) return {};

        const int64_t w = writeIndex_.load(std::memory_order_acquire);
        // Oldest frame we're keeping is `wantFrames` behind the write head.
        int64_t start = (w - wantFrames + capacityFrames_ * 4) % capacityFrames_;

        std::vector<int16_t> out(static_cast<size_t>(wantFrames) * 2);
        for (int64_t i = 0; i < wantFrames; ++i) {
            const int64_t src = (start + i) % capacityFrames_;
            out[static_cast<size_t>(i) * 2]     = buffer_[static_cast<size_t>(src) * 2];
            out[static_cast<size_t>(i) * 2 + 1] = buffer_[static_cast<size_t>(src) * 2 + 1];
        }
        return out;
    }

private:
    static inline int16_t floatToI16(float v) noexcept {
        const float c = v < -1.0f ? -1.0f : (v > 1.0f ? 1.0f : v);
        return static_cast<int16_t>(c * 32767.0f);
    }

    std::vector<int16_t> buffer_;
    int32_t sampleRate_ = 48000;
    int64_t capacityFrames_ = 0;
    std::atomic<int64_t> writeIndex_ {0};
    std::atomic<int64_t> totalWritten_ {0};
};

} // namespace grvr
