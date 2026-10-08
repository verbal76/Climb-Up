# Climb up - AUTHORITATIVE BUILD SPECIFICATION

Spec version 6 (Reevaluation) - generated 2026-10-08 by Game Designer. This file supersedes earlier versions; earlier versions are preserved in the Game Designer project history and must not be re-derived from memory.

## 0. Authority and how to use this file

This file is the authoritative specification for **Climb up**. Read it completely before making decisions. Preserve the owner's intent over convenience. If repository reality conflicts with this document, investigate, preserve recoverable working state, and take the safest path that satisfies this specification. Never silently reduce scope to a prototype, toy, mockup or gray-box. Where this file is explicit, follow it; where it is silent on a non-creative engineering detail, make the best reversible decision and record it in `docs/DECISIONS.md`.

The owner is Kevin. Creative and product decisions belong to the owner; engineering decisions belong to you.

# PART A - OWNER REQUIREMENTS (authoritative: implement exactly; nothing in Part B or C may contradict this)

## A1. Owner vision - the original concept, in their own words

<!--verbatim-->
> I want to make a game like up where you just keep climbing but I want it to be a 2.5d platformer that feels like it's built around a cylinder because it's like kind of round but you should be able to jump across and catch other things. The cylinder that it's a supposedly built around doesn't really exist. I just want that look so as you're climbing the whole spiral platform rotates so you are always centered in the screen. You are the camera anchor. I want it to be a platformer. We had to make jumps. I want trampoline pads. I want moving levels, moving platforms up and down left and right. I want ropes to climb. I want swinging platforms. I want platform sections themselves to move
<!--/verbatim-->

## A2. Owner requirements

- I want to make a game like up where you just keep climbing but I want it to be a 2.5d platformer that feels like it's built around a cylinder because it's like kind of round but you should be able to jump across and catch other things.
- The cylinder that it's a supposedly built around doesn't really exist.
- I just want that look so as you're climbing the whole spiral platform rotates so you are always centered in the screen.
- You are the camera anchor.
- I want it to be a platformer.
- We had to make jumps.
- I want trampoline pads.
- I want moving levels, moving platforms up and down left and right.
- I want ropes to climb.
- I want swinging platforms.
- I want platform sections themselves to move
- The game is like up where you just keep climbing
- The game is a 2.5d platformer
- The game feels like it's built around a cylinder
- The cylinder doesn't really exist
- The spiral platform rotates so the player is always centered in the screen
- The game includes trampoline pads
- One great 5-minute Play loop would be able to make all of these risky, sketchy jumps where the platform was too far away and you just barely made it.
- Or you climbed the rope just in time to catch the moving platform or the crumbling platformers you just ran across to stay together just long enough for you to make that epic jump
- I want they don't.
- This is a climbing game.
- Eithe you don't get better at it.
- Either you're able to do the obstacles or you're not that's all there is to it
- The game is purely a climbing game with environmental obstacles
- A successful gameplay loop throughout the entire game from start to finish
- He doesn't get stronger.
- It's a climbing game.
- Either you can make the jumps or you can't
- About 20 minutes of climbing gameplay with multiple obstacles of various kinds in combinations.
- It's a climbing game that's vaguely in a spiral where the camera is anchored on the player and the tower rotates to meet the camera angle and it should use the asset packs that I give it along with the master prompt.
- And if it has to create animations for the player than it does.
- It should also have a part where the gap for the jump is just a little bit too big and it can grab on with its fingerprints and pull itself up and it should have on-screen controls and it's a 2.5d so all the asset packs are going to be 3D
- So the player loot their repeat minute to minute is find obstacle find way around obstacle repeat.
- So hey, I got to go for this part of the platform to this part of the platform to progress.
- There might be a moving platform, a trampoline jump to make or something that I got to catch mid-air or a grappling hook or a cable but they always have something impeding their travel.
- They have a location that they have to get to and their game loop is to make across that

