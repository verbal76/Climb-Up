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
echo "   splash  HOST vs REF: $(diff k1_HOST_splash k1_REF_splash)   (HOST vs BASE: $(diff k1_HOST_splash k1_BASE_splash), informational: the unmodified app starts on a different clock)"
echo "   title   HOST vs REF: $(diff k1_HOST_title k1_REF_title)   (HOST vs BASE: $(diff k1_HOST_title k1_BASE_title))"
ascii k1_HOST_splash; ascii k1_HOST_title; ascii k1_BASE_title
S=$(diff k1_HOST_splash k1_REF_splash | sed 's/mean=\([0-9.]*\).*/\1/'); awk -v s="$S" 'BEGIN{exit !(s<1.0)}' && pass "K1 splash through the module is identical to the same game packaged (mean diff $S/255)" || fail "K1 splash differs between module and packaged (mean diff $S/255)"
T=$(diff k1_HOST_title k1_REF_title | sed 's/mean=\([0-9.]*\).*/\1/'); awk -v s="$T" 'BEGIN{exit !(s<3.0)}' && pass "K1 title through the module matches the packaged game within animation noise (mean diff $T/255)" || fail "K1 title differs between module and packaged (mean diff $T/255)"

echo "== K2 deterministic climb: the module-loaded game vs the same code packaged (exact)"
R=""; for who in REF HOST; do eval act=\$${who}_ACT; start "$act" --es selftest "digest:12:7200" >/dev/null; wait_for "SELFTEST digest" 240 || fail "K2 $who selftest produced no result"; line=$(logs | grep "SELFTEST digest" | tail -1); echo "   $who: $line"; eval "L_$who=\"\$line\""; done
dref=$(echo "$L_REF" | sed 's/.*-> \([0-9a-f]\{64\}[^[]*\).*/\1/'); dhost=$(echo "$L_HOST" | sed 's/.*-> \([0-9a-f]\{64\}[^[]*\).*/\1/')
[ -n "$dref" ] && [ "$dref" = "$dhost" ] && pass "K2 digest identical, byte for byte ($dhost)" || fail "K2 digests differ: [$dref] vs [$dhost]"
echo "$dhost" | grep -q "failed=false" && pass "K2 the autopilot climb completed on the device" || fail "K2 climb did not complete"

echo "== K3 live play (scripted demo run) through the module: renders, advances, and the module confirms itself"
start "$HOST_ACT" --es prop.climb.demo true >/dev/null
sleep 5; shot k3_HOST_a; sleep 5; shot k3_HOST_b; sleep 8; shot k3_HOST_c
expect "K3 module declared healthy after 15 s of live play" "confirmed healthy" 40
read m1 s1 <<<"$(lum k3_HOST_a)"; echo "   frame a: $m1 $s1   frame b: $(lum k3_HOST_b)"; awk -v s="$s1" 'BEGIN{exit !(s>20)}' && pass "K3 gameplay frame has content" || fail "K3 gameplay frame blank"
ascii k3_HOST_a; ascii k3_HOST_b; ascii k3_HOST_c; echo "   a vs b: $(diff k3_HOST_a k3_HOST_b)   b vs c: $(diff k3_HOST_b k3_HOST_c)"
D=$(diff k3_HOST_a k3_HOST_c | sed 's/mean=\([0-9.]*\).*/\1/'); awk -v s="$D" 'BEGIN{exit !(s>0.5)}' && pass "K3 the scene advances over time (mean diff $D)" || fail "K3 the scene did not change"
start "$REF_ACT" --es prop.climb.demo true >/dev/null; sleep 5; shot k3_REF_a
echo "   gameplay frame at 5 s  HOST vs REF: $(diff k3_HOST_a k3_REF_a)"
adb shell input tap 1200 600; sleep 1; logs | grep -q "FATAL" && fail "K3 touch crashed the game" || pass "K3 touch input handled without error"

