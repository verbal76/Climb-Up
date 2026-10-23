#!/usr/bin/env bash
# Offline test of the release gate in publish_release.sh: a fake `gh` keeps releases in a JSON file, so nothing touches GitHub.
#   bash tools/ci/test_publish_release.sh        prints PASS/FAIL per case, exits non-zero on any failure
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin"
cat > "$T/bin/gh" <<'PY'
#!/usr/bin/env python3
# fake gh api: releases and assets live in $FAKE_STATE; -q runs real jq -r on the response
import hashlib, json, os, re, subprocess, sys
a = sys.argv[1:]
assert a[0] == "api"; a = a[1:]
method, q, inp, url = "GET", None, None, None
i = 0
while i < len(a):
    x = a[i]
    if x == "-X": method = a[i + 1]; i += 2
    elif x == "-q": q = a[i + 1]; i += 2
    elif x == "--input": inp = a[i + 1]; i += 2
    elif x == "-H": i += 2
    elif x == "--paginate": i += 1
    else: url = x; i += 1
st = json.load(open(os.environ["FAKE_STATE"]))
def save(): json.dump(st, open(os.environ["FAKE_STATE"], "w"))
def body(): return open(inp, "rb").read() if inp != "-" else sys.stdin.buffer.read()
def rel(i): return next(r for r in st["releases"] if r["id"] == i)
path = re.sub(r"^https://uploads.github.com/", "", url)
path, _, qs = path.partition("?")
p = path.split("/")[3:]          # after repos/<owner>/<repo>
out = None
if p == ["releases"] and method == "GET": out = st["releases"]
elif p == ["releases"] and method == "POST":
    d = json.loads(body()); st["next"] += 1
    r = {"id": st["next"], "assets": [], "draft": True, "body": "", **d}; st["releases"].append(r); out = r
elif len(p) == 2 and p[0] == "releases" and method == "GET": out = rel(int(p[1]))
elif len(p) == 2 and p[0] == "releases" and method == "PATCH":
    r = rel(int(p[1])); r.update(json.loads(body()))
    if os.environ.get("FAKE_REFUSE_PUBLISH") and r.get("draft") is False: r["draft"] = True; save(); print("HTTP 403", file=sys.stderr); sys.exit(1)
    if r.get("draft") is False: r["html_url"] = "https://example/" + r["tag_name"]
    out = r
elif len(p) == 3 and p[2] == "assets" and method == "GET": out = rel(int(p[1]))["assets"]
elif len(p) == 3 and p[2] == "assets" and method == "POST":
    name = re.search(r"name=([^&]*)", qs).group(1).replace("%20", ".").replace(" ", ".")
    data = body(); st["next"] += 1
    dig = "sha256:" + hashlib.sha256(data + (b"x" if os.environ.get("FAKE_CORRUPT") else b"")).hexdigest()
    asset = {"id": st["next"], "name": name, "digest": dig}; rel(int(p[1]))["assets"].append(asset); out = asset
elif len(p) == 3 and p[:2] == ["releases", "assets"] and method == "GET":
    out = next(x for r in st["releases"] for x in r["assets"] if x["id"] == int(p[2]))
elif len(p) == 3 and p[:2] == ["releases", "assets"] and method == "DELETE":
    for r in st["releases"]: r["assets"] = [x for x in r["assets"] if x["id"] != int(p[2])]
    out = {}