- **Genre:** Platformer / vertical scroller
- **2D or 3D:** 2.5D
- **Movement and camera feel:** I just want that look so as you're climbing the whole spiral platform rotates so you are always centered in the screen. The game is like up where you just keep climbing
- **Target platforms:** Android (owner said: "Android")
- **Intended feeling:** lucky (owner said: "They would feel very lucky cuz this is a skill based game")
- **Core gameplay loop:** So the player loot their repeat minute to minute is find obstacle find way around obstacle repeat. So hey, I got to go for this part of the platform to this part of the platform to progress. There might be a moving platform, a trampoline jump to make or something that I got to catch mid-air or a grappling hook or a cable but they always have something impeding their travel. They have a location that they have to get to and their game loop is to make across that
- **World / level structure:** Procedurally generated stages (owner said: "Procedurally generated stages")
- **Combat:** No (owner said: "No")
- **Difficulty and failure:** Checkpoints and quick retry (owner said: "Checkpoints and quick retry")
- **Art style:** Voxel-look 2.5D (owner said: "Voxel-look 2.5D")
- **Over-the-air updates:** Yes - over-the-air updates (least intrusive) (owner said: "Yes - over-the-air updates (least intrusive)")
- **Game name:** Climb up (owner said: "Climb up")
- **Player progression:** No
- **What updates may change:** Content, tuning and game logic

## A3. Owner corrections (these supersede anything earlier; do NOT implement withdrawn items)

None.

## A4. Must-not-change constraints

These are binding and are listed in full in Part E. Do not reinterpret, 'improve' or normalise them.

## A5. First playable build scope

About 20 minutes of climbing gameplay with multiple obstacles of various kinds in combinations. It's a climbing game that's vaguely in a spiral where the camera is anchored on the player and the tower rotates to meet the camera angle and it should use the asset packs that I give it along with the master prompt. And if it has to create animations for the player than it does. It should also have a part where the gap for the jump is just a little bit too big and it can grab on with its fingerprints and pull itself up and it should have on-screen controls and it's a 2.5d so all the asset packs are going to be 3D

Objective: a complete, genuinely playable first version (the default; the owner did not ask for a prototype).

## A6. Completion criteria

- Win and loss: They win by getting to the top. They don't really ever fail. They just don't win
- The first build is done when: A successful gameplay loop throughout the entire game from start to finish

## A7. Asset policy and owner-supplied assets

Asset sources in priority order: 1) appropriately licensed free assets (CC0/public domain; verify every license); 2) for whatever remains uncovered, original/procedural work created by the builder.

Asset policy: **CC0 / public domain, else original/procedural** (chosen by the owner).

- **Game icon:** none supplied; create an original one.
- **Studio logo / splash:** OWNER-SUPPLIED file `Hot_Attic_Games_Master_Logo_ALPHA_FINAL.png` (1536x1024, sha256 e3d9bb5653eafb78...), kept untouched at `branding/master/studio_splash_e3d9bb56.png`. Use it; never generate a replacement.
- **Game splash / title image:** none supplied; create an original one.

## A8. Informed overrides and acknowledged risks

None.

# PART B - ACCEPTED RECOMMENDATIONS (Game Designer's suggestions the owner accepted or delegated; keep unless there is a strong engineering reason)

None.

# PART C - IMPLEMENTATION GUIDANCE (how to build it; derived from Parts A and B plus engineering practice)

Where anything in this part appears to conflict with Part A, Part A wins.

### Engineering decisions Bob made so the owner did not have to (change any of them for a good reason)

- **Phone orientation:** Landscape
- **Core fantasy:** It to be a 2.5d platformer that feels like it's built around a cylinder because it's like kind of round but you should be able to jump across and catch other things. The cylinder that it's a supposedly built around doesn't really exist.
- **Saving:** Only progress and stats
- **Tutorial / onboarding:** Contextual tips as things appear
- **Controls:** Touch
- **Touch control scheme:** Floating virtual stick (+ buttons if needed)
- **Effects and game feel:** Moderate
- **Audio and music:** Music and sound effects
- **HUD and UI style:** Clean readable HUD showing only essential state (key resources and goals), large touch-friendly targets, consistent iconography, and a pause menu reachable at all times.
- **Menus and settings:** Main menu, Pause menu, Volume controls, Graphics quality, Controls, Accessibility, Reset/erase save data, Credits and licenses
- **Accessibility:** Text size options, Colorblind-safe palette / modes, Reduced motion / screen shake off, Subtitles / captions, Vibration/haptics control, Large touch targets, Difficulty assists
- **Performance target:** 60 fps
- **Engine / framework:** libGDX
- **Network use:** Fully offline
- **Build pipeline:** GitHub Actions builds the artifact
- **Testing and validation:** Deterministic unit tests for rules/data; automated build on every checkpoint; headless smoke playtest. Generate stages from many seeds; an automated solver confirms every one has a completable route from start to goal, and a scripted run completes at least one. Persistence round-trip and migration tests. Lint/static analysis where the toolchain supports it.
- **Scope:** Go with your recommendation
- **Package / app ID:** com.hotatticgames.climbup
- **Versioning:** Semantic version + incrementing build code
- **Distribution:** Just me / sideload

