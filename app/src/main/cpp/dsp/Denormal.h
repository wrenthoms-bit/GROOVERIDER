#pragma once
#include <cstdint>
#if defined(__i386__) || defined(__x86_64__)
  #include <xmmintrin.h>
  #include <pmmintrin.h>
#endif

namespace grvr {

/// Flush denormals to zero on the calling thread (spec 2.9).
/// Long decaying tails generate denormals, and on some SoCs that is a ~10x
/// CPU cliff that shows up as dropouts, not as anything obviously wrong.
inline void enableFlushToZero() noexcept {
#if defined(__aarch64__)
    uint64_t fpcr = 0;
    asm volatile("mrs %0, fpcr" : "=r"(fpcr));
    fpcr |= (1ULL << 24);              // FZ
    asm volatile("msr fpcr, %0" : : "r"(fpcr));
#elif defined(__arm__)
    uint32_t fpscr = 0;
    asm volatile("vmrs %0, fpscr" : "=r"(fpscr));
    fpscr |= (1U << 24);
    asm volatile("vmsr fpscr, %0" : : "r"(fpscr));
#elif defined(__i386__) || defined(__x86_64__)
    _MM_SET_FLUSH_ZERO_MODE(_MM_FLUSH_ZERO_ON);
    _MM_SET_DENORMALS_ZERO_MODE(_MM_DENORMALS_ZERO_ON);
#endif
}

} // namespace grvr
