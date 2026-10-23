#!/usr/bin/env bash
# Owner (2026-10-22): one full GitHub Release per build carries the APK, the Windows EXE and the Linux build together.
#   tools/ci/publish_release.sh <tag> <build number> <notes.md> <file>...
# android.yml and windows.yml both call this with the same tag (v<version>-buildN); whichever finishes first creates the release, the other adds its
# files to it and appends its notes. Re-running a workflow replaces its own files (--clobber). Needs GH_TOKEN and REPO.
# The release is created as a draft first (that never needs a tag), the files are attached, then it is published (which creates the tag). If GitHub
# refuses the publish (Build 74: HTTP 403 creating the release with its tag), the files stay on the draft, the error is printed, and the job fails so the
# owner sees it; the owner can then press Publish on that draft.
set -euo pipefail
TAG="$1"; N="$2"; NOTES="$3"; shift 3
TITLE="Upwardly build ${N}"
# the release for this tag, published or draft (a draft is not found by tag name)
ID=$(gh api --paginate "repos/$REPO/releases?per_page=100" -q ".[] | select(.tag_name == \"$TAG\") | .id" | head -1)
if [ -z "$ID" ]; then
  # the id comes straight from the create call: a draft just made can be missing from the release list for a moment (Build 76: an empty id, then a 404)
  jq -n --arg t "$TITLE" --rawfile b "$NOTES" --arg tag "$TAG" --arg sha "$GITHUB_SHA" '{name:$t, body:$b, tag_name:$tag, target_commitish:$sha, draft:true}' > draft.json
  ID=$(gh api -X POST "repos/$REPO/releases" --input draft.json -q .id)
  [ -n "$ID" ] || { echo "::error::could not create the draft release"; exit 1; }
  for f in "$@"; do gh api -X POST "https://uploads.github.com/repos/$REPO/releases/$ID/assets?name=$(basename "$f" | sed 's/ /%20/g')" -H "Content-Type: application/octet-stream" --input "$f" -q .browser_download_url; done
  BODY=$(cat "$NOTES")
else
  for f in "$@"; do        # replace this workflow's files if a re-run uploads them again
    A=$(gh api "repos/$REPO/releases/$ID/assets?per_page=100" -q ".[] | select(.name == \"$(basename "$f")\") | .id")
    [ -z "$A" ] || gh api -X DELETE "repos/$REPO/releases/assets/$A"
    gh api -X POST "https://uploads.github.com/repos/$REPO/releases/$ID/assets?name=$(basename "$f" | sed 's/ /%20/g')" -H "Content-Type: application/octet-stream" --input "$f" -q .browser_download_url
  done
  OLD=$(gh api "repos/$REPO/releases/$ID" -q .body)
  if printf '%s' "$OLD" | grep -qF "$(head -1 "$NOTES")"; then BODY="$OLD"; else BODY=$(printf '%s\n\n---\n\n%s' "$OLD" "$(cat "$NOTES")"); fi
fi
jq -n --arg t "$TITLE" --arg b "$BODY" --arg tag "$TAG" --arg sha "$GITHUB_SHA" '{name:$t, body:$b, tag_name:$tag, target_commitish:$sha, draft:false, make_latest:"true"}' > rel.json
if OUT=$(gh api -X PATCH "repos/$REPO/releases/$ID" --input rel.json -q .html_url 2>&1); then
  echo "published $OUT"
else
  echo "$OUT"
  echo "::error::GitHub refused to publish the release (the files are attached to the draft '$TITLE'; press Publish on it): $OUT"
  exit 1
fi
