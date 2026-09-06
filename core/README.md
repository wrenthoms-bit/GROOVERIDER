# GrainCore — the shared DSP engine

This is the single source of truth for Grooverider's sound. The same C++ compiles to:

- **WebAssembly** for the browser build (`docs/index.html`, via `build-wasm.sh` → clang, no Emscripten).
- **Android** (Oboe) — the NDK build includes these same files; the platform layer just feeds it a source and pulls blocks.

No platform audio API, no threads, no STL, no allocation after init. One call — `render(out, nframes)` — is the entire hot path.

## Files
- `gmath.h` — tiny deterministic math (exp/sin/tanh). In-core rather than libm/JS Math so window tables, pan and pitch come out **bit-identical** on phone and browser. That identity is what lets a Seed sound the same everywhere (spec §3.1).
- `GrainCore.h` — the engine: source buffer, window tables, sample-accurate scheduler, voice pool, Catmull-Rom interpolation, √overlap × windowRms gain compensation, equal-power pan + Haas, output stage (DC block / width / soft-sat / gain), one-pole smoothing, hashed deterministic RNG, grain-cloud snapshot.
- `core_abi.cpp` — a flat C ABI (`grv_*`) exported to WASM and callable from JNI.
- `build-wasm.sh` / `reinline.sh` — build the wasm and re-embed it into the web app.
- `tests/` — native (`test_core.cpp`, `test_gmath.cpp`) and in-browser-runtime (`test_wasm.mjs`, run with Node) checks: click-free cloud, gain-comp flatness, pitch, stereo width, DC, and determinism.

## Verify
```bash
c++ -O2 -I. -o /tmp/tc tests/test_core.cpp && /tmp/tc   # native DSP checks
c++ -O2 -I. -o /tmp/tg tests/test_gmath.cpp && /tmp/tg # math accuracy vs libm
./build-wasm.sh && node tests/test_wasm.mjs            # same checks through the wasm
```

## Wiring into Android (M2)
Add these to the NDK build and call the core from the Oboe data callback instead of the M1 preview player:
```cmake
target_sources(grooverider PRIVATE ${CMAKE_SOURCE_DIR}/../../../../core/core_abi.cpp)
target_include_directories(grooverider PRIVATE ${CMAKE_SOURCE_DIR}/../../../../core)
```
The C ABI (`grv_render`, `grv_set_param`, `grv_set_source`, …) is the same surface the web app drives, so behaviour matches by construction.
