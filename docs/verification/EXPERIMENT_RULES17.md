# Experimental generator (rules 17): pendulums and four priorities

Owner directives 2026-10-28: "Freeze current baseline / add pendulums", "Experimental generator improvements: four priorities" and the immediate amendment
on testing. **Not for release.** Build 82 is the release candidate. Nothing here merges into it without the owner's explicit approval.

## Branches

| branch | head | role |
|---|---|---|
| `frozen/build-82-baseline` | 623805c | Build 82 release candidate. Never modified. |
| `ccr-cb458cd1-w4dh5c` | 623805c | The same frozen commit, used by the Build 82 package workflows. |
| `experiment/pendulums` | this branch | All gameplay changes (rules 17). |
| `diag/build-82-measure` | d6421cd | Frozen 623805c plus a reporting script only (`tools/ci/diag_summary.py`). Not a development branch. |

## Baseline evidence (Build 82, rules 16, fingerprint 978eaa5128ad2201d29dabbf)

- **CI:** run 40727062206. Core 500 tests, 0 failures, 3 skips. Desktop 129 tests, 0 failures.
- **Campaign:** courses from run 40752227771, report rebuilt by run 40759608865.
  - 30 of 30 courses passed.
  - 2,096 of 2,096 slices PROVEN_SOLVABLE.
  - 0 generation failures.
  - Docs: `BUILD82_GO_NO_GO.md`.
- **Baseline diagnostics:** run 40774298627 re-reads the stored 30 courses. No course is generated again.
- **Testing policy (owner amendment):**
  - Focused tests only while implementing.
  - One full CI run on the final candidate SHA.
  - The nine-course comparison campaign uses fresh seeds and is prepared but not dispatched until the owner authorises it after that CI.
  - No 30- or 900-course campaigns.

## Measurement additions (observe-only; no course, score or fingerprint changes)

- **`GenTrace` tallies:**
  - `why_<module>_<check>`: the check that refused a module.
  - For layer refusals, also the clash type: slice or ghost, body or spacing, and the element types.
  - `pendulum_*`: pendulum offers, acceptances and refusal reasons.
- **`LaneReport` `counts.csv`:** every tally, per slice.
- **`tools/ci/diag_summary.py`:** printed to the consolidate job's log.
  - P1: module acceptance and first refusal cause; mechanics on main-route links.
  - P2: on-route hazard families, with entropy.
  - P3: lanes, enemies and encounters per castle.
  - P4: route travel per castle interval (mean, median, range, sd, CV).

## Pendulum (priority 2, part A)

**Behaviour (existing model in `Element`):**
- Fixed pivot at (s, y + len).
- Angle amp·sin(2πt/period + phase).
- The hit disc is the drawn ball (BALL_R 0.5).
- Motion is a pure function of sim time: deterministic, and immune to the floating origin because positions are slice-relative like every hazard.

**Generator placement:**
- Pendulums take 40% of the gap-saw slots, with every saw guard.
- Swing size: the sweep equals the saw's travel for that gap, so the lethal box equals the saw's and never reaches over the decks.
  - The first version, with chains of 2.0-2.6 m and amplitude 0.45-0.70, swung about 1.9 m each way over the take-off and landing decks at head height. hazardOk refused every one, correctly.
- Chain length 1.1-1.4 m, shorter higher up.
- Natural period 2π√(len/g).

**Fairness:**
- Pendulums keep the saw's half-cycle window rule in all three places it is checked:
  - slice acceptance (`sawsFair`);
  - the module's link proof;
  - route re-proofs.

**Solver:**
- A pendulum is a moving hazard, so the solver samples waits across its period (0.3 s steps).
- Every move is rehearsed in the real Sim.
- Only a hit-free whole-slice witness accepts a slice.

**Renderer:** pivot block, seven chain links, and the pack's `spikyball` model at the ball's sim position.

**Tests:** `PendulumExperimentTest` 3 of 3 pass:
- motion stays on its chain, is periodic, symmetric, and stays inside the reserved box;
- a climber under the swing is hit and one 4.5 m away is never hit;
- generation places pendulums, and slices repeat byte for byte.

**Placement sample:**

