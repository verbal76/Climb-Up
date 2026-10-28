# Seed ledger - post-correction acceptance batch (owner authorisation 2026-10-28)

Selected before execution and never changed. Each seed below was checked to appear nowhere in the repository's tests, fixtures, probes,
workflow inputs or earlier campaign records (grep over *.java, *.md, *.yml, *.json, *.py: zero matches) before it was chosen.

| Mode | Seeds | Course length |
|---|---|---|
| NORMAL (0) | 7301, 7302, 7303, 7304, 7305, 7306, 7307, 7308, 7309, 7310 | 5,000 m |
| PUNISHER (1) | 7301, 7302, 7303, 7304, 7305, 7306, 7307, 7308, 7309, 7310 | 5,000 m |
| NOT GONNA HAPPEN (2) | 7301, 7302, 7303, 7304, 7305, 7306, 7307, 7308, 7309, 7310 | 5,000 m |

Each (seed, mode) pair is a distinct course: the mode changes the difficulty profile the generator builds from, so the same seed number in two
modes gives two different climbs. None of these 30 pairs was used before.

## Previously used seeds (not fresh evidence; regression use only)

- Campaign A and earlier campaigns: NORMAL and PUNISHER seeds 1-5; NGH Round 1: seeds 1-5 (run 40549406903).
- Regression fixtures and tests: seeds 1, 2, 3, 5, 7, 12, 21, 33, 34, 77, 224, 2024 and others named in core/src/test.
- Correction-phase probes (2026-10-28): NORMAL seed 2, PUNISHER seed 2, NGH seed 1 (slices 0-12), NORMAL seed 2024 (slice 48 dead end).

## Rules

- All 30 courses run on one frozen source commit and the same verification rules (recorded with the run below).
- A failed seed is never replaced. Its seed, SHA, logs, artifacts and failure classification are kept.
- No second 30-course batch is started automatically; a new statistical batch needs new unused seeds and owner authorisation.

## Execution record

(filled in when the batch is dispatched: source SHA, GitHub run id, per-course results)

## 30-course acceptance campaign (owner-authorised, 2026-10-28)

- **Validation gate.** CI run 40727062206 on 1123a32 (integration/build-82-repairs): core 500 tests, 0 failures, 3 skipped (the existing
  skips); desktop 129 tests, 0 failures.
- **Campaign.** difficulty-report.yml run 40752227771, workflow_dispatch on integration/build-82-repairs @ 1123a32:
  - seeds 7301-7310;
  - modes 0 NORMAL, 1 PUNISHER, 2 NOT GONNA HAPPEN;
  - top 5000 m, solver on.
- **Rules.** Rules 16, fingerprint 978eaa5128ad2201d29dabbf.
- **Policy.** Every course is kept in the results, failed or incomplete. No seed is replaced.
