# Overnight recovery record (Build 83, 2026-10-30/31)

Owner directive: "UPWARDLY BUILD 83 — AUTONOMOUS OVERNIGHT ENGINEERING DIRECTIVE" (priorities A plan cache, B avalanche check cost,
C early rejection, D progression controller audit - measurement/design only). Behaviour must be preserved exactly; no campaign, no release,
no merge, no Steam, Build 82 fallback untouched. PR #15 never merged.

- Branch: `fix/build-83-root-causes` (worktree `/tmp/claude-0/-home-user-Climb-Up/3cc9bd9b-3462-53b3-a9d2-97a9a2be1c43/scratchpad/pendwt`)
- Start commit: 426cbe9. Plan cache checkpoint: a731296.
- Local runner: compile with `./gradlew -q --offline :core:testClasses`, classpath in `../cp.txt` (scratchpad), run probes with
  `CLIMB_PROBE=1 CLIMB_CASES=... java -Xmx3g -cp "$(cat ../../cp.txt)" org.junit.runner.JUnitCore <class>` from `core/`.
  Do not recompile main classes while probe JVMs are running.

## Status

| Priority | State | Evidence |
|---|---|---|
| A plan cache | DONE: off by default, validated identical on 8 seed/mode cases, 10-17% faster | table below |
| B avalanche | DONE (measurement): 99% planning; exact cos/sin hoist tried, identical, no gain, reverted | DECISIONS, report section 4 |
| C early rejection | DONE: `-Dclimb.earlyReject` off by default, identical courses on 6 cases, up to -64% (NORMAL 5) | report section 5 |
| D controller audit | DONE (design only): docs/PROGRESSION_CONTROLLER_AUDIT.md section 6 | castles.log |

## Plan cache equivalence so far (off vs shadow vs on, slice bytes + module log + failures + untimed tallies)

| Case | off s/m | on s/m | saving | result |
|---|---|---|---|---|
| NORMAL 7322, 319 m (5 slices) | 0.537 | 0.448 | 17% | identical (shadow + on), 0 mismatches |
| NORMAL 1, 189 m | 0.180 | 0.159 | 12% | identical |
| PUNISHER 7322, 325 m | 0.717 | 0.622 | 13% | identical |
| PUNISHER 2, 218 m | 0.341 | 0.298 | 13% | identical |
| NGH 7322, 235 m | 0.795 | 0.680 | 14% | identical (corrected probe) |
| NGH 1, 173 m | 0.973 | 0.839 | 14% | identical |
| NORMAL 5, 190 m | 0.867 | 0.783 | 10% | identical |

Only difference ever seen: the tally `avalanche_proof_ms` (wall-clock milliseconds; differs between two OFF runs too). The probe now
excludes `*_ms` tallies. Running: pcB2.log (PUNISHER 3, NGH 2), modcost.log (B/C measurement, cache off).

## Next actions (remaining)

- Push the final checkpoint without [skip ci] so ci.yml validates the branch; read the result; report.

## Earlier plan (done)

1. When pcA/pcB finish: record results; compile the B/C instrumentation; run ModuleCostProbe (NGH 7322 150 m, P 7322 300 m) with cache ON.
2. Decide B and C optimizations from the measurements (exactness required; counters/tally attribution must also stay identical).
3. CastleAuditProbe N/P/NGH 7322 to ~2000 m with `-Dclimb.planCache=on` (identical output, faster).
4. DECISIONS entries, push, ci.yml run on the branch (no [skip ci]) for the final checkpoint, overnight report.
