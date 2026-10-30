# Build 83: evaluation against the 16 KPIs

This follows the owner's framework (docs/KPI_FRAMEWORK.md). It uses the nine-course campaign (run 40884899102, commit `c8ef538`), compared with Build 82's 30 courses (run 40752227771, commit `1123a32`). The detailed 47-row scorecard and Reports A–E follow this section.

Mode triples are NORMAL / PUNISHER / NGH. Lane totals are per course.

| # | KPI | Key measurements (Build 82 → Build 83) | Result |
|---|---|---|---|
| 1 | Run identity and reproducibility | Every run records its commit, rules (18), fingerprint `3e795268…`, seeds, mode, tuning and CI run. The bundled seed-12 replay was re-recorded on this commit. Reproducing identical results is checked by the full CI, which is still running | NOT VERIFIED (identity recorded; reproducibility pending CI) |
| 2 | Course completion and validation | 9/9 courses complete to castle 10. 626/626 slices proven. 0 generation failures, 0 geometry violations, 0 incomplete sections | PASS |
| 3 | Terrain difficulty | Terrain lane 5,306 / 5,862 / 6,541 → 5,949 / 6,153 / 6,547 (+12% / +5% / 0%). It starts higher but grows less from castle 1 to 10: +22% / +34% / +20% → +13% / +11% / +10%. Movement variety went up (KPI 16) | FAIL (progression flattened) |
| 4 | Hazard difficulty | Hazard lane 1,342 / 1,663 / 2,009 → 1,307 / 1,544 / 1,885 (−3% / −7% / −6%). PUNISHER hazards are flat from castle 1 to 10 (145 → 147). Hazard variety is up (entropy +5–8%) and spike-block density is down. Avalanches contribute less (KPI 11). Every hazard was avoided by the witness | FAIL (PUNISHER progression; less pressure in every mode) |
| 5 | Enemy difficulty | Enemy lane 558 / 900 / 1,289 → 531 / 865 / 1,096 (−5% / −4% / −15%); NGH castle 9 −27%. Reliance on crabs grew: NGH crabs +16%, skulls −23%, bees −20%, blobs −16%. No unfair encounters (0 witness setbacks) | FAIL (NGH); WATCH (NORMAL, PUNISHER) |
| 6 | Interaction and mitigation | Interaction lane 281 / 356 / 394 → 280 / 349 / 383 (0% / −2% / −3%). Mitigation in Build 83: 50 / 51 / 36. Build 82's mitigation total isn't in its consolidated diagnostics | PASS (interaction); NOT VERIFIED (mitigation against Build 82) |
| 7 | Difficulty density and pacing | **Separation erosion: 88% (PUNISHER over NORMAL) and 29% (NGH over PUNISHER), limit 25%.** Castle-to-castle swing (sd) 25.7 / 23.6 / 18.1 → 44.2 / 29.3 / 13.5. Castle drops 1 / 2 / 1 → 2 / 3 / 3. Longest unchallenged stretch per castle averages 10.5 / 10.3 / 8.4 m (max 14.8 / 19.6 / 14.9 m). Per-castle mean, median, quartiles and sd are in report_full.json and the detailed report | FAIL |
| 8 | Gameplay variety and repetition | Hazard entropy 2.70 / 2.74 / 2.63 → 2.84 / 2.90 / 2.85. Mechanic links +13 / +19 / +20 points. 0 variety flags against the 30-course baseline. "Difficulty rising while variety falls": none found. Unique mechanics per castle are 20.9 / 22.5 / 22.4. Spike blocks are still the top hazard and are mostly stand-ins for saws | PASS (WATCH: spike-block stand-ins, plain-deck share) |
| 9 | Generator quality and efficiency | Tries per course down 20–35%, acceptance up, 0 exhausted attempts. **But the generator substitutes for what it can't place:** only 47% / 27% of planned avalanches (P / NGH) are kept; NGH creature-deck acceptance is 57% → 43% and trap acceptance 55% → 36%; refused threats fall back to crab and saw modules; spike blocks stand in for saws 3–6×; whole-slice discards doubled | FAIL |
| 10 | Solver accuracy and fairness | A physics-consistent, hit-free witness through all 626 slices: 0 failed links, 0 unreachable sections, 0 setbacks. Planner-side refusals are 0.4–3% of attempts. (Modules the generator refused before any proof were never solver-tested) | PASS |
| 11 | Avalanche performance | **Frequency 23.2 / 28.1 → 11.0 / 10.3 a course (P / NGH; −53% / −63%).** Blocks 8.8 / 10.4 per avalanche (target 8–10 / 9–12). About 19 / 22 ricochets per avalanche, 64 / 68 block knocks per course; 80% / 77% of blocks reach the traversal area, 25% / 27% near the climber; 0 witness hits (avoidable). NORMAL 0.3 a course, castles 9–10 only, as designed. Trigger timing, lead and cleanup are covered by unit tests in the pending CI | FAIL (frequency); PASS (physics, fairness) |
| 12 | Enforcer performance | Per course 12.0 / 20.9 → 12.3 / 20.7 (P / NGH); NORMAL 0 (prohibition holds). PUNISHER tiers Yellow 3.7, Orange 6.0, Red 2.7; NGH all Red (20.7). PUNISHER: 15.7 kick tries (2.7 hit), 4.0 swats (3.3 hit), 12.0 retreats, 0.3 falls. NGH: 45 kick tries (13.3 hit), 27 swats (22 hit), 15 retreats, 5 falls. Build 82 behaviour counts aren't in its consolidated summary | PASS (counts, tiers, NORMAL prohibition); NOT VERIFIED (behaviour against Build 82) |
| 13 | Generation and runtime performance | Generation per course 221 / 260 / 302 s → 268 / 551 / 476 s (×1.2 / ×2.1 / ×1.6). The overnight commit was 1,304 / 1,530 s, so the speed-up removed 58–69%. Solver 10 / 16 / 24 → 12 / 24 / 30 s. Peak heap 180 / 209 / 187 → 179 / 183 / 194 MB. The likely expensive operation is the avalanche proofs (two decks back, four arrival times); it hasn't been profiled on CI | FAIL (generation time); PASS (memory) |
| 14 | Determinism, saves and replays | Fingerprint refreshed; seed-12 replay re-recorded (5,002 m, 10 doors, finish step 383,438). The replay, determinism, save and recovery tests all run in the pending full CI | NOT VERIFIED (pending CI) |
| 15 | Release and platform integrity | No packages built: nothing was authorised, and none is needed for this evaluation | NOT VERIFIED |
| 16 | Advanced movement utilization | Links with a mechanic 38% / 19% / 13% → 51% / 39% / 32%. The solver traversed a mechanic in 99.8% / 100% / 99.7% of 25 m windows (Build 82: 99.9 / 93.3 / 84.1%). Pads traversed per course 83 / 51 → 249 / 273. Every placed main-route mechanic was traversed. Plain decks are still 73 / 79 / 83% of take-offs | PASS (WATCH: plain-deck dominance) |

