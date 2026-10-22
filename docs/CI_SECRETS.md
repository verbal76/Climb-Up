# CI signing secrets (names and formats only - no values live in the repository)

## What is installed on the phone and what signs it
- Builds 37-42 (and every build made from commit 1ba87d8 on) are signed by the committed `android/debug.keystore` (alias `androiddebugkey`, passwords `android`). Verified on the published Build 42 APK: APK Signature Scheme v2 certificate SHA-256 `CE:DD:D6:86:8B:F9:4C:30:B2:70:F3:59:01:88:66:95:EE:6A:E9:AB:95:51:19:6C:2C:A9:E2:99:50:04:1C:39`, identical to the keystore's. Builds 37-42 all match.
- Builds 4-36 were signed with a different throw-away key on every CI run; those keys no longer exist, so those installs cannot be updated in place (uninstall once, which loses local data).
- This key is a *public* debug key (its private half is in the repository). That is acceptable for sideloaded family tests and is the identity every installed copy has, so it must **not** be replaced or regenerated: Android refuses an update signed by a different key. The cost: anyone can build an APK that Android will accept as an update to the installed game if the owner chooses to install it. A private release key would fix that but forces everyone to uninstall once; not recommended before a store release.

## Build 73: a private release key, with key rotation (owner decision, 2026-10-22)
For the public production release the owner chose to move to a private key without breaking updates. `tools/ci/rotate_sign.sh` (run by `android.yml`) signs every APK from Build 73 on as follows:
- **Android 8.x (API 26-27):** the old public identity (`CE:DD:D6:86...`), as before. These versions cannot rotate keys.
- **Android 9+ (API 28+):** the new private key, plus a proof of rotation (signing lineage) signed by the old key. The phone accepts it as an update and, from then on, trusts only the new key. The old key gets **no rollback capability**, so an APK signed only with the public key can no longer update an installed copy.
- **Gate:** the workflow then installs the last published APK (Build 71) on an Android 14 emulator, updates it in place with the new APK (same first-install time, data kept) and launches the game (`tools/ci/update_test.sh`). The release fails otherwise.
- **Keep the new keystore and its passwords safe and backed up (two places).** If it is lost, no later build can update an installed copy on Android 9+.

### Creating the key (owner, once, on your own computer; never in chat or in the repository)
1. Install a Java JDK if you have none (for example Eclipse Temurin 17), which provides `keytool`.
2. Run: `keytool -genkeypair -v -keystore upwardly-release.jks -alias upwardly -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Hot Attic Games, O=Hot Attic Games"`. Choose a strong store password; use the same one for the key when asked.
3. Base64 it on one line. PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("upwardly-release.jks")) | Set-Clipboard`. macOS/Linux: `base64 -w0 upwardly-release.jks` (macOS: `base64 -i upwardly-release.jks`).
4. GitHub, repository Settings > Secrets and variables > Actions > New repository secret, four times:

| Secret | Value |
|---|---|
| `ANDROID_RELEASE_KEYSTORE_BASE64` | the one-line base64 from step 3 |
| `ANDROID_RELEASE_KEYSTORE_PASSWORD` | the store password |
| `ANDROID_RELEASE_KEY_ALIAS` | `upwardly` |
| `ANDROID_RELEASE_KEY_PASSWORD` | the key password (the same as the store password if you pressed Enter) |

Without them the Android build stops at the signing step with a message naming the missing secret; nothing is published.

## The six Actions secrets (Builds 37-72; still used for the Android 8.x signature and the OTA)
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
- **Wired in:** `android.yml` (APK signing + OTA baseline) and `ota-publish.yml` (later content) read these secrets; `tools/ci/verify_secrets.sh` proves them first (PASS/FAIL only) and the build fails if the APK's certificate is not the Build 42 one.
- Rotating the OTA key later needs a new APK carrying the new public key (old installs only trust the pinned one).

## Loading the secrets
Never paste values into chat, issues, logs or commits. The values were verified once by a throw-away workflow (since removed; its logic is `tools/ci/verify_secrets.sh`, which every release build runs). To re-enter a value: Settings > Secrets and variables > Actions > (name) > Update secret, or on a computer with `gh auth login`: `tools/ci/set_github_secrets.sh <folder>` (reads the files, sends them over stdin, prints only the names). Delete the folder afterwards.
