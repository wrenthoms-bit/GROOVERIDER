# GROOVERIDER
## Granular Micro-Texture Engine — Implementation Specification v1.0

**Platform:** Android (Kotlin / Jetpack Compose + C++17 / Oboe)
**Author:** Wren (Delrogue)
**Date:** August 2026
**v1 Sonic Priority:** Evolving pads & atmospheres

---

## 0. Product Definition

### 0.1 The one-sentence version

Grooverider turns any audio — a vocal chop, a stem from a session, a field recording made ten seconds ago — into an evolving atmospheric texture, and makes that texture **reproducible, nameable, and re-applicable to other sources**.

### 0.2 The thesis

A granular engine on its own is not a signature sound. Granular engines are everywhere: Alchemy, Portal, Borderlands, Output Portal, half of Max for Live. What none of them do well is make chaos *ownable*.

An accident you cannot reproduce is a nice afternoon. An accident you can name, save, mutate, and apply to a different vocal six months later is a **process** — and a consistent, idiosyncratic process applied across records is precisely what a signature sound is made of.

So the engine is the price of entry. The Seed system is the product.

### 0.3 Design commitments

| Commitment | Consequence |
|---|---|
| **Everything is deterministic.** | No `rand()`, no wall-clock, no `random_device` anywhere in the signal path. Every stochastic decision derives from a hashed seed. |
| **The recipe is separable from the source.** | A Seed can be re-pointed at any other audio file. Your recipes become portable processing signatures. |
| **You cannot hit record fast enough.** | The engine always remembers the last 60 seconds. Capture is retroactive. |
| **The phone is an upgrade, not a compromise.** | Multitouch gesture control of a grain cloud beats a mouse. If a feature is worse than doing it in Logic, it does not ship. |
| **Offline render is the deliverable.** | Realtime is the instrument; the WAV that lands in Logic is the product, and it is rendered at higher quality than what you heard. |

### 0.4 Explicit non-goals

- Not a DAW. No arrangement view, no mixer, no multitrack, no plugin host.
- No MIDI input in v1. This is not a playable keyboard instrument — it is a texture generator.
- No cloud, no accounts, no sync. Local-first, permanently.
- No effects rack beyond the built-in output stage (§2.9).
- No sub-10ms latency chase. Stability beats latency for a pad instrument (§8.1).

---

## 1. System Architecture

### 1.1 Module map

```
┌──────────────────────────────────────────────────────────────┐
│  UI LAYER — Kotlin / Jetpack Compose                         │
│                                                              │
│  CloudScreen        SeedLibraryScreen     SourceScreen       │
│  (performance)      (browse/mutate)       (import/record)    │
│         │                    │                    │          │
│         └────────────────────┴────────────────────┘          │
│                              │                               │
│                    EngineViewModel (StateFlow)               │
└──────────────────────────────┬───────────────────────────────┘
                               │
┌──────────────────────────────┴───────────────────────────────┐
│  BRIDGE LAYER — Kotlin ↔ JNI                                 │
│                                                              │
│  GrooveriderEngine.kt   ──►  ParamRingBuffer (lock-free SPSC)│
│  (thin, stateless)      ◄──  MeterSnapshot (triple-buffered) │
│                         ◄──  GrainCloudSnapshot (lock-free)  │
└──────────────────────────────┬───────────────────────────────┘
                               │
┌──────────────────────────────────────────────────────────────┐
│  AUDIO CORE — C++17, real-time safe                          │
│                                                              │
│  ┌────────────┐  ┌──────────────┐  ┌───────────────────┐     │
│  │ GrainSched │─►│  VoicePool   │─►│   OutputStage     │     │
│  │            │  │  (N grains)  │  │  (gain/width/dc)  │     │
│  └─────┬──────┘  └──────┬───────┘  └─────────┬─────────┘     │
│        │                │                    │               │
│  ┌─────┴──────┐  ┌──────┴───────┐  ┌─────────┴─────────┐     │
│  │ SeedRng    │  │ SourceBuffer │  │  CaptureRing (60s)│     │
│  │ (hashed)   │  │ (float32)    │  │                   │     │
│  └────────────┘  └──────┴───────┘  └───────────────────┘     │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐    │
│  │ ModEngine: DriftGen ×3 │ LFO ×2 │ Lorenz │ ModMatrix │    │
│  └──────────────────────────────────────────────────────┘    │
└──────────────────────────────┬───────────────────────────────┘
                               │
                    Oboe (AAudio) output stream
```

### 1.2 Threading model

| Thread | Owns | May not |
|---|---|---|
| **Audio callback** (Oboe, RT priority) | Grain scheduling, voice rendering, mod evaluation, capture ring writes | Allocate, lock, log, call JNI upward, touch Room/DataStore |
| **UI / Main** | Compose state, gesture handling, param writes into the ring buffer | Block on audio state |
| **Render worker** (Dispatchers.Default) | Offline render via a second engine instance on a virtual clock | Touch the realtime engine instance |
| **IO** (Dispatchers.IO) | Source decode/import, Room, WAV write, share intents | — |

**Param transport, UI → audio:** a single-producer/single-consumer lock-free ring buffer of `ParamMsg { id: u16, value: f32 }`. The audio thread drains the whole queue at the top of each callback, applies values into a `ParamState` struct, and smooths continuous params with one-pole slew (§2.8).

**State transport, audio → UI:** triple-buffered snapshots, published with a release-store index. The UI reads whichever buffer index is current. Two snapshot types:

- `MeterSnapshot` — peak L/R, active voice count, CPU load, xrun count, and granted buffer size. 30 Hz.
- `GrainCloudSnapshot` — up to 256 `{ sourcePos: f32, pitchRatio: f32, amp: f32, age: f32 }` records for visualisation. 60 Hz.

### 1.3 Source layout

```
app/
  src/main/
    java/com/delrogue/grooverider/
      ui/        cloud/ library/ source/ common/
      engine/    GrooveriderEngine.kt  ParamId.kt  SeedCodec.kt
      data/      SeedDao.kt  SourceDao.kt  Database.kt  SourceStore.kt
      render/    OfflineRenderer.kt  WavWriter.kt  ShareExporter.kt
    cpp/
      engine/    Engine.cpp/.h  GrainScheduler.*  VoicePool.*  Grain.*
      dsp/       Window.*  Interp.*  OutputStage.*  Resampler.*
      rand/      SeedRng.*  SplitMix64.*  Xoshiro.*
      mod/       DriftGen.*  Lfo.*  Lorenz.*  ModMatrix.*
      io/        SourceBuffer.*  CaptureRing.*  ParamRing.*
      jni/       jni_bridge.cpp
      CMakeLists.txt
```

**Dependencies:** Oboe (audio), Room (persistence), DataStore (settings), ExoPlayer/Media3 (source decode — already in your toolkit from Super Star Studio), Compose. No third-party DSP libraries.