## The four questions

1. **Does it work?** Mostly.
   - KPIs 2 and 10 pass: every course completes and is proven solvable.
   - KPI 9 fails: the generator substitutes for avalanches and enemies it cannot place.
   - KPI 13 fails: generation is slower.
   - KPIs 1 and 14 await the full CI, and KPI 15 was not run.
2. **Is it appropriately difficult?** No.
   - Terrain (3), hazards (4), enemies (5, NGH) and pacing (7) fail. Separation erodes beyond the 25% standard, the ramp is flatter, and NORMAL swings more.
   - Interaction (6) holds.
3. **Is it enjoyable and varied?** Partly.
   - Variety (8), Enforcers (12) and advanced movement (16) pass or improve.
   - The avalanche mechanic (11) shows the intended physics, but at half to a third of the intended frequency.
4. **Is it better than Build 82?** Not overall.
   - **Better:** terrain and movement variety, hazard diversity, spike density, generator acceptance.
   - **Worse:** avalanche frequency, difficulty progression and separation, NGH enemy pressure, generation time.

**Recommendation: NO-GO for Build 83 as the release candidate** (Report E). Build 82 stays the better-balanced candidate.

**How the proposed diagnostics map onto these KPIs** (not started; awaiting the owner):

| Test | KPIs it informs |
|---|---|
| T1 avalanche refusal breakdown | 11, 9 |
| T2 same-seed lineage run (rules 16 / 17 / 18) | 3, 4, 5, 7 |
| T3 NGH enemy attribution | 5, 9 |
| T4 spiral-clearance audit | 9, 8, 16 |
| T5 solver coverage matrix | 10 |


---

# Detailed scorecard and Reports A–E

## What was tested

| Column | Source | Courses | Notes |
|---|---|---|---|
| **Build 82** (frozen baseline) | Run 40752227771 (report rebuilt by 40759608865), commit `1123a32`, rules 16 | 30: seeds 7301–7310 × NORMAL / PUNISHER / NGH, 5,000 m, solver on | Owner-accepted baseline |
| **Build 83 Initial** | Runs 40827920357 (PUNISHER) and 40827923074 (NGH), commit `73287be` | PUNISHER seeds 7301–7303; NGH seed 7301 only; no NORMAL | **Generation only: solver NOT RUN.** Before the 4.7× physics speed-up and the frequency corrections |
| **Build 83 Final** (frozen candidate) | Run 40884899102, commit `c8ef538`, rules 18 | 9: seeds 7321–7323 × 3 modes, 5,000 m, solver on | The nine-course validation the owner directed |

