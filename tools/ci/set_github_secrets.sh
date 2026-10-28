#!/usr/bin/env bash
# Loads the six Actions secrets from a folder of files named exactly like the secrets, using the GitHub CLI.
# Values go from the files straight to GitHub over stdin: nothing is printed, put on a command line or written anywhere else.
#   usage: tools/ci/set_github_secrets.sh <folder> [owner/repo]      (needs: gh auth login)
#          tools/ci/set_github_secrets.sh <folder> --check            (lists names and sizes only)
set -euo pipefail
dir="${1:?folder with the six secret files}"; repo="${2:-verbal76/Climb-Up}"
names=(OTA_SIGNING_PRIVATE_KEY OTA_SIGNING_KEY_PASSPHRASE ANDROID_KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD)
for n in "${names[@]}"; do [ -s "$dir/$n" ] || { echo "missing or empty: $dir/$n" >&2; exit 1; }; done
if [ "$repo" = "--check" ]; then for n in "${names[@]}"; do printf '%-28s %6s bytes\n' "$n" "$(wc -c < "$dir/$n")"; done; exit 0; fi
for n in "${names[@]}"; do gh secret set "$n" --repo "$repo" < "$dir/$n" >/dev/null && echo "set $n"; done
echo "done. Now delete the folder: shred -u $dir/* && rmdir $dir"
