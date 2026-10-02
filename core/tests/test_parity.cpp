// One brain: the same C++ gives the same samples natively and as WebAssembly.
// This renders "Standing Room Only" natively and writes the raw float output;
// test_parity.mjs renders the same through grooverider.wasm and compares.
//
//   c++ -std=c++17 -O2 -ffp-contract=off -I. -o /tmp/parity tests/test_parity.cpp && /tmp/parity /tmp/parity.raw
//   node tests/test_parity.mjs /tmp/parity.raw
#include "Observatory.h"
#include <cmath>
#include <cstdio>
#include <memory>
#include <vector>
using namespace grv;

int main(int argc, char** argv){
    if (argc < 2){ fprintf(stderr, "usage: test_parity <output.raw>\n"); return 2; }
    const float sr = 48000.f; const double dur = 6.0; const int N = (int)(sr*dur);
    // the demo source of webtests/engine.test.mjs: six harmonics, each a whole number of cycles
    std::vector<float> l((size_t)N, 0.f), r;
    for (int h=1;h<=6;h++){ const double f = std::round(130.81*h*dur)/dur;
        // summed in double and rounded once per harmonic, as JavaScript's Float32Array "+=" does
        for (int i=0;i<N;i++) l[(size_t)i] = (float)((double)l[(size_t)i] + 0.25/h*std::sin(2*M_PI*f*i/(double)sr+h)); }
    r = l; for (auto& v : r) v *= 0.9f;                                  // a different right channel, to exercise stereo
    auto core = std::make_unique<GrainCore>(); auto obs = std::make_unique<Observatory>();
    core->setBuffers(l.data(), r.data(), N); core->init(sr); obs->init(sr);
    core->setSeed(0x0051A9D005700A11ull); obs->setSeed(0x0051A9D005700A11ull);
    const float cp[][2] = {{P_DENSITY,60},{P_TIMING_JITTER,0.5f},{P_GRAIN_MS,2000},{P_SIZE_JITTER,0.35f},{P_REVERSE_PROB,0.3f},
                           {P_WINDOW,0},{P_OUT_GAIN,0.8f},{P_PLAYING,1},{P_ANTI_ALIAS,1}};
    for (auto& p : cp) core->setParamNow((int)p[0], p[1]);
    const float op[O_COUNT] = { 0.42f, 0, 900, 0.868f, 1.4f, 0.14f, 0.30f, 3, 3, -12, 0.06f, 1, 0.86f, 0.42f, 0.52f };
    for (int i=0;i<O_COUNT;i++) obs->setParam(i, op[i]);
    core->setParamNow(P_POSITION, 0.42f); core->setSource(2, N); obs->sourceChanged(0.42f);
    const float chord[3] = {0, 7, -12};
    std::vector<float> o(128*2); FILE* f = fopen(argv[1], "wb"); if (!f) return 2;
    for (int b=0; b<(int)(sr*30/128); b++){
        if (b == 4000) obs->setNotes(chord, 3);                          // keys come in part-way through
        if (b == 8000) obs->setParam(O_DRONE, 0), obs->setParam(O_SCAN, 0.2f), obs->setParam(O_CHAOS, 0.8f);
        obs->render(*core, o.data(), 128); fwrite(o.data(), 4, 256, f);
    }
    fclose(f);
    return 0;
}
