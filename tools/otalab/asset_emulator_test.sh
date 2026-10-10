#!/usr/bin/env bash
# Runs INSIDE an Android emulator session. Signed GAME-FILE delivery for the real game through the module host: a release carries a changed asset, it is downloaded into the content store,
# verified, activated at the next cold start and drawn by the game, while every other file still comes from the APK. Owner: OTA Engineer 2 (docs/ota-full/OWNERSHIP.md).
# Needs: $HOST_APK $SERVE $OUT; the served folder holds c6_assets (see build_asset_fixtures.sh).
set -u
HOST=com.hotatticgames.climbup.otaexp; HOST_ACT=$HOST/com.hotatticgames.climbup.otalab.LabLauncher
: "${HOST_APK:?}" "${SERVE:?}" "${OUT:?}"; mkdir -p "$OUT"
HERE=$(dirname "$0"); . "$HERE/lib.sh"

echo "== install"; adb install -r "$HOST_APK" >/dev/null && pass "installs: $(basename "$HOST_APK")" || { fail "install"; exit 1; }
adb shell pm clear $HOST >/dev/null

echo "== A1 the unmodified baseline module draws the APK's own files (reference frames)"
gameshots a1_HOST $HOST "$HOST_ACT"
logs | grep -q "running module v1" && pass "A1 baseline module running" || fail "A1 baseline module not running"
SPL_H=$(firstof a1_HOST splash); TIT_H=$(firstof a1_HOST title)
[ -n "$SPL_H" ] && [ -n "$TIT_H" ] && pass "A1 splash and title frames captured ($SPL_H, $TIT_H)" || fail "A1 frames missing (splash $SPL_H, title $TIT_H)"

echo "== A2 a signed release delivers a changed GAME FILE (lab fixture: inverted studio splash); only that file changes"
serve c6_assets; start "$HOST_ACT" --es updateBase http://10.0.2.2:8099/ >/dev/null
expect "A2 v6 and its game file downloaded into the content store and staged" "update check: staged v6" 60
N=$(adb shell "run-as $HOST sh -c 'ls files/host/cas | wc -l'" | tr -d '\r'); [ "$N" -ge 1 ] && pass "A2 content store holds $N verified file(s)" || fail "A2 content store empty"
gameshots a2_HOST $HOST "$HOST_ACT"
expect "A2 v6 activated at the next cold start with its override" "asset overrides active: 1 file" 5
SPL_A=$(firstof a2_HOST splash); TIT_A=$(firstof a2_HOST title)
if [ -n "$SPL_A" ] && [ -n "$SPL_H" ]; then
  R=$(diffp "$OUT/a2_HOST/$SPL_A" "$OUT/a1_HOST/$SPL_H"); echo "   splash with the delivered file vs the APK's: $R"; asciip "$OUT/a2_HOST/$SPL_A"
  awk -v s="$(echo "$R" | meanof)" -v c="$(echo "$R" | corrof)" 'BEGIN{exit !(s>20 && c<0.2)}' && pass "A2 the game now draws the delivered file ($R)" || fail "A2 splash not changed as delivered ($R)"
else fail "A2 no splash frame (override $SPL_A / baseline $SPL_H)"; fi
if [ -n "$TIT_A" ] && [ -n "$TIT_H" ]; then
  R=$(diffp "$OUT/a2_HOST/$TIT_A" "$OUT/a1_HOST/$TIT_H"); echo "   title with an override active vs without: $R"
  awk -v s="$(echo "$R" | meanof)" -v c="$(echo "$R" | corrof)" 'BEGIN{exit !(s<3.0 && c>0.97)}' && pass "A2 every other file still comes from the APK unchanged ($R)" || fail "A2 title changed ($R)"
else fail "A2 no title frame"; fi
logs | grep -q "ClassCast" && fail "A2 ClassCastException" || pass "A2 no ClassCastException (sounds load)"
echo "   game process alive: $(adb shell pidof $HOST | tr -d '\r')"


echo "== summary"; logs | grep -o "bootMs=[0-9]*" | tr '\n' ' '; echo
[ "$FAILS" -eq 0 ] && { echo "ALL CHECKS PASSED"; exit 0; } || { echo "$FAILS CHECK(S) FAILED"; exit 1; }
