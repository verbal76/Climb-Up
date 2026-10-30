# Progression controller audit (KPI 7B) - measurement and design only

Overnight directive 2026-10-30, priority D. Nothing in this document is implemented. No difficulty targets, caps, densities or balance values
were changed.

## 1. How the controller works today (code: `Progression`, `DifficultyProfile`)

- **Measurement.** Every finished slice is scored with the lane report's model (terrain T, hazards H, enemies E, interaction X, minus
  mitigation M) and accumulated per castle (500 m). The state travels inside the slices (`Course.prog`), so it is deterministic.
- **Targets (PROVISIONAL engineering values).** Linear from castle 1 to castle 10:

  | Mode | Castle 1 | Castle 10 | Growth 1→10 |
  |---|---|---|---|
  | NORMAL | 630 | 870 | +38% |
  | PUNISHER | 700 | 1045 | +49% |
  | NGH | 850 | 1210 | +42% |

  Category shares move from terrain-led at castle 1 toward more hazards and enemies at castle 10.
- **Control law.** A proportional controller: each selection factor is `1 + 1.5 × relative error`.
  - Before 35% of the current castle is built, the error is the previous castle's alone.
  - After that, the previous castle and the castle in progress are blended 50/50.
  - Each factor is clamped: terrain 0.8–1.3, hazards 0.7–1.35, enemies 0.7–1.1, interaction 0.8–1.3.
  - NGH hazards are capacity-controlled instead: at most +0.25 per castle, held while acceptance is below 0.55, bounded at 3.0.
  - There is no integral term. The header comment states the intent: "a castle that came out too hard makes the next one ease off instead of
    escalating".
- **Castle 1 opening factors.** PUNISHER opens at T 0.9 / H 0.8 / E 0.8 / X 0.9 because it measured 8–11% above its castle-1 target. NORMAL and
  NGH open neutral (1, 1, 1, 1).

## 2. Why the controller can suppress escalation (from the code)

1. **It tracks the absolute level, not the climb.** When castle 1 overshoots its target, castle 2's factors are pushed below 1 to bring the
   level back down to castle 2's target. If castle 1 overshoots by more than one castle's planned rise (PUNISHER: 38 points per castle),
   castle 2 is aimed *lower* than castle 1 came out. A dip after a hot start is the designed response, not a fault.
2. **No integral, bounded factors.** A category whose achievable value saturates keeps a steady-state error. Example: terrain is mostly set by
   the height-driven terrain curve, and factor 0.8 is its floor. Each category is controlled on its own error only (correction 2026-10-30: an
   earlier draft said the controller compensates with the other categories; the code does not), so a saturated category simply stays off
   target and the castle total drifts with it.
3. **Lag.** The previous castle's error is applied to the whole next castle. The castle in progress counts only after 35% of it is built, and then
   only at half weight. So the correction arrives a castle late, and overcorrection alternates with undercorrection: the 797 → 819 → 823 → 844
   PUNISHER sequence (Bob's three-seed evaluation, 2,000 m) is the controller pulling an overshot castle 1 toward a target line that rises more
   slowly than castle 1 started.
4. **Saturation near the top.** The bounds limit how far later castles can be pushed above the static profile. When castle 10's target needs factors past
   1.3–1.35, the climb flattens near the top.

## 3. Measured evidence

Filled from `CastleAuditProbe` (local, plan cache on, which is verified identical) - see section 6. Earlier evidence:

- The 1 km KPI audit (one seed per mode): PUNISHER castle 1 → 2 total 811.3 → 808.0 (−0.4%), against target 700 → 738 (+5.5%).
  Castle 1 was 16% over its target, so castle 2's factors were eased.
- Bob's three-seed 2,000 m evaluation: PUNISHER 797 → 819 → 823 → 844 (+5.9% over three castle steps, target +16%).

## 4. Proposed controller strategy (for owner review; not implemented)

- **S1. Calibrate the opening, not the gain.** Castle 1 should land on (or slightly under) its target. Then every later castle has room to rise
  at the planned slope. Lever options, in order of preference:
  - per-mode castle-1 opening factors, as PUNISHER has today, derived from measurement;
  - for the head-started modes, a smaller head start for castle 1 (`curveShift`), so the terrain curve does not open above target.

  This is the "lower early-castle difficulty" route. It costs nothing at the top and buys escalation.
- **S2. Track the slope as well as the level.** Make the next castle's target `max(target(n), measured(n-1) + planned rise × α)`, with α about
  0.5–1. A hot castle is then never followed by an easier castle, only by a smaller rise. This directly encodes KPI 7B's "meaningful escalation
  every castle", and needs no new bounds.
- **S3. Anti-windup for saturated categories.** When a factor sits on its bound for a whole castle, stop counting that category's error toward
  the others, and report it as `saturated`. This avoids pushing enemies to compensate for terrain.
- **S4. Reporting.** Add requested vs applied factor, the saturation flag and the slope error to `castle_control.csv`, so KPI 7B can be judged
  as "rises every castle, total growth near 30–40%" rather than as a fixed number.

Expected effect: S1 and S2 together turn "overshoot, then flatten" into a monotone climb. Total growth should stay near the targets' 38–49% when
castle 1 lands on target, or come out somewhat lower (about 30–40%) if castle 1 is calibrated slightly under target. All values are owner decisions.

## 5. Risks

- S1 changes what castle 1 looks like (it gets easier in modes that overshoot). That is a design decision.
- S2 can raise late castles above the current targets when early castles run hot. Cap it with the existing factor bounds.
- Any change alters every seed's course, so it needs a rules bump, replays and fingerprints at the next freeze.

## 6. Measurements (CastleAuditProbe, seed 7322, three castles each, 1,645-1,665 m)

Local runs with the plan cache and early rejection on. Both are verified to generate identical courses, so the numbers are those of the
default build. Totals are the controller's own estimate (the lane report's model); "factors" are the factors the castle's last slice was
built with. (lo)/(hi) mark a factor sitting on its bound.

