# Steam achievements and stats: entry sheet (App ID 5427130)

Use this sheet while you enter Upwardly's achievements and stats on the Steamworks partner site. Everything in it was
read from the game's source on 15 October 2026. The game code is not changed by this sheet.

- **27 achievements, 4 stats.** The API names below are the exact strings the game sends to Steam
  (`core/src/main/java/com/hotatticgames/climbup/sim/AchievementRules.java`). Steam matches them letter for letter,
  capitals included. If one differs, that achievement can never unlock and nothing tells you why.
- **Nothing is entered in Steamworks yet.** Assume every row below still has to be typed in.
- **Icons (final, owner approved 15 October 2026, Style A "Sky Diorama"):** 256 x 256 PNG, two per achievement, in
  `branding/steam/achievements/Upwardly_Steam_Achievement_Icons.zip` (also unzipped in
  `branding/steam/achievements/icons/`, with `manifest.csv`). `ACH_<NAME>_unlocked.png` goes in **Achieved Icon**,
  `ACH_<NAME>_locked.png` in **Unachieved Icon**. `Upwardly_Steam_Achievements.pdf` beside the zip shows every icon
  with its API name, name and description.
- **Thresholds:** Express Elevator (20 minutes) and The Long Haul (50,000 m) are approved for now and may be tuned
  after Steam play-testing. If they change, only the description text here and the constant in `AchievementRules`
  change. The API names stay the same.

## 1. The four stats (enter these first)

On the Stats page, every stat uses these settings:

| Setting | Value |
|---|---|
| Type | **INT** |
| Set By | **Client** |
| Default Value | **0** |
| Min Val | **0** |
| Increment Only | **ticked** (the game only ever raises these values, so Steam can safely refuse a lower one) |
| Max Change | leave empty |
| Aggregated | leave unticked (no global totals are used) |

| # | API Name | Display Name | Max Val | What it holds | How the game updates it | Used by |
|--:|---|---|---|---|---|---|
| S1 | `STAT_FALLS` | Falls | leave empty | Lifetime falls back to a checkpoint | Copied from the save's lifetime fall count every game step, then raised only if it is higher than what Steam has | `ACH_FALL_25`, `ACH_FALL_100` (they are decided from the save itself, so the stat is a record of the count) |
| S2 | `STAT_MARATHON_M` | Metres climbed | leave empty | Total new height climbed across all play sessions, in metres | At the start of each climb the game reads the stored value, then adds this session's net climb (highest point minus start point) | `ACH_MARATHON` |
| S3 | `STAT_TOWER_FELL_MASK` | Towers fallen on | **1023** | A 10-bit record of which of the ten towers you have fallen on (1 bit per tower; 1023 = all ten) | Read at the start of a climb, a bit is set on each fall, and the result is combined with Steam's value bit by bit so no tower is ever forgotten | `ACH_FALL_EVERY_TOWER` |
| S4 | `STAT_BEST_INFINITY_M` | Best endless distance | leave empty | Best distance past the summit, in metres | Raised whenever this climb goes further past the summit than the stored best | `ACH_INFINITY_BEST` |

Source: `AchievementRules.java` lines 60-65 (names), `PlayScreen.java` `show()` (read at climb start) and
`achievements()` (written every step), `desktop/.../SteamManager.java` `applyStat` (never lowers a value; the
tower stat is combined bit by bit).

## 2. The 27 achievements

On the Achievements page, every achievement uses **Set By: Client** and **Hidden: unticked**. Hiding some is up to
you; nothing in the game depends on it. **Progress Stat:** leave it as none. Linking `ACH_FALL_25`/`ACH_FALL_100` to
`STAT_FALLS` or `ACH_MARATHON` to `STAT_MARATHON_M` would show a progress bar on Steam. That is optional and
can be added later.

Icon files: `ACH_<NAME>_unlocked.png` goes in **Achieved Icon** and `ACH_<NAME>_locked.png` goes in **Unachieved
Icon**.

