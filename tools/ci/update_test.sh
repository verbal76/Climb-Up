#!/usr/bin/env bash
# Build 73: proves on a real Android runtime (emulator) that the new APK installs as an UPDATE over the last published build (same app, its data kept,
# no uninstall), then launches it and requires the game to keep running with no crash.   tools/ci/update_test.sh <previous.apk> <new.apk>
set -euo pipefail
PKG=com.hotatticgames.climbup
adb install "$1"
FIRST=$(adb shell dumpsys package $PKG | grep -m1 firstInstallTime | tr -d '\r' | sed 's/.*=//')
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 20; adb shell am force-stop $PKG
adb install -r "$2"                                   # fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE if the signing chain is broken
FIRST2=$(adb shell dumpsys package $PKG | grep -m1 firstInstallTime | tr -d '\r' | sed 's/.*=//')
VC=$(adb shell dumpsys package $PKG | grep -m1 versionCode | tr -d '\r')
echo "first installed: $FIRST / after update: $FIRST2; $VC"
[ "$FIRST" = "$FIRST2" ] || { echo "::error::the update replaced the app instead of updating it"; exit 1; }
adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 30
PID=$(adb shell pidof $PKG | tr -d '\r' || true)
adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: Process: $PKG" && { echo "::error::the game crashed after the update"; exit 1; } || true
[ -n "$PID" ] || { echo "::error::the game is not running 30 s after launch"; adb logcat -d | tail -80; exit 1; }
adb shell dumpsys activity activities | grep -m1 -E "mResumedActivity|topResumedActivity" | tr -d '\r'
echo "UPDATE TEST PASS: installed over Build 71 with data kept, launched, running (pid $PID)"
