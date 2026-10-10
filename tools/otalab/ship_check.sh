#!/usr/bin/env bash
# Runs INSIDE an Android emulator session. Takes the PUBLISHED OTA test APK exactly as the owner downloads it (GitHub Release) and checks that a release already published to the
# experimental channel reaches it over the internet: staged on the first start, active after the next cold start, visible on the title screen, proven healthy. No APK is built here.
# Needs: $APK $CHANNEL $OUT $VERSION (the channel release number) $KIND (banner|plain). Owner: OTA Engineer 1.
set -u
HOST=com.hotatticgames.climbup.otaexp; HOST_ACT=$HOST/com.hotatticgames.climbup.otalab.LabLauncher
: "${APK:?}" "${CHANNEL:?}" "${OUT:?}" "${VERSION:?}" "${KIND:?}"; mkdir -p "$OUT"; SERVE=/nonexistent
HERE=$(dirname "$0"); . "$HERE/lib.sh"

echo "== S0 install the published APK (the one the owner has), first start: baseline game, then its own update check"
adb install -r "$APK" >/dev/null && pass "S0 installs: $(basename "$APK")" || { fail "S0 install"; exit 1; }
adb shell pm clear $HOST >/dev/null
gameshots ship_PRE $HOST "$HOST_ACT"
logs | grep -q "running module v1" && pass "S0 the baseline game runs (module v1)" || fail "S0 baseline not running"
echo "   update check on the first start: $(logs | grep -E 'update check|update status' | sed 's/.*OTALAB *([0-9]*): //' | tr '\n' '|' | cut -c1-260)"
for i in $(seq 8); do logs | grep -q "update check: staged v$VERSION" && break; start "$HOST_ACT" >/dev/null; wait_for "update check: staged v$VERSION" 30 && break; sleep 20; done
logs | grep -q "update check: staged v$VERSION" && pass "S1 the installed app fetched v$VERSION from the GitHub channel, verified and staged it" || fail "S1 v$VERSION was not staged"
logs | grep -q "running module v$VERSION" && fail "S1 v$VERSION ran before the next cold start" || pass "S1 the running session did not change under the player"
TIT_0=$(firstof ship_PRE title); SPL_0=$(firstof ship_PRE splash)

echo "== S2 next cold start: the update is active"
go_home; sleep 1
gameshots ship_NEW $HOST "$HOST_ACT"
expect "S2 module v$VERSION runs after the restart" "running module v$VERSION" 5
if [ "$KIND" = banner ]; then
  expect "S2 the delivered title banner code is running" "title-banner v$VERSION" 5
  TIT_1=$(firstof ship_NEW title); SPL_1=$(firstof ship_NEW splash)
  if [ -n "$TIT_1" ] && [ -n "$TIT_0" ]; then R=$(diffp "$OUT/ship_NEW/$TIT_1" "$OUT/ship_PRE/$TIT_0"); echo "   title after the update vs before: $R"
    awk -v s="$(echo "$R" | meanof)" 'BEGIN{exit !(s>3)}' && pass "S2 the title screen visibly changed" || fail "S2 the title screen did not change ($R)"
    python3 - "$OUT/ship_NEW/$TIT_1" "$OUT/ship_PRE/$TIT_0" <<'PY'
import sys
from PIL import Image, ImageChops
a = Image.open(sys.argv[1]).convert("RGB"); b = Image.open(sys.argv[2]).convert("RGB")
w, h = a.size; top = int(h * 0.13)
rest_a = a.crop((0, top, w, h)); rest_b = b.crop((0, top, w, h))
d = ImageChops.difference(rest_a, rest_b); px = list(d.convert("L").getdata())
print("   below the ribbon: %.2f%% of pixels differ more than 40 levels (the live tower and hero animate)" % (100.0 * sum(1 for p in px if p > 40) / len(px)))
PY
  else fail "S2 title frames missing"; fi
  if [ -n "$SPL_1" ] && [ -n "$SPL_0" ]; then R=$(diffp "$OUT/ship_NEW/$SPL_1" "$OUT/ship_PRE/$SPL_0"); awk -v s="$(echo "$R" | meanof)" 'BEGIN{exit !(s<3)}' && pass "S2 the studio splash is unchanged ($R)" || fail "S2 the splash changed ($R)"; fi
else
  logs | grep -q "title-banner" && fail "S2 the banner code is still running" || pass "S2 the banner is gone"
fi
start "$HOST_ACT" --es prop.climb.demo true >/dev/null
expect "S3 v$VERSION proves itself in live play" "confirmed healthy" 60
if [ -n "${AUTO_DIR:-}" ] && [ "$KIND" = plain ]; then
  NEXT=$((VERSION+1))
  echo "== S4 automatic apply: while v$VERSION is running on the title screen, v$NEXT is published; the app must fetch it, show the panel and relaunch itself with no input"
  bash "$HERE/publish_channel.sh" "$AUTO_DIR" "$NEXT" && pass "S4 published v$NEXT" || fail "S4 publish v$NEXT"
  for i in $(seq 90); do v=$(curl -fsS -m 15 "${CHANNEL}manifest.json?nocache=$(date +%s)$i" 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin).get("moduleVersion",0))' 2>/dev/null); [ "${v:-0}" = "$NEXT" ] && break; sleep 10; done
  ok=0
  for i in $(seq 10); do
    start "$HOST_ACT" >/dev/null
    if wait_for "running module v$NEXT" 45; then ok=1; break; fi
    go_home; sleep 20
  done
  [ "$ok" = 1 ] && pass "S4 v$NEXT is running after the app relaunched itself" || fail "S4 no automatic relaunch into v$NEXT"
  logs | grep -q "auto-apply: restarting" && pass "S4 the automatic apply logged its decision" || fail "S4 no auto-apply log line"
fi
echo "   final: $(logs | grep -E 'update status' | tail -1 | sed 's/.*OTALAB *([0-9]*): //' | cut -c1-200)"
[ "$FAILS" -eq 0 ] && { echo "ALL CHECKS PASSED"; exit 0; } || { echo "$FAILS CHECK(S) FAILED"; exit 1; }