echo "== K4 a climb in progress survives module updates (and a world-changing module waits)"
start "$HOST_ACT" --es prop.climb.start play --es prop.climb.seed 777 >/dev/null
expect "K4 climb started: host told" "climbInProgress=true" 40; sleep 4
H0=$(adb shell "run-as $HOST sh -c 'cd files; sha256sum history.bin'" | tr -d '\r' | cut -d' ' -f1); [ -n "$H0" ] && pass "K4 climb history exists ($H0)" || fail "K4 no history.bin"
go_home; sleep 1                                    # backgrounding persists the save, as on a phone
serve c2_same_world; start "$HOST_ACT" --es prop.climb.start play --es updateBase http://10.0.2.2:8099/ >/dev/null
expect "K4 same-world v2 staged" "update check: staged v2" 40; go_home; sleep 1
start "$HOST_ACT" --es prop.climb.start play >/dev/null
expect "K4 same-world v2 applied at the next cold start, mid-climb" "running module v2" 40
H1=$(adb shell "run-as $HOST sh -c 'cd files; sha256sum history.bin'" | tr -d '\r' | cut -d' ' -f1); [ "$H0" = "$H1" ] && pass "K4 history.bin byte-identical after the module switch" || fail "K4 history changed ($H0 -> $H1)"
adb shell "run-as $HOST sh -c 'cd files; grep -o seed[^,}]* save.json'" | tr -d '\r' | grep -q 777 && pass "K4 save still holds the climb (seed 777)" || fail "K4 save lost the climb"
go_home; sleep 1
serve c3_new_generator; start "$HOST_ACT" --es prop.climb.start play --es updateBase http://10.0.2.2:8099/ >/dev/null
expect "K4 world-changing v3 staged" "update check: staged v3" 40; go_home; sleep 1
start "$HOST_ACT" --es prop.climb.start play >/dev/null
expect "K4 v3 waits: generator ruleset would change the climb" "waiting: generator ruleset" 40
logs | grep -q "running module v3" && fail "K4 v3 activated over a climb in progress" || pass "K4 the climb stayed on the module that generated it (v2)"

echo "== K5 forged, broken and unreadable releases cannot disturb the real game"
serve c4_tampered; start "$HOST_ACT" --es updateBase http://10.0.2.2:8099/ >/dev/null; expect "K5 tampered module refused" "update check: .*checksum mismatch" 40
serve c5_no_entry; start "$HOST_ACT" --es updateBase http://10.0.2.2:8099/ >/dev/null; expect "K5 module with a missing entry class staged (it is signed and well-formed)" "update check: staged v5" 40
start "$HOST_ACT" >/dev/null; # v2 was applied in K4 but never played 15 s, so it is still unproven and is NOT the safety net: the last PROVEN module (the baseline v1) is
expect "K5 it fails to load; the last proven module (v1; v2 never proved itself) runs in the same launch" "rolled back v5 to v1.*failed to load" 40; expect "K5 the real game keeps running" "running module v1" 20
stop_server

echo "== K6 a signed release delivers a changed GAME FILE (lab fixture: inverted studio splash); only that file changes"
serve c6_assets; start "$HOST_ACT" --es updateBase http://10.0.2.2:8099/ >/dev/null
expect "K6 v6 and its game file downloaded into the content store and staged" "update check: staged v6" 60
N=$(adb shell "run-as $HOST sh -c 'ls files/host/cas | wc -l'" | tr -d '\r'); [ "$N" -ge 1 ] && pass "K6 content store holds $N verified file(s)" || fail "K6 content store empty"
start "$HOST_ACT" --es prop.climb.start splash >/dev/null
expect "K6 v6 activated at the next cold start with its override" "asset overrides active: 1 file" 40
sleep 1.6; shot k6_splash; sleep 7; shot k6_title
echo "   splash vs the unchanged splash (REF): $(diff k6_splash k1_REF_splash)   title vs REF: $(diff k6_title k1_REF_title)"
ascii k6_splash
SP=$(diff k6_splash k1_REF_splash | sed 's/mean=\([0-9.]*\).*/\1/'); awk -v s="$SP" 'BEGIN{exit !(s>20)}' && pass "K6 the delivered file is what the game shows (splash differs by $SP/255)" || fail "K6 splash unchanged ($SP/255)"
TT=$(diff k6_title k1_REF_title | sed 's/mean=\([0-9.]*\).*/\1/'); awk -v s="$TT" 'BEGIN{exit !(s<3.0)}' && pass "K6 every other file still comes from the APK unchanged (title diff $TT/255)" || fail "K6 title changed ($TT/255)"

echo "== P1 delivery-layer measurements (informational on an emulator: software GL, NOT representative of a phone; never fails the run)"
bash "$HERE/perf_capture.sh" "$OUT/perf" 3 20 2>&1 | tee "$OUT/perf.txt" | sed 's/^/   /' || true

echo "== summary"; logs | grep -o "bootMs=[0-9]*" | tr '\n' ' '; echo
[ "$FAILS" -eq 0 ] && { echo "ALL CHECKS PASSED"; exit 0; } || { echo "$FAILS CHECK(S) FAILED"; exit 1; }
