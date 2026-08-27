#!/usr/bin/env bash
# Runs the Grooverider audio-core checks on your laptop -- no Android device,
# no emulator, no Gradle. The Oboe API is replaced by a small local shim, so
# what gets exercised is our code: the lock-free structures, the smoothing,
# and the rendered signal itself.
#
#   ./run.sh
#
# Exit code 0 means every check passed. Wire this into CI before M3 -- the
# bit-identical Seed test lives here too, and it is the one check the whole
# product rests on.
set -euo pipefail
cd "$(dirname "$0")"

CXX="${CXX:-c++}"
CORE=../app/src/main/cpp

echo "building with $CXX ..."
"$CXX" -std=c++17 -O2 -Wall -Wextra -Wno-unused-parameter -ffp-contract=off \
    -Ishim -I"$CORE" \
    -o /tmp/grooverider_tests \
    test_engine.cpp "$CORE/engine/Engine.cpp" "$CORE/engine/GrainScheduler.cpp" \
    "$CORE/engine/ModEngine.cpp" "$CORE/engine/OfflineRenderer.cpp" \
    "$CORE/dsp/Resampler.cpp" log_stub.cpp \
    -lpthread

/tmp/grooverider_tests
