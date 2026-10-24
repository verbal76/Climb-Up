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
