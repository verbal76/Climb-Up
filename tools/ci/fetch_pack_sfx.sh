#!/usr/bin/env bash
# Converts the owner-licensed "Minifantasy - Forgotten Plains Audio Pack" (Leohpaz) into the few small OGG files the game uses.
# The pack may not be redistributed, so the converted files are git-ignored (assets/audio/pack_mf/) and exist only in built artifacts.
#
#   tools/ci/fetch_pack_sfx.sh <pack.zip> [out_dir]        convert from a local zip
#   tools/ci/fetch_pack_sfx.sh --download [out_dir]        CI: fetch the pack zip, check it, convert
#   tools/ci/fetch_pack_sfx.sh --check                     CI: fetch the pack zip and check it only (no conversion, writes nothing)
#
# Source (owner, Build 78): the asset Minifantasy_Forgotten_Plains_SFX.zip on the owner's release tagged "Sfx" in THIS repository, through the
# GitHub REST API with the workflow's own GITHUB_TOKEN (env GH_TOKEN; REPO or GITHUB_REPOSITORY names the repository). No personal token.
# A published release needs only "contents: read"; a DRAFT release is visible only to a token that can write ("contents: write").
# Every problem fails the run with a clear ::error:: (missing asset, no access, failed download, wrong size or SHA-256, unzip or conversion
# failure). Nothing falls back to another pack. (The game itself still runs with its built-in sounds if a build has no pack clips.)
set -uo pipefail
EXPECT_SHA=d94e9fd53240fb2787dee72fcfdc2ee23f51113beb489d59c12e586ba711692a
ASSET=Minifantasy_Forgotten_Plains_SFX.zip
TAG=Sfx
# test hook (.github/workflows/sfx-pack-access.yml): --check another tag / asset / hash, to prove draft access with a throwaway release
if [ -n "${SFX_TEST_TAG:-}" ]; then TAG="$SFX_TEST_TAG"; ASSET="$SFX_TEST_ASSET"; EXPECT_SHA="$SFX_TEST_SHA"; fi
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODE="${1:-}"
fail() { echo "::error::pack sfx: $*"; exit 1; }

[ -n "$MODE" ] || fail "usage: fetch_pack_sfx.sh <pack.zip>|--download [out_dir]|--check"
OUT="${2:-$ROOT/assets/audio/pack_mf}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
if [ "$MODE" != "--check" ]; then command -v ffmpeg >/dev/null 2>&1 || fail "ffmpeg not found"; fi
PY=""; for c in python3 python; do if "$c" -c pass >/dev/null 2>&1; then PY="$c"; break; fi; done
[ -n "$PY" ] || fail "python not found"

if [ "$MODE" = "--download" ] || [ "$MODE" = "--check" ]; then
  SRC="${REPO:-${GITHUB_REPOSITORY:-verbal76/Climb-Up}}"
  ZIP="$WORK/pack.zip"
  # find the asset on the "Sfx" release (the list endpoint also shows a draft to a token that may see it), then download it by id
  "$PY" -I - "$SRC" "$ASSET" "$ZIP" "$TAG" <<'PYEOF' || exit 1
