#!/usr/bin/env bash
# Build 73 (owner): move the APK to a PRIVATE signing key without breaking updates (Android key rotation, APK Signature Scheme v3.1).
#   tools/ci/rotate_sign.sh <in.apk> <out.apk>
# The old identity (android/debug.keystore, public, CE:DD:D6:86...) signs the v2 block, so Android 8.x still updates as before; the new private key
# (ANDROID_RELEASE_* secrets) signs the v3 block together with a proof of rotation (lineage) signed by the old key, so Android 9+ accepts the update
# and from then on trusts only the new key: the old key is given no rollback capability, so an APK signed only with the public key can no longer
# update an installed copy on Android 9+. Prints only public fingerprints. Exits 1 on any problem. Run from the repository root.
set -euo pipefail; umask 077
IN="$1"; OUT="$2"
for n in ANDROID_RELEASE_KEYSTORE_BASE64 ANDROID_RELEASE_KEYSTORE_PASSWORD ANDROID_RELEASE_KEY_ALIAS ANDROID_RELEASE_KEY_PASSWORD; do
  if [ -z "${!n:-}" ]; then echo "::error::secret $n is not set (see docs/CI_SECRETS.md, Build 73 key rotation)"; exit 1; fi
done
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
printf '%s' "$ANDROID_RELEASE_KEYSTORE_BASE64" | base64 -d > "$T/new.ks"
OLD_FP=$(keytool -list -v -keystore android/debug.keystore -storepass android -alias androiddebugkey | grep "SHA256:" | head -1 | sed 's/.*SHA256: //')
NEW_FP=$(keytool -list -v -keystore "$T/new.ks" -storepass "$ANDROID_RELEASE_KEYSTORE_PASSWORD" -alias "$ANDROID_RELEASE_KEY_ALIAS" | grep "SHA256:" | head -1 | sed 's/.*SHA256: //')
[ -n "$NEW_FP" ] || { echo "::error::the release keystore does not open with the given password and alias"; exit 1; }
[ "$NEW_FP" != "$OLD_FP" ] || { echo "::error::the release key is the public debug key"; exit 1; }
echo "old (public) signer: $OLD_FP"; echo "new (private) signer: $NEW_FP"
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1); AS="$BT/apksigner"
OLD=(--ks android/debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android)
NEW=(--ks "$T/new.ks" --ks-key-alias "$ANDROID_RELEASE_KEY_ALIAS" --ks-pass env:ANDROID_RELEASE_KEYSTORE_PASSWORD --key-pass env:ANDROID_RELEASE_KEY_PASSWORD)
"$AS" rotate --out "$T/lineage" --old-signer "${OLD[@]}" --set-rollback false --new-signer "${NEW[@]}"
"$AS" sign "${OLD[@]}" --next-signer "${NEW[@]}" --lineage "$T/lineage" --rotation-min-sdk-version 28 --in "$IN" --out "$OUT"
"$AS" verify --verbose "$OUT" | grep -E "^Verified|^Verifies" || true
"$AS" verify "$OUT"
norm() { tr 'a-f' 'A-F' | sed 's/../&:/g; s/:$//'; }
V2=$("$AS" verify --print-certs --max-sdk-version 27 "$OUT" | grep -m1 "certificate SHA-256 digest" | sed 's/.*: //' | norm)
V3=$("$AS" verify --print-certs --min-sdk-version 28 "$OUT" | grep -m1 "certificate SHA-256 digest" | sed 's/.*: //' | norm)
echo "Android 8.x signer: $V2"; echo "Android 9+ signer: $V3"
[ "$V2" = "$OLD_FP" ] || { echo "::error::Android 8.x would not see the old signer"; exit 1; }
[ "$V3" = "$NEW_FP" ] || { echo "::error::Android 9+ would not see the new signer"; exit 1; }
"$AS" lineage --in "$OUT" --print-certs -v | tee "$T/lin.txt"
LIN=$(grep -c "certificate SHA-256 digest" "$T/lin.txt" || true)
[ "$LIN" -ge 2 ] || { echo "::error::the APK carries no proof of rotation"; exit 1; }
echo "ROTATED_SIGNER_SHA256=$NEW_FP" | tee -a "${GITHUB_ENV:-/dev/null}"
echo "key rotation OK"
