# Climb up

A 2.5D skill-platformer by **Hot Attic Games**: climb a spiral tower that doesn't exist. The camera is anchored on the player and the whole
course rotates around you as you climb. Ropes, cables, swinging and moving platforms, crumbling tiles, bounce pads and fingertip ledge grabs, in
an **endless**, procedurally generated (and solver-validated) tower that keeps getting harder. Timed sawblades, cannons that fire spiked balls, pop-up spikes, spiked stone blocks and slabs, angled springs, crabs that shove you, a floating spiked club, and coloured castles whose keys are hidden below the path. You play Quaternius' blue bunny-eared "Character", a skinned and animated 3D model. No upgrades: either you can make the jump or you can't.

Status: **candidate build 0.1.0** produced from the Climb Up v6 design package (see `design-package/`, `CLAUDE.md`). Android target; desktop build included for testing.

## Install (Android, sideload)
Every CI build publishes a **GitHub Release** (`v0.1.0-buildN`) with the APK attached: open the repo's *Releases* page on your phone and tap the APK.
The Android APK is built by GitHub Actions (`.github/workflows/android.yml`, run it manually: *Actions -> Android build -> Run workflow*). Download the
`climb-up-debug-apk-N` artifact, copy the `.apk` to a phone and open it (allow "install unknown apps"). Landscape only; Android 8.0+ (API 26), OpenGL ES 2.0.
Local APK builds need the Android SDK (`ANDROID_HOME`) and access to Google's Maven; the sandbox this was written in blocks `dl.google.com`, so the APK has **not** been built or installed here.

## Controls (touch)
* **Left half of the screen: floating stick.** Put a thumb down anywhere on the left; push left/right to run, **up/down to climb ropes, pull up from a ledge, or let go**.
* **Right half of the screen: JUMP.** Hold for a higher jump, tap for a hop. Jump is buffered and has coyote time. In the air the stick steers.
* Short of a ledge? Push toward it and you grab it with your fingertips; stick up or JUMP pulls you up. Ropes and cables are grabbed by jumping into them.
* **SWING** (appears above JUMP only while you carry the floating spiked club): knocks crabs off. Desktop: X or J.
* Pause button top-right. Settings (volume, text size, haptics, reduced motion, captions, left-handed layout, slower-speed and jump-forgiveness assists, tips on/off, erase data) are in the pause menu and on the title screen.
* Desktop testing keys: A/D or arrows move, W/S climb, Space/Z jump, Esc pause.

## Build and run on desktop
```
./gradlew :core:test                      # headless unit tests, generator + solver, persistence
./gradlew :desktop:run                    # run the game in a window (needs OpenGL; xvfb-run works for headless screenshots)
./gradlew :android:assembleDebug -PwithAndroid   # Android APK (needs the Android SDK)
```
Test hooks (system properties): `-Dclimb.demo=true` (autopilot plays), `-Dclimb.start=title|settings|credits|play`, `-Dclimb.seed=N -Dclimb.startHeight=M` (begin partway up), `-Dclimb.hazard=CANNON|SAW_V|SPIKE_TRAP|SPIKE_DROP|SPRING|GATE|KEY|CRAB|CLUB` (start beside the first one),
`-Dclimb.shots=DIR -Dclimb.shotCount=N` (numbered PNG screenshots), `-Dclimb.overlay=pause`, `-Dclimb.camDist=4.2` (close-up camera), `-Dclimb.w/-Dclimb.h`.

## Layout
`core/` game (sim = pure-Java rules, render = libGDX 3D, ui/audio/screens) · `desktop/` LWJGL3 launcher + content tools · `android/` launcher, manifest, icons ·
`assets/` models, the player model (hero), audio, data (`data/tuning.json` holds every movement/generator number), `pack/` (hazard, castle, key, crab and club models converted from the Quaternius pack) · `tools/` audio/icon generators · `docs/`.
Credits and licenses: `ASSETS.md`, `assets/licenses/`, in-game Credits screen.