**How to read the comparisons**
- Build 83 Final uses different seeds and fewer courses (3 per mode) than Build 82 (10 per mode). Differences under about 5% are within seed variation.
- Build 83 Initial overlaps Build 82 on seeds 7301–7303, so those rows are same-seed comparisons.
- **Thresholds.** The record holds only one numeric KPI threshold: at most 25% erosion of mode separation from castle 1 to castle 10 (Build 82 GO/NO-GO). The other KPIs have no number, so I judged them against Build 82 as the reference, as the KPI addendum directs. No new thresholds were invented.
- **Statuses:**
  - PASS: verified against the criterion.
  - FAIL: confirmed regression.
  - WATCH: concerning but not established.
  - NOT VERIFIED: the evidence isn't available.
- **Full CI** (run 40884943058 on `c8ef538`) was **still running** when this was written. Its rows say so.

---

## Report A: Build 83 KPI scorecard

Values are per course unless stated; mode triples are NORMAL / PUNISHER / NGH. Δ is Final against Build 82.

### 1. Enemy pressure

| KPI | Threshold | Build 82 | Build 83 Initial | Build 83 Final | Δ | Result |
|---|---|---|---|---|---|---|
| Enemy-lane pressure, NORMAL | Build 82 ref. | 558 | not run | 531 | −4.8% | WATCH |
| Enemy-lane pressure, PUNISHER | Build 82 ref. | 900 | 861 (same seeds as Build 82: −2.8%) | 865 | −3.9% | WATCH (within seed variation) |
| Enemy-lane pressure, NGH | Build 82 ref. | 1,289 (seed 7301: 1,220) | 1,049 (seed 7301: −14%) | 1,096 | **−15.0%** | **FAIL** (now on 3 seeds) |
| NGH castle 9 / castle 10 enemy lane | Build 82 ref. | 154 / 137 | 117 / 113 | 112 / 128 | −27% / −7% | **FAIL** (castle 9) / WATCH (castle 10) |
| Enemies placed (N / P / NGH) | Build 82 ref. | 371 / 597 / 738 | – / 604 / 674 | 364 / 605 / 692 | −2% / +1% / −6% | PASS / PASS / WATCH |

### 2. Enemy variety

| KPI | Threshold | Build 82 | Build 83 Initial | Build 83 Final | Δ | Result |
|---|---|---|---|---|---|---|
| PUNISHER crabs / skulls / bees / blobs | Build 82 ref. | 303 / 121 / 89 / 72 | 345 / 112 / 74 / 60 | 334 / 105 / 88 / 65 | +10% / −13% / −1% / −10% | WATCH (towards crabs) |
| NGH crabs / skulls / bees / blobs | Build 82 ref. | 273 / 204 / 92 / 147 | 303 / 137 / 84 / 128 (1 seed) | 317 / 158 / 74 / 123 | +16% / −23% / −20% / −16% | **FAIL** (the harder creatures are displaced by crabs) |
| Creature-deck acceptance (N / P / NGH) | Build 82 ref. | 62.5% / 55.2% / 56.7% | – / 48.2% / 38.1% | 58.5% / 49.4% / 42.9% | −4 / −6 / −14 pts | WATCH / WATCH / **FAIL** |
| Trap-module acceptance (N / P / NGH) | Build 82 ref. | 62.3% / 57.9% / 54.6% | – / 42.4% / 32.7% | 55.9% / 45.5% / 36.3% | −6 / −12 / −18 pts | WATCH / **FAIL** / **FAIL** |
| Enforcers (P / NGH) | Build 82 ref. | 12.0 / 20.9 | 12.7 / 22.0 | 12.3 / 20.7 | +2% / −1% | PASS |

### 3. Difficulty progression

| KPI | Threshold | Build 82 | Build 83 Initial | Build 83 Final | Δ | Result |
|---|---|---|---|---|---|---|
| Mode ordering at every castle | must hold | yes | not measurable (one mode per run) | yes | – | PASS |
| Separation erosion, PUNISHER over NORMAL | **≤ 25%** | 29.7% | not measurable | **88.4%** | – | **FAIL** |
| Separation erosion, NGH over PUNISHER | **≤ 25%** | 61.4% | not measurable | 29.0% | improved | **FAIL** |
| Ramp castle 1 → 10 (N / P / NGH) | Build 82 ref. | +43% / +35% / +17% | – / +23% / +28% | +34% / +14% / +10% | flatter in every mode | **FAIL** |
| Castle 10 total (N / P / NGH) | Build 82 ref. | 869 / 1,002 / 1,108 | – / 984 / 1,087 | 913 / 936 / 1,023 | +5% / −7% / −8% | PASS / **FAIL** / **FAIL** |
| NGH castle 10 against its provisional 1,210 target | provisional, not absolute | 1,108 | 1,087 | 1,023 | further from it | WATCH |
| Castles below the previous castle (N / P / NGH) | Build 82 ref. | 1 / 2 / 1 | – / 2 / 1 | 2 / 3 / 3 | more | WATCH |
| Castle-to-castle swing, sd of steps (N / P / NGH) | Build 82 ref. | 25.7 / 23.6 / 18.1 | – / 13.4 / 32.1 | **44.2** / 29.3 / 13.5 | +72% / +24% / −25% | **FAIL** / WATCH / PASS |