| # | API Name | Display Name | Description (paste into Steam) | Exact coded condition |
|--:|---|---|---|---|
| 1 | `ACH_TOWER_1` | First Castle | Reach the first castle. | castles opened this climb >= 1 |
| 2 | `ACH_TOWER_5` | Halfway Up | Reach the fifth castle. | castles opened this climb >= 5 |
| 3 | `ACH_SUMMIT` | To the Top | Reach the summit of all ten towers. | the finish (or win) event fires |
| 4 | `ACH_FINGERTIP` | By the Fingertips | Catch a ledge by your fingertips and pull yourself up. | a pull-up event that leaves the climber in the ledge pull-up |
| 5 | `ACH_ROPE_TOP` | Top of the Rope | Climb to the top of a rope and haul yourself onto the beam. | a pull-up event that leaves the climber on the beam at a rope top |
| 6 | `ACH_MIDAIR_CATCH` | Nice Catch | Land on a moving platform straight out of the air. | a landing on a sideways, up-down or in-out moving platform |
| 7 | `ACH_CRAB_OFF` | Crab Rangoon | Knock a crab or a bee off with the spiked club. | the crab-off event fires |
| 8 | `ACH_BOUNCE` | Boing! | Get launched by a trampoline, spring or swing. | the bounce event fires (trampoline, spring, bouncy slab or swing launch) |
| 9 | `ACH_INFINITY_ENTER` | Beyond the Top | Keep climbing past the summit. | after finishing, at least 1 m above the tenth castle |
| 10 | `ACH_INFINITY_BEST` | New Heights | Beat your best distance past the summit. | this climb's distance past the summit is greater than a stored best that is above 0 |
| 11 | `ACH_SPACE` | Deep Space | Reach Deep Space. | the current world is Deep Space (the fifth world) |
| 12 | `ACH_CLEAN_RUN` | No Take-Backs | Reach the summit without ever falling back to a checkpoint. | at the finish, falls back this climb == 0 |
| 13 | `ACH_FIRST_FALL` | Gravity: 1, You: 0 | Fall back to a checkpoint for the first time. | lifetime falls >= 1 |
| 14 | `ACH_FALL_25` | Persistence Over Skill | Fall back to a checkpoint 25 times. | lifetime falls >= 25 |
| 15 | `ACH_FALL_100` | The Ground Remembers You | Fall back to a checkpoint 100 times. | lifetime falls >= 100 |
| 16 | `ACH_FALL_EVERY_TOWER` | Dropped But Not Out | Fall back to a checkpoint on every one of the ten towers. | all 10 bits of the tower-fall record set (kept across sessions in `STAT_TOWER_FELL_MASK`) |
| 17 | `ACH_RAGE_SPOT` | It's Personal Now | Fall at the same spot five times in a row, then get past it. | 5 or more falls in a row back to the same checkpoint, then a later checkpoint is reached |
| 18 | `ACH_INFINITY_500` | Still Climbing | Climb 500 m past the summit. | distance past the summit >= 500 m |
| 19 | `ACH_INFINITY_1000` | Thin Air | Climb 1,000 m past the summit. | >= 1000 m |
| 20 | `ACH_INFINITY_2500` | Why Are You Still Here | Climb 2,500 m past the summit. | >= 2500 m |
| 21 | `ACH_INFINITY_5000` | Basically Astronaut | Climb 5,000 m past the summit. | >= 5000 m |
| 22 | `ACH_ALL_WORLDS` | Grand Tour | Pass through all five worlds in one sitting. | all 5 worlds seen since the climb was opened this session |
| 23 | `ACH_NO_CHECKPOINT_WORLD` | No Safety Net | Climb out of a world without falling back to a checkpoint in it. | crossed up into the next world with no fall back since entering this one |
| 24 | `ACH_SPEED_SUMMIT` | Express Elevator | Reach the summit in under 20 minutes. | at the finish, run clock > 0 and <= 1200 s |
| 25 | `ACH_PERFECT_SUMMIT` | Flawless | Reach the summit without falling back or being hit by a hazard. | at the finish, falls back == 0 and hazard hits == 0 this climb |
| 26 | `ACH_SWING_CHAIN` | Acrobat | Go from a rope to a swing to a moving platform without touching solid ground. | rope grabbed, then a swing super-jump, then a landing on a moving platform, with no landing on solid ground in between (landing on a swing does not break it) |
| 27 | `ACH_MARATHON` | The Long Haul | Climb 50,000 m in total. | `STAT_MARATHON_M` (stored total plus this session) >= 50,000 |

