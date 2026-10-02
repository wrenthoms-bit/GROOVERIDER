# Android Observatory — build brief

**Goal:** bring the Android app up to the sound and feel of the live web app
(https://grooverider.netlify.app). It doesn't need to match the web app feature for feature.
The guiding principle is **"shared brain, native body"**: the DSP is shared C++ and sounds the
same on both platforms, while the UI and visuals are built natively for Android.

## Where things stand (Oct 2026)

**Android app** (`app/`): goes well beyond M1. The repo already contains a grain engine
(`engine/GrainScheduler`, `VoicePool`), a mod engine (`mod/Lfo`, `DriftGen`, `Lorenz`,
`ModMatrix`), an offline renderer, seeds (Room: `seed/*`), the cloud screen with gestures,
a seed library, onboarding, render/share/WAV export, telemetry, and help dialogs.

**Web app** (`docs/index.html`): uses the shared `core/GrainCore.h` (compiled to WASM) for
the grains. The "Observatory" layer around it is written in **JavaScript**: the
`ObservatoryEngine` class, `docs/index.html` lines ~379–656. **That class is the reference
implementation for this brief.** It contains:

- Lorenz chaos (RK4, seeded initial condition), driving position, pitch and width together; rate/depth curves are in `_control()`
- Playhead servo + **DRONE** latch (rate halved, depth floor 0.5, latched range 0.6×, "breath" gain swell)
- **Scale-lock**: key, scale masks (free/chromatic/major/minor/pent-maj/pent-min/fifths), register, detune
- **Space**: send high-pass → 4 allpass diffusers per side → 8-line FDN (Householder, damped, slowly modulated delays) → **shimmer** (octave-up windowed pitch shift fed back into the send, channels crossed) → **tone** low-pass; smoothed wet/dry
- MIDI keyboard chord → pitch centres (up to 16 notes)
- Presets: **Standing Room Only** (flagship, default), Glass Tide, Ember Churn, Bare Grains (`PRESETS`, ~line 751)
- Sample library with **Big River** (`docs/samples/big-river.m4a`)
- Visuals (~lines 1269–1583): WebGL caustic/aurora water, mote sprites, feedback trails, bloom, "spectral tide" waterfall; Canvas2D fallback
- Start-screen tips + help panel

**The main structural problem:** there are now **two grain engines**, Android's
`GrainScheduler` and the web's `core/GrainCore.h`. Seeds won't sound the same on both until
that's resolved.

*Update, 2 Oct 2026:* resolved on `feat/android-observatory`. After the Phase 0 audit Wren chose
option (a): `GrainScheduler` and `VoicePool` are gone and Android now runs `core/GrainCore.h`
through `engine/GrainEngine.h`. Wherever a later phase says "the Android engine", that is what it means.

## Working rules

- Branch `feat/android-observatory` off an up-to-date `main`. If the working tree is dirty or you're on another branch, **stop and ask Wren** before switching.
- One commit per phase, with a clear message. Don't push without asking.
- Don't edit `docs/` (the live web app) until Phase 5.
- Keep `-ffp-contract=off`. Never use `-ffast-math`. Audio thread: no allocation, no locks, no logging. Flush denormals.
- `ParamId.h` and `ParamId.kt` must stay in exact sync. Add new IDs at the end; never renumber existing ones.
- New seed fields need defaults, so existing saved seeds still load and sound the same.
- Don't touch `gradle/libs.versions.toml` or AGP/Kotlin versions unless a phase truly needs it, and say so if you do.
- After each phase: build in Android Studio, run the `nativetest/` harness, and give Wren a short report covering what changed, what to test on the phone (realme RMX3943), and any risks. **Wait for his OK before starting the next phase.**

## Phase 0 — Audit (report only, no code changes)

1. Confirm the app builds and runs. Map what exists against `GROOVERIDER_SPEC.md` milestones M0–M8 (done / partial / missing).
2. Compare `GrainScheduler` with `core/GrainCore.h`: params, ranges, RNG/determinism scheme, windows, gain compensation, and sound. Render the same settings + seed from both offline (nativetest) and compare RMS, spectral centroid, and how they sound.
3. Recommend one of: (a) Android adopts `core/GrainCore.h` behind the existing `Engine` API, or (b) keep both for now. Say what each option means for existing saved seeds. Wren's preferred end state is one grain engine on both platforms. Don't make the swap in this phase.

## Phase 1 — Shared Observatory FX in C++

- Create `core/Observatory.h`: a header-only C++ port of `ObservatoryEngine`'s chaos + drone + scale-lock + space/shimmer/tone. It must work with the Android engine now and compile freestanding (no STL, no heap after init, same constraints as `GrainCore.h`) so the web can use it in Phase 5.
- Use `float` with fixed buffers sized for 48 kHz max. Derive all randomness from the master seed (the existing hashed RNG, not mulberry32). It should sound like the web version, **not** be bit-identical to the JS.
- Reuse or replace `mod/Lorenz.h` so there's one Lorenz. Explain which you chose.
- Add params (append to `ParamId`): chaos, pitch amount, key, scale, register, detune, drone, space, shimmer, tone, scan. Ranges as in the JS `p` object.
- Tests in `nativetest/`: 10 minutes of Standing Room Only with no NaN/Inf, no denormal stalls and no DC creep, a reverb tail that decays to below −90 dBFS after input stops, and a bit-identical result from two renders with the same seed.
- Performance target: the full chain at 48 kHz uses less than 25% of one big core, with zero xruns over 5 minutes on the realme (use the existing `XrunTelemetry`).

## Phase 2 — Wire it in

- Integrate into the `Engine` audio callback **and** `OfflineRenderer`, so exports include the FX and reverb tail, just like the web export.
- Add the new params to JNI, `ParamId.kt`, `ParamState`/`Seed` (with defaults), seed JSON, mutation/breeding, and naming.
- Factory presets in `FactoryContent`: the 4 web presets, with **Standing Room Only as the default on first launch**.
- Add **Big River** as a bundled factory source (copy `docs/samples/big-river.m4a` into app assets; decode through the existing `AudioDecoder`).

## Phase 3 — The Observatory visuals, natively

- Port the WebGL shaders (caustic water, motes, trails, bloom, spectral tide) to **OpenGL ES 3.0** in a `GLSurfaceView` hosted in Compose via `AndroidView`. AGSL isn't an option because minSdk is 26.
- Feed it from the existing `GrainCloudSnapshot` (grain position/pitch/pan/age) plus an FFT of the output for the tide.
- Keep the current Canvas cloud as the fallback. Cap at 30 fps on battery, pause when backgrounded, and make sure visuals never touch the audio thread.

## Phase 4 — Native body

- Compose controls: the 4 macro rings (Texture / Drift / Space / Pitch, like the web), a **DRONE** button, key/scale pickers, a shimmer/tone detail sheet, and a preset picker showing each preset's subtitle.
- Keep the existing cloud gestures. Add haptic ticks on detents.
- **USB MIDI** via `android.media.midi`: Wren's Akai MPK Mini over USB-C. CC learn (long-press a control, move a knob; store the mapping) and keys → chord pitch centres, with sustain pedal, matching the web behaviour.
- Port the web's start-screen tips into the existing `HelpDialog` (headphones, long sustained sources make the best drones, and the drone recipe: DRONE on, SPACE high, DRIFT low, then leave it alone).

## Phase 5 — One brain (optional, ask first)

- Compile `core/Observatory.h` into the web WASM (`core/build-wasm.sh` / `reinline.sh`) and replace the JS `ObservatoryEngine`, so web and Android produce identical audio for the same seed.
- Use a shared seed file format so a seed saved on the phone opens on the web, and vice versa.

## Definition of done

Standing Room Only on the phone, with headphones on, is hard to tell apart from the web version, and stays smooth and click-free for 10 minutes. The WAV export matches what you hear. Wren would rather open the phone app than the website.
