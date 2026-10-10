#!/usr/bin/env bash
# Runs INSIDE an Android emulator session. The LIVE update path end to end: the OTA test APK (baseline v1, channel address inside) is installed, then NEW signed releases are published
# to the experimental GitHub channel by GitHub Actions while the app is installed, and the app must fetch them over the network, verify, stage and activate them at the next cold start.
# Releases (built beforehand into $REL, versions in $REL/v1..v4): r1 = changed executable code + a changed game file; r2 = signed but cannot load; r3 = signed with a key the app does not
# trust; r4 = clean (plain game, no override). Needs: $APK $REL $CHANNEL $OUT $GH_TOKEN $GITHUB_REPOSITORY. Owner: OTA Engineer 1.
set -u
HOST=com.hotatticgames.climbup.otaexp; HOST_ACT=$HOST/com.hotatticgames.climbup.otalab.LabLauncher
: "${APK:?}" "${REL:?}" "${CHANNEL:?}" "${OUT:?}"; mkdir -p "$OUT"; SERVE=/nonexistent
HERE=$(dirname "$0"); . "$HERE/lib.sh"
V1=$(cat "$REL/v1"); V2=$(cat "$REL/v2"); V3=$(cat "$REL/v3"); V4=$(cat "$REL/v4")
MARK=$(printf '%s' "climb-up signed code update marker" | sha256sum | cut -c1-12)

# the channel, as the CDN serves it to everybody, shows version $1
channel_has() { local n=$1 i v; for i in $(seq 90); do v=$(curl -fsS -m 15 "${CHANNEL}manifest.json?nocache=$(date +%s)$i" 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin).get("moduleVersion",0))' 2>/dev/null); [ "${v:-0}" = "$n" ] && return 0; sleep 10; done; return 1; }
# cold-start the app until its own update check reports $1 (the device may see a CDN copy a few minutes after the runner does)
start_until() { local pat=$1 tries=${2:-14} i; for i in $(seq "$tries"); do start "$HOST_ACT" >/dev/null; wait_for "$pat" 25 && return 0; sleep 20; done; return 1; }
publish() { bash "$HERE/publish_channel.sh" "$REL/$1" "$2"; }

echo "== L0 install the OTA test APK; it is the baseline (v1) and knows only the channel address"
adb install -r "$APK" >/dev/null && pass "L0 installs: $(basename "$APK")" || { fail "L0 install"; exit 1; }
adb shell pm clear $HOST >/dev/null
gameshots live_PRE $HOST "$HOST_ACT"
logs | grep -q "running module v1" && pass "L0 the baseline module runs" || fail "L0 baseline not running"
echo "   channel check at first start: $(logs | grep -E 'update check|update status' | sed 's/.*OTALAB *([0-9]*): //' | tr '\n' '|' | cut -c1-260)"
SPL_0=$(firstof live_PRE splash); TIT_0=$(firstof live_PRE title)

