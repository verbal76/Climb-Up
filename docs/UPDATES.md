# Update architecture — Climb up

Per studio standard §10. Different platforms use different, engine-appropriate mechanisms; each publish is separately
authorized (never triggered by a push — see RELEASES.md). Full OTA design/tests: `docs/OTA.md`.

## Identifiers
- **Game build number** — the coordinated release number.
- **Source SHA** — recorded in each build's manifest / BUILD_INFO.
- **Native runtime compatibility** — `OtaConfig.RUNTIME` (the bundled script/schema runtime version).
- **Content version** — `OtaConfig.BUNDLED_CONTENT_VERSION` / `tools/ota/content_version.txt`.
- **Channel** — the `release` OTA channel (GitHub Release tag `ota`).

## Native vs content boundary
| Change | Delivery |
|---|---|
| Tuning numbers, balance, content expressed as the bundled sandboxed data/scripts the game interprets | **Android signed OTA** (content-only; no native code, no arbitrary execution) |
| Any Java/engine/native/permission change, or a schema/runtime bump | **New native build** (APK / EXE / Linux) — never an OTA |

## Android OTA (implemented)
Signed, content-only: a manifest signed with a key whose **public key is pinned in the app**, SHA-256 per file,
minimum-app-version + schema gate, atomic staging, anti-rollback counter, automatic fallback to bundled content on any
verify/load failure. Checks are silent, non-blocking, ≤ once/24 h, unmetered-only, short timeout; applied only on the next
cold start. Permissions: INTERNET only. Tested: bad signature rejected, corrupt file rejected, interrupted download clean,
rollback, offline start, version gate (see `docs/OTA.md` / `docs/VERIFICATION.md`). Publish: manual `ota-publish.yml` only.

## Windows / Linux updates
Desktop distribution is via **SteamPipe** (Steam delivers game files and native updates) and/or full replacement of the
sideload EXE/tar.gz. **Do not** build a competing desktop binary updater (§10). The current sideload EXE/Linux builds are
full self-contained packages; there is no desktop OTA. Save compatibility and rollback are provided by Steam + the
versioned save system (`docs/` save notes / `SaveData` schema).

## Separation (safety)
Creating a GitHub Release does **not** publish an OTA; publishing an OTA does **not** create a native build; neither
touches Steam. Each is a distinct, explicitly-authorized manual workflow.
