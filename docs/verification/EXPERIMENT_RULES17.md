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
