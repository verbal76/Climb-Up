# OTA update reliability (OTA Engineer 2) - design and evidence

Branch `exp/ota-reliability`, branched from `333e980754540aacc676b4ebf79446de9e4ff22f` (see `BRANCH_POINT.txt`).
Verification levels used below: **unit** (JVM, one class), **integration** (JVM, real filesystem, several classes), **emulator** (Android API 34 in CI), **device** (physical phone). Nothing here has been run on a physical device.

## 1. Asset overlay: splash delivered through the host (A)

| Item | Result | Level |
|---|---|---|
| `e70b717` (E1: re-install the overlay in `onResume`) | Splash frame vs APK: mean 0 -> 78, dominant colours are the inverted fixture's. The delivered file IS drawn. Title unchanged (mean 0.17), sounds load, no `ClassCastException`, store verified, v6 activated at the next cold start. | emulator (run 38013493096) |
| Remaining red check in that run | My own assertion (`corr<0.2`, measured 0.214: both frames share a dark background). Not an overlay defect; `OverlayFiles` needs no change. | emulator |
| Replacement assertion (`tools/otalab/asset_emulator_test.sh`) | On logo pixels, distance to invert(APK frame) must be < 0.5x the distance to the APK frame, and mean > 20. Stricter than before: it proves the CONTENT is the delivered file. Sanity-checked on synthetic frames (inverted: 0 vs 609; unchanged fails). | unit-level check of the metric only; **not yet run on CI** |

### Path safety (`PathSafetyTest`, unit / JVM integration, local 75/75 for hostkit)

- Manifest side: 7 good names accepted; about 45 hostile spellings refused (`..` and `.` segments, absolute, `//`, trailing `/`, Windows separators and drive letters, percent-encoded dots/separators, NUL/control/space/Windows-forbidden characters, fullwidth dot and slash, division/fraction slash, RLO, zero-width, combining marks, Cyrillic look-alikes, Kelvin sign, any non-ASCII, any `..` substring). Count and size limits hold; an absurd size and duplicate JSON keys do not crash the parser.
- Request side: 9 equivalent spellings of an overridden path reach the override; case variants, `..` escapes, encoded separators, a trailing NUL and the content-store file name/path never do. A request cannot reach a content-store file (store files are addressed by hash only).
- **Defect 1 (fixed):** duplicate-by-case detection used the default locale: under `tr_TR`, `ID` and `id` did not collide. Now `Locale.ROOT` (tested in tr, az, lt, US, ROOT).
- **Defect 2 (fixed):** `OverlayFiles.norm` did not canonicalise `a//b`, `a/./b`, `a/x/../b`, so those spellings silently skipped an override that the exact spelling hit. Now canonicalised; a `..` that would climb above the root is kept (never equals a key) and left to the backend as before. Case is deliberately not folded (the APK lookup is case-sensitive).

Not done yet from (A): every asset type through the overlay on a device (g3dj, obj, png, ogg), asset probe interface for E1.

## 2. Crash-point fault injection for `ModuleStore` (D)

`ModuleStore.Fault` is a no-op seam called between the durable steps of boot, activation, drop, confirm, staging, baseline install and every state write (production never sets it). `CrashPointTest` discovers every injection point by running a scenario, then re-runs it from a clean directory once per point with the process killed exactly there (an `Error` nothing in the store catches), restarts like the host (boot, install baseline if nothing runs, confirm if healthy) and checks the invariants. A second sweep also kills the first recovery launch at each of ITS points.

Invariants: I1 only verified modules are handed to the loader; I2 launches settle (no endless update/rollback loop); I3 the anti-rollback counter never drops and old releases stay refused; I4 an interrupted update is applied or can be delivered again; I5 saves are exactly the old set or exactly the migrated set, v1 never runs on migrated saves, and no snapshot leaks.

Scenarios: upgrade v1->v2; upgrade with a save-schema bump; crash-loop rollback with a save-schema bump (SaveGuard restore); first baseline install; eight kinds of damage to the state files.
Result: **1,390 simulated deaths pass** (integration). Before the fixes the same harness failed.

Defects found by the harness and fixed in `ModuleStore`:

1. **Update livelock.** Death after `activate` published `mod/vN` but before the state recording it was saved left `staged=N` with no `staged/` directory: `preflight` said "up to date" forever and the update could never be delivered again. Fix: `reconcileStaged()` puts any orphaned, newer, non-blacklisted module directory back into `staged/` so it goes through the normal activation checks.
2. **Anti-rollback counter lost.** A missing or damaged `state.json` reset `highest`, `revokeFloor` and `bad` to 0, so an old signed release would have been accepted again. Fix: checksummed state (`S1:<sha256>\n<json>`, legacy bare JSON still read), a verified backup copy `state.json.bak`, fsync of the file and the directory, and when no copy is usable the floor is rebuilt from the highest version/revoke floor of every installed module that still verifies.
3. **Delete-before-record orderings.** Activation pruned old modules before the new state was written; a drop deleted the broken module before the verdict was durable; `confirm` discarded the SaveGuard snapshot before the confirmation was durable (a death in between lost the only way back to the old saves). All are now save-first. A confirmed module's obsolete snapshot is discarded at boot if the process died before doing so.
4. `saveState` no longer hides failure (returns false; `confirm` keeps the snapshot if the write failed).

