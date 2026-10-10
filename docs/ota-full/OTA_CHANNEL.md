# Experimental OTA channel (GitHub Actions -> phones)

Everything is on the experimental branch `exp/ota-full`; nothing here touches the production release branch, the production `ota` release, tags or GitHub Releases.

## One-time setup (owner, 2 minutes)
1. Make a key (any computer with OpenSSL): `openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out exp-module-key.pem`
2. GitHub, repository `verbal76/Climb-Up` -> Settings -> Secrets and variables -> Actions -> New repository secret. Name `OTA_EXP_MODULE_KEY_PEM`, value = the whole contents of `exp-module-key.pem`. Keep the file somewhere safe; it is never committed.

## Build the OTA test APK and publish the first release
Actions -> "OTA experimental channel - build the OTA test APK and publish a signed game module" -> Run workflow on `exp/ota-full`. It builds the isolated test app with your public key pinned inside, signs the module, publishes it to the branch `ota-exp-channel`, and uploads the APK as the artifact `ota-test-apk`. Install `ota-test-apk` on the phone (app id `com.hotatticgames.climbup.otaexp`, never replaces the installed game).

## Ship a gameplay correction to phones
Change the game on `exp/ota-full`, raise the number in `tools/otalab/module_version.txt`, push. The workflow signs and publishes the new module. Phones check the channel every time the test app starts (or from the console: "Check for updates now"); a newer verified release is downloaded and applies at the next cold start. A release that changes level generation waits while a climb is in progress; a broken release rolls back by itself.

## Limits
Experimental channel: one signing key, no staged rollout. Raw GitHub file hosting can lag a few minutes after a publish.
