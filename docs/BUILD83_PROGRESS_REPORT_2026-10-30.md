# Build 83 progress report (accelerated execution directive, 2026-10-30)

Nothing was merged, published, released or uploaded. Build 82, the demo and the c8ef538 candidate are untouched. PR #15 and PR #16 stay draft.

## 1. Completed work

| Item | State | Commit / evidence |
|---|---|---|
| Phase 1A plan cache on by default (`-Dclimb.planCache=off` emergency switch) | done | bf5fd66; identical courses on 8 seed/mode cases (overnight report) |
| Phase 1B early rejection on by default (`-Dclimb.earlyReject=false` switch) + explicit attribution (`early_refused_*` tallies, `Module.earlyRejected`, modules.csv `refused_early`) | done | bf5fd66 |
| KPI 7B observability: castle_control.csv requested vs applied factors, saturation, growth, planned rise, slope error, per-castle refusals; batch summary KPI 7B section | done | 7b12f25 |
| Phase 2 slope-aware control (S2) | implemented, A/B'd at full height, **not enabled** (no improvement) | 7b12f25 (flag), 25e7982 (result) |
| Controller audit corrected (categories are controlled independently; no cross-category compensation) | done | 7b12f25 |
| Phase 3 NGH hazard acceptance: refusal causes classified; mortar-row lift tried (`-Dclimb.mortarAdapt`) | tried, **no gain, stays off**; remaining lever is an owner rule | 7db5825; run 41062643253 |
| Phase 5 CI reconciliation (run 41030282332): exactly the 4 expected failures | done | PR #16 comment |
| Phase 5 gem / spread investigation | done; owner decisions | e4a2d64, `docs/verification/build83/GEM_SPREAD_INVESTIGATION.md` |
| Certification package tooling (exec PDF, technical PDF, offline dashboard, CSV/JSON, charts, manifest, ZIP, log scorecard) | done | 0968063, 702b9bd, `docs/CERTIFICATION_PACKAGE.md` |
| Visual atmosphere prototype (off by default) | done, draft PR #17 | 265266c, 4f56b36, 3271353 |
| Mechanic-introduction pop-up UX fix | done, on PR #17 | 26d799a, e71dde7, `docs/TIPS_UX_REPORT.md` |
| Audio / SFX loudness and juice | in progress (branch `av/build83-audio`) | efeca95 |

## 2. Performance (separate from the unresolved Rules 19 tests)

Local, seconds of generation per metre, default build before vs both optimisations (now the default):
NORMAL 7322 0.517 -> 0.238 (-54%), NORMAL 5 0.649 -> 0.206 (-68%), NORMAL 1 0.179 -> 0.120 (-33%), PUNISHER 7322 0.717 -> 0.591 (-18%),
PUNISHER 2 0.343 -> 0.290 (-15%), NGH 7322 0.805 -> 0.628 (-22%). Courses identical (overnight report sections 3 and 5).
GitHub, generation only, 5,000 m (run 41053445468, the new defaults): NORMAL 136-146 s, PUNISHER 322-372 s, NGH 508-569 s per course.

## 3. Castle 1-10 evidence (KPI 7B; lane report total, generation only, seeds 2 and 7322)

| Course | Castle 1 -> 10 | Dips | Worst step |
|---|---|---|---|
| NORMAL s2 / s7322 | +38.4% / +32.1% | 3 / 3 | -8.2% / -5.5% |
| PUNISHER s2 / s7322 | +22.6% / +17.4% | 3 / 4 | -12.2% / -3.8% |
| NGH s2 / s7322 | +0.1% / +7.0% | 5 / 4 | -4.6% / -9.5% |

Mode ordering NORMAL < PUNISHER < NGH holds at every castle (mode means). NORMAL meets the owner's 30-40% reference; PUNISHER and NGH do
not. The slope-aware law did not change this (PROGRESSION_CONTROLLER_AUDIT section 7): the categories the controller could raise are at
their limits (NGH hazards fall 181 -> 152 because hazard modules are refused; enemies at the 1.10 ceiling; terrain barely responds).

## 4. Hazard acceptance (NGH)

Full height (run 41053445468, NGH 2 courses): HAZ 42%, TRAP 34%, CREATURE 48%, MORTAR 6.9%, DROPS 14% of tries accepted; the spiral-layer
rule causes 37-81% of the refusals (MORTAR 81%, DROPS 81%). Local cause breakdown (seed 7322, three castles): the leading layer refusal for
every module kind is a deck within 9.5 m above a launcher (pad, spring, vertical mover) on the revolution below, and the GitHub T4 tables
show most of those decks miss by 0.25 m or less. That spacing rule is the owner's launcher shortcut protection and was not changed.
The mortar-row adaptation (7db5825: a row deck that would clash with a lower layer is lifted up to 1 m) was validated at full height
(run 41062643253, NGH seeds 2 / 7322, `-Dclimb.mortarAdapt=true`, against run 41053445468): mortar acceptance 6.9% -> 7.2%. Layer refusals fell
(81% -> 72%) but hazard-box refusals rose (11% -> 19%): a lifted deck lifts its mortar column into another layer's standing room. No real gain,
so it stays off. Without relaxing a rule, the generator cannot place materially more NGH hazards; the remaining lever is the owner's 9.5 m
launcher spacing rule (most refusals miss it by 0.25 m or less), which needs an owner decision and its own shortcut-safety evidence.

## 5. 16-KPI status and gaps

The certification campaign has not run (it waits for a stable candidate). Known now: KPI 2 generation and geometry clean on 6 of 6
full-height courses (solver not run); KPI 7 / 7B FAIL for PUNISHER and NGH escalation; KPI 14 fingerprint and bundled replay pending the
Rules 19 freeze; OfficialWorldRulesTest gem and spread pending owner decisions. The packager marks every unmeasured KPI NOT VERIFIED.

## 6. CI / branch state

- `fix/build-83-root-causes` (draft PR #16, validation only): head 7db5825. Full CI on d218fa9: 586 core tests, 4 expected failures.
- `visual/build83-atmosphere` (draft PR #17): atmosphere prototype and tips UX; its CI is running.
- `av/build83-audio`: audio and juice (agent still working); to be combined with PR #17 for review.
- `tools/cert-package`: merged into `fix/build-83-root-causes` (0f28f55).

## 7. Owner decisions and blockers

1. Gem midpoint outlier (one gem of 20 at 5.35 m vs 4.5 m): generator fallback, test bound, or accept.
2. Run-difficulty spread 1.067 vs 1.10: more seeds, accept as noise, or change the statistic.
3. PUNISHER / NGH escalation: castle-1 calibration (lower NGH/PUNISHER castle 1 toward its target) and/or a change to the 9.5 m launcher
   spacing rule, which blocks most hazard modules by under 0.25 m. Both are owner-visible; neither was done.
4. Tips UX wording, top edge placement, the unused `ledge` tip, the 14 s desktop control hint.
5. Certification campaign: starts when the candidate is frozen (Phase 3 result, the decisions above, and the Rules 19 freeze).
