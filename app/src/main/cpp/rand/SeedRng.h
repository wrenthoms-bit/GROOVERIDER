#pragma once
#include <cstdint>

namespace grvr {

/// Stream IDs for grain-index-derived randomness (spec 3.2). Every stochastic
/// decision in the grain engine draws from one of these, so tweaking one
/// parameter's randomness never re-rolls another's.
enum StreamId : uint32_t {
    kStreamTiming     = 1,
    kStreamSize       = 2,
    kStreamPosition   = 3,
    kStreamPitch      = 4,
    kStreamPan        = 5,
    kStreamHaas       = 6,
    kStreamReverse    = 7,
    kStreamWindow     = 8,
    kStreamAmp        = 9,
    kStreamDriftA     = 16,
    kStreamDriftB     = 17,
    kStreamDriftC     = 18,
    kStreamChaosInit  = 32,
};

/// Deterministic hashed randomness (spec 3.1-3.2). Every value is a pure
/// function of (masterSeed, streamId, grainIndex) -- never a running stream --
/// so replaying the same grain index always reproduces the same draw,
/// regardless of block size, device, or how many times the engine has run.
class SeedRng {
public:
    static inline uint64_t splitmix64(uint64_t x) noexcept {
        x += 0x9E3779B97F4A7C15ull;
        x = (x ^ (x >> 30)) * 0xBF58476D1CE4E5B9ull;
        x = (x ^ (x >> 27)) * 0x94D049BB133111EBull;
        return x ^ (x >> 31);
    }

    /// [0, 1). Top 24 bits only -- exactly representable in float32, so the
    /// result never depends on rounding mode (spec 3.2).
    static inline float value(uint64_t masterSeed, uint32_t streamId, uint64_t grainIndex) noexcept {
        uint64_t h = splitmix64(masterSeed ^ (static_cast<uint64_t>(streamId) << 48) ^
                                 (grainIndex * 0x9E3779B97F4A7C15ull));
        h = splitmix64(h);
        return static_cast<float>(h >> 40) * 0x1.0p-24f;
    }

    /// [-1, 1)
    static inline float bipolar(uint64_t masterSeed, uint32_t streamId, uint64_t grainIndex) noexcept {
        return value(masterSeed, streamId, grainIndex) * 2.0f - 1.0f;
    }
};

} // namespace grvr
