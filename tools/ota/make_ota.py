#!/usr/bin/env python3
"""Builds the family-test OTA assets from assets/data/tuning.json:  build/ota/payload.zip  +  build/ota/manifest.json
FAMILY PLAYTEST ONLY: the manifest carries a SHA-256, not a signature (see docs/OTA.md).
usage: tools/ota/make_ota.py --version N [--out build/ota] [--min-version-code N]"""
import argparse, hashlib, json, os, zipfile

BASE = "https://github.com/verbal76/Climb-Up/releases/download/ota-dev/"
RUNTIME = 1   # keep equal to OtaConfig.RUNTIME in the app
ap = argparse.ArgumentParser()
ap.add_argument("--version", type=int, required=True, help="content version; must be higher than every version already published")
ap.add_argument("--out", default="build/ota")
ap.add_argument("--min-version-code", type=int, default=0)
a = ap.parse_args()
root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
src = os.path.join(root, "assets", "data", "tuning.json")
body = open(src, "rb").read()
json.loads(body)                                   # must at least be valid JSON; the app validates the numbers again
out = os.path.join(root, a.out) if not os.path.isabs(a.out) else a.out
os.makedirs(out, exist_ok=True)
zp = os.path.join(out, "payload.zip")
with zipfile.ZipFile(zp, "w", zipfile.ZIP_DEFLATED) as z:
    zi = zipfile.ZipInfo("tuning.json", date_time=(2026, 1, 1, 0, 0, 0)); zi.compress_type = zipfile.ZIP_DEFLATED
    z.writestr(zi, body)
data = open(zp, "rb").read()
manifest = {"schema": 1, "channel": "dev", "contentVersion": a.version, "runtime": RUNTIME, "minVersionCode": a.min_version_code,
            "payloadUrl": BASE + "payload.zip", "sha256": hashlib.sha256(data).hexdigest(), "size": len(data),
            "note": "TEST-ONLY family playtest update (checksum only, not authenticated)"}
open(os.path.join(out, "manifest.json"), "w").write(json.dumps(manifest, indent=2) + "\n")
print(json.dumps(manifest, indent=2))
