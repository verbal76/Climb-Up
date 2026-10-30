# Upwardly Build 83: master workstream reconciliation and pre-KPI readiness audit

Owner directive "Master workstream reconciliation & pre-KPI readiness audit", 2026-10-30, about 17:15 UTC. This is a status audit. It
started no simulation, no new CI campaign, no merge, no default change and no build. One action was taken to protect finished work: the
audio branch `av/build83-audio` existed only in the session container and was pushed so it cannot be lost (section 2, row 13).

State vocabulary used below:

- **committed** — on a branch on GitHub;
- **CI passed** — the CI workflow succeeded on that exact SHA;
- **solver validated** — a `solve=1` Difficulty report proved every slice;
- **merged** — into a protected or release branch (nothing in Build 83 is);
- **packaged** — an APK / EXE was built;
- **released** — published.

Nothing below is merged, packaged or released.

---

## 1. Executive summary

- **One gameplay candidate branch exists:** `fix/build-83-root-causes` (draft PR #16, head `72c0a6d`).
  - **What it carries:** the Rules 19 work, the generator speed-ups (plan cache, early rejection, both default on), KPI 7B
    instrumentation, the certification-package tooling and the geometry-audit tooling.
  - **Latest change:** the owner-approved geometry change (radius 11.5 m plus the pad-gap fix, approved 2026-10-30, "go with option C plus
    the pad gap fix").
- **That head is committed but not yet validated.** Three runs on `72c0a6d` are in progress:
  - CI 41095330974;
  - solve=1 campaign 41095334120;
  - shortcut audit 41095339447.
- **The radius itself is solver-validated.** Run 41089449890, without the pad fix: 9 / 9 courses, 625 / 625 slices PROVEN_SOLVABLE.
- **Presentation work sits on two review branches:**
  - `visual/build83-atmosphere` (PR #17): atmosphere prototype, off by default; tips banner; mortar plinth; depth-mover posts removed.
  - `av/build83-audio`: audio mastering and juice.
  - **Neither has ever run CI.** Both change presentation only and do not affect measured gameplay or generation.
- **Known CI failures on the candidate (last complete CI 41030282332, `d218fa9`):** exactly 4.
  - Two are by design until the freeze: generation fingerprint and bundled replay.
  - Two are owner decisions: OfficialWorldRulesTest gem midpoint (5.35 m vs 4.5 m) and run-difficulty spread (1.067 vs 1.10).
- **Progression (KPI 7 / 7B):** escalation is better with radius 11.5 (castle 1 to 10: NORMAL +37 %, PUNISHER +26 %, NGH +17 % on 3
  seeds). Mode separation still erodes by 57-60 % against the framework's 25 % limit. That is the last open design question for the
  generator.
- **Assessment: NOT READY to freeze yet.** Freezing is close and needs no new development. The prerequisites are:
  1. finish reading the three running validations;
  2. owner decisions on gem midpoint, spread and castle-1 calibration;
  3. the freeze steps (rules 19 bump, fingerprint, bundled replays) and one full CI.

  Section 9 has the GO conditions.

**Conflict to flag.** This directive says "do not activate radius 11.5 without approval". The owner approved it earlier the same
afternoon, and it is committed as the default in `assets/data/tuning.json` on the candidate branch (`72c0a6d`). It is not merged, packaged
or released. If that approval was not meant to change the branch default yet, the revert is one line (`"radius": 11.5` back to `10.0`);
the pad-gap fix is independent of it.

---

## 2. Workstream status matrix

Abbreviations:

- **G** = affects gameplay / generation; **P** = presentation only; **R** = reporting only.
- **Pre-KPI** = must be resolved before the full KPI audit.
- **Ready** = ready to integrate into a candidate.

| # | Workstream (objective) | Status | Branch / SHA / PR | What is implemented | Tests done (result) | Running | Remaining | Blockers | Kind | Pre-KPI | Ready |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | **Build 83 root causes / procedural generation (Rules 19)**: C1 shortcut rule, hit is not a failure, timed rebound, Tarzan swings, turbo ramps, descents, lowered pads, permanent plank holes, plan cache and early rejection (both default on) | ACTIVE (validation) | `fix/build-83-root-causes` `72c0a6d`, PR #16 (draft, base `ccr-cb458cd1-w4dh5c`) | All listed, committed. `RULES_VERSION` in code is still 18 (the rules 19 bump is a freeze step). | CI 41030282332 on `d218fa9`: 586 core tests, exactly the 4 known failures. Full-height generation 9/9 courses (41053445468); plan cache and early rejection proven identical. | CI 41095330974 on `72c0a6d` | Triage CI on `72c0a6d`; freeze steps | Owner decisions (gem, spread, calibration) | G | yes | after freeze |
| 2 | **Geometry audit** (layer separation, corridor, dead zone, rule audit, radius 10 / 11.5 / 13, rule variants) | COMPLETE | report `72a0ebe` on `audit/geometry`, merged into the candidate branch at `a7d259f` | Instruments (off by default), report, source inventory, comparison package (artifact of run 41089358746) | 6 full-height candidates × 9 courses; shortcut audits; 3 solver runs | — | — | — | R | no | yes (done) |
| 3 | **Solver validation of the geometry candidates** | COMPLETE | runs on `3397a41` | — | r11.5 41089449890: 9/9, 625/625 proven. lowreach 41089445815: 9/9, 621/621, but NGH falls below PUNISHER at castle 10. r11.5+lowreach 41089453619: 9/9, 621/621. | — | Superseded for the candidate by 41095334120 (same radius plus the pad fix) | — | G | evidence | — |
| 4 | **Radius 11.5 (option C)** | ACTIVE (committed, validation running) | `72c0a6d` | `tuning.json` radius 11.5 | Solver-validated at `3397a41` (row 3) | 41095334120 (solve=1, 9 courses) | Read results | See the conflict note in section 1 | G | yes | after validation |
| 5 | **Pad shortcut gap fix** (C1 pad reach 10.25 m at edge gaps ≤ 2 m; only tightens) | ACTIVE (committed, validation running) | `72c0a6d` | `CourseGenerator.REACH_PAD`; ShortcutReachTest pins the 6 audit pairs; LayerSpacingTest expectation updated for pads | Local: ShortcutReachTest 6/6, LayerSpacingTest 6/6, DescentRouteC1Test 3/3 | T5 audit 41095339447 (expect 0 pad shortcuts); CI 41095330974 | Read results | — | G | yes | after validation |
| 6 | **Low-reach launcher variant / 9.5 m rule** | DECIDED (owner chose C; blanket kept) | switch `-Dclimb.layerRule=lowreach` (off), `3397a41` | Experiment switch only | Shortcut audit: no new shortcut; solver 9/9 | — | Nothing (re-open only on new evidence) | — | G (off) | no | n/a |
| 7 | **Solver-only mortar / bee knock-up lifts** (solver climbs 6-8 m with a hazard's knock) | COMPLETE: covered by an existing owner rule | — | Rules 19 "Emergent movement, category C": knock-backs and rebounds may reach later revolutions; the generator neither prevents nor relies on them | T5 audits: 80-91 such pairs per 6 courses, mostly solver-only | — | None. Not a defect under the owner's rule. My geometry report's "open question" wording should be read this way. | — | G | no | n/a |
| 8 | **Castle progression and mode separation (KPI 7 / 7B)** | PENDING OWNER DECISION | candidate | Instrumentation complete (castle_control.csv; KPI 7B tables). Slope control tried: no gain, off. Mortar-row lift tried: no gain, off. | Radius 11.5: growth +37 / +26 / +17 %; erosion 57 % (P over N) and 60 % (NGH over P) against the ≤ 25 % target | 41095334120 re-measures it | Owner: castle-1 calibration for PUNISHER / NGH, or accept the measured separation | Owner | G | yes (it changes generation) | — |
| 9 | **Rules 19 optimisation and generator performance** | COMPLETE (measurement continues in KPI 13) | candidate (`bf5fd66`) | Plan cache (10-17 % faster), early rejection (4-64 % faster); both on, identical courses | PlanCacheProbe identical on N / P / NGH seeds | — | Radius 11.5 adds 20-60 % generation time per course (geometry runs); measure in the campaign | — | G (speed) | no | yes |
| 10 | **Mortar trajectory, platform interactions, rendering** | COMPLETE (pending owner review of the plinth) | plinth `2d945a7` on `visual/build83-atmosphere` | Trajectory unchanged. Plinth 0.45 m replaces the 2 m block (renderer). mortarAdapt off. Player-only collision contract not recommended (geometry report section 7). | Screenshot `docs/visual/mortar_plinth_1280x720_below.png` | — | Owner review; integrate with the visual branch | — | P | no | yes |
| 11 | **Visual atmosphere prototype** | PENDING OWNER REVIEW | `visual/build83-atmosphere` `bb4447c`, PR #17 (draft, base `fix/build-83-root-causes` at `acb501c`, behind the candidate) | Atmosphere behind `-Dclimb.atmos` (off) | AtmosShot screenshots; **no CI has ever run on this branch** | — | Owner: keep off / turn on / drop; merge the candidate into it and run CI once | Owner | P | no | after CI |
| 12 | **Depth-mover floating posts** | COMPLETE (owner approved the look) | `bb4447c` (visual branch) | Two draw calls removed (renderer only) | 12 before/after screenshots, 4 heights × 3 moments | — | Integrate with the visual branch | — | P | no | after CI |
| 13 | **Tutorial banner replacement** | COMPLETE (wording / placement decisions open) | `26d799a`, `e71dde7` (visual branch) | Top-edge banner, 2.5 s, dismissible, no stacking, seen-state respected | TipBannerTest; screenshots at 16:9 / 20:9 / 160 % text | — | Owner: wording, placement, unused `ledge` tip, 14 s desktop control hint | Owner | P | no | after CI |
| 14 | **Audio, SFX and game juice** | COMPLETE (pending owner listening review) | `av/build83-audio` `efeca95` (**pushed today**; base `d218fa9`); no PR | Mastered effects, equal-dB slider (0 silent, 10 = 0 dB), desktop OpenAL level fix, distinct cues, new original cues, small particle accents (reduced-motion aware) | SfxMixTest and desktop AudioMixProbe per `docs/AUDIO_POLISH_REPORT.md` (author's report, not re-run by CI; **no CI has ever run on this branch**) | — | Owner listening review; merge the candidate into it and run CI once | Owner | P | no | after CI |
| 15 | **Known CI failures** | PENDING OWNER DECISION (2) / BY DESIGN (2) | candidate | — | 41030282332: fingerprint, bundled replay (freeze steps); gem midpoint 5.35 m vs 4.5 m, spread 1.067 vs 1.10 (owner) | 41095330974 may add radius-dependent failures (not yet known) | Triage; owner decisions | Owner | G | yes | — |
| 16 | **Determinism, replays, rules version** | NOT STARTED (freeze step, by owner rule) | candidate | Determinism tests exist (KPI 14). Rules bump, fingerprint and bundled replay re-record are deferred to the freeze. | Last full determinism suite: part of CI 41030282332 (passed except fingerprint and replay) | — | At freeze: `RULES_VERSION` 19, fingerprint, re-record replays, CI | A frozen candidate | G | yes | at freeze |
| 17 | **Reporting infrastructure and the 16-KPI suite** | ACTIVE: implemented, never run on a real campaign (section 6) | candidate (`0f28f55`, `702b9bd`, `7b12f25`) | cert_package (PDFs, dashboard, CSV, JSON, charts, ZIP), log scorecard, KPI 7B columns | see section 6 | — | see section 6 | — | R | partly | — |
| 18 | **Nine-run 16-KPI certification campaign** | NOT STARTED (by design) | — | — | — | — | Runs once on the frozen SHA | Freeze | R | — | — |
| 19 | **Gem midpoint outlier** (1 gem of 20 at 5.35 m) | PENDING OWNER DECISION | `e4a2d64` investigation | Cause found: three narrow rests placed, widening refused | — | — | Owner: generator fallback, test bound, or accept | Owner | G | yes (CI) | — |
| 20 | **Run-difficulty spread** (1.067 vs ≥ 1.10, 8 seeds) | PENDING OWNER DECISION | `e4a2d64` | Not significant on 8 seeds (F test) | — | — | Owner: more seeds, accept as noise, or change the statistic | Owner | G (test) | yes (CI) | — |
| 21 | **Older open draft PRs** | LEGACY (no action) | #15 `experiment/pendulums` (rules 18 candidate, frozen, never merged); #11 / #10 generation-recovery prep and audit (based on `build-74-determinism`); #8 OTA reliability; #1 candidate 0.1.0 | Superseded or reference only | — | — | Owner may close #10 / #11 / #8 when convenient | — | — | no | — |
| 22 | **Frozen baselines** | COMPLETE, untouched | `frozen/build-82-baseline` = `ccr-cb458cd1-w4dh5c` = `integration/build-82-repairs` = `623805c`; `demo/build-82` `a5b0223` | — | — | — | — | — | — | — | — |

Other branches found (no active work): `diag/*`, `repair/*`, `feature/generation-recovery`, `test/generation-recovery-prep`,
`docs/generation-recovery-audit`, `base/build-80`, `b77-*`, `exp/*`, `tools/cert-package` (merged into the candidate at `0f28f55`).

---

## 3. Active GitHub Actions, agents and scheduled work

| Item | What it does | Why still running | Expected result | Needed for next candidate | Duplicate of | Verdict |
|---|---|---|---|---|---|---|
| CI 41095330974 (`ci.yml`, PR #16, `72c0a6d`) | Full validation suite on the candidate head | Started 17:02 UTC; full CI takes about 4 h | The 4 known failures, plus possibly tests whose expectations depend on the course layout (the radius changes every course) | Yes: it is the only CI on the head that carries radius 11.5 and the pad fix | none | Keep |
| Difficulty report 41095334120 (solve=1, seeds 1 / 2 / 7322, all modes, 5,000 m, `72c0a6d`) | Solver validation plus KPI 7B and geometry tables at the new defaults | Started 17:02 | 9/9 PROVEN_SOLVABLE expected (the radius alone was 9/9) | Yes until the freeze: it is the only solver evidence with the pad fix | 41089449890 has the radius without the pad fix | Keep |
| Difficulty report 41095339447 (T5Probe `audit` × 6, `72c0a6d`) | Cross-revolution shortcut audit, first 30 slices of 6 courses | Started 17:02 | 0 pad shortcuts (12 before the fix) | Yes: it is the evidence that the pad fix works in real courses | none | Keep |
| Scheduled check-in `trig_0151eWpmshZNFD2UeDGvrJGK` (17:36 UTC) | Reads the three runs above, records them in the geometry report, reports to the owner | One-shot | Results recorded | Yes | — | Keep (it only reads and records) |
| Helper agent: 16-KPI suite review | Read-only review of the KPI tooling for section 6 | Started for this audit | Section 6 | Yes | — | Finishes with this report |
| Other agents / sessions | none (the visual and audio agents finished earlier; no other session is running) | — | — | — | — | — |

Nothing is redundant enough to stop. The earlier geometry runs (41081093114-41084688309, 41089358746, 41089445815, 41089449890,
41089453619) are complete. They are design evidence for the geometry decision, not candidate evidence.

---

## 4. Outstanding bugs and unresolved decisions

**Defects**

- None open in gameplay that are known to invalidate KPI results.
- The pad shortcut gap was the last known generation defect. It is fixed in `72c0a6d`, with validation running.

**Test failures**

- fingerprint and bundled replay: by design until the freeze;
- gem midpoint and difficulty spread: owner decisions;
- plus whatever CI 41095330974 reports for radius-dependent expectations (unknown until it finishes).

**Owner decisions (consolidated in section 8)**

- castle-1 calibration / mode separation;
- gem midpoint;
- spread;
- visual prototype on / off;
- tips wording;
- audio sign-off;
- whether presentation branches join the candidate before or after the KPI campaign.

---

## 5. Geometry decision readiness: radius 11.5

Evidence: geometry report `docs/geometry_audit/GEOMETRY_AUDIT_REPORT.md` and its comparison artifact; solver run 41089449890; running
runs in section 3.

| Area | Finding | Standing |
|---|---|---|
| Solvability | 9/9 courses, 625/625 slices PROVEN_SOLVABLE, 0 integrity violations, 10/10 castles (41089449890, radius only) | **Proven** for the radius; **pending** for `72c0a6d` (41095334120) |
| Fairness / shortcut prevention | Shortcut rules are unchanged by the radius. The pad-gap fix closes the only shortcut class the audit found (12 pad shortcuts in 6 audited courses' first 30 slices). Low-reach launchers: 0 shortcuts under every rule. Knock-up lifts are category C by owner rule. | Fix **committed**; real-course confirmation **pending** (41095339447) |
| Shortcut creation | A wider tower creates no new cross-revolution reach (reach tables are per launcher, not per radius); layer refusals fall 25-30 % because more of the near-threshold placements clear | **Proven** (generation runs) |
| Route length and traversal time | Route elements per 5,000 m +9 % (NORMAL 6,546 to 7,151), +10 % (PUNISHER), +12 % (NGH); revolutions −4 %. Traversal time is not instrumented; expected roughly in proportion to route length (about +10 %). | Route length **proven**; time **provisional** |
| Generator time and retries | Generation-only: NORMAL 170 to 204 s, PUNISHER 291 to 473 s, NGH 593 to 703 s per course (+20 to +63 %; runner noise is large). Modules tried fall 10-15 % and accepted rise 9-10 %, so fewer retries but more placed content and longer routes. | **Proven** (direction); magnitude **provisional** (KPI 13 in the campaign) |
| Mechanic distribution | SAFE placement share rises 6-11 points for every mechanic (pads 40 to 48 % of route decks, ramps 37 to 48 %, NORMAL). Per-castle mechanic tables exist in the comparison artifact (`castle_dead_zone.csv`) and the lane reports (modules.csv) but were not reviewed castle by castle. | Aggregate **proven**; per-castle **not reviewed** |
| Hazard placements | +17-25 % hazards, mortar-row acceptance ×2 in PUNISHER / NGH. As the owner notes, this is flexibility, not a target: the progression controller still decides what to place. | **Proven** |
| PUNISHER and NGH progression | Castle 1 to 10: PUNISHER +20 to +26 %, NGH +8 to +17 % (3 seeds). Castle 1 itself also rises (NORMAL 661 to 722). | **Proven** on 3 seeds; small-sample caution |
| Mode separation | Erosion 71 to 57 % (PUNISHER over NORMAL), 66 to 60 % (NGH over PUNISHER); ordering holds at every castle; still above the 25 % limit | **Proven**; fix needs an owner decision |
| Determinism and replays | Every course changes; the rules 19 bump, fingerprint and bundled replays are already planned at the freeze; per-seed records from older rules show "NOT THESE RULES"; radius is OTA-frozen, so it ships in a full build only | **Owner-approved**; freeze steps **not started** |

The 9.5 m rule was **not** modified. `lowreach` and `measured` are off.

---

## 6. KPI suite readiness and instrumentation gaps

Full per-KPI table with file and line citations: `docs/KPI_SUITE_AUDIT_2026-10-30.md` (read-only review of
`tools/ci/cert_package.py`, the lane-report producers and the workflow). KPI identifiers and definitions are the framework's
(`docs/KPI_FRAMEWORK.md`); none were renamed or added.

### Summary per KPI

**Status logic used by the packager:**
- **Auto** = the packager can PASS or FAIL it on its own.
- **Rule FAIL** = it can FAIL automatically on a rule, and otherwise an analyst decides.
- **Analyst** = measured, but the status is the analyst's call; there is no numeric target in the framework.

| KPI | Measured by the packager | Status logic | Charts / per-castle | Target in the framework | Main gaps |
|---|---|---|---|---|---|
| 1 Run identity | yes | Auto | scorecard / n.a. | one commit, rules version, fingerprint, 9 runs, CI green | fingerprint and CI are typed-in evidence; `RULES_VERSION` still 18 until the freeze |
| 2 Completion and validation | yes | Auto | solver chart / yes | 5,000 m, 10 castles, 100 % proven, 0 violations | "geometry integrity" = integrity.csv only; the T5 shortcut audit and the geometry audit are not inputs |
| 3 Terrain | measurements | Analyst | lane curves / yes | none numeric | no per-mechanic terrain breakdown; route length not read |
| 4 Hazards | measurements | Analyst | density and type mix / yes | none numeric | timing windows, overlaps, exposure columns not read |
| 5 Enemies | measurements | Analyst | density and type mix / yes | none numeric | pursuit, attack, knockback and reach not read |
| 6 Interaction | measurements | Analyst | composition / yes | none numeric | ledger double-count audit not performed |
| 7A Density and pacing | measurements | Analyst | **no chart** / yes | none numeric | no window-distribution or spike chart |
| 7B Progression | yes | Rule FAIL (ordering, erosion ≤ 25 %) | 6 charts / yes | ordering at every castle, erosion ≤ 25 % | ordering on mode means only; erosion on castles 1 and 10 only |
| 7 | derived from 7A + 7B | — | — | — | — |
| 8 Variety | measurements | Analyst | route variety / yes | none numeric | flags never fail; encounter distribution missing |
| 9 Generator quality | measurements | Analyst | modules and refusals / partly | none numeric | "silent substitution" has no measure |
| 10 Solver fairness | yes | Rule FAIL (100 % proven) | solver chart / yes | 100 % PROVEN_SOLVABLE | the shortcut audit is not an input; clean vs collision-assisted proofs not split |
| 11 Avalanche | yes | Rule FAIL (NORMAL castles 9-10, ≤ 2 blocks) | yes / yes | NORMAL rule only | PUNISHER / NGH block counts and frequency not checked |
| 12 Enforcer | yes | Rule FAIL (none in NORMAL) | yes / yes | none in NORMAL | behaviour not split by tier |
| 13 Performance | measurements | Analyst | 2 charts / yes | none numeric | no previous-build comparison; peak heap sampled once per slice |
| 14 Determinism / saves / replays | CI evidence | Auto | none | "within approved tolerances" (undefined) | passes on typed-in CI success alone; replay and save items optional |
| 15 Platforms | release evidence | Auto | none | five platforms | typed-in evidence only |
| 16 Advanced movement | yes | Analyst | utilization / yes | none numeric | "optional" = placed minus main route; reachability of branches not checked |

**Outputs:** every requested output is implemented in code:
- 19 charts (PNG and SVG);
- data CSVs and `package_data.json`;
- `kpi_measurements.json` / `.csv`;
- an offline HTML dashboard;
- executive and technical PDFs plus `TECHNICAL_REPORT.md`;
- an inner and an outer manifest with SHA-256 for every file;
- a ZIP.

It needs only Python 3 and matplotlib, which the workflow installs.

**Has it been tested on real courses? No.** The packager has never run on a real campaign. It was tested on a rules 12 sample and on
synthetic self-test fixtures. The Build 83 validation report's KPI table was made by hand outside the packager.

### Reporting gaps, separate from gameplay

**Category A (would make the campaign untrustworthy or fail to run):**

- **R1.** The packager is unproven on real courses. Cheapest proof, with no new simulation: run the workflow's `consolidate_only` +
  `certify` on the courses of the solve=1 run 41095334120 when it finishes. That is 9 real courses and 3 seeds × 3 modes, and costs one
  packaging job.
- **R2.** `docs/verification/build83/evaluation_overrides.json` is referenced but missing. Dispatch without `--overrides` first (every
  analyst KPI then reads NOT VERIFIED); the analyst evaluation is applied afterwards with a `consolidate_only` re-run.
- **R3.** Pass `seeds ["1","2","7322"]` explicitly; the workflow default is two seeds.
- **R4.** Supply the fingerprint and CI evidence from the full CI of the same SHA.
- **R5.** Keep the default build label (the scorecard step expects it).

**Category C (owner decision):**

- **R6.** Should the shortcut audit (T5Probe) and the geometry audit feed KPI 2 and KPI 10? Recommended: run the campaign with
  `-Dclimb.geomAudit=true`, and run the 6-job shortcut audit on the frozen SHA as attached evidence. The audit is cheap; it cannot run in
  the same dispatch as `certify`.

**Category B (known limits, reported as such, not blockers):**

- no numeric targets for KPIs 3-6, 7A, 8, 9, 13 and 16. These are analyst-judged by the framework's design; no target is invented here.
- no 7A chart;
- the ledger double-count audit is not performed;
- Enforcer behaviour is not split by tier;
- hazard timing and exposure columns are unread;
- no automatic previous-build comparison;
- KPI 14's replay and save items are optional, though the full CI that KPI 14 relies on runs the determinism, replay and save tests.

---

## 7. Category A / B / C and the minimum path to a stable candidate

**A. Must complete before the full KPI audit** (shortest order):

1. **Read the three running validations** (CI 41095330974, solve 41095334120, T5 41095339447). No new runs. If CI reports
   layout-dependent test expectations, update them only where they pin generated output that the radius legitimately changes, never
   weakening an assertion. *About 1-2 h after they finish.*
2. **Owner decisions** on the gem midpoint, the difficulty spread and castle-1 calibration (section 8). If calibration is chosen, implement
   and verify it with one solve=1 run on 3 seeds (*about half a day*). If not, the measured erosion is reported as a known KPI 7B result.
3. **Prove the packager on real courses without new simulation** (section 6 R1): one `consolidate_only` + `certify` job on the courses
   of 41095334120; fix whatever it exposes (R2-R5 are dispatch settings, not code). *About 1-3 h.*
4. **Freeze:** `RULES_VERSION` 19 with its description; regenerate the fingerprint; re-record the bundled replays; one full CI (*about 4-5 h
   wall, mostly CI*). Expected result: 0 failures, or only owner-accepted ones.
5. **Then** the nine-run (3 seeds × 3 modes) 16-KPI campaign on that exact SHA, with `certify=1`.

**B. Independent of, or after, the KPI audit** (presentation only):

- atmosphere prototype;
- tips banner;
- mortar plinth;
- depth-mover posts;
- audio and juice.

They change no generated course and no measured KPI. Integrate them by merging the frozen candidate into each review branch and running CI
once. If they are wanted in the KPI candidate build, merge them before step 4; they then share that one CI and cost nothing extra. Also
category B: closing the legacy PRs.

**C. Owner decision** (section 8).

---

### Evidence reuse: what counts, and what must be re-run

| Evidence | SHA / config | Reusable for |
|---|---|---|
| Geometry comparison runs (41081093114-41084673954, 41089358746) | `f9e6c9b` / `3397a41`, switches | The geometry decision only (done). Not candidate evidence. |
| Solver runs 41089449890 / 41089445815 / 41089453619 | `3397a41` | Shows the radius and rule variants are solvable. Superseded for the candidate by 41095334120. |
| Shortcut audits 41084678527 / 41084683218 / 41084688309 | `3397a41` | Before-fix baseline for the pad gap. |
| CI 41030282332 | `d218fa9` | Superseded by 41095330974. |
| **CI 41095330974, solve 41095334120, T5 41095339447** | **`72c0a6d`** | The current candidate's evidence: read them; do not re-run them. 41095334120's courses also serve the packager dry run (section 6, R1). |
| After the freeze commit | frozen SHA | **Must run:** one full CI, and the nine-course 16-KPI campaign with `certify`, because KPI 1 requires one commit across all evidence. The freeze changes the rules version, fingerprint and replays, not generation, so no extra solver campaign is needed before it. |

No other nine-course campaign is needed before the freeze.

---

## 8. Decisions required from the owner

1. **Radius 11.5:** confirm it stays as the candidate default (approved earlier today; see the conflict note).
2. **Mode separation / castle-1 calibration (KPI 7B):** calibrate PUNISHER and NGH castle 1 toward their targets, or accept the measured
   erosion (57-60 %) as this build's result.
3. **Gem midpoint outlier:** generator fallback, a test bound, or accept.
4. **Run-difficulty spread** 1.067 vs 1.10: more seeds, accept as noise, or change the statistic.
5. **Presentation package:** which of the atmosphere prototype (on / off / drop), the tips banner (wording, top-edge placement, the unused
   `ledge` tip, the 14 s desktop hint), the mortar plinth, the depth-mover cleanup and the audio pass join the candidate, and whether before
   the freeze or after.
6. Whether the shortcut audit and the geometry audit become formal evidence for KPI 2 and KPI 10 (section 6, R6).
7. Optional: close the legacy draft PRs #8, #10 and #11.

---

## 9. GO / NOT READY

**NOT READY to freeze now.** The candidate will be ready to freeze, with no further development, when all of the following hold:

1. CI 41095330974, solve 41095334120 and T5 41095339447 have finished and show no new defect;
2. decisions 2-4 are made;
3. the packager has produced a complete package from real courses (section 6, R1).

The freeze step then makes it GO for the 16-KPI campaign.