---

## 2. The Grain Engine

### 2.1 Source buffer

- Deinterleaved `float32`, one array per channel, fully resident in RAM.
- Cap: **60 seconds**. At 48 kHz stereo that is 48000 × 60 × 2 × 4 = **23 MB**. Acceptable on any device from the last six years.
- Sources are resampled on import to the engine rate (device native — query it, never assume 48 k) with a windowed-sinc resampler. Resampling happens once, at import, never in the audio thread.
- Immutable once loaded. Swapping sources swaps the whole buffer under a double-buffer + atomic pointer flip, so the audio thread never sees a partial write.

### 2.2 The grain

```cpp
struct Grain {
    double  srcPos;        // fractional read position, samples
    double  rate;          // playback ratio (pitch); negative = reverse
    uint32_t age;          // samples elapsed since birth
    uint32_t life;         // total duration, samples
    float   amp;           // linear gain
    float   panL, panR;    // pre-computed equal-power coefficients
    uint16_t windowId;     // index into window table set
    float   chanOffset;    // stereo Haas offset in samples (§2.7)
    bool    active;
};
```

Fixed-capacity array, `MAX_GRAINS = 256`, with an active count and swap-remove on death. **Zero allocation after engine init.**

### 2.3 The scheduler

The scheduler is a sample-accurate onset clock. Per callback, it walks the block and spawns grains at exact sample offsets, so grain timing is never quantised to the buffer size — this matters enormously for the smoothness of dense clouds.

```
nextOnset -= blockSize
while (nextOnset < blockSize):
    spawnGrain(atOffset = nextOnset, index = grainCounter++)
    interval = sampleRate / density
    nextOnset += interval * (1 + jitter * rng(TIMING, grainCounter))
```

**Scheduler parameters**

| Param | Range | Pad default | Notes |
|---|---|---|---|
| `density` | 0.5 – 200 grains/s | **40** | Grains per second |
| `timingJitter` | 0 – 1 | 0.15 | 0 = metronomic (comb-filters), 1 = fully stochastic |
| `grainSize` | 5 – 2000 ms | **400** | |
| `sizeJitter` | 0 – 1 | 0.25 | Per-grain duration variance |
| `position` | 0 – 1 | — | Normalised playhead into source |
| `spray` | 0 – 5000 ms | **250** | Random ± window around `position` |
| `drift` | −2.0 – +2.0 | **0.05** | Playhead advance rate. 0 = frozen, 1 = realtime scan, negative = reverse |
| `pitch` | −24 – +24 st | 0 | |
| `pitchSpray` | 0 – 24 st | **0.15** | Continuous detune; heavy overlap makes small values chorus beautifully |
| `pitchSet` | off / oct / 5th / user | oct+5th | Quantise per-grain pitch to an interval set |
| `reverseProb` | 0 – 1 | 0.2 | Probability a grain plays backwards |
| `spread` | 0 – 1 | **0.8** | Stereo panning width |

The pad defaults above give overlap ≈ 16 (§2.5), which is the smooth-cloud regime.

### 2.4 Windowing

Precomputed tables, **4096 points**, `float32`, read with linear interpolation on a normalised phase (`age / life`). Linear interpolation of a 4096-point window is inaudible; cubic here is wasted CPU.

| Window | Use |
|---|---|
| **Gaussian** (σ = 0.18, pedestal-removed) | Default for pads. Smoothest spectrum, no sidelobes. |
| **Tukey** (plateau 0.0–0.9, adjustable) | More body per grain; plateau 0 degenerates to Hann. |
| Expodec | Percussive attack — reserved for the glitch work in v2. |
| Rexpodec | Reverse-percussive — swells. |

**Hard rule:** every window must be **exactly zero** at phase 0 and 1. A window that does not reach zero produces a click per grain, and at 40 grains/second that is a 40 Hz buzz, not a texture.

**A Gaussian does not satisfy this on its own — this will bite you.** A truncated Gaussian has a non-zero pedestal at the edges, and it is much larger than intuition suggests:

| σ | Raw value at grain edge |
|---|---|
| 0.30 | 0.249 (**−12 dBFS**) |
| 0.25 | 0.135 (−17 dBFS) |
| 0.18 | 0.021 (−34 dBFS) |
| 0.12 | 0.00017 (−75 dBFS) |

Every one of those clicks. Fix it by removing the pedestal and renormalising, which costs one subtract and one multiply at table-build time and is exactly zero at both edges:

```cpp
const float g0 = std::exp(-0.5f * (0.5f/sigma) * (0.5f/sigma));
for (int i = 0; i < N; ++i) {
    float x = float(i) / float(N - 1);
    float g = std::exp(-0.5f * ((x - 0.5f)/sigma) * ((x - 0.5f)/sigma));
    table[i] = (g - g0) / (1.0f - g0);
}
```

**Window RMS feeds back into gain compensation.** Window shape changes output level independently of overlap — a pedestal-removed Gaussian has RMS 0.461 at σ = 0.12 and 0.631 at σ = 0.25, a 2.7 dB difference. So the full compensation is:

```cpp
float gainComp = 1.0f / (windowRms[windowId] * std::sqrt(std::max(1.0f, overlap)));
```

with `windowRms` computed once per table at build time. Without this, changing window shape is a hidden volume control.

### 2.5 Overlap and gain compensation

```
overlap = density × grainSize(seconds)
```

Grains have effectively uncorrelated phase, so they sum incoherently: amplitude grows as **√overlap**, not overlap. Without compensation, the density knob doubles as a volume knob — which is the single most common failure in amateur granular engines and instantly makes the instrument unplayable.

```cpp
float gainComp = 1.0f / (windowRms * std::sqrt(std::max(1.0f, overlap)));
```

Apply as a smoothed value (§2.8), not per-block, or density automation zippers.

**Verified.** Simulated across a 40× density sweep (5 → 200 grains/s):

| Source | Systematic level trend | Per-realisation variance |
|---|---|---|
| Noise-like | **0.05 dB** | negligible |
| Tonal, no pitch spray | 1.61 dB | ±0.8 dB |
| Tonal, pitchSpray 0.15 st | **0.64 dB** | ±0.8 dB |

Two things fall out of this, and the second one is the useful one:

1. The √overlap law is *correct*, not approximate. What looks like level drift in a single render is per-realisation variance (σ ≈ 0.5–0.9 dB) — the cloud breathing. It averages out and should not be "fixed". **Do not add an RMS auto-leveller on top of this** — it fights the instrument's natural dynamics and makes dense settings sound compressed and dead.

