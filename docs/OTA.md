# Family-test OTA (TEST ONLY)

**Climb Up is a proof-of-concept game tested through sideloaded APKs on a few trusted family phones. This updater is not for Google Play, internal-testing tracks, strangers, or any production use.**

> **Security statement.** The only integrity check is a SHA-256 taken from `manifest.json`. A checksum proves the download was not corrupted; it does **not** authenticate who published it. Anyone who can write to the `ota-dev` release (or intercept it, since the manifest is trusted as-is) could ship content to every installed copy. This configuration is therefore **not approved for untrusted or production distribution**. Promoting it to production needs a separate security review and authenticated (signed) update delivery. Nothing in the app or CI does that automatically.

## What it updates
Only `assets/data/tuning.json` (every gameplay / generator / movement number: run speed, gravity, jump, gap sizes, crumble delays, swing super-jump power, zone sizes ...). No code, no assets, no models/audio, no rendering or controls. A payload may contain exactly that one file; any other entry name (including `../x` or subfolders) rejects the whole payload.

## How it works (small on purpose)
* **Hosting:** two files on a public GitHub release with the fixed tag `ota-dev`: `manifest.json` and `payload.zip` (repo is public; no server, no accounts, no credentials in the APK, no identifiers in the request).
  `https://github.com/verbal76/Climb-Up/releases/download/ota-dev/manifest.json`
* **Manifest:** `schema`, `channel` (`dev`), `contentVersion` (integer, must only ever go up), `runtime`, `minVersionCode`, `payloadUrl` (must live under the same release URL), `sha256`, `size`.
* **Check:** silent background thread at app start, at most once per 24 h, short timeouts, failure is silent. Settings > About shows `CONTENT:` state and the last result, has an on/off switch (default on) and an `UPDATE NOW` button.
* **Download -> verify -> stage:** size caps (manifest 16 KB, payload 256 KB), SHA-256 of the zip must match, allow-listed unzip, the tuning JSON must parse and pass `Tuning.validate()` (finite, sane ranges), then written to `ota/staging.tmp` and renamed to `ota/staging` (atomic). A half-finished download is deleted at the next start.
* **Apply:** only on the **next cold start**, never mid-session. `ota/active` -> `ota/previous`, staging -> `ota/active`.
* **Compatibility gate:** `runtime` must equal the app's `OtaConfig.RUNTIME` (bump it, and the same constant in `tools/ota/make_ota.py`, only with a native/code change that old payloads cannot survive) and `minVersionCode` must not exceed the installed `versionCode`.
* **Anti-rollback:** a version is accepted only if it is higher than everything ever applied or already staged and has not been rolled back before.
* **Rollback / loop protection:** a freshly applied payload is "pending" until the game reaches 15 s of live play. If the app is started more than twice without that confirmation (crash, hang, ANR, kill), or the active files fail their checksum/validation, the payload is dropped, its version is blacklisted, and the previous payload (or the bundled content) is used. A bad file or a bad state file can never prevent the game from starting on bundled content.

## Publishing an update (owner)
1. Edit `assets/data/tuning.json`, commit and push it.
2. GitHub > Actions > **Publish family-test OTA content** > Run workflow > enter the next version number (higher than the last). It builds `tools/ota/make_ota.py` output and uploads `manifest.json` + `payload.zip` to the `ota-dev` release (created on first use). Uses only the automatic workflow token: **no secrets**. (Local alternative: `python3 tools/ota/make_ota.py --version N`, then `gh release upload ota-dev build/ota/* --clobber`.)
3. Open the game once (the check runs at start), close it, open it again: the new numbers are live.

## Signing (why one replacement APK is needed)
The APKs published so far were debug-signed with a **different throw-away key on every CI run**, so no build can install over another, and they have no INTERNET permission and no updater code. Therefore **the currently installed APK cannot receive OTA updates**. One replacement APK is required: it contains the updater, the INTERNET permission, and a **fixed debug keystore committed at `android/debug.keystore`** (the standard Android debug identity: store/key password `android`, alias `androiddebugkey`; it is public by design, not a secret, and must never be used for a store release). Uninstall the old build first (saves on the phone are lost once), then install the new one; every later build then installs over it with no uninstall, and gameplay-number changes need no APK at all.

## Not done / limits
* Only tuning numbers are updatable (no scripts, no assets). The spec's signed-manifest design (CLAUDE.md section 9) is intentionally replaced by this test-only design per the owner's correction; it must be built properly (signed manifest, pinned public key) before any production use.
* The OTA code path was verified with unit tests and a Python-built payload on desktop; it was not run on a phone and no APK was built for this candidate.