The display names are the ones proposed with the achievements code (`docs/windows/STEAM_ACHIEVEMENTS.md`). Some
descriptions are reworded so they say exactly what the code checks. Any of the text can change without touching code.

### Audit detail (for reference)

| # | API Name | Event that triggers it | Stats involved | Cumulative? | Unlocks with Steam in offline mode? | Unit test (`AchievementRulesTest`) |
|--:|---|---|---|---|---|---|
| 1 | `ACH_TOWER_1` | castle door opened | none | within one climb | yes | `towerMilestonesFireOnceAtTheRightCount` |
| 2 | `ACH_TOWER_5` | castle door opened | none | within one climb | yes | `towerMilestonesFireOnceAtTheRightCount` |
| 3 | `ACH_SUMMIT` | `EV_FINISH` / `EV_WIN` | none | no | yes | `cleanRunPerfectAndSpeedAreDistinct` |
| 4 | `ACH_FINGERTIP` | `EV_PULL`, mode `PULLUP` | none | no | yes | `fingertipAndRopeTopAreDistinguishedByResultingMode` |
| 5 | `ACH_ROPE_TOP` | `EV_PULL`, mode `BEAM` | none | no | yes | `fingertipAndRopeTopAreDistinguishedByResultingMode` |
| 6 | `ACH_MIDAIR_CATCH` | `EV_LAND` on `MOVE_H/MOVE_V/MOVE_Z` | none | no | yes | `midairCatchOnlyCountsTranslatingMovers` |
| 7 | `ACH_CRAB_OFF` | `EV_CRAB_OFF` | none | no | yes | `crabOffAndBounce` |
| 8 | `ACH_BOUNCE` | `EV_BOUNCE` | none | no | yes | `crabOffAndBounce` |
| 9 | `ACH_INFINITY_ENTER` | height past the summit | none | no | yes | `infinityThresholds` |
| 10 | `ACH_INFINITY_BEST` | height past the summit | reads `STAT_BEST_INFINITY_M` | across sessions | yes, needs a best already stored | `infinityBestNeedsAPriorRecordToBeat` |
| 11 | `ACH_SPACE` | world index | none | no | yes | `spaceAndAllWorlds` |
| 12 | `ACH_CLEAN_RUN` | finish | none | within one climb (survives save and resume) | yes | `cleanRunPerfectAndSpeedAreDistinct` |
| 13 | `ACH_FIRST_FALL` | lifetime falls (save file) | writes `STAT_FALLS` | lifetime | yes | `fallMilestonesAtOneTwentyFiveAndHundred` |
| 14 | `ACH_FALL_25` | lifetime falls (save file) | writes `STAT_FALLS` | lifetime | yes | `fallMilestonesAtOneTwentyFiveAndHundred` |
| 15 | `ACH_FALL_100` | lifetime falls (save file) | writes `STAT_FALLS` | lifetime | yes | `fallMilestonesAtOneTwentyFiveAndHundred` |
| 16 | `ACH_FALL_EVERY_TOWER` | `EV_RESPAWN` on each tower | `STAT_TOWER_FELL_MASK` | across sessions | yes | `fallEveryTowerNeedsAllTenAndRespectsTheSeed` |
| 17 | `ACH_RAGE_SPOT` | `EV_RESPAWN` x5 then `EV_CHECKPOINT` | none | within one session | yes | `rageSpotNeedsFiveFailsInARowThenClearing` |
| 18-21 | `ACH_INFINITY_500/1000/2500/5000` | height past the summit | none | within one climb | yes | `infinityThresholds` |
| 22 | `ACH_ALL_WORLDS` | world index | none | within one session | yes | `spaceAndAllWorlds` |
| 23 | `ACH_NO_CHECKPOINT_WORLD` | world change | none | no | yes | `noCheckpointWorldRequiresAFallFreeWorld` |
| 24 | `ACH_SPEED_SUMMIT` | finish + run clock | none | no | yes | `cleanRunPerfectAndSpeedAreDistinct` |
| 25 | `ACH_PERFECT_SUMMIT` | finish + falls/hits this climb | none | within one climb | yes | `cleanRunPerfectAndSpeedAreDistinct` |
| 26 | `ACH_SWING_CHAIN` | `EV_ROPE` -> `EV_JUMP+EV_BOUNCE` -> `EV_LAND` on a mover | none | no | yes | `swingChainRopeThenSwingThenMidairCatch`, `swingChainIsBrokenByTouchingSolidGround` |
| 27 | `ACH_MARATHON` | net new height | `STAT_MARATHON_M` | across sessions | yes | `marathonCrossesItsThreshold` |

