#!/usr/bin/env bash
# Runs INSIDE an Android emulator session (api 34, x86_64). Exercises the full-game OTA host with the synthetic module on real ART:
# class loading, read-only dex, lifecycle, input, rendering, staged update -> next-start activation, tamper/corruption rejection, crash-loop rollback, offline start, recovery.
# Needs: adb on PATH, the lab APK path in $APK, prebuilt bundles under $SERVE/<scenario>/, a screenshot checker, and $OUT for logs. Prints PASS/FAIL per check; exit 1 if any FAIL.
set -u
PKG=com.hotatticgames.climbup.otalab; ACT=$PKG/.LabLauncher
: "${APK:?}" "${SERVE:?}" "${OUT:?}"; mkdir -p "$OUT"
FAILS=0; SERVER_PID=""
pass() { echo "PASS  $*"; }
fail() { echo "FAIL  $*"; FAILS=$((FAILS+1)); }
logs() { adb logcat -d -v brief -s OTALAB:I AndroidRuntime:E 2>/dev/null; }
stop_server() { [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null; SERVER_PID=""; }
serve() { stop_server; python3 -m http.server 8099 --bind 0.0.0.0 --directory "$SERVE/$1" >/dev/null 2>&1 & SERVER_PID=$!; sleep 1; }
go_home() { adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null; sleep 1; }
# cold start (force-stop first); $1 = optional update base URL
launch() { adb logcat -c; if [ -n "${1:-}" ]; then adb shell am start -S -W -n "$ACT" --es updateBase "$1" >/dev/null; else adb shell am start -S -W -n "$ACT" >/dev/null; fi; }
wait_for() { local pat=$1 t=${2:-45} i; for i in $(seq "$t"); do logs | grep -Eq "$pat" && return 0; sleep 1; done; return 1; }
expect() { local what=$1 pat=$2 t=${3:-45}; if wait_for "$pat" "$t"; then pass "$what"; else fail "$what (waited for /$pat/)"; logs | tail -25; fi; }
refute() { local what=$1 pat=$2; if logs | grep -Eq "$pat"; then fail "$what (found /$pat/)"; logs | tail -15; else pass "$what"; fi; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
color() { python3 "$(dirname "$0")/checkpix.py" "$OUT/$1.png"; }

echo "== install"; adb install -r "$APK" >/dev/null && pass "lab APK installs (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))" || { fail "install"; exit 1; }
adb shell pm clear $PKG >/dev/null

echo "== S1 baseline module (signed, shipped inside the APK) loads from DexClassLoader and renders"
launch
expect "S1 baseline installed + run (v1)" "running module v1"
expect "S1 module class loader is DexClassLoader" "class loader .*DexClassLoader"
expect "S1 module created through the host's libGDX (single Gdx)" "gdx=true"
expect "S1 packaged asset read from the verified module dir" "asset: hello from v1"
expect "S1 first frame rendered" "module 1 first frame"
expect "S1 healthy after 120 frames -> confirmed" "confirmed healthy"
sleep 2; shot s1; C1=$(color s1); echo "   screen colour: $C1"; echo "$C1" | grep -q "^OK v1" && pass "S1 pixels match v1 clear colour" || fail "S1 pixels ($C1)"

echo "== S2 input and lifecycle reach the module"
adb shell input tap 400 300; expect "S2 touch delivered to module" "module 1 touch"
go_home; expect "S2 host paused" "host onPause"; expect "S2 module paused" "module 1 pause"
adb shell am start -n "$ACT" >/dev/null; expect "S2 module resumed" "module 1 resume"

echo "== S3 download v2 while v1 plays: staged, NOT activated"
serve v2; launch http://10.0.2.2:8099/
expect "S3 v2 verified and staged" "update check: staged v2"
sleep 3; refute "S3 the running session never switches to v2" "module 2 create"

echo "== S4 next cold start activates v2 (and only then)"
launch
expect "S4 v2 activated at cold start" "running module v2"; expect "S4 v2 confirmed" "module 2 healthy"; expect "S4 v2 confirmed healthy" "confirmed healthy"
sleep 2; shot s4; C2=$(color s4); echo "   screen colour: $C2"; echo "$C2" | grep -q "^OK v2" && pass "S4 pixels match v2 clear colour" || fail "S4 pixels ($C2)"

echo "== S5 forged / corrupt / incompatible releases are refused before anything runs"
for sc in t_dex:"checksum mismatch" t_manifest:"signature invalid" t_signature:"signature invalid" wrong_key:"signature invalid" wrong_app:"another app" wrong_iface:"different interface" wrong_channel:"wrong channel" wrong_host:"different host" old_v1:"up to date"; do
  name=${sc%%:*}; want=${sc#*:}; serve "$name"; launch http://10.0.2.2:8099/
  expect "S5 $name refused ($want)" "update check: .*$want" 30
done
launch; expect "S5 after every attempt the install still runs v2" "running module v2"; refute "S5 nothing was staged" "staged=[1-9]"

echo "== S6 a module that starts and then crashes the process is rolled back automatically"
serve crash_v4; launch http://10.0.2.2:8099/; expect "S6 v4 staged" "update check: staged v4"
launch; expect "S6 launch 1 of v4" "running module v4"; expect "S6 v4 crashes" "synthetic module crashes while running" 30
launch; expect "S6 launch 2 of v4" "running module v4"; expect "S6 v4 crashes again" "synthetic module crashes while running" 30
launch; expect "S6 launch 3: rolled back to v2" "rolled back v4 to v2" ; expect "S6 v2 plays again" "module 2 healthy"
serve crash_v4; launch http://10.0.2.2:8099/; expect "S6 the broken v4 is not downloaded again" "update check: .*(up to date|rolled back or revoked)"

echo "== S7 a module that cannot start falls back inside the same launch"
serve throw_v5; launch http://10.0.2.2:8099/; expect "S7 v5 staged" "update check: staged v5"
launch; expect "S7 create() failure seen" "module create failed"; expect "S7 same launch continues on v2" "module 2 healthy"; expect "S7 rollback recorded" "rolled back v5 to v2.*failed to load"

echo "== S8 offline start works"
stop_server; launch http://10.0.2.2:8099/; expect "S8 offline check fails silently" "update check: offline or failed"; expect "S8 game still runs" "module 2 healthy"

echo "== S9 corrupted module at rest -> recovery path -> baseline from the APK restores play"
adb shell "run-as $PKG sh -c 'chmod u+w files/host/mod/v2/module.dex; printf X | dd of=files/host/mod/v2/module.dex bs=1 seek=100 conv=notrunc'" 
launch; expect "S9 corruption detected" "verification failed"; expect "S9 baseline reinstalled and running" "running module v1"; expect "S9 v1 plays" "module 1 healthy"

echo "== summary"; logs | grep -o "bootMs=[0-9]*" | tr '\n' ' '; echo
adb logcat -d -v time -s OTALAB:I AndroidRuntime:E > "$OUT/logcat-final.txt" 2>/dev/null
[ "$FAILS" -eq 0 ] && { echo "ALL CHECKS PASSED"; exit 0; } || { echo "$FAILS CHECK(S) FAILED"; exit 1; }
