#!/usr/bin/env bash
# Builds the baseline module (goes INSIDE the lab APK) and every update scenario the emulator test serves. All signed with the throw-away lab key.
# usage: build_fixtures.sh SYNTHETIC_JAR OUT_SERVE_DIR BASELINE_ASSET_DIR KEY_PEM OTHER_KEY_PEM D8 ANDROID_JAR
set -euo pipefail
J=$1; SERVE=$2; BASE=$3; KEY=$4; OTHER=$5; D8=$6; LIB=$7
T=$(dirname "$0")/make_module.py
mk() { python3 "$T" --jar "$J" --key "$KEY" --d8 "$D8" --lib "$LIB" "$@"; }
mk --out "$BASE" --version 1                                                   # baseline shipped in the APK
mk --out "$SERVE/v2" --version 2
mk --out "$SERVE/t_dex" --version 3 --tamper dex
mk --out "$SERVE/t_manifest" --version 3 --tamper manifest
mk --out "$SERVE/t_signature" --version 3 --tamper signature
python3 "$T" --jar "$J" --key "$OTHER" --d8 "$D8" --lib "$LIB" --out "$SERVE/wrong_key" --version 3        # signed by a key the host does not pin
mk --out "$SERVE/wrong_app" --version 3 --app com.someone.else
mk --out "$SERVE/wrong_iface" --version 3 --interface 2
mk --out "$SERVE/wrong_channel" --version 3 --channel release
mk --out "$SERVE/wrong_host" --version 3 --host-min 2 --host-max 2
mk --out "$SERVE/old_v1" --version 1
mk --out "$SERVE/crash_v4" --version 4 --mode crashRender
mk --out "$SERVE/throw_v5" --version 5 --mode throwCreate
