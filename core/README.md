# The shared DSP engine

This is the single source of truth for Grooverider's sound. The same C++ compiles to:

- **WebAssembly** for the browser build (`docs/index.html`, via `build-wasm.sh` → clang, no Emscripten). The page runs the whole instrument from it: grains and Observatory.
- **Android** (Oboe) — the NDK build includes `GrainCore.h` directly; `GrainEngine.h` in the app feeds it a source and pulls blocks.

No platform audio API, no threads, no STL, no allocation after init. One call — `render(out, nframes)` — is the entire hot path.

## Files
- `gmath.h` — tiny deterministic math (exp/log/pow/sin/tanh). In-core rather than libm/JS Math so window tables, pan and pitch come out **bit-identical** on phone and browser. That identity is what lets a Seed sound the same everywhere (spec §3.1).
- `GrainCore.h` — the engine: source buffer, window tables, sample-accurate scheduler, voice pool, Catmull-Rom interpolation, √overlap × windowRms gain compensation, equal-power pan + Haas, output stage (DC block / width / soft-sat / gain), one-pole smoothing, hashed deterministic RNG, grain-cloud snapshot. Also a voice cap with soft density limiting (`P_MAX_VOICES`), an optional per-grain anti-alias filter (`P_ANTI_ALIAS`, off by default), and host-sized source buffers (`setBuffers(l, r, capacity)`).
- `Observatory.h` — the layer around the grains that makes the instrument: Lorenz chaos swaying position, pitch and width together, the playhead servo and DRONE latch, scale-lock, keyboard notes, and the space (diffusion → 8-line FDN reverb → octave-up shimmer → tone). A C++ port of the web app's JavaScript `ObservatoryEngine`, which stays the reference for how it should sound. The host owns a `GrainCore` and calls `Observatory::render(core, out, n)` instead of `core.render`. Buffers are sized for 48 kHz; above that the space is bypassed. Both apps run it: Android through `GrainEngine` (switched on per Seed), the web through `grv_obs_render`. It began as a port of the web app's JavaScript engine, which it has now replaced.
- `Lorenz.h` — the one RK4 Lorenz integrator, used by the Observatory and by Android's mod matrix.
- `core_abi.cpp` — a flat C ABI (`grv_*`) exported to WASM, around one engine instance: `grv_render` for the grains alone, `grv_obs_render` for the whole instrument.
- `build-wasm.sh` / `reinline.sh` — build the wasm and re-embed it into the web app. The build needs a clang that can link WebAssembly; Apple's cannot, so the script falls back to the Android NDK's.
- `tests/` — native (`test_core.cpp`, `test_gmath.cpp`) and in-browser-runtime (`test_wasm.mjs`, run with Node) checks: click-free cloud, gain-comp flatness, pitch, stereo width, DC, determinism, voice cap, live position, anti-alias and long sources.

## Verify
```bash
c++ -O2 -I. -o /tmp/tc tests/test_core.cpp && /tmp/tc   # native DSP checks
c++ -O2 -I. -o /tmp/tg tests/test_gmath.cpp && /tmp/tg # math accuracy vs libm
./build-wasm.sh && node tests/test_wasm.mjs            # same checks through the wasm
# one brain: the native build and the wasm give the same samples
c++ -std=c++17 -O2 -ffp-contract=off -I. -o /tmp/parity tests/test_parity.cpp && /tmp/parity /tmp/parity.raw && node tests/test_parity.mjs /tmp/parity.raw
```

## One brain
The same source gives the same samples everywhere: `tests/test_parity` checks native against WebAssembly bit for bit, and `nativetest/` checks that an Android export is bit-identical to driving the engine the way the web page does. So with the same source audio at the same sample rate, a seed renders the same on the phone and in the browser. (Each app decodes audio files with its platform's own decoder, so a compressed file such as Big River can differ by a rounding step between them.)

## How Android uses it
`app/src/main/cpp/CMakeLists.txt` adds this folder to the include path, and `engine/GrainEngine.h` owns a `grv::GrainCore` and a `grv::Observatory` per engine instance (one realtime, one for offline render). A Seed says whether the Observatory is on; Seeds saved before it existed leave it off and drive the core directly. It uses the class directly rather than the C ABI, because the ABI's single static instance and fixed 60 s buffers suit the browser, not two engines side by side. `nativetest/` checks that the Android output is bit-identical to driving `GrainCore` by hand, and holds the Observatory's tests (`test_observatory.cpp`).

Notes for hosts:
- **Position.** `P_POSITION` is live: moving it carries the playhead along. Android's plain grain engine (Seeds saved before the Observatory) uses that. With the Observatory, position goes to `O_POSITION` and it steers the playhead through `P_DRIFT`.
- **Anti-alias.** Both apps turn `P_ANTI_ALIAS` on. Sped-up grains are a little duller with it than without.

Window ids here are 0 Gaussian, 1 Hann, 2 Tukey. Android's saved seeds number Tukey and Hann the other way round; `GrainEngine` translates.

## Seed files (`.grvr`)
One JSON format, read and written by both apps, so a seed saved on the phone opens in the browser and the other way round. `tests/fixtures/standing-room-only.grvr` is the example both sides are tested against.

```json
{
  "format": "grooverider-seed", "version": 1,
  "name": "Standing Room Only",
  "masterSeed": "0051a9d005700a11",
  "source": { "name": "Big River", "hash": "…" },
  "observatory": true,
  "params": { "density": 60, "grainMs": 2000, "timingJitter": 0.5, "sizeJitter": 0.35, "position": 0.42,
              "sprayMs": 900, "reverse": 0.3, "spread": 0.868, "window": "gaussian", "width": 1.4, "gain": 0.8,
              "chaos": 0.14, "pitch": 0.3, "key": 3, "scale": 3, "register": -12, "detune": 0.06,
              "drone": true, "space": 0.86, "shimmer": 0.42, "tone": 0.52, "scan": 0 },
  "plain": { "drift": 0, "pitchSt": 0, "pitchSpraySt": 0, "chaosRate": 0.3, "chaosEnabled": false }
}
```

- `masterSeed` is 16 hex digits as text: a 64-bit number does not survive being a JavaScript number.
- `params` holds what the engine is given, under the names the Observatory uses. `window` is `gaussian`, `hann` or `tukey` by name, because the two apps number them differently.
- `source` names the sound; a seed does not carry audio. `hash` is the phone's content hash and is absent in files from the web. Each app puts the seed on the matching sound if it has it, and otherwise on the one that is loaded.
- `observatory: false` marks a Seed made on the phone's plain grain engine. `plain` then holds what only that engine uses. The web has no such engine and opens these as the nearest Observatory settings.
- A reader ignores what it does not know, defaults what is missing, and refuses a `version` newer than its own.
