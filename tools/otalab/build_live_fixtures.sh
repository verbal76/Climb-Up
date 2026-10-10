#!/usr/bin/env bash
# Builds the baseline (v1, goes inside the APK) and the four releases the live-update test publishes one after the other (see live_ota_e2e.sh):
#   r1 changed executable code (marker entry class) + a changed game file (inverted studio splash, lab fixture)   r2 signed but its entry class does not exist (cannot load)
#   r3 signed with a key the app does not trust                                                                      r4 the plain game again (no override)
# usage: build_live_fixtures.sh JAR MARKED_JAR KEY_PEM PASSPHRASE_ENV_NAME_OR_EMPTY D8 ANDROID_JAR OUT_DIR APK_BASELINE_DIR V1 V2 V3 V4     (Pillow must be installed)
set -euo pipefail
J=$1; M=$2; KEY=$3; PASSENV=$4; D8=$5; LIB=$6; OUT=$7; BASE=$8; V1=$9; V2=${10}; V3=${11}; V4=${12}
T=$(dirname "$0")/make_module.py; APP=com.hotatticgames.climbup.otaexp; ENTRY=com.hotatticgames.climbup.module.ClimbModule
P=(); [ -n "$PASSENV" ] && P=(--passphrase-env "$PASSENV")
mk() { local jar=$1 key=$2; shift 2; python3 "$T" --jar "$jar" --key "$key" --d8 "$D8" --lib "$LIB" --no-props --app $APP --entry $ENTRY --save-schema 6 --save-min 6 --ruleset 1 "$@"; }
mkdir -p "$OUT"
mk "$J" "$KEY" --out "$BASE" --version 1 "${P[@]}"
OV=$(mktemp -d); mkdir -p "$OV/branding"
python3 - "$OV/branding/studio_splash.png" "$(dirname "$0")/../../assets/branding/studio_splash.png" <<'PYEOF'
import sys
from PIL import Image, ImageOps
im = Image.open(sys.argv[2]).convert("RGBA"); r, g, b, al = im.split()
Image.merge("RGBA", (*ImageOps.invert(Image.merge("RGB", (r, g, b))).split(), al)).save(sys.argv[1])
PYEOF
mk "$M" "$KEY" --out "$OUT/r1" --version "$V1" --entry com.hotatticgames.climbup.module.ClimbModuleMarked --assets-dir "$OV" "${P[@]}"
mk "$J" "$KEY" --out "$OUT/r2" --version "$V2" --entry com.hotatticgames.climbup.module.Missing "${P[@]}"
OTHER=$(mktemp -d)/untrusted.pem; openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$OTHER" 2>/dev/null
mk "$J" "$OTHER" --out "$OUT/r3" --version "$V3"
mk "$J" "$KEY" --out "$OUT/r4" --version "$V4" "${P[@]}"
echo "$V1" > "$OUT/v1"; echo "$V2" > "$OUT/v2"; echo "$V3" > "$OUT/v3"; echo "$V4" > "$OUT/v4"
ls "$OUT"