### Anti-slop acceptance criteria (what does NOT count as satisfying this design)

- A flat single-plane scene does not satisfy 2.5D: the playfield must have genuine depth layering (parallax backgrounds, foreground occluders, depth-staggered lighting) while play stays on the intended plane.
- A death with no consequence, a recovery that can soft-lock, or a restart that leaves stale state does not satisfy the failure-and-recovery design.
- A HUD too small to read on a phone, or touch controls that cover critical play, do not satisfy the controls and presentation requirements.
- Engine-default, placeholder-looking or visually incoherent output does not satisfy the visual target: smaller and polished beats larger and unfinished.

## 2. Vision and non-negotiables

The owner's concept is quoted verbatim in section A1.

**Core fantasy:** It to be a 2.5d platformer that feels like it's built around a cylinder because it's like kind of round but you should be able to jump across and catch other things. The cylinder that it's a supposedly built around doesn't really exist.

**Intended player feeling:** lucky

**Design reading of that feeling:** narrow-escape moments produced by skilled execution: tight but fair margins, last-moment catches, recoverable near-failures; success is decided by the player's skill, never by hidden randomness. Derive the mechanics from this experience; do not turn the word "lucky" into a feature.

**Genre:** Platformer / vertical scroller. **Dimension:** 2.5D. 

### Reference games

References inform design characteristics only. Never copy characters, art, maps, music, writing, names or any proprietary asset from them.

- **About 20**

### Non-negotiables

- The first build is a genuinely playable, complete game - the full intended core loop with real visuals, input, audio (if specified), menus, settings, saves, win/failure/progression - not a prototype.
- No unfinished-work markers, placeholder gameplay or stubbed systems ship in the first build. Anything deferred is listed in section 16 and nowhere else.
- Everything in this file is implemented, validated by automation, repaired and polished before asking the owner to playtest.
- No secrets (API keys, tokens, signing keys, passwords) are ever committed.
- All external assets satisfy the license policy in section 8 and are logged with provenance.

## 3. Player experience

- **Core loop:** So the player loot their repeat minute to minute is find obstacle find way around obstacle repeat. So hey, I got to go for this part of the platform to this part of the platform to progress. There might be a moving platform, a trampoline jump to make or something that I got to catch mid-air or a grappling hook or a cable but they always have something impeding their travel. They have a location that they have to get to and their game loop is to make across that
- **World/level structure:** Procedurally generated stages
- **Win/loss:** They win by getting to the top. They don't really ever fail. They just don't win
- **Difficulty and failure:** Checkpoints and quick retry
- **Tutorial/onboarding:** Contextual tips as things appear
- **Movement and camera feel:** I just want that look so as you're climbing the whole spiral platform rotates so you are always centered in the screen. The game is like up where you just keep climbing

## 4. Gameplay and system requirements

Every system the owner specified or accepted below is required in the first build and must be reachable and exercised during normal play. Genre checklist items apply wherever they fit this game's actual design; if one does not fit, adapt or omit it and note why in `docs/DECISIONS.md` (Part A always wins).

### Specified design (who decided each item is in Parts A and B)

- **Progression:** NONE by design (owner decision). The player never gets stronger - no XP, levels, upgrades, unlocks or power-ups. Skill and physical progress through the game are the only progression; do not add any.
- **Combat:** NONE by design (owner decision). No enemies, bosses, weapons, health or damage model; challenge comes from the environment.

### Required systems (genre completeness checklist - adapt each item to this game's real design)