2. **A little pitch spray is load-bearing.** On a tonal source with zero detune, neighbouring grains stay partially phase-correlated, summation drifts toward coherent (∝ N rather than √N), and the systematic trend more than doubles. The 0.15 st default in §2.3 is not a decorative chorus — it is what keeps the density knob from becoming a volume knob on sustained material. If a future preset sets `pitchSpray = 0`, expect level drift and do not blame the gain compensation.

**Voice cap:** peak concurrent grains ≈ `ceil(overlap × (1 + sizeJitter)) + 2`. At the ceiling (200 grains/s × 2000 ms) that is 400+ — beyond `MAX_GRAINS`. Rather than hard-clipping the count, **soft-limit the density** so `density ≤ MAX_GRAINS / grainSize`, and surface it in the UI as the cloud visibly saturating. Stealing grains mid-flight causes clicks; refusing to spawn them does not.

### 2.6 Interpolation

**Cubic Hermite (Catmull-Rom), 4-point.** Linear interpolation on transposed grains is audibly dull and adds broadband noise; sinc is unnecessary at these densities.

```cpp
inline float hermite(float xm1, float x0, float x1, float x2, float t) {
    float c = (x1 - xm1) * 0.5f;
    float v = x0 - x1;
    float w = c + v;
    float a = w + v + (x2 - x0) * 0.5f;
    float b = w + a;
    return ((((a * t) - b) * t + c) * t + x0);
}
```

**Aliasing:** for `|rate| > 1` the read is undersampling the source. Rather than oversample the whole engine, apply a per-grain one-pole lowpass at `min(nyquist, nyquist / |rate|)` when `|rate| > 1.2`. In the offline renderer, replace this with 2× oversampling of the grain read (§6.3) — cheap, since offline is not realtime-bound.

### 2.7 Stereo strategy

- **Mono source:** per-grain equal-power pan, `θ = (rng(PAN, idx) - 0.5) × spread × π/2`, `panL = cos(θ + π/4)`, `panR = sin(θ + π/4)`.
- **Stereo source:** read both channels, then apply the same rotation as a mid/side-preserving width control.
- **Haas spread (the good trick):** give each grain a small per-channel source-position offset, `chanOffset = rng(HAAS, idx) × spread × 0.010 × sampleRate` (0–10 ms). The right channel reads from a slightly different point in the source than the left. On a mono vocal this produces instant, natural, non-phasey width that no amount of chorus achieves — and because it is derived from source position rather than delay, it never collapses in mono to a comb filter; it collapses to a slightly denser cloud.

### 2.8 Parameter smoothing

Every continuous param passes through a one-pole slew on the audio thread:

```cpp
current += (target - current) * coeff;   // coeff = 1 - exp(-1 / (tau * sampleRate))
```

| Param class | τ |
|---|---|
| Gain, gainComp, pan width | 20 ms |
| Position, drift, pitch | 50 ms |
| Density, grainSize | 80 ms (slow — these change grain-scheduling geometry) |

Discrete params (windowId, pitchSet, reverseProb) apply at the *next grain spawn*, never mid-grain.

### 2.9 Output stage

Minimal and fixed — this is not an effects rack:

1. **DC blocker** — one-pole highpass at 12 Hz. Non-negotiable: reverse grains and asymmetric windows accumulate DC offset, and DC eats headroom in Logic invisibly.
2. **Width** — mid/side gain, 0–200%.
3. **Soft saturation** — `tanh`-style at −3 dBFS, gentle. Catches stochastic peaks without a limiter's pumping.
4. **Output gain** + true-peak-aware meter.

Denormals: set the ARM FPSCR flush-to-zero bit on audio thread entry. Long tails of decaying grains generate denormals, and on some SoCs that is a 10× CPU cliff.

---

## 3. The Seed System

**This is the product.** Everything in §2 exists so that this section can work.

### 3.1 The determinism contract

> Given the same Seed and the same source audio, the engine produces **bit-identical output**, every time, on any device, in realtime or offline.

Three rules enforce it:

1. No `rand()`, `std::random_device`, wall-clock time, or thread-scheduling-dependent value ever reaches a sonic parameter.
2. All randomness is derived by **hashing**, not by advancing a stream.
3. Floating-point ops in the signal path avoid `-ffast-math` reassociation. Build with `-ffp-contract=off` for the render path.

### 3.2 Grain-index-derived randomness

This is the load-bearing architectural decision, and it is not the obvious one.

The naive approach is a running PRNG stream: each grain calls `rng.next()`. That works — until you realise the output then depends on *how many grains have been spawned since the engine started*. Hit play twice at different moments and you get different textures. Offline render diverges from what you heard. The whole thing collapses.

Instead, every random value is a **pure function** of `(masterSeed, streamId, grainIndex)`:

```cpp
inline float SeedRng::value(uint64_t masterSeed, uint32_t streamId, uint64_t grainIndex) {
    uint64_t h = splitmix64(masterSeed ^ (uint64_t(streamId) << 48) ^ (grainIndex * 0x9E3779B97F4A7C15ull));
    h = splitmix64(h);
    return float(h >> 40) * 0x1.0p-24f;   // [0, 1), exactly representable in float32
}
```

Take the top 24 bits, not 53. A 53-bit draw scaled by 2⁻⁵³ has to be rounded to fit a `float`, and rounding is exactly the kind of platform-dependent detail that quietly breaks the bit-identical guarantee in §3.1. 24 bits is exact in float32, gives ~16.7 M distinct values, and is far more resolution than any grain parameter needs.

Consequences, all of them good:

- Grain *n* has the same character whenever it is rendered.
- The offline renderer reproduces the realtime performance exactly, because it recreates the same grain indices.
- Block size, device, and CPU load are irrelevant to the sound.
- **Independent streams mean independent tweaks.** `PITCH_SPRAY` and `PAN` draw from different `streamId`s, so nudging pitch spray does not scramble the panning you liked. In a running-stream design, changing any random-consuming parameter re-rolls everything downstream — which is why so many "random" synth patches feel impossible to refine.

**Stream IDs:** `TIMING=1, SIZE=2, POSITION=3, PITCH=4, PAN=5, HAAS=6, REVERSE=7, WINDOW=8, AMP=9, DRIFT_A=16, DRIFT_B=17, DRIFT_C=18, CHAOS_INIT=32`.

`grainIndex` is a monotonic `uint64_t` reset only on transport restart, and is itself part of the captured performance (§6.2).

### 3.3 The Seed record

```kotlin
@Entity(tableName = "seeds")
data class Seed(
    @PrimaryKey val id: String,             // UUID
    val name: String,                       // auto-generated, user-renameable
    val masterSeed: Long,                   // 64-bit — the whole random universe
    val params: ByteArray,                  // packed ParamState snapshot (~512 B)
    val modRoutes: ByteArray,               // packed mod matrix (16 × 8 B)
    val sourceHash: String,                 // SHA-256 of source PCM — NOT a file path
    val inPointMs: Int,
    val outPointMs: Int,
    val parentId: String?,                  // lineage
    val mutationDistance: Float,            // how far from parent
    val lockedParams: Long,                 // bitmask, 64 params
    val favourite: Boolean,
    val renderCount: Int,
    val createdAt: Long,
    val waveformThumb: ByteArray            // 256-point peak envelope for the library grid
)
```

