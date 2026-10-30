# Upwardly Build 83: vertical geometry, active corridor and buildable dead-zone audit

Owner directive 2026-10-30 ("Master vertical geometry, active corridor and buildable dead-zone audit"). Branch `audit/geometry`.
This is an audit, not certification. No default changed, nothing was merged, published or uploaded, and Build 82, the demo and the
c8ef538 candidate are untouched. Every experiment is behind a switch that is off by default.

## 0. The answer in one page

**What limits generation is not the height between layers alone. It is how often a module lands close to the threshold of a cross-revolution
rule.** Most refused modules miss a rule by a quarter of a metre or less, so small geometric changes move many of them across the line.

| Question | Finding |
|---|---|
| How far apart are the spiral layers? | Median 11.3 m (NORMAL), 10.7 m (PUNISHER), 10.3 m (NGH) from a route deck to the deck one revolution above; p5 is 6.3-7.0 m. |
| How much of that is really used? | The climber's body and reach take the lower ~5.6 m (NORMAL) to ~4.7 m (NGH). The median **buildable dead zone** above that is 4.7 m (NORMAL), 4.1 m (PUNISHER), 3.8 m (NGH), and under 0.6 m at p5. |
| What is the 9.5 m launcher rule? | A blanket: inside a 2.5 m edge-to-edge arc window, any platform 4-9.5 m above a pad, spring, seesaw, ramp, swing or any mover is refused. It is the original "no trampoline shortcuts" rule. |
| Is 9.5 m conservative? | **Yes for most launchers, no for pads.** Measured reach: sideways/depth movers and seesaws 3.75 m, ramps 4.75 m, swings 6.75 m, springs 8.5 m, elevators amplitude + 3.75 m, **pads 10.25 m** (above 9.5 m). |
| Is removing it safe? | **Not for pads and springs.** With the blanket waived for every launcher, the shortcut audit found new pad and spring shortcuts that the measured-reach rule (C1) misses. With the blanket waived only below the low-reach launchers (`lowreach`), the audit found **no** new shortcut from any of them. |
| What does each option buy? | Rule changes alone buy little: +3 to +9 % hazards. **A wider tower buys the most**: radius 11.5 m gives +17 to +25 % hazards, doubles mortar-row acceptance in PUNISHER and NGH, and lifts the castle 1 to 10 growth to +37 / +26 / +17 % (from +35 / +20 / +8 %). |
| What does it cost? | Radius 11.5 m: the tower is 15 % wider (the curve reads slightly flatter), the route is ~10 % longer for the same 5,000 m, generation takes 20-60 % longer, and every course changes (rules bump, fingerprint, replays re-recorded; radius is frozen for over-the-air updates, so it needs a full build). |
| Does anything fix mode separation (KPI 7B)? | No option reaches the 25 % erosion limit. Radius 11.5 m improves it (erosion 71 % -> 57 % for PUNISHER over NORMAL, 66 % -> 60 % for NGH over PUNISHER); the rule-only options make it worse or break the ordering at castle 10. |
| Pre-existing defects found | (1) Pads reach decks 9.5-10.3 m up that neither the blanket (stops at 9.5 m) nor C1 (interpolation at small arc gaps) refuses: 12 such shortcuts in 6 audited courses (first 30 slices). (2) The solver can climb 6-8 m from a plain deck with a hazard's knock-up (mortar, bee); the independent search almost never can. Both exist today, under every option. |

**Recommendation: Option C (radius 11.5 m) together with the C1 pad-gap fix**, pending the solver validation runs listed in section 11 and
the owner's view of the wider tower (screenshots in section 9). Keep the 9.5 m blanket as it is for now; `lowreach` is a safe, smaller
follow-up if more hazard room is still wanted after the radius change. Details and the alternatives are in section 12.

---

## 1. What ran