### 4. Hazard variety

| KPI | Threshold | Build 82 | Build 83 Initial | Build 83 Final | Δ | Result |
|---|---|---|---|---|---|---|
| Hazard-family entropy, bits (N / P / NGH) | Build 82 ref. | 2.70 / 2.74 / 2.63 | – / 2.89 / 2.82 | 2.84 / 2.90 / 2.85 | +5–8% | PASS |
| Spike-block share of hazards | Build 82 ref. | 28.5% / 32.8% / 35.7% | – / 30.0% / 31.5% | 26.9% / 29.4% / 29.4% | −2 to −6 pts | PASS (still the top family: WATCH) |
| Spike blocks per 25 m window, mean / max | Build 82 ref. | 1.33/6, 1.93/13, 2.68/13 | – , 1.58/7, 2.06/8 | 1.21/7, 1.55/6, 1.95/8 | lower | PASS |
| Windows with ≥2 spike blocks | Build 82 ref. | 37.5% / 54.4% / 70.2% | – / 47.1% / 62.0% | 34.7% / 46.6% / 58.2% | lower | PASS |
| Spike blocks offered as stand-ins for unplaceable saws, against those chosen on purpose | none set | not measured in Build 82's tallies | – / 3.8× / 5.7× | 2.9× / 4.4× / 6.1× | – | WATCH (owner observation) |

### 5. Advanced movement

| KPI | Threshold | Build 82 | Build 83 Initial | Build 83 Final | Δ | Result |
|---|---|---|---|---|---|---|
| Route links using a mechanic (N / P / NGH) | Build 82 ref. | 38.0% / 19.4% / 12.6% | – / 38.5% / 34.5% | 51.3% / 38.7% / 32.1% | +13 / +19 / +20 pts | PASS |
| 25 m windows where the solver's witness traversed a mechanic | Build 82 ref. | 99.9% / 93.3% / 84.1% | **NOT RUN** (solver off) | 99.8% / 100% / 99.7% | up | PASS (solver-proven) |
| Witness-traversed pads per course (N / P / NGH) | Build 82 ref. | 83 / 51 / – | NOT RUN | 249 / 273 / – | ×3 / ×5 | PASS |
| Plain-deck take-off share | Build 82 ref. | 80.0% / 89.7% / 93.2% | – / 79.4% / 81.4% | 72.9% / 79.4% / 82.6% | −7 / −10 / −11 pts | PASS (still dominant: WATCH, owner observation) |
| Terrain-lane growth castle 1 → 10 | Build 82 ref. | +22% / +34% / +20% | – / +18% / +27% | +13% / +11% / +10% | flatter | **FAIL** |

### 6. Generator efficiency

| KPI | Threshold | Build 82 | Build 83 Initial | Build 83 Final | Δ | Result |
|---|---|---|---|---|---|---|
| Module tries per course (N / P / NGH) | Build 82 ref. | 10,185 / 15,018 / 16,063 | – / 10,551 / 13,869 (1 seed) | 6,873 / 9,743 / 12,916 | −33% / −35% / −20% | PASS |
| Modules accepted, share of tries | Build 82 ref. | 27.3% / 19.3% / 18.5% | – / 25.2% / 19.1% | 38.8% / 27.3% / 20.6% | up | PASS |
| Discarded later by the whole-slice witness | Build 82 ref. | 2.3% / 4.0% / 4.4% | – / 9.0% / 10.9% | 5.4% / 7.6% / 9.5% | ×2 | WATCH |
| Refused by the spiral-clearance check (pads, vertical movers, falling elevators) | Build 82 ref. | 96–99.5% | 80–89% | 74–86% | −13 to −25 pts | PASS (improved; still the main loss of variety: WATCH, owner observation) |
| Generation failures | 0 | 0 | 0 | 0 | – | PASS |
| Avalanches placed (P / NGH) | about Build 82 (owner) | 23.2 / 28.1 | 9.7 / 7 | **11.0 / 10.3** | −53% / −63% | **FAIL** (primary blocker) |
| Avalanches refused as unfair, per course (P / NGH) | Build 82 ref. | 1.7 / 4 (same seed, owner figures) | 16.3 / 34 | 53 / 113 unfair verdicts; 12.3 / 28.3 dropped | – | **FAIL** |

### 7. Route quality

