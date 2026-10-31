# Rule 21 difficulty progression (Build 83, experiment)

Owner directive 2026-10-31 ("Build 83 / Rule 21 master implementation"). Supersedes the Rule 20 30/15 linear application; the owner principle
(three linked curves that rise together, about 15 % apart) is unchanged.

## Objectives
- 3.3 % compounded growth per castle: Target(n) = D1 x 1.033^(n-1), +33.9 % castle 1 -> 10. NORMAL D1 = 560.
- PUNISHER = 1.15 x NORMAL and NGH = 1.3225 x NORMAL at every castle.
- Pacing classes per castle step (evaluation and control guidance, never a rejection gate): IDEAL 3.0-3.6 %, MINOR_LOW 2.5-3.0 %, MINOR_HIGH
  3.6-4.5 %, MODERATE 1.5-2.5 % or 4.5-6.0 %, SERIOUS below 1.5 % or above 6.0 %.

## Controller (one controller, `Progression`)
- Base factors per castle carried forward and corrected by the measured error with damped gains (terrain 1.0, hazards 0.6, enemies 0.6,
  interaction 0.5), at most x/÷1.25 per castle, clamped to the profile's bounds (anti-windup).
- Planned level: the target, but at least 1.5 % and at most 6 % above the previous castle's measurement.
- Within-castle trim from 30 % of a castle: cumulative, bounded ±15 %.
- Safety, fairness, geometry and solvability always win; a castle is never regenerated for pacing.

## Calibration
See docs/DECISIONS.md, "Build 83 Rule 21": lane shares from the Rule 20 campaign, additive terrain-demand steering, NGH terrain head start 2,000 m
and lighter opening hazards, the castle-2 Meadow inside its hazard budget, NGH capacity hold from 40 % acceptance.

## Measurement
Scoring is unchanged from Rule 20 (one shared definition now), so Rule 21 and Rule 20 scores are directly comparable. Reaction speed, Enforcers
and avalanches stay beside the score as diagnostics.