else: print("fake gh: unhandled", method, url, file=sys.stderr); sys.exit(2)
save()
s = json.dumps(out)
if q: s = subprocess.run(["jq", "-r", q], input=s, capture_output=True, text=True).stdout.rstrip("\n")
print(s)
PY
chmod +x "$T/bin/gh"
export PATH="$T/bin:$PATH" REPO=o/r GH_TOKEN=x FAKE_STATE="$T/state.json"
FAIL=0
cd "$T"
printf 'apk' > "upwardly-1.3.0-build9.apk"; printf 'exe' > "Upwardly Build 9 EXE.zip"; printf 'lin' > "Upwardly Build 9 Linux.tar.gz"
printf 'Android notes\n' > a.md; printf 'Desktop notes\n' > d.md
reset() { echo '{"next":100,"releases":[]}' > "$FAKE_STATE"; }
pub() { GITHUB_SHA="$1" bash "$HERE/publish_release.sh" v1.3.0-build9 9 "$2" "${@:3}" > "$T/log" 2>&1; }
state() { jq -r "$1" "$FAKE_STATE"; }
check() { if [ "$2" = "$3" ]; then echo "PASS $1"; else echo "FAIL $1: got '$2', want '$3'"; sed 's/^/    /' "$T/log"; FAIL=1; fi; }

reset; pub aaa a.md upwardly-1.3.0-build9.apk; check "android first: run passes" $? 0
check "android first: still a draft" "$(state '.releases[0].draft')" true
pub aaa d.md "Upwardly Build 9 EXE.zip" "Upwardly Build 9 Linux.tar.gz"; check "desktop second: run passes" $? 0
check "desktop second: published" "$(state '.releases[0].draft')" false
check "published: 5 files" "$(state '.releases[0].assets | length')" 5
check "checksums cover 3 packages" "$(grep -c . checksums.sha256)" 3
check "manifest source commit" "$(jq -r .source_sha build-manifest.json)" aaa
check "manifest android sha" "$(jq -r '.artifacts[0].sha256' build-manifest.json)" "$(printf apk | sha256sum | cut -d' ' -f1)"
check "notes carry both" "$(state '.releases[0].body' | grep -c 'notes')" 2

reset; pub aaa d.md "Upwardly Build 9 EXE.zip" "Upwardly Build 9 Linux.tar.gz"; pub aaa a.md upwardly-1.3.0-build9.apk
check "desktop first, android second: published" "$(state '.releases[0].draft')" false

reset; pub aaa d.md "Upwardly Build 9 EXE.zip" "Upwardly Build 9 Linux.tar.gz"
check "desktop alone (android failed): draft" "$(state '.releases[0].draft')" true
check "desktop alone: no checksums yet" "$(state '[.releases[0].assets[].name] | index("checksums.sha256")')" null

reset; pub old a.md upwardly-1.3.0-build9.apk; pub new d.md "Upwardly Build 9 EXE.zip" "Upwardly Build 9 Linux.tar.gz"
check "mixed commits: not published" "$(state '.releases[0].draft')" true
check "mixed commits: stale APK removed" "$(state '[.releases[0].assets[].name] | map(select(endswith(".apk"))) | length')" 0
check "mixed commits: re-pointed" "$(state '.releases[0].target_commitish')" new
pub new a.md upwardly-1.3.0-build9.apk
check "re-run android on new commit: published" "$(state '.releases[0].draft')" false

reset; pub aaa a.md upwardly-1.3.0-build9.apk; pub aaa d.md "Upwardly Build 9 EXE.zip" "Upwardly Build 9 Linux.tar.gz"
pub aaa a.md upwardly-1.3.0-build9.apk; check "re-run on a public release: refused" $? 1
check "public release untouched" "$(state '.releases[0].assets | length')" 5

reset; FAKE_CORRUPT=1 pub aaa a.md upwardly-1.3.0-build9.apk; check "corrupt upload: run fails" $? 1

reset; pub aaa a.md upwardly-1.3.0-build9.apk; FAKE_REFUSE_PUBLISH=1 pub aaa d.md "Upwardly Build 9 EXE.zip" "Upwardly Build 9 Linux.tar.gz"
check "publish refused: run fails" $? 1
check "publish refused: stays draft" "$(state '.releases[0].draft')" true

[ "$FAIL" = 0 ] && echo "PUBLISH_GATE_TEST PASS" || { echo "PUBLISH_GATE_TEST FAIL"; exit 1; }
