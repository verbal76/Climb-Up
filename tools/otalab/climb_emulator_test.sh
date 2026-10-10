#!/usr/bin/env bash
# Runs INSIDE an Android emulator session. The REAL Climb Up game, three ways on the same device: (BASE) the unmodified packaged game built from :android, (REF) the same classes
# packaged without a loader, (HOST) the same classes delivered as a signed module through the new host. Needs: $BASE_APK $REF_APK $HOST_APK $SERVE $OUT.
set -u
BASE=com.hotatticgames.climbup; REF=com.hotatticgames.climbup.otaref; HOST=com.hotatticgames.climbup.otaexp
BASE_ACT=$BASE/com.hotatticgames.climbup.android.AndroidLauncher; REF_ACT=$REF/com.hotatticgames.climbup.otaref.PackagedLauncher; HOST_ACT=$HOST/com.hotatticgames.climbup.otalab.LabLauncher
: "${BASE_APK:?}" "${REF_APK:?}" "${HOST_APK:?}" "${SERVE:?}" "${OUT:?}"; mkdir -p "$OUT"
HERE=$(dirname "$0"); . "$HERE/lib.sh"

echo "== install (three separate applications; none replaces anything the owner has)"
for a in "$BASE_APK" "$REF_APK" "$HOST_APK"; do adb install -r "$a" >/dev/null && pass "installs: $(basename "$a")" || { fail "install $a"; exit 1; }; done
for p in $BASE $REF $HOST; do adb shell pm clear $p >/dev/null; done
SIZE=$(adb shell wm size | tail -1 | tr -d '\r'); echo "   device: $SIZE API $(adb shell getprop ro.build.version.sdk | tr -d '\r')"

echo "== K1 cold start of the real game: the module-delivered game draws exactly what the same game packaged draws"
echo "   BASE (unmodified packaged game) cold start: $(start "$BASE_ACT")"; sleep 8; shot k1_BASE_screen
gameshots k1_REF $REF "$REF_ACT"
gameshots k1_HOST $HOST "$HOST_ACT"
logs | grep -q "running module v1" && pass "K1 HOST ran the module delivered from DexClassLoader" || fail "K1 HOST did not report running module v1"
SPL_R=$(firstof k1_REF splash); SPL_H=$(firstof k1_HOST splash); TIT_R=$(firstof k1_REF title); TIT_H=$(firstof k1_HOST title)
[ -n "$SPL_R" ] && [ -n "$SPL_H" ] && [ -n "$TIT_R" ] && [ -n "$TIT_H" ] && pass "K1 both builds drew the studio splash and then the title screen ($SPL_H, $TIT_H)" || fail "K1 missing frames (splash $SPL_R/$SPL_H, title $TIT_R/$TIT_H)"
if [ -n "$SPL_H" ] && [ -n "$SPL_R" ]; then
  R=$(diffp "$OUT/k1_HOST/$SPL_H" "$OUT/k1_REF/$SPL_R"); echo "   studio splash  HOST vs REF: $R"; asciip "$OUT/k1_HOST/$SPL_H"
  awk -v s="$(echo "$R" | meanof)" 'BEGIN{exit !(s<1.0)}' && pass "K1 studio splash from the module is identical to the packaged game's" || fail "K1 splash differs between module and packaged ($R)"
fi
if [ -n "$TIT_H" ] && [ -n "$TIT_R" ]; then
  R=$(diffp "$OUT/k1_HOST/$TIT_H" "$OUT/k1_REF/$TIT_R"); echo "   title screen   HOST vs REF: $R"; asciip "$OUT/k1_HOST/$TIT_H"
  awk -v s="$(echo "$R" | meanof)" -v c="$(echo "$R" | corrof)" 'BEGIN{exit !(s<3.0 && c>0.97)}' && pass "K1 title screen from the module matches the packaged game's ($R)" || fail "K1 title differs between module and packaged ($R)"
fi

