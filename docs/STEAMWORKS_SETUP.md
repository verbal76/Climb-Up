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

## 2. Steam Cloud — use Auto-Cloud (no code needed, conflict-safe)

The game writes its progress with atomic replace-on-rename and keeps per-device settings in separate files, so Steam's
Auto-Cloud can sync the save folder as it is. Steam itself preserves local saves while offline and shows its conflict
dialog when both the local and the cloud copy changed, so newer progress is never silently overwritten.

Steamworks → your app → **Steam Cloud** → enable, then under **Auto-Cloud** add these root-path entries:

| Root | Subdirectory | Pattern | OS | Recursive |
|---|---|---|---|---|
| `WinAppDataRoaming` | `HotAtticGames/Upwardly` | `players.json` | Windows | no |
| `WinAppDataRoaming` | `HotAtticGames/Upwardly/players` | `*` | Windows | yes |
| `LinuxXdgDataHome` | `HotAtticGames/Upwardly` | `players.json` | Linux | no |
| `LinuxXdgDataHome` | `HotAtticGames/Upwardly/players` | `*` | Linux | yes |

What that syncs and why:

- `players.json` — the list of named players and which one is selected.
- `players/pN/save.json` — that player's progress, best height, records and castle times (versioned; migrations are built in).
- `players/pN/run.json` — the climb in progress (SAVE & EXIT / autosave snapshot); harmless if absent.
- `players/pN/history.bin` — the generated slices of the climb in progress (append-only, needed to resume exactly where the player left off).

What must **not** be synced (per-device, excluded by the patterns above): `settings.json` (sound, text size, quality — Valve
asks that graphics settings stay per device), `upwardly-desktop.cfg` (window, display mode, key bindings, controller
profiles), `desktop.log`, and any `*.tmp` / `*.corrupt` files.

Byte quota: a player folder is well under 1 MB in normal play (`history.bin` grows with a very long climb; 16 MB per user is
plenty). Set **"Enable cloud synchronization on the Steam client"** to on. The in-game data never references absolute paths,
so Windows and Linux (Steam Deck) share the same cloud files.

Cross-device conflicts: Steam's own "Cloud sync conflict" dialog appears before launch when the local and cloud copies
diverge; the player picks one. The game needs no code for this and has none.

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
