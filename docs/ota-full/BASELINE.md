# Full-game OTA — Phase 1 audit and permanent baseline

Branch `exp/ota-full` (local worktree `/home/user/climb-up-otafull`), created from the published source. Nothing in this branch is published, pushed or CI-triggering.
The release branch `ccr-cb458cd1-w4dh5c` and the published Build 44 are untouched.

## 1. Release state (verified against GitHub, not assumed)

| Item | Value |
|---|---|
| Latest published Android build | Build 44, `v1.1.5-build44`, published 2026-10-09T22:23:31Z |
| Source SHA of that build | `9a9b9bea3f38d3211ad7be34cb9529adf676dd9f` |
| APK | `climb-up-1.1.5-build44-debug.apk`, 21,364,457 bytes |
| APK SHA-256 | `64813b4a17573602183fd9f1dc5c9911a04cdda8c29cf9687f6d96e85e34e213` (GitHub asset digest = release notes) |
| Signing certificate SHA-256 | `CE:DD:D6:86:8B:F9:4C:30:B2:70:F3:59:01:88:66:95:EE:6A:E9:AB:95:51:19:6C:2C:A9:E2:99:50:04:1C:39` (identity of installed Build 42 and builds 37–44) |
| versionName / versionCode | 1.1.5 / 44 |
| Package | `com.hotatticgames.climbup`, minSdk 26, targetSdk 35, compileSdk 35 |
| Existing OTA | signed manifest, runtime 2, schema 2, bundled content v1, payload = `tuning.json` only (docs/OTA.md) |
| Authoritative branch | `ccr-cb458cd1-w4dh5c` (the only branch whose pushes run `android.yml`, which builds AND publishes a release) |

Reconciliation of the earlier work:

