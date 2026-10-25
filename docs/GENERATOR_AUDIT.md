# Generator architecture audit against the owner's master directive (2026-10-24)

Status: Phase 1 (audit) and Phase 2 (gap analysis) done. Phases 3-6 not started, pending the owner decisions at the end.
Recovery point: branch `build-74-determinism`, HEAD `bd0b550` (PR #9). Generator source is exactly `bd0b550`.

## State preserved at this point

- CI on `bd0b550`: 7 failures (from 14):
  - AimedCannonTest (0 aimed)
  - BalanceConveyorTest x2 (seed 7 section 9 has no conveyor; seed 2 link 27 knocked back again and again)
  - OfficialRunTest slice 36 link 7
  - DeterminismTest fingerprint and BundledReplayTest (expectations to refresh)
  - TowerStreamingTest frame 1578
- TowerStreamingTest frame 1578 is verified rounding (`RebuildFlipProbe`): 2 one-step flips, the same 30 surfaces, falls 5/5, end gap 0.025 mm. The owner approved a one-step tolerance; not applied yet.
- Replays recorded on `bd0b550` (NORMAL), saved uncommitted in `replays-pending/` (see RECORDINGS.txt): seed 12 (342,700 steps, events d65b196e..), seed 7 (339,873, 37787762..), seed 21 falls (413,457, 028e37c7..). They are valid only for `bd0b550` generation: any generator change below invalidates them.
- Aimed cannons: AimedCannonTest's slices had 2 aimed / 5 side cannons on `85740e6` and 0 / 4 from `e76b7db`. Even before, aimed cannons were rare because of the level gate below. Retrying the cannon's timing did not restore them (tried, reverted).

## Structure today

- Timed game: 10 castles every 500 m (5 worlds x 1000 m), then the endless tower. Compliant with the directive's structure.

## Gap analysis

| Requirement | Today (code evidence) | Verdict |
|---|---|---|
| Every mechanic stays and is used | All mechanics exist in Sim and the generator | Partial |
| Mechanic suppression | Aimed cannons near 0; saws become spiked blocks next to cannons or creatures (`buildHaz` rolls, `sawsClearOfCreatures`) | Noncompliant |
| No level gates (section 6) | Aimed cannon only in world 4+ (`z >= 3`); skull and blob modules only world 3+ (`z >= 2`); mortar, seesaw, bridge, spring and slider weights only world 2+ (`zone >= 1`); hazards start at `hazardStartY` | Noncompliant |
| Context-sensitive encounter difficulty (section 7) | None. Difficulty is one height ramp (`intensity`, `late`, `tier`) applied to per-module weights, periods and counts. No score of a whole encounter | Noncompliant |
| Incompatible demands, human feasibility (sections 10-13) | Per-link proofs: solver plans each jump with every hazard present, margin and timing window, quiet starts. This proves mechanical possibility per link. There is no classification of pursuit vs waiting vs position demands, and no check across consecutive links under pursuit | Partial |
| Coordinated 10-level progression (sections 5, 9) | Height ramp only. No per-level targets or distribution checks (OfficialWorldRulesTest checks obstacle load rises only roughly) | Partial |
| Normal Level 1 interesting (section 4) | World 1 is limited to hops, stairs, crumble, pads, ropes, movers, swings and sliders. No enemies or most hazards below `hazardStartY` | Noncompliant |
| Two curves, Punisher starting at about Normal L3-4 (section 8) | `T.hard` multiplies creature, trap and hazard weights, adds enemies and speeds up timers. The same curve, scaled. A third mode, NEVER GONNA HAPPEN, also exists | Noncompliant |
| Forks, not dead ends (sections 15-16) | Dead-end spurs and lures (owner request of 2026-10-24), no reconnecting forks | Noncompliant (design superseded) |
| Determinism and speedrunning (section 17) | Seeded, fingerprinted, replays reproduce on Windows, Linux and Android | Compliant |

## Root cause

- Difficulty is a function of height applied module by module. The generator decides what may appear by where it is (gates), not by what the whole encounter asks of the player.
- Its validation proves each link alone. So it either:
  - rejects strong mechanics wholesale (aimed cannons, saws), or
  - would accept combinations whose demands conflict, such as waiting for a timed platform while crabs and a skull force movement.
- The directive's diagnosis matches the code.

## Correction design (proposed; to implement after the owner decisions)

1. **Demand model, in the generator.**
   - Each placed mechanic records what it asks of the player at a link:
     - PURSUE (crab, skull, blob, bee)
     - WAIT (mover alignment, spike cycle, mortar)
     - POSITION (launch spot, belt, seesaw)
     - COMMIT (crumble, plank holes)
     - RECOVER (knockback)
   - Each demand has an intensity from local geometry: platform width, gap, period, enemy count and reach.
2. **Encounter score.**
   - Computed over a sliding window of about 3 links.
   - It is the sum of demand intensities plus explicit interaction terms: PURSUE x WAIT, PURSUE x POSITION, overlapping attack windows.
   - Every term is logged, so a score can be explained.
3. **Feasibility rule.**
   - Reject a composition only where a PURSUE demand overlaps a WAIT or POSITION demand with no escape room, or with a cycle longer than the pursuit allows.
   - The response is to move the encounter (spacing, placement, sequencing), not to drop the mechanic.
   - Per-link solver proofs stay as they are.
4. **Progression.**
   - Each mode has a target band per level (1-10) for the encounter-score distribution: mean, share of hard encounters, maximum, and a minimum spacing of recovery points.
   - The generator chooses compositions whose score keeps the running level distribution inside the band.
   - All mechanics stay eligible everywhere. Low levels choose low-scoring placements of the same mechanics, which removes the level gates.
   - Punisher L1 is about Normal L3.5; Punisher L10 is above Normal L10.
5. **Routes.**
   - Replace dead ends with forks of a fast and a long branch that reconnect.
   - Normal: occasional single forks.
   - Punisher: more, longer, occasionally nested forks.
   - Both use the existing decoy and route machinery, and every branch is proven forward.
6. **Validation.**
   - Mechanic presence per level and mode.
   - Score bands per level.
   - A feasibility-rule audit.
   - Forks reconnect.
   - Determinism.
   - Replays re-recorded once, at the end.

Estimated effort: several focused sessions (the generator is about 1,700 lines). It changes every seeded course (rules 7), so the replays above, records and the fingerprint all reset.

## Owner decisions (2026-10-24, answered)

1. Modes: new climbs offer only NORMAL and PUNISHER until this design infrastructure is in place.
   - NEVER GONNA HAPPEN keeps its code and its times board, and a climb already begun on it carries on.
   - A saved NEVER GONNA HAPPEN setting starts PUNISHER climbs (`Tuning.PLAYABLE_MODES = 2`).
2. Build 79 is held until the redesign lands. No build from the current generator.
3. Forks replace the dead-end spurs.
4. The redesign starts now.

## Implementation log

- G1: modes limited to two (SettingsScreen, ClimbGame.newClimbTuning).
- G2: `sim/EncounterModel`: per-link demands (pursue, wait, position, commit, recover), interaction terms and a conflict flag, with a reason string for every link. `LevelReport` probe: per-level score distribution, conflicts and mechanic counts for either mode.
- G3 (commit 959a2e2): level gates removed (aimed cannon, skull/blob, drops, creature line now scale with `level(y)` from Level 1). Punisher is its own curve: `PUNISHER_SHIFT` 1500 m ahead on intensity/late/level (Punisher L1 ≈ Normal L4), plus its faster timers and extra enemies; the pick-weight multiplier is gone. Feasibility rule in `commit()`: a link that `EncounterModel` flags as a conflict (≥2 pursuers + waiting + cramped/positioned take-off) is rolled back and re-composed (`failConflict`).
- G4 (smoke round 1, uncommitted until the round closes): dead-end spurs replaced by `tryFork` (branch below the route that bounces back up onto a later route platform; every link proven, route re-proven around it).

### Smoke round 1 finding: forks under the route do not fit the tower (blocker, needs owner direction)

Measured with `ForkProbe` (seeds 160-163 x 5 slices, NORMAL), after correcting each rejection in turn:

- The route is a near-continuous staircase. Take-off gaps: 133 of 171 are 1-2 m, 27 are 2-4 m, none above 4 m.
- In those gaps a dropping player catches the next ledge (fingertip grab, reach 0.4 m). So a branch can only leave through a gap of at least 2.2 m, or under a next platform at least 2.1 m higher: about 1 take-off in 6.
- A branch under the route needs about 3.4 m of headroom (body 1.25 m plus a hop, plus the overlap clearance) to be walkable.
- In slow-climbing stretches the previous revolution of the tower is only 7-9 m below the route (circumference 62.8 m). The branch then clashes with that layer's bodies or comes within jump reach of it. These are genuine clashes, not rule artefacts.
- Result: 0 forks in 20 slices after these corrections (no broken links: the course falls back to the plain route, so there are no dead ends either).
- Correcting this needs forks planned in the route generator itself, by reserving a split section with room above or below. The owner directed that correction on 2026-10-24 (below).

### Correction (owner direction 2026-10-24): the route reserves room for its forks

- **Route builder:** a new module, `Kind.FORK`, lays a split section into the route at regular heights: every 70 m in NORMAL and every 34 m in PUNISHER, never next to a castle. The section has:
  - a 4 m entry platform;
  - a wide 2.5-2.8 m drop gap;
  - 3 risers (PUNISHER 5), 2.5 m wide, 1.5-1.8 m apart, climbing 2.8 m in all;
  - a wide 2.5-2.8 m gap before a 4 m rejoin platform.

  Every gap and rise is an ordinary hop, proven like every module.
- **Branch (decoy phase, `tryReservedFork`):** drop through the gap onto a 3 m landing 3.2 m down, hop along under the risers with at least 3.4 m of headroom, then bounce off a pad in the gap before the rejoin platform up onto it. PUNISHER's branch has more, shorter hops and a narrow 1.4 m foothold. Every branch link is proven, and the route links around it are proven again with the branch in place. If the branch cannot be proven, the trampoline fork (`tryPadFork`) is tried; failing that, the section stays a plain climb.
- **Not used: a crumbling platform on the PUNISHER branch.** It cannot pass the branch's stand-and-land link proof, and relaxing that proof was not acceptable.
- **Extra forks:** opportunistic forks stay. These are trampoline forks, plus the longer under-route branch where the geometry happens to allow it. A periodic wide hop (every 36 m, PUNISHER 18 m) gives them gaps to use.
- **Bug fixed:** `extract()` and `shift()` dropped the fork records when a slice was finished, so earlier probes under-counted forks.

Smoke round 2 (ForkProbe, seed 160, 5 slices each):

| Mode | Forks | Reserved splits proven | Broken (independent reconnection check) |
|---|---|---|---|
| NORMAL | 8 (5 reserved, 3 trampoline) | 5 of 5 laid | 0 |
| PUNISHER | 10 (7 long, 3 trampoline) | 7 of 7 laid (after the narrow-foothold change) | 0 |

The probe's reconnection check runs `Autopilot.linkExists` over every fork link: take-off to branch, along the branch, and branch to rejoin.

## Shared difficulty architecture and validation (2026-10-25)

### Architecture
- **Shared:** one generator (`CourseGenerator`), one solver (`Autopilot`, no difficulty branches) and one physics (`Sim`). Both modes are proven with the same margins and timing windows.
- **What differs per difficulty:**
  - `Tuning.react()`: creatures and cannons act 1.25x faster in PUNISHER.
  - `Tuning.hard`: extra creatures per group.
  - Everything else now lives in `sim/DifficultyProfile` (curve shift, late cap, split spacing and risers, branch hop, narrow foothold, wide-hop spacing, opportunistic fork rates, threat budget, quiet key rooms).
- The profile reproduces rules-7 courses byte for byte. GenHashProbe SHA-256 over the first slices, before and after:
  - NORMAL seeds 1 and 2;
  - PUNISHER seed 2;
  - NEVER GONNA HAPPEN seed 1.
- **Open:** PUNISHER's threat budget and quiet key rooms are off pending the owner's decision. Turning them on changes PUNISHER courses only.
- **Retries:** bounded (8 attempts per module, 5 fallback modules, 24 attempts per slice, the last 12 with relaxed layer clearance). Exhaustion throws; there is no graceful fallback yet.

### Measurements (VarietyProbe: NORMAL seeds 1-3 and PUNISHER seeds 1-2 to 1,100 m; seed 3 to 2,600 m; 4 jobs in parallel, so timings are relative)

| | Build 79 (bd0b550) | Build 80 (7ed261d) | threat repair (0ed7c64) | PUNISHER (repair) |
|---|---|---|---|---|
| links over the NORMAL threat budget, per seed | 8 / 23 / 14 | 10 / 6 / 2 | 0 / 0 / 0 | 12 / 26 |
| worst link threat | 17.5 | 13.5 | 4.5 | 32.5 |
| solver completed | (not run) | 54/54 slices | 53/53 slices | 35/36 slices |
| generation, s per slice | 12-18 | 15-23 | 15-27 | 33-67 |
| crabs, Level 1, per seed | 0 / 11 / 8 | 0 / 0 / 0 | 0 / 0 / 0 | 3 / 14 |
| seed 3 to 2,600 m: crabs / ramps / conveyor ramps / conveyor bridges | 20 / 23 / 1 / 1 | - | 8 / 18 / 0 / 0 | - |
| key-room kinds (0 chain, 2 on the route, 3 pad and perch) | {0,2}, {0,3}, {0,2} | {0}, {0}, {2} | {0}, {0}, {0,2} | {2}, {0,2} |

### Experiments on Build 80, level 1 (throwaway copies, never committed)
- Conflict rule off: crabs 0 / 2 / 2.
- Forks off: crabs 0 / 0 / 6. The pad-and-perch key room returns (seed 1). Generation drops to 6-13 s per slice.

### Classification
- **Threat repair:** works. NORMAL density is unchanged versus Build 80 and no NORMAL link is over budget.
- **Fewer crabs:** a regression from the Build 80 redesign, not the repair. The conflict rule and forks each account for part of it.
- **Conveyor ramps:** rare even in Build 79 (1 in 2,600 m). The test's sample is too small, and there are now none.
- **Key-room variety:** reduced by forks taking the room key rooms need.
- **Layer clash:** a rule mismatch. The fork pad is 8.9 m above a lower turn, out of jump reach.
- **PUNISHER seed 2, castle/level 1, 170.3 m:** a solver limitation. After a cannon knock-back to platform 27 it keeps planning toward link 31. The jump itself is a 1.64 m gap with a 0.6 m rise.
- **Forks roughly double generation time.** PUNISHER generation (33-67 s per slice) is a phone performance risk.

### Awaiting owner decisions
1. PUNISHER threat-budget values: proposed base 7, growth 8 and cramped factor 0.85. These are heuristic, about 40% above NORMAL.
2. Restoring crab decks.
3. Fork generation cost.
4. Solver recovery after a knock-back to an earlier platform.
