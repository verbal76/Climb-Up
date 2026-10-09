#!/usr/bin/env bash
# Checks the signing secrets (passed as environment variables by the workflow) WITHOUT printing any value: only PASS/FAIL, lengths and public fingerprints.
#   tools/ci/verify_secrets.sh [--ota-only | --android-only] [--keystore-out FILE]
# --keystore-out writes the decoded Android keystore (mode 600) for the build to use. Exits 1 on any problem. Run from the repository root.
set +e; umask 077
only=""; ksout=""
while [ $# -gt 0 ]; do case "$1" in --ota-only) only=ota;; --android-only) only=android;; --keystore-out) ksout="$2"; shift;; esac; shift; done
fails=0; T=$(mktemp -d)
ok()  { echo "PASS  $1"; }
bad() { echo "FAIL  $1 - $2"; fails=$((fails+1)); }
WANT_CERT="CE:DD:D6:86:8B:F9:4C:30:B2:70:F3:59:01:88:66:95:EE:6A:E9:AB:95:51:19:6C:2C:A9:E2:99:50:04:1C:39"   # the certificate of the installed Build 42 (and builds 37-42)

if [ "$only" != ota ]; then
  for n in ANDROID_KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD; do v="${!n}"; [ -z "$v" ] && bad $n "is empty or not set" || echo "info  $n is set, ${#v} characters"; done
  printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 -d > $T/ks 2>/dev/null
  if cmp -s $T/ks android/debug.keystore; then ok "ANDROID_KEYSTORE_BASE64 decodes to exactly android/debug.keystore"; else bad ANDROID_KEYSTORE_BASE64 "does not decode to the committed keystore"; fi
  FP=$(keytool -list -v -keystore $T/ks -storepass "$ANDROID_KEYSTORE_PASSWORD" -alias "$ANDROID_KEY_ALIAS" 2>/dev/null | grep "SHA256:" | head -1 | sed 's/.*SHA256: //')
  [ "$FP" = "$WANT_CERT" ] && ok "keystore opens with the given password and alias; certificate matches the installed Build 42 ($FP)" || bad "ANDROID keystore/password/alias" "could not open it, or the certificate differs (got: ${FP:-nothing})"
  [ "$ANDROID_KEY_PASSWORD" = "android" ] && ok "ANDROID_KEY_PASSWORD matches the keystore's key" || bad ANDROID_KEY_PASSWORD "is not the key password"
  if [ -n "$ksout" ]; then cp $T/ks "$ksout" && chmod 600 "$ksout"; fi
fi

if [ "$only" != android ]; then
  for n in OTA_SIGNING_PRIVATE_KEY OTA_SIGNING_KEY_PASSPHRASE; do v="${!n}"; [ -z "$v" ] && bad $n "is empty or not set" || echo "info  $n is set, ${#v} characters"; done
  printf '%s' "$OTA_SIGNING_KEY_PASSPHRASE" | grep -Eq '^[0-9a-f]{64}$' && ok "OTA_SIGNING_KEY_PASSPHRASE is 64 hex characters, no stray whitespace" || bad OTA_SIGNING_KEY_PASSPHRASE "is not exactly 64 lowercase hex characters"
  printf '%s\n' "$OTA_SIGNING_PRIVATE_KEY" > $T/k.pem
  head -1 $T/k.pem | grep -q '^-----BEGIN ENCRYPTED PRIVATE KEY-----$' && ok "OTA_SIGNING_PRIVATE_KEY has the BEGIN ENCRYPTED PRIVATE KEY line" || bad OTA_SIGNING_PRIVATE_KEY "first line is not -----BEGIN ENCRYPTED PRIVATE KEY-----"
  printf '%s' "$OTA_SIGNING_KEY_PASSPHRASE" > $T/pp
  openssl pkey -in $T/k.pem -passin file:$T/pp -pubout -outform DER 2>/dev/null | base64 -w0 > $T/pub.b64
  if [ ! -s $T/pub.b64 ]; then bad OTA_SIGNING_PRIVATE_KEY "cannot be opened with OTA_SIGNING_KEY_PASSPHRASE"
  elif [ "$(cat $T/pub.b64)" = "$(tr -d '\n ' < assets/ota/ota_public_key.b64)" ]; then ok "OTA private key opens and is the pair of the public key pinned in the app (key id $(base64 -d $T/pub.b64 | sha256sum | cut -c1-16))"
  else bad OTA_SIGNING_PRIVATE_KEY "opens, but is NOT the pair of assets/ota/ota_public_key.b64"; fi
fi
rm -rf $T
[ $fails -eq 0 ] && { echo "RESULT: signing secrets are correct"; exit 0; } || { echo "RESULT: $fails problem(s) above"; exit 1; }