Total ≈ 1.2 KB per Seed. Ten thousand Seeds is 12 MB. Storage is a non-issue; design for abundance.

### 3.4 Source content-addressing — the feature that makes it a signature

A Seed references its source by **SHA-256 of the decoded PCM**, not by file path. Sources live in an internal content-addressed store (`files/sources/<hash>.pcm` + a `sources` table with the original name, duration, and import date).

This buys three things:

1. **Seeds survive** file renames, moves, and re-imports of the same audio.
2. **Deduplication** is free.
3. **Re-pointing.** A Seed can be applied to *any* source: `seed.copy(sourceHash = otherSource.hash)`. The recipe — the density curve, the drift geometry, the chaos routing, the master seed — stays intact while the material changes.

Point 3 is the signature-sound mechanic. A saved Seed applied across a vocal, a Rhodes stem, and a field recording of Perth traffic produces three textures that are *recognisably related* — same fingerprint, different material. That relatedness across a body of work is what a signature sound actually is.

UI affordance: long-press a Seed → **"Apply to…"** → source picker.

*Note:* position and spray are stored normalised (0–1 of source duration), so re-pointing to a source of a different length behaves musically rather than landing on silence.

### 3.5 Auto-naming

A Seed's name is derived deterministically from its `masterSeed` — the same seed always gets the same name, so names are stable and memorable rather than arbitrary.

```
name = adjective[hash % 128] + " " + noun[(hash >> 8) % 256]
```

with wordlists drawn from texture, weather, mineral, and light vocabulary — *Bitter Amber Drift*, *Slow Tin Halo*, *Ninth Ash Bloom*. Users can rename; the generated name persists as a subtitle.

### 3.6 Mutation

The exploration mechanic. From any Seed, generate **six children**:

```
mutate(parent, amount ∈ [0,1], childIndex):
    childSeed = splitmix64(parent.masterSeed ^ (childIndex * PHI64))
    for each param p not locked:
        σ = amount × sensitivity[p] × range[p]
        child.p = clamp(parent.p + gaussian(childSeed, p) × σ)
    if amount > 0.5 and rng > 0.7:
        perturb one random mod-matrix route (source, dest, or depth)
    child.parentId = parent.id
    child.mutationDistance = amount
```

**Per-param sensitivity weights** are the difference between a useful mutate button and a random-patch button. Some params tolerate large moves (`spray`, `pitchSpray`, `reverseProb`); some destroy the patch if moved much (`density`, `grainSize`, `drift`). Ship a hand-tuned sensitivity table — this is a *taste* parameter, and tuning it is how the app acquires your taste.

| Param | Sensitivity |
|---|---|
| spray, pitchSpray, spread, timingJitter, sizeJitter | 1.0 |
| reverseProb, pitch, window plateau | 0.7 |
| drift, position | 0.5 |
| density, grainSize | 0.25 |
| output gain | 0.0 (never mutated) |

**Param locks:** a 64-bit mask. Tap any control to padlock it. Locks are the mechanism by which mutation converges instead of wandering — you lock what you love and re-roll the rest.

**Lineage:** `parentId` forms a tree. The library shows it as an expandable ancestry so you can walk back to an earlier ancestor and branch differently. Every re-roll and mutation is therefore undoable by navigation, not by an undo stack.

### 3.7 Breeding (M7, optional)

Two parents → child: params linearly interpolated at a user-set blend, `masterSeed` inherited from whichever parent the blend favours, mod routes taken wholesale from one parent (interpolating a routing matrix produces nonsense, not a hybrid).

---

## 4. Modulation & Chaos

### 4.1 Why independent randomness fails

Route six independent random sources to six parameters and you get mush. Everything moves at once, in unrelated directions, and the ear reads it as noise rather than motion. This is why most "randomise" buttons produce nothing usable.

The fix is **correlated chaos**: one chaotic system with several outputs that are unpredictable *individually* but bound together by a shared underlying state. The ear hears coupled motion as intentional — as a system breathing — even when it cannot predict it. That perceived intent is the difference between a happy accident and a mess.

### 4.2 Modulation sources

| Source | Outputs | Character |
|---|---|---|
| **Lorenz** | `x`, `y`, `z` | The primary chaos engine. Three correlated, never-repeating streams. |
| **Drift A/B/C** | 1 each | Band-limited random walk — organic, slow, uncorrelated. |
| **LFO 1/2** | 1 each | Tempo-syncable, for when you want something to actually pulse. |
| **Macros 1–4** | 1 each | Direct user control (the XY pad axes and two knobs). |
| **Touch** | `x`, `y`, `pressure` | Live gesture, also recorded to the automation lane (§6.2). |
| **Global env** | 1 | Slow one-shot ramp for render-time trajectories (risers, later). |

### 4.3 The Lorenz system

```
dx/dt = σ(y − x)
dy/dt = x(ρ − z) − y
dz/dt = xy − βz          σ = 10, ρ = 28, β = 8/3
```

- Integrated with **RK4 at a 1 kHz control rate** (every `sampleRate/1000` samples). RK4 rather than Euler because Euler on a stiff chaotic system drifts off the attractor and can diverge to infinity — a silent bug that becomes a very loud one.
- Initial condition derived from the master seed: `x₀ = 0.1 + rng(CHAOS_INIT,0)`, likewise `y₀`, `z₀`. Deterministic, per §3.1.
- `chaosRate` maps `dt` logarithmically over **0.00002 – 0.005** time-units per control tick. At the low end a single orbit takes minutes — which is exactly the timescale a pad wants.
- Normalised for use: `xn = x/20`, `yn = y/25`, `zn = (z − 25)/25`, each soft-clipped to [−1, 1].
- **Guard:** if `|x| > 100` or any output is NaN, reset to the seeded initial condition. Cheap insurance against a numerical excursion turning into a full-scale DC blast in someone's headphones.

### 4.4 Modulation matrix

Fixed 16 slots, evaluated at control rate (1 kHz), summed per destination, then slewed (§2.8).

```cpp
struct ModRoute {
    uint8_t source;      // ModSource enum
    uint8_t dest;        // ParamId enum
    float   depth;       // bipolar, −1 .. +1
    uint8_t curve;       // LINEAR | EXP | SCURVE | QUANTISED
};
```

Destinations: any scheduler parameter, window plateau, output width, and — deliberately — `chaosRate` itself, so chaos can modulate its own speed.

