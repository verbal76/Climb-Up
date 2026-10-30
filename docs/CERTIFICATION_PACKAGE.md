# Certification package (KPI framework reporting tool)

`tools/ci/cert_package.py` turns a difficulty campaign (the `lanes-mode-<m>-seed-<s>` course folders of `difficulty-report.yml`)
into one reporting package for the owner's KPI framework (`docs/KPI_FRAMEWORK.md`). It only reads the campaign's files. It changes
no course, score or game code, and it runs no Java or Gradle. It needs Python 3 and matplotlib, and takes about 15 s for nine courses.

## Running it

**From the workflow.** Dispatch `Difficulty report` with `certify` = `1`, the usual `modes` / `seeds`, and optional `cert_args`. After
its normal steps, the consolidate job runs the packager and the self-check, then uploads the `certification-package` artifact. Leave
`certify` empty and the workflow runs exactly as before.

```
cert_args: --evidence ci_conclusion=success --evidence ci_sha=<full CI commit> --evidence fingerprint=<generator fingerprint>
           --overrides docs/verification/build83/evaluation_overrides.json
```

**Locally.** Run it on downloaded artifacts, for example to add the analyst evaluation after the campaign:

```
python3 tools/ci/cert_package.py OUT_DIR PARTS_DIR [--variety-dir DIR] [--overrides FILE] [--evidence k=v ...]
        [--manifest-extra k=v ...] [--expect-modes '["0","1","2"]' --expect-seeds '["1","2","3"]'] [--build-label "Build 83"]
python3 tools/ci/cert_package.py --selftest OUT_DIR
```

| Input | Meaning |
|---|---|
| `PARTS_DIR` | One sub-folder per course (LaneReport output). |
| `--variety-dir` | `variety_report.py` output (`variety_summary.json`: variety flags and seed similarity). Optional. |
| `--expect-modes` / `--expect-seeds` | The expected panel (default: env `EXPECT_MODES` / `EXPECT_SEEDS`, as for `batch_summary.py`). A missing course is kept as a row and is never measured. |
| `--evidence k=v` | Evidence from outside the campaign. Keys: `ci_conclusion`, `ci_sha`, `ci_run_url`, `ci_minutes`, `fingerprint`, `release_android`, `release_windows`, `release_linux`, `release_steam`, `release_steamdeck`. The env var `CERT_EVIDENCE` (space-separated `k=v`) is also read. |
| `--overrides` | `evaluation_overrides.json`: the analyst evaluation (schema below). |
| `--manifest-extra k=v` | Recorded verbatim in the manifest. |
| env `GITHUB_SHA`, `GITHUB_RUN_ID`, `GITHUB_SERVER_URL`, `GITHUB_REPOSITORY`, `DISPATCH_INPUTS` | The candidate commit (or `git rev-parse HEAD`), the run link and the dispatch inputs, including `jvm_props`. |

## Outputs

`OUT_DIR/Upwardly_Build83_Certification/` holds:

| File | Content |
|---|---|
| `manifest.json` | The candidate SHA, run id and URL, dispatch inputs, JVM props, expected and found modes and seeds, rules versions, fingerprints, course commits, evidence, the overrides file and its hash, tool versions (Python, matplotlib, Java on the packaging host), machine info, the SHA-256 of every course input file, the SHA-256 and size of every package file, and the final statuses. |
| `data/` | `courses.csv`, `castles.csv` (one row per course and castle, every per-castle metric), `mode_castles.csv` (mean / sd / min / max / n per mode and castle), `modes.csv`, `hazard_types.csv`, `mechanics.csv`, `modules.csv`, `slices.csv`, `windows.csv`, `avalanche_events.csv`, and `package_data.json` (all of these). Together they are enough to recompute every figure. |
| `kpi_measurements.json` / `.csv` | KPIs 1-16, 7A and 7B. Each has checks (rule, measured value, status, evidence file and columns, worst case) and measurements (value per mode or course, unit, evidence, worst case as run / mode / seed / castle, or "not measured in this campaign"). It records the automatic status, the final status and who decided it, and the missing inputs. |
| `charts/*.png` / `*.svg` | 01 scorecard; 02 progression per difficulty (seed curves, mean, min-max band, target dashed); 03 combined; 04 castle-to-castle %; 05 composition (mitigation below zero); 06 target vs actual; 07 separation (erosion limit dashed); 08 hazard and 09 enemy density; 10 avalanches (per castle, blocks per event); 11 Enforcers (tiers per castle; Sim behaviour counters); 12 module distribution; 13 acceptance and refusal reasons; 14 route variety; 15 solver outcomes; 16 generation performance; 17 slowest slices; 18 requested vs applied controller factors and saturation; 19 mechanic utilization. |
| `dashboard.html` | One offline file: inline CSS and JS, data embedded as JSON, no network access. Filters: difficulty, seed, castle, mechanic, hazard type, KPI and status. Hover tooltips, KPI and castle drill-downs, sortable tables, light and dark mode. |
| `Upwardly_Build83_Certification_Executive.pdf` | Identity, the four questions, the scorecard, findings, risks and recommendations (from the overrides, otherwise "analyst evaluation pending"), automatic FAILs and missing evidence, and the key charts. |
| `Upwardly_Build83_Certification_Technical.pdf`, `TECHNICAL_REPORT.md` | Methodology, the seed panel, every KPI with its checks, measurements and evidence, castle tables per course, all charts, exceptions and limits. |
| `exceptions.txt` | Missing courses, failed gates, unproven slices, integrity violations, automatic FAILs and refused analyst statuses. |
| `evaluation_overrides.json` | A copy of the overrides used, if any. |

