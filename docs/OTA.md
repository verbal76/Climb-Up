# Over-the-air updates (signed)

Scope (owner decision): content and game logic expressed as **data the game interprets**. Today that is `assets/data/tuning.json`: every movement, hazard-timing, difficulty-ramp and assist number. No native code, no scripts, no assets, no models, no audio. A payload is a zip holding exactly `tuning.json`.

## How it works
* **Hosting:** three files on the public GitHub release tagged `ota` (no server, no accounts, no identifiers sent, one HTTPS GET each):
  `https://github.com/verbal76/Climb-Up/releases/download/ota/` `manifest.json`, `manifest.sig`, `payload.zip`.
* **Trust:** `manifest.sig` is a base64 DER ECDSA (P-256, SHA-256) signature over the exact bytes of `manifest.json`, verified against the public key pinned in the app (`OtaConfig.PUBLIC_KEY_B64`, = `assets/ota/ota_public_key.b64`, key id `a95c0a99d590e934`). **Nothing in a manifest is read until its signature verifies.** The manifest carries the SHA-256 and size of the payload, so the payload is authenticated too. The private key exists only as the `OTA_SIGNING_PRIVATE_KEY` / `OTA_SIGNING_KEY_PASSPHRASE` GitHub secrets.
* **Manifest fields:** `schema` (2), `channel` (`release`), `keyId`, `contentVersion` (integer), `runtime`, `minVersionCode`, `payloadUrl` (must be under the release URL), `sha256`, `size`.
* **Runtime compatibility:** `runtime` must equal `OtaConfig.RUNTIME` exactly (this build: **2**) and `minVersionCode` must not exceed the installed `versionCode`. Bump `RUNTIME` (here and in `tools/ota/make_ota.py`; `OtaConfigTest` fails if they differ) when a change to the tuning schema or its meaning makes older payloads unsafe or newer payloads unreadable. A payload may not change the tower's layout constants (`OtaConfig.FROZEN_TUNING`: radius, zone/castle/gem spacing, chunk/course/ramp height, spiral pitch, rest/checkpoint cadence): those need a new APK.
* **Versions:** this APK bundles content version `OtaConfig.BUNDLED_CONTENT_VERSION` (**1**). Only versions strictly above it and above everything ever applied or staged are taken (anti-rollback: an old signed release replayed by an attacker is ignored).
* **Check:** silent background thread at app start, at most once per 24 h, 6 s timeouts, size caps (manifest 16 KB, signature 1 KB, payload 256 KB), failure is silent. Settings > About shows `CONTENT: V1 (BUNDLED), RUNTIME 2`, the last result, an on/off switch (default on) and `UPDATE NOW`. A transfer that is cut short (fewer bytes than promised) is discarded.
* **Download -> verify -> stage:** signature, key id, channel, runtime, build gate, SHA-256, allow-listed unzip, `Tuning.validate()` (finite, sane ranges), layout check, then `ota/staging.tmp` -> atomic rename to `ota/staging`. Nothing half-written is ever used; a leftover temp dir is deleted at the next start.
* **Activation:** only on the **next cold start**, never mid-session: `ota/active` -> `ota/previous`, staging -> `ota/active`. No prompts, no forced restart.
* **Rollback / loop protection:** a freshly applied payload is "pending" until the game reaches 15 s of live play. If the app starts more than twice without that confirmation (crash, hang, kill), or the active files fail their checksum/validation, the payload is dropped, its version is blacklisted and the previous payload (or the bundled content) is used. Offline, or any failure, leaves the installed content untouched.
* **Climbs in progress:** the tower's stored slices are bytes and its layout constants are frozen, so an update never invalidates a saved climb. Movement-feel numbers apply to the whole tower; each *new* slice is proven by the solver under the new numbers, slices already generated were proven under the numbers current when they were made. Keep movement changes modest.
* **Not implemented:** the "unmetered connection only" condition of the original design (it needs `ACCESS_NETWORK_STATE`; the spec also says INTERNET only). The whole download is under 260 KB.

## Publishing an update (owner)
1. Edit `assets/data/tuning.json`.
2. Raise the number in `tools/ota/content_version.txt` (must be higher than the last published; the APK ships 1).
3. Push to the release branch. `Publish signed OTA content` runs: verifies the secrets, runs the generator/solver/OTA/persistence tests against the new numbers, signs, pushes the files through the app's own client against the pinned key (`:core:verifyOta`) and only then uploads to the `ota` release. (The same push also builds a new APK, as every push to that branch does.)
4. Phones pick it up within a day at app start; it applies on the following start. To force it: Settings > About > UPDATE NOW, then restart.
`tools/ota/make_ota.py --version N --sign` is the tool the workflow runs; it refuses to sign with a key that is not the pair of the pinned public key.

## Rotating the key / revoking
The pinned key is part of the APK. A new key means a new APK carrying the new public key (and new secrets); installs of the old APK keep trusting only the old key.

## Verification performed (see docs/VERIFICATION.md)
`OtaTest` (14), `OtaConfigTest` (3), `OtaEndToEndTest` (4: full pipeline over local HTTP incl. a signed gameplay change measured in the Sim after the next start, forged/altered releases, interrupted transfer, and the release produced by `make_ota.py` accepted by the Java verifier), and the release gate `:core:verifyOta` on every CI build with the real key.
