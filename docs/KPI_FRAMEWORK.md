# Upwardly build evaluation KPIs (owner framework, 2026-10-29)

This is the established reporting framework for every build evaluation. Build reports use these 16 categories, the four questions and the acceptance standards below.

Every KPI gets one of four results:
- **PASS:** verified against its criterion.
- **FAIL:** confirmed regression or failed requirement.
- **WATCH:** concerning, but not established as a regression.
- **NOT VERIFIED:** the required evidence is missing. A missing measurement is never a PASS.

## The 16 KPIs

| # | KPI | What it judges |
|---|---|---|
| 1 | Run identity and reproducibility | Exact commit, generator rules, fingerprint, seed, difficulty, tuning, CI run and environment. The same configuration must reproduce identical results |
| 2 | Course completion and validation | Full 5,000 m generation, all ten castles, solver success, geometry integrity. No unproven sections, invalid geometry or unresolved failures |
| 3 | Terrain difficulty | Spacing, jump distances, landing tolerances, vertical and horizontal movement, ropes, movers, seesaws, crumbling platforms, branches, dips, recovery. Rises appropriately without impossible jumps or lost movement variety |
| 4 | Hazard difficulty | Saws, spike blocks, cannons, mortars, spike drops, spike traps, pendulums, avalanches: frequency, exposure, timing windows, overlaps, collision risk. Rises, stays varied, stays avoidable |
| 5 | Enemy difficulty | Crabs, bees, skulls, blobs, Yellow/Orange/Red Enforcers: counts, placement, reachable encounters, pursuit, attacks, knockbacks, scores. Progresses without leaning on one type or becoming unfair |
| 6 | Interaction and mitigation | Compounded threats, and the defences that counter them. Meaningful compounding, no double counting |
| 7 | Difficulty density and pacing | Spikes, easy stretches, consecutive hazards, density, hardest sections, unchallenged distances, castle-to-castle progression (mean, median, min, max, sd, quartiles). Consistent challenge with deliberate variation and recovery |
| 8 | Gameplay variety and repetition | Unique mechanics, module repetition, repeated terrain patterns, rope frequency, longest repeated sequences, encounter distribution, entropy. Also flags difficulty rising while variety falls |
| 9 | Generator quality and efficiency | Attempts, accepted and rejected modules, rejection reasons, retries, fallbacks, exhausted attempts (bounded retry rules). Is the generator building the intended designs, or silently substituting easier ones? |
| 10 | Solver accuracy and fairness | Traversal success, failed links, unreachable sections, real collision simulation, timing, hazard avoidance, recovery, movement choices. A legitimate, physics-consistent path through every tested section |
| 11 | Avalanche performance | Frequency by castle, block counts, trigger timing, speed anticipation, rebounds, tumbling, block-on-block collisions, exposure, knockbacks, cleanup, per mode. Right frequency, intended physics, threatening, avoidable by skill |
| 12 | Enforcer performance | Yellow, Orange and Red separately: appearances, engagements, pursuit distance, platforms followed, kicks, swats, successful attacks, knockoffs, disengagements, retreats. Behaviour matches the tier; none in NORMAL |
| 13 | Generation and runtime performance | Generation time per slice, solver time, expensive modules, rejected-attempt overhead, memory, peak allocation, CI time. No unacceptable cost, and the expensive operations identified |
| 14 | Determinism, saves and replays | Fingerprints, replay event/state hashes, recorder integrity, divergence, checkpoints, keys, hits, falls, completion, save/load, damaged-save recovery. Identical seeds and inputs give identical outcomes; no lost progress |
| 15 | Release and platform integrity | Android, Windows, Linux, Steam, Steam Deck: identity, packaging, install, startup, controls, gamepad, save compatibility, Steam configuration, speedrun timing. Passing this does not authorise publication |
| 16 | Advanced movement utilization | Pads, springs, seesaws, ramps, swings, movers, ropes, cables, crumbling platforms. For each: placed, main route, optional, route links, solver-traversed, frequency over the climb |

## Scoring lanes

| Lane | Measures |
|---|---|
| TERRAIN | Environmental traversal challenge |
| HAZARDS | Environmental threats |
| ENEMIES | Hostile encounters |
| INTERACTION | Extra difficulty from combined threats |
| MITIGATION | Reductions from defensive advantages |

Lanes are measured per 25 m section and consolidated by castle, seed and difficulty. A total reference score is used to compare progression.

## Acceptance standards

| Requirement | Standard |
|---|---|
| Standard evaluation | 9 full-height simulations, 3 seeds per difficulty |
| Course | 5,000 m, 10 castles |
| Solvability | 100% of tested slices proven |
| Generation failures / geometry violations | 0 / 0 |
| Difficulty ordering | NORMAL < PUNISHER < NGH |
| Separation erosion | At most 25% loss of the adjacent-mode advantage |
| Variety | No unacceptable repetition or variety-rule violations |
| Determinism | Exact reproducibility within approved tolerances |
| Full CI | Must pass on the candidate commit |
| Missing measurements | NOT VERIFIED, never automatically PASS |

Avalanche frequency, enemy variety, pacing and the other gameplay metrics are compared with the approved baseline and the existing design requirements. Not every KPI has a single numeric threshold.

## The four questions every build report answers

1. **Does it work?** KPIs 1, 2, 9, 10, 13, 14, 15.
2. **Is it appropriately difficult?** KPIs 3, 4, 5, 6, 7.
3. **Is it enjoyable and varied?** KPIs 8, 11, 12, 16.
4. **Is it better than the previous build?** All 16.

**Reporting rule:** nine complete simulations → consolidated data → all KPI evaluations → recommendations. No further balancing is authorised on incomplete samples.
