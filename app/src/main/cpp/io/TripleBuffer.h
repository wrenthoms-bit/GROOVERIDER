#pragma once
#include <atomic>

namespace grvr {

/// Triple-buffered snapshot, audio thread -> UI thread.
/// The writer never blocks and never waits for the reader; the reader always
/// gets the most recently published complete value (spec 1.2).
template <typename T>
class TripleBuffer {
public:
    /// Audio thread: fill this, then call publish().
    T& writeSlot() noexcept { return slots_[writeIdx_]; }

    void publish() noexcept {
        writeIdx_ = ready_.exchange(writeIdx_, std::memory_order_acq_rel);
    }

    /// Reader thread: copies out the newest published value.
    void read(T& out) noexcept {
        readIdx_ = ready_.exchange(readIdx_, std::memory_order_acq_rel);
        out      = slots_[readIdx_];
    }

private:
    T                slots_[3] {};
    int              writeIdx_ {0};
    int              readIdx_  {1};
    std::atomic<int> ready_    {2};
};

} // namespace grvr
