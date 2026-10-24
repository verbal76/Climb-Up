#!/usr/bin/env bash
# Owner (2026-10-22): one full GitHub Release per build carries the APK, the Windows EXE and the Linux build together.
#   tools/ci/publish_release.sh <tag> <build number> <notes.md> <file>...
# android.yml and windows.yml both call this with the same tag (v<version>-buildN). Needs GH_TOKEN, REPO and GITHUB_SHA.
#
# Release gate (Build 78, after Build 77's Android run published an APK-only release while the desktop build was failing):
# * Every run adds its files to a DRAFT for the tag (a release candidate: collaborators only, no tag, nothing public). Each upload is checked against
#   GitHub's own SHA-256 of the stored file.
# * A draft from another commit is a stale candidate: its files are removed and it is re-pointed at this commit, so the three packages always share one
#   commit.
# * Only the run that completes the set (one .apk, one EXE .zip, one Linux .tar.gz, all from this commit) publishes: it writes checksums.sha256 and
#   build-manifest.json for all three from the stored files' SHA-256s and then makes the release public. Until then the draft waits and the job passes.
# * A failed platform therefore leaves an unpublished candidate, never a partial public release.
# * A release that is already public is never changed by a build: the run stops with an error.
# Both callers run in the concurrency group "upwardly-release-publish", so two runs never check and publish at the same time.
set -euo pipefail
TAG="$1"; N="$2"; NOTES="$3"; shift 3
TITLE="Upwardly build ${N}"
VER="${TAG#v}"; VER="${VER%-build*}"

sha_of() { sha256sum "$1" | cut -d' ' -f1; }
upload() {  # upload <release id> <file>: replaces a file of the same name, then checks GitHub's digest of what it stored
  local id="$1" f="$2" name a got
  name="$(basename "$f")"
  # GitHub stores spaces in asset names as dots
  a=$(gh api "repos/$REPO/releases/$id/assets?per_page=100" -q ".[] | select(.name == \"${name// /.}\") | .id")
  [ -z "$a" ] || gh api -X DELETE "repos/$REPO/releases/assets/$a"
  a=$(gh api -X POST "https://uploads.github.com/repos/$REPO/releases/$id/assets?name=$(printf '%s' "$name" | sed 's/ /%20/g')" \
        -H "Content-Type: application/octet-stream" --input "$f" -q .id)
  for _ in 1 2 3 4 5; do got=$(gh api "repos/$REPO/releases/assets/$a" -q '.digest // empty'); [ -z "$got" ] || break; sleep 3; done
  [ "$got" = "sha256:$(sha_of "$f")" ] || { echo "::error::upload of $name stored $got, expected sha256:$(sha_of "$f")"; exit 1; }
  echo "uploaded $name ($got)"
}

# the release for this tag, published or draft (a draft is not found by tag name)
# by tag, or (a draft can show as "untagged-..." after an update) by its title; every update below re-sends tag, title and commit so it keeps them
REL=$(gh api --paginate "repos/$REPO/releases?per_page=100" -q ".[] | select(.tag_name == \"$TAG\" or (.draft and .name == \"$TITLE\")) | [.id, .draft, .target_commitish] | @tsv" | head -1)
if [ -n "$REL" ]; then
  ID=$(printf '%s' "$REL" | cut -f1)
  [ "$(printf '%s' "$REL" | cut -f2)" = "true" ] || { echo "::error::release $TAG is already public; a build never changes a published release"; exit 1; }
  if [ "$(printf '%s' "$REL" | cut -f3)" != "$GITHUB_SHA" ]; then
    echo "candidate draft $TAG is from $(printf '%s' "$REL" | cut -f3): removing its files and re-pointing it at $GITHUB_SHA"
    for a in $(gh api "repos/$REPO/releases/$ID/assets?per_page=100" -q '.[].id'); do gh api -X DELETE "repos/$REPO/releases/assets/$a"; done
    jq -n --arg t "$TITLE" --arg tag "$TAG" --arg sha "$GITHUB_SHA" '{name:$t, tag_name:$tag, target_commitish:$sha, body:""}' | gh api -X PATCH "repos/$REPO/releases/$ID" --input - -q .id >/dev/null
  fi
else
  # the id comes straight from the create call: a draft just made can be missing from the release list for a moment (Build 76: an empty id, then a 404)
  ID=$(jq -n --arg t "$TITLE" --arg tag "$TAG" --arg sha "$GITHUB_SHA" '{name:$t, body:"", tag_name:$tag, target_commitish:$sha, draft:true}' \
       | gh api -X POST "repos/$REPO/releases" --input - -q .id)
  [ -n "$ID" ] || { echo "::error::could not create the draft release"; exit 1; }
