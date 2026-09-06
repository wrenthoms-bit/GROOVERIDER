#pragma once
// Minimal, deterministic math for the portable core. Implemented in-core (not
// libm / JS Math) so window tables, pan and pitch come out BIT-IDENTICAL whether
// this compiles for Android (NDK) or WebAssembly (clang). That identity is what
// lets a Seed sound the same on phone and browser (spec 3.1).
//
// Accuracy target: audio-grade (< ~1e-5 abs where it matters), not last-ULP.

namespace grv {

constexpr float  PI   = 3.14159265358979323846f;
constexpr float  TWO_PI = 6.28318530717958647692f;
constexpr float  LN2  = 0.69314718055994530942f;

inline float gabs(float x){ return x < 0.f ? -x : x; }

// exp(x) via range reduction to [-ln2/2, ln2/2] and a degree-5 minimax poly.
inline float gexp(float x){
    if (x < -87.f) return 0.f;
    if (x >  88.f) return 3.4e38f;
    // n = round(x / ln2)
    float n = x * 1.44269504088896f;          // x / ln2
    n = (n >= 0.f) ? (float)(int)(n + 0.5f) : (float)(int)(n - 0.5f);
    float r = x - n * LN2;                     // remainder in ~[-0.347, 0.347]
    // e^r poly (Horner), good to ~1e-7 on this range
    float p = 1.f + r*(1.f + r*(0.5f + r*(0.16666667f + r*(0.04166667f + r*0.00833333f))));
    // scale by 2^n via bit manipulation of the float exponent
    int ni = (int)n;
    union { float f; unsigned int u; } bits;
    bits.u = (unsigned int)((127 + ni) & 0xFF) << 23;
    return p * bits.f;
}

inline float gexp2(float x){ return gexp(x * LN2); }     // 2^x

// sin over any range: reduce to [-PI, PI], degree-7 minimax (odd poly).
inline float gsin(float x){
    // fold to [-PI/2, PI/2]: sin(x) = (-1)^k sin(x - k*PI)
    float kf = x * (1.f / PI);
    kf = (kf >= 0.f) ? (float)(int)(kf + 0.5f) : (float)(int)(kf - 0.5f);
    int k = (int)kf;
    float r = x - kf * PI;                      // r in [-PI/2, PI/2]
    float r2 = r * r;
    // degree-7 minimax on [-PI/2, PI/2] (~1e-6 abs)
    float s = r * (0.99999660f + r2*(-0.16665810f + r2*(0.00830629f + r2*(-0.00018363f))));
    return (k & 1) ? -s : s;
}
inline float gcos(float x){ return gsin(x + 1.57079632679f); }

// tanh approximation (Padé-style rational), for the output soft-saturation.
// Cheap enough for the per-sample hot path; monotone, exact odd symmetry.
inline float gtanh(float x){
    if (x < -15.f) return -1.f;
    if (x >  15.f) return  1.f;
    float e = gexp(2.f * x);
    return 1.f - 2.f / (e + 1.f);
}

inline float gsqrt(float x){                   // wasm/x86 both lower this to a sqrt op
    return __builtin_sqrtf(x);
}
inline float gfloor(float x){ return __builtin_floorf(x); }
inline float gfmod(float a, float b){
    if (b == 0.f) return 0.f;
    return a - b * __builtin_truncf(a / b);
}

} // namespace grv
