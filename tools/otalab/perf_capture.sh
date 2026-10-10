#!/usr/bin/env bash
# Measurement protocol for the delivery layer (docs/ota-full/PERF_PROTOCOL.md). Same script for the emulator (plumbing only: software GL, numbers are NOT representative) and for a real device
# (Pixel 10 Pro XL): cold-start time, frame-interval distribution of the game's surface, and memory, for the unmodified packaged game (cold start only), the same classes packaged (REF) and
# delivered as a module (HOST). usage: perf_capture.sh OUT_DIR [RUNS] [PLAY_SECONDS]   (the three apps must be installed)
set -u
OUT=${1:?}; RUNS=${2:-5}; PLAY=${3:-60}; mkdir -p "$OUT"; HERE=$(dirname "$0")
BASE=com.hotatticgames.climbup; REF=com.hotatticgames.climbup.otaref; HOST=com.hotatticgames.climbup.otaexp
ACT_BASE=$BASE/com.hotatticgames.climbup.android.AndroidLauncher; ACT_REF=$REF/com.hotatticgames.climbup.otaref.PackagedLauncher; ACT_HOST=$HOST/com.hotatticgames.climbup.otalab.LabLauncher
echo "device: $(adb shell getprop ro.product.model | tr -d '\r') Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r')) $(adb shell wm size | tail -1 | tr -d '\r')"
cold() { local name=$1 act=$2 pkg=${act%%/*}; local ts=""; for i in $(seq "$RUNS"); do adb shell am force-stop "$pkg"; sleep 1; ts="$ts $(adb shell am start -S -W -n "$act" 2>&1 | grep TotalTime | awk '{print $2}' | tr -d '\r')"; sleep 3; done
  echo "cold start $name (ms, $RUNS runs):$ts   median=$(echo $ts | tr ' ' '\n' | sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}')"; }
cold "packaged (unmodified game)" "$ACT_BASE"; cold "packaged (same classes, no loader)" "$ACT_REF"; cold "module through the host" "$ACT_HOST"
play() { local name=$1 act=$2 pkg=${act%%/*}; adb shell am start -S -W -n "$act" --es prop.climb.demo true >/dev/null; sleep 20            # past the splash, into the scripted climb
  local layer; layer=$(adb shell dumpsys SurfaceFlinger --list | tr -d '\r' | grep "$pkg" | grep -i surfaceview | head -1)
  [ -z "$layer" ] && layer=$(adb shell dumpsys SurfaceFlinger --list | tr -d '\r' | grep "$pkg" | head -1)
  adb shell dumpsys SurfaceFlinger --latency-clear "$layer" >/dev/null 2>&1; sleep "$PLAY"
  adb shell dumpsys SurfaceFlinger --latency "$layer" > "$OUT/latency_$name.txt" 2>/dev/null
  echo "frames $name: $(python3 "$HERE/frames.py" "$OUT/latency_$name.txt")"
  adb shell dumpsys meminfo "$pkg" | grep -E "TOTAL PSS|TOTAL:|Java Heap:|Native Heap:|Graphics:" | head -5 | sed "s/^/   mem $name: /" | tr -s ' '; }
play "packaged-ref" "$ACT_REF"; play "module-host" "$ACT_HOST"
