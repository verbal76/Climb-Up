# Build 82 independent code review - remediation record (2026-10-30)

Frozen baseline: `623805c2c4a7b7d6415d2f4d98065ef8fddd8185` (tag `build-82-frozen-baseline`, branches `frozen/build-82-baseline`,
`integration/build-82-repairs`) - untouched. Repairs: branch `repair/build-82-review`, cut from that exact commit. Not merged anywhere, no
release, no Actions run started. Build 83 (`fix/build-83-root-causes`, frozen candidate 2a7935b) was read, never modified.

## Verdicts

| # | Finding | Verdict | Correction |
|---|---|---|---|
| 1 | PlayScreen.show returns early on FirstSliceFailed; resize/render dereference a null world | **Confirmed** (libGDX setScreen calls resize after show; `world.resize` NPE) | Climb start moved before the play screen (ClimbStart, with 6); defensive null-world guards in resize/render |
| 2 | Enforcer hop and crab follow use raw `j +- 1` | **Confirmed, reproduced** (live layout: both stop at a slice's decoy) | `deck(j, +-1)`; Enforcer stores its route offset `cur + step` |
| 3 | Mixed-rules climbs still set tower/pace/finish bests | **Confirmed** | `RunRecord.setsRecords` - one gate (assisted, mixed-rules, difficulty); `RunRecord.towerBests` |
| 4 | resetEncounters compares raw indices with the checkpoint | **Confirmed** (by code: side structures follow their slice's route) | `Sim.behindCheckpoint`: route position; a side structure counts by the route deck it hangs from (strictly below the checkpoint) |
| 5 | movingNear/maxPeriod hazard window uses raw indices | **Confirmed, reproduced** (misses the next slice's decks, counts a decoy's hazard) | `Autopilot.nearLink`: route decks before/after, hazards on route decks only |
| 6 | First slice + provisional successor generated synchronously on the render thread | **Confirmed** for typed codes and any start whose preparation did not match (locally 13.5 s for slice 0 on a loaded runner; the CI print of the same test gives the device-class figure). ANR: the GL thread is not Android's UI thread, so not an input ANR; a long render-thread stall can still make the platform's pause synchronisation give up if the app is backgrounded meanwhile | ClimbStart: background preparation with the existing `Tower.prepare`, "BUILDING COURSE...", play screen only when ready |
| 7 | Failure record names the previous climb's seed/difficulty/tuning | **Confirmed** | `recordGenerationFailure(seed, difficulty, tuning, what)` for the first-slice path |
| 8 | tuningStamp depends on reflection order | **Confirmed in principle** (`getDeclaredFields` order is unspecified; ART orders differently from HotSpot); not reproduced on two runtimes here | Name-ordered, exact-bits stamp; the Build 82 stamp is still accepted on resume and restamped |
| 9 | Balance values hard-coded (KNOCK_LOCKOUT, SAW_MIN_WINDOW, SafeStart.MAX_WAIT) | **Confirmed, systemic** (about 131 numeric constants in sim code) | **Not changed** - owner decision on ownership (below) |
| 10 | Enforcer allocates a ColorAttribute per material per frame | **Confirmed** | Recolour the rig's own emissive attribute in place |

## Background start: stale results, threads, failures

- Preparation is keyed on the exact seed and Tuning instance (Tower). Each poll re-requests the current selection first (`prepare` is idempotent for
  the same key), so a changed seed, difficulty or reloaded tuning is prepared afresh and a stale result is never taken; a superseded request still
  queued is cancelled (one already running finishes untouched and is ignored).
- While a climb is being built the title menu and the seeded-run keys wait: nothing changes the selection or leaves mid-build.
- The worker runs only the generator (`sim` package: no libGDX graphics calls; its mutable statics are diagnostics and the test fault hook).
  `openRun`, the save, the history file and every screen change stay on the render thread.
- A course that cannot be built returns to the title with its notice (typed code). A random seed whose first slice fails is retried by
  `openRun` as before (synchronously; first-slice failures have not been seen outside fault injection).
- Generation, replays and saves: unchanged (same pure function, other thread).

## Compatibility

- Save format: unchanged. Tuning stamp: new climbs get the new stamp; a stored Build 82 stamp that matches is accepted and restamped.
- Generation fingerprint with every repair: `978eaa5128ad2201d29dabbf` (= Build 82 rules 16).
- Slice-by-slice generation comparison, original vs repaired classes, seeds 1-3 x NORMAL and PUNISHER x 8 slices (48 slices, CRC32 of each
  slice blob): **identical**.
- Bundled seed-12 replay (BundledReplayTest) on the repaired simulation: **passes** (same finish step, event and state hashes).
- Focused tests: Build82ReviewTest 7/7 (incl. the stale-selection test, 703 s); LiveTowerDeckOrderTest 4/4 (the three runnable cases fail on
  the original code); GenerationRecoveryTest + RecordsTest + TowerPrepareTest 34/34. Not run: the full core and desktop suites (no CI on this
  branch by owner direction).

## Finding 9 - proposed ownership (owner decision)

Generator fairness rules (SAW_MIN_WINDOW, SafeStart.MAX_WAIT, reach tables) stay in code under RULES_VERSION and the fingerprint, so an OTA
tuning file can never change courses silently; player-feel values (KNOCK_LOCKOUT and similar) move to tuning.json in a future build, with equal
defaults and OTA frozen-key review. Not done in the Build 82 repair: it changes the tuning file and OTA schema of a frozen fallback for no player
benefit.

## Build 83 exposure (2a7935b, read only)

| # | Build 83 | Notes |
|---|---|---|
| 1 | Inherited | `PlayScreen.java:119/205` |
| 2 | Inherited, reproduced on Build 83 classes | `Sim.java:947/954/1666/1668/1675` |
| 3 | Inherited | `PlayScreen.java:547/550`, `RunRecord.java:41` |
| 4 | Inherited | `Sim.java:464/467` |
| 5 | Inherited, reproduced; **new instance**: `avlLiveNear` (`Autopilot.java:394`, rules 18) uses a raw `a0-6..a0+2` window | |
| 6 | Inherited, **heavier**: radius 11.5 slices cost more and vary by seed (one seed's slice 0 about 3.5x another's) | the plan cache is per thread (ThreadLocal), so concurrent preparation and tower growth do not race; up to about 8 MB per open scope |
| 7 | Inherited | `ClimbGame.java:255/336` |
| 8 | Inherited | `ClimbGame.java:315` |
| 9 | Inherited, grown (about 160 constants) | C1 reach tables, SHORTCUT_MARGIN, layer spacing |
| 10 | Inherited only | `WorldRenderer.java:1437`; new Atmos/Juice/TipBanner code allocates nothing per frame |

Recommendation: leave the frozen candidate and its campaign alone; port 1, 3, 6, 7, 8, 10 as a separate generation-independent change after
the campaign (re-implemented against Build 83's code); treat 2, 4, 5 and `avlLiveNear` as a rules change for Build 83 with its own fingerprint,
generation comparison and replay check.
