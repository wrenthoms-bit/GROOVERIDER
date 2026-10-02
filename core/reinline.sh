#!/usr/bin/env bash
# Rebuild the wasm and re-embed it into docs/index.html (replaces the base64 blob
# inside <script id="wasmb64">…</script>).
set -euo pipefail
cd "$(dirname "$0")"
./build-wasm.sh
python3 - << 'PY'
import base64, re
b64 = base64.b64encode(open("grooverider.wasm", "rb").read()).decode("ascii")   # the same on macOS and Linux
p = "../docs/index.html"; s = open(p).read()
s, n = re.subn(r'(<script id="wasmb64"[^>]*>).*?(</script>)', lambda m: m.group(1) + b64 + m.group(2), s, flags=re.S)
if n != 1: raise SystemExit("could not find the wasmb64 script tag in docs/index.html")
open(p, "w").write(s); print("re-inlined wasm into docs/index.html (%d base64 characters)" % len(b64))
PY
