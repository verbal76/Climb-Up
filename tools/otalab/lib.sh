#!/usr/bin/env bash
# Shared helpers for the emulator scenario scripts (sourced, never run on its own). Owner: OTA Engineer 1 (docs/ota-full/OWNERSHIP.md); other scripts call these, they do not edit them.
# Needs $OUT (evidence directory) and $SERVE (served bundles) in the environment; defines HERE, FAILS, SERVER_PID, pass/fail, log + wait helpers, a local bundle server, screenshots and the
# game's own framebuffer shots (gameshots/firstof), image comparison and ASCII thumbnails.
HERE=${HERE:-$(dirname "${BASH_SOURCE[0]}")}; FAILS=0; SERVER_PID=""
pass() { echo "PASS  $*"; }; fail() { echo "FAIL  $*"; FAILS=$((FAILS+1)); }
logs() { adb logcat -d -v brief -s OTALAB:I AndroidRuntime:E 2>/dev/null; }
stop_server() { [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null; SERVER_PID=""; }
serve() { stop_server; python3 -m http.server 8099 --bind 0.0.0.0 --directory "$SERVE/$1" >/dev/null 2>&1 & SERVER_PID=$!; sleep 1; }
go_home() { adb shell input keyevent KEYCODE_HOME; sleep 1; adb shell am start -a android.settings.SETTINGS >/dev/null; sleep 2; }      # a full-screen activity from another app: backgrounding the game is deterministic
ascii() { echo "   --- $1 ---"; python3 "$HERE/ascii.py" "$OUT/$1.png" 64 | sed 's/^/   | /'; }
wait_for() { local pat=$1 t=${2:-60} i; for i in $(seq "$t"); do logs | grep -Eq "$pat" && return 0; sleep 1; done; return 1; }
expect() { local what=$1 pat=$2 t=${3:-60}; if wait_for "$pat" "$t"; then pass "$what"; else fail "$what (waited for /$pat/)"; logs | tail -25; fi; }
# start <activity> [am extras...]: cold start, prints "TotalTime" (ms) the system measured
start() { local act=$1; shift; adb logcat -c; adb shell am start -S -W -n "$act" "$@" 2>&1 | grep -E "TotalTime" | tr -d '\r'; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
lum() { python3 - "$OUT/$1.png" <<'P'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("L"); px = list(im.getdata()); n = len(px); m = sum(px) / n; v = sum((p - m) ** 2 for p in px) / n
print("%.1f %.1f" % (m, v ** 0.5))
P
}
diff() { python3 "$HERE/imgdiff.py" "$OUT/$1.png" "$OUT/$2.png"; }


# gameshots NAME PKG ACT [am extras...]: runs the game with its OWN framebuffer screenshots (climb.shots: files are named by the screen that drew them, e.g. splash_00.png, title_02.png),
# waits for ten of them and pulls them to $OUT/NAME/. Independent of device compositor timing, so the same screen can be compared across builds.
gameshots() { local name=$1 pkg=$2 act=$3; shift 3; local d=$OUT/$name i n; rm -rf "$d"; mkdir -p "$d"; adb shell "run-as $pkg rm -rf files/shots" >/dev/null 2>&1; adb logcat -c
  adb shell am start -S -W -n "$act" --es prop.climb.shots "/data/data/$pkg/files/shots" --es prop.climb.shotEvery 1.0 --es prop.climb.shotCount 10 "$@" >/dev/null
  for i in $(seq 150); do n=$(adb shell "run-as $pkg ls files/shots 2>/dev/null | wc -l" | tr -d '\r'); [ "${n:-0}" -ge 10 ] && break; sleep 1; done
  for f in $(adb shell "run-as $pkg ls files/shots" | tr -d '\r'); do adb exec-out "run-as $pkg cat files/shots/$f" > "$d/$f"; done
  echo "   $name frames: $(ls "$d" | tr '\n' ' ')"; }
firstof() { ls "$OUT/$1" | grep "^$2_" | head -1; }
diffp() { python3 "$HERE/imgdiff.py" "$1" "$2"; }
asciip() { echo "   --- $1 ---"; python3 "$HERE/ascii.py" "$1" 64 | sed 's/^/   | /'; }
meanof() { sed 's/.*mean=\([0-9.]*\).*/\1/'; }
corrof() { sed 's/.*corr=\(-\?[0-9.]*\).*/\1/'; }

