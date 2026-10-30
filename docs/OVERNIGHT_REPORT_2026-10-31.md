# Build 83 overnight engineering report (2026-10-30/31)

Branch `fix/build-83-root-causes`. Nothing was merged, packaged, published or uploaded. Build 82 and the demo are untouched. No gameplay
rule, difficulty target, cap or density was changed. Every optimization is behind a switch that is **off by default**, so the default build
generates exactly as before.

## 1. Executive summary

- **A. Plan cache:** implemented, shadow-validated and validated active. Courses are identical; generation is 10–17% faster.
- **B. Avalanche checks:** the cost was measured and the cause found. No exact reduction beyond A and C helps: one was tried, proven
  identical, found to gain nothing and reverted.
- **C. Early rejection:** implemented. The same modules are kept and refused. It saves 4–64% depending on the seed, and is largest on
  NORMAL's slowest seeds.
- **D. Progression controller:** audited with measured castle data. A strategy is proposed; nothing was changed.

## 2. Performance (local, same machine, seconds of generation per metre)

| Case | Default | Plan cache | Early rejection | Both |
|---|---|---|---|---|
| NORMAL 7322 (381 m) | 0.517 | 0.448* | 0.285 | 0.238 (−54%) |
| NORMAL 5 (260 m) | 0.649 | −10%† | 0.236 | 0.206 (−68%) |
| NORMAL 1 (189 m) | 0.179 | 0.159 | 0.136 | 0.120 (−33%) |
| PUNISHER 7322 (325 m) | 0.717 | 0.622 | 0.675 | 0.591 (−18%) |
| PUNISHER 2 (218 m) | 0.343 | 0.298 | 0.329 | 0.290 (−15%) |
| NGH 7322 (235 m) | 0.805 | 0.680 | 0.722 | 0.628 (−22%) |

\* Measured over 319 m (5 slices). † Measured over 190 m (3 slices) against 0.867 for the same 190 m: −10%. Figures from different heights
are not comparable across rows. One local machine; up to two runs shared the CPU.

## 3. Plan cache (priority A)

- **Inputs** (safety audit): everything a plan reads is in the key. That is the whole starting simulation encoded by reflection (climber,
  element states, hazard and creature states, avalanche blocks, timers), the course (every element and hazard with every field, route and
  decoy indices, key rooms, forks), the tuning, the call's flags and the planner switches.
  - The planner and simulation use no random numbers.
  - Only the motion-cache handle is excluded. Every trial installs its own, and the cache is value-transparent.
  - A unit test fails if a new field is ever left out of the key.
- **Side effects:** the planner leaves its start state untouched (verified in shadow mode). It raises observe-only report tallies, which are
  recorded per entry and replayed in order on a hit. It returns a mutable result, so every hit gets a fresh copy.
- **Scope and invalidation:** one slice attempt on one thread. Any change to any input changes the key. Entries are matched by the full key,
  not by a hash alone. A memory cap clears the scope.
- **Shadow evidence:**
  - 0 mismatches on every run. Shadow mode checks every would-be hit's result fields, the chosen move's policy, a replayed trial to the same
    end state, the tallies and the untouched start state.
  - Would-be hit rate: 12–15% of plans.
- **Active evidence:** the cache-off, shadow and cache-on runs (game-faithful growth with backtracking) give identical:
  - encoded slice bytes (every element, hazard, enemy and traversal module);
  - module attempts (kind, role, result, refusal counters, element and hazard ranges);
  - slice failures and backtracks;
  - every report tally.

  Seeds: NORMAL 7322/1/5, PUNISHER 7322/2/3, NGH 7322/1/2. The only difference ever seen was `avalanche_proof_ms`, a wall-clock millisecond
  tally that differs between two cache-off runs as well.

## 4. Avalanche check cost (priority B)

- In an avalanche encounter check, 99% of the time is planning: a 24-wait sweep with 8 s longer trials, simulated with live blocks. That
  is about 0.41 s per plan, against 0.06 s for other encounters. Walking the climber through costs almost nothing.
- There are no exact repeats to reuse: each arrival delay and each re-proof after a module starts from a different state or course.
- Profile (JFR, NGH 7322 with both switches on):
  - 24% avalanche rigid-body step, mostly `StrictMath.cos/sin` of each block's angle, recomputed for every deck it touches.
  - 28% crab deck lookups (`Course.deck`, `isPlatform`).
- **Exact change tried, measured and reverted:** each block's cosine and sine were computed once per substep instead of at every deck
  contact, and the landing scan tested the cheap height condition first. The patched build was bit-identical to the previous one: recorded
  signatures matched for NGH 7322 (3 slices) and PUNISHER 7322 (4 slices). But it was no faster (0.695 vs 0.686 s/m, 0.632 vs 0.629 s/m),
  because a block meets only about one deck per substep. The change was reverted; the simulation code is unchanged.
