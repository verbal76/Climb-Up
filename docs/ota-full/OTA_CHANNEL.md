# Experimental OTA channel (GitHub Actions -> phones)

Everything is on the experimental branch `exp/ota-full`; nothing here touches the production release branch, the production `ota` release, tags or GitHub Releases.

## Signing key
The channel is signed with the OTA key already configured in this repository (secrets `OTA_SIGNING_PRIVATE_KEY` and `OTA_SIGNING_KEY_PASSPHRASE`; its public half is the one pinned in the app, `assets/ota/ota_public_key.b64`). The workflows open it into a runner-local temporary file, refuse to continue if it does not match the pinned public key, and never print it. No other secret is needed.

Channel address: `https://raw.githubusercontent.com/verbal76/Climb-Up/ota-exp-channel/` (files `manifest.json`, `manifest.sig`, `module.dex`, `assets/<sha256>`).

## Build the OTA test APK and publish the first release
Actions -> "OTA experimental channel - build the OTA test APK and publish a signed game module" -> Run workflow on `exp/ota-full` (or push a change to `tools/otalab/module_version.txt`). It builds the isolated test app with your public key pinned inside, signs the module, publishes it to the branch `ota-exp-channel`, and uploads the APK as the artifact `ota-test-apk`. Install `ota-test-apk` on the phone (app id `com.hotatticgames.climbup.otaexp`, never replaces the installed game).

## Ship a gameplay correction to phones
Change the game on `exp/ota-full`, raise the number in `tools/otalab/module_version.txt` above the version the channel currently serves (a release that is not higher is refused, by the publisher and by the app), push. The workflow signs and publishes the new module. Phones check the channel every time the test app starts (or from the console: "Check for updates now"); a newer verified release is downloaded and applies at the next cold start. A release that changes level generation waits while a climb is in progress; a broken release rolls back by itself.

## Limits
Experimental channel: one signing key, no staged rollout. Raw GitHub file hosting can lag a few minutes after a publish.

The live end-to-end test (`ota-exp-e2e.yml`, change `tools/otalab/live_e2e_run.txt` and push to run it) publishes four releases to this channel while an emulator app is installed; it picks versions above the current channel version by itself.
