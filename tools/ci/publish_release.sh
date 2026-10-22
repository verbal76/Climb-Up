#!/usr/bin/env bash
# Owner (2026-10-22): one full GitHub Release per build carries the APK, the Windows EXE and the Linux build together.
#   tools/ci/publish_release.sh <tag> <build number> <notes.md> <file>...
# android.yml and windows.yml both call this with the same tag (v<version>-build<N>); whichever finishes first creates the release, the other adds its
# files to it and appends its notes. Re-running a workflow replaces its own files (--clobber). Needs GH_TOKEN and REPO.
set -euo pipefail
TAG="$1"; N="$2"; NOTES="$3"; shift 3
TITLE="Upwardly build ${N}"
add() {
  gh release upload "$TAG" "$@" --repo "$REPO" --clobber
  OLD=$(gh release view "$TAG" --repo "$REPO" --json body -q .body)
  if ! printf '%s' "$OLD" | grep -qF "$(head -1 "$NOTES")"; then printf '%s\n\n---\n\n%s\n' "$OLD" "$(cat "$NOTES")" > merged.md; else printf '%s\n' "$OLD" > merged.md; fi
  gh release edit "$TAG" --repo "$REPO" --title "$TITLE" --notes-file merged.md --draft=false --latest
  echo "added to https://github.com/$REPO/releases/tag/$TAG"
}
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then add "$@"; exit 0; fi
if gh release create "$TAG" "$@" --repo "$REPO" --target "$GITHUB_SHA" --title "$TITLE" --notes-file "$NOTES" --latest; then
  echo "published https://github.com/$REPO/releases/tag/$TAG"
else
  sleep 10; add "$@"          # the other workflow created it at the same moment
fi
