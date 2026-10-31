# Build 83 release hardening - remediation record (2026-10-31)

Pre-repair baseline: `fix/build-83-root-causes` @ `2a7935bec51ffba7ff3ab128bfab2f2419cf1db7` (rules 19, fingerprint `b0c5646446610a0274e036ab`) -
untouched, with its evidence: CI 41117970356 (success), campaign 41137500091 (9 courses, every job green), T5 audit 41137503239 (every job
green). Hardening branch: `release/build-83-hardening`, cut from that exact commit. Build 82 (`frozen/build-82-baseline`, tag
`build-82-frozen-baseline`, `ccr-cb458cd1-w4dh5c`) untouched. The Build 82 review findings and their Build 83 exposure are in
`BUILD82_REVIEW_REMEDIATION_2026-10-30.md` (branch `repair/build-82-review`); each was re-implemented here against Build 83's code, not copied.

## Changes and classification

A = generation-neutral (presentation, start flow, records, saves, diagnostics). B = simulation / gameplay behaviour at play time.
C = can affect generation or the solver (the generator proves links by running `Sim` and `Autopilot`).

| # | Change | Class | Where |
|---|---|---|---|
| 1 | A typed code that cannot be built is refused on the title, before the play screen (no null-world crash); defensive null-world guards in resize/render | A | ClimbStart, TitleScreen, SeedScreen, PlayScreen |
| 2 | Climb preparation off the render thread: keyed on seed and Tuning instance, stale results never taken, superseded requests cancelled, menu input held while building, failures returned to the title; generator only on the worker | A | ClimbStart, ClimbGame, Tower |
| 3 | Enforcer hop and crab follow step by route deck (`deck(j, +-1)`), not raw index; the Enforcer stores its route offset | B / C | Sim |
| 4 | Checkpoint encounter reset compares route positions (`behindCheckpoint`): side structures count by the route deck they hang from | B / C | Sim |
| 5 | Autopilot moving-hazard window (`movingNear`, `maxPeriod`) counts route decks, hazards on route decks only (`nearLink`) | C | Autopilot |
| 6 | Autopilot live-avalanche span (`avlLiveNear`) counts six route decks behind to two ahead (`avlSpan`) | C | Autopilot |
| 7 | Mixed-rules and assisted climbs set no personal best (`RunRecord.setsRecords`, `towerBests`) | A | RunRecord, PlayScreen |
| 8 | Generation-failure records name the failed climb's own seed, difficulty and tuning | A | ClimbGame |
| 9 | Tuning stamp: name-ordered, content-based (runtime-independent); a stamp from before the repair is accepted and restamped | A | ClimbGame |
| 10 | Enforcer glow recoloured in place (no per-frame allocation) | A | WorldRenderer |
| 11 | Owner achievement policy and Steam statistics decisions C1-C3 (below) | A | AchievementRules, AchievementDelivery, RunRecord, SaveData, SaveStore, PlayScreen, TitleScreen, Steam* |

Not changed: balance constants in code (review finding 9; constants refactor deferred by owner decision), tuning.json, generation rules,
KPI thresholds.

## Achievement policy (owner decisions 2026-10-30)

- Competitive achievements (SPEED_SUMMIT, PERFECT_SUMMIT, CLEAN_RUN, NO_CHECKPOINT_WORLD, INFINITY_BEST) and STAT_BEST_INFINITY_M only on
  eligible climbs: not assisted, not mixed-rules, not world picker, not developer autopilot; NORMAL, PUNISHER and NGH alike
  (`RunRecord.competitiveEligible`). All other achievements stay open to assisted play.
- The world picker updates no achievement or statistic. The developer autopilot counts as an assist (no personal best, split, finish or
  endless record).
- C1: FIRST_FALL / FALL_25 / FALL_100 use a lifetime fall count (`SaveData.lifetimeFalls`), separate from the per-climb count. An older save
  starts it at its climb's falls; Steam's STAT_FALLS is merged with max, never added. Assisted falls count, world-picker falls do not.
- C2: NO_CHECKPOINT_WORLD tracking (`achZone`, `achZoneFell`) persists across save, quit and resume; a climb saved before the tracking
  existed counts its current world as fallen in.
- ALL_WORLDS progress (`worldsSeenMask`) persists. Achievements earned while Steam cannot take them are kept in `pendingAchievements` and
  offered again until Steam confirms them (idempotent).
- C3: no Steam reset; tester contamination accepted; future ineligible updates blocked by the rules above.
- New save fields all have defaults; older saves load unchanged (tests below).

Steam Cloud limits (reported, not fixable in-game): falls before this build cannot be recovered beyond the old per-climb maximum Steam holds;
Auto-Cloud syncs save.json as one file, last writer wins, so two machines played offline at the same time can lose one side's
`worldsSeenMask` or pending ids (falls recover through the Steam stat). `bestHeight` is still updated by assisted climbs (open owner question).

## Generation and replay evidence

- Generation fingerprint with every change: `b0c5646446610a0274e036ab` (DeterminismTest), the frozen baseline's.
- Slice-by-slice comparison, original 2a7935b classes vs hardened classes, NORMAL / PUNISHER / NGH x seeds 1-3 x 6 slices (54 slices, CRC32
  of each slice blob): **identical** (`GenEq83`). Within a generated slice the route is contiguous with its side structures after it, so raw
  `j +- 1` and `deck(j, +-1)` agree there; they differ across a slice boundary of the live tower. The comparison and the unchanged
  fingerprint found no generation difference; a full-course comparison of all 626 campaign slices was not run (it would need a campaign).
- Bundled seed-12 replay (BundledReplayTest) on the hardened simulation: **passes** (finish step 412,631, events and states hashes as pinned).
- Consequence: classes 3-6 change what happens at play time in the live tower (an Enforcer or crab at a slice boundary, encounter resets
  after a checkpoint, the developer autopilot's waiting) and not what is generated, so the baseline campaign 41137500091 and T5 audit
  41137503239 carry over to this candidate. They covered 626 slices; the comparison here covers 54 of them plus the fingerprint seeds.

## Validation

Local, on the hardened classes (JUnit, one JVM per batch, Java 17):
- ReleaseHardeningTest 7/7, AchievementEligibilityTest 12/12, LiveTowerDeckOrderTest 5/5 (24/24, 311 s);
- GenerationRecoveryTest, TowerPrepareTest, RecordsTest, PersistenceTest, AchievementRulesTest 65/65 (1,737 s);
- DeterminismTest fingerprint line (b0c5646446610a0274e036ab);
- BundledReplayTest 1/1 (2,140 s);
- GenEq83 54/54 slices identical.

The live-tower deck tests fail on the original classes and pass here. The full core and desktop suites run in CI on the final Build 83 SHA,
together with the 30/15 Split (owner 2026-10-31); this checkpoint itself started no CI.