"Offline mode" means the Steam client is running in offline mode. Steam keeps unlocks and stats locally and syncs
them when it reconnects. With **no Steam client at all** (the plain GitHub build), nothing is sent:
- Achievements decided from the save file (towers, falls) unlock the next time the game runs with Steam, because the
  condition still holds.
- One-off moments (a bounce, a catch, a finish) have to happen again.
- The cross-session stats (metres climbed, towers fallen on, best endless distance) do not grow without Steam.

### Points to be aware of (found in the audit, not changed)

1. **`ACH_ALL_WORLDS` means one sitting, not one climb.** The record of worlds seen starts again whenever a climb is
   opened, including when you resume a saved climb after restarting the game. The description above says "in one
   sitting" so it matches the code.
2. **`ACH_NO_CHECKPOINT_WORLD` after resuming:** if you resume a climb partway through a world and reach the next one
   without falling, it counts, even though you only climbed part of that world. This is rare and in the player's favour.
3. **`ACH_MARATHON` counts new height only.** Climbing back up after a fall does not add metres. It also only grows
   while Steam is running.
4. **`ACH_INFINITY_BEST` cannot unlock on your first trip past the summit.** It needs a stored best above 0 to beat.
5. **Stats read before Steam is ready.** The stored stats are read when a climb starts. If Steam has not delivered
   them yet (normally it takes about a second after launch), that session starts from 0 for metres climbed and towers
   fallen on. Steam's stored values are never lowered (the game keeps the larger value and adds tower bits), so nothing
   is lost, but that session's metres are not added to the total.
6. **If Steam reports that the stats could not be loaded,** the game does not ask again during that session. Unlocks
   from that session wait in memory and are dropped at exit. State-based achievements unlock again next session;
   one-off moments have to be repeated.
7. **`ACH_MIDAIR_CATCH` is easy:** any landing on a moving platform counts, including a short hop onto one.

None of these blocks configuring Steamworks. Any change to them is a gameplay-side code change and needs your go-ahead.

## 3. Click by click in Steamworks

1. Sign in at **partner.steamgames.com** and open **Upwardly (5427130)**.
2. Open **App Admin**, then **Edit Steamworks Settings**. Open the **Stats & Achievements** tab and choose **Stats**.
3. For each row of section 1, click **New Stat** and fill in:
   - **API Name**: exactly as in the table.
   - **Type**: INT.
   - **Set By**: Client.
   - **Increment Only**: ticked.
   - **Min Val**: 0.
   - **Max Val**: 1023 for `STAT_TOWER_FELL_MASK`, empty for the others.
   - **Default Value**: 0.
   - **Display Name**: as in the table.

   Click **Save** on the row. Do this for all four.
4. Choose **Achievements** (same tab). Click **New Achievement**. A row appears with placeholder text. Fill it in left to
   right (this matches the page as it looked on 15 October 2026):
   - **API Name** (top box, shows `NEW_ACHIEVEMENT_1_0`): select all, delete, type the API name from the table, e.g.
     `ACH_TOWER_1`.
   - **Progress Stat**: leave **None**. Leave **Min value 0** and **Max 0** as they are.
   - **Display Name** (upper box, shows `NEW_ACHIEVEMENT_NAME_1_0`): replace it with the display name, e.g. `First Castle`.
   - **Description** (lower box, shows `NEW_ACHIEVEMENT_DESC_1_0`): replace it with the description, e.g.
     `Reach the first castle.`
   - **Set By**: **Client** (already selected).
   - **Hidden?**: leave unticked.
   - **Achieved Icon**: **Choose File**, pick `ACH_TOWER_1_unlocked.png`, then click **Upload** under it.
   - **Unachieved Icon**: **Choose File**, pick `ACH_TOWER_1_locked.png`, then click **Upload** under it.
   - Click **Save** at the right end of the row.

   Then **New Achievement** again for the next row, 27 times in the table's order.
5. Icons: unzip `Upwardly_Steam_Achievement_Icons.zip` first; every file is named after its API name, so pick the
   pair whose name matches the row's API Name. If **Upload** does nothing on a brand-new row, save the row first, then
   open it again (its Edit link), upload the two icons and Save.
