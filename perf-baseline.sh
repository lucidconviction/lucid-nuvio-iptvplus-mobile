#!/usr/bin/env bash
# Captures a repeatable Android performance baseline for Lucid Nuvio IPTV+ Mobile.
#
# Usage:
#   scripts/perf-baseline.sh [package] [output-dir]
#
# Default package is the full debug build. Run it three times on the same device before and after
# a change; compare cold-start totals and gfxinfo percentiles, not a single run.
set -euo pipefail

PKG="${1:-com.lucid.nuvioiptvplus.mobile.debug}"
OUT="${2:-build/perf/$(date +%Y%m%d-%H%M%S)}"
ACTIVITY="${PKG}/com.nuvio.app.launcher.AppIconDefault"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not on PATH" >&2
  exit 1
fi

mkdir -p "$OUT"
adb shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1 || true
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
sleep 1

adb shell am start -W -n "$ACTIVITY" | tee "$OUT/cold-start.txt"
sleep 6
adb shell dumpsys gfxinfo "$PKG" > "$OUT/gfxinfo.txt" 2>&1 || true
adb shell dumpsys meminfo "$PKG" > "$OUT/meminfo.txt" 2>&1 || true
adb logcat -d -v brief | grep -E "Player|Choreographer|Skipped|ANR|FATAL" > "$OUT/logcat.txt" 2>&1 || true

echo
echo "Saved baseline to $OUT"
echo "  cold-start.txt  TotalTime / WaitTime"
echo "  gfxinfo.txt     Janky frames / percentiles"
echo "  meminfo.txt     PSS and heap"