`OUT_DIR/Upwardly_Build83_Certification.zip` is the whole folder. `OUT_DIR/manifest.json` (the outer manifest) holds the ZIP's SHA-256
and size and the inner manifest's SHA-256. `.zip.sha256` holds the same checksum in `sha256sum` format.

## Statuses

The statuses are PASS, FAIL, WATCH and NOT VERIFIED. **A missing measurement is never a PASS.**

The packager sets an automatic status only where the framework gives an objective rule:

| KPI | Objective checks |
|---|---|
| 1 | One real commit across the courses, equal to the candidate. One rules version. One fingerprint (from the course files or `--evidence fingerprint`). The standard sample. Full CI success on the candidate (`ci_conclusion`, `ci_sha`). |
| 2 | Every expected course present. 3 seeds per difficulty. 5,000 m. 10 complete castles. 100% of slices `PROVEN_SOLVABLE` (requires the `verdict` column). Gate generation `COMPLETE`. 0 integrity violations. Gate passes. |
| 7B | Ordering NORMAL < PUNISHER < NGH at every castle (mode means). Separation erosion of at most 25% for each adjacent pair. Erosion is (A1 - A10) / A1, where Ak is the harder mode's percentage advantage at castle k. A harder mode that has no advantage at castle 1 fails. |
| 10 | 100% of slices `PROVEN_SOLVABLE`. |
| 11 | NORMAL avalanches only in castles 9-10, with at most 2 blocks each (from `hazards.csv` and `encounters.csv`). |
| 12 | No Enforcers in NORMAL (from `encounters.csv` and `hazards.csv`). |
| 14 | Full CI success on the candidate commit (evidence). |
| 15 | All five platform evidence keys are `success`. Any `failure` gives FAIL. Passing does not authorise publication. |

How a check decides the KPI:

- Any objective check that fails gives an automatic **FAIL**.
- A KPI passes automatically only when its rule decides the whole KPI (KPIs 1, 2, 14, 15), every check passes and no required input is missing.
- Everything else is **NOT VERIFIED**, with its measured values, until an analyst evaluates it.
- KPI 7 combines 7A and 7B: FAIL if either fails, PASS only if both pass.

## Analyst overrides (`evaluation_overrides.json`)

```json
{"analyst": "name", "verdict": "GO / NO-GO text for the executive report", "comparison": "against the previous build",
 "kpis": {"3": {"status": "WATCH", "interpretation": "...", "recommended_action": "..."}, "7A": {"status": "PASS", "interpretation": "..."}},
 "findings": ["..."], "risks": ["..."], "recommendations": ["..."]}
```

The package records who decided each status (automatic, analyst, or derived from 7A / 7B) beside the automatic status. An analyst
**PASS is refused**, and recorded in the exceptions, when:

- the KPI has an automatic FAIL;
- a required input is missing;
- for KPI 7, 7A and 7B do not both pass.

An analyst status that differs from an automatic PASS or FAIL is accepted and listed in the exceptions.

## Self-check (`--selftest [OUT_DIR]`)

The self-check runs two kinds of test.

**Synthetic tests:**
- An empty campaign must not pass any KPI, even if the analyst marks every KPI PASS.
- A legacy course without the solver `verdict` column must not pass KPI 2 or KPI 10.
- KPI 14 must be NOT VERIFIED without CI evidence, PASS with matching success, and FAIL with failure.

**With OUT_DIR, checks on the package itself:**
- The ZIP checksum matches the outer manifest, and every member is intact.
- Every file's SHA-256 matches `manifest.json`, with no unlisted files.
- KPIs 1-16, 7A and 7B are all present.
- No automatic PASS has missing inputs.
- The dashboard parses, has valid embedded JSON, and has no external references or network calls.
- Both PDFs are valid.

## Limits

- **Older course folders degrade to "not measured in this campaign".** Such folders can lack `castle_control.csv` (and its KPI 7B columns), the solver `verdict` / `witness_setbacks` / `resyncs` columns, `refused_early`, the planned Enforcer and avalanche counts, and the newer Enforcer and avalanche counters. The sample campaign used to test the tool (rules 12) lacks all of these.
- **These are not instrumented by LaneReport at all:** Enforcer pursuit distance, platforms followed, knock-offs and disengagements, and the collision-assisted vs clean proof split (unless a `*assist*` column appears in `solver.csv`). The Enforcer behaviour counters are not split by tier. They are reported as not measured and never inferred.
- **The generator fingerprint is not written by LaneReport,** so KPI 1 needs `--evidence fingerprint=...`. KPI 14 (determinism, replays, saves) and KPI 15 (platforms) come only from evidence.
- **KPI 6's double-counting audit (ledger rule by rule) is outside this package.**