All runs are on `audit/geometry` and generation-only unless stated: seeds 1, 2 and 7322; NORMAL, PUNISHER and NGH; 5,000 m; 10 castles.
`-Dclimb.geomAudit=true` adds the geometry tables and changes nothing else (the baseline's KPI 7B numbers equal run 41053445468's to the
decimal).

| Label | Run | Commit | Switches |
|---|---|---|---|
| baseline | 41081093114 | f9e6c9b | none |
| r11.5 | 41081097861 | f9e6c9b | `-Dclimb.radius=11.5` (circumference 62.8 -> 72.3 m) |
| r13 | 41081102740 | f9e6c9b | `-Dclimb.radius=13` (81.7 m) |
| measured | 41081107092 | f9e6c9b | `-Dclimb.layerRule=measured` (no 9.5 m blanket; C1, bodies and the plain 4 m still apply) |
| r11.5+measured | 41081111661 | f9e6c9b | both |
| lowreach | 41084673954 | 3397a41 | `-Dclimb.layerRule=lowreach` (blanket waived only below sideways/depth movers, seesaws, swings, non-turbo ramps) |
| comparison package | 41089358746 | 3397a41 | `geometry_compare` of the six: charts, CSV, JSON ([artifact](https://github.com/verbal76/Climb-Up/actions/runs/41089358746/artifacts/12993760637)) |
| shortcut audits | 41084678527 / 41084683218 / 41084688309 | 3397a41 | T5Probe `audit` (first 30 slices, every cross-revolution platform pair within 11 m up and 10 m along, tested on the real Sim by an independent input search and by the solver), default / measured / lowreach |
| solver validation | 41089445815 / 41089449890 / 41089453619 | 3397a41 | `solve=1`: lowreach, r11.5, r11.5 + lowreach (section 11) |

Source inventory (Stage A): `SOURCE_INVENTORY.md` in this folder, with file and line references for every constant used below.
Repository-side tables: `candidate_comparison.csv` and `candidate_comparison.png` (this folder), built from the run logs.

---

## 2. Layer separation against physical clearance (baseline)

Medians per course, p5-p95 in brackets, metres. Samples every 1 m of route arc; "above" is the same arc one circumference later.

| Mode | Pitch | Deck to upper deck | Body clearance | Reach gap | Dead zone | Revolutions |
|---|---|---|---|---|---|---|
| NORMAL | 11.29 (7.02-16.68) | 11.33 (6.68-16.88) | 7.31 (2.87-12.96) | 5.60 (1.50-11.34) | 4.66 (0.56-10.48) | 436 |
| PUNISHER | 10.71 (6.52-15.68) | 10.73 (6.25-15.97) | 6.78 (2.52-12.02) | 4.98 (1.43-10.24) | 4.07 (0.49-9.39) | 466 |
| NGH | 10.34 (6.34-15.15) | 10.36 (6.11-15.43) | 6.37 (2.40-11.59) | 4.68 (1.44-9.89) | 3.81 (0.55-9.05) | 480 |

- **Pitch is emergent.** No code enforces a pitch (`spiralPitch` 24 in tuning.json is read by nothing). It is the route's average slope ×
  the circumference, and the slope comes from the module mix: ropes, elevators, pads and stairs steepen it; every hazard, creature, mortar and
  spike-drop module flattens it. Harder modes place more flat hazard modules, so their layers are closer together. That is the first reason
  the hardest mode has the least room.
- **Body clearance** subtracts the active layer's occupancy (deck + 2.8 m standing room, plus a mover's travel) from the lowest occupied
  point above.
- **Reach gap** subtracts the highest point the climber can reach from any deck within 12 m (measured reach: plain 3.75 m; each launcher its
  own) from the lowest deck above.
- **Dead zone** is what is left after occupancy, reach and hazard influence (each hazard's lethal box) of the active layer.

![cross-section and distributions: comparison artifact charts 01-07]

## 3. Active gameplay corridor and influence envelope

The corridor is measured per movement class, from the generator's own tables (the same ones C1 uses), not from a model:

| Movement class | Vertical reach above its deck | Source |
|---|---|---|
| Standing room (body) | 2.8 m | `vHi`, CourseGenerator |
| Plain jump + ledge catch | 3.75 m measured (3.90 m from the constants: apex 1.85 + catch 2.05) | T5_FINDINGS section 2 |
| Seesaw, sideways mover, depth mover | 3.75 m | same |
| Ramp (plain, shaking, spinning) | 4.75 m | same; the turbo ramp is not measured |
| Swing | 6.75 m | same |
| Falling elevator / elevator | amplitude + 3.75 m (5.95-9.25 m) | same |
| Spring | 8.5 m | same |
| Pad | 10.25 m (at 4.5 m along the arc) | same |

The **hazard influence envelope** is kept separate: each hazard's lethal box (`hzLo`..`hzHi`). The mortar's is the tallest common one: from
the muzzle 3 m below the row deck to 2.9 m above it (amplitude 5.5 m plus the ball). Cannon balls fly 14-15 m past their lane, which no box
covers (inventory open question 10).

Note on the plain margin: the code comments and the plain 4 m rule assume a 1.3 m ledge catch; the Sim catches 2.05 m. The plain spacing
therefore has about 0.1 m of real margin, not 0.75 m. The body rule (5.1 m) is what actually separates plain decks inside the arc window.

## 4. The buildable dead zone, per mechanic

Share of route-deck samples where each mechanic could be placed as the next module, judged with the generator's own rules. **SAFE** = fits
under today's rules. **CONDITIONAL** = fits only if the blanket 9.5 m rule is replaced by measured reach (C1 and bodies still hold).
**UNBUILDABLE** = blocked by measured reach, bodies or hazard boxes as well. Baseline, NORMAL / PUNISHER / NGH:

| Mechanic | SAFE | CONDITIONAL | UNBUILDABLE |
|---|---|---|---|
| Plain deck | 98 / 97 / 97 | 0 / 0 / 0 | 2 / 2 / 2 |
| Pad | 40 / 32 / 28 | 0 / 0 / 0 | 60 / 67 / 72 |
| Spring | 52 / 43 / 38 | 2 / 2 / 3 | 46 / 55 / 60 |
| Elevator (MOVE_V) | 64 / 55 / 48 | 12 / 13 / 15 | 25 / 33 / 37 |
| Seesaw | 52 / 40 / 34 | 32 / 39 / 41 | 16 / 21 / 25 |
| Ramp | 37 / 26 / 20 | 23 / 24 / 25 | 40 / 50 / 55 |
| Swing | 54 / 44 / 39 | 20 / 21 / 23 | 26 / 34 / 38 |
| Sideways mover | 60 / 51 / 45 | 36 / 44 / 50 | 4 / 5 / 5 |
| Mortar row | 99 / 98 / 97 | 0 / 0 / 0 | 1 / 2 / 3 |

- Pads are unbuildable at most route decks because their own 10.25 m reach is the binding limit, not the blanket.
- Sideways movers, seesaws, ramps and swings are where the blanket costs the most: 20-50 % of route decks are CONDITIONAL for them.
- **The mortar row itself almost always fits** (97-99 %). Its 5-11 % acceptance comes from the decks it needs above lower-revolution
  launchers (section 7), not from its own column.

## 5. Placement-restriction audit

### 5.1 The rule set (exact order, CourseGenerator `layersClash`)

1. Same revolution (|Δs| < C/2): no clash, the route order handles it.
2. **C1 measured reach**: the upper platform is within the lower one's measured reach + 0.5 m at that edge-to-edge gap, at any arc distance the
   table covers: clash.
3. Arc window: edges more than 2.5 m apart: no clash.
4. **Bodies**: lower top + 1.5 m above upper bottom (5.1 m plain over plain): clash.
5. Not both platforms: no clash.
6. Δy ≥ 9.5 m: no clash.
7. Δy < 4 m: clash (never decisive; step 4 already covers it).
8. **Blanket**: the lower platform launches or carries the climber: clash. This is the 9.5 m rule.

### 5.2 What the 9.5 m rule measures, why 9.5, and whether it is conservative

It measures nothing per launcher: one height for every launcher and mover. 9.5 m came from the rules 8 "no trampoline shortcuts" decision
(DECISIONS, 2026-10-25), sized for the pad. The T5 measurements show it is conservative beyond physics for sideways and depth movers, seesaws
(3.75 m), ramps (4.75 m) and swings (6.75 m), about right for springs (8.5 + 0.5 = 9.0 m) and elevators (up to 9.75 m with the margin), and
**not enough for pads** (10.25 + 0.5 = 10.75 m). The inventory's section 8 gives, for each launcher, the height band only the blanket refuses.

### 5.3 Opportunities against attempts, and overlapping constraints

- **Attempts.** Per course, the generator refuses 27,005 (NORMAL), 42,345 (PUNISHER) and 52,321 (NGH) candidate placements on layer rules;
  6,567 / 7,668 / 8,200 of them (24 / 18 / 16 %) are refused by the blanket and by nothing else. Attempts repeat (the same deck is retried with
  other modules), so these counts overstate unique opportunities.
- **Unique opportunities.** Section 4's CONDITIONAL shares are per route deck, not per attempt: 20-50 % of decks for the low-reach launchers.
- **Overlap.** Removing the blanket entirely (`measured`) cut layer refusals by 26 / 12 / 9 % but raised hazards placed by only 9 / 6 / 7 %:
  freed placements then fail on C1, bodies or hazard boxes. The rules overlap heavily; the blanket is rarely the only obstacle to a hazard
  module that also needs a clear column.

## 6. Shortcut validation (Stage E)

Every cross-revolution platform pair within 11 m up and 10 m along, in the first 30 slices, tested on the real Sim (independent search with
the human-plausible 100 ms stick, plus the solver). A **shortcut** is a reachable pair that skips at least 5 route platforms.

| Rule | Courses audited | Pad shortcuts | Spring shortcuts | Low-reach launcher shortcuts | Plain-deck pairs (hazard-assisted, see below) |
|---|---|---|---|---|---|
| Today (blanket) | 6 | 12 (Δy 9.53-10.19 m, all ≥ 9.5) | 0 | 0 | 81 |
| measured (no blanket) | 6 | 9, incl. two **below 9.5 m** onto swings (8.78, 9.34 m) | 1 (9.06 m) | 0 | 91 |
| lowreach | 6 | 3 (Δy 10.06-10.27 m, all ≥ 9.5) | 0 | **0** | 80 |

(Courses: NORMAL 7321 and 7323, PUNISHER 7321 and 7323, NGH 7321 and 7323. NORMAL 7321 under today's rule and `measured` ran locally.)

- **Removing the blanket for pads and springs is unsafe**: C1's pad table underestimates reach at small or negative edge gaps, and swings are
  moving targets the table never measured. Those are new shortcuts the blanket was catching.
- **`lowreach` adds no shortcut from any launcher it relaxes.** Its remaining pad shortcuts are the pre-existing ≥ 9.5 m class.
- **Pre-existing pad gap (defect, every option).** Pad pairs 9.5-10.3 m up at an edge gap near zero are refused by neither rule: the blanket
  stops at 9.5 m and C1 interpolates 8.25-9.5 m there. Fix candidate: make C1's pad envelope the maximum over the whole table at gaps below
  2 m (10.25 + 0.5 = 10.75 m). It only tightens; it needs its own A/B before adoption.
- **Hazard-assisted lifts (open, every option).** 80-91 plain-deck pairs per 6 courses are reachable by the solver, 6.3-7.9 m up, using a
  hazard on the lower deck (a mortar's or a bee's knock-up). The independent search reached only 1-4 of them. Whether the solver models a
  move a person could make, or over-credits a knock, is undetermined; it is not caused by any option here.

## 7. Mortar investigation

| | baseline | r11.5 | r13 | measured | r11.5+measured | lowreach |
|---|---|---|---|---|---|---|
| Mortar-row acceptance NORMAL / PUNISHER / NGH (%) | 11.3 / 5.4 / 5.6 | 22.4 / 13.7 / 11.4 | 27.4 / 19.1 / 17.2 | 16.6 / 7.1 / 7.3 | 18.8 / 12.4 / 10.4 | 10.6 / 6.6 / 7.2 |

- **Cause.** Of refused mortar rows, about 81 % fail on layer rules (decks of the row sitting above a launcher of the revolution below) and
  about 11 % on the hazard box (run 41053445468). The mortar's own geometry fits 97-99 % of route decks (section 4).
- **The block under the mortar** was drawing only (2 m tall, ending 6.3 m below the path). It is now a 0.45 m plinth on the visual branch
  (`visual/build83-atmosphere` 2d945a7), ending ~4.8 m below. No rule ever used it.
- **Two-way projectile preserved.** The ball still rises from the muzzle 3 m below the path to 2.9 m above it and falls back; nothing here
  changes its trajectory.
- **The proposed player-only collision contract** differs from today's generator. In the Sim the ball already touches only the player. The
  generator, however, keeps the ball's whole column clear of other revolutions' bodies, so no ball can emerge through a deck someone stands on.
  A player-only contract would let the column pass through other revolutions' decks. That frees at most the ~11-19 % of mortar refusals that
  are hazard-box refusals, and creates balls rising through floors, unseen until they hit. **Not recommended.**
- **What actually helps mortars:** a wider tower (r11.5 doubles acceptance in PUNISHER and NGH) or relaxing the blanket under low-reach
  launchers (small gain; most mortar rows are blocked above pads, springs and elevators, which `lowreach` keeps).

## 8. Controlled spacing experiments

Tower 5,000 m and 10 castles in every case. Means per course over 3 seeds. Full table: `candidate_comparison.csv`; chart:
`candidate_comparison.png`.

| Candidate | Mode | Pitch (m) | Dead zone (m) | Layer refusals | Hazards | Enemies | Modules accepted | Growth castle 1-10 | Dips | Gen s |
|---|---|---|---|---|---|---|---|---|---|---|
| baseline | N / P / NGH | 11.3 / 10.7 / 10.3 | 4.7 / 4.1 / 3.8 | 27.0k / 42.3k / 52.3k | 854 / 1003 / 1208 | 405 / 587 / 702 | 2536 / 2489 / 2472 | +35 / +20 / +8 % | 2.7 / 3.7 / 4.3 | 170 / 291 / 593 |
| r11.5 | | 11.7 / 11.1 / 10.7 | 5.3 / 4.6 / 4.3 | 19.9k / 29.7k / 40.6k | 1024 / 1206 / 1509 | 391 / 664 / 792 | 2774 / 2712 / 2710 | +37 / +26 / +17 % | 3.0 / 3.0 / 3.0 | 204 / 473 / 703 |
| r13 | | 12.2 / 11.5 / 11.0 | 5.9 / 5.1 / 4.7 | 16.3k / 24.9k / 32.0k | 1144 / 1334 / 1722 | 456 / 738 / 908 | 2976 / 2928 / 2874 | +29 / +17 / +8 % | 3.0 / 2.7 / 3.3 | 250 / 548 / 936 |
| measured | | 11.0 / 10.4 / 10.1 | 4.3 / 3.7 / 3.5 | 19.9k / 37.2k / 47.4k | 934 / 1067 / 1292 | 380 / 603 / 708 | 2596 / 2535 / 2468 | +33 / +18 / +7 % | 3.3 / 3.3 / 4.3 | 290 / 400 / 573 |
| r11.5+measured | | 11.6 / 11.0 / 10.4 | 4.9 / 4.3 / 4.0 | 15.1k / 28.3k / 33.4k | 1028 / 1219 / 1543 | 386 / 622 / 810 | 2798 / 2725 / 2713 | +29 / +17 / +4 % | 3.3 / 3.3 / 4.0 | 347 / 544 / 855 |
| lowreach | | 11.1 / 10.5 / 10.2 | 4.3 / 3.8 / 3.6 | 22.7k / 36.9k / 43.1k | 882 / 1030 / 1280 | 397 / 584 / 695 | 2608 / 2498 / 2450 | +28 / +21 / +5 % | 3.7 / 3.0 / 4.7 | 169 / 444 / 408 |

Mode separation (KPI 7B, erosion of the harder mode's advantage from castle 1 to castle 10; the limit is 25 %):

| Candidate | PUNISHER over NORMAL, castle 1 -> 10 | erosion | NGH over PUNISHER, castle 1 -> 10 | erosion |
|---|---|---|---|---|
| baseline | +18.4 % -> +5.4 % | 71 % | +16.1 % -> +5.5 % | 66 % |
| r11.5 | +17.3 % -> +7.5 % | **57 %** | +14.5 % -> +5.8 % | **60 %** |
| r13 | +20.0 % -> +7.6 % | 62 % | +16.0 % -> +7.2 % | 55 % |
| measured | +17.2 % -> +3.6 % | 79 % | +14.6 % -> +4.0 % | 72 % |
| r11.5+measured | +16.4 % -> +5.1 % | 69 % | +20.4 % -> +7.5 % | 63 % |
| lowreach | +13.8 % -> +8.4 % | 39 % | +14.5 % -> 0.0 % (NGH not above PUNISHER at castle 10) | 100 % |

What these show:

- **Why a modest radius change has a large effect.** r11.5 widens the circumference by 15 % but the pitch by only ~4 %: the freed room is
  filled at once by more flat hazard modules (+12 % accepted modules), which flatten the route again. The gain comes from the threshold
  effect: most layer refusals missed by ≤ 0.25 m, so +0.4 m of median pitch clears a quarter of them.
- **r13 overshoots.** It adds the most hazards, but castle 1 rises as much as castle 10, so growth falls back to baseline in PUNISHER and
  NGH, and NGH generation time rises 58 %. The owner's illustrative "+3 m" corresponds to roughly r13 in circumference terms; by pitch it is
  not reachable with radius alone (r13 adds +0.7-0.9 m), because the route flattens to absorb it. A pitch target would need slope control,
  which the progression controller does not have (PROGRESSION_CONTROLLER_AUDIT section 7).
- **Rule-only candidates move little**: +3 to +9 % hazards, and growth in PUNISHER and NGH falls. They lift castle 1 (NORMAL 661 -> 719-721)
  about as much as castle 10.
- **Combining the radius with `measured`** is worse than the radius alone on growth: the freed placements go into early castles too.
- Not run: a variable (per-castle) spacing. A per-castle radius is not possible (one cylinder); per-castle rule strength would be the way
  and was not tested.
- **Statistical caution.** Three seeds per mode. Hazard counts, mortar acceptance and refusal counts move far outside the seed spread; growth
  and dips move within it for the rule-only candidates. Growth differences under ~5 points should not be relied on.

## 9. Visual impact of a wider tower

`radius_r10_r11.5_r13_h750.png` and `_h2750.png` (this folder): the same seed and height (seed 7, 744 m and 2,746 m, the climber on the same
route element), generated and rendered through the real WorldRenderer at radius 10, 11.5 and 13. Around the climber the three look almost the
same: the tower curves away from the camera slightly more slowly, so platforms toward the screen edges sit a little flatter and more of the
next decks stay in view. The courses differ in detail because each radius generates its own course. This is a creative call for the owner (Part E: "how the terrain rotates to match the camera
focus" must not change; the camera anchor and rotation are unchanged by a radius change, only the curvature is).

## 10. Dependency matrix

What each option touches. ● changes, ○ unaffected, ◐ must be re-validated.

| Subsystem | C1 pad fix | lowreach | radius 11.5 |
|---|---|---|---|
| CourseGenerator layer rules | ● | ● | ○ (rules unchanged; geometry changes) |
| Every generated course / seed codes | ● (rare pad pairs) | ● | ● (every course) |
| Rules version, fingerprint, bundled replays | ● | ● | ● |
| Per-seed records (stamped by rules version) | old records shown as "NOT THESE RULES" | same | same |
| Sim physics, movement, mortar trajectory | ○ | ○ | ○ |
| Sim circumference wrap (`dsWrap`), cross-revolution hazards (bees, aimed cannons see across revolutions) | ○ | ○ | ◐ (fewer near-overlaps; re-check) |
| Solver / Autopilot | ◐ | ◐ | ◐ (solve=1 runs, section 11) |
| WorldRenderer tower curvature, culling, shadows, particles, space scenery inside the cylinder | ○ | ○ | ◐ (screens in section 9; `SpaceScene.inside` keeps scenery off the play plane by radius) |
| Over-the-air updates | rules change needs a build | same | radius is in `OtaConfig.FROZEN_TUNING`: needs a full build |
| Save files | ○ (course regenerated from seed) | ○ | ○ |
| Progression controller (castle factors) | ○ | ◐ | ◐ (more modules fit; factors saturate less) |
| Generation time (streaming budget) | ○ | ◐ | ● +20-60 % per course |
| Route length / playtime for 5,000 m | ○ | ○ | ● about +10 % |
| Demo, Build 82 | ○ | ○ | ○ (frozen; not rebuilt) |

## 11. Impact on the 16 KPIs (expected, not certified)

| KPI | C1 pad fix | lowreach | radius 11.5 |
|---|---|---|---|
| 1 Run identity | rules bump | rules bump | rules bump |
| 2 Completion and validation | needs solve=1 | **solve=1 run 41089445815** | **solve=1 run 41089449890** |
| 3 Terrain difficulty | ≈ | ≈ (more seesaws/movers under decks) | ↑ (castle 1 +9 %) |
| 4 Hazard difficulty | ≈ | ↑ small | ↑ (+17-25 % hazards, mortars ×2 in P/NGH) |
| 5 Enemy difficulty | ≈ | ≈ | ↑ (+13 % enemies in P/NGH) |
| 6 Interaction | ≈ | ≈ | ↑ |
| 7 / 7B Pacing and progression | ≈ | ↓ (NGH order breaks at castle 10) | ↑ (best growth and erosion; still fails the 25 % limit) |
| 8 Variety | ≈ | ↑ small | ↑ (more mortar and drop rows) |
| 9 Generator efficiency | ≈ | ↑ | ↑ (-25 % layer refusals) |
| 10 Solver fairness | ↑ (fewer shortcuts) | ≈ (no new shortcuts) | needs solve=1 |
| 11 Avalanche | ≈ | ≈ | ≈ |
| 12 Enforcer | ≈ | ≈ | ≈ |
| 13 Performance | ≈ | ≈ | ↓ (+20-60 % generation time) |
| 14 Determinism, replays | re-record | re-record | re-record |
| 15 Platforms | build | build | build (frozen tuning) |
| 16 Advanced movement | ≈ | ↑ (low-reach launchers placed more) | ↑ |

Solver validation: section 13 (all three candidates 100 % PROVEN_SOLVABLE).

## 12. Options

| | A. Keep geometry, fix the gap | B. Measured-reach rule (`lowreach`) | C. Wider tower (radius 11.5) | D. C + B |
|---|---|---|---|---|
| What changes | C1 pad envelope only | blanket waived below low-reach launchers | circumference 62.8 -> 72.3 m | both |
| Hazards placed (NGH) | ≈ 1208 | 1280 (+6 %) | 1509 (+25 %) | not run as a pair; r11.5 + full `measured` gave 1543 |
| Mortar acceptance (NGH) | ≈ 5.6 % | 7.2 % | 11.4 % | (r11.5+measured: 10.4 %) |
| Growth castle 1-10 P / NGH | ≈ +20 / +8 % | +21 / +5 % | **+26 / +17 %** | (r11.5+measured: +17 / +4 %) |
| Shortcut safety | improves | no new shortcut | rules unchanged | no new shortcut expected |
| Cost | rules bump | rules bump | rules bump, wider look, +10 % route, +20-60 % gen time, full build | sum |
| Risk | lowest | low | medium (visual + performance) | medium-high |

**Recommendation: C, plus A's pad-gap fix.** It is the only option that improves hazard placement, mortar acceptance, castle growth and mode
separation together, without relaxing any fairness rule. B is safe but small, and in this sample it weakened NGH's lead at castle 10. D is
not better than C in the measured data (the full-`measured` pair lost growth); `lowreach` + r11.5 is in the solver runs for completeness.
None of the options fixes KPI 7B's 25 % erosion limit; that remains the castle-1 calibration decision already with the owner.

**Owner decisions needed.**

1. Accept a 15 % wider tower (section 9 screenshots), the ~10 % longer route and the extra generation time? (C)
2. Fix the pre-existing pad gap (A)? It only tightens.
3. Keep the 9.5 m blanket as is (recommended for now), or adopt `lowreach`?
4. Treat the hazard-assisted lifts (section 6) as an open solver question for a separate investigation?

Nothing will be implemented until the owner approves. Any approved change then needs the rules bump, fingerprint, replay re-record, full CI
and the certification campaign, in that order.

## 13. Solver validation results

`solve=1`, seeds 1, 2 and 7322, all modes, 5,000 m (commit 3397a41):

| Candidate | Run | Courses passing | Slices PROVEN_SOLVABLE | Non-proven | Integrity violations | Castles complete | Ordering at every castle |
|---|---|---|---|---|---|---|---|
| r11.5 | 41089449890 | 9 / 9 | 625 / 625 | 0 | 0 | 9 / 9 | yes |
| lowreach | 41089445815 | 9 / 9 | 621 / 621 | 0 | 0 | 9 / 9 | **no** (NGH not above PUNISHER at castle 10) |
| r11.5 + lowreach | 41089453619 | 9 / 9 | 621 / 621 | 0 | 0 | 9 / 9 | yes |

Every course in all three runs reports a witness setback (the solver's own witness run took a hit somewhere), so it is not specific to a
candidate; a solve=1 baseline at radius 10 on the same commit is needed before reading anything into it.

## 13a. Owner decision (2026-10-30)

**Option C plus the pad-gap fix**, implemented on `fix/build-83-root-causes`: `assets/data/tuning.json` radius 10 -> 11.5, and C1's pad table
holds 10.25 m at every edge gap up to 2 m. The 9.5 m blanket stays. No rules bump yet (that, the fingerprint and the bundled replays are done at
the final freeze). Validation on the implementing commit: full CI, a `solve=1` campaign at the new defaults, and the T5 shortcut audit.

## 14. Reproducing

```
./gradlew :core:testClasses
cd core && java -Dclimb.geomAudit=true [-Dclimb.radius=11.5] [-Dclimb.layerRule=measured|lowreach] \
  -cp build/classes/java/main:build/classes/java/test:<gdx jar> com.hotatticgames.climbup.sim.LaneReport ../assets/data/tuning.json 5000 <mode> <seed> <out>
python3 tools/ci/geometry_audit.py <out> label=<course folders>...
java [-Dclimb.layerRule=...] -cp ... com.hotatticgames.climbup.sim.T5Probe audit <mode> <seed> 30 <out>
```

On GitHub: Difficulty report with `jvm_props` (as in section 1), `geometry_compare` for the comparison package, `probe_jobs` for the shortcut
audits.