echo "== L1 a NEW release (changed executable code + a changed game file) is published by GitHub Actions while the app is installed"
publish r1 "$V1" && pass "L1 published v$V1 to the channel" || fail "L1 publish v$V1"
channel_has "$V1" && pass "L1 the channel serves v$V1" || fail "L1 the channel never showed v$V1"
start_until "update check: staged v$V1" && pass "L1 the app fetched v$V1 over the network, verified and staged it" || fail "L1 the app did not stage v$V1"
logs | grep -q "running module v$V1" && fail "L1 v$V1 ran before the next cold start" || pass "L1 the running session did not change under the player"
go_home; sleep 1
gameshots live_R1 $HOST "$HOST_ACT"
expect "L1 v$V1 runs after the restart" "running module v$V1" 5
expect "L1 its changed executable code is running (marker $MARK)" "code-marker $MARK" 5
expect "L1 its changed game file is active" "asset overrides active: 1 file" 5
logs | grep -q "ClassCastException" && fail "L1 a sound failed to load" || pass "L1 sounds load"
SPL_1=$(firstof live_R1 splash); TIT_1=$(firstof live_R1 title)
if [ -n "$SPL_1" ] && [ -n "$SPL_0" ]; then R=$(diffp "$OUT/live_R1/$SPL_1" "$OUT/live_PRE/$SPL_0"); echo "   splash after the live update vs before: $R"; awk -v s="$(echo "$R" | meanof)" 'BEGIN{exit !(s>20)}' && pass "L1 the delivered game file is on screen" || fail "L1 splash unchanged ($R)"; else fail "L1 splash frames missing"; fi
if [ -n "$TIT_1" ] && [ -n "$TIT_0" ]; then R=$(diffp "$OUT/live_R1/$TIT_1" "$OUT/live_PRE/$TIT_0"); echo "   title after vs before: $R"; awk -v s="$(echo "$R" | meanof)" -v c="$(echo "$R" | corrof)" 'BEGIN{exit !(s<3 && c>0.97)}' && pass "L1 every other file is unchanged" || fail "L1 the title changed ($R)"; fi
start "$HOST_ACT" --es prop.climb.demo true >/dev/null
expect "L1 v$V1 proves itself in live play" "confirmed healthy" 60

echo "== L2 a release that is signed but cannot start is published: the app rolls back to the last proven release inside the same launch"
publish r2 "$V2" && pass "L2 published v$V2" || fail "L2 publish v$V2"
channel_has "$V2" || fail "L2 the channel never showed v$V2"
start_until "update check: staged v$V2" && pass "L2 v$V2 staged" || fail "L2 v$V2 not staged"
go_home; sleep 1; start "$HOST_ACT" >/dev/null
expect "L2 the broken release was rolled back" "rolled back v$V2 to v$V1" 40
expect "L2 the game keeps running on the last proven release" "running module v$V1" 20

echo "== L3 a release signed with a key the app does not trust is published: it is refused"
publish r3 "$V3" && pass "L3 published v$V3 (wrong signing key)" || fail "L3 publish v$V3"
channel_has "$V3" || fail "L3 the channel never showed v$V3"
start "$HOST_ACT" >/dev/null; wait_for "update check:" 40
logs | grep -q "update check: staged v$V3" && fail "L3 an untrusted release was staged" || pass "L3 the untrusted release was not staged ($(logs | grep 'update check:' | sed 's/.*update check: //' | head -1 | cut -c1-80))"

echo "== L4 a clean newer release is published: the changed code and the changed file are replaced again"
publish r4 "$V4" && pass "L4 published v$V4" || fail "L4 publish v$V4"
channel_has "$V4" || fail "L4 the channel never showed v$V4"
start_until "update check: staged v$V4" && pass "L4 v$V4 staged" || fail "L4 v$V4 not staged"
go_home; sleep 1
gameshots live_R4 $HOST "$HOST_ACT"
expect "L4 v$V4 runs" "running module v$V4" 5
logs | grep -q "code-marker" && fail "L4 the old changed code is still running" || pass "L4 the changed code is gone"
logs | grep -q "asset overrides active" && fail "L4 the old override is still active" || pass "L4 the override is gone"
SPL_4=$(firstof live_R4 splash)
if [ -n "$SPL_4" ] && [ -n "$SPL_0" ]; then R=$(diffp "$OUT/live_R4/$SPL_4" "$OUT/live_PRE/$SPL_0"); echo "   splash after v$V4 vs the original: $R"; awk -v s="$(echo "$R" | meanof)" 'BEGIN{exit !(s<5)}' && pass "L4 the original splash is back" || fail "L4 splash differs from the original ($R)"; fi
echo "   final: $(logs | grep -E 'update status' | tail -1 | sed 's/.*OTALAB *([0-9]*): //' | cut -c1-200)"

echo "== summary"
[ "$FAILS" -eq 0 ] && { echo "ALL CHECKS PASSED"; exit 0; } || { echo "$FAILS CHECK(S) FAILED"; exit 1; }
