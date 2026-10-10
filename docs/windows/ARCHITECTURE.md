# Upwardly (Windows) - architecture

One game, several launchers. `core/` is the only place gameplay lives; the Windows build adds a launcher and input layer around it and does not copy or fork any simulation, physics or generation code.

```
core/     shared game (sim, generator, renderer, UI, audio, saves)   + platform/Platform, platform/GameInput (the seam)
android/  AndroidLauncher (unchanged)                                  uses Platform.MOBILE (original behaviour)
desktop/  DesktopLauncher (LWJGL3) + DesktopPlatform + input/*         the Windows build (also runs on Linux/macOS for development)
```

## The seam in core (the only shared-code changes)
* `platform/Platform` - what a launcher decides: touch overlay yes/no, network yes/no, display title, extra Settings tabs, hints. `Platform.MOBILE` answers exactly as the game always did, so Android is unchanged.
* `platform/GameInput` - desktop input reaches the simulation only by filling the same `InputState` the touch overlay fills (moveX, moveY, jumpHeld, jumpPressed, swingPressed). No physics, timing, buffering or coyote logic is touched.
* `ui/Ui.Navigator` - optional focus ring/hover navigation hook for the game's own buttons; `null` on Android, so every added line is skipped there.
* Guarded call sites: `PlayScreen` (input source, hints, pause control), `SettingsScreen` (extra tabs, OTA/touch rows hidden on desktop), `TitleScreen`/`CreditsScreen` (title text), `ClimbGame` (platform field, OTA never started on desktop).
* Regression proof for Android: `PlatformSeamTest` pins every `Platform.MOBILE` answer; the unmodified gameplay suite (`:core:test`) passes with the seam in place; the Android module is untouched.

## Desktop module
| piece | role |
|---|---|
| `DesktopLauncher` | window config (title Upwardly, vsync, icon, size limits), asset root (`app/assets` beside the jar), focus-loss auto pause, `--smoke` mode |
| `DesktopPlatform` | wires `InputManager`, `MenuNav`, display, hints, tabs; writes `desktop.log` (startup + frame pacing) |
| `input/Act, Ctl, Bindings, Defaults` | the action model: 13 actions, logical gamepad controls (never raw button numbers), up to 3 bindings per action, conflict rules per context (game vs menu) |
| `input/DesktopConfig` | settings file `upwardly-desktop.cfg` (bindings, per-controller profiles, dead zone, input mode, display); atomic write, `.bak` fallback, corrupt file kept as `.corrupt` |
| `input/InputManager` | per-frame polling of keyboard, mouse and the active controller -> actions; device tracking for prompts; menu auto-repeat |
| `input/MenuNav, FocusModel` | spatial focus for keyboard/controller/mouse-hover; wheel moves focus; confirm lock for freshly shown menus |
| `input/Remapper` | rebinding state machine (waiting -> proposed -> confirm/cancel; conflicts; no orphaned action) |
| `input/GdxSources, GdxPad` | the only code that touches libGDX input and gdx-controllers (jamepad/SDL mapping), by logical control |
| `BindingsScreen, DesktopTabs` | rebinding UI, WINDOW and CONTROLS Settings tabs, built from the game's own buttons |
| `DisplayManager, DataDirs, AssetFiles` | windowed/fullscreen/resolutions, `%APPDATA%\HotAtticGames\Upwardly`, asset root resolution |

## Data flow
```
keyboard / mouse / controller -> GdxSources/GdxPad -> InputManager (bindings, dead zone, device policy)
   -> GameInput.read(InputState) -> Sim.step (unchanged)          [gameplay]
   -> MenuNav focus + activation -> Ui.button return values        [menus]
```
Keyboard, mouse and one active controller are always live together. The "device" (keyboard or controller) only decides which button names are shown.

## Files the Windows build owns on disk (`%APPDATA%\HotAtticGames\Upwardly`)
`save.json`, `settings.json`, `history.bin` (shared formats, identical to Android's), `upwardly-desktop.cfg` (+ `.bak`), `desktop.log`. Never the working directory.

## What is deliberately absent
No OTA/network code runs (`Platform.networkAllowed()` is false: no check, no staged-content read), no Steamworks API, no achievements/cloud/leaderboards.
