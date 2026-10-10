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

Observations NOT changed (design decisions for E1/owner):
- The harness only requires convergence after a SINGLE death before confirmation: two deaths plus the next launch is three unconfirmed launches, and the crash-loop rule then legitimately rolls back.
- With `state.json` and its backup both lost, the blacklist (`bad`) and the `restoring` marker are lost; only the anti-rollback floor is rebuilt.

Model limits: deaths are modelled BETWEEN steps; torn writes inside one syscall, power loss with an unflushed page cache, and storage-full are not injected (not verified).

## 3. Not yet done
B (download/installation hardening: Content-Range validation, https/redirect policy, size/time bounds), C (parser fuzzing, key rotation/revocation device scenarios), E (`UpdateStatus`), the device scenarios (kill during download/activation, 20+ release stress, real-game SaveGuard, combined code+asset release), and the asset-type/path-safety matrix from (A).