| mode, seed, slices | pendulums placed | saws still placed |
|---|---|---|
| NORMAL 7302, slices 0-13 | 2 | 12 |
| PUNISHER 7301, slices 0-13 | 9 | 13 |

## Priority 1: advanced traversal

**Root cause:** NGH seed 7301, 0-600 m, generation only.

| module | tries | accepted | refused by the layer check |
|---|---|---|---|
| MOVER_V | 433 | 0.5% | 99.5% |
| PAD | 417 | 1.4% | 98.6% |
| ELEV_FALL | 403 | 0.7% | 99.3% |

- About 3,000 refusals each were the 9.5 m launcher spacing against a plain STATIC platform of the revolution below.
- Body overlaps were about 160 each.
- The pitch controller favours climbing modules when the layer gap is small. That is exactly when a launcher above the lower revolution fails the "either platform" spacing, so it kept retrying them, about 47 tries per slice.

**Owner decision 2026-10-28 (experiment only):**
- The 9.5 m spacing between revolutions applies when the **lower** platform launches or carries the climber, as the fork-branch rule already did.
- A launcher above a plain platform is no shortcut:
  - the plain platform's reach (3.25 m, or 3.6 m with a skull knockback) falls short of it;
  - it throws the climber upward;
  - when the revolution above it is built, it is the lower platform and keeps its 9.5 m.
- Unchanged:
  - body clearance;
  - launch-column clearance;
  - the 4 m plain spacing;
  - every solver proof.
- Changes rules 16 behaviour on the experimental branch only.

## Build 82 baseline diagnostics (30 stored courses, no course generated)

Source: runs 40774298627 (P1-P4) and 40780279216 (dominance, KPI 16, enemy ledger), both re-reading the artifacts of campaign 40752227771. The
full tables are in `baseline82/DIAG_build82_30courses.md`.

**P1, advanced traversal.** The layer check refuses nearly every launcher and carrier.

| NGH | tries | accepted | refused by the layer check |
|---|---|---|---|
| MOVER_V | 34,950 | 0.4% | 99.5% |
| PAD | 34,810 | 0.6% | 99.3% |
| ELEV_FALL | 34,048 | 0.4% | 99.5% |
| SWING | 781 | 10.1% | 86.6% |
| SEESAW | 1,096 | 11.7% | 84.8% |
| SPRING | 739 | 21.4% | 73.9% |

NORMAL and PUNISHER show the same pattern with somewhat higher acceptance.

**KPI 16, advanced movement utilisation.**

| | NORMAL | PUNISHER | NGH |
|---|---|---|---|
| ordinary links (both ends STATIC) | 62.0% | 80.6% | 87.4% |
| witness-traversed per course: pads | 82.8 | 51.1 | 28.1 |
| witness-traversed per course: vertical movers | 82.0 | 40.2 | 25.3 |
| witness-traversed per course: ropes | 351 | 236 | 202 |
| placed pads off the main route only | 62% | 79% | 86% |

- Every other main-route mechanic was traversed by the witness.
- Attribution: modules own their defining element types 100% of the time. 1.1-1.5% of main-route elements carry no module, almost all STATIC.

**P2, spike blocks on route.**

| | NORMAL | PUNISHER | NGH |
|---|---|---|---|
| spike blocks | 2,669 | 3,870 | 5,385 |
| share of on-route hazards | 28.5% | 32.8% | 35.7% |
| hazard entropy (bits) | 2.70 | 2.74 | 2.63 |
| spike-block share, castle 1 | 27% | 32% | 35% |
| spike-block share, castle 9-10 | 32% | 35% | 38-40% |
| 25 m windows with 2 or more spike blocks | 37.5% | 54.4% | 70.2% |
| avalanche blocks (separate type) | 6 | 232 | 281 |

**P2, spike-block conversions.** Experiment samples only; the baseline never recorded the original draws.

| 600 m sample | conversions | share of spike-block draws |
|---|---|---|
| PUNISHER | 89 | 45% |
| NGH | 79 | 38% |

Leading causes:
- a bee or crab deck barred near an Enforcer (PUNISHER 36, NGH 26);
- a gap saw next to a bee (32, 23);
- a saw a creature can reach (8, 15).

None is a refused traversal module.