import json, os, sys, urllib.request, urllib.error
repo, name, out, tag = sys.argv[1:5]
tok = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN") or ""
def req(url, accept):
    r = urllib.request.Request(url, headers={"Accept": accept, "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "upwardly-ci"})
    if tok: r.add_unredirected_header("Authorization", "Bearer " + tok)   # never forwarded to the storage host the download redirects to
    return urllib.request.urlopen(r, timeout=120)
def die(msg): print("::error::pack sfx: " + msg); sys.exit(1)
asset = None
try:
    for page in range(1, 11):
        rels = json.load(req("https://api.github.com/repos/%s/releases?per_page=100&page=%d" % (repo, page), "application/vnd.github+json"))
        for rel in rels:
            if rel.get("tag_name") == tag:
                asset = next((a for a in rel.get("assets", []) if a.get("name") == name), None)
                if asset is None: die("release '%s' in %s has no asset %s" % (tag, repo, name))
                print("pack sfx: release '%s' (%s) asset %s, %d bytes" % (tag, "draft" if rel.get("draft") else "published", name, asset["size"]))
                break
        if asset or len(rels) < 100: break
except urllib.error.HTTPError as e:
    die("listing the releases of %s failed: HTTP %d (%s)" % (repo, e.code, "the token cannot read this repository" if e.code in (401, 403, 404) else e.reason))
if asset is None: die("no release tagged '%s' in %s that this token can see (a draft release needs contents: write)" % (tag, repo))
try:
    with req("https://api.github.com/repos/%s/releases/assets/%d" % (repo, asset["id"]), "application/octet-stream") as r, open(out, "wb") as f:
        while True:
            b = r.read(1 << 20)
            if not b: break
            f.write(b)
except (urllib.error.URLError, OSError) as e:
    die("download of %s failed: %s" % (name, e))
if os.path.getsize(out) != asset["size"]: die("download of %s is %d bytes, the release says %d" % (name, os.path.getsize(out), asset["size"]))
PYEOF
else
  ZIP="$MODE"; [ -f "$ZIP" ] || fail "zip not found: $ZIP"
fi

GOT="$("$PY" -I -c 'import hashlib,sys;print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$ZIP" 2>/dev/null)"
[ "$GOT" = "$EXPECT_SHA" ] || fail "SHA-256 mismatch: got $GOT, expected $EXPECT_SHA (corrupted or a different file)"
if [ "$MODE" = "--check" ]; then echo "pack sfx: OK, downloaded from $SRC, SHA-256 $GOT"; exit 0; fi
"$PY" -I -m zipfile -e "$ZIP" "$WORK/x" >/dev/null 2>&1 || fail "unzip failed"

mkdir -p "$OUT" || fail "cannot create $OUT"
N=0
# mono 22.05 kHz OGG, silence trimmed, normalised, length capped.  conv <out-name> <source-file-stem>
conv() {  # conv <out-name> <src-stem> <max-seconds> <loop:0|1>
  local src; src="$(ls "$WORK"/x/"$2".wav 2>/dev/null | head -n1)"
  [ -n "$src" ] || { echo "::error::pack sfx: the pack has no $2.wav"; return; }
  if [ "$4" = "0" ]; then
    ffmpeg -v error -y -i "$src" -ac 1 -ar 22050 -af "silenceremove=start_periods=1:start_threshold=-45dB,loudnorm=I=-20:TP=-2:LRA=7,atrim=0:$3" -c:a libvorbis -q:a 2 "$OUT/$1.ogg" && N=$((N+1))
  else
    ffmpeg -v error -y -i "$src" -ac 1 -ar 22050 -af "loudnorm=I=-26:TP=-3:LRA=7" -c:a libvorbis -q:a 1 "$OUT/$1.ogg" && N=$((N+1))
  fi
}
for i in 1 2 3; do
  conv "step_grass_$i" "04_step_grass_$i" 0.5 0
  conv "step_dirt_$i"  "05_step_dirt_$i"  0.5 0
  conv "step_stone_$i" "06_step_stone_$i" 0.5 0
done
for i in 1 2; do
  conv "land_grass_$i" "07_landing_on_grass_$i" 0.6 0
  conv "land_stone_$i" "08_landing_on_stone_$i" 0.9 0
  conv "hit_wood_$i"   "15_Hit_on_wood_$i"      0.9 0
  conv "hit_brick_$i"  "16_Hit_on_brick_$i"     0.6 0
done
# quiet ambience loops (whole file, loop point is the pack's own)
conv amb_birds    "13_Birds_loop"      0 1
conv amb_wind     "14_Wind_loop"       0 1
conv amb_crickets "11_Crickets_2_loop" 0 1
echo "pack sfx: wrote $N files to $OUT"
[ "$N" -ge 20 ] || fail "only $N of 20 clips converted"
exit 0
