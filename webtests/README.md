# webtests — checks for the web app

Checks for `docs/index.html`. They live outside `docs/` so they are not published with the site.

```bash
cd webtests
node engine.test.mjs      # engine only: needs Node, nothing else
npm install               # once, for the browser checks
node browser.test.mjs     # needs Google Chrome installed
npm test                  # both
```

Exit code 0 means every check passed.

## engine.test.mjs

Runs the page's `ObservatoryEngine` class in Node against the real `core/grooverider.wasm`. The class is read straight out of `docs/index.html` (between the `ENGINE-BEGIN` and `ENGINE-END` markers), so it tests exactly what ships.

| Area | Check |
|---|---|
| Stability | "Standing Room Only" for 60 s: finite, peak below 1, level steady, dense voices |
| Worst case | space, shimmer and chaos all at maximum for 90 s stays bounded |
| Space | reverb tail rings and decays after grains stop; space at 0 is fully dry |
| Shimmer | energy appears an octave up when shimmer is on |
| Scale-lock | every grain lands on the selected scale; free mode is continuous |
| Chaos and drone | chaos sways the playhead; scan moves it; drone latches it; the pad repositions it |
| MIDI keyboard | grains land on the held notes; with scale-lock they stay in key; clearing returns to key + register |
| Determinism | same seed and settings give a bit-identical render |

This class is the reference the Android port is matched against (see `ANDROID_OBSERVATORY_BRIEF.md`). If a change to the engine alters these numbers, it has changed the sound.

## browser.test.mjs

Drives the real page in headless Chrome. It serves `docs/` on a local port itself.

It covers: auto-play of the default preset, ring and pad controls, MIDI-learn, all presets, WAV export with its tail, import, mic recording, MIDI keyboard playing (latch, gate, sustain), the sample library menu, the help panel and start-screen tips, and the Canvas2D fallback.

What it does **not** prove: MIDI devices, the microphone and the iPhone are simulated. It checks the page's own logic, not real hardware, real Safari, or how anything sounds.

Set `CHROME_PATH` if Chrome is not at the usual macOS location.
