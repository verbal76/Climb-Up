#!/usr/bin/env bash
# Builds the REAL Climb Up game module bundles (unchanged core + entry class, via D8) for the module-loading host and the update scenarios the emulator test serves.
# usage: build_climb_fixtures.sh CLIMB_MODULE_JAR OUT_SERVE_DIR BASELINE_ASSET_DIR KEY_PEM OTHER_KEY_PEM D8 ANDROID_JAR
set -euo pipefail
J=$1; SERVE=$2; BASE=$3; KEY=$4; OTHER=$5; D8=$6; LIB=$7
T=$(dirname "$0")/make_module.py; APP=com.hotatticgames.climbup.otaexp
mk() { python3 "$T" --jar "$J" --key "$KEY" --d8 "$D8" --lib "$LIB" --no-props --app $APP --entry com.hotatticgames.climbup.module.ClimbModule --save-schema 6 --save-min 6 "$@"; }
mk --out "$BASE" --version 1 --ruleset 1                                   # baseline inside the APK
mk --out "$SERVE/c2_same_world" --version 2 --ruleset 1                    # a content/code release that does not change the world: applies mid-climb
mk --out "$SERVE/c3_new_generator" --version 3 --ruleset 2                 # would change the unseen part of a climb: must wait
mk --out "$SERVE/c4_tampered" --version 4 --ruleset 1 --tamper dex
mk --out "$SERVE/c5_no_entry" --version 5 --ruleset 1 --entry com.hotatticgames.climbup.module.Missing
