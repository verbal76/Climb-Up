# Build 83 candidate: consolidated validation report

## Scope

**Candidate (frozen).**
- `experiment/pendulums` at `c8ef538`.
- Rules 18: the Build 83 avalanche overhaul, on top of the rules 17 pendulum experiment.
- Generation fingerprint `3e79526889137dc34e1edeb9`.

**Campaign.** GitHub Actions run [40884899102](https://github.com/verbal76/Climb-Up/actions/runs/40884899102):
- 9 courses: NORMAL, PUNISHER and NOT GONNA HAPPEN (NGH) × seeds 7321, 7322 and 7323;
- 5,000 m each, with the full solver verdict on every slice;
- the consolidation job and its report package (`difficulty-baseline-3lane`, artifact 12903559445) succeeded.

**Full CI.** Run [40884943058](https://github.com/verbal76/Climb-Up/actions/runs/40884943058) on PR #15, the same commit. It is **still running at the time of writing**, so its row below is pending.

**Build 82 baseline.**
- Run 40752227771; its report was rebuilt by run 40759608865.
- Commit `1123a32`, rules 16.
- 30 courses (seeds 7301-7310 × 3 modes), each 5,000 m with the solver on.

**Caveats.**
- The baseline has 10 courses per mode; Build 83 has 3. Its seeds are also different. Differences of a few percent are within seed variation.
- No adjustments were made after the freeze, per the owner's directive.

## Verdict

- **Engineering: PASS.** Every course generated, every slice was proven solvable, geometry is clean, all 10 castles were reached, and no witness took a setback.
- **The avalanche frequency target: FAIL.**
- **The difficulty-separation standard: FAIL.** It is worse than Build 82 for PUNISHER over NORMAL.
- **Difficulty ramp and smoothness: mixed.**
- **Terrain variety: improved over Build 82, but still dominated by plain decks.**

## KPI scorecard

| # | KPI | Build 82 | Build 83 | Status |
|---|---|---|---|---|
| **A. Reliability and solver** | | | | |
| A1 | Courses complete to castle 10 | 30/30 | 9/9 | PASS |
| A2 | Slices proven solvable, hit-free witness | 2,096/2,096 | 626/626 | PASS |
| A3 | Generation failures / unknown / unsolvable verdicts | 0 / 0 / 0 | 0 / 0 / 0 | PASS |
| A4 | Geometric-integrity violations | 0 | 0 | PASS |
| A5 | Witness setbacks (including avalanche hits) | 0 | 0 | PASS |
| A6 | Full CI suite (core + desktop) on the frozen commit | green on 1123a32 | running | NOT VERIFIED (pending) |
| **B. Difficulty** | | | | |
| B1 | Mode ordering at every castle | yes | yes | PASS |
| B2 | Separation erosion, PUNISHER over NORMAL (limit 25%) | 29.7% (+21.8% to +15.3%) | **88.4%** (+21.2% to +2.5%) | FAIL |
| B3 | Separation erosion, NGH over PUNISHER (limit 25%) | 61.4% | 29.0% (+13.1% to +9.3%) | FAIL (improved) |
| B4 | Ramp castle 1 to 10 (N / P / NGH) | +43% / +35% / +17% | +34% / +14% / +10% | FAIL (flatter in every mode) |
| B5 | Castles below the previous castle (N / P / NGH) | 1 / 2 / 1 | 2 / 3 / 3 | WATCH |
| B6 | Castle-to-castle swing, sd of steps (N / P / NGH) | 25.7 / 23.6 / 18.1 | **44.2** / 29.3 / 13.5 | FAIL for NORMAL, WATCH for PUNISHER, PASS for NGH |
| **C. Avalanches** | | | | |
| C1 | Avalanches per course, PUNISHER (target about Build 82) | 23.2 | **11.0** (planned 23.3) | FAIL |
| C2 | Avalanches per course, NGH (target about Build 82) | 28.1 | **10.3** (planned 38.7) | FAIL |
| C3 | Blocks per avalanche (P 8-10, NGH 9-12) | 4-5 / 5-7 | 8.8 / 10.4 | PASS |
| C4 | Violence: ricochets per avalanche; block knocks per course (P / NGH) | n/a (old physics) | 18.9 / 22.0; 64 / 68 | PASS |
| C5 | Share of released blocks that land in the traversal area (P / NGH) | n/a | 80% / 77% | PASS |
| C6 | Fairness: witness hits by avalanche blocks | 0 | 0 | PASS |
| C7 | Lead time, first impact, determinism, cleanup, cost per step | n/a | focused tests green locally; CI pending | NOT VERIFIED (pending) |
| **D. Enemy pressure** | | | | |
| D1 | Enemies per course, PUNISHER | 597 | 605 (+1%) | PASS |
| D2 | Enemies per course, NGH | 738 | 692 (-6%) | WATCH |
| D3 | Enemy mix: crabs / skulls per course (N / P / NGH) | 213/71, 303/121, 273/204 | 217/62, 334/105, 317/158 | WATCH (crabs up 2-16%, skulls down 13-23%) |
| D4 | Enforcers per course (P / NGH) | 12.0 / 20.9 | 12.3 / 20.7 | PASS |
| **E. Variety, generation, performance** | | | | |
| E1 | Route links using a terrain mechanic (N / P / NGH) | 38% / 19% / 13% | 51% / 39% / 32% | PASS (improved), still WATCH |
| E2 | Plain-deck take-off share (N / P / NGH) | 80% / 90% / 93% | 73% / 79% / 83% | WATCH (owner observation) |
| E3 | Terrain lane growth castle 1 to 10 (N / P / NGH) | +22% / +34% / +20% | +13% / +11% / +10% | FAIL |
| E4 | Hazard family entropy, bits (N / P / NGH) | 2.70 / 2.74 / 2.63 | 2.84 / 2.90 / 2.85 | PASS |
| E5 | Spike-block share of hazards (N / P / NGH) | 28.5% / 32.8% / 35.7% | 26.9% / 29.4% / 29.4% | WATCH (still the top family) |
| E6 | Variety flags against the 30-course baseline | n/a | 0 | PASS |
| E7 | Generation time per course on CI, seconds (N / P / NGH) | 221 / 260 / 302 | 268 / 551 / 476 | FAIL (P x2.1, NGH x1.6) |
| E8 | Solver time per course, seconds (N / P / NGH) | 10 / 16 / 24 | 12 / 24 / 30 | WATCH |
| E9 | Peak heap, MB (N / P / NGH) | 180 / 209 / 187 | 179 / 183 / 194 | PASS |

## Report A: reliability and solver

- All 9 courses passed the acceptance gate:
  - 626 slices, every one PROVEN_SOLVABLE with a hit-free witness;
  - 0 generation failures;
  - clean geometry;
  - all 10 castles reached;
  - tops at 5,009-5,042 m.
- The witness took no hits from avalanche blocks, Enforcers or anything else.
- The solver is not what makes the varied modules rare (see Report E). Of the reasons recorded for refusing a module, the planner's own ("plan" and "window") are 0.4-3%.
- The solver's real share is slices accepted module by module and then discarded by the whole-slice witness:

  | Module | Discarded in NGH |
  |---|---|
  | Saw modules | 19% |
  | Creature decks | 18% |
  | Traps | 15% |

  Those discards are either genuinely unfair combinations or lines the solver cannot find. This campaign cannot tell which.

## Report B: difficulty

- **Ordering holds at every castle.**
- **Separation has compressed sharply for PUNISHER over NORMAL.** The cause is a NORMAL that got harder overall (mean 802 against Build 82's 744) while PUNISHER's late castles fell:

  | Castle | PUNISHER, Build 82 | PUNISHER, Build 83 |
  |---|---|---|
  | 9 | 982 | 950 |
  | 10 | 1,002 | 936 |

  At castle 10, PUNISHER is only 2.5% above NORMAL.
- **NGH's separation from PUNISHER erodes less than in Build 82** (29% against 61%). This is because NGH flattened too: castle 10 is 1,023 against 1,108. It still fails the 25% limit.
- **Owner Observation 1 is confirmed for NORMAL and partly for PUNISHER.** The castle-to-castle swing nearly doubled in NORMAL (sd 44 against 26). In NORMAL, castle 4 jumps +82 and castle 5 falls -45; castle 6 jumps +81 and castle 7 falls -35. NGH is smoother than in Build 82.
- **By lane (executive_report.pdf, page 3):**
  - NORMAL's terrain lane is nearly flat (+13%). Its climb comes from hazards (+107%) and enemies (+281%), and both swing by castle.
  - PUNISHER's hazard lane is flat from castle 1 to 10 (145 to 147).

## Report C: avalanches

- **The physics behaves as directed:**
  - PUNISHER drops 8.8 blocks per avalanche and NGH 10.4;
  - about 19-22 ricochets per avalanche;
  - 64-68 block knocks per course;
  - 77-80% of the blocks reach the traversal area;
  - 25-27% come near the climber.
- **Frequency is about half of Build 82 in PUNISHER and about a third in NGH:**

  | Mode | Planned per course | Placed per course | Placed share | Unfair verdicts | Dropped |
  |---|---|---|---|---|---|
  | PUNISHER | 23.3 (matches the 23.2 that Build 82 placed) | 11.0 | 47% | 53 | 12 |
  | NGH | 38.7 | 10.3 | 27% | 113 | 28 |

  The loss happens in the fairness proof. PUNISHER plans about as many avalanches as Build 82 placed, but fewer than half of them are proven fair and kept.
- The cause was diagnosed before the freeze (docs/DECISIONS.md): "no plan" from the trigger deck while 8-9 blocks are still moving. About a third of refused spots cannot be proven even with no avalanche.
- **Per seed:**

  | Mode | Seed 7321 | Seed 7322 | Seed 7323 |
  |---|---|---|---|
  | PUNISHER | 11 | 12 | 10 |
  | NGH | 10 | 13 | 8 |

  The shortfall is consistent, not seed variation.

## Report D: enemy pressure

- **PUNISHER enemies are on par with Build 82** (+1%).
- **NGH is 6% lower: WATCH.**
- **The mix has moved towards crabs and away from skulls in all three modes.** Crabs come from the trap module, which is one of the generator's fallbacks (Report E). That plausibly explains the shift; it is not proven.
- **Enforcer counts match Build 82.**

## Report E: variety, the generator and the solver

**Owner observations (2026-10-29): spiky hazard difficulty; crabs, spikes and saws look like fallbacks; almost no terrain variety.**

**1. Refusals are mostly the generator's geometry check, not the solver.** PADs, vertical movers, falling elevators, mortar rows and hops are refused 74-86% of the time by the check that keeps a module clear of the spiral turns above and below it. That check runs before any proof, so the solver never sees those modules. Saw modules, trap modules, ropes and creature decks pass far more often (about 35-55%).

**2. The fallbacks are saw and trap modules.**
- A refused threat module tries the other one first (saws ↔ traps).
- Then up to four other challenges are drawn from the same mix.
- Then plain travel.
- Separately, as the climb rises, plain hops and stairs are swapped for saws or traps: from 0% in NORMAL, 15% in PUNISHER and 30% in NGH at the start, rising with height.

The saw module builds gap saws and vertical saws. The trap module builds crabs, floor saws, spike blocks, spike mats and crushers.

**3. Spike blocks are mostly stand-ins for gap saws that cannot be placed fairly** (beside a bee, a cannon, a creature's reach or a narrow take-off). Stand-in offers outnumber spike blocks chosen on purpose by about 3× in NORMAL, 4× in PUNISHER and 6× in NGH. These are generator tallies of attempts, including discarded ones.

**4. Terrain variety improved over Build 82 but is still plain-deck dominated.** Mechanic links rose from 38/19/13% to 51/39/32%:
- PADs on the witness route went from 83 to 249 per NORMAL course.
- Vertical movers went from 82 to 220.

Plain decks are still the take-off point for 73/79/83% of links. Variety falls as the mode gets harder, because harder modes refuse more of the moving modules. Why mechanic use rose since Build 82 is not established, given the different seeds and course counts.

**5. Generation got slower.**
- PUNISHER takes 551 s per course against 260 s, about 8.2 s per slice against 3.8 s.
- NGH takes 476 s against 302 s.
- Seed spread is large: PUNISHER took 282-886 s.

This comes from the avalanche proofs from two decks back at four arrival times. It has not been checked against the in-game streaming budget.

## Seed variation

The ± values in the difficulty curves are 1 sd across the three seeds (batch/curves.csv):

| Measure | Range across seeds |
|---|---|
| Difficulty, per castle | sd 7-56 |
| Avalanches, PUNISHER | 10-12 |
| Avalanches, NGH | 8-13 |
| Generation time, PUNISHER | 282-886 s |
| Generation time, NGH | 317-560 s |

The avalanche FAILs (C1, C2) hold on every seed. B2, B4 and E3 are based on the three-seed means; I did not check them seed by seed.

E7 does not hold on every seed. PUNISHER seed 7323 took 282 s, close to Build 82's 260 ± 89 s. The slowdown comes from seeds 7321 and 7322.

## Not verified

- **A6 / C7:** the full CI suite on `c8ef538`. It is still running (core and desktop tests, including the avalanche tests, the bundled seed-12 replay and the determinism fingerprint).
- **Real-device play** (feel, readability of an avalanche with no warning arrows, frame rate).
- **Whether the generator's spiral-clearance check is stricter than it needs to be.**
- **Whether the solver could play the combinations that were refused before any proof.**
- **Whether discarded saw and creature slices are unfair or merely unsolved.** All three of these need the diagnostics proposed to the owner, which are not authorised yet.
- **Build 82 versus Build 83 differences under 5%.** They are within seed variation with 3 courses per mode.

## Files

- `kpi_data.json`: every number in this report and the chart inputs, with both sources.
- `kpi_scorecard.csv`: the scorecard above.
- `Build83_vs_Build82_charts.pdf` and `01-08_*.png`:
  - difficulty curves;
  - mode separation;
  - avalanche frequency;
  - castle-to-castle swing;
  - terrain;
  - hazard families;
  - enemies;
  - performance.
- **The campaign's own package** (artifact `difficulty-baseline-3lane` of run 40884899102):
  - executive, detailed, components and variety PDFs;
  - per-course segment PDFs;
  - the 15-page chart collection;
  - DIAG.md (P1-P4, KPI 16, generator tallies);
  - BATCH.md;
  - the CSVs and report_full.json.
