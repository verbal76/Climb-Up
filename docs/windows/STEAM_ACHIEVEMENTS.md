# Upwardly (Windows) - Steam achievements

Windows/desktop only. Android and the plain (non-Steam) zip build are unaffected: the hook is a no-op unless a Steam
client is running. **Nothing has been uploaded to Steam** - the owner still has to create these 27 achievement
definitions and 4 stats in Steamworks (using the exact API ids below) before any can unlock.

- **App ID:** `5427130` (public - it is in the store URL, not a secret). Constant `SteamManager.STEAM_APP_ID`; dev runs also read `steam_appid.txt` at the repo root (dev only; never shipped).
- **Library:** `com.code-disaster.steamworks4j:steamworks4j:1.9.0` (Maven Central), added to the `:desktop` module only. The wrapper is MIT-licensed; it bundles Valve's Steamworks SDK redistributable natives.
- **License note:** the Steamworks SDK is a **platform-SDK exception** to the project's CC0/original asset policy - it is the required, proprietary SDK to ship on Steam. It is pulled only via the Maven dependency (the SDK is not committed to this repo). The game still makes **no network calls of its own**; any online work (unlocking, stats) is done by the Steam client.
- **Decision logic lives in core:** `com.hotatticgames.climbup.sim.AchievementRules` is a pure, engine-free, unit-tested class that maps simulation events/state -> achievement ids. The desktop layer (`SteamManager` + `SteamAchievements`) only forwards those ids and persists the integer stats. See `AchievementRulesTest`.

## The 27 achievements

API ids are EXACT and must match the Steamworks entries. Display names and descriptions are suggestions for the owner.

| # | API id | Display name | Description | Fires on (existing event / state) |
|--:|--------|--------------|-------------|------------------------------------|
| 1 | `ACH_TOWER_1` | First Castle | Reach the first tower. | `SaveData.towers >= 1` |
| 2 | `ACH_TOWER_5` | Halfway Up | Reach the fifth tower. | `SaveData.towers >= 5` |
| 3 | `ACH_SUMMIT` | To the Top | Finish all ten towers. | `Sim.EV_FINISH` (castle-10 door) |
| 4 | `ACH_FINGERTIP` | By the Fingertips | Catch a ledge over a too-big gap and haul yourself up. | `EV_PULL` with resulting mode `PULLUP` |
| 5 | `ACH_ROPE_TOP` | Top of the Rope | Mantle onto the beam at the top of a rope. | `EV_PULL` with resulting mode `BEAM` |
| 6 | `ACH_MIDAIR_CATCH` | Nice Catch | Land on a moving platform straight out of the air. | `EV_LAND` on a `MOVE_H/MOVE_V/MOVE_Z` |
| 7 | `ACH_CRAB_OFF` | Crab Rangoon | Knock a crab (or bee) off with the spiked club. | `EV_CRAB_OFF` |
| 8 | `ACH_BOUNCE` | Boing! | Launch off a trampoline / bouncy slab. | `EV_BOUNCE` |
| 9 | `ACH_INFINITY_ENTER` | Beyond the Top | Climb past the summit into the endless section. | `finished` and `>= 1 m` past castle 10 |
| 10 | `ACH_INFINITY_BEST` | New Heights | Beat your best distance into the endless section. | endless metres > prior record (`STAT_BEST_INFINITY_M`) |
| 11 | `ACH_SPACE` | Deep Space | Reach the space world. | current world index == `Palette.SPACE` (4) |
| 12 | `ACH_CLEAN_RUN` | No Take-Backs | Finish without ever falling back to a checkpoint. | `EV_FINISH` and `climbFalls == 0` |
| 13 | `ACH_FIRST_FALL` | Gravity: 1, You: 0 | Your first-ever fall back to a checkpoint. | lifetime `falls >= 1` |
| 14 | `ACH_FALL_25` | Persistence Over Skill | Recover from 25 falls. | lifetime `falls >= 25` (`STAT_FALLS`) |
| 15 | `ACH_FALL_100` | The Ground Remembers You | Recover from 100 falls. | lifetime `falls >= 100` (`STAT_FALLS`) |
| 16 | `ACH_FALL_EVERY_TOWER` | Dropped But Not Out | Fall and recover on all ten towers. | 10-bit `STAT_TOWER_FELL_MASK` fully set |
| 17 | `ACH_RAGE_SPOT` | It's Personal Now | Clear a spot after failing it five times in a row. | 5x `EV_RESPAWN` at one checkpoint, then `EV_CHECKPOINT` past it |
| 18 | `ACH_INFINITY_500` | Still Climbing | 500 m into the endless section. | endless metres >= 500 |
| 19 | `ACH_INFINITY_1000` | Thin Air | 1000 m into the endless section. | endless metres >= 1000 |
| 20 | `ACH_INFINITY_2500` | Why Are You Still Here | 2500 m into the endless section. | endless metres >= 2500 |
| 21 | `ACH_INFINITY_5000` | Basically Astronaut | 5000 m into the endless section. | endless metres >= 5000 |
| 22 | `ACH_ALL_WORLDS` | Grand Tour | Reach every world in a single run. | all 5 world indices seen this run |
| 23 | `ACH_NO_CHECKPOINT_WORLD` | No Safety Net | Clear an entire world without falling back to a checkpoint. | crossed up a world band with no `EV_RESPAWN` in it |
| 24 | `ACH_SPEED_SUMMIT` | Express Elevator | Finish all ten towers under the time limit. | `EV_FINISH` and `finishTime <= SPEED_SUMMIT_SECONDS` |
| 25 | `ACH_PERFECT_SUMMIT` | Flawless | Finish all ten towers with no falls and no hazard hits. | `EV_FINISH` and `climbFalls == 0 && climbHits == 0` |
| 26 | `ACH_SWING_CHAIN` | Acrobat | Chain rope -> swing -> mid-air catch without touching the ground. | `EV_ROPE` -> swing super-jump (`EV_JUMP+EV_BOUNCE`) -> `EV_LAND` on a mover, no solid ground between |
| 27 | `ACH_MARATHON` | The Long Haul | Climb 50,000 m across all your runs. | cumulative `STAT_MARATHON_M >= MARATHON_METERS` |

