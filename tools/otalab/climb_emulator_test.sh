#!/usr/bin/env bash
# Runs INSIDE an Android emulator session. The REAL Climb Up game, three ways on the same device: (BASE) the unmodified packaged game built from :android, (REF) the same classes
# packaged without a loader, (HOST) the same classes delivered as a signed module through the new host. Needs: $BASE_APK $REF_APK $HOST_APK $SERVE $OUT.
set -u
BASE=com.hotatticgames.climbup; REF=com.hotatticgames.climbup.otaref; HOST=com.hotatticgames.climbup.otaexp
BASE_ACT=$BASE/com.hotatticgames.climbup.android.AndroidLauncher; REF_ACT=$REF/com.hotatticgames.climbup.otaref.PackagedLauncher; HOST_ACT=$HOST/com.hotatticgames.climbup.otalab.LabLauncher
: "${BASE_APK:?}" "${REF_APK:?}" "${HOST_APK:?}" "${SERVE:?}" "${OUT:?}"; mkdir -p "$OUT"
HERE=$(dirname "$0"); FAILS=0; SERVER_PID=""
pass() { echo "PASS  $*"; }; fail() { echo "FAIL  $*"; FAILS=$((FAILS+1)); }
logs() { adb logcat -d -v brief -s OTALAB:I AndroidRuntime:E 2>/dev/null; }
stop_server() { [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null; SERVER_PID=""; }
serve() { stop_server; (cd "$SERVE/$1" && python3 -m http.server 8099 --bind 0.0.0.0 >/dev/null 2>&1 & echo $! > "$OUT/server.pid"); SERVER_PID=$(cat "$OUT/server.pid"); sleep 1; }
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

echo "== install (three separate applications; none replaces anything the owner has)"
for a in "$BASE_APK" "$REF_APK" "$HOST_APK"; do adb install -r "$a" >/dev/null && pass "installs: $(basename "$a")" || { fail "install $a"; exit 1; }; done
for p in $BASE $REF $HOST; do adb shell pm clear $p >/dev/null; done
SIZE=$(adb shell wm size | tail -1 | tr -d '\r'); echo "   device: $SIZE API $(adb shell getprop ro.build.version.sdk | tr -d '\r')"

echo "== K1 cold start of the real game three ways: studio splash then title"
for who in BASE REF HOST; do
  eval act=\$${who}_ACT; echo "   $who: $(start "$act")"
  sleep 1.6; shot k1_${who}_splash; sleep 7; shot k1_${who}_title
done
logs | grep -q "running module v1" && pass "K1 HOST ran the module delivered from DexClassLoader" || fail "K1 HOST did not report running module v1"
for who in BASE REF HOST; do read m s <<<"$(lum k1_${who}_title)"; echo "   $who title: mean luminance $m, contrast $s"; awk -v s="$s" 'BEGIN{exit !(s>20)}' && pass "K1 $who title screen has content (not blank)" || fail "K1 $who title looks blank"; done
echo "   splash  HOST vs BASE: $(diff k1_HOST_splash k1_BASE_splash)   REF vs BASE: $(diff k1_REF_splash k1_BASE_splash)"
echo "   title   HOST vs BASE: $(diff k1_HOST_title k1_BASE_title)   REF vs BASE: $(diff k1_REF_title k1_BASE_title)"
# the splash is static (logo at full opacity between 0.5 s and 2.3 s): it must match the packaged game almost exactly
S=$(diff k1_HOST_splash k1_BASE_splash | sed 's/mean=\([0-9.]*\).*/\1/'); awk -v s="$S" 'BEGIN{exit !(s<2.0)}' && pass "K1 splash through the module matches the packaged game (mean diff $S/255)" || fail "K1 splash differs from the packaged game (mean diff $S/255)"
T=$(diff k1_HOST_title k1_BASE_title | sed 's/mean=\([0-9.]*\).*/\1/'); awk -v s="$T" 'BEGIN{exit !(s<12.0)}' && pass "K1 title through the module matches the packaged game within animation noise (mean diff $T/255)" || fail "K1 title differs from the packaged game (mean diff $T/255)"

echo "== K2 deterministic climb: the module-loaded game vs the same code packaged (exact)"
R=""; for who in REF HOST; do eval act=\$${who}_ACT; start "$act" --es selftest "digest|12|7200" >/dev/null; wait_for "SELFTEST digest" 240 || fail "K2 $who selftest produced no result"; line=$(logs | grep "SELFTEST digest" | tail -1); echo "   $who: $line"; eval "L_$who=\"\$line\""; done
dref=$(echo "$L_REF" | sed 's/.*-> \([0-9a-f]\{64\}[^[]*\).*/\1/'); dhost=$(echo "$L_HOST" | sed 's/.*-> \([0-9a-f]\{64\}[^[]*\).*/\1/')
[ -n "$dref" ] && [ "$dref" = "$dhost" ] && pass "K2 digest identical, byte for byte ($dhost)" || fail "K2 digests differ: [$dref] vs [$dhost]"
echo "$dhost" | grep -q "failed=false" && pass "K2 the autopilot climb completed on the device" || fail "K2 climb did not complete"

echo "== K3 live play (scripted demo run) through the module: renders, advances, and the module confirms itself"
start "$HOST_ACT" --es prop.climb.demo true >/dev/null
sleep 5; shot k3_HOST_a; sleep 6; shot k3_HOST_b
expect "K3 module declared healthy after 15 s of live play" "confirmed healthy" 40
read m1 s1 <<<"$(lum k3_HOST_a)"; echo "   frame a: $m1 $s1   frame b: $(lum k3_HOST_b)"; awk -v s="$s1" 'BEGIN{exit !(s>20)}' && pass "K3 gameplay frame has content" || fail "K3 gameplay frame blank"
D=$(diff k3_HOST_a k3_HOST_b | sed 's/mean=\([0-9.]*\).*/\1/'); awk -v s="$D" 'BEGIN{exit !(s>0.5)}' && pass "K3 the scene advances over time (mean diff $D)" || fail "K3 the scene did not change"
start "$REF_ACT" --es prop.climb.demo true >/dev/null; sleep 5; shot k3_REF_a
echo "   gameplay frame at 5 s  HOST vs REF: $(diff k3_HOST_a k3_REF_a)"
adb shell input tap 1200 600; sleep 1; logs | grep -q "FATAL" && fail "K3 touch crashed the game" || pass "K3 touch input handled without error"

echo "== K4 a climb in progress survives module updates (and a world-changing module waits)"
start "$HOST_ACT" --es prop.climb.start play --es prop.climb.seed 777 >/dev/null
expect "K4 climb started: host told" "climbInProgress=true" 40; sleep 4
H0=$(adb shell run-as $HOST sh -c 'cd files; sha256sum history.bin' | tr -d '\r' | cut -d' ' -f1); [ -n "$H0" ] && pass "K4 climb history exists ($H0)" || fail "K4 no history.bin"
adb shell input keyevent KEYCODE_HOME; sleep 2                                    # backgrounding persists the save, as on a phone
serve c2_same_world; start "$HOST_ACT" --es prop.climb.start play --es updateBase http://10.0.2.2:8099/ >/dev/null
expect "K4 same-world v2 staged" "update check: staged v2" 40; adb shell input keyevent KEYCODE_HOME; sleep 2
start "$HOST_ACT" --es prop.climb.start play >/dev/null
expect "K4 same-world v2 applied at the next cold start, mid-climb" "running module v2" 40
H1=$(adb shell run-as $HOST sh -c 'cd files; sha256sum history.bin' | tr -d '\r' | cut -d' ' -f1); [ "$H0" = "$H1" ] && pass "K4 history.bin byte-identical after the module switch" || fail "K4 history changed ($H0 -> $H1)"
adb shell run-as $HOST sh -c 'cd files; grep -o "\"seed\":[0-9]*" save.json' | tr -d '\r' | grep -q 777 && pass "K4 save still holds the climb (seed 777)" || fail "K4 save lost the climb"
adb shell input keyevent KEYCODE_HOME; sleep 2
serve c3_new_generator; start "$HOST_ACT" --es prop.climb.start play --es updateBase http://10.0.2.2:8099/ >/dev/null
expect "K4 world-changing v3 staged" "update check: staged v3" 40; adb shell input keyevent KEYCODE_HOME; sleep 2
start "$HOST_ACT" --es prop.climb.start play >/dev/null
expect "K4 v3 waits: generator ruleset would change the climb" "waiting: generator ruleset" 40
logs | grep -q "running module v3" && fail "K4 v3 activated over a climb in progress" || pass "K4 the climb stayed on the module that generated it (v2)"

echo "== K5 forged, broken and unreadable releases cannot disturb the real game"
serve c4_tampered; start "$HOST_ACT" --es updateBase http://10.0.2.2:8099/ >/dev/null; expect "K5 tampered module refused" "update check: .*checksum mismatch" 40
serve c5_no_entry; start "$HOST_ACT" --es updateBase http://10.0.2.2:8099/ >/dev/null; expect "K5 module with a missing entry class staged (it is signed and well-formed)" "update check: staged v5" 40
start "$HOST_ACT" >/dev/null; expect "K5 it fails to load and the previous module runs in the same launch" "rolled back v5 to v2.*failed to load" 40; expect "K5 the real game keeps running on v2" "running module v2" 20
stop_server

echo "== summary"; logs | grep -o "bootMs=[0-9]*" | tr '\n' ' '; echo
[ "$FAILS" -eq 0 ] && { echo "ALL CHECKS PASSED"; exit 0; } || { echo "$FAILS CHECK(S) FAILED"; exit 1; }