| KPI | Threshold | Build 82 | Build 83 Initial | Build 83 Final | Δ | Result |
|---|---|---|---|---|---|---|
| Branching: fork-module share of route links | Build 82 ref. | 6.7% / 13.3% / 12.6% | – / 14.5% / 14.2% | 7.2% / 14.3% / 14.1% | +0.5 to +1.5 pts | PASS |
| Route travel per castle interval, m | Build 82 ref. | 2,968 / 3,285 / 3,529 | – / 3,135 / 3,223 | 2,925 / 3,118 / 3,254 | −1% / −5% / −8% | WATCH |
| Route links per castle interval | Build 82 ref. | 554 / 627 / 667 | – / 553 / 560 | 525 / 552 / 571 | −5% / −12% / −14% | WATCH |
| Variety flags against the 30-course baseline | 0 | – | not compared | 0 | – | PASS |
| Repetition: longest same-signature run per castle | Build 82 ref. | not in Build 82's per-castle comparison (different seeds) | – | 8.6 / 11.8 / 11.6 links | – | NOT VERIFIED (against Build 82) |

### 8. Reliability

| KPI | Threshold | Build 82 | Build 83 Initial | Build 83 Final | Δ | Result |
|---|---|---|---|---|---|---|
| Slices proven solvable | all | 2,096 / 2,096 | **NOT RUN** | 626 / 626 | – | PASS |
| Generation failures / unknown or unsolvable verdicts / integrity violations | 0 | 0 / 0 / 0 | 0 / – / 0 | 0 / 0 / 0 | – | PASS |
| Witness setbacks (fair encounters, including avalanche hits) | 0 | 0 | NOT RUN | 0 | – | PASS |
| Determinism, bundled replay, regression suite (full CI) | green | green (1123a32) | – | running | – | NOT VERIFIED (pending) |
| Generation time, s per course (P / NGH) | Build 82 ref. | 260 / 302 (seed 7301: 153 / 355) | 1,304 / 1,530 | 551 / 476 | ×2.1 / ×1.6 | **FAIL** (much better than Initial: −58% / −69%) |
| Solver time, s per course (N / P / NGH) | Build 82 ref. | 10 / 16 / 24 | NOT RUN | 12 / 24 / 30 | +20–50% | WATCH |
| Peak heap, MB | Build 82 ref. | 180 / 209 / 187 | – / 190 / 182 | 179 / 183 / 194 | ±4% | PASS |


---

## Report B: enemy pressure and variety

**Was the original 12% PUNISHER reduction a real regression?** No: it was seed variation.
- The 12% came from comparing one seed with Build 82's ten-course average.
- On the same three seeds as Build 82 (Initial), PUNISHER was −2.8%. On fresh seeds (Final) it is −3.9%.
- The PUNISHER enemy count is +1%.
- The limited correction allowance for enemies was therefore not used (Report D).

**NGH is a real reduction.**
- Enemy pressure is −15.0% across three seeds. Castle 9 is −27% and castle 10 is −7%.
- Skulls, bees and blobs are all down (−23%, −20%, −16%), while crabs are up 16%.
- Creature-deck acceptance fell from 57% to 43% and trap acceptance from 55% to 36%.

**Likely cause (inferred, not proven).** The new avalanche proofs run from two decks before the trigger, for four arrival times. They also prove that whole stretch with every hazard live. That makes it harder for creature decks and traps nearby to pass, and NGH has the most avalanches planned (38.7 a course). The crab rise fits the generator's fallback order: a refused threat module tries the trap module next, and the trap module builds crabs (Report C). Proving this would need a diagnostic run.

**Does castle 10 hold?**
- NORMAL: yes (76 against 82).
- PUNISHER: about (112 against 115).
- NGH: 128 against 137, but castle 9 sags to 112.

Castle by castle. Enemy pressure is the enemy lane (castle sum of adjusted 25 m windows). Counts and acceptance are per course. Initial is generation-only, before the corrections.

#### NORMAL

