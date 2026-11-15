# Performance baseline

This is the repeatable measurement used for the stability/performance work. It is intentionally
small: a cold-start timing, frame stats and memory for the debug build on the same device.

## Before a change

```bash
./perf-baseline.sh
./perf-baseline.sh
./perf-baseline.sh
```

Keep all three runs. Use the median, not the best run.

## What to compare

- **Cold start:** `TotalTime` and `WaitTime` from `cold-start.txt`.
- **Frames:** `Janky frames` and the 50th/90th/95th/99th percentiles from `gfxinfo.txt`.
- **Memory:** `TOTAL PSS` from `meminfo.txt`.
- **Logs:** skipped frames, ANRs, player errors and Choreographer warnings in `logcat.txt`.

## Player overlay check

1. Open a 4K/HDR stream and let it play for 30 seconds with controls hidden.
2. Reset gfxinfo, show the controls for 15 seconds, then dump gfxinfo.
3. Hide the controls for 15 seconds, then dump gfxinfo again.
4. Compare the visible-controls run with the hidden-controls run.

The visible-controls p95 should stay within one frame budget of the hidden-controls p95. If it
does not, measure again with `adb shell dumpsys SurfaceFlinger --latency` or Perfetto before
changing composition structure.

## CI

`.github/workflows/stability.yml` compiles the app, runs the Android host tests and builds the
debug APK on every push/PR to `main`. A change is not done until that workflow is green and the
same change has a before/after baseline from this script on a real device.
