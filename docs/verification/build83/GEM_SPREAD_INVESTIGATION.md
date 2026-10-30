# OfficialWorldRulesTest: gem midpoint and run-difficulty spread (Rules 19), investigation

Owner follow-up 2026-10-30 on PR #16: investigate both, do not change generation or the thresholds, say whether each is isolated or
systematic. Full CI 41030282332 (d218fa9) fails exactly these two plus the fingerprint and bundled replay (held for the freeze). Rules 18
candidate CI 40884943058 (c8ef538) passed both. Measured with `GemSpreadProbe` (core test tree; scratch, CLIMB_PROBE), the same generation
path as the test (`TestUtil.slices`: CourseGenerator.chunk, NORMAL tuning), default switches (plan cache and early rejection on; both verified
to generate identical courses). Its loads reproduce the CI message exactly.

## 1. Gem midpoint (test: gem j within 4.5 m of 250 + 500 j, seeds 7 and 8 to 5,300 m)

**Purpose of the tolerance.** The red gem is the halfway checkpoint (owner 2026-10-09: one gem per section, exactly halfway); a fall returns
the climber to it, so its position sets the replay cost of a fall. The original bound was 6 m; after the gem was chosen from the slice's own
platforms with a rest rebuilt near the midpoint (GEM_LEAD 4 m), the worst case over 12 seeds was 3.43 m and the bound was tightened to 4.5 m
(DECISIONS). The generator's own target is the same 4.5 m (`GEM_MAX_DEV`).

**Measurements (seeds 1-8, first 1,100 m, 16 gems).** 15 gems are 0.13-3.50 m from the midpoint (14 of them below it, as GEM_LEAD intends);
one is out of tolerance: **seed 8, gem 1, y 255.35 m, +5.35 m.** The CI failure is this gem.

**Mechanism (seed 8, slice 3, elements 59-73).** The climb crossed the gem trigger (midpoint - 4 m = 246 m) at deck 66 (246.55 m).
`chooseGem` found no suitable deck (plain, >= 3.5 m wide) within 1.5 m and added rests: they came out 1.0 m, 2.0 m and, after a rope to
255.4 m, 3.0 m wide, all too narrow to hold the gem. `widenForGem` could not widen deck 66 (3.0 m at 3.45 m off), so the nearest suitable deck
was the 5.0 m deck at 255.35 m. No Tarzan swing or turbo ramp is involved; Rules 19 changed the course before this point (different modules
drawn earlier), which produced this geometry.

**Fairness.** Harmless: 0.85 m past the tolerance on a 250 m half-section changes a fall's replay distance by about 0.3%; the gem is on the
route, on a wide plain deck, reachable and proven. **Isolated**: 1 of 16 gems here and 1 of 20 in the test; no other gem is above 3.5 m.

**Options (owner decision; nothing changed).** (a) a fourth rest attempt or a widening fallback over the next two decks before accepting an
out-of-tolerance deck (generation change, rules bump at the freeze); (b) keep the generator and relax the test bound to the measured worst
case plus margin; (c) accept the failure as a known outlier.

## 2. Run-difficulty spread (test: obstacle load over the first 1,100 m, seeds 1-8, max >= 1.10 x min)

**What it measures.** "Load" = every entry of the course's hazard list (saws, spike blocks, drops, cannons, mortars, creatures, **and keys,
gates and clubs**) plus moving route elements, counted per seed over the first 1,100 m. It asks that runs differ in raw obstacle count (the
owner's "every run is different, difficulty is not normalised"). It is a between-run variety statistic of raw counts, not the intra-run
castle-to-castle escalation of KPI 7B and not a difficulty score; the two are not equivalent.

| Build | Loads, seeds 1-8 | Mean | SD | hi/lo |
|---|---|---|---|---|
| Rules 18 (c8ef538) | 336, 364, 353, 339, 372, 356, 329, 360 | 351 | 14 | 1.131 (pass) |
| Rules 19 (d218fa9) | 337, 336, 345, 336, 336, 350, 351, 329 | 340 | 7.6 | 1.067 (fail) |

**Isolated or systematic: not established.** The statistic is the max/min of 8 noisy counts. The variance ratio between the builds is 3.4,
below the 5% two-sided F(7,7) threshold of about 5.0, so 8 seeds cannot distinguish a real loss of spread from chance. The mean fell 3%
(351 -> 340): Rules 19 draws Tarzan swings and turbo ramps from 500 m, which take module slots that hazard modules would otherwise fill
(seeds 2, 5 and 8 lost 28-36 loads; seed 7 gained 22).

**Options (owner decision; nothing changed).** (a) measure on more seeds (e.g. 16-24) before deciding; (b) keep the threshold and accept the
current failure as sample noise; (c) a threshold or statistic change (for example a coefficient of variation over more seeds), which needs
owner approval.
