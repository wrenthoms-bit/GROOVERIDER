# nativetest — audio core checks

Runs the C++ audio core on your laptop, with no Android device, emulator, or
Gradle involved. `shim/oboe/Oboe.h` stands in for the real Oboe API, so what
gets exercised is *our* code.

```bash
cd nativetest
./run.sh
```

Exit code 0 means every check passed.

## What it checks

| Area | Check |
|---|---|
| `ParamRing` | 200k messages across two real threads: FIFO order, no loss, refuses overflow rather than corrupting |
| `TripleBuffer` | ~2M concurrent reads with zero torn snapshots |
| `Smoother` | reaches 63% after one time constant, settles, survives a zero time constant |
| Engine level | output peak matches the gain setting |
| Engine pitch | rendered frequency matches the requested frequency |
| **Engine smoothing** | a hard frequency sweep produces no discontinuity — this *is* the M0 "no zipper noise" acceptance test, measured rather than listened for |
| Engine gain steps | an abrupt 0.0 → 0.9 gain change stays below a click threshold |
| Engine DC | no DC offset accumulating in the output |

## Why this exists now

M3 requires a bit-identical render test running in CI on every commit — spec
§9, and the one non-negotiable check in the project. That harness has to live
somewhere, and standing it up at M0 means the audio core is never untested.

Add new cases to `test_engine.cpp`; the `check()` helper takes a bool, a name,
and an optional detail string that prints beside the result.

## The shim is not Oboe

`shim/` implements only the slice of the Oboe API the engine touches, with a
fake stream fixed at 48 kHz / 192-frame bursts. It verifies our logic, not
Oboe's, and not the device. Stream opening, latency, xRuns, and route-change
recovery can only be tested on real hardware.