| Work | State |
|---|---|
| Platform-edge catch fix + generator gem fix + notice-box fix | Published (Build 44, `9a9b9be`). Validated by the unit suite. **Awaiting physical testing** on the phone. |
| Title screen: LEGACY RUNS moved so CREDITS is not hidden (`21ee43b`) | **Committed locally only, not pushed, not in any published build.** UI-only (no simulation code). Full suite passed on it. Deliberately NOT part of this branch's baseline. |
| Uncommitted changes | none (clean tree at audit time) |
| Branch hygiene | `verify-secrets` remote branch still exists (owner deletes it); backup/* branches preserved |

The experimental branch is based on `9a9b9be`, the exact source of the published APK, so every equivalence comparison is against what is installed.

## 2. Toolchain

libGDX 1.14.2 (gdx core; gdx-backend-android; natives armeabi-v7a, arm64-v8a, x86_64 `.so` extracted into `android/libs`), Gradle 8.14.3 wrapper, AGP 8.7.3, Java 17 release target (JDK 21 locally, Temurin 17 in CI), androidx.core forced to 1.15.0. No game-owned native code. Native libs come only from gdx-platform.

## 3. Host/game coupling (the real boundary)

* `android/` is **one class**, `AndroidLauncher extends AndroidApplication`. It sets `ClimbGame.appBuild` (a static int) and calls `initialize(new ClimbGame(getFilesDir()), config)`. That is the entire coupling from the Android side.
* `core/` (72 classes, ~7,600 lines) depends only on `com.badlogicgames.gdx:gdx`. `ClimbGame extends Game` is the `ApplicationListener`. Sim, Tower, generator, renderer, audio, UI, saves and the existing data-OTA client all live in `core/`.
* All asset access is `Gdx.files.internal(...)` (8 call sites: Models, HeroRig, Audio, SplashScreen, ClimbGame tuning). No `AssetManager`, no classpath resources, no reflection, no `ClassLoader` use.
* Persistent state is under `getFilesDir()` (`SaveStore`, `HistoryStore`, `ota/`), passed in by the launcher; the module does not need Android APIs for it.
* Mutable statics: `ClimbGame.appBuild`, `Autopilot.why` (debug string), `Tower.POOL` (daemon single-thread executor `tower-gen`, never shut down). Threads: `tower-gen`, `ota-check` (daemon).
* Assets total 29 MB / 253 files (inventory in `asset-inventory.sha256`).

Consequence: the smallest viable host/game split needs **no gameplay source changes**. The game module is `core/` as it is, plus a ~10-line factory class; the host is `AndroidLauncher` plus loader/verifier/rollback.

## 4. Technical blockers and risks found in the audit

1. **No Android SDK or D8/R8 in the build sandbox** (`dl.google.com` returns 403 through the proxy; `maven.google.com` redirects to it). Dex conversion, APK assembly and anything that needs ART can only run in GitHub Actions. This sandbox can test: signing/verification, staging, rollback and crash-loop logic, the module contract, and class loading through a JVM class loader (an analogue, NOT evidence for `DexClassLoader`).
2. **Android 14+ rejects writable dex** (`SecurityException` unless the file is read-only) — the loader must `setReadOnly()` the verified file in app-private storage. Must be proven on a real ART (CI emulator, then the Pixel).
3. **libGDX must exist exactly once.** The module build must compile `gdx` as `compileOnly` and the module dex must not contain `com.badlogic.gdx.*`; the loader's parent is the host class loader. A duplicate-class check is a CI gate.
4. **`Gdx.files.internal` reads the APK.** Downloaded assets cannot be reached that way. Plan (Phase 5): the host installs a thin `Files` wrapper whose `internal(path)` returns a verified downloaded file when the active asset manifest owns the path, else the unchanged APK handle. No game call site changes.
5. **Save/world compatibility.** Saves are v6; climbs are stored as generated slice blobs (`history.bin`), so a resumed climb does not re-run the generator for the saved part, but *future* slices do. A module whose generator or Sim differs changes the unseen part of an in-progress climb. Hence each module declares `saveSchema` and `generatorRuleset` and activation is refused when they differ from the active module while a climb is in progress (Phase 2 contract, enforced by tests).
6. **Existing data-OTA coexistence.** The `tuning.json` OTA lives inside `core/` and is part of the game. It is carried into the module unchanged. The signed-module channel is a separate, host-owned channel with its own key id; the two never share files.
7. **Google Play.** Play forbids downloading executable code. The store edition must compile the same `core/` into the APK and exclude the loader (build flavour, Phase 8). The sideload edition uses a distinct application id so it cannot overwrite the installed game.

## 5. Baseline evidence

| Item | Value |
|---|---|
| Source commit | `9a9b9bea3f38d3211ad7be34cb9529adf676dd9f` |
| Save format | `SaveData.CURRENT_VERSION = 6`; history file `history.bin` |
| Asset inventory | `docs/ota-full/asset-inventory.sha256` (253 files); SHA-256 of the inventory file: `0ba6d41aa788f7ec30c86e9e2bd6c01248e7007d02f5bf294547c53d45be07de` |
| Native dependencies | gdx 1.14.2 natives for armeabi-v7a, arm64-v8a, x86_64 (the only `.so` files shipped) |
| Simulation / generator / persistence / OTA tests | see section 6 (run on this exact SHA) |
| Performance measurements | **NOT MEASURED.** No device or emulator is available in this environment. Frame-time, latency, memory, cold-start and thermal numbers must come from the Pixel 10 Pro XL. A measurement protocol is in `docs/ota-full/PERF_PROTOCOL.md` (Phase 7). Nothing is claimed. |

## 6. Test results at the baseline SHA

`./gradlew :core:test --rerun-tasks` at `9a9b9be` in this worktree (JDK 21, headless): **BUILD SUCCESSFUL, 27 suites, 130 tests, 0 failed, 0 skipped, 0 errors** (10 m 56 s).
Covers the simulation (movement, edges, hazards, bridges, falls), the streaming tower and floating origin, autopilot-solved generation over many seeds (endless + framed), save/resume/history, persistence, and the existing signed data-OTA (protocol, end-to-end over local HTTP, tool cross-check, config drift).
These are the numbers the module-loaded build must reproduce exactly (Phase 4 adds a deterministic replay digest on top; see EQUIVALENCE.md when written).
