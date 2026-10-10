#!/usr/bin/env bash
# Measurement protocol for the delivery layer (docs/ota-full/PERF_PROTOCOL.md). One script for the emulator (plumbing only: software GL, numbers are NOT representative of a phone) and for a
# real device (Pixel 10 Pro XL). It measures, for the unmodified packaged game (BASE), the same classes packaged without a loader (REF) and the same classes delivered as a signed module
# through the host (HOST): cold-start time (system-measured), frame pacing and frame work time during an identical scripted climb (the game's own demo mode, measured by the same PerfListener
# in REF and HOST), and process memory. REF and HOST runs are interleaved so that thermal or background drift hits both. Verdict lines compare HOST with REF against fixed budgets.
# usage: perf_capture.sh OUT_DIR [COLD_RUNS=5] [MEASURE_SECONDS=60] [PLAY_RUNS=3]      env: PERF_STRICT=1 makes an exceeded budget a failure (use on a real device)
set -u
OUT=${1:?}; COLD=${2:-5}; MEAS=${3:-60}; PLAYS=${4:-3}; WARM=12; mkdir -p "$OUT"; export OUT; HERE=$(dirname "$0"); . "$HERE/lib.sh"
BASE=com.hotatticgames.climbup; REF=com.hotatticgames.climbup.otaref; HOST=com.hotatticgames.climbup.otaexp
ACT_BASE=$BASE/com.hotatticgames.climbup.android.AndroidLauncher; ACT_REF=$REF/com.hotatticgames.climbup.otaref.PackagedLauncher; ACT_HOST=$HOST/com.hotatticgames.climbup.otalab.LabLauncher
echo "device: $(adb shell getprop ro.product.model | tr -d '\r') Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r')) $(adb shell wm size | tail -1 | tr -d '\r') abi $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
median() { tr ' ' '\n' | grep -v '^$' | sort -n | awk '{a[NR]=$1} END{if(NR) print a[int((NR+1)/2)]; else print "NA"}'; }

declare -A COLDMS
cold() { local name=$1 act=$2; local pkg=${act%%/*}; local ts=""; for i in $(seq "$COLD"); do adb shell am force-stop "$pkg"; sleep 1; ts="$ts $(adb shell am start -S -W -n "$act" 2>&1 | grep TotalTime | awk '{print $2}' | tr -d '\r')"; sleep 3; done
  COLDMS[$name]=$(echo "$ts" | median); echo "cold start $name (ms, $COLD runs):$ts   median=${COLDMS[$name]}"; }
cold BASE "$ACT_BASE"; cold REF "$ACT_REF"; cold HOST "$ACT_HOST"

# play NAME ACT TAG RUN: scripted climb (demo mode) with the frame recorder on; waits for the PERF line
declare -A PERFLINE; declare -A PSS
play() { local name=$1 act=$2 tag=$3 run=$4; local pkg=${act%%/*}; adb shell am force-stop "$pkg"; sleep 1; adb logcat -c
  adb shell am start -S -W -n "$act" --es prop.climb.demo true --es prop.climb.perf "$MEAS" --es prop.climb.perfWarm "$WARM" >/dev/null
  wait_for "PERF $tag frames=" $((WARM + MEAS + 90)) || { echo "   $name run $run: no PERF line"; return; }
  local line; line=$(logs | grep "PERF $tag frames=" | tail -1 | sed 's/.*PERF //'); echo "   $name run $run: $line"; echo "$line" >> "$OUT/perf_$name.txt"
  local pss; pss=$(adb shell dumpsys meminfo "$pkg" | grep -m1 "TOTAL PSS" | sed 's/.*TOTAL PSS: *\([0-9]*\).*/\1/'); [ -z "$pss" ] && pss=$(adb shell dumpsys meminfo "$pkg" | grep -m1 -E "^ *TOTAL " | awk '{print $2}')
  echo "$pss" >> "$OUT/pss_$name.txt"; echo "   $name run $run: PSS ${pss:-NA} KB"; }
rm -f "$OUT"/perf_*.txt "$OUT"/pss_*.txt
for r in $(seq "$PLAYS"); do play REF "$ACT_REF" packaged-ref "$r"; play HOST "$ACT_HOST" module-host "$r"; done

python3 - "$OUT" "${COLDMS[BASE]}" "${COLDMS[REF]}" "${COLDMS[HOST]}" "${PERF_STRICT:-0}" <<'P'
import re, sys, statistics as st
out, cb, cr, ch, strict = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5] == "1"
def load(name):
    rows = []
    try:
        for l in open("%s/perf_%s.txt" % (out, name)):
            d = {}
            m = re.search(r"frames=(\d+) fps=([\d.]+) interval_ms\[p50=([\d.]+) p90=([\d.]+) p99=([\d.]+) max=([\d.]+)\] work_ms\[p50=([\d.]+) p99=([\d.]+) max=([\d.]+)\] slow25=(\d+) slow50=(\d+)", l)
            if m: rows.append(dict(zip("frames fps i50 i90 i99 imax w50 w99 wmax s25 s50".split(), map(float, m.groups()))))
    except IOError: pass
    return rows
def pss(name):
    try: return [float(x) for x in open("%s/pss_%s.txt" % (out, name)).read().split() if x.replace('.', '').isdigit()]
    except IOError: return []
R, H = load("REF"), load("HOST")
bad = 0
def row(k): return (st.median([r[k] for r in R]), st.median([r[k] for r in H]))
print("== frame pacing and work during the identical scripted climb (median over %d REF / %d HOST runs)" % (len(R), len(H)))
if R and H:
    for k, label in [("fps", "frames per second"), ("i50", "interval p50 ms"), ("i99", "interval p99 ms"), ("imax", "interval max ms"), ("w50", "work p50 ms"), ("w99", "work p99 ms"), ("s25", "frames slower than 25 ms")]:
        a, b = row(k); print("   %-26s REF %8.2f   HOST %8.2f   (HOST/REF %.3f)" % (label, a, b, (b / a) if a else float('nan')))
    a99, b99 = row("i99"); afps, bfps = row("fps"); aw, bw = row("w99")
    checks = [("interval p99 within REF*1.10+2 ms", b99 <= a99 * 1.10 + 2.0), ("fps at least 97% of REF", bfps >= afps * 0.97), ("work p99 within REF*1.10+2 ms", bw <= aw * 1.10 + 2.0)]
else:
    print("   NO FRAME DATA"); checks = [("frame data present", False)]
pr, ph = pss("REF"), pss("HOST")
if pr and ph:
    a, b = st.median(pr), st.median(ph); print("   %-26s REF %8.0f   HOST %8.0f   (HOST/REF %.3f)" % ("process PSS KB", a, b, b / a)); checks.append(("PSS within REF*1.05+8 MB", b <= a * 1.05 + 8192))
print("== cold start (system-measured ms, median): BASE %s  REF %s  HOST %s" % (cb, cr, ch))
try:
    cr_, ch_ = float(cr), float(ch); checks.append(("cold start within REF+150 ms and 1.15x", ch_ <= cr_ + 150 and ch_ <= cr_ * 1.15 + 50))
except ValueError: checks.append(("cold start data present", False))
for name, ok in checks:
    print("%s  budget: %s" % ("WITHIN" if ok else "EXCEEDED", name)); bad += (not ok)
print("== %s" % ("all budgets met" if not bad else "%d budget(s) exceeded%s" % (bad, "" if strict else " (informational: not a gate off a real device)")))
sys.exit(1 if (bad and strict) else 0)
P
exit $?