### 4.5 Pad-first default patch ("First Light")

The state the app opens in on a fresh install. It must sound good on any dropped-in audio within two seconds, or the app has failed its first impression.

| Route | Source | Destination | Depth | Curve |
|---|---|---|---|---|
| 1 | Lorenz x | position | +0.18 | SCURVE |
| 2 | Lorenz y | pitchSpray | +0.35 | EXP |
| 3 | Lorenz z | spread | +0.25 | LINEAR |
| 4 | Drift A | grainSize | +0.20 | LINEAR |
| 5 | Drift B | density | +0.15 | LINEAR |
| 6 | Macro 1 (TEXTURE) | grainSize / density | ±1.0 | curated (§5.4) |
| 7 | Macro 2 (DRIFT) | chaosRate + all Lorenz depths | +1.0 | EXP |

Note routes 1–3: one chaotic system moving playhead, detune, and width *together*. The cloud appears to wander somewhere and take its colour with it.

### 4.6 Drift generators

Band-limited random walk, not a filtered noise source: pick a random target, interpolate to it with a cubic ease, pick the next.

```
if (phase >= 1.0) { prev = target; target = rng(DRIFT_x, step++); phase -= 1.0; }
phase += rate / controlRate;
out = prev + (target - prev) * smoothstep(phase);
```

`rate` spans 0.005 – 2 Hz. Deterministic via the `DRIFT_A/B/C` streams, so drift is part of the reproducible universe.

---

## 5. The Cloud — Performance UI

### 5.1 Orientation and philosophy

**Landscape, one screen, no menus during performance.** Two thumbs on the glass, the whole instrument visible. Portrait exists only for the library and source screens.

Anything that requires a menu during a performance is a design failure — you lose the moment while navigating.

```
┌────────────────────────────────────────────────────────────────┐
│  ◂ seed name          ▓▓ meter ▓▓        voices 87   ⏺ CAPTURE │  ← 8%
├────────────────────────────────────────────────────────────────┤
│                                                                │
│         THE CLOUD  —  waveform + live grain particles          │  ← 34%
│    ·  ·   ∙·∙  ·      ∙ · ∙∙ ·  ·    ∙   · ∙                   │
│  ▁▂▅▇█▇▅▃▂▁▂▄▆█▇▅▃▂▁▁▂▃▅▇█▆▄▂▁▂▃▅▆▇█▇▅▃▂▁▂▃▄▅▆▇▆▄▃▂▁          │
│              ▲ position          ░░ spray window ░░            │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│                        XY PERFORMANCE PAD                      │  ← 40%
│              X → position        Y → texture                   │
│                                                                │
├────────────────────────────────────────────────────────────────┤
│   TEXTURE      DRIFT       PITCH       SPACE      🔒  ⟳  ☰     │  ← 18%
└────────────────────────────────────────────────────────────────┘
```

### 5.2 The grain cloud visualisation

The single best UI decision available to a granular app: **draw the grains.**

Each active grain is a particle plotted over the source waveform:

| Visual property | Mapped from |
|---|---|
| X position | grain's source read position |
| Y position | pitch ratio (centre = unity, up = transposed up) |
| Radius | grain duration |
| Opacity | current window amplitude — so grains fade in and out as they live and die |
| Hue | subtle shift with pan (left cooler, right warmer) |

Rendered in Compose `Canvas` from the `GrainCloudSnapshot` at 60 fps, up to 256 particles. Additive blending on a near-black field.

This is not decoration. It is the fastest diagnostic instrument in the app: you can *see* density, spray width, pitch scatter and stereo spread at a glance, and you learn the parameter space in minutes rather than hours. It is also, frankly, the thing that will make you want to open the app.

### 5.3 Gesture map

| Gesture | Action |
|---|---|
| **1-finger drag on pad** | X = position, Y = texture macro |
| **1-finger lift** | Position holds where you left it (no snap-back) |
| **Long-press pad (400 ms)** | **Freeze** — playhead latches, drift → 0. Haptic tick. Press again to release. |
| **2-finger pinch** | Stereo spread / width |
| **2-finger vertical drag** | Pitch spray |
| **2-finger rotate** | Drift rate, including through zero into reverse |
| **Double-tap pad** | **Re-roll** — new master seed, params untouched. The "give me an accident" button. Pushes onto the lineage stack, so it is always navigable back. |
| **Three-finger tap** | **Capture** the last 60 s (§6.1) |
| **Swipe up from bottom** | Seed library drawer |
| **Swipe down from top** | Source picker |
| **Tap a macro label** | Padlock that param against mutation |
| **Drag on waveform** | Set in/out points |

Every gesture is one-handed-recoverable. Nothing requires precision — this is a texture instrument, not a piano.

### 5.4 The four macros

Curated, non-linear, and mapped so that **every position on every macro sounds good**. This is where taste is encoded. A macro that can produce a bad sound is a knob that has to be avoided, and a knob you avoid is UI you shouldn't have shipped.

**TEXTURE** — the master character control, a curated path through (grainSize, density, timingJitter):

| Position | grainSize | density | jitter | Character |
|---|---|---|---|---|
| 0.0 | 1200 ms | 12/s | 0.05 | Glassy, near-static drone |
| 0.25 | 700 ms | 24/s | 0.12 | Smooth pad |
| 0.5 | 400 ms | 40/s | 0.18 | **Default cloud** |
| 0.75 | 140 ms | 80/s | 0.35 | Shimmering, granular-visible |
| 1.0 | 45 ms | 200/s | 0.6 | Dense sand, pitch emerging from rate |

Note the overlap stays in the 8–20 range across the whole sweep (§2.5) — that constraint is what keeps it always-usable.

**DRIFT** — chaos amount. Scales `chaosRate` plus every Lorenz route depth simultaneously. At 0, the texture is static and loopable; at 1, it never repeats. Exponential curve, because the interesting territory is all in the bottom 20%.

**PITCH** — combined `pitchSpray` + `pitchSet`. Detented: 0 = unison, 0.25 = subtle chorus, 0.5 = octaves, 0.75 = octave + fifth, 1.0 = wide chromatic scatter.

**SPACE** — width, Haas spread, and the output-stage width control together.

### 5.5 Visual design

