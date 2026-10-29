# Upwardly Demo

Owner directive: "UPWARDLY — MASTER DEMO DEVELOPMENT DIRECTIVE", 28 October 2026. This file describes how the demo works, what it guarantees,
and how to make the same demo from a later build (Build 83 and on).

## What the player gets

1. Studio splash, then the demo menu: PLAY (or CONTINUE: <stage>), SETTINGS, BEST TIMES, CREDITS, EXIT. There's no difficulty choice, no course
   choice, no players, no legacy runs and no hidden menus.
2. PLAY fades to black. A centred title card reads NORMAL DIFFICULTY: it fades in, holds and fades out. The stage then fades in from black.
3. Each stage starts just past castle 2's door (1,000 m) and ends when the player walks through castle 3's door (1,500 m). Every stage is shown in
   Dusk Spire.
4. When a stage is cleared, a panel shows the time (plus NEW BEST, BEST or ASSISTED) and CONTINUE. CONTINUE also happens by itself after 9 s. The
   game then fades to black and the next card appears: PUNISHER DIFFICULTY, then NOT GONNA HAPPEN DIFFICULTY.
5. After the third stage comes the promotional screen:
   - UPWARDLY / WISHLIST NOW ON STEAM / AVAILABLE NOVEMBER 2026;
   - the official Steam logo, once the owner supplies it;
   - WISHLIST ON STEAM (the store page), PLAY DEMO AGAIN (back to NORMAL), and MAIN MENU.
6. The pause menu offers:
   - RESUME;
   - RETRY CHECKPOINT;
   - RESTART STAGE (this stage only, from its beginning, straight away);
   - SETTINGS;
   - MAIN MENU.

   A fall goes back to the last checkpoint, exactly as in the full game. Leaving mid-stage and coming back restarts that stage, never an earlier one.

## How it is built: one layer on top of an approved build

The demo is a layer of additions on top of the full game's code. Gameplay, physics, enemies, the generator and rendering are the source build's own.

**Build-time identity.**
- `-Pclimb.edition=demo` makes Gradle generate `EditionInfo` with `DEMO = true`, the demo revision, the source commit and the build number
  (`core/build.gradle`, `Edition`).
- No launch option or save can turn a full build into the demo or the demo into the full game.
- `-Dclimb.demo` is still only the old attract-mode autopilot switch, and the demo build ignores it.

**Recipe (`assets/demo/recipe.json`, `DemoRecipe`).** Everything the demo says and plays comes from here:
- the stage order;
- each stage's difficulty, seed, title card text and course file with its SHA-256;
- the section (castle 2 to castle 3);
- the world (2 = Dusk Spire);
- the card timings;
- the promo lines, the store URL and the Steam logo file.

The recipe is refused unless all of the following hold:
- there are exactly three stages, in the order NORMAL, PUNISHER, NOT GONNA HAPPEN;
- the section runs from one castle to the next;
- the store URL, if set, is a Steam store page.

**Baked courses (`assets/demo/stage-*.course`, `DemoCourse`, tool `DemoBake`).**
- Each file holds every slice of the tower on its seed, from the bottom to a little above castle 3, exactly as the real generator made it through
  the real Tower.
- Each file carries stamps: the seed, the difficulty, the generation rules version, the tuning stamp and the castles.
- On load, the game checks every stamp and the recipe's SHA-256. Any mismatch stops the demo with the reason shown in the menu. It never plays
  another course or silently falls back.
- The tuning stamp is a CRC of every tuning number by field name and exact bits, so it is the same on Android and the desktop.
- Courses in the demo:

| Stage | Seed | Course SHA-256 (first 12 characters) |
|---|---|---|
| NORMAL | 4104 | 800c556024d4 |
| PUNISHER | 4105 | in `recipe.json` |
| NOT GONNA HAPPEN | 4103 | in `recipe.json` |

- The demo workflow re-makes every slice with the build's own generator and requires the result to be byte-identical (`DemoBake verify`).

**Flow (`DemoFlow`, `DemoCardScreen`, `DemoTitleScreen`, `DemoPromoScreen`, `PlayScreen` stage hooks).**
- Each stage is a fresh tower from its baked course. The tower is sealed (`Tower.sealed`: it never makes a slice), so nothing above the course
  can ever exist.
- The stage uses a fresh simulation, HUD and clock. No enemies, projectiles, keys, club or checkpoints carry over between stages. The character
  and settings do.
- The start uses the world picker's mid-tower start (`afterCastle(2)`). The finish uses the configurable finish castle: the simulation's tuning
  is the difficulty's own with `finishCastle = 3`. The generator never reads `finishCastle`.
- A stage floor 4 m under castle 2's deck works like the bottom of the tower: falling past it sends the player back to the checkpoint, so castle
  1 to 2 is never playable.
- The next stage's tower is built while the clear panel and the title card show.

**Dusk Spire (presentation only).** `WorldRenderer.zoneOverride` sets the sky, light, platform skins and hanging rocks. `Cues.zoneOverride` sets
the footsteps, and the HUD banner and ambience also use Dusk Spire. What the generator built for 1,000-1,500 m (the Frost Ridge band) plays
exactly as generated: its wet, slippery platforms stay slippery and stay visible as puddles.

