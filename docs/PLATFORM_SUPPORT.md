# Platform support — Climb up

One authoritative source line (`ccr-cb458cd1-w4dh5c`), three platform targets, shared gameplay (studio standard §2/§4).

## Engine
libGDX 1.14.2 (Java/Kotlin, LWJGL3 desktop backend, Android backend). Code-only framework — every platform is the same
source compiled by Gradle. No engine-specific gameplay branches.

## Shared vs platform-specific
| Shared (module `core/`) | Platform-specific |
|---|---|
| Simulation & physics (`sim/Sim.java`), procedural generator + solver (`sim/CourseGenerator.java`), hazards, checkpoints, fall rule | Input adapters: `android/` touch controls; `desktop/` keyboard + Xbox/gamepad controller (`desktop/.../input/InputManager.java`) |
| Rendering & animation logic (`render/`), the hero rig & grip poses (`render/HeroRig.java`), camera | Window management / display modes / fullscreen (`desktop/` LWJGL3; Android activity) |
| Save schema & persistence (`SaveData`, `SaveStore`), settings | Filesystem save paths (Android app storage; desktop `~/.local/share/hotatticgames/upwardly`, Windows `%APPDATA%`) |
| Audio, UI, menus, accessibility | Packaging (APK via AGP; EXE via jpackage; Linux app-image via jpackage) |
| Core unit tests (`core/src/test`) | Update delivery (Android signed-OTA content; desktop = SteamPipe/full replacement) |

Platform is selected by the Gradle module and build flags — **never** by a different gameplay history.

## Targets
| Platform | Output | How built | Runtime | Status |
|---|---|---|---|---|
| **Android** | Installable APK (`com.hotatticgames.climbup`, signed) | `android.yml` (manual dispatch) → `:android:assembleDebug -PwithAndroid`, cert-gated to the installed Build-42 identity | Android | Built + published in CI; **physical phone test pending (owner)** |
| **Windows** | Self-contained EXE in a ZIP | `windows.yml` (manual dispatch) → `tools/windows/package.ps1` (jpackage app-image: EXE + jlink JRE + assets) | bundled JRE 17, no system Java | Built + smoke-tested in CI (headless + software-GL); **physical Windows test pending (owner)** |
| **Linux x86-64** | Native app-image in a TAR.GZ | `windows.yml` `linux` job (manual dispatch) → `tools/linux/package.sh` (jpackage: ELF launcher + jlink JRE + assets) | bundled JRE 17, no system Java | **Built + executed locally** (ELF launcher, GL first frame at 1280×800, clean exit, smoke PASS); **physical Steam Deck test pending (owner)** |

## Notes
- Linux is a **native** build (not Proton). Suitable for Steam Deck / Steam Machine; Proton is additional coverage, not a substitute.
- The desktop builds run at 1280×800 (Steam Deck native) and other aspect ratios; see `docs/STEAM_HARDWARE_CHECKLIST.md`.
- Public desktop name is "Upwardly" (window title / app-image name); the game's display title is "Climb up". This naming
  split is intentional-ish and a pending owner decision (see RELEASES.md).
