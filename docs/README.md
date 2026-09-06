# Grooverider — web build

A browser build of the Grooverider granular texture engine. It runs the **same C++ DSP core** as the Android app, compiled to WebAssembly (see `../core/`). Everything runs locally in the page — no server, no upload.

## Run it
- **GitHub Pages:** repo **Settings → Pages → Build and deployment → Deploy from a branch → `main` / `/docs`**. It publishes at `https://wrenthoms-bit.github.io/GROOVERIDER/`.
- **Locally:** `cd docs && python3 -m http.server` then open `http://localhost:8000`. (Opening `index.html` directly also works — the wasm is inlined — but a local server is more reliable.)

## Use it
1. Click once to enable audio (browser autoplay rule).
2. **Load demo tone**, **Import audio** (drag-drop works too), or **Record mic**.
3. **Play**, then move the macros (TEXTURE / DRIFT / PITCH / SPACE) and grain controls.
4. **Render 8s WAV** exports a 32-bit float file to your downloads.

`index.html` is fully self-contained (the wasm is embedded as base64). To rebuild after changing the core, run `../core/reinline.sh`.