- Not done: crab deck lookups. Caching them inside the simulation would need invalidation on course growth in the live game, so it is not
  provably exact without more work. The search was not shortened and no physics was simplified.

## 5. Early rejection (priority C)

- **Measured** (refused modules by the first check that refused them):

  | Case | Modules refused only by a geometry-only check after their proofs | Time spent proving them |
  |---|---|---|
  | NORMAL 5 | 5 conflict + 11 threat budget | 104 s of 171 s |
  | NORMAL 7322 | 1 threat budget | 10 s of 195 s |
  | PUNISHER 7322 | 2 conflict | 10 s of 231 s |
  | NGH 7322 | none | 0 |

  All other post-proof refusals were genuine plan failures, which depend on the plan and cannot be decided early.
- **Change** (`-Dclimb.earlyReject`, off by default):
  - The encounter-conflict rule and NORMAL's threat budget read only geometry and hazards. Every commit check must pass for a module to be
    kept, so they now may run before the proofs.
  - Kept and refused modules are identical: slice bytes and every module attempt's outcome and placement match the default run (NORMAL
    7322/5/1, PUNISHER 7322/2, NGH 7322).
  - Diagnostics differ only for a refused module whose proofs would also have failed: it is counted as conflict/threat instead of plan, and
    its proofs' tallies are not raised. This is why it stays off until the owner decides.
- The castle-window rollback was also examined. It cannot be decided early, because whether the next variant succeeds depends on the proof.

## 6. Determinism evidence

- Equivalence runs (`PlanCacheProbe`): seeds and modes as above, game-faithful growth (`GameGrowth`, backtracking included). Logs are in the
  scratchpad; results are recorded in `docs/OVERNIGHT_RECOVERY.md`.
- Cross-build check (tooling for future exact optimizations: `CLIMB_SIGOUT` / `CLIMB_SIGREF` in PlanCacheProbe): the reverted
  simulation speed-up matched the previous build bit for bit on NGH and PUNISHER 7322.
- `PlanCacheTest` (5 tests) passes locally.
- Branch CI is run for the final commit (section 8).

## 7. Difficulty progression audit (priority D)

Details are in `docs/PROGRESSION_CONTROLLER_AUDIT.md`. In short:

- **Escalation is flat.** Castle 1 → 3: NORMAL +4%, PUNISHER +1%, NGH +5%, against target rises of +8% to +11%.
- **Castle 1 overshoots its target in every mode.** The terrain curve drives it, and the terrain factor is on its floor (PUNISHER and NGH)
  while still 17–29% over target.
- **The controller compensates by easing hazards and enemies.** That is the PUNISHER castle-2 hazard dip.
- **NGH ceilings are binding.** Enemies are pinned at their +10% bound and still reach only 60–69% of target. Hazard module acceptance
  collapses by castle 3.
- **Proposal (owner decision):**
  - Calibrate castle 1 through terrain (curve position or a castle-1 terrain floor).
  - Add a minimum per-castle rise (slope tracking), so a hot castle is followed by a smaller rise, never a dip.
  - Add anti-windup for saturated categories.
  - Report requested versus applied factors.

## 8. Repository status

- Branch `fix/build-83-root-causes`. Commits this night: 426cbe9, a731296, dcf1c7b, e7346a2 (all pushed).
- Full `ci.yml` suite: draft validation PR #16 (never to be merged; base is the frozen Build 82 branch). This was the first full CI of the
  Rules 19 work. Run 41016672931 on e7346a2 ran 586 core tests with 8 failures, none from the overnight code (whose switches are off):
  - Fixed by the next commit (Rules 19 bookkeeping):
    - Tarzan and turbo were missing from the verification coverage register (2 tests).
    - The ledge pull-up test still expected the old hit = failure rule. It now asserts that no clean arrival exists, that the link is made only
      as a marked collision-assisted move, and that the strict rule refuses it.
    - The spinning-ramp test still expected spinning ramps, which the owner removed from generation on 2026-10-29.
  - Expected until the Rules 19 freeze (owner rule: bump the rules version, fingerprint and replays only at the final freeze):
    `DeterminismTest` fingerprint and `BundledReplayTest`.
  - Owner decisions needed:
    - `OfficialWorldRulesTest` gem midpoint: 5.35 m off against 4.5 m. Swing and turbo modules change where a rest can sit.
    - `OfficialWorldRulesTest` difficulty spread across runs: 1.067 against at least 1.10.

    Both are generation outcomes of Rules 19. Fixing them means changing generation or a test's tolerance, so they are left for the owner.
- No release, Steam or workflow dispatch. Frozen branches (Build 82, the demo, the c8ef538 candidate) are untouched. PR #15 is untouched.

## 9. Owner decisions needed

1. Turn the plan cache on by default? It is validated identical; it saves 10–17%.
2. Turn early rejection on by default? Courses are identical; only refusal-reason diagnostics shift for doubly-refused modules.
3. The controller strategy in the audit (S1–S4): any change alters every course and needs a rules bump at the next freeze.
