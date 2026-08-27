#pragma once
#include <atomic>
#include <cstddef>
#include <cstdint>

namespace grvr {

/// One parameter change, UI thread -> audio thread.
struct ParamMsg {
    uint16_t id    = 0;
    float    value = 0.0f;
};

/// Lock-free single-producer / single-consumer ring.
/// Exactly one thread may push and exactly one may pop. A second producer
/// breaks this silently, so keep it to the UI thread (spec 8.3).
template <size_t N>
class ParamRing {
    static_assert(N >= 2 && (N & (N - 1)) == 0, "N must be a power of two");

public:
    /// Producer side. Returns false if the ring is full (never blocks).
    bool push(uint16_t id, float value) noexcept {
        const size_t w    = write_.load(std::memory_order_relaxed);
        const size_t next = (w + 1) & (N - 1);
        if (next == read_.load(std::memory_order_acquire)) return false;
        buf_[w] = ParamMsg{id, value};
        write_.store(next, std::memory_order_release);
        return true;
    }

    /// Consumer side (audio thread). Returns false when drained.
    bool pop(ParamMsg& out) noexcept {
        const size_t r = read_.load(std::memory_order_relaxed);
        if (r == write_.load(std::memory_order_acquire)) return false;
        out = buf_[r];
        read_.store((r + 1) & (N - 1), std::memory_order_release);
        return true;
    }

private:
    ParamMsg            buf_[N] {};
    std::atomic<size_t> write_ {0};
    std::atomic<size_t> read_  {0};
};

} // namespace grvr
