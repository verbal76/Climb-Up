# The 30/15 Split - Upwardly's difficulty progression principle

Owner design decision (2026-10-31), approved for Build 83. This is the governing difficulty progression principle for Upwardly from Build 83
on. It supersedes the earlier independent castle-1 -> castle-10 targets (NORMAL +38 %, PUNISHER +49 %, NGH +42 %).

## The principle

- **30 % progression arc:** each mode, NORMAL, PUNISHER and NOT GONNA HAPPEN (NGH), targets about 30 % more difficulty at castle 10 than
  at castle 1.
- **15 % separation:** PUNISHER targets about 15 % more difficulty than NORMAL at the same castle, and NGH about 15 % more than PUNISHER.
  The separations compound: NGH is about 1.15 x 1.15 = 1.3225 x NORMAL.
- These are design targets, not exact numbers, hard caps or pass/fail tolerances. The three modes are designed, measured and judged as one
  coordinated system: three clearly distinct curves that rise together without substantially converging.

| illustration (NORMAL castle 1 = 100) | castle 1 | castle 10 |
|---|---|---|
| NORMAL | 100 | 130 |
| PUNISHER | 115 | 149.5 |
| NGH | 132.25 | 171.925 |

## Linked targets

1. NORMAL's curve is set first. Its castle-1 level comes from the existing generator, and is adjusted only on gameplay evidence.
2. PUNISHER's target at every castle is derived from NORMAL's at that castle with the intended separation.
3. NGH's target is derived from PUNISHER's in the same way.
4. The link holds at all ten castles, not only at the endpoints.
5. Lowering NORMAL's curve lowers the harder modes' curves with it. No mode keeps an independent ceiling.

## Expected variation

Procedural courses do not hit exact scores, and that is acceptable. For example, NORMAL progressing 28.7 % instead of 30 %, PUNISHER
averaging 14.2 % above NORMAL, or even NGH about 11.7 % above PUNISHER when the gameplay distinction stays substantial.

A few points of deviation is not a failure. No hard tolerance is set without evidence and owner approval. A statistically imperfect result
is preferable to an artificially forced one.

## How it is evaluated

Every castle is measured, and reports give:
- castle-by-castle scores per mode;
- each mode's castle 1 -> 10 progression;
- average, minimum and maximum adjacent-mode separation over all castles;
- early (castles 1-3), middle (4-7) and late (8-10) trends;
- terrain, hazards, enemies and interaction separately;
- plateaus, spikes and convergence;
- intended versus generated values.

A favourable average must not hide a late collapse in separation. Natural fluctuation and recovery sections are fine; persistent convergence
or long plateaus are investigated.

## Gameplay identity comes first

- **NORMAL:** accessible but genuinely challenging; difficulty builds steadily without becoming needlessly punishing.
- **PUNISHER:** for skilled platformer players and speedrunners; tighter execution, faster reactions, more demanding combinations,
  meaningful route decisions.
- **NGH:** the extreme mode, the hardest legitimate challenge, for exceptionally skilled players.

The 15 % is a measurement objective, not a substitute for these identities. A faster, more aggressive enemy can add more real difficulty than
several extra static hazards, and the measurement has to acknowledge such differences.

## Constraints that targets never override

Tower clearance, solvability, collision and geometry integrity, reasonable execution windows, enemy anti-spam rules, hazard fairness, route
diversity and deterministic generation all take precedence over the targets. Where a target cannot be reached inside these constraints, the
limiting factor is reported; safety rules are never weakened silently. Low scores are not compensated by simply adding enemies or
avalanches.

## Measurement limitations (known, Build 83)

From the Build 83 KPI 7B investigation (campaign 41137500091):
- The interaction score saturates at 40 per castle.
- The terrain lane is about 70 % of every total.
- Repeated enemies are discounted.
- Reaction speed and dynamic hazards are under-represented.
- The KPI 7B endpoint method exaggerates erosion.

The original KPI 7B FAIL of the Build 83 baseline stays in its historical record unchanged; no threshold is changed to manufacture a pass.
The 30/15 Split is evaluated on the full castle-by-castle evidence, with these limitations stated.

## Validation of a generation change made for it

Because the 30/15 Split changes generation, the baseline campaign cannot certify it. Validation runs in this order:
1. Focused development tests first: determinism, solvability, replay integrity, difficulty measurement and shortcut checks.
2. A before/after comparison of the three curves.
3. The minimum final certification campaign, proposed for owner approval once the generator is stable.
4. The final full CI run and packaging on the exact final Build 83 SHA.

## Build 83 application (owner choices 2026-10-31)

- **Full 30/15 everywhere.** The generator's late ceilings were measured on the Build 83 baseline: PUNISHER about 1,010-1,020 and NGH about
  1,065-1,115 lane units at castles 8-10, limited by jump demand at reach, hazard clearance, the enemy anti-spam bound and the interaction cap.
  So the linked curves are placed under NGH's ceiling: about NORMAL 640 -> 832, PUNISHER 736 -> 957, NGH 846 -> 1,100. NORMAL becomes easier
  than at 2a7935b, and PUNISHER and NGH start lower and climb further. Feasibility: docs/reviews/BUILD83_30_15_FEASIBILITY_2026-10-31.md.
- **Dynamic factors beside the score.** Reaction speed (PUNISHER 1.25x, NGH 1.5x), Enforcers and avalanches are reported next to each
  curve as acknowledged additional difficulty. They are not folded into the score the generator steers by.