- Near-black background (#0A0A0C). This is a phone in a dark studio at 1 a.m.
- Grains rendered with additive blending, warm amber → cool cyan across the pan field.
- Waveform in muted grey; the spray window as a soft luminous band.
- Motion is the primary UI feedback. Almost no text.
- OLED-friendly and, practically, battery-friendly.

---

## 6. Capture, Render & Handoff

### 6.1 Retro-capture — "that was it"

You cannot hit record fast enough for a happy accident. So the engine is always recording.

- A **60-second circular buffer** of engine output, `int16` interleaved stereo: 48000 × 60 × 2 × 2 = **11.5 MB**. Written by the audio thread with a single atomic write index; no locks.
- Three-finger tap (or the header button) freezes the ring and opens the capture review sheet: waveform of the last 60 s, draggable in/out handles, instant preview.
- From there: **Keep** (offline re-render at high quality, §6.3) or **Discard**.

Because the engine is deterministic, the capture stores not just audio but the *performance*, which means the kept version can be rendered better than the version you heard.

### 6.2 The gesture automation lane

Always running, negligible cost. Records, at 100 Hz:

```
GestureFrame { tSamples: u64, macro1..4: f32, touchX/Y/P: f32, flags: u16 }
```

20 bytes × 100 Hz × 60 s = 120 KB per minute. Alongside it, the transport records `grainIndexAtStart` and the master seed.

Together — **Seed + gesture lane + grain index origin = the complete performance**, in about 150 KB. The offline renderer replays the lane against the deterministic engine and reproduces the take exactly, at whatever quality you ask for.

This is the payoff of §3.2. It is why determinism was worth the discipline.

### 6.3 Offline renderer

A second engine instance, same C++ code, driven by a virtual clock on a worker thread. Differences from realtime:

| | Realtime | Offline |
|---|---|---|
| Max grains | 256 (adaptive) | 1024 |
| Interpolation | Cubic Hermite | Cubic Hermite @ 2× oversample |
| Anti-alias | One-pole per grain | Proper decimation filter |
| Mod control rate | 1 kHz | 4 kHz |
| Speed | 1× | 10–40× realtime |

Rendering 60 seconds should take 2–6 seconds on a modern device. Show a progress indicator, allow cancel, never block the audio engine.

### 6.4 Seamless loop rendering

For loops destined for Logic, the tail must meet the head. Render `bars` at the project tempo plus a `tailLength` overhang, then **wrap the overhang back over the start with an equal-power crossfade**. Because grains are long (up to 2 s), a naive cut leaves the cloud visibly and audibly amputated at the loop point; wrapping preserves it.

Additionally: at loop-render time, `position` modulation is optionally forced to be **periodic over the loop length** (mod sources phase-locked to bar count), so the texture's motion itself loops rather than merely its edges matching.

### 6.5 Export

**Format:** WAV, 32-bit float, engine sample rate. 32-bit float rather than 24-bit because the output stage's soft saturation sits at −3 dBFS and float leaves headroom decisions to Logic.

**Filename:** `GR_BitterAmberDrift_124bpm_8bars_a91f3c.wav`

**Embedded Seed (do this, it costs nothing):** write a custom `grvr` RIFF chunk containing the Seed as JSON, plus the source hash. A `LIST/INFO/ICMT` chunk carries a human-readable summary for DAWs that show comments.

Dropping an exported WAV back into Grooverider reads the chunk and **restores the exact patch**. Six months later, hearing a texture in an old project, you can recover how you made it. Logic and every other DAW ignore unknown chunks harmlessly.

**Delivery:** Android share sheet → whatever you already use to reach the Mac mini. A "Send to Mac" over local network is a v2 nicety, not an M6 requirement.

---

## 7. Persistence

| Store | Contents |
|---|---|
| **Room** | `seeds`, `sources` (hash, name, duration, importedAt), `renders` (path, seedId, createdAt) |
| **Files** | `files/sources/<sha256>.pcm` — content-addressed raw float32; `files/renders/*.wav` |
| **DataStore** | Device settings: buffer size preference, engine sample rate, theme, haptics, adaptive-voice cap |

Sources are never deleted implicitly. A source referenced by any Seed is undeletable without an explicit confirm that names the affected Seeds.

---

## 8. Android-Specific Engineering

### 8.1 Oboe configuration

```cpp
oboe::AudioStreamBuilder b;
b.setDirection(oboe::Direction::Output)
 ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
 ->setSharingMode(oboe::SharingMode::Exclusive)
 ->setFormat(oboe::AudioFormat::Float)
 ->setChannelCount(2)
 ->setSampleRate(oboe::kUnspecified)          // take the device native rate
 ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None)
 ->setDataCallback(callback)
 ->setErrorCallback(errorCallback);
```

Then set `bufferSizeInFrames = burstSize × N`, starting at **N = 4**. For a pad instrument, 20–40 ms of latency is imperceptible; an underrun is a click that ruins a take. Implement adaptive buffer growth: if `xRunCount` increases, bump N up to a ceiling of 8 and log it.

`setSharingMode(Exclusive)` may silently fall back to Shared — always read back the actual granted configuration rather than assuming.

### 8.2 Lifecycle

- **Foreground service** with `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` so the engine survives screen-off and app-switching.
- `AudioAttributes` usage `MEDIA`, content type `MUSIC`.
- Handle `onErrorAfterClose` (device route changes — headphones unplugged, Bluetooth connect) by rebuilding the stream on a non-audio thread. **Bluetooth output will add 150–250 ms of latency**; detect A2DP routing and show a subtle "wired recommended" hint rather than letting the user conclude the app is broken.
- Request audio focus properly; duck or pause on transient loss.

### 8.3 Real-time safety checklist

Enforce in code review, every time:

- [ ] No `new`, `malloc`, `std::vector::push_back`, or `std::string` on the audio thread
- [ ] No mutex, no `std::lock_guard`
- [ ] No `__android_log_print`
- [ ] No JNI calls upward from the callback
- [ ] No file or network IO
- [ ] Denormal flush-to-zero set on thread entry
- [ ] All lock-free structures verified SPSC — a second producer breaks them silently
- [ ] No unbounded loops; grain spawn count per block is capped

### 8.4 Performance targets

| Device class | Target | Notes |
|---|---|---|
| Flagship (Pixel 8+, S23+) | 256 voices, < 15% CPU | |
| Mid-tier (2022 mid-range) | 128 voices, < 35% CPU | Adaptive cap |
| Floor | 64 voices | Below this, ship a warning, not a bad experience |

Profile with Android Studio's profiler and Oboe's built-in `LatencyTuner`. Add NEON intrinsics to the grain mix loop **only if measurement demands it** — 256 grains of cubic interpolation is roughly 12 MFLOPs/s, which is nothing for a modern ARM core.

### 8.5 Permissions

`RECORD_AUDIO` (mic source capture), `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS` (13+, for the service notification). File access via the photo/document picker only — no broad storage permission.

---

## 9. Milestone Build Plan

Nine milestones, each independently demoable and each with a hard acceptance test. The ordering is deliberate: **the engine must be provably deterministic (M3) before the UI exists (M5)**, because retrofitting determinism onto a running system is a rewrite.

---

### M0 — Skeleton & Signal Path
*Prove audio comes out and the bridge works.*

**Build**

- Compose app shell, three empty destinations, dark theme
- CMake + Oboe integration, JNI bridge, `Engine` singleton with start/stop
- Foreground service with media-playback type
- `ParamRingBuffer` (lock-free SPSC) and `MeterSnapshot` (triple-buffered)
- Test tone generator behind a param
- xRun counter and measured latency surfaced in a debug panel

**Acceptance**

- [ ] 440 Hz sine plays cleanly for **10 minutes with zero xRuns** on the target device
- [ ] A Compose slider changes the tone's frequency with no zipper noise
- [ ] Screen-off and app-switch do not stop audio
- [ ] Headphone unplug/replug rebuilds the stream without a crash
- [ ] Measured round-trip latency logged; actual granted stream config logged

---

### M1 — Source Pipeline
*Get real audio into memory, correctly.*

**Build**

- Import via document picker; decode with Media3/`MediaCodec` → float PCM
- Mic capture via an Oboe input stream, 60 s max, with level monitoring
- Windowed-sinc resampler to engine rate (import-time only)
- SHA-256 content hashing; content-addressed source store
- Room `sources` table; waveform peak-envelope generation (256 and 2048 point)
- Source screen: list, waveform, scrub preview, in/out trim

**Acceptance**

- [ ] Import a 30 s MP3, a 44.1 kHz WAV, and a 48 kHz WAV — all play back at correct pitch and duration
- [ ] Record 10 s from the mic and play it back with no dropouts
- [ ] Re-importing the same file produces the same hash and creates no duplicate
- [ ] A 60 s stereo source occupies ~23 MB and does not trigger a GC pause during playback

---

### M2 — Grain Engine v1
*The core. No randomness yet beyond spray — just a clean cloud.*

**Build**

- `Grain` struct, fixed `VoicePool`, swap-remove lifecycle
- Sample-accurate `GrainScheduler`
- Window tables: Gaussian, Tukey, Hann (4096 pt)
- Cubic Hermite interpolation + per-grain anti-alias lowpass
- √overlap gain compensation and density soft-limiting
- Equal-power panning + Haas channel offset
- Output stage: DC blocker, width, soft saturation, gain
- One-pole param smoothing with per-class time constants
- Debug slider panel for every parameter

**Acceptance**

- [ ] Default pad settings (40/s, 400 ms, Gaussian) produce a **click-free** cloud from a vocal source
- [ ] Sweeping density 5 → 200 grains/s changes **density, not loudness** — mean RMS over ≥8 master seeds trends less than **1.5 dB** end to end (per-seed variance of ±1 dB is expected and correct — measure the mean, not one render)
- [ ] The same sweep with `pitchSpray = 0` on a sustained tonal source shows a *larger* trend — confirms grain decorrelation is working as described in §2.5
- [ ] Sweeping grainSize 5 → 2000 ms produces no discontinuity or dropout
- [ ] `spread` at 1.0 on a mono source produces audible width that **survives mono summing** without comb filtering
- [ ] Output DC offset < −80 dBFS after 60 s at `reverseProb = 1.0`
- [ ] Every window table reads **exactly 0.0** at index 0 and N−1 (unit test, all window types, all σ values)
- [ ] Switching window type at fixed density/size changes character, **not level**, within ±0.5 dB (verifies `windowRms` compensation)
- [ ] 256 concurrent voices sustained with no xRuns

---

### M3 — Determinism & the Seed ⭐
*The milestone the product depends on.*

**Build**

- `SplitMix64` hashing, `SeedRng::value(masterSeed, streamId, grainIndex)`
- Replace every stochastic decision with hashed derivation; audit for `rand()`
- Monotonic `grainIndex`, reset only on transport restart
- `ParamState` pack/unpack codec
- `Seed` entity, Room DAO, save/load/delete
- Deterministic auto-naming from wordlists
- Build flags: `-ffp-contract=off` on the engine target
- A test harness that renders a Seed to a buffer twice and diffs

**Acceptance**

- [ ] The same Seed + source rendered twice is **bit-identical** (`memcmp` == 0)
- [ ] Same Seed rendered at block sizes 96, 192, 384 and 960 → bit-identical
- [ ] Same Seed on two different devices → identical within −120 dBFS
- [ ] Changing `pitchSpray` leaves the pan distribution unchanged (stream independence verified by logging pan values for grains 0–99 before and after)
- [ ] Saving, force-quitting, relaunching and loading a Seed reproduces the texture exactly
- [ ] Grepping the engine for `rand(`, `random_device`, `chrono::now` in the signal path returns nothing

---

### M4 — Modulation & Chaos
*Make it move.*

**Build**

- `DriftGen` ×3 (seeded band-limited random walk)
- `Lorenz` with RK4 at 1 kHz control rate, seeded init, NaN/excursion guard
- `Lfo` ×2 with tempo sync
- 16-slot `ModMatrix` with curve types
- Macro system with curated mapping curves
- The "First Light" default patch

**Acceptance**

- [ ] Lorenz runs for **60 minutes** with no NaN, no divergence, no audible discontinuity
- [ ] `chaosRate` at minimum produces motion perceptible over minutes, not seconds
- [ ] Routing one Lorenz to five destinations produces motion that reads as **coupled**, not random (subjective, but the A/B against five independent Drifts must be obvious)
- [ ] Mod matrix depth at 0 is bit-identical to no route at all
- [ ] "First Light" on an arbitrary dropped-in vocal sounds good within 2 seconds, unassisted
- [ ] Full mod load adds < 3% CPU

---

### M5 — The Cloud UI
*Make it an instrument.*

**Build**

- Landscape performance screen, the four-zone layout
- `GrainCloudSnapshot` pipeline and the 60 fps particle Canvas
- XY pad with multitouch gesture recogniser (drag, pinch, rotate, long-press, multi-tap)
- Four macro controls with padlock affordance
- Waveform strip with position marker and spray-window band
- Haptics on freeze, re-roll and capture
- Header: seed name, meter, voice count, capture button

**Acceptance**

- [ ] Particle rendering holds 60 fps with 256 grains on a mid-tier device
- [ ] Every gesture in §5.3 is reachable and reliable **one-handed**
- [ ] A full 5-minute texture can be sculpted **without opening a menu**
- [ ] Long-press freeze latches within 400 ms with haptic confirmation
- [ ] Double-tap re-roll is navigable back via the lineage stack
- [ ] The cloud visualisation accurately reflects density, spray, pitch scatter and pan — verified by setting extremes and confirming the picture matches

---

### M6 — Capture, Render & Export
*Get it into Logic.*

**Build**

- 60 s `int16` capture ring, lock-free
- Gesture automation lane at 100 Hz, with grain-index origin
- Capture review sheet: waveform, trim handles, preview, keep/discard
- `OfflineRenderer` — second engine instance, virtual clock, HQ settings, 2× oversample
- Seamless loop wrap with equal-power crossfade; optional bar-periodic modulation
- `WavWriter` — 32-bit float, `grvr` JSON chunk, `LIST/INFO` comment
- Filename convention, renders library, Android share sheet

**Acceptance**

- [ ] Capture after a 60 s improvisation returns audio **matching what was heard**
- [ ] Offline re-render of a captured performance matches the ring-buffer audio within **−80 dBFS** (differences only from HQ settings)
- [ ] 60 s renders in under 8 seconds on the target device
- [ ] An 8-bar loop dropped into Logic **loops without a seam** — verified by ear and by looking at the waveform join
- [ ] Exported WAV opens correctly in Logic, QuickTime and Audacity (unknown chunk ignored)
- [ ] Re-importing an exported WAV into Grooverider **restores the exact patch**
- [ ] Share sheet delivers the file to the Mac mini

---

### M7 — Seed Library & Mutation
*Make chaos ownable.*

**Build**

- Library grid: waveform thumbnails, names, favourites, search, tags
- Lineage tree view, expandable, navigable to any ancestor
- **Mutate** — six children, sensitivity-weighted, laid out for audition
- Param locks (64-bit mask) wired to every control
- **Apply to…** — re-point a Seed to a different source
- Breeding (two parents → blended child)
- Seed export/import as `.grvr` JSON for backup

**Acceptance**

- [ ] Mutate at 0.2 produces six recognisable variations of the parent — none broken, none identical
- [ ] Mutate at 0.9 produces six clearly different textures — still none broken (no silence, no clipping, no runaway)
- [ ] Locking `grainSize` and `density`, then mutating at 1.0, leaves those values untouched
- [ ] Applying a Seed to three different sources yields three textures that are **audibly siblings**
- [ ] The lineage tree walks back to a 5-generations-ago ancestor and branches correctly
- [ ] 500 seeds in the library scroll at 60 fps

---

### M8 — Polish, Performance & First Release
*Make it survive contact with real use.*

**Build**

- Adaptive voice cap with device profiling on first run
- NEON grain mix loop **if profiling demands it**
- Onboarding: three screens, then straight into a demo source with First Light loaded
- Ships with 8–12 curated factory Seeds and 3 factory sources
- Crash reporting, xRun telemetry (local only)
- Empty states, error states, permission rationale copy

**Acceptance**

- [ ] Clean install → audible, good-sounding texture in **under 30 seconds**, no reading required
- [ ] 2-hour session with no crash, no memory growth, no thermal throttle audible as dropouts
- [ ] Runs acceptably on the floor-spec device
- [ ] Battery drain measured and documented
- [ ] **The real test:** a texture made in Grooverider survives into a finished Delrogue track

---

## 10. Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Android audio glitching on mid-tier devices | High | High | Generous buffers from day one; adaptive voice cap; never chase minimum latency |
| Determinism breaks silently under refactor | Medium | **Critical** | The M3 bit-identical test runs in CI on every commit. Non-negotiable. |
| Granular output sounds generic | Medium | High | The macro curation (§5.4) and sensitivity table (§3.6) are the antidote — budget real listening time for them, they are not config |
| Feature creep toward a mini-DAW | High | High | §0.4 is a contract. Re-read it at each milestone. |
| Mutation produces mostly unusable children | Medium | Medium | Sensitivity weights + locks; tune against real sessions, not synthetic tests |
| Bluetooth latency read as a bug | High | Low | Detect A2DP, show the hint |
| 60 s source cap feels limiting | Low | Medium | It is a deliberate constraint. Revisit only after real use, never speculatively. |

---

## 11. Doors Left Open (v2+)

Deliberately excluded from v1, but the architecture accommodates them:

- **Found-sound front door** (the good half of Concept 1) — mic capture straight into the grain engine. The granular path uses the *entire* recording: transient, noise floor, decay. Nothing is thrown away, which is precisely where single-cycle wavetable extraction failed. Cheap to add — M1 already builds the mic path.
- **Riser / transition mode** — tempo-synced global envelope driving position, density and pitch trajectories over N bars. Mostly a preset-and-envelope layer over the existing engine.
- **Glitch mode** — Expodec/Rexpodec windows, buffer-stutter scheduling, rhythmic grain quantisation. The window tables already exist.
- **Sub-texture mode** — a lowpassed parallel grain stream with pitch quantised to octaves below, for grain-derived low-end movement. Genuinely uncommon; a strong signature candidate.
- **Rhythm capture** (Concept 2) — only worth it if it drives *this* engine's grain scheduling rather than exporting MIDI. Tapped rhythm as a grain onset pattern is interesting; tapped rhythm as a MIDI file is a notepad.
- **Spectral freeze** — FFT-based infinite sustain alongside the grain cloud.
- **Send to Mac** — local network drop straight into a watch folder.

---

## 12. Build Order Summary

```
M0 Skeleton ──► M1 Sources ──► M2 Grain Engine ──► M3 DETERMINISM ⭐
                                                        │
                          ┌─────────────────────────────┤
                          ▼                             ▼
                     M4 Modulation                 (CI: bit-identical test)
                          │
                          ▼
                     M5 Cloud UI ──► M6 Capture & Render ──► M7 Seed Library
                                                                   │
                                                                   ▼
                                                            M8 Polish & Ship
```

**The critical path is M0 → M1 → M2 → M3.** Everything after M3 is additive; everything before it is foundational. If the project stalls, it stalls because determinism was treated as a nice-to-have.

---

*Grooverider — an instrument for making accidents you can keep.*

---

## Appendix A — What Was Verified Numerically

The DSP claims in this spec were simulated before it was written, not asserted. Three findings changed the design:

**1. √overlap gain compensation is exact, and pitch spray is what makes it exact.**
Across a 40× density sweep (5 → 200 grains/s), the systematic level trend was 0.05 dB on noise-like sources and 0.64 dB on tonal sources *with* the 0.15 st default pitch spray — but 1.61 dB with pitch spray at zero. Grain decorrelation is doing real work. See §2.5.

**2. The apparent level drift in a single render is realisation variance, not error.**
Per-seed standard deviation was 0.5–0.9 dB and averaged out over eight seeds. An RMS auto-leveller was simulated as a fix and made things *worse* (3–4 dB spread) while flattening the instrument's dynamics. It was cut from the design. Measure the mean over multiple seeds, not one render.

**3. A truncated Gaussian window clicks — badly.**
At σ = 0.25 the window sits at 0.135 (−17 dBFS) at the grain boundary. At 40 grains/second that is a 40 Hz buzz on every texture, and it would have been very hard to diagnose after the fact. Pedestal removal fixes it exactly, and window RMS turned out to feed back into the gain compensation as a further 2.7 dB term. See §2.4.

Reproduce with the simulation scripts alongside this document.