| Mode | Castle | Total / target | Terrain T | Hazards H | Enemies E | Interaction X | Factors T / H / E / X |
|---|---|---|---|---|---|---|---|
| NORMAL | 1 | 677 / 630 (+7.4%) | 533 / 504 | 90 / 82 | 43 / 32 | 18 / 13 | 0.92 / 0.72 / 0.70 lo / 0.80 lo |
| NORMAL | 2 | 713 / 657 (+8.6%) | 573 / 514 | 108 / 93 | 24 / 36 | 14 / 15 | 0.89 / 0.82 / 0.94 / 0.80 lo |
| NORMAL | 3 | 705 / 683 (+3.1%) | 525 / 522 | 114 / 104 | 47 / 40 | 26 / 17 | 0.91 / 0.82 / 1.10 hi / 0.80 lo |
| PUNISHER | 1 | 810 / 700 (+15.7%) | 551 / 462 | 160 / 140 | 72 / 70 | 33 / 28 | 0.80 lo / 0.86 / 0.78 / 0.80 lo |
| PUNISHER | 2 | 806 / 738 (+9.2%) | 587 / 484 | 124 / 149 | 72 / 75 | 27 / 30 | 0.80 lo / 0.99 / 0.97 / 0.93 |
| PUNISHER | 3 | 818 / 777 (+5.3%) | 597 / 506 | 133 / 159 | 62 / 81 | 32 / 31 | 0.80 lo / 1.25 / 1.10 hi / 1.05 |
| NGH | 1 | 917 / 850 (+7.9%) | 599 / 493 | 177 / 179 | 100 / 145 | 43 / 34 | 0.80 lo / 0.99 / 1.10 hi / 0.80 lo |
| NGH | 2 | 893 / 890 (+0.4%) | 578 / 513 | 175 / 191 | 104 / 150 | 40 / 36 | 0.80 lo / 1.07 / 1.10 hi / 0.80 lo |
| NGH | 3 | 959 / 930 (+3.1%) | 686 / 533 | 141 / 204 | 91 / 156 | 43 / 37 | 0.80 lo / 1.28 / 1.10 hi / 0.81 |

NGH hazard capacity (accepted / attempted modules), castle 3: HAZ 42/82, TRAP 11/30, MORTAR 1/27, DROPS 1/7. Hazard control applied its whole
request in every castle (no capacity constraint triggered); the hazard shortfall comes from refusals, not from the request.

### Findings

1. **Escalation is flat in every mode.** Castle 1 → 3: NORMAL 677 → 705 (+4%), PUNISHER 810 → 818 (+1%), NGH 917 → 959 (+5%). Over
   the same castles the targets rise +8%, +11% and +9%. Castle 2 is at or below castle 1 in NORMAL (+5% then −1%), PUNISHER (−0.5%)
   and NGH (−3%).
2. **Terrain overshoots and the terrain factor is already on its floor.** PUNISHER and NGH terrain sits on factor 0.80 in all three castles
   and still measures 17–29% above its target; NORMAL's terrain runs 1–11% over at 0.89–0.92. Terrain comes from the height-driven terrain
   curve (plus the head start), which the selection factor barely reaches. This is the root of the "castle 1 too hot" overshoot.
3. **Each category is corrected on its own overshoot, and the level law turns an over-target castle into a dip.** (Corrected 2026-10-30: an
   earlier draft attributed this to compensation for terrain; the factors are computed per category, independently.) PUNISHER castle 1's
   hazards came out 14% over their own target (160 / 140), so castle 2's hazard factor was lowered and hazards fell 160 → 124, below castle 1:
   the castle-2 dip the 1 km audit reported. Terrain cannot come down (factor on its floor), so it does not offset the dip. Interaction sits on
   its 0.80 floor in NORMAL in every castle for the same reason (its own overshoot).
4. **Enemies saturate at their ceiling in NGH** (E 1.10 hi in every castle) and still reach only 60–69% of target. The +10% bound (the
   owner's "no enemy spam") is binding. NGH's total lands near target only because terrain happens to overshoot by about as much as enemies
   fall short (arithmetic, not control), so the composition drifts away from the intended shares.
5. **NGH hazards fall short by castle 3** (141 of 204) because the hazard modules are increasingly refused (mortars 1/27, drops 1/7), not
   because the controller asks for too little.

### What this means for the strategy in section 4

- S1 (calibrate the opening) has to act on **terrain**, the category that overshoots. Possible levers: castle 1's position on the
  terrain curve (`curveShift`) or a terrain factor floor below 0.80 for castle 1 only. Lowering the other categories' opening factors would
  not touch the terrain overshoot.
- S3 (anti-windup): because categories are independent there is no cross-category windup to remove; what remains is reporting saturation
  (requested vs applied) and not letting a saturated category's error grow without bound in the slope law.
- NGH's hazard shortfall is a capacity problem (module acceptance) and needs generator work, not controller gain.
- With S1 and S2, every mode has 20–30% of headroom between castle 1's calibrated level and castle 10's target. That matches the owner's
  30–40% reference without raising castle 10.

All of this is a proposal for the owner. No targets, bounds, caps or densities were changed.
