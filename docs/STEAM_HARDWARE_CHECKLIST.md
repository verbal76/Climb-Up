# Steam Deck / Steam Machine checklist — Upwardly

Per studio standard §12 and Valve's docs
([recommendations](https://partner.steamgames.com/doc/steamhardware/recommendations),
[compat](https://partner.steamgames.com/doc/steamhardware/compat)). Status is evidence-based. **No physical Steam Deck or
physical controller test has occurred**: every row that needs the hardware says OWNER HARDWARE TEST, never PASS.

Dispositions (Build 62 closure): **COMPLETE** (engineering done, automated evidence) · **OWNER HARDWARE TEST**
(implementation complete, physical verification still needed) · **OWNER STEAMWORKS ACTION** (needs the partner account;
see `docs/STEAMWORKS_SETUP.md`) · **N/A**.

| # | Requirement (Valve Verified unless noted) | Disposition | Evidence / note |
|---|---|---|---|
| 1 | Native Linux x86-64 build exists | **COMPLETE** | `tools/linux/package.sh` builds an ELF app-image; the desktop workflow smokes it headless at 1280×800 on every run ("SMOKE_RESULT PASS"); published as `Upwardly Build NN Linux.tar.gz`. |
| 2 | Launches & runs at 1280×800 | **COMPLETE** + OWNER HARDWARE TEST | The end-to-end scenario (`:desktop:scenario`, CI gate) resizes the real window to 1280×800 and screenshots play, pause, TIMES, every settings tab, the bindings editor, the title, MORE, PLAYERS and the name picker there (shots 23–31): nothing clipped or overlapping. A first run on a Deck starts full screen at the panel's native mode. On-Deck launch itself: owner. |
| 3 | 16:10 / 16:9 / other ratios, responsive HUD, readable text | **COMPLETE** + OWNER HARDWARE TEST | Same scenario at 1024×768, 1280×720, 1280×800, 1600×900, 1920×1080, 2560×1080 and full screen; the HUD, toasts and tips fit between the plates at every ratio (build 60's toast fit); TEXT SIZE 100/130/160 % and the caption options exist. Handheld readability at arm's length: owner. |
| 4 | Controller-only: launch, menus, settings, gameplay, pause, resume, save/load, exit | **COMPLETE** + OWNER HARDWARE TEST | Every button in the game is a focusable `Ui.button`, so the pad's d-pad/stick, A and B reach all of them. The scenario drives a controller-only pass with the keyboard and mouse never touched: title → MORE → PLAYERS → NEW PLAYER (the name "DECK" typed on the picker with the pad) → PLAY → pause (Menu button) → TIMES → SETTINGS (all six tabs) → the controller bindings editor → back → SAVE & EXIT → CREDITS → EXIT path. Physical pad: owner. |
| 5 | No mandatory mouse/keyboard | **COMPLETE** | As #4; the bindings editor, the name picker and every dialog (rebind, conflict, restore defaults, erase) are buttons. |
| 6 | Default controller mapping via Steam Input | **OWNER STEAMWORKS ACTION** | The game reads a standard gamepad natively; the partner-site default should be the Gamepad template (`docs/STEAMWORKS_SETUP.md` §3). |
| 7 | Controller glyphs (ABXY etc., not hardcoded Xbox) | **COMPLETE** + OWNER HARDWARE TEST | CC0 glyph pack (build 60): Xbox, PlayStation and Switch families chosen from the pad's name; build 62 maps the Steam Deck's own controls and Steam Input's virtual pad to the Xbox layout; unknown pads fall back to the Xbox glyphs; controls the pack lacks show their names. Unit-tested (`InputManagerTest`, `GlyphsTest`). Seen only in screenshots, never on a physical pad: owner. |
| 8 | Automatic Steam on-screen keyboard for text entry | **COMPLETE** (in-game keyboard) | The only text field is the player name, entered on the game's own on-screen picker (A–Z, 0–9, space, delete, OK, cancel) that the pad drives (scenario shot 31). Steam's floating keyboard is deliberately not wired (it injects key presses the picker ignores because the same keys drive the menus); Valve's requirement is text entry without a physical keyboard, which the picker meets. |
| 9 | Mixed mouse + joystick camera input | **N/A** | Fixed camera (owner-locked: anchored on the player, the tower rotates). |
| 10 | Offline single-player (launch, play, save, load) | **COMPLETE** + OWNER HARDWARE TEST | Fully offline; only the OTA check uses the network and fails silently. The scenario saves and reloads progress; the Linux smoke runs with no network. On-Deck: owner. |
| 11 | Update checks fail gracefully offline | **COMPLETE** | Silent, non-blocking, unmetered-only, 6 s timeouts; gameplay never waits on it (OTA tests). |
| 12 | No external launcher | **COMPLETE** | jpackage native launcher straight into the game. |
| 13 | Graphics settings stored per device, not Cloud | **COMPLETE** | `settings.json` and `upwardly-desktop.cfg` are per device and excluded from the Auto-Cloud patterns in `docs/STEAMWORKS_SETUP.md`. |
| 14 | Device graphics defaults (Auto/Quality/Battery) | **COMPLETE** + OWNER HARDWARE TEST | Build 62: a Steam Deck (Game Mode's `SteamDeck=1`, or the Steam client saying so) gets its own first-run defaults — full screen at the native mode, MEDIUM quality, full render scale, 2× anti-aliasing, sky effects, vsync, frame limit 60 (`GraphicsProfile.steamDeck`, `GraphicsTest`). Whether 60 holds on the device, and the thermal/battery trade: owner. |
| 15 | Vulkan | **N/A** | OpenGL through LWJGL3; runs on Mesa (llvmpipe locally; RADV's GL on the Deck). No rewrite (§12.6). |
| 16 | Suspend/resume, focus changes, controller reconnect, extended play, clean exit | **COMPLETE** + OWNER HARDWARE TEST | Losing window focus pauses and autosaves (`ClimbGame.pause`, `PlayScreen.pause`: progress, run snapshot, settings); a frame after a long freeze is clamped to 0.1 s so a suspend can never run minutes of simulation at once; unplugging the controller in use pauses the run with a "CONTROLLER DISCONNECTED" note and plugging it back in makes it active again with its prompts (build 62; `InputManagerTest`, scenario checks 22). Clean exit smoked in both packages. Real suspend on a Deck and hours-long sessions: owner. |
| 17 | Performance (FPS, frame pacing, thermal, battery) | **OWNER HARDWARE TEST** | No physical measurement; llvmpipe software numbers are not representative. |
| 18 | Steam Cloud saves (cross-device) | **OWNER STEAMWORKS ACTION + OWNER TEST REQUIRED** | Code side complete in Build 69: atomic writes, per-player folders, per-device files separate, and the Linux save folder now named like the Windows one (old folder moved once) so one root override shares the files. The exact quotas, four Auto-Cloud rows and the root override are in `docs/STEAMWORKS_SETUP.md` §2; the real-device test is below. |

## Owner hardware test list (what only a real Deck or pad can show)

1. Install `Upwardly Build 62 Linux.tar.gz` on the Deck (Game Mode, add as a non-Steam game or through the partner app): the game should open full screen at 1280×800 with no window frame and no mouse needed.
2. Play one tower on the Deck's controls only: d-pad/stick to move, A to jump, Menu to pause, B to back out of every menu. Open SETTINGS → CONTROLS and confirm the prompts show Xbox-style A/B/X/Y glyphs.
3. Pause, press the power button (suspend), wait a minute, resume: the pause menu should still be up, the run intact, and the clock not jumped.
4. With a Bluetooth or USB pad: unplug it mid-climb (the run must pause and say CONTROLLER DISCONNECTED), plug it back in and press RESUME on it.
5. Type a new player name on the picker with the pad only.
6. Read the HUD at arm's length with TEXT SIZE 100 % and 130 %.
7. Note frame rate and warmth after 15 minutes (the default cap is 60).

## Steam Cloud test (OWNER TEST REQUIRED, after the Steamworks settings in `docs/STEAMWORKS_SETUP.md` §2 are published)

Needs a Build 69 or later package installed **through Steam** (a Steam depot or a Steam-launched build) on a Windows PC and a
Steam Deck (or Linux PC) signed in to the same Steam account. Leave "Enable cloud support for developers only" unticked (before release only key holders own the game).

**Order matters if the Deck already has a save from Build 68 or earlier:** launch Build 69 on the Deck first (step 6), so the
game moves the old folder and Steam uploads it, before any Windows save reaches the cloud. Otherwise Steam fills the new folder
from the cloud first and the old Deck folder is left as it is (kept, not merged).

1. **Windows first save.** Launch Upwardly through Steam. Play a seeded run (NEW RUN → SEEDED RUN, note the 13-letter code)
   until at least two castles are open, then SAVE & EXIT and quit. In Steam's library, the game's page should show the cloud
   status as synced. Note: best height, the two castle splits on MORE → TIMES, the course code.
2. **Deck receives it.** On the Deck, launch through Steam. The same player, best height, course code and splits must appear,
   and CONTINUE must resume the climb at the same castle. If the Deck already had a local save, Steam shows a conflict dialog
   first: choose the cloud copy for this test (the local files are kept by Steam until you choose).
3. **Back again.** On the Deck open a third castle (or set a faster leg), SAVE & EXIT, quit. On Windows launch again: the third
   castle and the new time must be there.
4. **Offline.** Put the Deck in offline mode (or Wi-Fi off), play and SAVE & EXIT, then reconnect and launch Steam: Steam uploads
   on reconnect. Launch on Windows and confirm the offline progress arrived.
5. **Conflict.** Play offline on both machines, each saving something different, then go online on both. Launching on either
   must show Steam's "Cloud Sync Conflict" dialog naming both copies; pick one; the game starts with that copy and does not crash.
   Expected: the copy not chosen is replaced (Auto-Cloud does not merge records); this is the documented limitation.
6. **Old Deck save survives.** On a Deck that played Build 68 or earlier (saves in `~/.local/share/hotatticgames/upwardly`),
   the first Build 69 launch must show the old player, best height and times; the folder
   `~/.local/share/HotAtticGames/Upwardly` now holds them. If the cloud already had a save from Windows, the old local folder is
   left in place untouched rather than merged (the game never overwrites); tell me and I will add a merge step.
7. Record device, build number, what synced and any dialog text. Only after steps 1 to 5 pass: tick Steam Cloud under the store
   page's Supported Features.

