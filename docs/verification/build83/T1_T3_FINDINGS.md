# Build 83 diagnostics T1–T3: results (2026-10-29)

These runs measure only; no gameplay or generator behaviour was changed. Every run used seeds 7321–7323 and climbed to 5,000 m, and all of them passed their validation gate.

| Test | Run | Code | Setting |
|---|---|---|---|
| T2 rules 16 (Build 82 game code) | 40902920698 | `diag/build-82-measure` (`30b0e44`) | All modes, solver on |
| T2 rules 17 (the pendulum experiment) | 40902926090 | `diag/rules17-lineage` = `e50fbfe` | All modes, solver on |
| T2 rules 18 (frozen Build 83) | 40884899102 | `c8ef538` | The nine-course campaign |
| T1 avalanche refusal breakdown | 40902931651 | `diag/build-83-causes` (`9d4988e`) | PUNISHER and NGH, generation only, `-Dclimb.diagAvl=true` |
| T3 NGH enemy attribution | 40902937372 | `diag/build-83-causes` | PUNISHER and NGH, generation only, avalanche proof from one deck before the trigger |

**T3 setting in effect:** its courses differ from T1 (for example 8.3 against 11.0 PUNISHER avalanches), so the one-deck setting was applied.

**Control check: PASS.** T1 generated exactly the same courses as the frozen campaign: the same slices, the same 11.0 and 10.3 avalanches per course, and identical lane totals. So the diagnostic counts are observe-only, and generation does not depend on whether the solver runs.

## Rules 17 is more than pendulums

The experiment branch's rules 17 also contains:
- **Capability-aware layer spacing**: launcher spacing is measured only from the lower platform; skull and spike-drop decks keep 9.5 m.
- **A re-draw near Enforcers**: a bee or crab deck barred near an Enforcer is drawn again as another hazard; a barred bee becomes a vertical saw or a cannon.

These apply in every mode, including NORMAL.

## T2: which change caused what (same seeds, per course)

| Measure | Mode | Rules 16 | Rules 17 | Rules 18 | Caused by |
|---|---|---|---|---|---|
| **Avalanches placed** | PUNISHER | 22.0 | 19.3 | **11.0** | Mostly **rules 18** (−43%); rules 17 −12% |
| | NGH | 29.0 | 23.7 | **10.3** | Mostly **rules 18** (−57%); rules 17 −18% |
| **Castle 1 → 10 ramp** | NORMAL | +46% | +34% | +34% | **Rules 17 alone** (rules 17 and 18 are identical for NORMAL) |
| | PUNISHER | +40% | +21% | +13% | Both: rules 17 first, then rules 18 |
| | NGH | +19% | +9% | +10% | **Rules 17** |
| **Castle-to-castle swing (sd)** | NORMAL | 28.1 | 44.2 | 44.2 | **Rules 17 alone** (owner Observation 1) |
| | PUNISHER | 24.1 | 33.6 | 29.3 | Rules 17 |
| | NGH | 34.8 | 54.9 | 13.5 | Rules 17 raised it; rules 18 smoothed it |
| **Separation erosion, PUNISHER over NORMAL** (limit 25%) | – | 25% | 60% | 88% | Both |
| **Separation erosion, NGH over PUNISHER** | – | 74% | 77% | 30% | Already failing on Build 82's code; rules 18 improved it (NGH castle 10 held while PUNISHER's fell) |
| **Enemy lane** | NGH | 1,302 | 1,111 | 1,096 | **Rules 17** (−15%) |
| | PUNISHER | 898 | 864 | 865 | Rules 17 (−4%) |
| **Creature-deck acceptance** | NGH | 59.6% | 49.6% | 42.9% | Rules 17 (−10 pts), then rules 18 (−7 pts) |
| **Trap-module acceptance** | NGH | 57.3% | 39.0% | 36.3% | Mostly rules 17 |
| | PUNISHER | 56.6% | 49.1% | 45.5% | Both |
| **Plain-deck take-off share** | NORMAL | 80.2% | 72.9% | 72.9% | **Rules 17 improved it** |
| | PUNISHER | 89.4% | 79.6% | 79.4% | Rules 17 |
| | NGH | 93.5% | 82.7% | 82.6% | Rules 17 |
| **Generation time, s per course** | NORMAL / PUNISHER / NGH | 161 / 270 / 385 | 286 / 283 / 437 | 268 / 551 / 476 | NORMAL: rules 17. PUNISHER: **rules 18** (avalanche proofs) |
| **Decks refused inside an earlier hazard's lethal box** (sum over courses) | PUNISHER / NGH | 0 / 0 | 0 / 1,817 | **7,864 / 21,343** | **Rules 18**: the rigid avalanches' larger fall zones |