- **Movement model** (Platformer): Run/jump/coyote time/jump buffer constants documented and tunable.
- **Procedural stage generator** (Platformer): Seeded generator that composes the obstacle types into coherent, readable, fair routes; every generated stage is completable, deliberate hard jumps are allowed, and difficulty rises. Authored components, patterns and rules are allowed ingredients.
- **Checkpoints and respawn** (Platformer): Instant respawn, state restoration rules.
- **Environmental hazards** (Platformer): Hazards that set the player back (falls, crumbling or moving obstacles). No enemies, no combat and no health or damage model.
- **Checkpoint and run progress save** (Platformer): Persist checkpoint, best height and settings; there is no stage menu.

### Procedural generation constraints (what every generated result must preserve)

Procedural does not mean random placement. Derived by Game Designer from the owner's design; the owner can change any of it. Generation happens inside these bounds, and a generator is not successful merely because its outputs differ.

- Every generated route is completable from start to goal; an automated validator or solver proves it for every seed the build can produce.
- Destinations and hazards are readable before the player commits to a move (affordances are clear, nothing is invisible or ambiguous).
- Difficulty is fair: hard moments are deliberate and recoverable, never accidental or impossible, and the overall difficulty ramps toward completion.
- Challenge alternates with recovery (checkpoints, safe footing or resting points) so tension has a rhythm.
- Style, scale and visual language stay coherent from stage to stage.
- Margins are tuned so skilled play regularly lands near-misses and last-moment catches; generation never relies on luck to make a stage passable.
- The overall topology the owner described survives generation (for example an unbroken upward ascent), not a pile of disconnected pieces.
- Traversal grammar: only the owner's traversal elements (jump, trampoline, rope, cable, swing, moving platform, crumbling, grab, catch, pull, gap) and their combinations are used; each combination is checked for fairness.
- Authorship: generation runs under authored constraints (fairness, difficulty, pacing, recovery, validation, traversal grammar). Authored modules, patterns, templates, grammars and rules are all allowed ingredients, and so is generating geometry directly; which mix to use is an engineering decision. The owner has not forbidden hand-authored ingredients.

What may vary: order, spacing and combination of obstacles within the safe bounds above; seed-driven layout and pacing of difficulty beats; visual variants within the established style. Validate functionally (automated solver/validator over many seeds) AND by playtest, because a stage can be valid without being good.

### Progression is player mastery

Progression is the player's own mastery: discovering what actions are possible, combining them, applying them to harder situations and internalising the timing, together with physical progress through the course. The avatar itself never gets mechanically stronger.

### Cross-cutting systems that must exist

- Pause behavior: the game can be paused/backgrounded at any time without loss or exploit; resuming restores exact state.
- Death/failure/restart: defined flow from failure to retry or menu with no dead ends or soft-locks.
- Save/load: Only progress and stats. See section 11.
- Main loop must never reach an unwinnable or stuck state; automated tests assert this where feasible.

## 5. Content scope (recommended sizing)

Scope tier: **Large** (recommended: Large). Design complexity score 3 (genre systems, dimension, procedural content, platform count). Claude plan and conservative usage preference reduce the single-pass scope by one tier.

These counts are Game Designer's UPPER-BOUND EFFORT HEURISTICS, not owner requirements, unless the owner stated numbers in Part A. Do not pad content to reach them. Map each unit onto this game's real structure (for example depth zones instead of levels in a descent game) and size content so the intended loop is complete and replayable. Author content as data (tables/resources), validated by automated checks; no content slot may be an empty stub.

- Generated stage templates / segments: **28**
- Worlds / themes: **4**
- Obstacle and traversal types: **12**

## 6. Controls, camera and orientation

- Input methods: Touch
- Touch scheme: Floating virtual stick (+ buttons if needed). Touch targets at least 48dp; controls must not obscure critical play; layout adapts to both thumbs and to phone/tablet.
- Orientation: Landscape; layouts must handle notches, cutouts, safe areas and aspect ratios from 16:9 to 21:9 (and tablets).
- Camera and movement feel follow section 3 and must be tunable from a single data file.

## 7. Art, audio and interface direction

- Art direction: Voxel-look 2.5D
- Effects/game feel: Moderate (all screen shake/flash effects respect the reduced-motion setting)
- Audio: Music and sound effects
- HUD/UI: Clean readable HUD showing only essential state (key resources and goals), large touch-friendly targets, consistent iconography, and a pause menu reachable at all times.
- Menus and settings: Main menu, Pause menu, Volume controls, Graphics quality, Controls, Accessibility, Reset/erase save data, Credits and licenses