echo "== K2 deterministic equivalence: the module-loaded game vs the same code packaged, over climbs of several kinds (exact), and vs the JVM"
# autopilot climbs, a flailing player (falls, respawns, checkpoints, crumbling), and autopilot-then-flailing (reaches the hazards); every step's state, events, hits and checkpoints are hashed
K2REQS="digest:12:7200,digest:3:7200,chaos:1:6000,chaos:3:6000,mixed:3:20000,mixed:6:20000"
selftests "$REF_ACT" "$K2REQS" "$OUT/k2_REF.txt" 900; selftests "$HOST_ACT" "$K2REQS" "$OUT/k2_HOST.txt" 900
echo "   requests answered: REF $(wc -l < "$OUT/k2_REF.txt") HOST $(wc -l < "$OUT/k2_HOST.txt") of $(echo "$K2REQS" | tr ',' '\n' | wc -l)"
if [ "$(wc -l < "$OUT/k2_HOST.txt")" -eq "$(echo "$K2REQS" | tr ',' '\n' | wc -l)" ] && cmp -s "$OUT/k2_REF.txt" "$OUT/k2_HOST.txt"; then pass "K2 every digest identical between the packaged game and the module-delivered game, byte for byte"; else fail "K2 digests differ or are missing"; diff "$OUT/k2_REF.txt" "$OUT/k2_HOST.txt" | cut -c1-300; fi
sed 's/^\([^ ]*\) \([0-9a-f]\{12\}\)[0-9a-f]* \(.*\)$/   \1  \2... \3/' "$OUT/k2_HOST.txt" | cut -c1-260
grep -q "^digest:12:7200 .* failed=false" "$OUT/k2_HOST.txt" && pass "K2 the autopilot climb completed on the device" || fail "K2 the autopilot climb did not complete"
grep -E "^(chaos|mixed)" "$OUT/k2_HOST.txt" | grep -Eq "falls=[1-9]" && pass "K2 the flailing runs fell and respawned (checkpoint/respawn logic exercised)" || fail "K2 no falls in the flailing runs"
grep -E "^mixed" "$OUT/k2_HOST.txt" | grep -Eq "hits=[1-9]" && pass "K2 hazard hits occurred in the mixed runs (hazard logic exercised)" || fail "K2 no hazard hit in any mixed run"
if [ -n "${JVM_DIGESTS:-}" ] && [ -s "$JVM_DIGESTS" ]; then
  m=0; d=0; for r in $(echo "$K2REQS" | tr ',' '\n'); do dev=$(grep "^$r " "$OUT/k2_HOST.txt" | cut -d' ' -f2-); jvm=$(grep "^$r -> " "$JVM_DIGESTS" | sed 's/^[^ ]* -> //'); if [ -n "$dev" ] && [ "$dev" = "$jvm" ]; then m=$((m+1)); else d=$((d+1)); echo "   cross-runtime DIFFERENT for $r"; echo "     device: $(echo "$dev" | cut -c1-160)"; echo "     jvm   : $(echo "$jvm" | cut -c1-160)"; fi; done
  [ "$d" -eq 0 ] && pass "K2 the device (ART) reproduces the JVM's digests for all $m requests" || echo "   NOTE (informational, not a gate): $m of $((m+d)) requests identical between the device and the JVM; $d differ"
fi

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

echo "== K7 a signed release that changes the EXECUTING CODE (not the game) while a climb is in progress, then a hard kill"
MARK=$(printf '%s' "climb-up signed code update marker" | sha256sum | cut -c1-12)
go_home; H0=$(adb shell "run-as $HOST sh -c 'cd files; sha256sum history.bin'" | tr -d '\r' | cut -d' ' -f1)
serve c7_code_change; start "$HOST_ACT" --es updateBase http://10.0.2.2:8099/ >/dev/null
expect "K7 code release staged" "update check: staged v7" 40
logs | grep -q "code-marker" && fail "K7 the new code ran before the next cold start" || pass "K7 the running session did not change under the player (activation waits for the next cold start)"
go_home; sleep 1
start "$HOST_ACT" >/dev/null
expect "K7 the new module runs after the cold start" "running module v7" 40
expect "K7 its new code path executed on the device (marker $MARK)" "code-marker $MARK" 20
expect "K7 the host found the climb still in progress" "climbInProgress=true" 20
H1=$(adb shell "run-as $HOST sh -c 'cd files; sha256sum history.bin'" | tr -d '\r' | cut -d' ' -f1); [ -n "$H0" ] && [ "$H0" = "$H1" ] && pass "K7 history.bin byte-identical across the code update" || fail "K7 history changed ($H0 -> $H1)"
selftests "$HOST_ACT" "digest:12:7200" "$OUT/k7_HOST.txt" 300
[ "$(grep '^digest:12:7200 ' "$OUT/k7_HOST.txt")" = "$(grep '^digest:12:7200 ' "$OUT/k2_REF.txt")" ] && pass "K7 the updated code plays the same game: the climb digest equals the packaged game's" || fail "K7 digest after the code update differs"
logs | grep -q "running module v7" && pass "K7 still v7 for the self-test launch" || fail "K7 lost v7"
echo "   --- hard kill (the app is removed from recents / killed by the system) and relaunch ---"
start "$HOST_ACT" >/dev/null; sleep 6; adb shell am force-stop $HOST; sleep 1
start "$HOST_ACT" >/dev/null
expect "K7 after the kill the same module relaunches" "running module v7" 40
expect "K7 after the kill the climb is still recognised as resumable" "climbInProgress=true" 20
H2=$(adb shell "run-as $HOST sh -c 'cd files; sha256sum history.bin'" | tr -d '\r' | cut -d' ' -f1); [ "$H0" = "$H2" ] && pass "K7 history.bin byte-identical after the kill" || fail "K7 history changed by the kill ($H0 -> $H2)"
adb shell "run-as $HOST sh -c 'cd files; grep -o seed[^,}]* save.json'" | tr -d '\r' | grep -q 777 && pass "K7 save still holds the climb (seed 777)" || fail "K7 save lost the climb"
stop_server

echo "== P1 delivery-layer measurements (informational on an emulator: software GL, NOT representative of a phone; never fails the run)"
bash "$HERE/perf_capture.sh" "$OUT/perf" 3 20 2>&1 | tee "$OUT/perf.txt" | sed 's/^/   /' || true

echo "== summary"; logs | grep -o "bootMs=[0-9]*" | tr '\n' ' '; echo
[ "$FAILS" -eq 0 ] && { echo "ALL CHECKS PASSED"; exit 0; } || { echo "$FAILS CHECK(S) FAILED"; exit 1; }