**Records (`DemoRecords`, `demo.json` in the demo's own data folder).**
- The demo keeps a best time per difficulty, counted in simulation steps (the run clock). Title cards, loading, pauses and holds never count.
- Each best is stored with its seed and course fingerprint. A best set on another course or generator is never compared.
- Assisted times are shown but never become bests.
- The demo also keeps the best time for all three stages in one go.
- A stage is reached only by clearing the ones before it, so an edited file cannot skip ahead.

**Locked out.** The demo enforces all of this in code, not by hiding buttons:
- hidden world picker, determinism check and version-tap;
- difficulty setting and SHOW COURSE SEED;
- autopilot, probes and every developer launch option. `Edition.scrubLaunchOptions` clears every `climb.*` property except where files go and
  the screenshot harness. `Edition.devProp` reads nothing in the demo.
- OTA (none at all: no `ota` folder, no network, and no INTERNET permission on Android);
- free climbs (`ClimbGame.openRun` throws in the demo, and `PlayScreen` refuses anything but a demo stage);
- Steam start-up under the full game's App ID, and achievements.

**Isolation.**
- Android: package `com.hotatticgames.climbup.demo`, labelled "Upwardly Demo". It has its own data and installs beside the full game. It is
  signed with the studio's identity (same key rotation), so later demo builds update it in place.
- Windows: `%APPDATA%\HotAtticGames\Upwardly Demo`, with `Upwardly Demo.exe`.
- Linux / Steam Deck: `$XDG_DATA_HOME/HotAtticGames/Upwardly Demo`, with `bin/Upwardly Demo`.

## The demo workflow (`.github/workflows/demo.yml`)

Run it by hand from a `demo/**` branch. The full game's `android.yml` and `windows.yml`, and their branch guards, are unchanged.

**Inputs:**
- `source_commit`: the approved full-game commit. It must be in the branch's history.
- `recipe_version`: must equal the recipe's.
- `demo_build_number`: the Android versionCode and the package names.
- `demo_revision`.
- Optional `promo_line1` / `promo_line2`, for release messaging. Course hashes are unaffected.

**Jobs:**
1. **validate:**
   - provenance;
   - `DemoTest` in both editions, plus targeted full-game tests;
   - `DemoBake verify` (byte-identical courses);
   - the slice solver over every slice of each stage's section.
2. **android:**
   - signed APK;
   - checks: package, label, versionCode, no INTERNET, not debuggable, studio certificate, courses inside;
   - on an emulator: installed beside the full game, launched, still running after 30 s, screenshots.
3. **windows:**
   - self-contained `Upwardly Demo` app-image and zip;
   - `--smoke` with no Java on PATH (demo identity, its own save folder, every course checked);
   - a 40 s launch on software OpenGL.
4. **linux:**
   - native app-image and tar.gz;
   - `--smoke`;
   - a virtual-display run that captures every demo screen at 1280x720, 1280x800 (Steam Deck) and 2400x1080 (`tools/ci/demo_visual.sh`).
5. **draft:** creates a private draft release only, never published, holding:
   - the APK, the EXE zip and the Linux tar.gz;
   - `demo-build-manifest.json`: source and demo commits, demo revision, recipe, generator fingerprint, seeds, package names, checksums,
     validation evidence;
   - `checksums.sha256`;
   - review screenshots;
   - a SteamPipe layout zip: Windows and Linux depot content, plus `tools/steam-demo` build scripts with `REPLACE_WITH_DEMO_APP_ID`
     placeholders.

**What it never does:**
- publish a release;
- upload to Steam;
- touch the full game's releases, OTA channel or depots.

## Making the demo from Build 83 (or any approved build)

1. **Branch.** Take the approved full-game commit. Branch `demo/build-83` from it, then merge the demo layer from the previous demo branch
   (`git merge demo/build-82`) and resolve conflicts:
   - Keep the new build's gameplay, generator and rendering.
   - Only the demo hooks are carried. Never cherry-pick old generator or physics code.
2. **Hooks.** The hooks to check after the merge (each is small and marked "the demo"):
   - `ClimbGame`: create, runTuning, mainMenu, openRun, versionLabel, eraseEverything.
   - `PlayScreen`: stage constructor, show guard, floor, finish, pause menu, HUD.
   - `SettingsScreen`: About, rows.
   - `WorldRenderer.zoneOverride`, `Cues.zoneOverride` and `Tower.sealed`.
   - `DesktopPlatform` / `DataDirs` / `DesktopLauncher`, and `android/build.gradle` plus `src/demo/AndroidManifest.xml`.
   - The packaging scripts' edition switch.
3. **Courses.** A new generator makes new courses, so the old ones must not be reused (the stamps refuse them anyway).
   - Run `.github/workflows/demo-courses.yml` on the new branch over candidate seeds.
   - Pick a representative seed per difficulty: highest score, every slice solved, the mode's signature threats.
   - Put the seeds in `recipe.json`, bump `recipe`, and bake:
     `cd core && java -cp build/classes/java/main:build/classes/java/test:<gdx.jar> com.hotatticgames.climbup.DemoBake bake ../assets/data/tuning.json ../assets/demo/recipe.json ../assets`.
   - Commit the course files and the recipe.
4. **Tests.** Run `DemoTest` in both editions (`./gradlew :core:test --tests '*DemoTest' -Pclimb.edition=demo`).
5. **Build.** Dispatch `demo.yml` with the new `source_commit`, recipe version, demo build number (higher than the last, so the installed demo
   updates) and demo revision.
6. **Review.** Inspect the draft: screenshots, manifest, packages. Steam upload is the owner's step, with the demo's own App ID.

## Owner items (not invented here)

- **The official Steam logo.** Valve's branding asset, used under Steam's brand guidelines. The promo screen draws it when
  `promo.steamLogo` names a file in `assets/` and never draws an imitation; until then the screen shows the text lines only.
- **The Steam Demo App ID and its depots.** These are placeholders in `tools/steam-demo/*.vdf`. The desktop demo does not start Steam at all until
  that ID exists, because it must never run as App 5427130.
- **The store link.** It is `https://store.steampowered.com/app/5427130/`, the full game's store page under the App ID confirmed in
  `docs/STEAMWORKS_SETUP.md` §1. It is one line in `recipe.json`.