## 8. Assets, provenance and branding

License policy: **CC0 / public domain, else original/procedural**. 'Free to download' is not a license. For every external asset: verify the license on the asset's own page, save a copy of the license text under `assets/licenses/`, and add an entry to `ASSETS.md` with file, source URL, creator, license, and download date. The in-game credits screen lists all of them. Do not scrape or redistribute assets against a site's terms.

If no coherent, appropriately licensed set exists for a need, build the original procedural replacement described below. Never leave an asset need unresolved or as a placeholder.

### Character and creature models

The player character(s) and any non-combat creatures or props the design names, with the animation states the gameplay needs (idle, move, jump, land, catch, climb as relevant).

- Resolution: use a CC0/public-domain set from external sources
- Candidate sources: Kenney (https://kenney.nl/assets); Quaternius (https://quaternius.com); Poly Haven (https://polyhaven.com); OpenGameArt.org (CC0-filtered) (https://opengameart.org); itch.io (CC0-tagged assets) (https://itch.io/game-assets/assets-cc0)
- License: CC0 1.0 / public domain
- Instructions: Choose ONE visually coherent set (consistency over variety). If no coherent clean-license set covers this need, build the original procedural fallback: Generate meshes from primitives and parametric/lathe/extrude builders with vertex colors or simple materials; deterministic seeds.

### Environment models and props

Everything needed to render the world structure chosen for this game.

- Resolution: use a CC0/public-domain set from external sources
- Candidate sources: Kenney (https://kenney.nl/assets); Quaternius (https://quaternius.com); Poly Haven (https://polyhaven.com); OpenGameArt.org (CC0-filtered) (https://opengameart.org); itch.io (CC0-tagged assets) (https://itch.io/game-assets/assets-cc0)
- License: CC0 1.0 / public domain
- Instructions: Choose ONE visually coherent set (consistency over variety). If no coherent clean-license set covers this need, build the original procedural fallback: Generate meshes from primitives and parametric/lathe/extrude builders with vertex colors or simple materials; deterministic seeds.

### Materials and textures

Surface materials consistent with the chosen art direction.

- Resolution: use a CC0/public-domain set from external sources
- Candidate sources: Poly Haven (https://polyhaven.com); ambientCG (https://ambientcg.com)
- License: CC0 1.0 / public domain
- Instructions: Choose ONE visually coherent set (consistency over variety). If no coherent clean-license set covers this need, build the original procedural fallback: Generate tileable textures with noise/pattern shaders baked at build time.

### UI kit (panels, buttons, bars, icons)

All menus, HUD elements, settings, and icon sets referenced by the spec.

- Resolution: use a CC0/public-domain set from external sources
- Candidate sources: Kenney (https://kenney.nl/assets); OpenGameArt.org (CC0-filtered) (https://opengameart.org); itch.io (CC0-tagged assets) (https://itch.io/game-assets/assets-cc0)
- License: CC0 1.0 / public domain
- Instructions: Choose ONE visually coherent set (consistency over variety). If no coherent clean-license set covers this need, build the original procedural fallback: Build the UI from engine-native drawing primitives (rounded rects, gradients, icons drawn as vectors) with a shared theme.

### Visual effects

Feedback effects for the game's key actions (landing, catching, bouncing, failing and recovering as relevant) and ambient particles.

- Resolution: use a CC0/public-domain set from external sources
- Candidate sources: Kenney (https://kenney.nl/assets)
- License: CC0 1.0 / public domain
- Instructions: Choose ONE visually coherent set (consistency over variety). If no coherent clean-license set covers this need, build the original procedural fallback: Use engine particle systems and shaders with a shared small palette.

### Fonts

At least one readable UI font and one display font; must satisfy the license policy.

- Resolution: use a CC0/public-domain set from external sources
- Candidate sources: Kenney (https://kenney.nl/assets)
- License: CC0 1.0 / public domain
- Instructions: Choose ONE visually coherent set (consistency over variety). If no coherent clean-license set covers this need, build the original procedural fallback: Use a CC0 font from a blanket-CC0 source, or generate a bitmap font from code.

### Sound effects

UI, gameplay actions, impacts, pickups, alerts.

- Resolution: use a CC0/public-domain set from external sources
- Candidate sources: Kenney (https://kenney.nl/assets); OpenGameArt.org (CC0-filtered) (https://opengameart.org); itch.io (CC0-tagged assets) (https://itch.io/game-assets/assets-cc0); Freesound (CC0-filtered) (https://freesound.org)
- License: CC0 1.0 / public domain
- Instructions: Choose ONE visually coherent set (consistency over variety). If no coherent clean-license set covers this need, build the original procedural fallback: Synthesize effects with a small sfxr-style synthesizer (waveform, envelope, filter) rendered at build time with seeded parameters per effect.

### Music tracks

Menu track plus gameplay track(s) matching the mood; loopable.

- Resolution: use a CC0/public-domain set from external sources
- Candidate sources: Kenney (https://kenney.nl/assets); OpenGameArt.org (CC0-filtered) (https://opengameart.org); itch.io (CC0-tagged assets) (https://itch.io/game-assets/assets-cc0); Freesound (CC0-filtered) (https://freesound.org)
- License: CC0 1.0 / public domain
- Instructions: Choose ONE visually coherent set (consistency over variety). If no coherent clean-license set covers this need, build the original procedural fallback: Synthesize loopable music from a small procedural sequencer (scales, chord progressions, simple instruments) rendered at build time.

### Game branding and identity

- **Game/app icon:** create an original asset in keeping with the art direction and the game's identity.
- **Studio/developer splash:** uploaded master at `branding/master/studio_splash_e3d9bb56.png` (Hot_Attic_Games_Master_Logo_ALPHA_FINAL.png, 1536x1024, sha256 e3d9bb5653ea...). Never modify or overwrite the master; derive every size from it.
- **Game splash / title screen:** create an original asset in keeping with the art direction and the game's identity.

Derive all required platform sizes, formats, adaptive-icon foreground/background and safe-zone variants automatically from the master artwork (for Android: mipmap densities, adaptive icon layers with the monochrome layer, Play Store 512px icon; splash via the platform splash API plus an in-game splash screen). Keep the master file untouched.

## 9. Architecture, toolchain and platforms

- Engine/framework: **libGDX** (Kotlin / Java). License: Apache-2.0.
- Why: Code-only framework: everything is source an agent can write, test with JUnit and build with Gradle; excellent Android fit; very good 2D, basic 3D.
- Known caveats to design around: No visual editor; iOS (MobiVM) and web (GWT/TeaVM) backends need extra care; you assemble more engine pieces yourself.
- Headless validation: Headless backend plus JUnit for simulation/rules; deterministic fixed-step logic is easy to test.
- Build artifacts: Gradle assembleDebug/assembleRelease for Android; desktop jar/native packaging.

Verified toolchain facts (captured with sources by Game Designer; re-verify only if a build fails because of them):

- libGDX: latest release 1.14.2 on Maven Central (https://repo.maven.apache.org/maven2/com/badlogicgames/gdx/gdx/maven-metadata.xml)
- Gradle: current Gradle release 9.8.1 (https://services.gradle.org/versions/current)

- Target platforms: Android
- Performance target: 60 fps
- Network policy: Fully offline. Apart from the signed update check described below, the game makes no network calls and works fully offline.

### Over-the-air updates (owner chose this; implement the LEAST INTRUSIVE design)

- **Scope (owner decision):** content, tuning AND game logic expressed as sandboxed data/scripts the game interprets (no native code, no arbitrary code execution; the script runtime is part of the bundled build and versioned by the schema gate). Everything else ships as a normal build.
- **Checking:** silent and non-blocking, at most once every 24 hours and at app start, only on an unmetered connection, with a short timeout. Failure is silent. Nothing interrupts play, nothing is shown unless the player opens Settings.
- **Applying:** only on the next cold start, never mid-session. No restart prompts, no popups, no forced updates.
- **Safety:** a signed manifest (public key pinned in the app), a SHA-256 for every file, a minimum-app-version and schema gate, atomic staging, an anti-rollback counter, and automatic fallback to the bundled content if anything fails to verify or load.
- **Hosting and privacy:** static files on free hosting (for example GitHub Releases or Pages); no server of your own, no accounts, no analytics, and no device identifiers or personal data in the request.
- **Permissions:** INTERNET only. No foreground service, no notifications, no background-fetch permission beyond what the platform grants by default.
- **Controls:** a Settings switch (default on) and a visible content version in an About/diagnostics line; a manual 'check now' lives only in Settings.
- **Tests:** bad signature rejected, corrupt file rejected, interrupted download resumes or discards cleanly, rollback works, offline start works, version gate holds. Record the result in `docs/VERIFICATION.md`.

### Repository layout and rules

- Keep game logic separate from rendering/engine glue so rules, simulation and data validation are unit-testable headlessly.
- All balance/content numbers live in data files, never scattered in code.
- Docs: `docs/DECISIONS.md` (engineering decisions), `docs/ARCHITECTURE.md`, `HANDOFF.md` (current state for the next context), `ASSETS.md` (provenance).
- Commit at every stable, tested checkpoint with a clear message; never leave `main` unbuildable. Never force-push or rewrite history on shared branches.

### Build pipeline

Use GitHub Actions to build the installable artifact on every push to the working branch and on tags, and upload it as a downloadable workflow artifact. Claude writes and maintains the workflow, reads the run results, and repairs failures until green. Gradle: wrapper pinned, Android Gradle Plugin and compile/target SDK pinned to a verified compatible set; `assembleDebug` for owner playtests and `bundleRelease` for stores.

## 10. Performance requirements

- Hold the target frame rate (60 fps) on the minimum hardware in normal play, with graceful quality degradation instead of stutter.
- Cold start under 4 seconds on minimum hardware to the main menu; no ANRs; memory stays within a documented ceiling; battery-friendly background behavior.

## 11. Persistence and save requirements

- Save model: Only progress and stats.
- Saves are versioned with explicit migrations and tested with fixture saves from every previous version.
- Writes are atomic (write temp then rename); corrupt saves are detected, backed up and recovered from without crashing.
- The app can be killed at any moment on a phone; autosave on pause/background and at safe points.
- Settings persist separately from game saves. A 'reset data' option exists if listed in the menus.

## 12. Accessibility

Implement and test each of the following; all must be reachable from the settings screen and apply immediately:

- Text size: at least 3 steps (100/130/160%) applied to all text, with layouts that reflow without clipping.
- Colorblind safety: never convey information by color alone; provide shape/pattern/icon redundancy and a palette option verified against deuteranopia/protanopia/tritanopia simulations.
- Reduced motion: disables screen shake, flashes, heavy parallax and camera sway.
- Subtitles/captions for all speech and meaningful audio cues, with size and background options.
- Vibration/haptics: global on/off and intensity control.
- Large touch targets: minimum 48dp, 56dp for primary actions, adequate spacing.
- Difficulty assists: adjustable game speed, jump forgiveness and similar (and damage or aim assist where the game has combat), changeable at any time without penalty.

## 13. Testing and validation

Deterministic unit tests for rules/data; automated build on every checkpoint; headless smoke playtest. Generate stages from many seeds; an automated solver confirms every one has a completable route from start to goal, and a scripted run completes at least one. Persistence round-trip and migration tests. Lint/static analysis where the toolchain supports it.

### Validation ladder (run in this order; repair failures before proceeding)

- Static checks: lint/format/type checks for the chosen toolchain.
- Unit tests for all rules, simulations, data validation (content tables complete, references resolve, no negative or infinite values).
- Persistence tests: save/load round trip, corrupt save recovery, migration from fixtures.
- Headless smoke playtest(s): Generate stages from many seeds; an automated solver confirms every one has a completable route from start to goal, and a scripted run completes at least one.
- Build the real artifact for every target platform in CI and confirm it installs/launches (emulator or headless where available). Report only what you actually ran.
- UI inspection: capture screenshots of every screen at phone and tablet aspect ratios and review them for clipping, overlap and unreadable text.

### Verify before finishing (game-specific - actually play these paths)

- Launch the real build on Android from a cold start and reach live gameplay with no errors.
- Exercise movement: jump, climb. Confirm the camera never clips or hides the player, collision holds (no falling through the world, no sticking), and the feel matches the owner's description.
- Generate several different stages and confirm each is valid, completable and different.
- Fail on purpose: confirm the failure-and-recovery behaviour (Checkpoints and quick retry) works, restores a correct state and cannot soft-lock.
- Pause and resume (and on a phone, background and return); if saving exists, save, kill the app, relaunch and load.
- Exercise the update pipeline: serve a valid update and confirm it applies on the NEXT launch only; serve a tampered and a corrupt one and confirm both are rejected with the game still starting on bundled content; confirm the game starts and plays with no network.
- Confirm there is no player power progression anywhere: nothing the player earns makes later climbs mechanically easier.
- Reach the completion state defined for the first build, then restart cleanly.
- Capture representative gameplay screenshots (or video) on the real build where tooling permits, inspect them for bad composition, camera clipping, unreadable HUD, placeholder assets, empty environments, scale problems, broken materials, visual obstruction, poor character readability, weak lighting and unfinished geometry, repair what you find, and inspect again.
- Report honestly what you could and could not test. Successful compilation, an editor screenshot, a tool handshake, or unit tests alone are NOT gameplay verification.

### Definition of done for the first build

A successful gameplay loop throughout the entire game from start to finish

### Deliverable contract

- A working game project with source, assets and any editable sources committed to the repository.
- The installable/runnable build for Android, with its location reported.
- A README with exact install/launch instructions and controls, and `docs/VERIFICATION.md` listing what was actually exercised, what was not, and known remaining issues.
- Android: the studio splash (Hot Attic Games, or the owner-supplied logo above) then the game's own title screen, a current target SDK, 16 KB page-size compatibility where native code is used, GitHub Actions producing the artifact, coherent versioning, release artifact naming, and rollback-safe update behaviour where over-the-air updates are used.

## 14. Packaging, identity and release

- Display name: **Climb up**
- Application/package ID: `com.hotatticgames.climbup` (never change after first release)
- Versioning: Semantic version + incrementing build code; the CI sets the build code from the run number.
- Distribution: Just me / sideload
- Request only the permissions the game actually needs and document each in `docs/DECISIONS.md`.

## 15. Execution plan and resource rules

Resource estimate: **Moderate**. Large scope with complexity 3. Expect several focused sessions across multiple checkpoints.

Claude environment expectation: Claude Pro; usage style: Conservative - share my allowance with other projects.

### Phases

- Phase 0 - Foundations: toolchain pinned and verified, repository layout, CI pipeline producing an installable artifact, data schemas, test harness.
- Phase 1 - Complete core loop: the full intended core loop playable end to end with real (not placeholder) visuals, input and feedback.
- Phase 2 - Systems: every required system from this spec implemented and integrated (progression, saves, UI, settings, win/loss).
- Phase 3 - Content: author the content volume in the Scope section using data-driven tables; validate with automated checks.
- Phase 4 - Polish: audio, VFX/game feel, accessibility, performance tuning, onboarding.
- Phase 5 - Release candidate: full validation, repair pass, final build artifact, handoff for human playtesting.

### Resource discipline

- Keep one authoritative CLAUDE.md; record decisions in files, not chat.
- Work in durable phases with a committed checkpoint and HANDOFF.md at the end of each.
- Run targeted tests while iterating; run the full suite and a full build only at phase gates.
- Use subagents only when their value clearly exceeds their cost; avoid parallel swarms.
- Do not research settled questions repeatedly; persist findings in `docs/DECISIONS.md`.
- Do not sacrifice product quality merely to save tokens; do shrink process overhead instead.
- Do not ask the owner anything already answered in this file. Escalate only genuine creative decisions not covered here, credentials/permissions only the owner can supply, or verified blockers with no engineering alternative.

## 16. Explicit exclusions and deferred features

No exclusions were declared beyond: online multiplayer, accounts and cloud services (not part of this build).

# PART D - UNRESOLVED AND DELEGATED DECISIONS

None unresolved. Every build-critical decision is resolved by the owner, delegated, or left to implementation discretion above.

The owner's plain-English design review was approved.


# PART E - MUST NOT CHANGE (binding constraints; do not reinterpret, 'improve' or normalise any of them)

Stated by the owner:

- It must not reinterpret where the camera focus is.
- How the terrain rotates to match the camera focus and that this is purely a climbing game with environmental obstacles

Implied by the owner's own decisions:

- Asset policy: CC0 / public domain, else original/procedural (chosen by the owner).
- Use the owner-supplied studio splash exactly as provided.

## 17. Human-only steps

Only these steps genuinely need the owner. Do everything else autonomously.

- Install and playtest the first build on a real device and report back what feels wrong (text, voice, screenshots or video).
