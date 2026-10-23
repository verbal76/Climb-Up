#!/usr/bin/env bash
# Converts the owner-licensed "Minifantasy - Forgotten Plains Audio Pack" (Leohpaz) into the few small OGG files the game uses.
# The pack may not be redistributed, so the converted files are git-ignored (assets/audio/pack_mf/) and exist only in built artifacts.
#
#   tools/ci/fetch_pack_sfx.sh <pack.zip> [out_dir]        convert from a local zip (CI and local use run exactly this)
#   tools/ci/fetch_pack_sfx.sh --download [out_dir]        CI: fetch release asset Minifantasy_Forgotten_Plains_SFX.zip of tag "Sfx" via the GitHub API
#                                                          (needs GH_TOKEN and GITHUB_REPOSITORY, or REPO), then convert
# Never fails the build: any problem prints a warning and exits 0; the game plays without these optional sounds.
set -uo pipefail
EXPECT_SHA=d94e9fd53240fb2787dee72fcfdc2ee23f51113beb489d59c12e586ba711692a
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
warn() { echo "::warning::pack sfx: $*"; exit 0; }

MODE="${1:-}"; [ -n "$MODE" ] || { echo "usage: fetch_pack_sfx.sh <pack.zip>|--download [out_dir]"; exit 0; }
OUT="${2:-$ROOT/assets/audio/pack_mf}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
command -v ffmpeg >/dev/null 2>&1 || warn "ffmpeg not found, skipping (the game falls back to its built-in sounds)"
PY=""; for c in python3 python; do if "$c" -c pass >/dev/null 2>&1; then PY="$c"; break; fi; done
[ -n "$PY" ] || warn "python not found, skipping"

if [ "$MODE" = "--download" ]; then
  REPO="${REPO:-${GITHUB_REPOSITORY:-verbal76/Climb-Up}}"
  command -v gh >/dev/null 2>&1 || warn "gh not found, skipping"
  # the release may be a draft, which only the list endpoint shows: look it up by tag name
  AID="$(gh api "repos/$REPO/releases" --paginate --jq '.[] | select(.tag_name=="Sfx") | .assets[] | select(.name=="Minifantasy_Forgotten_Plains_SFX.zip") | .id' 2>/dev/null | head -n1)" || true
  [ -n "${AID:-}" ] || warn "release asset not found (tag Sfx), skipping"
  gh api -H "Accept: application/octet-stream" "repos/$REPO/releases/assets/$AID" > "$WORK/pack.zip" 2>/dev/null || warn "download failed, skipping"
  ZIP="$WORK/pack.zip"
else
  ZIP="$MODE"; [ -f "$ZIP" ] || warn "zip not found: $ZIP"
fi

GOT="$("$PY" -I -c 'import hashlib,sys;print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$ZIP" 2>/dev/null)"
[ "$GOT" = "$EXPECT_SHA" ] || warn "sha256 mismatch ($GOT), skipping"
"$PY" -I -m zipfile -e "$ZIP" "$WORK/x" >/dev/null 2>&1 || warn "unzip failed, skipping"

mkdir -p "$OUT" || warn "cannot create $OUT"
N=0
# mono 22.05 kHz OGG, silence trimmed, normalised, length capped.  conv <out-name> <source-file-stem>
conv() {  # conv <out-name> <src-stem> <max-seconds> <loop:0|1>
  local src; src="$(ls "$WORK"/x/"$2".wav 2>/dev/null | head -n1)"
  [ -n "$src" ] || { echo "pack sfx: missing $2"; return; }
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
exit 0
