# Named players (branch exp/feature-players)

Several named players, each with their own saved climb, records and stats. Settings stay shared. All of it lives in `core/`, so Android and Windows get it from the same code.

## What the player sees
- Title menu (left grid): CONTINUE or PLAY, NEW RUN (only when there is a climb), PLAYERS, SETTINGS, CREDITS, EXIT. One wide primary button, then pairs; an odd last button takes the full width. Room for 7 (a TIMES button goes between SETTINGS and CREDITS: add it to `TitleScreen.Item`, its label, and `choose`).
- Title (right): "PLAYING AS <NAME>", the hero picker, and LEGACY RUNS when there are old runs.
- EXIT saves (`persist`) and closes the game (`Gdx.app.exit`). No confirm.
- PLAYERS: up to 6 players, two per row. Tap a name to play as them (back to the title). DELETE asks first (CANCEL is the highlighted button); deleting the last player leaves a fresh empty PLAYER 1. NEW PLAYER opens the name picker.
- Name picker: A-Z, 0-9, SPACE, DELETE, OK, CANCEL, all `ui.button`s, at most 10 characters, capitals only, no leading or doubled spaces, no duplicate names (any case). Raw typed keys are not read (the Windows menu navigation uses W/A/S/D, arrows, Enter, Space and Esc, which would clash); with it WASD/arrows + Enter walk the picker and CANCEL answers Esc. The phone's Back button cancels.
- SETTINGS > ERASE ALL PLAYERS removes every player and the settings (it used to erase the one save).
- The last player used is selected on the next start.

## Storage (`Profiles`, `SaveStore`, `SaveData` v7)
```
<data dir>/settings.json              shared settings
<data dir>/players.json               index: lastId, players [{id,name}]  (atomic write; rebuilt from the folders if damaged)
<data dir>/players/p<id>/save.json    that player's SaveData (v7: new field `name`)
<data dir>/players/p<id>/run.json     that player's SAVE & EXIT snapshot
<data dir>/players/p<id>/history.bin  that player's climb (slice bytes, unchanged format)
```
- `SaveData` is the per-player object: put new per-player records (ten-tower times, the infinity record) there as new fields. Nothing else needs to change; `SaveStore.sanitize` is where to range-check them.
- v6 -> v7: only `name` is new. The old single save (`save.json`, `run.json`, `history.bin` in the data folder) is copied into `players/p1` as "PLAYER 1", the index is written, then the originals are deleted. A crash before the index is written redoes the copy from the untouched originals. A damaged old save is moved with the player and recovered the usual way (`save.json.corrupt`).
- `ClimbGame`: `store` and `history` now point at the current player's folder; `settingsStore` is the data folder. `switchPlayer`, `addPlayer`, `deletePlayer`, `eraseEverything`.
- `ClimbGame.anyClimbInProgress()` covers every player. The OTA module (`ModuleGame`) reports that to the host instead of only the current player's climb, so a generator change never lands on any player's unfinished climb.

## Layout (`TitleLayout`, `Dp`)
- Pure numbers, no GL. Everything sits in a 1200-unit area centred on the screen, so 21:9 only adds scenery. The menu is on the left, the hero keeps the middle, the right column holds the rest.
- Button height comes from the real density (`Dp.unitsPerDp`): at least 48dp (primary 56dp when there is room), the normal 84/96 units on big screens, and rows shrink to fit rather than overlap. `-Dclimb.upd=2` simulates a small phone on a desktop.
- The same 48dp rule applies to the players list, the name picker and their dialogs.

## Tests
`ProfilesTest` (migration, crash points, damaged index/saves, independence, delete, name rules, switching, guard), `TitleLayoutTest` (no overlaps for 1 to 8 buttons, 6 screen shapes, densities from a 4K TV to a 360dp phone; 48dp; labels fit). `PersistenceTest` still passes unchanged.

## Changed for the OTA
`SaveData.CURRENT_VERSION` is 7. A module that carries this must be signed with `--save-schema 7 --save-min 6` (it reads v6 saves and migrates them); the host then snapshots the data folder before its first launch, so a rollback finds the old single save untouched. `tools/otalab/ship/release.txt` and the workflow were not edited here.
