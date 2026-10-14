# Upwardly Build 64 — Steam Deck test package (owner hardware test)

Build 64 packages (one source commit for all three: `0f7d6d78eda12b2422f19753b512c425451d3b43`, the Build 64 code `29bfd94` plus a docs-only commit). Verification of every package is in `docs/VERIFICATION.md` ("Build 64 candidate").

| Platform | File | Size | SHA-256 |
|---|---|---|---|
| Android | https://github.com/verbal76/Climb-Up/releases/download/v1.1.5-build64/upwardly-1.1.5-build64-debug.apk | 22,041,224 | `20f638e3525c2e191ed1bf27178025b3edc7ee66aec3120a8c7bcf220d823bcc` |
| Windows (desktop PC and laptop) | https://github.com/verbal76/Climb-Up/releases/download/build-64-desktop/Upwardly.Build.64.EXE.zip | 50,270,657 | `c9259d06a41e9d10bd432a194e161080516cfcb2fdc5438956180682a76fe905` |
| Linux x86-64 (Steam Deck) | https://github.com/verbal76/Climb-Up/releases/download/build-64-desktop/Upwardly.Build.64.Linux.tar.gz | 55,123,915 | `8da3b873bbfaf70fce772787e94d6cdc20661f5345c350649c7736250bfcff58` |

The Linux package is a native x86-64 app-image: the launcher `Upwardly/bin/Upwardly` (a small ELF that needs only glibc), the game in `Upwardly/lib/app/upwardly.jar` with its assets beside it, and a bundled Java 17 runtime in `Upwardly/lib/runtime`. Nothing has to be installed on the Deck. It is a `.tar.gz` rather than a `.zip` on purpose: tar keeps the launcher's executable bit (`-rwxr-xr-x`, checked in the published archive), zip would not. SteamOS Desktop Mode extracts it with a right-click.

## Install on the Steam Deck (Desktop Mode)

1. In Gaming Mode press the Steam button → Power → **Switch to Desktop**.
2. Open the browser (or any way to download) and save `Upwardly.Build.64.Linux.tar.gz` to `~/Downloads` (or to `/home/deck/Games`, whichever you prefer).
3. In Dolphin (the file manager) right-click the file → **Extract** → **Extract archive here**. You get a folder `Upwardly` containing `bin`, `lib` and `BUILD_INFO.txt`.
4. Executable permission is already set inside the archive. If a launch ever fails with "permission denied", open Konsole in that folder and run `chmod +x Upwardly/bin/Upwardly`.
5. Add it to Steam: open Steam (Desktop Mode) → menu **Games** → **Add a Non-Steam Game to My Library…** → **Browse…** → set the file type filter to **All Files** → go to the extracted folder → `Upwardly/bin/Upwardly` → **Open** → **Add Selected Programs**. Optionally rename the shortcut to "Upwardly" (right-click → Properties).
6. Return to Gaming Mode (the **Return to Gaming Mode** icon on the desktop).
7. Library → Non-Steam → **Upwardly** → Play. The game starts full screen at the Deck's 1280×800 (it reads the `SteamDeck=1` environment Game Mode sets; its first-run Deck profile is MEDIUM quality, 2× anti-aliasing, vsync, 60 fps cap). Settings → ABOUT must read `V1.1.5  BUILD 64`.
8. Controller: the Deck's built-in controls come through Steam Input's default gamepad template and the game treats them as an Xbox-layout pad (A confirm, B back, View or Menu = pause). The prompts in the hint bar and menus must show those glyphs, never key caps.
9. Saves: `~/.local/share/HotAtticGames/Upwardly` (players in `players/pN/`, device settings in `settings.json` and `upwardly-desktop.cfg`). Nothing is written next to the game.
10. If anything refuses to start, run it once from Konsole to see the message: `~/Downloads/Upwardly/bin/Upwardly` (adjust the path). `Upwardly/BUILD_INFO.txt` names the build and source commit.

## First 15 minutes on the Deck

1. **Launch** (Gaming Mode). Title screen: logo, a tagline under it, menu PLAY / NEW RUN / SETTINGS / MORE, status bar `… V1.1.5  BUILD 64`. Note the tagline, leave to a climb and come back later: the other tagline must show.
2. **Controller-only menu pass** (never touch the trackpads as a mouse): d-pad or left stick moves the orange focus, A selects, B goes back. MORE → PLAYERS → NEW PLAYER → type a name on the picker → OK. MORE → TIMES → BACK. SETTINGS → every tab (GENERAL, ACCESS, ABOUT, DISPLAY, GRAPHICS, CONTROLS) → CONTROLS → the CONTROLLER BINDINGS row says EDIT (a pad is recognised) → EDIT → BACK.
3. **Random run**: NEW RUN → RANDOM RUN. Under the clocks the HUD shows `SEED XXXXX-XXXX-XXXX`. Climb to the first castle: jump (A), a rope (stick up/down), a trampoline, a checkpoint gem. Watch the castle toast: TOWER 1 time, then the TOTAL.
4. **Pause** with the Menu button: RESUME, RETRY CHECKPOINT, SETTINGS, TIMES, SAVE & EXIT all reachable; the SEED line sits under the buttons. TIMES shows the course header and the tower rows. B backs out.
5. **Seeded run**: SAVE & EXIT → NEW RUN → SEEDED RUN → type `39W24-NANC-WT7R` on the picker with the d-pad (DELETE fixes a slip; OK with a wrong or short code must refuse with a reason) → OK. The HUD must show exactly that code. The first platforms are the same every time this code is used, on any device (compare with the phone: same code, same course).
6. **Suspend / resume**: mid-climb press the power button, wait 30 seconds, press it again. Expected: the game is either paused (pause menu) or continues exactly where it was; the TOTAL clock must not have jumped by more than a tenth of a second, controls must work, audio must play, the HUD and the seed line must be intact. Repeat once on the title screen.
7. **Settings**: GRAPHICS → SHOW COURSE SEED off → resume: the HUD code line disappears, the pause menu and TIMES still show it. SHOW BEST TIMES off → the PB marks and "vs best" lines leave the HUD; the clock keeps running. Switch both back on. Quit (MORE → EXIT), relaunch: both still on, the run resumes with CONTINUE.
8. **Readability**: at arm's length, read the smallest text on the TIMES screen (the legend under the table) and the HUD seed line. If any of it is uncomfortable to read, say which.
9. **Screenshot (Build 65 and later)**: pause → TAKE SCREENSHOT. The menu comes back, the run stays paused, "SCREENSHOT SAVED" names the folder (`~/Pictures/Upwardly/Screenshots` on the Deck). Steam's own capture (Steam + R1) is separate and must still work.

