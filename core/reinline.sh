#!/usr/bin/env bash
# Rebuild the wasm and re-embed it into docs/index.html (replaces the base64 blob
# inside <script id="wasmb64">…</script>).
set -euo pipefail
cd "$(dirname "$0")"
./build-wasm.sh
B64=$(base64 -w0 grooverider.wasm 2>/dev/null || base64 grooverider.wasm | tr -d '\n')
python3 - "$B64" << 'PY'
import sys, re
b64=sys.argv[1]
p="../docs/index.html"; s=open(p).read()
s=re.sub(r'(<script id="wasmb64"[^>]*>).*?(</script>)', r'\1'+b64+r'\2', s, flags=re.S)
open(p,'w').write(s); print("re-inlined wasm into docs/index.html")
PY
