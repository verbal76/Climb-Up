# Upwardly (Windows) - delivery report

Status: **engineering complete and automatically validated as far as this environment allows; NOT released, NOT published, nothing uploaded to Steam.** Physical-device checks are listed in section "Not verified" and still need the owner (or hardware).

Evidence is CI run 9 of `.github/workflows/windows.yml` (https://github.com/verbal76/Climb-Up/actions/runs/38020682825, head `2be53bb`) unless stated; later commits on the branch only add this report.

## 1. Agent delegation status
Delegated by the Android OTA engineer session (`session_01F57bWRDdZWBkp9cYKbBN1Q`) to an independent session with its own container, branch and task context. No production-release authority was granted or used.

## 2. Agent identity and assignment
Upwardly WINDOWS ENGINEER. Assignment: Windows desktop port and Steam preparation of the existing libGDX game, per the owner's directive (all 27 report items below).

## 3. Source baseline
Build 44, source SHA `9a9b9bea3f38d3211ad7be34cb9529adf676dd9f` (version 1.1.5), verified by `git log` of the detached checkout before branching. Two workflows existed (`android.yml`, `ota-publish.yml`), both publishing, both triggered only by pushes to `ccr-cb458cd1-w4dh5c` or manual dispatch.

## 4. Windows development branch
`exp/upwardly-windows` (draft PR #2 against `ccr-cb458cd1-w4dh5c`, labelled do-not-merge-without-owner).

## 5. Exact source SHA
Based on `9a9b9bea3f38d3211ad7be34cb9529adf676dd9f`. CI-validated head: `2be53bbb5aca6ab43f421e947c247dfc59567863`; the branch head is that plus this report commit.

## 6. Desktop architecture
See `ARCHITECTURE.md`. Shared `core/` unchanged in behaviour; `Platform`/`GameInput` seam (Android = `Platform.MOBILE`); `desktop/` holds the LWJGL3 launcher, input layer, settings tabs, bindings UI, packaging. Shared-code diff vs baseline: 8 files, +188/-38 lines in `core/src/main` (+1 new test). Simulation, physics, generation, camera, audio, rendering code: not touched.

## 7. Keyboard controls implemented
A/Left, D/Right move; W/Up, S/Down climb; Space jump; X/J swing club; Esc/P pause; menus W/S/A/D + arrows, Enter/Space confirm, Esc back. Fills the same `InputState` as touch (moveX/moveY/jumpHeld/jumpPressed/swingPressed); jump presses are latched until the next simulation step so jump buffering/coyote behaviour is untouched. Tests: `InputManagerTest` (keyboard mapping, held jump, tap shorter than a frame, pause/resume clearing, same-frame responsiveness). Scenario: keyboard-only title -> settings -> rebinding -> play -> pause.

## 8. Mouse controls implemented
Left click selects/activates menu buttons, hover moves the focus ring, wheel moves through menus; right/middle click do nothing in menus; the mouse never reaches the simulation (no touch processor on desktop). Scenario checks hover focus, right click ignored, left click activates (`mouse hover focuses CREDITS`, `right click does not activate`, `left click activates CREDITS`). Mouse buttons (not left) can be bound to gameplay actions.

## 9. Controller support implemented
gdx-controllers 2.2.4 (jamepad/SDL game-controller mappings), addressed by logical control (A/B/X/Y, bumpers, triggers, sticks, D-pad) never by driver button number. Defaults: left stick/D-pad move, A jump/confirm, B back, Start pause (Xbox "MENU", PlayStation "OPTIONS"), X/Square swing. Left stick uses the same response curve and dead-zone shape as the touch stick. Labels per family (Xbox, PlayStation Cross/Circle/Square/Triangle, Nintendo swapped positions, generic).

## 10. Automatic controller discovery results
Policy AUTO: keyboard/mouse always live, one active controller, prompts follow last meaningful use (65% stick push or button; 250 ms debounce; mouse jitter ignored). Unit tests (`InputManagerTest`): connect during play keeps the run and the held key; disconnect keeps keyboard and returns prompts; two controllers - last used wins, never summed; active one vanishing hands over to the next; drift never flips prompts. Scenario (CI, fake controller injected into the real game in a real window): `controller discovered during play without touching the run`, `prompts switched to the controller`, `controller removed mid-run: run intact and keyboard still active`. **Real hardware: not tested** (section "Not verified").

## 11. Custom keyboard binding results
Rebinding UI (Settings > CONTROLS > KEYBOARD + MOUSE BINDINGS): select slot -> waiting for input -> proposed binding with conflict message -> CONFIRM / TRY AGAIN / CANCEL -> saved. Up to 3 bindings per action, secondary bindings never silently erased, conflicts move the input only if the other action keeps one, last binding cannot be removed, left click and F11 reserved. Fixed menu recovery path (arrows, Enter, Esc; D-pad/A/B) works even if every menu binding is changed. Scenario: rebind Jump to K (memory + disk), conflicting A moved from Move Left, cancel changes nothing, RESTORE DEFAULTS (memory + disk).

## 12. Custom controller binding results
Same state machine (`Remapper`) for controllers: face/shoulder buttons, triggers, D-pad, stick half-axes with polarity ("L STICK LEFT"); the strongest new push names the axis and direction; profile stored per controller id with the shared defaults as fallback; a controller vanishing during remapping cancels without writing. Logic covered by `FocusAndRemapTest`/`InputManagerTest` (analog direction binding, per-controller profiles). The controller rebinding **screen** was exercised only in code review and unit tests of its model, not by the scenario (no pad connect + remap run in a real window), and **never with hardware**.

## 13. Binding persistence results
`upwardly-desktop.cfg` in the save folder; atomic write (temp + rename, previous good file kept as `.bak`); corrupt file kept as `.corrupt` and defaults used; `.bak` fallback; failed write leaves the old file byte-identical; bad lines/tokens ignored; hand-edited conflicts repaired; every key libGDX can name round-trips. 11 tests in `ConfigPersistenceTest` plus the scenario's on-disk checks. Settings persist separately from the shared `settings.json`/`save.json`, which keep their Android formats.

## 14. Desktop Settings implementation
Existing Settings screen extended through `Platform.settingsTabs()`: tabs GENERAL (existing audio/game), ACCESS, ABOUT, plus WINDOW (display mode, resolution) and CONTROLS (INPUT DEVICE, CONNECTED CONTROLLER, KEYBOARD + MOUSE BINDINGS, CONTROLLER BINDINGS, CONTROLLER DEAD ZONE, RESTORE DEFAULTS). Same visual style; usable keyboard-only, mouse-only, controller-only (spatial focus tested); reachable from the main menu and from a paused run (scenario `controls reachable from a paused run`). Touch-only rows (haptics, left-handed, high-contrast controls) and OTA rows are hidden on desktop.

## 15. Mobile-control hiding behavior
No touch overlay, no stick, no jump/swing buttons on desktop; the corner pause icon becomes a labelled key chip (e.g. "ESCAPE / PAUSE"); a faded hint strip (e.g. "A/D MOVE  SPACE JUMP  W/S CLIMB  ESCAPE PAUSE") shows for the first seconds and when the device changes; tip text is rewritten to the active prompts ("PRESS X"). Android unchanged: `PlatformSeamTest` pins `Platform.MOBILE` (touch controls on, original title, OTA allowed, tips untouched) and every desktop branch is behind `g.platform`.

## 16. Windows display-mode support
Windowed, fullscreen (F11, Alt+Enter, Settings), resolution list from the monitor's modes plus common sizes, resizable window (min 640x360) with the game's own ExtendViewport/fixed vertical field of view (wider windows show more width, as on a wide phone; framing and camera code untouched), high-DPI via libGDX logical mode. Scenario in a real window: resized to 1600x900, 1920x1080, 2560x1080 (21:9), 1024x768, 1280x720; fullscreen entered and left; screenshots inspected. Vertical sync on, frame pacing independent of the fixed simulation step.

## 17. Windows EXE packaging details
`tools/windows/package.ps1`: `:desktop:windowsJar` (shared game + LWJGL3 + Windows natives only) -> `jpackage --type app-image` -> `Upwardly.exe` (icon from the existing game icon, version `1.1.5.<build>`, vendor Hot Attic Games) -> `app/assets` -> checksums -> Steam layout -> zip. Build is reproducible from the repository (CI does it on a clean Windows runner). Window title/display name Upwardly.

## 18. Bundled runtime details
jlink'd Temurin 17 runtime inside the app folder (`java.base, java.desktop, java.logging, java.management, java.naming, java.xml, jdk.unsupported, jdk.crypto.ec`). CI proof: with `JAVA_HOME` cleared and every Java directory removed from PATH (`java on PATH: False`), `Upwardly.exe --smoke` exits 0 with `java.home=...\Upwardly\runtime`, assets from `app\assets`, saves in `%APPDATA%\HotAtticGames\Upwardly`, GLFW 3.4.0 initialised, SDL controller library loaded, 1200 simulation steps run. CI found and fixed a real missing-module startup crash (`EC KeyFactory not available`).

## 19. Steam distribution folder location
`build/windows/steam/content/` (identical to `build/windows/dist/Upwardly/`), built by CI; VDF templates with placeholder IDs in `build/windows/steam/scripts/` (source: `tools/windows/steam/`). See `STEAM_PACKAGING.md`. Launch executable: `Upwardly.exe`. Checksums: `SHA256SUMS.txt` (every file + the zip). Test artifact: workflow artifact `EXPERIMENTAL-Upwardly-win64-build-9` (48.6 MB, workflow artifact only, not a Release).

## 20. Automated test results
* Gameplay regression suite (`:core:test`, unmodified tests) passed on CI in runs 2-9 on this branch (Linux job step "Gameplay regression suite"); locally 130/130 before the one added test file; plus `PlatformSeamTest` (2 tests). No existing test or physics code was modified.
* Desktop unit tests: 84 tests, 0 failures (input mapping, mouse/menu navigation, controller mapping, discovery/disconnect, device switching, persistence, conflicts, restore defaults, menu recovery, asset resolution, display list, save folders, prompts).
* End-to-end scenario in a real window with software OpenGL (Xvfb on Linux, CI): 31 checks, all pass - startup, Upwardly title, keyboard-only navigation, mouse, rebinding, persistence, play, controller plug/unplug, pause, window resizes, fullscreen, save files, clean exit of the menu flow. Timing there: avg 17 ms/frame (software GL).
* Linux packaged-jar smoke and Windows EXE smoke: pass.
* Not run: lint/static analysis (none configured in the repository).

## 21. Windows runtime validation results
On a real Windows Server 2025 runner (CI run 9): package built; EXE ran with no Java installed on PATH (`--smoke` PASS); EXE launched with a software OpenGL driver (Mesa llvmpipe, CI-only helper, never shipped): window and GL context created, first frame 874 ms after launch, frames 11-600 averaged 19.8 ms (max 1436 ms, the shader/model warm-up), ran 30 s, `--exit-after` closed it, **exit code 0, `clean exit` logged**. Save folder `%APPDATA%\HotAtticGames\Upwardly` used (never the working directory).

## 22. Physical controller validation status
**Not validated on hardware.** No controller was available. Unit and scenario tests prove the logic and the real-window flow with a fake controller; they do not prove that a specific Xbox/PlayStation/Switch pad reports the mapping assumed (notably the stick-Y sign convention and trigger axes in `GdxPad`, based on SDL conventions). First owner test: plug in a pad, check movement/jump/pause, the prompt names, and remap one button; report anything backwards.

## 23. Remaining defects
Inherited gameplay defects listed in directive section 18 were not touched (shared code, separate workstream). Windows-specific known limits:
* Not tested on a GPU or a physical Windows desktop session: real vsync, real fullscreen (only Xvfb fullscreen), high-DPI scaling, multi-monitor, alt-tab behaviour (auto-pause on focus loss is implemented, unverified).
* Audio: the packaged runtime includes OpenAL via LWJGL, but the Windows runner has no sound device, so audible playback is **unverified** (the Linux scenario ran with audio disabled; the Windows run used the game's normal audio init, which falls back silently).
* The EXE is unsigned (SmartScreen/antivirus prompts possible outside Steam).
* Pause-menu subtitle line sits under the focus ring at 720p (cosmetic).
* Core suite duration on CI varies widely (4.5-35 min).
* Upwardly is used for the window title, executable metadata, title screen, credits heading and About line. I did not audit every other shared string for the old name; any remaining ones are display-only.

## 24. Files modified
61 files vs baseline: `.github/workflows/{android,ota-publish}.yml` (job-level branch guards only), `.github/workflows/windows.yml` (new), `build.gradle` (controllers version), `core/` (8 main files + `platform/` package + 1 test), `desktop/` (launcher, 20 classes, 8 test classes, icons, `build.gradle`), `tools/windows/`, `docs/windows/`. Full list: `git diff --stat 9a9b9bea..exp/upwardly-windows`.

## 25. Commit and push status
All work committed and pushed to `exp/upwardly-windows` only (never force-pushed). Draft PR #2 open for review. Workflow audit completed before the first push (section 26).

## 26. Confirmation that Android OTA work remains untouched
The OTA session's repository, worktree and branch were never accessed; commit `21ee43b` was neither seen nor needed; no unpublished OTA architecture was used. The Android module, signing configuration, package ids, save paths and OTA identifiers are unchanged (`git diff` shows no change under `android/`). The only edits to the two existing workflows are `if: github.ref == 'refs/heads/ccr-cb458cd1-w4dh5c'` on their jobs. Only `windows.yml` has run on this branch.

## 27. Confirmation that no production release or Steam publication occurred
No GitHub release, tag, Android APK, OTA publication, Steam upload, Steamworks app, credit assignment, store page or review submission. `windows.yml` has `permissions: contents: read`, uses no secrets, and only uploads workflow artifacts named EXPERIMENTAL. The Windows build makes no network calls (OTA never starts on desktop; `Platform.networkAllowed()` is false).

## Not verified (explicit list)
1. Any physical controller (Xbox, PlayStation, Switch, Steam Input virtual pad) and Steam Deck.
2. Rendering on a real GPU/monitor, real fullscreen, high-DPI, multi-monitor, vsync behaviour on Windows.
3. Audible audio on Windows.
4. Mouse and keyboard through a real Windows input stack (CI injected input through the input layer, not OS events).
5. The controller-bindings screen in a real window with a controller.
6. Installation from the zip on a clean consumer machine, SmartScreen/antivirus behaviour, Steam launch.
7. Windows startup time on real hardware (CI measured 0.87 s to first frame under software GL on a server VM).