### Clean vs Flawless (distinct on purpose)
- `ACH_CLEAN_RUN` = finished with **no checkpoint fall-backs** (`climbFalls == 0`).
- `ACH_PERFECT_SUMMIT` = finished with **no fall-backs AND no hazard hits** (`climbFalls == 0 && climbHits == 0`) - a strict superset, so "Flawless" is harder.

## Steam INT stats (define these in Steamworks too)
These are persisted by Steam across sessions on the player's machine; the unlock DECISION still happens in core. All are
kept monotonic in `SteamManager` (max, or bitwise-OR for the mask) so a stale read can never overwrite a larger value.

| Stat id | Meaning | Used by |
|---------|---------|---------|
| `STAT_FALLS` | lifetime fall count (mirror of `SaveData.falls`) | `ACH_FALL_25`, `ACH_FALL_100` |
| `STAT_MARATHON_M` | cumulative metres climbed across all runs | `ACH_MARATHON` |
| `STAT_TOWER_FELL_MASK` | 10-bit mask of towers fallen on | `ACH_FALL_EVERY_TOWER` |
| `STAT_BEST_INFINITY_M` | best distance into the endless section | `ACH_INFINITY_BEST` |

## Tunable constants (TODO: owner to confirm after a Steam play-test)
In `AchievementRules`:
- `SPEED_SUMMIT_SECONDS = 1200f` (20 minutes) - the `ACH_SPEED_SUMMIT` limit; a first guess.
- `MARATHON_METERS = 50000` - the `ACH_MARATHON` total; a first guess.
- `FALL_25_THRESHOLD = 25`, `FALL_100_THRESHOLD = 100`, `RAGE_SPOT_FAILS = 5` - as named in the design.

## Achievement-only counters added (no physics / progression touched)
These are pure telemetry derived from events the game already fires; none is read by the simulation:
- `SaveData.climbFalls`, `SaveData.climbHits` - fall-backs / hazard hits in the current climb (reset on a new climb), so a clean/flawless summit survives a save-and-resume. New save fields; libGDX JSON tolerates them (old saves default to 0).
- In-session counters inside `AchievementRules`: worlds-seen-this-run mask, same-spot fail streak, no-checkpoint-world tracking, swing-chain latch. The per-tower fell mask is seeded from `STAT_TOWER_FELL_MASK` so it completes across sessions.

## Not verified here
Actual unlocking needs the owner's Windows machine + a running Steam client + the real app with these ids defined in
Steamworks. Linux/CI cannot run Steam, so only compilation and the pure-logic unit tests run here.
