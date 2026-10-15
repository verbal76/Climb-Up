# Steamworks setup — what only the owner can do

Everything the game can do on its own side for Steam is done and tested without Steam credentials (see
`docs/STEAM_HARDWARE_CHECKLIST.md`). The items below need the Steamworks partner account, the app's store configuration
or Valve's review, so they are **owner actions**. None of them blocks the GitHub tester builds, which do not use Steam.

## 1. Confirm the App ID (do this first)

`desktop/src/main/java/com/hotatticgames/climbup/desktop/SteamManager.java` carries `STEAM_APP_ID = 5427130`, and the
development file `steam_appid.txt` at the repository root holds the same number. **This number was never confirmed
against the partner site**; it was entered as a placeholder when the achievements code was written. If the real App ID
differs, change both places (one constant, one text file) and nothing else.

With the wrong App ID the Steam client refuses `SteamAPI_Init`, the game logs "Steam client not running or init failed -
achievements disabled" and plays exactly as the plain build; nothing crashes.

## 2. Steam Cloud — Auto-Cloud (App ID 5427130), cross-platform Windows ⇄ Linux / Steam Deck

Rewritten for Build 69. The Build 62 table that stood here was wrong for Linux (it named `HotAtticGames/Upwardly` while the
Linux build then saved to lower-case `hotatticgames/upwardly`, and it had no root override, so Windows and Linux files
would have landed in separate cloud spaces). From Build 69 both systems save to a folder called `HotAtticGames/Upwardly`
(Linux moves the old lower-case folder across once, on first launch), and one root override maps Windows onto Linux.

### Save locations (Build 69 onward)

| System | Folder |
|---|---|
| Windows | `%APPDATA%\HotAtticGames\Upwardly` (e.g. `C:\Users\<you>\AppData\Roaming\HotAtticGames\Upwardly`) |
| Linux | `$XDG_DATA_HOME/HotAtticGames/Upwardly`, normally `~/.local/share/HotAtticGames/Upwardly` |
| Steam Deck | `/home/deck/.local/share/HotAtticGames/Upwardly` (the Linux build) |
| Android | the app's private storage (`Context.getFilesDir()`); no Steam Cloud |

### What syncs and what does not

