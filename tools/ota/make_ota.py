#!/usr/bin/env python3
"""Builds the signed OTA assets from assets/data/tuning.json:  build/ota/payload.zip, manifest.json, manifest.sig
The manifest is signed (ECDSA P-256 / SHA-256, DER, base64) with the private key held in the OTA_SIGNING_PRIVATE_KEY / OTA_SIGNING_KEY_PASSPHRASE secrets;
the app verifies it against the public key pinned in the APK. The key never touches the command line or the output.

usage: tools/ota/make_ota.py --version N [--out build/ota] [--min-version-code N] [--sign] [--public-key-file F]
  --sign             sign with the key in the environment (OTA_SIGNING_PRIVATE_KEY = encrypted PKCS#8 PEM, OTA_SIGNING_KEY_PASSPHRASE) and verify the result with openssl
  --public-key-file  base64 X.509 SubjectPublicKeyInfo to name in the manifest and check the signature against (default: assets/ota/ota_public_key.b64)"""
import argparse, base64, hashlib, json, os, subprocess, sys, tempfile, zipfile

BASE = "https://github.com/verbal76/Climb-Up/releases/download/ota/"
RUNTIME = 2        # keep equal to OtaConfig.RUNTIME in the app
SCHEMA = 2         # keep equal to OtaConfig.SCHEMA
CHANNEL = "release"
FROZEN = ["radius", "zoneHeight", "zoneCount", "castleSpacing", "finishCastle", "gemSpacing", "courseHeight", "rampHeight", "chunkHeight", "spiralPitch", "restEvery", "checkpointEveryRests"]

ap = argparse.ArgumentParser()
ap.add_argument("--version", type=int, required=True, help="content version; must be higher than every version already published (the build ships version 1)")
ap.add_argument("--out", default="build/ota")
ap.add_argument("--min-version-code", type=int, default=0)
ap.add_argument("--sign", action="store_true")
ap.add_argument("--public-key-file", default=None)
ap.add_argument("--base", default=BASE, help="payload base URL (tests use a local server)")
a = ap.parse_args()
root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
pubfile = a.public_key_file or os.path.join(root, "assets", "ota", "ota_public_key.b64")
pub_der = base64.b64decode(open(pubfile).read().strip())
key_id = hashlib.sha256(pub_der).hexdigest()[:16]

src = os.path.join(root, "assets", "data", "tuning.json")
body = open(src, "rb").read()
t = json.loads(body)                               # must at least be valid JSON; the app validates the numbers again
for f in FROZEN:
    if f not in t: sys.exit("tuning.json lacks layout constant " + f)
out = os.path.join(root, a.out) if not os.path.isabs(a.out) else a.out
os.makedirs(out, exist_ok=True)
zp = os.path.join(out, "payload.zip")
with zipfile.ZipFile(zp, "w", zipfile.ZIP_DEFLATED) as z:
    zi = zipfile.ZipInfo("tuning.json", date_time=(2026, 1, 1, 0, 0, 0)); zi.compress_type = zipfile.ZIP_DEFLATED
    z.writestr(zi, body)
data = open(zp, "rb").read()
manifest = {"schema": SCHEMA, "channel": CHANNEL, "keyId": key_id, "contentVersion": a.version, "runtime": RUNTIME, "minVersionCode": a.min_version_code,
            "payloadUrl": a.base + "payload.zip", "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
mpath = os.path.join(out, "manifest.json")
open(mpath, "w", newline="\n").write(json.dumps(manifest, indent=2) + "\n")
spath = os.path.join(out, "manifest.sig")
if os.path.exists(spath): os.remove(spath)

if a.sign:
    pw, pem = os.environ.get("OTA_SIGNING_KEY_PASSPHRASE"), os.environ.get("OTA_SIGNING_PRIVATE_KEY")
    if not pw or not pem: sys.exit("--sign needs OTA_SIGNING_PRIVATE_KEY and OTA_SIGNING_KEY_PASSPHRASE in the environment")
    os.umask(0o077)
    with tempfile.TemporaryDirectory() as tmp:
        kf = os.path.join(tmp, "k.pem"); open(kf, "w").write(pem.strip() + "\n")
        env = dict(os.environ, OTA_PASS=pw)
        r = subprocess.run(["openssl", "dgst", "-sha256", "-sign", kf, "-passin", "env:OTA_PASS", "-out", os.path.join(tmp, "sig.der"), mpath], env=env, capture_output=True)
        if r.returncode: sys.exit("signing failed (wrong passphrase or key?)")
        der = open(os.path.join(tmp, "sig.der"), "rb").read()
        # verify with the PUBLIC key before anything is published
        pk = os.path.join(tmp, "pub.der"); open(pk, "wb").write(pub_der)
        r = subprocess.run(["openssl", "dgst", "-sha256", "-verify", pk, "-keyform", "DER", "-signature", os.path.join(tmp, "sig.der"), mpath], capture_output=True)
        if r.returncode: sys.exit("the signature does not verify against the public key (is this the right key pair?)")
    open(spath, "w", newline="\n").write(base64.b64encode(der).decode() + "\n")
    print("signed; keyId " + key_id)
print(json.dumps(manifest, indent=2))
