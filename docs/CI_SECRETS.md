# CI signing secrets (names and formats only - no values live in the repository)

## What is installed on the phone and what signs it
- Builds 37-42 (and every build made from commit 1ba87d8 on) are signed by the committed `android/debug.keystore` (alias `androiddebugkey`, passwords `android`). Verified on the published Build 42 APK: APK Signature Scheme v2 certificate SHA-256 `CE:DD:D6:86:8B:F9:4C:30:B2:70:F3:59:01:88:66:95:EE:6A:E9:AB:95:51:19:6C:2C:A9:E2:99:50:04:1C:39`, identical to the keystore's. Builds 37-42 all match.
- Builds 4-36 were signed with a different throw-away key on every CI run; those keys no longer exist, so those installs cannot be updated in place (uninstall once, which loses local data).
- This key is a *public* debug key (its private half is in the repository). That is acceptable for sideloaded family tests and is the identity every installed copy has, so it must **not** be replaced or regenerated: Android refuses an update signed by a different key. The cost: anyone can build an APK that Android will accept as an update to the installed game if the owner chooses to install it. A private release key would fix that but forces everyone to uninstall once; not recommended before a store release.

## The six Actions secrets
| Secret | Value | Format |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | the existing `android/debug.keystore`, byte for byte | base64, one line (3,556 chars) |
| `ANDROID_KEYSTORE_PASSWORD` | `android` | text, no newline |
| `ANDROID_KEY_ALIAS` | `androiddebugkey` | text, no newline |
| `ANDROID_KEY_PASSWORD` | `android` | text, no newline |
| `OTA_SIGNING_PRIVATE_KEY` | new ECDSA P-256 private key, PKCS#8 encrypted (AES-256-CBC, PBKDF2-HMAC-SHA256, 600,000 iterations) | PEM text, multi-line |
| `OTA_SIGNING_KEY_PASSPHRASE` | random 256-bit passphrase for that key | 64 hex chars, no newline |

## OTA signing
- Algorithm: ECDSA over P-256 with SHA-256 (`SHA256withECDSA`, DER signature). Chosen because every Android version this app supports (minSdk 26) verifies it natively; Ed25519 is not available before Android 13.
- Public key pinned in the app: `assets/ota/ota_public_key.b64` (X.509 SubjectPublicKeyInfo, base64). SHA-256 of the DER: `a95c0a99d590e9345103c6ffdbcf601f927bdf5eea384f13c8f96f942a87d88a`.
- CI signs with `openssl dgst -sha256 -sign <(openssl pkey -in key -passin env:OTA_SIGNING_KEY_PASSPHRASE)`; verified end to end (openssl signature accepted by the JVM verifier, tampered data rejected).
- **Not wired in yet:** the current updater (`OtaClient`) checks a SHA-256 only, and no workflow reads these secrets, so setting them changes nothing until the signed-manifest work is done and pushed.
- Rotating the OTA key later needs a new APK carrying the new public key (old installs only trust the pinned one).

## Loading the secrets
Never paste values into chat, issues, logs or commits. Either type/paste each one into Settings > Secrets and variables > Actions > (name) > Update secret, or on a computer with `gh auth login`: `tools/ci/set_github_secrets.sh <folder>` (reads the files, sends them over stdin, prints only the names). Delete the folder afterwards.
