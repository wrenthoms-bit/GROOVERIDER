#!/usr/bin/env bash
# Compile the portable GrainCore to a self-contained WebAssembly module using
# clang directly (no Emscripten needed). Output: grooverider.wasm (~70 KB, zero imports).
# The web app (docs/index.html) has this wasm inlined as base64; rebuild + re-inline
# with reinline.sh after changing the core.
set -euo pipefail
cd "$(dirname "$0")"
clang++ --target=wasm32 -O3 -ffreestanding -fno-exceptions -fno-rtti -nostdlib \
  -Wl,--no-entry -Wl,--allow-undefined -Wl,--export-dynamic \
  -Wl,--initial-memory=33554432 -Wl,--max-memory=67108864 -Wl,-z,stack-size=131072 \
  -o grooverider.wasm core_abi.cpp
echo "built grooverider.wasm ($(stat -c%s grooverider.wasm 2>/dev/null || stat -f%z grooverider.wasm) bytes)"
