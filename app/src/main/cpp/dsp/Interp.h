#pragma once

namespace grvr {

/// 4-point cubic Hermite / Catmull-Rom interpolation (spec 2.6). Linear is
/// audibly dull and adds broadband noise on transposed grains; sinc is
/// unnecessary at these densities.
inline float hermite(float xm1, float x0, float x1, float x2, float t) noexcept {
    const float c = (x1 - xm1) * 0.5f;
    const float v = x0 - x1;
    const float w = c + v;
    const float a = w + v + (x2 - x0) * 0.5f;
    const float b = w + a;
    return ((((a * t) - b) * t + c) * t + x0);
}

} // namespace grvr
