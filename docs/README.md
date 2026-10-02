# Grooverider — web build ("Observatory")

A browser build of the Grooverider granular instrument, for evolving pads and drones. The grains come from the **same C++ DSP core** as the Android app, compiled to WebAssembly (see `../core/`). Everything runs locally in the page: no server, no upload.

Live at **https://grooverider.netlify.app** and `https://wrenthoms-bit.github.io/GROOVERIDER/`.

## Run it
- **Netlify:** `../netlify.toml` publishes this folder; a push to `main` redeploys.
- **GitHub Pages:** repo **Settings → Pages → Build and deployment → Deploy from a branch → `main` / `/docs`**.
- **Locally:** `cd docs && python3 -m http.server` then open `http://localhost:8000`. (Opening `index.html` directly also plays the demo pad, but library samples and MIDI need a server.)

## Use it
1. Click **Enter the Observatory**. Browsers won't make sound until you click something.
2. Choose a sound: **Samples…**, **Import audio** (drag-drop works too, up to 60 s), or **Record mic** (up to 10 s).
3. Shape it:
   - **Performance field:** drag left and right through the sound, up and down for brighter or darker.
   - **TEXTURE** long smooth grains to short dense ones. **DRIFT** how far and fast the sound wanders on its own. **SPACE** reverb, room to ocean. **PITCH** how far grains scatter in pitch.
   - **DRONE** holds one spot and lets it evolve. **KEY** and **SCALE** keep it in tune (key assumes the source is in C).
   - **Detail** opens the fine controls: shimmer, tone, register, scan and more.
4. The menu under the title switches **presets**; "Standing Room Only" is the default. **Re-roll** gives a new variation with the same settings.
5. **Render WAV** exports 8 seconds plus the reverb tail as a 32-bit float file.

**? Help** in the top bar has all of this in the page, plus what to check if there is no sound (on iPhone and iPad, the silent switch mutes browser audio).

## How it's built
`index.html` is self-contained for the engine: the wasm is embedded as base64. To rebuild after changing the core, run `../core/reinline.sh`.

Around the core sits a web layer, the `ObservatoryEngine` class in `index.html`: Lorenz chaos modulation, the playhead servo and drone latch, scale-lock, and the space (reverb, shimmer, tone). The same class runs live in an AudioWorklet and offline for WAV export, so the export matches what you hear. It is also the reference the Android port is matched against (`../ANDROID_OBSERVATORY_BRIEF.md`), so a change to how it sounds needs passing on.

Visuals are WebGL (caustic water, motes, trails, bloom) with a Canvas2D fallback.

## Sample library
The **Samples…** menu lists built-in sources and shows which one is loaded. The demo pad is synthesised in the page; the rest are audio files in `samples/`, fetched only when chosen (so they need the page served over http(s), not opened as a file).

To add one: put a compressed copy in `samples/` and add a line to the `SAMPLES` array in `index.html`. Sources are capped at 60 s. On macOS, `afconvert -f m4af -d aac -b 256000 in.wav samples/name.m4a` makes a suitable file.

## MIDI (Akai MPK Mini etc.)
Chrome or Edge on a computer only (Web MIDI), and the page must be on **https or localhost**. Everything else works without MIDI.

**Keyboard**
1. Plug in the controller, click **MIDI**, allow access.
2. Play. Notes become the cloud's pitch centres (middle C is the source's own pitch), and a chord spreads the grains across its notes. Scale-lock snaps them into the key; choose Free to play chromatically.
3. In **Detail → MIDI keyboard**, **Latch** keeps a chord after you let go and **Gate** only sounds while keys are held. An unbound sustain pedal (CC64) holds the chord.

**Knobs and pads**
1. Click **Learn**, click a control's **MIDI** badge, then move a knob. It binds instantly.
2. For **Play**, **Re-roll** or **Drone**, click the button, then hit a pad.
3. Turn Learn off. Bindings are saved in the browser.

Knobs send absolute CC, so they map 1:1 to a parameter's range. Re-binding a control replaces its old CC. A pad bound to a button no longer plays a pitch.

## Tests
See `../webtests/`: engine checks that run in Node, and browser checks that drive the page in headless Chrome.

Two URL switches exist for testing: `?2d` forces the Canvas2D renderer, and `?autostart` starts without the first click where the browser allows it. `window.__grv` exposes live stats.