| Castle | Enemy pressure: B82 | Initial | Final | Δ Final vs B82 | Enemies placed: B82 / Final | Final crabs / skulls / bees / blobs | B82 crabs / skulls / bees / blobs | Creature decks accepted: B82 / Final | Trap modules accepted: B82 / Final |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 32 | n/a | 20 | -38% | 21 / 16 | 12 / 2 / 2 / 0 | 12 / 4 / 4 / 1 | 60% / 40% | 63% / 60% |
| 2 | 41 | n/a | 40 | -2% | 28 / 26 | 14 / 5 / 5 / 1 | 18 / 4 / 5 / 1 | 65% / 64% | 54% / 59% |
| 3 | 31 | n/a | 42 | +35% | 20 / 27 | 16 / 5 / 5 / 1 | 11 / 4 / 4 / 1 | 70% / 63% | 67% / 56% |
| 4 | 44 | n/a | 40 | -8% | 30 / 26 | 14 / 4 / 5 / 3 | 19 / 5 / 3 / 2 | 61% / 78% | 73% / 70% |
| 5 | 60 | n/a | 51 | -15% | 40 / 36 | 24 / 5 / 4 / 3 | 22 / 8 / 7 / 2 | 64% / 47% | 64% / 57% |
| 6 | 63 | n/a | 61 | -3% | 43 / 48 | 34 / 6 / 5 / 3 | 25 / 8 / 7 / 2 | 63% / 51% | 60% / 49% |
| 7 | 62 | n/a | 57 | -9% | 41 / 35 | 19 / 8 / 3 / 5 | 24 / 8 / 5 / 4 | 67% / 59% | 61% / 42% |
| 8 | 66 | n/a | 69 | +4% | 45 / 47 | 28 / 9 / 6 / 4 | 27 / 9 / 6 / 4 | 57% / 60% | 62% / 61% |
| 9 | 77 | n/a | 75 | -2% | 48 / 54 | 31 / 7 / 10 / 6 | 25 / 10 / 8 / 6 | 66% / 62% | 66% / 49% |
| 10 | 82 | n/a | 76 | -8% | 56 / 49 | 25 / 10 / 5 / 9 | 30 / 10 / 8 / 8 | 59% / 59% | 58% / 64% |
| **Total** | **558** | **n/a** | **531** | **-4.8%** | **371 / 364** | | | | |

#### PUNISHER

| Castle | Enemy pressure: B82 | Initial | Final | Δ Final vs B82 | Enemies placed: B82 / Final | Final crabs / skulls / bees / blobs / Enforcers | B82 crabs / skulls / bees / blobs / Enforcers | Creature decks accepted: B82 / Final | Trap modules accepted: B82 / Final |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 72 | 66 | 73 | +1% | 54 / 56 | 35 / 6 / 11 / 3 / 1.0 | 30 / 7 / 14 / 2 / 1.1 | 61% / 43% | 61% / 46% |
| 2 | 69 | 66 | 48 | -30% | 48 / 38 | 26 / 5 / 4 / 1 / 1.0 | 27 / 10 / 8 / 3 / 0.9 | 62% / 37% | 62% / 43% |
| 3 | 63 | 77 | 73 | +16% | 40 / 41 | 16 / 11 / 9 / 4 / 1.3 | 20 / 9 / 5 / 4 / 1.0 | 66% / 74% | 70% / 60% |
| 4 | 70 | 62 | 66 | -5% | 44 / 40 | 19 / 9 / 5 / 6 / 1.3 | 22 / 10 / 7 / 4 / 1.3 | 66% / 61% | 66% / 56% |
| 5 | 94 | 100 | 97 | +3% | 63 / 69 | 38 / 12 / 12 / 5 / 1.0 | 32 / 12 / 10 / 8 / 1.3 | 55% / 54% | 65% / 48% |
| 6 | 94 | 91 | 93 | -0% | 63 / 67 | 39 / 12 / 8 / 7 / 1.0 | 33 / 13 / 8 / 8 / 1.2 | 63% / 52% | 61% / 44% |
| 7 | 102 | 96 | 100 | -2% | 68 / 69 | 36 / 12 / 11 / 8 / 1.3 | 36 / 13 / 9 / 9 / 1.2 | 49% / 50% | 49% / 45% |
| 8 | 107 | 94 | 101 | -6% | 69 / 73 | 40 / 10 / 11 / 10 / 1.3 | 34 / 14 / 10 / 10 / 1.3 | 44% / 47% | 46% / 45% |
| 9 | 114 | 94 | 101 | -12% | 69 / 74 | 44 / 11 / 8 / 10 / 1.7 | 31 / 17 / 7 / 12 / 1.5 | 56% / 43% | 54% / 51% |
| 10 | 115 | 115 | 112 | -3% | 78 / 78 | 40 / 16 / 9 / 12 / 1.3 | 38 / 17 / 10 / 13 / 1.2 | 54% / 46% | 56% / 32% |
| **Total** | **900** | **861** | **865** | **-3.9%** | **597 / 605** | | | | |

#### NGH