fi

for f in "$@"; do upload "$ID" "$f"; done
OLD=$(gh api "repos/$REPO/releases/$ID" -q .body)
if [ -z "$OLD" ]; then BODY=$(cat "$NOTES")
elif printf '%s' "$OLD" | grep -qF "$(head -1 "$NOTES")"; then BODY="$OLD"
else BODY=$(printf '%s\n\n---\n\n%s' "$OLD" "$(cat "$NOTES")"); fi
jq -n --arg t "$TITLE" --arg tag "$TAG" --arg sha "$GITHUB_SHA" --arg b "$BODY" '{name:$t, tag_name:$tag, target_commitish:$sha, body:$b}' | gh api -X PATCH "repos/$REPO/releases/$ID" --input - -q .id >/dev/null

# the gate: all three packages on the candidate?
ASSETS=$(gh api "repos/$REPO/releases/$ID/assets?per_page=100" -q '.[] | [.name, .digest] | @tsv')
pick() { printf '%s\n' "$ASSETS" | awk -F'\t' -v re="$1" '$1 ~ re' ; }
APK=$(pick '\\.apk$'); EXE=$(pick 'EXE\\.zip$'); LIN=$(pick 'Linux\\.tar\\.gz$')
MISSING=""
[ "$(printf '%s' "$APK" | grep -c .)" = 1 ] || MISSING="$MISSING Android-APK"
[ "$(printf '%s' "$EXE" | grep -c .)" = 1 ] || MISSING="$MISSING Windows-EXE-zip"
[ "$(printf '%s' "$LIN" | grep -c .)" = 1 ] || MISSING="$MISSING Linux-tar.gz"
if [ -n "$MISSING" ]; then
  echo "::notice::release candidate '$TITLE' is a draft (not public) and waits for:$MISSING. The run that adds the last package publishes it."
  exit 0
fi

d() { printf '%s' "$1" | cut -f2 | sed 's/^sha256://'; }
f() { printf '%s' "$1" | cut -f1; }
for x in "$APK" "$EXE" "$LIN"; do [ -n "$(d "$x")" ] || { echo "::error::no SHA-256 from GitHub for $(f "$x")"; exit 1; }; done
printf '%s  %s\n%s  %s\n%s  %s\n' "$(d "$APK")" "$(f "$APK")" "$(d "$EXE")" "$(f "$EXE")" "$(d "$LIN")" "$(f "$LIN")" > checksums.sha256
jq -n --argjson n "$N" --arg v "$VER" --arg sha "$GITHUB_SHA" --arg t "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg a "$(f "$APK")" --arg as "$(d "$APK")" --arg w "$(f "$EXE")" --arg ws "$(d "$EXE")" --arg l "$(f "$LIN")" --arg ls "$(d "$LIN")" \
  '{game:"Upwardly", build:$n, version:$v, source_sha:$sha, published:$t, artifacts:[
     {platform:"android", file:$a, sha256:$as},
     {platform:"windows-x86_64", file:$w, sha256:$ws, runtime:"jlink JRE 17 (jpackage app-image)"},
     {platform:"linux-x86_64", file:$l, sha256:$ls, runtime:"jlink JRE 17 (jpackage app-image)"}]}' > build-manifest.json
upload "$ID" checksums.sha256
upload "$ID" build-manifest.json

# owner (2026-10-24): releases are prepared as a private draft; it goes public only when the dispatch says so (publish_public)
if [ "${PUBLISH_PUBLIC:-false}" != "true" ]; then
  echo "::notice::all three packages, checksums.sha256 and build-manifest.json are on '$TITLE', kept as a PRIVATE DRAFT (publish_public is off; the owner publishes it)"
  exit 0
fi
jq -n --arg t "$TITLE" --arg tag "$TAG" --arg sha "$GITHUB_SHA" '{name:$t, tag_name:$tag, target_commitish:$sha, draft:false, make_latest:"true"}' > rel.json
if OUT=$(gh api -X PATCH "repos/$REPO/releases/$ID" --input rel.json -q .html_url 2>&1); then
  echo "published $OUT (all three packages from $GITHUB_SHA)"
else
  echo "$OUT"
  echo "::error::GitHub refused to publish the release (all files are on the draft '$TITLE'; press Publish on it): $OUT"
  exit 1
fi
