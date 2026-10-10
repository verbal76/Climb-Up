#!/usr/bin/env bash
# Publishes one prepared, signed module bundle to the EXPERIMENTAL update channel: the branch "ota-exp-channel" of this repository, served as plain static files at
# https://raw.githubusercontent.com/<repo>/ota-exp-channel/ . The branch is rewritten as a single fresh commit each time (no history to grow). Refuses a version that is not higher
# than the one already published. Never touches the production branch, the production "ota" release, tags or GitHub Releases. Owner: OTA Engineer 1.
# usage: publish_channel.sh BUNDLE_DIR VERSION      env: GH_TOKEN, GITHUB_REPOSITORY (set by GitHub Actions)
set -eu
B=${1:?}; N=${2:?}; : "${GH_TOKEN:?}" "${GITHUB_REPOSITORY:?}"
URL="https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"; T=$(mktemp -d)
CUR=0
if git ls-remote --exit-code --heads "$URL" ota-exp-channel >/dev/null 2>&1; then
  git clone --quiet --depth 1 --branch ota-exp-channel "$URL" "$T/cur"
  [ -f "$T/cur/manifest.json" ] && CUR=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1])).get('moduleVersion',0))" "$T/cur/manifest.json")
fi
if [ "$N" -le "$CUR" ]; then echo "::error::version $N is not higher than the published v$CUR (raise tools/otalab/module_version.txt)"; exit 1; fi
mkdir "$T/new"; cp -r "$B/." "$T/new/"
printf 'Experimental signed game-module channel for the OTA test app. Not the production OTA channel.\n' > "$T/new/README.txt"
git -C "$T/new" init --quiet -b ota-exp-channel
G="-c user.name=github-actions[bot] -c user.email=41898282+github-actions[bot]@users.noreply.github.com"
git -C "$T/new" $G add -A
git -C "$T/new" $G commit --quiet -m "Experimental module channel: v$N from ${GITHUB_SHA:-local}"
git -C "$T/new" push --quiet --force "$URL" ota-exp-channel
echo "published module v$N (was v$CUR) to https://raw.githubusercontent.com/${GITHUB_REPOSITORY}/ota-exp-channel/"
rm -rf "$T"