| Castle | Enemy pressure: B82 | Initial (seed 7301 only) | Final | Δ Final vs B82 | Enemies placed: B82 / Final | Final crabs / skulls / bees / blobs / Enforcers | B82 crabs / skulls / bees / blobs / Enforcers | Creature decks accepted: B82 / Final | Trap modules accepted: B82 / Final |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 121 | 109 | 107 | -12% | 72 / 75 | 40 / 14 / 10 / 9 / 2.0 | 29 / 19 / 11 / 10 / 2.0 | 57% / 38% | 49% / 34% |
| 2 | 110 | 95 | 98 | -11% | 60 / 64 | 31 / 14 / 7 / 10 / 2.0 | 20 / 17 / 10 / 11 / 2.1 | 58% / 41% | 59% / 34% |
| 3 | 102 | 56 | 96 | -6% | 55 / 59 | 24 / 12 / 12 / 9 / 1.7 | 18 / 16 / 8 / 11 / 2.0 | 65% / 52% | 61% / 42% |
| 4 | 101 | 95 | 101 | -0% | 54 / 54 | 15 / 16 / 7 / 14 / 1.7 | 18 / 16 / 5 / 13 / 1.7 | 66% / 57% | 64% / 42% |
| 5 | 131 | 111 | 106 | -18% | 75 / 73 | 40 / 15 / 5 / 10 / 2.0 | 27 / 21 / 9 / 16 / 2.0 | 57% / 44% | 54% / 45% |
| 6 | 135 | 108 | 119 | -12% | 80 / 72 | 28 / 16 / 9 / 16 / 2.0 | 31 / 19 / 11 / 17 / 2.3 | 52% / 33% | 54% / 30% |
| 7 | 146 | 142 | 117 | -20% | 85 / 72 | 31 / 17 / 7 / 14 / 2.0 | 34 / 23 / 9 / 17 / 2.0 | 53% / 45% | 58% / 50% |
| 8 | 152 | 103 | 112 | -26% | 86 / 72 | 35 / 16 / 5 / 13 / 2.7 | 32 / 25 / 8 / 18 / 2.3 | 56% / 39% | 49% / 27% |
| 9 | 154 | 117 | 112 | -27% | 84 / 68 | 29 / 18 / 7 / 11 / 2.3 | 27 / 24 / 11 / 19 / 2.1 | 57% / 42% | 53% / 33% |
| 10 | 137 | 113 | 128 | -7% | 87 / 85 | 43 / 21 / 3 / 16 / 2.3 | 36 / 23 / 9 / 16 / 2.4 | 53% / 48% | 54% / 37% |
| **Total** | **1289** | **1049** | **1096** | **-15.0%** | **738 / 692** | | | | |

---

## Report C: generation and gameplay quality

**Worse than Build 82 (highlighted):**
- **Avalanche frequency.** PUNISHER places 11.0 avalanches a course (Build 82: 23.2) and NGH 10.3 (28.1).
  - PUNISHER still plans 23.3 a course, but only 47% are proven fair and kept; NGH keeps 27%.
  - The remaining refusals are "no plan" two decks past the trigger while 8–9 blocks are still moving. About a third of refused spots cannot be proven even with no avalanche.
  - The per-seed counts are PUNISHER 11 / 12 / 10 and NGH 10 / 13 / 8, so the shortfall is consistent.
- **Difficulty curve.**
  - PUNISHER is only 2.5% above NORMAL at castle 10.
  - The ramp is flatter in every mode, and terrain barely rises (+10–13%).
  - NORMAL swings far more from castle to castle (owner Observation 1, confirmed): castle 4 +82, castle 5 −45, castle 6 +81, castle 7 −35.
- **NGH enemy pressure and acceptance** (Report B).
- **Generation time.** PUNISHER takes 2.1× as long as Build 82 and NGH 1.6×. The spread across seeds is large: PUNISHER ranged 282–886 s.
- **Whole-slice witness discards** have doubled (5–10% of accepted modules).
- **Route length per castle** is 5–14% fewer links in PUNISHER and NGH.

**Better than Build 82:**
- Traversal variety: route links with a mechanic are up 13–20 points. The solver's witness traversed mechanics in 99.7–100% of windows, and traversed 3–5× as many pads.
- Hazard entropy is up.
- Spike-block density is down.
- Forks are up slightly.
- Every slice is proven solvable, and there are zero witness setbacks (fairness).

**Owner observations, answered from the data:**
- **Crabs, spikes and saws as fallbacks:** confirmed. When a module is refused, the generator tries the other threat module (saws ↔ traps), then other challenges, then plain travel. Plain hops and stairs are also swapped for saws or traps as the climb rises. Spike blocks are mostly stand-ins for gap saws that can't be placed fairly (3–6× the deliberate picks).
- **"Almost no terrain variety":** 73–83% of take-offs are still plain decks. The main cause is the generator's spiral-clearance check. It refuses 74–86% of pads, vertical movers and falling elevators before the solver ever sees them. Build 82 refused 96–99.5% of them, which is why Build 83 already shows more terrain mechanics.
- **Generator or solver?** Mostly the generator. The solver's own refusals are 0.4–3% of attempts. Its share is the whole-slice discards and the avalanche proofs.

**Determinism:** NOT VERIFIED until the full CI finishes. Focused determinism and avalanche tests passed locally before the freeze. Under the owner's instruction, local results are not counted.

---

## Report D: correction log

