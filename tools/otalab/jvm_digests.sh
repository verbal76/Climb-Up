#!/usr/bin/env bash
# Runs the game module's deterministic self-test on the JVM for a broad set of requests and writes "request -> result" lines. The emulator scenarios compare the device's results with these
# (cross-runtime: HotSpot here, ART there) and compare the module-loaded game with the packaged game exactly (same runtime). Owner: OTA Engineer 1.
# usage: jvm_digests.sh MODULE_JAR GDX_JAR TUNING_JSON OUT_FILE [PARALLEL]
set -eu
JAR=$1; GDX=$2; TUN=$3; OUT=$4; P=${5:-3}
REQS=""
for s in 12 1 2 3 4 5; do REQS="$REQS digest:$s:7200"; done                       # the autopilot climbs (plans around every hazard)
for s in 1 2 3 4 5 6 7 8; do REQS="$REQS chaos:$s:6000"; done                     # a flailing player: falls, respawns, checkpoints, crumbling platforms
for s in 1 2 3 4 5 6; do REQS="$REQS mixed:$s:20000"; done                       # autopilot to height, then flailing bursts: hazards, crabs, bees
echo $REQS | tr ' ' '\n' | xargs -P "$P" -I{} sh -c "java -Dclimb.tuning='$TUN' -cp '$JAR:$GDX' com.hotatticgames.climbup.module.SelfTest {} 2>/dev/null | grep -- ' -> '" | sort > "$OUT"
echo "jvm digests: $(wc -l < "$OUT") requests"; sed 's/^\([^ ]*\) -> \([0-9a-f]\{12\}\)[0-9a-f]* \(.*\)$/\1  \2... \3/' "$OUT" | cut -c1-230
