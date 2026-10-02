#!/usr/bin/env bash
# Compile the portable core (GrainCore + Observatory) to a self-contained WebAssembly
# module using clang directly (no Emscripten needed). Output: grooverider.wasm, zero imports.
# The web app (docs/index.html) has this wasm inlined as base64; rebuild + re-inline
# with reinline.sh after changing the core.
set -euo pipefail
cd "$(dirname "$0")"
# Needs a clang that can link WebAssembly (it brings wasm-ld). Apple's does not;
# the Android NDK's does, so fall back to the newest NDK installed.
if ! command -v wasm-ld >/dev/null 2>&1; then
  NDK_BIN=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/ndk/*/toolchains/llvm/prebuilt/*/bin 2>/dev/null | sort | tail -1)
  if [ -n "${NDK_BIN:-}" ] && [ -x "$NDK_BIN/wasm-ld" ]; then PATH="$NDK_BIN:$PATH"
  else echo "no wasm-ld found: install LLVM (brew install llvm lld) or the Android NDK" >&2; exit 1; fi
fi
clang++ --target=wasm32 -O3 -ffreestanding -fno-exceptions -fno-rtti -nostdlib -ffp-contract=off \
  -Wl,--no-entry -Wl,--allow-undefined -Wl,--export-dynamic \
  -Wl,--initial-memory=33554432 -Wl,--max-memory=67108864 -Wl,-z,stack-size=131072 \
  -o grooverider.wasm core_abi.cpp
echo "built grooverider.wasm ($(stat -c%s grooverider.wasm 2>/dev/null || stat -f%z grooverider.wasm) bytes)"
