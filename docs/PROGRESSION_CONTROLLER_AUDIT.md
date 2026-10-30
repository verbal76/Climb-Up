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
   the height-driven terrain curve, and factor 0.8 is its floor. The controller then compensates with the other categories, which the owner
   did not intend (enemies are held to +10% on purpose).
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

## 6. Measurements (CastleAuditProbe)

(pending)