| # | Authority | Problem and evidence | Root cause | Change | Tests | Before → after | Outcome |
|---|---|---|---|---|---|---|---|
| D1 | Overnight addendum (measure the optimised commit) | Generation 6.4× (PUNISHER) and 4.3× (NGH) slower than Build 82 on 73287be (991 s against 153 s; 1,530 s against 355 s for seed 7301) | The rigid-block physics looked up nearby decks and their geometry inside every sub-step | `2f1c77f`: nearby decks and their geometry looked up once per step, out-of-reach decks skipped. Generated courses unchanged | AvalancheOverhaulTest (cost per step), the generation fingerprint | 82 → 9.8 µs per step; on CI PUNISHER 1,304 → 551 s, NGH 1,530 → 476 s | Partial: still ×2.1 / ×1.6 against Build 82 (FAIL) |
| D2 | Frequency addendum (narrow corrections) | PUNISHER 9.7 and NGH 7 avalanches against Build 82's 22.7 and 31 | Rigid blocks never came to rest, so the proof found no clean move off the trigger deck | `74ad1b8`: blocks on slow deck contact settle (route clear in 5–7 s, violence kept) | 18 focused avalanche tests, local 1,200 m measurements | PUNISHER 9.7 → 11.0; NGH 7 → 10.3 | Partial: FAIL against Build 82 |
| D3 | Frequency addendum | As D2 | The planner tried no waits beside a live avalanche | `74ad1b8`: 24 waits 0.45 s apart and a longer trial when an avalanche is live (shared by proof and solver) | As D2 | Included in D2's after-figures | Partial |
| D4 | Frequency addendum | NGH still low (2 placed, 6 dropped to 1,200 m locally) | Hypothesis: the climber on the trigger deck needed to retreat first | The proof tried the solver's other standing spots | Local 1,200 m, NGH and PUNISHER | Counts identical | **Reverted** before the freeze; no effect |
| D5 | KPI addendum (one minor correction for enemies) | PUNISHER −12% enemy pressure | Seed variation (−2.8% on the same seeds) | **None: allowance unused** | – | – | Not needed for PUNISHER. The NGH −15% only became established in the final campaign; the owner's directive now forbids further adjustment |

The follow-up validation for D1–D3 is the Final campaign (run 40884899102). No change was made after the freeze (`c8ef538`). The commits since then are documentation only.

---

## Report E: Build 83 readiness

**Exact commit tested:** `c8ef53843cb6b05e9248e44017aa6db7a6f827e6` (`experiment/pendulums`, rules 18, fingerprint `3e79526889137dc34e1edeb9`).

**Final test results:**
- Campaign: 9/9 courses complete and 626/626 slices proven solvable, with 0 failures, 0 integrity violations and 0 witness setbacks.
- Full CI: **pending** (run 40884943058).

**Passed:**
- Reliability.
- Mode ordering.
- Enforcers.
- PUNISHER enemy count.
- Hazard entropy and spike density.
- Advanced movement and traversal variety (solver-proven).
- Branching.
- Generator acceptance and failures.
- Heap.

**Failed:**
- Avalanche frequency (PUNISHER −53%, NGH −63%), which was the primary goal.
- PUNISHER-over-NORMAL separation (88% erosion) and NGH-over-PUNISHER separation (29%).
- The ramp in every mode; PUNISHER and NGH castle 10.
- NORMAL castle-to-castle swing.
- NGH enemy pressure (−15%; castle 9 −27%) and the NGH enemy mix.
- PUNISHER and NGH trap acceptance; NGH creature acceptance.
- Terrain-lane growth.
- Generation time.

**Remaining concerns:**
- Plain-deck dominance and spike blocks as stand-ins.
- The spiral-clearance check refusing most vertical and moving modules.
- Whole-slice discards doubled.
- Determinism unconfirmed until CI finishes.
- Real-device feel of avalanches with no warning not tested.

**Recommendation: NO-GO for Build 83 as the release candidate.**
- The avalanche overhaul's physics, trigger and fairness work as intended, and every course is solvable.
- But its primary success criterion (about Build 82's frequency) failed. Difficulty separation and the ramp are worse than Build 82, and NGH enemy pressure dropped.
- Build 82 remains the better-balanced release candidate.
- A CONDITIONAL GO would need the frequency shortfall and the separation and ramp regressions resolved and re-validated. That is new development, for the owner to decide; it is not authorised here.

**Not verified:**
- Full CI and determinism (pending).
- Real-device play.
- Whether the spiral-clearance check is stricter than necessary.
- Whether the solver could play the modules refused before any proof.
- Whether the discarded slices are unfair or merely unsolved.
- The cause of the NGH enemy drop (inferred only).

---

## Files

- `kpi_scorecard.csv`: Report A as a table.
- `kpi_data.json`: chart inputs and sources.
- `castle_tables.json`: Report B's castle data for Build 82 and Build 83 Final.
- `Build83_vs_Build82_charts.pdf` and `01–08_*.png`: comparison charts.
- The campaign's own package: artifact `difficulty-baseline-3lane` of run 40884899102, including the 148-page detailed report.