## Steam Deck physical-device checklist (report PASS / FAIL and what you saw)

- [ ] Launches from Gaming Mode as a Non-Steam Game, full screen, 1280×800, no stretching, no black bars.
- [ ] Controller-only: title, NEW RUN, RANDOM RUN, SEEDED RUN, seed entry, SETTINGS (all tabs), pause menu, TIMES TABLE, gameplay, rope climbing, checkpoints, finish screen (castle 10, or the END RUN flow if you get there), return to title, EXIT.
- [ ] Prompts show pad glyphs everywhere (hint bar, pause corner, bindings editor); no keyboard prompts while playing on the pad.
- [ ] Focus always visible; nothing unreachable; no double-activation.
- [ ] Deck controls recognised with no setup; Steam Input default template works.
- [ ] Text readable at handheld size: HUD, seed codes, TIMES legend, settings notes, captions.
- [ ] Suspend / resume in gameplay and in menus: no crash, no lost checkpoint, no audio loss, controls work, clock not jumped.
- [ ] Performance: steady 60 fps (frame counter in the Deck's performance overlay), castle transitions, rope climbs, cannon fields, deep space; note the temperature and battery drain after 15 minutes of play.
- [ ] Audio: music and effects, captions if you turn them on.
- [ ] Offline: airplane mode on, launch, play, save, quit, relaunch — everything there.
- [ ] Saves survive quit / relaunch: players, climb in progress, times, both HUD switches.
- [ ] Same code on the phone and the Deck gives the same course.
- [ ] Graphics settings: DISPLAY and GRAPHICS tabs usable; a change persists across a relaunch.

## What was verified without hardware (and what was not)

Automated, on the Build 64 code: the controller-only scenario pass at 1280×800 (title, MORE, PLAYERS, name picker, PLAY, Menu-button pause, TIMES, every settings tab, the bindings editor, SAVE & EXIT, CREDITS, NEW RUN → SEEDED RUN → a code typed with the d-pad → OK → the climb on exactly that seed → pause → SAVE & EXIT); the keyboard pass at 1280×720; the unplug-pauses / replug-resumes handling; Steam Deck and Steam Input pad names mapped to the Xbox layout (unit test); the first-run Deck graphics profile (unit test); the Linux package launched from its own `bin/Upwardly` under a virtual display at 1280×800 (title with BUILD 64, the picker, the times table); the core suite (288 tests: generator determinism to 5,600 m, the rules fingerprint, records, persistence, seed codes), the desktop suite (112), the limb-scale guard, the packaged-jar smoke. Smallest in-game text is about 14 px tall at 1280×800 (HUD seed line, TIMES legend), above Valve's 9 px legibility floor; everything else is 20 px or larger.

Not verifiable here, yours: real framerate, frame pacing, temperature and battery; audio output; Steam Input on the real Deck; suspend / resume through SteamOS; readability at 7 inches; the Deck's fullscreen behaviour under gamescope; cross-device determinism (the same code on the phone and the Deck).

## Known items (none blocks today's testing)

- **Suspend and the run clock (policy, NONBLOCKER):** the clock counts simulation steps only, so a suspended Deck cannot gain time. If SteamOS reports a focus loss when it sleeps, the game pauses itself (as it does on alt-tab) and the clock stops. If it does not, the first frame after waking simulates at most 0.1 s (the frame clamp), so a suspend costs the run at most 0.1 s and gains nothing. If you want "any stall over a second pauses the game" instead, that is a three-line change for the next build; say so after you see what the Deck does.
- **Cross-platform determinism (RELEASE consideration, not a blocker):** the generator is proven deterministic on the desktop JVM (same seed, same course, bit for bit, past castle 10). Five sine / cosine calls in the generator's reach checks use `Math.sin` / `Math.cos`, which Java allows to differ by one unit in the last place between runtimes; a difference that flips a course has not been observed, and your phone-versus-Deck code check is the real test. The fix, if ever needed, is `StrictMath` in those five calls with a rules-version bump.
- **Steam Cloud (RELEASE BLOCKER, Steamworks side):** not implemented in code by design; it is Auto-Cloud over the save folder (`docs/STEAMWORKS_SETUP.md`, section 2). Until it is configured on the partner site, saves stay on the device. Local play is unaffected.
- **Steam Deck Verified:** Valve's evaluation, requested after the store build is up; nothing here claims it.
- **Steamworks administration for the 30 October target (owner's side, tracked in `docs/STEAMWORKS_SETUP.md` and `docs/STEAM_HARDWARE_CHECKLIST.md`):** confirm the App ID; store page review (allow a week); the Coming Soon page must be live at least two weeks before release, so by about 16 October; two depots (Windows, Linux) uploaded with SteamPipe from the Build 64 packages or their successors; the Steam Input default configuration (Gamepad template); Auto-Cloud; the release checklist. None of it changes the game packages.
