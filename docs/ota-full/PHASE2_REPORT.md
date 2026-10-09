# Phase 2 report — isolated feasibility prototype (synthetic module)

Source: branch `exp/ota-full` (local), based on `9a9b9be` (= published Build 44). **Nothing pushed, published or CI-triggered. `core/` and `android/` are byte-identical to Build 44** (`git diff 9a9b9be -- core android` is empty).

## 1. Implemented
| Piece | Where | Notes |
|---|---|---|
| Host/game contract | `modspi/` | 2 interfaces, see INTERFACE.md |
| Verifier, trust, manifest, store, boot glue, downloader | `hostkit/` (pure Java + gdx JSON; no Android classes) | rollback / crash-loop / anti-rollback / revocation / climb gate / baseline reinstall |
| Synthetic proof module | `modsynthetic/` | modes `ok`, `throwCreate`, `crashRender`; renders a colour keyed to its version, reads a packaged file, logs lifecycle and touch |
| Android lab host | `otalab/` (application id `com.hotatticgames.climbup.otalab`) | `DexClassLoader`, read-only dex, recovery screen, update check; cannot replace the installed game |
| Bundle tool | `tools/otalab/make_module.py` | patches module, D8 to `module.dex`, hashes, signs with openssl; refuses a jar that carries gdx/SPI classes; can emit deliberately tampered fixtures |
| Emulator scenario suite + workflow | `tools/otalab/emulator_test.sh`, `.github/workflows/otalab.yml` | triggers only on `exp/ota-full` / manual; `contents: read`; throw-away key generated inside the run; uploads artifacts only |

## 2. Existing code moved / modified
None. (New modules only; `settings.gradle` gains includes; `.gitignore` gains lab-key guards.)

## 3. Evidence by level (honest)
| Level | What | Result |
|---|---|---|
| Unit (JVM) | `hostkit` 36 tests: signature (forged, edited, unsigned, other key, key-id spoof), hashes (flip, truncate, extra/missing file), identity (app, channel, interface, host range), revoked/rotated keys, 14 malformed-manifest variants, staging atomicity, activation only at cold start, interrupted download discarded, anti-rollback, crash-loop rollback + blacklist + recovery, safety net never deleted, corrupt-at-rest, corrupt state file, baseline reinstall, climb/save gate, revokeFloor, repeated updates, real class loading via `URLClassLoader` incl. create-failure and wrong-entry fallback | **36/36 pass** |
| Mutation check | broke the hash check, the signature check, and the crash-loop limit one at a time | each made the suite fail; restored; suite green |
| Integration (JVM, local HTTP server) | download → verify while streaming → stage; flipped byte, truncated transfer, oversize, forged signature, edited manifest, older/rolled-back release, server offline, cleartext refused | pass (inside the 36) |
| Tool cross-check | bundles built by `make_module.py` (openssl) verified by the Java host verifier; tampered dex/manifest/signature, wrong key, wrong app rejected | pass (run locally) |
| Compile check | `otalab` Java type-checked with `javac` against the real API 34 framework (`android-all`) and the real `gdx-backend-android` 1.14.2 | compiles |
| Emulator (ART, API 34) | scenarios S1–S9 in `emulator_test.sh` | **NOT RUN YET** — needs the workflow to run on GitHub (no SDK/KVM in this sandbox; `dl.google.com` is blocked here) |
| Physical device (Pixel 10 Pro XL, Android 17) | — | **NOT RUN** |

## 4. The 18 Phase-2 proof points
| # | Requirement | Status |
|---|---|---|
| 1 | small host starts | written; emulator S1 pending |
| 2 | loads a separately packaged module | `DexClassLoader` path written + type-checked; pending S1 |
| 3 | authenticated before execution | **unit-tested** |
| 4 | module uses the libGDX interfaces | pending S1 (class-loader and `Gdx` identity are logged and asserted) |
| 5 | lifecycle reaches the module | by construction (stock backend drives the module's listener); pending S2 |
| 6 | renders in the existing graphics environment | pending S1 pixel check |
| 7 | receives input | pending S2 |
| 8 | loads packaged assets | module-dir file read pending S1; APK-asset delivery is Phase 5 |
| 9 | downloads a newer signed module | **integration-tested (JVM)**; pending S3 on device |
| 10 | activates after restart only | **unit-tested**; pending S3/S4 |
| 11 | broken module cannot prevent startup | **unit-tested** (load failure, create failure, crash loop, corruption, no state); pending S6–S9 |
| 12 | restores previous working module | **unit-tested**; pending S6/S7/S9 |
| 13 | works on owner's Android version | **not tested** (Android 17 / Pixel needs a device) |
| 14 | Android 14+ downloaded-code rules | read-only dex implemented; pending S1 on API 34 |
| 15 | no privileged permissions | `INTERNET` only (manifest) |
| 16 | no root | by design (`run-as` is used only by the CI test on a debuggable test build) |
| 17 | no simulation rewrite | **true by audit**: `core/` unchanged; module = `core/` + a ~10-line factory |
| 18 | store build from same source | design only (store host links `core/` directly; no loader in that APK) — Phase 8 |

## 5. Gameplay / determinism / rendering / performance / saves
Not applicable yet: Climb Up is not migrated. Its source, tests (130/130 at the baseline) and assets are untouched; the shipped Build 44 is unaffected. No performance numbers exist for the new architecture (`BASELINE.md` §5).

## 6. Risks found
1. Raw `.dex` via `DexClassLoader` is expected to work on API 26+; if S1 shows otherwise, fall back to a jar containing `classes.dex` (same security model).
2. Android 17: the CI emulator matrix should add the newest API image as soon as available; Pixel test remains the real answer.
3. Class-loader disposal: a loaded module cannot be unloaded; activation is cold-start only (as required), which sidesteps it.
4. `Tower.POOL` (a never-shut-down daemon executor) lives in module classes; fine because the whole process restarts for any module switch.
5. Save-schema rollback rule not yet implemented (INTERFACE.md).
6. If `state.json` is lost, the anti-rollback counter resets to the APK baseline. Acceptable for sideload; flagged for Phase 6 (derive `highest` from verified directories).
7. First-run cost of ART verifying a ~7 MB game dex has not been measured; the install-time `dex2oat` cannot run on a downloaded module (it will be JIT/verified at load). Phase 7 must measure cold start; if it is materially slower the mitigation is a background `cmd package compile`-style warm-up or shipping the baseline inside the APK, never changing game code.

## 7. Next
Run `otalab.yml` (needs your OK to push `exp/ota-full`), fix whatever ART/CI shows, then Phase 3 (Climb Up factory module + `ClimbGame` build as dex) and the Phase 4 deterministic replay digest.
