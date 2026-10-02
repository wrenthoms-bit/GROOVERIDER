#pragma once
#include "gmath.h"

// ============================================================================
// Lorenz — the one chaos integrator (spec 4.3). Three correlated, never-
// repeating streams from one attractor, stepped with RK4: Euler drifts off
// the attractor on a system this stiff and can diverge to infinity.
//
// Only the integration lives here. How fast it runs and how x/y/z are scaled
// for use belongs to the caller: the Observatory uses the web app's curves,
// Android's mod matrix uses spec 4.3's. Freestanding: no STL, no libm.
// ============================================================================

namespace grv {

struct Lorenz {
    float x = 0.1f, y = 0.1f, z = 0.1f;

    void set(float x0, float y0, float z0){ x = x0; y = y0; z = z0; }

    // advance by dt attractor time-units
    void step(float dt){
        const float k1x = dx(x, y),    k1y = dy(x, y, z),    k1z = dz(x, y, z);
        const float x2 = x + k1x*dt*0.5f, y2 = y + k1y*dt*0.5f, z2 = z + k1z*dt*0.5f;
        const float k2x = dx(x2, y2),  k2y = dy(x2, y2, z2), k2z = dz(x2, y2, z2);
        const float x3 = x + k2x*dt*0.5f, y3 = y + k2y*dt*0.5f, z3 = z + k2z*dt*0.5f;
        const float k3x = dx(x3, y3),  k3y = dy(x3, y3, z3), k3z = dz(x3, y3, z3);
        const float x4 = x + k3x*dt, y4 = y + k3y*dt, z4 = z + k3z*dt;
        const float k4x = dx(x4, y4),  k4y = dy(x4, y4, z4), k4z = dz(x4, y4, z4);
        x += (dt / 6.0f) * (k1x + 2.0f*k2x + 2.0f*k3x + k4x);
        y += (dt / 6.0f) * (k1y + 2.0f*k2y + 2.0f*k3y + k4y);
        z += (dt / 6.0f) * (k1z + 2.0f*k2z + 2.0f*k3z + k4z);
    }

    // true once any coordinate has left its bound or stopped being a number.
    // Callers reset to their seeded start: cheap insurance against a numerical
    // excursion becoming a full-scale DC blast in someone's headphones.
    bool escaped(float limX, float limY, float limZ) const {
        return !(gabs(x) < limX) || !(gabs(y) < limY) || !(gabs(z) < limZ);
    }

private:
    static constexpr float SIGMA = 10.0f, RHO = 28.0f, BETA = 8.0f / 3.0f;
    static float dx(float x, float y){ return SIGMA * (y - x); }
    static float dy(float x, float y, float z){ return x * (RHO - z) - y; }
    static float dz(float x, float y, float z){ return x * y - BETA * z; }
};

} // namespace grv
