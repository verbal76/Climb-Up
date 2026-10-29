#!/usr/bin/env bash
# Demo (docs/DEMO.md): proves on a real Android runtime (emulator) that the demo APK installs BESIDE the full game (its own package, its own data),
# launches, and keeps running with no crash; saves a screenshot of what it shows.   tools/ci/demo_side_by_side_test.sh <full-game.apk> <demo.apk> <shots dir>
set -euo pipefail
FULL=com.hotatticgames.climbup
DEMO=com.hotatticgames.climbup.demo
mkdir -p "$3"
first_install() { local d; d=$(adb shell dumpsys package $FULL | tr -d '\r'); grep -m1 firstInstallTime <<<"$d" | sed 's/.*=//'; }
adb install "$1"
adb shell monkey -p $FULL -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 20; adb shell am force-stop $FULL
FULL_FIRST=$(first_install)
adb install "$2"                                      # a new package: never an update of (or a replacement for) the full game
PKGS=$(adb shell pm list packages | tr -d '\r')          # captured once: a grep -q on the live pipe would break it under pipefail
echo "$PKGS" | grep -E "package:$FULL$|package:$DEMO$" | sort
grep -qx "package:$FULL" <<<"$PKGS" || { echo "::error::the full game disappeared when the demo was installed"; exit 1; }
grep -qx "package:$DEMO" <<<"$PKGS" || { echo "::error::the demo is not installed as $DEMO"; exit 1; }
FULL_FIRST2=$(first_install)
[ "$FULL_FIRST" = "$FULL_FIRST2" ] || { echo "::error::installing the demo changed the full game's install"; exit 1; }
DUMP=$(adb shell pm dump $DEMO | tr -d '\r')
grep -m3 -E "versionCode|versionName|dataDir" <<<"$DUMP" || true
if grep -qi "android.permission.INTERNET" <<<"$DUMP"; then echo "::error::the demo asks for INTERNET"; exit 1; else echo "no INTERNET permission"; fi
adb logcat -c
adb shell monkey -p $DEMO -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 6;  adb exec-out screencap -p > "$3/android-01-splash.png" || true
sleep 12; adb exec-out screencap -p > "$3/android-02-menu.png" || true
sleep 12
PID=$(adb shell pidof $DEMO | tr -d '\r' || true)
LOG=$(adb logcat -d | tr -d '\r')
if grep -qE "FATAL EXCEPTION|AndroidRuntime: Process: $DEMO" <<<"$LOG"; then echo "::error::the demo crashed"; echo "$LOG" | tail -80; exit 1; fi
[ -n "$PID" ] || { echo "::error::the demo is not running 30 s after launch"; adb logcat -d | tail -80; exit 1; }
adb shell run-as $DEMO ls files 2>/dev/null | tr -d '\r' | head -20 || true
echo "DEMO SIDE-BY-SIDE PASS: $DEMO installed beside $FULL (untouched), launched, running (pid $PID)"