6. Check the page: it lists **27 achievements**, every API name starts `ACH_`, and nothing has a typo or a stray
   space. The Stats page lists **4 INT stats**. Compare them against this sheet one row at a time.
7. Publish: open the **Publish** tab, click **Prepare for Publishing**, then **Publish to Steam**. Type the
   confirmation text Steam asks for and confirm. **Until you publish, the Steam client does not know the new
   achievements and the game cannot unlock them.**
8. Check after publishing: with the Steam client open, look at Upwardly's page in your Library. The achievements
   section should show 0 / 27. Before release, achievements do not show on the public Community pages; that is
   expected.

Only you can do these steps. Nothing in the repository, and no build, does them.

## 4. Real Steam-client test (Build 69 or the next Steam test build)

Not done yet. Nothing has been tested with a real Steam client. Run these steps on a test account.

1. **Configure and publish** the stats and achievements (section 3, steps 1 to 8).
2. **Launch through Steam:** install the Steam build (depot or a branch you set up) and start Upwardly from the
   Steam Library, not from the zip.
3. **Check that Steam started:** open `desktop.log` in the game's data folder. Windows: `%APPDATA%\HotAtticGames\Upwardly`.
   Linux and Steam Deck: `~/.local/share/HotAtticGames/Upwardly`. Look for these lines:
   `[steam] Steam initialised (App ID 5427130)` and then `[steam] stats received`.
   `Steam client not running or init failed` means the game did not reach Steam.
4. **Trigger a simple achievement:** start a climb and bounce on the first trampoline (`ACH_BOUNCE`). Then fall back
   to a checkpoint once (`ACH_FIRST_FALL`).
5. **Confirm Steam received it:** the Steam pop-up should appear in the corner. The log shows
   `[steam] achievement unlocked: ACH_BOUNCE`. Library > Upwardly > Achievements shows it with its unlocked icon.
6. **Confirm the stats:** Steam does not list stat values in the Library, so check them through what they feed.
   Keep falling: `ACH_FALL_25` should unlock on the 25th lifetime fall. Fall once on a tower you have not fallen on
   before, quit, and start again: the game should still remember that tower (`ACH_FALL_EVERY_TOWER` needs all ten
   across sessions). If you link a progress stat (optional), its progress bar moves as you play.
7. **Restart and check persistence:** quit, start again from Steam. Unlocked achievements stay unlocked. The log
   shows `stats received` again. Climbing more adds to the metres total instead of starting again.
8. **Offline mode:** in Steam, choose **Steam > Go Offline**, start Upwardly, unlock something new (for example
   `ACH_TOWER_1`). The log should still show `Steam initialised`.
9. **Reconnect:** choose **Go Online**. The achievement from step 8 should appear as unlocked on Steam within a few
   minutes.
10. **Steam Deck:** repeat steps 2 to 5 on the Deck in Game Mode. Do this before the Windows test if the Deck is the
    main target. The pop-up appears at the bottom of the screen.
11. **Without Steam:** quit Steam completely and run the GitHub zip build. The game must play normally. The log
    shows `Steam client not running or init failed - achievements disabled` and nothing unlocks. Android has no Steam
    code at all.

### Resetting achievements while testing (Steam console)

Open the console by typing `steam://open/console` in a browser address bar or the Windows **Run** box. In the
Console tab:
- `achievement_clear 5427130 ACH_BOUNCE` locks that one achievement again (use any API name).
- `reset_all_stats 5427130` sets **every stat and every achievement for Upwardly back to zero / locked.**

> **Warning:** both commands permanently wipe that account's progress for Upwardly. They cannot be undone. Use them
> only on a test account, never on an account whose unlocks you want to keep. The game never runs these commands
> itself.

## 5. What is not verified

- Nothing above has run with a real Steam client: no unlock, stat store, offline sync or Deck pop-up has been seen.
  The game logic is covered by 17 unit tests on the rules. The desktop Steam layer is only compiled; continuous
  integration cannot run Steam.
- The Linux and Steam Deck build includes the Steam library's Linux files (the steamworks4j 1.9.0 jar ships
  `libsteam_api.so`). It has never been started under the Steam client on Linux.
