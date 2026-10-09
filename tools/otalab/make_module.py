#!/usr/bin/env python3
"""Builds one signed game-module bundle for the host (docs/ota-full/INTERFACE.md): module.dex (or module.jar with --jar-only), assets, manifest.json, manifest.sig.
The manifest is signed (ECDSA P-256 / SHA-256, DER, base64) with a private key PEM given by --key (never printed, never copied); the matching public key id is written into the manifest.
usage: make_module.py --jar modsynthetic.jar --out DIR --version N --key key.pem [--d8 PATH/d8 --lib android.jar | --jar-only] [options]"""
import argparse, base64, hashlib, json, os, re, subprocess, sys, tempfile, zipfile

ap = argparse.ArgumentParser()
ap.add_argument("--jar", required=True); ap.add_argument("--out", required=True); ap.add_argument("--version", type=int, required=True)
ap.add_argument("--key", required=True, help="private key PEM (PKCS#8, P-256)"); ap.add_argument("--passphrase-env", default="")
ap.add_argument("--d8"); ap.add_argument("--lib"); ap.add_argument("--jar-only", action="store_true")
ap.add_argument("--app", default="com.hotatticgames.climbup.otalab"); ap.add_argument("--channel", default="internal")
ap.add_argument("--entry", default="com.hotatticgames.climbup.synthetic.SyntheticModule")
ap.add_argument("--mode", default="ok"); ap.add_argument("--hello", default=None)
ap.add_argument("--interface", type=int, default=1); ap.add_argument("--host-min", type=int, default=1); ap.add_argument("--host-max", type=int, default=1)
ap.add_argument("--save-schema", type=int, default=6); ap.add_argument("--save-min", type=int, default=6); ap.add_argument("--ruleset", type=int, default=1)
ap.add_argument("--revoke-floor", type=int, default=0); ap.add_argument("--content", type=int, default=1)
ap.add_argument("--source-sha", default="0123456789abcdef0123456789abcdef01234567")
ap.add_argument("--tamper", choices=["none", "dex", "manifest", "signature"], default="none", help="test fixtures only: produce a deliberately broken bundle")
a = ap.parse_args()

def die(m): sys.exit("make_module: " + m)
os.makedirs(a.out, exist_ok=True)
for f in os.listdir(a.out): os.remove(os.path.join(a.out, f))

with tempfile.TemporaryDirectory() as tmp:
    # 1. the module jar with this release's behaviour switches (synthetic module only; the real game module has no such file)
    patched = os.path.join(tmp, "module-in.jar")
    with zipfile.ZipFile(a.jar) as zin, zipfile.ZipFile(patched, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            if item.filename == "synthetic.properties": continue
            n = item.filename
            if n.startswith("com/badlogic/") or n.startswith("com/hotatticgames/climbup/spi/"): die("module jar carries host-owned classes: " + n)
            zout.writestr(item, zin.read(n))
        zout.writestr("synthetic.properties", "version=%d\nmode=%s\nhue=%.2f\n" % (a.version, a.mode, (0.37 * a.version) % 1.0))
    # 2. code file
    if a.jar_only:
        code_name = "module.jar"; code = open(patched, "rb").read()
    else:
        if not a.d8 or not a.lib: die("--d8 and --lib are required unless --jar-only")
        outd = os.path.join(tmp, "dex")
        r = subprocess.run([a.d8, "--release", "--min-api", "26", "--lib", a.lib, "--output", outd, patched], capture_output=True, text=True)
        if r.returncode: die("d8 failed: " + r.stderr[-400:])
        code_name = "module.dex"; code = open(os.path.join(outd, "classes.dex"), "rb").read()
    hello = (a.hello or ("hello from v%d" % a.version)).encode()
    files = [(code_name, code), ("hello.txt", hello)]
    # 3. manifest
    pub = subprocess.run(["openssl", "pkey", "-in", a.key, "-pubout", "-outform", "DER"] + (["-passin", "env:" + a.passphrase_env] if a.passphrase_env else []), capture_output=True)
    if pub.returncode: die("cannot read the signing key")
    key_id = hashlib.sha256(pub.stdout).hexdigest()[:16]
    manifest = {"schema": 1, "app": a.app, "channel": a.channel, "keyId": key_id, "moduleName": "1.0.%d" % a.version, "sourceSha": a.source_sha,
                "entry": a.entry, "dex": code_name, "moduleVersion": a.version, "interfaceVersion": a.interface, "hostMin": a.host_min, "hostMax": a.host_max,
                "saveSchema": a.save_schema, "saveMin": a.save_min, "generatorRuleset": a.ruleset, "contentVersion": a.content, "revokeFloor": a.revoke_floor,
                "files": [{"name": n, "sha256": hashlib.sha256(b).hexdigest(), "size": len(b)} for n, b in files]}
    mbytes = json.dumps(manifest, separators=(",", ":"), sort_keys=True).encode()
    mpath = os.path.join(tmp, "manifest.json"); open(mpath, "wb").write(mbytes)
    spath = os.path.join(tmp, "sig.der")
    env = dict(os.environ)
    r = subprocess.run(["openssl", "dgst", "-sha256", "-sign", a.key, "-out", spath, mpath] + (["-passin", "env:" + a.passphrase_env] if a.passphrase_env else []), capture_output=True, env=env)
    if r.returncode: die("signing failed")
    sig = base64.b64encode(open(spath, "rb").read())
    # 4. fixtures for rejection tests
    if a.tamper == "dex": code = code[:len(code) // 2] + bytes([code[len(code) // 2] ^ 1]) + code[len(code) // 2 + 1:]; files[0] = (code_name, code)
    if a.tamper == "manifest": mbytes = mbytes.replace(b'"moduleVersion":%d' % a.version, b'"moduleVersion":%d' % (a.version + 50))
    if a.tamper == "signature": sig = base64.b64encode(os.urandom(70))
    for n, b in files: open(os.path.join(a.out, n), "wb").write(b)
    open(os.path.join(a.out, "manifest.json"), "wb").write(mbytes); open(os.path.join(a.out, "manifest.sig"), "wb").write(sig)
    print("module v%d %s -> %s (keyId %s, %s %d bytes)" % (a.version, a.mode, a.out, key_id, code_name, len(code)))
