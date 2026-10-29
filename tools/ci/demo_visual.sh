#!/usr/bin/env bash
# Demo (docs/DEMO.md): runs the PACKAGED Linux demo under a virtual display and saves screenshots of every demo screen for review: the menu, a stage
# title card, the first seconds of a stage (Dusk Spire at castle 2), the best-times panel, settings, and the promotional screen, at desktop
# (1280x720), Steam Deck (1280x800) and an ultrawide phone shape (2400x1080). Only presses what a player could press (scripted taps on buttons).
#   tools/ci/demo_visual.sh <app-image dir> <out dir>
set -euo pipefail
APP="$1"; OUT="$2"; mkdir -p "$OUT"
BIN="$APP/bin/$(basename "$APP")"
run() {   # name, width, height, taps, shots, seed file (or "")
  local name="$1" w="$2" h="$3" taps="$4" n="$5" seed="$6" data; data="$(mktemp -d)"
  mkdir -p "$data/HotAtticGames/Upwardly Demo"
  [ -n "$seed" ] && cp "$seed" "$data/HotAtticGames/Upwardly Demo/demo.json"
  echo "== $name ${w}x${h}"
  XDG_DATA_HOME="$data" JAVA_TOOL_OPTIONS="-Dclimb.shots=$OUT/$name -Dclimb.shotEvery=1.5 -Dclimb.shotCount=$n -Dclimb.w=$w -Dclimb.h=$h -Dclimb.taps=$taps -Dclimb.autopilot=true -Dclimb.startHeight=4000" \
    timeout 240 xvfb-run -a -s "-screen 0 ${w}x${h}x24" env -u JAVA_HOME PATH=/usr/bin:/bin "$BIN" > "$OUT/$name.log" 2>&1 || { echo "::warning::$name exited non-zero"; tail -30 "$OUT/$name.log"; }
  ls "$OUT/$name" 2>/dev/null | head -40 || echo "no shots for $name"
  # the demo must have ignored the developer options passed above (autopilot, a start height): the log says what it honoured
  grep -E "Exception|FATAL" "$OUT/$name.log" | head -5 || true
}
# PLAY sits at (W/2, ~332) and SETTINGS / BEST TIMES under it at 720 units high (DemoTitleScreen); taps are in the 1280-wide virtual space
run sequence 1280 720 "640,332@4.5" 14 ""
run deck     1280 800 "640,374@4.5" 12 ""
FIN=$(mktemp); echo '{"format":1,"stage":3,"cleared":[true,true,true],"best":[{"ticks":0,"seed":0,"fingerprint":""},{"ticks":0,"seed":0,"fingerprint":""},{"ticks":0,"seed":0,"fingerprint":""}]}' > "$FIN"
run promo      1280 720 "640,332@4.5" 6 "$FIN"
run promodeck  1280 800 "640,374@4.5" 6 "$FIN"
run promowide  2400 1080 "800,332@4.5" 6 "$FIN"
run times      1280 720 "774,232@4.5" 5 ""
run settings   1280 720 "506,232@4.5" 5 ""
find "$OUT" -name '*.png' | wc -l