Courses complete with every slice proven at every point in the lineage; there were no failures in rules 16, 17 or 18.

## T1: why avalanche spots are refused (rules 18; per course)

| Measure | PUNISHER | NGH |
|---|---|---|
| Unfair verdicts (proof attempts failed) | 53 | 113 |
| Of the failed main proofs, re-proved **without** the avalanche | 66% pass (the avalanche is the cause), 34% fail anyway (the stretch was never provable) | 62% pass, 38% fail anyway |
| Leading reason | "No move found" **off the trigger deck** (link 2 from the proof start) while **8–9 blocks are still falling and none has settled**: about 23 a course | The same: about 42 a course |
| Climber knocked off the route before reaching the trigger deck | 6 | 16 |
| Failed only at a later arrival delay (0.7 / 1.6 / 2.9 s) | 5 / 3 / 1 | 8 / 8 / 6 |
| Refused at insertion (geometry check) | 8 | 18 |
| Candidate trigger deck taken by something else before the proof (a wasted try) | 17 | 41 |
| Dropped after all tries | 12.3 | 28.3 |

**What this means.** The spot itself is the problem, not the proof window. The momentum trigger fires as the climber approaches, so the climber reaches the trigger deck while all 8–12 blocks are coming down, and from there no move is clean. About a third of the candidate spots could never be proven even without an avalanche, and many tries are lost to decks that something else claimed first. Each of those wastes one of the four tries.

## T3: is the two-deck proof window behind the enemy drop? No

| Measure (per course) | Rules 18 (proof from 2 decks back) | T3 (from 1 deck back) |
|---|---|---|
| Avalanches placed (PUNISHER / NGH) | 11.0 / 10.3 | **8.3 / 9.3** (fewer) |
| Creature-deck acceptance (PUNISHER / NGH) | 49.4% / 42.9% | 47.8% / 45.4% |
| Trap acceptance (PUNISHER / NGH) | 45.5% / 36.3% | 42.4% / 34.5% |
| NGH enemy lane | 1,096 | 1,121 |

Starting the proof closer to the trigger makes avalanche frequency worse and does not recover enemy placement. The hypothesis is **rejected**.

The NGH enemy drop comes mainly from **rules 17** (the Enforcer-adjacent re-draw and layer spacing). The extra rules 18 loss lines up with the 12× rise in decks refused inside an avalanche's lethal box.

## Effect on the 16 KPIs

| KPI | What the diagnostics establish |
|---|---|
| 3 Terrain, 7 Pacing (NORMAL) | NORMAL's flatter ramp, castle-to-castle spikes and higher castle 1 all come from **rules 17**. The avalanche overhaul does not affect NORMAL |
| 5 Enemies, 9 Generator (NGH) | The −15% NGH enemy lane is **rules 17**. Rules 18 adds a smaller acceptance loss, probably from avalanche fall zones refusing decks |
| 7 Separation | PUNISHER over NORMAL eroded by both changes (25% → 60% → 88%). NGH over PUNISHER already failed on Build 82's code with these seeds (74%), and rules 18 improved it |
| 8 Variety, 16 Movement | The terrain-variety gain (plain decks −7 to −11 points) comes from **rules 17's** layer spacing |
| 11 Avalanches, 9 Generator | The frequency loss is **rules 18**: spots where the trigger deck has no escape while the blocks fall, unprovable stretches, and wasted tries |
| 13 Performance | The PUNISHER slowdown is **rules 18** (avalanche proofs); the NORMAL slowdown is rules 17 |

## Possible corrections (none started; each needs owner approval)

1. **Avalanche spots (KPI 11):**
   - Choose trigger decks that leave an escape while blocks fall, for example a wider top, or a deck before it the climber can hold on.
   - Before trying a spot, check that the stretch is provable at all without an avalanche (about a third aren't), so no try is wasted.
   - Stop counting a try when the candidate deck was claimed by something else.

   The proof start stays at two decks (T3), and block counts and physics stay as they are.
2. **Fall-zone refusals (KPI 9, 5):** check whether the avalanche's lethal box for later decks is drawn larger than where blocks actually fall, using the recorded block paths. Fix it only if it is.
3. **Rules 17 trade-off (KPIs 3, 5, 7, 8, 16):** it gave the terrain-variety gain but caused the NORMAL spikes, the flatter ramps and the NGH enemy drop.
   - Option A: keep it and correct the curve and the re-draw.
   - Option B: separate it and re-measure its parts (layer spacing, Enforcer re-draw, pendulums) one by one.
