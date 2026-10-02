# GrainCore — the shared DSP engine

This is the single source of truth for Grooverider's sound. The same C++ compiles to:

- **WebAssembly** for the browser build (`docs/index.html`, via `build-wasm.sh` → clang, no Emscripten).
- **Android** (Oboe) — the NDK build includes `GrainCore.h` directly; `GrainEngine.h` in the app feeds it a source and pulls blocks.

No platform audio API, no threads, no STL, no allocation after init. One call — `render(out, nframes)` — is the entire hot path.

## Files
- `gmath.h` — tiny deterministic math (exp/log/pow/sin/tanh). In-core rather than libm/JS Math so window tables, pan and pitch come out **bit-identical** on phone and browser. That identity is what lets a Seed sound the same everywhere (spec §3.1).
- `GrainCore.h` — the engine: source buffer, window tables, sample-accurate scheduler, voice pool, Catmull-Rom interpolation, √overlap × windowRms gain compensation, equal-power pan + Haas, output stage (DC block / width / soft-sat / gain), one-pole smoothing, hashed deterministic RNG, grain-cloud snapshot. Also a voice cap with soft density limiting (`P_MAX_VOICES`), an optional per-grain anti-alias filter (`P_ANTI_ALIAS`, off by default), and host-sized source buffers (`setBuffers(l, r, capacity)`).
- `Observatory.h` — the layer around the grains that makes the instrument: Lorenz chaos swaying position, pitch and width together, the playhead servo and DRONE latch, scale-lock, keyboard notes, and the space (diffusion → 8-line FDN reverb → octave-up shimmer → tone). A C++ port of the web app's JavaScript `ObservatoryEngine`, which stays the reference for how it should sound. The host owns a `GrainCore` and calls `Observatory::render(core, out, n)` instead of `core.render`. Buffers are sized for 48 kHz; above that the space is bypassed. Not yet used by either app: Android wires it in next, the web switches to it last.
- `Lorenz.h` — the one RK4 Lorenz integrator, used by the Observatory and by Android's mod matrix.
- `core_abi.cpp` — a flat C ABI (`grv_*`) exported to WASM, around one static engine instance.
- `build-wasm.sh` / `reinline.sh` — build the wasm and re-embed it into the web app.
- `tests/` — native (`test_core.cpp`, `test_gmath.cpp`) and in-browser-runtime (`test_wasm.mjs`, run with Node) checks: click-free cloud, gain-comp flatness, pitch, stereo width, DC, determinism, voice cap, live position, anti-alias and long sources.

## Verify
```bash
c++ -O2 -I. -o /tmp/tc tests/test_core.cpp && /tmp/tc   # native DSP checks
c++ -O2 -I. -o /tmp/tg tests/test_gmath.cpp && /tmp/tg # math accuracy vs libm
./build-wasm.sh && node tests/test_wasm.mjs            # same checks through the wasm
```

## How Android uses it
`app/src/main/cpp/CMakeLists.txt` adds this folder to the include path, and `engine/GrainEngine.h` owns a `grv::GrainCore` per engine instance (one realtime, one for offline render). It uses the class directly rather than the C ABI, because the ABI's single static instance and fixed 60 s buffers suit the browser, not two engines side by side. `nativetest/` checks that the Android output is bit-identical to driving `GrainCore` by hand, and holds the Observatory's tests (`test_observatory.cpp`).

Two things differ by host on purpose:
- **Position.** `P_POSITION` is live: moving it carries the playhead along. Android's XY pad uses that. The web sets it once per source and steers the playhead through `P_DRIFT` instead, which behaves exactly as before.
- **Anti-alias.** Android turns `P_ANTI_ALIAS` on; the web leaves it off. Sped-up grains are a little duller with it on.

Window ids here are 0 Gaussian, 1 Hann, 2 Tukey. Android's saved seeds number Tukey and Hann the other way round; `GrainEngine` translates.
