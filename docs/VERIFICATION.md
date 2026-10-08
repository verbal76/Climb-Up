# Verification (candidate 0.1.0) - what was actually run

## Run and passed (Linux sandbox, JDK 21, Gradle 8.14.3)
* `./gradlew :core:test` - 22 JUnit tests, all green:
  * Sim: jump apex vs physics, variable height, coyote + buffered jump, pad bounce, rope grab/climb/leap, ledge grab + pull-up, crumble fall/respawn,
    fall -> checkpoint -> immediately playable (no soft-lock), determinism + clone equality, 4 x 12,000-step random-input fuzz runs (no NaN, bounded, wrapped).
  * Courses: all 6 shipped towers (~320 elements, 520 m) are completed start-to-goal by the look-ahead `Autopilot` playing the real simulation
    (350-364 s of perfect play each); structure checks (checkpoints at least every 14 elements, every element type present across the bank, ascent, no
    layer clashes/shortcuts between revolutions); generator determinism per seed; 4 freshly generated seeds are valid, completable and different; sampled links all have a
    measurable tolerance (worst sampled margin 8% of the macro-input grid).
  * Persistence: round trip, corrupt save backed up + defaults, migrations from v0 and v1 fixtures, atomic write leaves no temp file, erase, no progression fields exist.
* Desktop game launched under Xvfb/Mesa (software GL): splash -> title -> play; 30 s autopilot-driven session from the real `PlayScreen` with no exceptions,
  checkpoint progress saved and reloaded. Screenshots reviewed for: title, settings (both pages, 100% and 160% text), credits, pause, summit overlays at 16:9,
  ~21:9, 4:3 and 20:9 aspect ratios, and in-game views of all four zones (ropes, cables, swings, pads, crumbling tiles, movers visible). Fixes made from that review:
  screenshot flip, cloud art, shadow blob, settings layout overflow, title layout, win panel width, camera framing, robot arm poses/yaw.
* `AndroidLauncher` compiles against gdx-backend-android 1.14.2 (checked with a stub android.jar).

## NOT run / unknown
* **No APK was built, installed or run.** `dl.google.com` (Android SDK + AGP) is blocked in this sandbox. The Android module, manifest, icons, resources and CI workflow are untested; first CI run may need small fixes.
* No real touch input, haptics, audio output (no sound device), on-device frame rate, memory, thermals, notch/cutout behaviour, background/kill-and-relaunch on a phone.
* Human feel of movement/difficulty is unplayed - the solver proves solvability and tolerance, not fun. Expect tuning (`assets/data/tuning.json`, then `:desktop:genCourses`).
* Colorblind palettes, over-the-air updates, 16 KB page-size verification of natives, release signing: not done.

## Known issues / risks
* Robot is a voxel extrusion of 2D sprite parts; at some angles the shading looks noisy. Face is readable; limbs are simple.
* Kenney blocks are smooth/rounded rather than strictly "voxel-look"; robot, particles, clouds and font are voxel-styled.
* One-way platform tops mean you can jump up through blocks (reads oddly on thick blocks).
* Decor/trees can briefly overlap the robot when it stands beside them.