5. **`ModuleStore.forgiveCleanPause()`** (E1's REQUEST). Every launch that dies before `confirm` counts toward `MAX_UNCONFIRMED_LAUNCHES=2`, so three quick back-outs would roll back and blacklist a healthy release. The launcher calls this from `onPause` after the module's first rendered frame: it decrements `tries` once (never below 0, only for an unconfirmed module, idempotent per launch, durable before it returns). A hang or crash never reaches `onPause`, so it still counts. Tests: six clean back-outs never roll back, two un-forgiven launches still do; sweep of 917 simulated deaths around it. (integration; emulator wiring is E1's)

6. **`recountAfterResume()`** (E1 review R1, security). `forgiveCleanPause()` alone could hide a real crash: launch (tries 1), first frame, Home (forgiven, 0), back, the module crashes with tries=0 and never rolls back. `recountAfterResume()` is its reversible pair: only if this launch was forgiven and the module is still unconfirmed, `tries++`, durable, idempotent per forgiveness. Launcher contract: onPause (after a first frame) -> `forgiveCleanPause()`, onResume -> `recountAfterResume()`. Tests: three pause-resume-crash launches roll back and blacklist; pause with no resume then eight kills never count; a confirmed module is never forgiven or recounted; crash-point sweep (634 deaths) around both.
7. **Legacy state fixtures** (E1 review R2): a bare-JSON `state.json` written by the pre-S1 code loads, the next save rewrites it as S1 and the legacy bytes become `state.json.bak`; a truncated S1 main with a legacy backup recovers from the backup, repairs the main, and a damaged main never replaces the good backup.
8. **Monotonic floor file** (E1 review R3): `root/floor` = `F1:<sha256>\n<highest>,<revokeFloor>`, written durably only when a number rises and applied at every boot. A release that was rolled back (its directory deleted) stays refused even when BOTH state copies are lost. Written AFTER the state move, never before: a first version that wrote it before the state let a death in between leave the floor claiming a version whose activation the state never recorded, and that update could then be neither adopted nor delivered again (the sweep caught it). A damaged or tampered floor file is ignored (it can only ever raise the floor if its checksum verifies). Residual: someone who can write the app's private directory can delete the floor, the state and its backup; that is outside this threat model.

Observations NOT changed (design decisions for E1/owner):
- The harness only requires convergence after a SINGLE death before confirmation: two deaths plus the next launch is three unconfirmed launches, and the crash-loop rule then legitimately rolls back.
- With `state.json` and its backup both lost, the blacklist (`bad`) and the `restoring` marker are lost; the anti-rollback floor survives through `root/floor` (item 8), which also covers every blacklisted version at or below `highest`.

Model limits: deaths are modelled BETWEEN steps; torn writes inside one syscall, power loss with an unflushed page cache, and storage-full are not injected (not verified).

## 3. Download hardening (B) - `ModuleDownloader`, `DownloadHardeningTest` (integration, real local HTTP server; local 90/90 for hostkit)

| Gap found | Fix |
|---|---|
| Redirects were followed by `HttpURLConnection` itself (up to 20 hops, no policy) | Followed by hand: at most `MAX_REDIRECTS=5` hops, https only (http only for lab servers and never from an https URL), no credentials in the URL, relative `Location` resolved, `Range` re-sent on each hop. Policy is a pure function, tested with 10 refused targets (downgrade, ftp, file, jar, javascript, empty, userinfo). |
| `206` accepted without checking where it starts | `Content-Range` must be present, start exactly at the offset asked for, and fit the remaining declared size; otherwise the response is refused and nothing is appended. |
| `416` left the partial file in place, so every later check failed the same way | `RangeRejected` discards the partial file; the next check starts over (tested end to end). |
| Only a per-read socket timeout: a slow drip never ended | Wall-clock budget per check (default 15 min, `withTimeBudgetMillis`); partial asset downloads are kept and resumed by the next check. |
| No free-space check | Required bytes (manifest files; then missing assets minus what is already partially downloaded) plus a 4 MiB margin are checked BEFORE anything is created; a full disk mid-write leaves no staging and does not touch the install. |

Mutation check: disabling the offset check and the discard-on-416 turned exactly the two matching tests red; restored afterwards. One existing fixture (`AssetDeliveryTest`) answered `206` without `Content-Range`, which RFC 9110 forbids for a single range; I made the fixture protocol-correct rather than relaxing the production check.
Not verified: https against a real CDN (cleartext lab server only), a real `ENOSPC` (simulated by a failing stream), device runs.

## 4. Security (C) - `SecurityFuzzTest`, `KeyRotationTest`, `SecretHygieneTest` (unit / JVM integration; local 105/105 for hostkit)

**Fuzz / property tests (fixed seeds).** About 6,000 mutations each of the manifest and of `assets.json` (bit flips, truncation, range delete / duplicate, junk insertion, numbers replaced by `1e999`, `NaN`, hex, `"7"`, `null`, `[]`; strings replaced by NUL, RLO, fullwidth dots, lone surrogates, 70 KB strings; nesting up to 30,000 levels). Properties: only `IllegalArgumentException` escapes; `peekKeyId` (which runs on unauthenticated bytes BEFORE the signature check) never throws; whatever parses satisfies the format invariants; a manifest that differs from the signed bytes by one byte never verifies (4,000 mutations); 3,000 random DER mutations and random signature texts never verify and never throw. No pre-authentication crash was found.

Defects found (red first) and fixed:
1. **Signature text spellings.** `TrustedKeys.verify` used the MIME base64 decoder, which silently discards junk, so a valid signature hidden among garbage verified. Now strict base64 (surrounding whitespace and line breaks tolerated; openssl wraps at 64).
2. **Duplicate JSON keys** in the manifest, in file entries, in `assets.json` and its entries were accepted (parsers disagree about which value wins). Now refused.
3. **`assets.json` had no size limit of its own** (the signed manifest allows a file up to 256 MB, which would be read into memory). Now 8 MiB, checked on the file length before reading.
4. libGDX JSON accessors can throw other runtime types for mismatched value kinds; both parsers now convert any runtime exception into their documented `IllegalArgumentException`.
Not changed on purpose: ECDSA signature malleability ((r,s) vs (r,n-s)): the signature value is never used as an identifier, hash input or cache key, so both forms verify the same signed bytes; rejecting high-S would reject half of all legitimate signatures.

**Key rotation, revocation, revokeFloor, adversarial staging (full store scenarios).** Two pinned keys both work; a manifest naming key A but signed by B is refused; old-host / new-host / later-host rotation never strands an install and never reopens the retired key; a pinned-but-revoked key invalidates what it signed with no soft lock (recovery, then a baseline signed by the new key restores play, stable across restarts); a `revokeFloor` makes the rollback target unusable so a bad release cannot fall back to a known-vulnerable one, and the old baseline is refused; 120 random adversarial stagings accept exactly the strictly-newer ones and leave no debris. All passed on first run (no defect found in these paths) - they are regression guards.

**Secrets hygiene (static scan of the checked-out tree, not of uploaded artifacts).** No private-key body anywhere; no workflow or lab script prints a private key file; the lab workflows are `contents: read`, use no repository secrets, never publish, generate their keys inside the run, and no artifact path can include a key file or the whole temp directory. Mutation-checked: a planted PEM body, a `cat lab.pem`, an artifact path `.../lab.pem` and an artifact path of the whole temp dir each turn a test red (the first version of the artifact-path check missed the planted path and was fixed).
Not verified: the contents of artifacts CI actually uploaded (the sandbox cannot download them), key rotation as an emulator scenario.

## 5. Update status model (E) - `UpdateStatus`, `UpdateStatusTest` (unit + JVM integration; local 110/110 for hostkit)

A pure immutable value with `headline(now)` (one line) and `lines(now)` (diagnostics), built by `UpdateStatus.capture(store, host, appName, downloaderOrNull)` from `ModuleStore.snapshot()` (taken under the store lock) and the downloader's new structured result (`ModuleDownloader.outcome` / `outcomeVersion` / `checkedAtMillis`; the human `status` string is unchanged). Shows host/app name + level, channel, active module version and whether it is confirmed, staged version, active asset-set id (order-independent hash of its path+sha256 list, "none" if it serves no files), last check result and age, last update result, rollback status, newest release ever accepted and revoke floor.

**Hard rule:** the headline contains "up to date" only if a check completed and found nothing newer, covered BOTH the module and its asset set, nothing is waiting, nothing was rolled back, and the version the check measured against is the version that is running; and it always adds that the app itself is not checked (the OTA never updates the installed app). Motivating defect found while building it: after a release is rolled back and blacklisted, `ModuleStore.preflight` (and so the downloader's text) says "up to date (v8)" while v1 runs; the status model reports "Rolled back ... running v1" instead.
Tests: the rule is checked exhaustively over all 8 component subsets x 5 check kinds x active/staged/rollback/version combinations (both directions: claimed iff true; diagnostics lines never make the claim); a real lifecycle (fresh -> staged -> running -> checked -> offline); the rolled-back v8 case; asset-set id stability. Mutation-checked: dropping the "measured against the running version" condition turns the exhaustive test red.
**Integration (E1):** show `capture(...).headline(System.currentTimeMillis())` and `lines(...)` in Settings/About; call `capture` on the thread that owns the store or any thread (the snapshot is synchronized); pass the `ModuleDownloader` instance that ran the check (or null). `UpdateStatus.Component.APP` exists so a future app-update check can be added without changing the rule.
Not verified: on a device, and the wording has not been reviewed by the owner.

## 6. Not yet done
key rotation / revocation as emulator scenarios, the device scenarios (kill during download/activation, 20+ release stress, real-game SaveGuard, combined code+asset release), and the asset-type/path-safety matrix from (A).