**P3, NGH castle 9 to castle 10.**
- Enemy placements per course rise from 84.2 to 86.9, but the enemy lane falls from 153.6 to 137.2.
- The ledger shows the mix shifting:
  - skulls 25 to 22 rows (value -9.8);
  - blobs 20 to 16 (-7.2);
  - bees 12 to 9 (-4.1);
  - crabs 30 to 38 (+3.7).
- A crab row is worth about 0.55 (repetition factor 0.60), a skull row about 2.9.
- Lower scored exposure, not fewer placements. Why the creature modules thin out at castle 10 is the next measurement (P3c).

**P4, castle travel.**

| | route travel per castle interval, mean | CV |
|---|---|---|
| NORMAL | 2,968 m | 0.041 |
| PUNISHER | 3,285 m | 0.043 |
| NGH | 3,529 m | 0.045 |

The intervals are already consistent (NORMAL 2,766-3,424 m), so there is little room for a soft preference to help.

**P3c, why creature decks thin out at NGH castle 10.**

| NGH castle | creature modules tried / accepted per course | layer refusals |
|---|---|---|
| 8 | 77 / 43.5 | 26% |
| 9 | 76 / 43.6 | 31% |
| 10 | 74 / 39.4 | 34% |

- Confirmed from the stored courses: fewer skull and blob decks are accepted at castle 10, mostly on the layer check.
- The crab count per crab deck rises with height (the generator's crab formula). Crabs fill the gap and score low.
- Supported hypothesis: the rules 17 layer change helps creature decks too (a blob's tipping bridge launches). It must be measured at castles 9-10, which only full-height courses reach.

## Priority 2 change: Enforcer-barred bees and crab decks (rules 17)

The Enforcer rule bars a creature next to an Enforcer encounter, not a hazard. Such a draw is now drawn again by the variety memory:
- a barred bee becomes a vertical saw or a cannon (a gap saw there would fail the creature-reach rule);
- a barred crab deck becomes a spike mat, a floor saw or a crusher.

A spiked block remains only where nothing else fits. NORMAL (no Enforcers) is unchanged. Saw-protection conversions keep the spiked block, which needs no timing window.

NGH seeds 7311-7313, 0-600 m, generation only, same code before and after:

| | before | after |
|---|---|---|
| spiked blocks, share of on-route hazards | 34.7% (158) | 31.4% (139) |
| total on-route hazards | 455 | 442 |
| vertical saws | 16.7% | 19.9% (watch) |
| cannons | 2.9% | 4.3% |
| geometric integrity | 6 of 6 runs clean | 6 of 6 runs clean |

A first version that also re-drew to gap saws raised NGH spiked blocks in a 600 m sample. Those saws were converted back by the creature-reach rule. It was narrowed (commit 1096c78).

## Priority 4: recommendation

- Build 82 castle intervals already vary by 4.1-4.5% (CV of route travel). NORMAL runs 2,766-3,424 m.
- No soft preference is proposed. Its benefit would be small against the risk of more generation refusals.
- The integrated comparison checks that the other changes do not widen the spread.

## Nine-course comparison campaign (prepared; NOT dispatched: needs the owner's authorisation after the final CI)

**Dispatch:**
- workflow `difficulty-report.yml`, ref `experiment/pendulums` at the final candidate SHA;
- seeds `["7321","7322","7323"]` (fresh: not in the Build 82 campaign), modes `["0","1","2"]`;
- `top` 5000, `solve` 1, `baseline_run` 40752227771.

The consolidate job prints `tools/ci/diag_summary.py` (P1-P4, P2b dominance, KPI 16, P3b ledger, P3c modules per castle) beside the existing lane, variety and batch reports.

**Comparison against the Build 82 30-course baseline, reported separately per category:**

| category | measures |
|---|---|
| difficulty quality | lanes per castle, combined progression, separation erosion (25% standard) |
| gameplay variety | KPI 16, hazard family dominance, entropy, repetition, castle travel CV |
| generator efficiency | module tries, acceptance, refusal causes, discarded attempts, fallbacks, generation time |
| engineering reliability | PROVEN_SOLVABLE slices, integrity violations, generation failures, solver time, heap |

Three seeds per mode are indicative, not statistical. Differences are reported against the baseline's spread, never as a verdict on their own.