Synced (the player's progress, nothing machine-specific):
- `players.json` — the named players and who played last.
- `players/pN/save.json` — everything that player has earned: castle progress and checkpoint, best height, statistics, the
  course code (seed) of the climb in progress, per-seed best times, castle-to-castle splits, full-run records, legacy runs.
- `players/pN/run.json` — the climb in progress (SAVE & EXIT / autosave). May be absent.
- `players/pN/history.bin` — the generated slices of that climb, needed to resume exactly. May be absent.

Not synced (per device, or temporary): `settings.json` (volume, text size, quality, accessibility, chosen character),
`upwardly-desktop.cfg` (window, display mode, key bindings, controller profiles), `desktop.log`, `Screenshots/`, `ota/`,
every `*.tmp` (half-written files), `*.corrupt` (quarantined damaged files) and `save-before-build64.json` (a one-off backup).
The patterns below name only the four synced files, so nothing else can slip in.

### Steamworks settings (Steamworks → App 5427130 → Application → Steam Cloud)

| Field | Value |
|---|---|
| Byte quota per user | `100000000` |
| Number of files allowed per user | `100` |
| Enable cloud support for developers only | **unchecked** (before the public release only key holders own the game; with it ticked Auto-Cloud stays off unless the Steam console command `testappcloudpaths 5427130` is used, per the live page) |
| Dynamic Cloud Sync | unchecked |
| Shared Cloud App ID | `0` |

Why those quotas are enough: at most 6 players × 3 files + `players.json` = 19 files. A `save.json` is about 1 KB (it grows
by a few hundred bytes per seed timed; at most 60 seeds are kept), `run.json` a few KB, and `history.bin` about 1.5 MB per
100 km climbed. 100 MB covers several thousand kilometres per player.

**Auto-Cloud → Root Paths** (four rows, all with OS **[Cross-Platform]**, the default; the live page warns that a specific OS locks the files to that OS):

| Root | Subdirectory | Pattern | OS | Recursive |
|---|---|---|---|---|
| `WinAppDataRoaming` | `HotAtticGames/Upwardly` | `players.json` | [Cross-Platform] | unchecked |
| `WinAppDataRoaming` | `HotAtticGames/Upwardly/players` | `save.json` | [Cross-Platform] | checked |
| `WinAppDataRoaming` | `HotAtticGames/Upwardly/players` | `run.json` | [Cross-Platform] | checked |
| `WinAppDataRoaming` | `HotAtticGames/Upwardly/players` | `history.bin` | [Cross-Platform] | checked |

**Auto-Cloud → Root Overrides** (one row):

| Original Root | OS | New Root | Add/Replace Path | Replace Path |
|---|---|---|---|---|
| `WinAppDataRoaming` | Linux + SteamOS | `LinuxXdgDataHome` | (empty) | unchecked |

With that override the same cloud files land on Linux/Steam Deck at `LinuxXdgDataHome/HotAtticGames/Upwardly/...`,
which is exactly where the Linux build saves. macOS is not shipped, so it has no override.

### Conflicts and offline play

Auto-Cloud syncs whole files; it does not merge the records inside them. If a player plays offline on two machines and both
change, Steam shows its **Cloud sync conflict** dialog at launch and the player keeps one machine's files; the other machine's
progress in those files is replaced. Steam keeps the local files untouched until the player chooses. The game itself never
writes a half file (every save is written to `*.tmp` and renamed), so a sync can never pick up a torn save. Course codes,
course generation and the timing rules are identical on Windows and Linux, so a best time stays attached to the same course
on both. Advice for testers: play on one machine at a time while online, and let Steam finish syncing (it does so when the
game closes) before switching.

Moving the old Linux folder: the game moves `hotatticgames/upwardly` into `HotAtticGames/Upwardly` at launch, entry by entry, only
where the new folder does not already have that entry. Steam Auto-Cloud downloads before the game starts, so on a Deck whose new
folder was already filled from the cloud, the old folder's clashing entries stay where they are (kept, never deleted or merged).
For testers: start the Deck on Build 69 before the first Windows save reaches the cloud.

### Click-by-click (owner)

1. Steamworks → **App 5427130** → **Application** → **Steam Cloud**.
2. Enter **Byte quota per user** `100000000` and **Number of files allowed per user** `100` (done 15 October); leave
   **Enable cloud support for developers only** unticked, **Enable Steam Cloud sync on system suspend and resume** unticked and
   **Shared cloud App ID** at `0`. **Save**.
3. The **Auto-Cloud Configuration** section is now available. Under **Root Paths**, **Add New Root** four times and fill the
   four rows of the Root Paths table above exactly (Root, Subdirectory, Pattern, OS, Recursive). **Save**.
4. Under **Root Overrides**, **Add Root Override** once and fill the one row above. **Save**.
5. **Publish** → **Prepare for Publishing** → **Publish to Steam**. Cloud settings only reach Steam clients after publishing.
6. Install a Build 69 (or later) package through Steam on a Windows PC and on the Steam Deck and run the test in
   `docs/STEAM_HARDWARE_CHECKLIST.md` ("Steam Cloud"). Do not untick "developers only" before it passes.
7. For launch: on the store page's **Supported Features** tick **Steam Cloud** (the page's RELEASING note says this completes
   the release checklist). Use **Enable cloud support for developers only** only for testing cloud changes after the public release.

Field names checked against the owner's screenshots of the live page (15 October): Byte quota per user, Number of files allowed
per user, Enable cloud support for developers only, Enable Steam Cloud sync on system suspend and resume, Shared cloud App ID,
Root OS [Cross-Platform]. The Root Paths and Root Overrides column names follow Valve's Auto-Cloud page; if a
label reads slightly differently on the live page, the values are the same.

## 3. Steam Input — default configuration

Steamworks → your app → **Steam Input** → set the default configuration for **"Xbox Controller"** / generic gamepads to
the **Gamepad** template (no custom action set is needed: the game reads a standard gamepad and remaps in its own
Controls tab). For the Steam Deck set the default to **Gamepad** as well. Keyboard and mouse are never required.

The game's own prompts already follow the connected pad (Xbox / PlayStation / Switch glyphs, the Deck's controls as the
Xbox layout).

## 4. Steam Deck review

The Deck Verified review is Valve's. Before requesting it, run the owner checklist in `docs/STEAM_HARDWARE_CHECKLIST.md`
(the rows marked OWNER HARDWARE TEST) on a real Deck: 1280×800 Game Mode launch, controller-only play, suspend/resume,
the on-screen name picker, performance.

## 5. Not needed / not done on purpose

- **Steam's floating on-screen keyboard** (`ShowFloatingGamepadTextInput`): not wired. The game's name entry is its own
  on-screen picker (A–Z, 0–9, space, delete, OK, cancel), fully usable with the pad, the Deck's controls and touch. Steam's
  floating keyboard works by injecting key presses, which the picker deliberately ignores because the same keys drive the
  menus (WASD/arrows/Enter would press buttons while "typing"). Valve's requirement is that text entry works without a
  physical keyboard; the picker satisfies it.
- **Leaderboards, Workshop, stats beyond the achievement counters**: not part of the design.
- **Vulkan**: the renderer is OpenGL through LWJGL3; it runs under the Deck's Mesa/RADV OpenGL driver. No rewrite.
