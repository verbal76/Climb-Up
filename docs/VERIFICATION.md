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

## Fixed after the first physical test (build 4)
* Tapping PLAY / SETTINGS / CREDITS on the title screen crashed the app: the screen change happened mid-frame and `render()` returned before `ui.end()`, so the next screen's `SpriteBatch.begin()` threw. Screen changes are now applied after the batch closes (title and play screens). Reproduced on desktop with scripted taps (`-Dclimb.taps=...`), then verified: title -> Settings -> back -> Credits -> back -> Play -> pause -> Settings -> back -> resume -> pause -> Main Menu, no exceptions. This was missed earlier because no automated run exercised the buttons.

* Decoys: all six towers (51-87 decoys each) still complete start to goal with decoys active; decoy structure/anchoring and 'dead end is not progress' covered by tests; tap sweep (14 taps across title/pause/settings/credits) clean after the juice pass.

## Endless climb (builds 17+)
* `:core:test` now also covers the endless tower (`EndlessTest`): 6 seeds x 10 slices are completed by the solver (including fetching every key and returning before each castle gate); slice = pure function of (seed, index, previous slice) and identical after a JSON save/restore; the tower grows and resumes from a saved slice; hazards are absent in the first 70 m, rise with height and all kinds appear; hazard hit sends you back with grace and never from another layer; cannon balls are pure functions of time; a gate is a wall until its key is carried and keys are one-use; every gate has a key room of the same colour that hangs off a route platform before it; crabs shove but never hurt and a club knocks them off.
* Stress runs (not unit tests): about 560 slices (33 km of tower) from 40 seeds, 173 castles with key rooms: 0 generation failures; solver failures were found and fixed one by one (spike trap lethal from other layers, ring-wide pull-up, platform-edge landings, key-room return tolerance, stale validation after later decoys). Final stress results are in the PR description.
* Desktop screenshots reviewed for: cannon + spiked ball, spike block, spike slab, saw, spring + arrow, castle in key colour, crab, floating club, endless HUD.
* Known weak spots: crab and key visuals are small at gameplay distance; the solver models perfect play, not human timing, so the hazards' timing windows (>= 14-20% of a cycle) are the fairness guard; nothing here has been played on a phone yet.

## NOT run / unknown
* **No APK was built, installed or run.** `dl.google.com` (Android SDK + AGP) is blocked in this sandbox. The Android module, manifest, icons, resources and CI workflow are untested; first CI run may need small fixes.
* No real touch input, haptics, audio output (no sound device), on-device frame rate, memory, thermals, notch/cutout behaviour, background/kill-and-relaunch on a phone.
* Human feel of movement/difficulty is unplayed - the solver proves solvability and tolerance, not fun. Expect tuning (`assets/data/tuning.json`, then `:desktop:genCourses`).
* Colorblind palettes, 16 KB page-size verification of natives, release signing: not done. Family-test OTA: implemented and unit-tested (OtaTest), see docs/OTA.md; not yet exercised on a phone, no APK built for it.

## Known issues / risks
* Player is the Quaternius Character: no dedicated rope-climb or hang animations (Run / Jump_Idle are reused); 29 bones need a GLES2 device with >= 128 vertex uniform vectors (virtually all phones).
* Kenney blocks are smooth/rounded rather than strictly "voxel-look"; robot, particles, clouds and font are voxel-styled.
* One-way platform tops mean you can jump up through blocks (reads oddly on thick blocks).
* Decor/trees can briefly overlap the robot when it stands beside them.

## Build 29 batch (seesaw/bridges, ramps, fall rule, rigged hamster, astronauts, space scenery)
Exercised on desktop (Xvfb screenshots + JUnit + solver stress), NOT on a phone:
- Unit tests: 44 passing (BridgeTest, RampTest added; fall-onto-lower-level test).
- Solver stress: about 1,500 endless slices across several runs; 1 solver failure per ~170 slices (plan from an arrival state), 0 generation failures. Finite whole-tower solver run fails about 1 in 30 seeds (test seeds moved to 300+).
- Screenshots: all six characters through every animation state; hamster on a rope/ledge; seesaw, bridge, all four ramp variants; planets, rocks and ship fly-bys at altitude; title screen with the hero picker.
- Not verified: audio dropout fix (cause not reproduced on desktop), anything on a real device, frame rate with the space scene on low-end phones (planet models ~250 KB each; the scene is skipped on the lowest graphics quality).

## Build 43 candidate: bounded memory, floating origin, new save format (desktop/headless only; nothing here ran on a phone)
Exercised (JUnit unless noted):
- `TowerStreamingTest` (7): history round trip; rebuilding the window with different origins never changes what the player does (27 rebuilds, worst divergence 1.7e-4 m); 40 drops from unloaded heights land exactly where a whole-tower oracle lands; passing below the lowest platform with the checkpoint's slice unloaded respawns correctly; resident slices <= 10, local heights < 1,600 m over 7.1 km, 1,052 B per stored slice; key/gate state survives unload/reload; an autopilot climbs a streaming tower and a fully resident one side by side through 106 rebuilds (63 forced, origins 0-1,500 m), staying within 1.5 mm for 11,000 steps until a controller decision legitimately flips.
- `ResumeTest`: save to disk, rebuild everything from the files, same checkpoint platform (height and arc), keys carried, castle 1 still open, then an extreme fall back to that checkpoint with keys kept. `PersistenceTest`/`LegacyTest`: old climbs become a legacy record plus one-time notice, settings and bests kept, history file survives a torn write.
- `FramedGenerationTest`, `EndlessTest`: framed slices through the codec are identical to their source and complete under the whole-course solver.
- Scripted measurement (not a unit test): tower generated to 100 km: jump apex 1.8500 m at 100,006 m (same as at ground level; with absolute floats it was already -0.3% at 100 km and broken at 10,000 km); history 1.45 MB at 90 km.
- Solver sweeps (not unit tests): 2,400 slices (seeds 200-259) and 1,600 slices (seeds 260-299) on the absolute chain; 1,200 framed slices through the codec (seeds 100-129). Failures found: 224/12, 209/17, 202/31, 259/24, 118/8(framed) - all solver or harness limitations, none a generation defect (docs/DECISIONS.md); all fixed and pinned by regression tests. The sweeps after the last fix were not rerun in full.
- Desktop launch under Xvfb at seed 5, 3,000 m: renders (HUD, castle, space scene), resumes a climb file; screenshot reviewed.
NOT verified: on-device frame time, memory and thermals; the Android crash itself (no device log was captured, the 2,513 m ramp crash is explained by a stale saved slice, see DECISIONS.md); renderer cleanup under long falls was checked only by code review and the desktop smoke run; a very long (hours) session; any rebuild-hitch measurement on a phone (a window rebuild measured on the desktop JVM: mean 0.9 ms, worst 4.4 ms over 200 rebuilds of a ~620-element window, i.e. perhaps 5-10x that on a phone, done on the render thread; slice generation (~0.46 s per slice on this machine, solver-validated) runs on a background thread).
