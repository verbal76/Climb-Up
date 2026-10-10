#!/usr/bin/env bash
# Builds the signed release that serves a changed GAME FILE for the asset scenario. Owner: OTA Engineer 2.
# usage: build_asset_fixtures.sh CLIMB_MODULE_JAR OUT_SERVE_DIR KEY_PEM D8 ANDROID_JAR
set -euo pipefail
J=$1; SERVE=$2; KEY=$3; D8=$4; LIB=$5
T=$(dirname "$0")/make_module.py; APP=com.hotatticgames.climbup.otaexp
mk() { python3 "$T" --jar "$J" --key "$KEY" --d8 "$D8" --lib "$LIB" --no-props --app $APP --entry com.hotatticgames.climbup.module.ClimbModule --save-schema 6 --save-min 6 "$@"; }
# a release that serves a changed game file: LAB-ONLY fixture, the owner's studio splash with its colours inverted (the real logo is never altered or shipped)
OV=$(mktemp -d); mkdir -p "$OV/branding"
python3 - "$OV/branding/studio_splash.png" "$(dirname "$0")/../../assets/branding/studio_splash.png" <<'P'
import sys
from PIL import Image, ImageOps
im = Image.open(sys.argv[2]).convert("RGBA"); r, g, b, al = im.split()
Image.merge("RGBA", (*ImageOps.invert(Image.merge("RGB", (r, g, b))).split(), al)).save(sys.argv[1])
P
mk --out "$SERVE/c6_assets" --version 6 --ruleset 1 --assets-dir "$OV"
