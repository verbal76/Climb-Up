# Build 83 corrected difficulty measurement - six-course comparison (2026-10-31)

## Scope and method

- **Generation unchanged.** Courses: the frozen Build 83 generation (rules 19, fingerprint `b0c5646446610a0274e036ab`), NORMAL seeds 1 and 3,
  PUNISHER seeds 2 and 3, NGH seeds 1 and 3 (the campaign seeds), each 5,000 m, generated locally.
- **What was measured.** Each course was measured twice with the generator's own steering estimate (`Progression`):
  - *original*: as it is today;
  - *corrected*: launcher take-offs (pad, spring, swing, elevator) scored against the launcher's measured reach (`CourseGenerator.reachAt`,
    the real-simulation C1 tables) instead of plain-jump reach.
- **Unchanged by the correction:**
  - pad and spring launches still score 0 (owner rule, confirmed 2026-10-31);
  - landing width, moving or swinging targets, tilt, crumble and wet footing;
  - hazards, enemies and interaction.
- **Course outputs.** Probes `CorrectedLanes` / `CorrectedLanes2`; outputs `scratchpad/tprobe/cl_*`, `cm_*`, summary `six_course.txt`.
  NORMAL seed 1 and PUNISHER seed 2 ran with the first probe version, which does not split plain-link from mechanic-link terrain; those
  columns are from the seed-3 / NGH courses.

## Per-castle means (2 courses per mode)

Total = terrain + hazards + enemies + interaction. "Plain" = terrain of STATIC-to-STATIC links (jumps); "mechanic" = terrain of every
other link (launchers, movers, ramps, seesaws, crumbling, ropes and cables).

| mode | castle | total original -> corrected (change) | terrain original -> corrected | hazards | enemies | interaction | plain-link terrain | mechanic-link terrain original -> corrected |
|---|---|---|---|---|---|---|---|---|
| NORMAL | 1 | 744 -> 528 (-216) | 618 -> 402 | 86 | 28 | 14 | 142 | 481 -> 251 |
| | 2 | 852 -> 624 (-228) | 637 -> 410 | 159 | 38 | 22 | 168 | 474 -> 240 |
| | 3 | 789 -> 598 (-191) | 648 -> 458 | 94 | 26 | 23 | 223 | 435 -> 238 |
| | 4 | 846 -> 650 (-196) | 632 -> 436 | 152 | 32 | 33 | 284 | 364 -> 172 |
| | 5 | 876 -> 676 (-200) | 636 -> 436 | 142 | 68 | 34 | 211 | 419 -> 222 |
| | 6 | 864 -> 654 (-210) | 639 -> 429 | 148 | 51 | 34 | 210 | 447 -> 227 |
| | 7 | 940 -> 738 (-202) | 666 -> 464 | 167 | 80 | 37 | 227 | 443 -> 238 |
| | 8 | 914 -> 724 (-190) | 660 -> 471 | 155 | 66 | 38 | 242 | 464 -> 246 |
| | 9 | 946 -> 738 (-208) | 654 -> 446 | 176 | 86 | 40 | 242 | 385 -> 179 |
| | 10 | 992 -> 798 (-194) | 704 -> 509 | 170 | 78 | 46 | 242 | 465 -> 259 |
| PUNISHER | 1 | 836 -> 654 (-182) | 588 -> 406 | 153 | 68 | 34 | 223 | 366 -> 173 |
| | 2 | 871 -> 660 (-210) | 604 -> 394 | 180 | 60 | 32 | 222 | 403 -> 190 |
| | 3 | 840 -> 665 (-174) | 614 -> 440 | 132 | 70 | 30 | 277 | 362 -> 178 |
| | 4 | 887 -> 718 (-170) | 635 -> 466 | 149 | 74 | 32 | 311 | 314 -> 168 |
| | 5 | 961 -> 752 (-210) | 656 -> 447 | 179 | 90 | 41 | 287 | 347 -> 162 |
| | 6 | 976 -> 796 (-180) | 665 -> 485 | 172 | 106 | 44 | 282 | 402 -> 219 |
| | 7 | 962 -> 794 (-168) | 632 -> 464 | 182 | 115 | 38 | 281 | 365 -> 186 |
| | 8 | 1028 -> 826 (-202) | 708 -> 506 | 173 | 112 | 42 | 328 | 375 -> 176 |
| | 9 | 990 -> 820 (-170) | 662 -> 493 | 178 | 114 | 45 | 329 | 341 -> 183 |
| | 10 | 1006 -> 833 (-172) | 670 -> 498 | 176 | 116 | 46 | 301 | 373 -> 196 |
| NGH | 1 | 1007 -> 820 (-187) | 680 -> 492 | 184 | 112 | 40 | 286 | 392 -> 206 |
| | 2 | 1002 -> 818 (-183) | 647 -> 464 | 196 | 120 | 44 | 288 | 359 -> 176 |
| | 3 | 966 -> 796 (-170) | 694 -> 524 | 164 | 72 | 40 | 368 | 326 -> 156 |
| | 4 | 1040 -> 888 (-152) | 720 -> 568 | 194 | 86 | 42 | 415 | 306 -> 153 |
| | 5 | 1052 -> 874 (-178) | 671 -> 493 | 224 | 118 | 45 | 328 | 343 -> 166 |
| | 6 | 1027 -> 851 (-176) | 670 -> 494 | 190 | 126 | 46 | 312 | 357 -> 182 |
| | 7 | 1071 -> 891 (-180) | 678 -> 498 | 228 | 122 | 48 | 344 | 334 -> 154 |
| | 8 | 1094 -> 912 (-181) | 689 -> 508 | 212 | 154 | 44 | 346 | 342 -> 162 |
| | 9 | 1098 -> 928 (-170) | 695 -> 526 | 244 | 118 | 44 | 354 | 341 -> 170 |
| | 10 | 1106 -> 940 (-166) | 688 -> 521 | 228 | 145 | 48 | 368 | 319 -> 152 |

The original totals of these six courses match campaign 41137500091's means (NORMAL castle 1: 744 here, 732 there; PUNISHER 836 / 846;
NGH 1,007 / 1,013), so the local measurement reproduces the campaign's.

## Mode differences

| | original | corrected |
|---|---|---|
| NORMAL progression castle 1 -> 10 (castles 1-3 -> 8-10) | +33 % (+20 %) | +51 % (+29 %) |
| PUNISHER progression | +20 % (+19 %) | +27 % (+25 %) |
| NGH progression | +10 % (+11 %) | +15 % (+14 %) |
| PUNISHER over NORMAL: mean / min / max | 6.9 / 1.4 / 12.9 % | 12.1 / 4.5 / 23.9 % |
| - early (1-3) / middle (4-7) / late (8-10) | 7.0 / 7.4 / 6.2 % | 13.6 / 12.7 / 9.9 % |
| NGH over PUNISHER: mean / min / max | 12.1 / 5.2 / 20.5 % | 16.4 / 6.9 / 25.4 % |
| - early / middle / late | 16.8 / 10.8 / 9.1 % | 23.0 / 14.8 / 12.1 % |

## What is measurement and what is content

- **Measurement only.**
  - Every mode loses 150-230 points per castle; all of it is the launcher term: about 50-80 launcher links per castle fall from about
    150-245 gap points to 6-16. The generator uses its launchers well within their measured reach.
  - Because NORMAL has the most launcher and mechanic links, it loses the most. The old score inflated NORMAL most and compressed the
    gaps between modes.
- **Content (unchanged by the correction, newly visible).**
  - Plain jumps are genuinely harder in the harder modes: plain-link terrain is +38 % (PUNISHER over NORMAL) and +30 % (NGH over
    PUNISHER) early, +32 % and +12 % late.
  - Enemies stay well separated: +50 % and +22 % late.
  - Hazards separate NGH from PUNISHER late (+30 %) but barely PUNISHER from NORMAL (+5 % late).
  - Interaction converges (the cap).
- **Remaining real convergence.** PUNISHER over NORMAL late (about 10 %, minimum 4.5 % at castle 10) and NGH's flat progression
  (+14-15 %) are content, not measurement.

## The 30/15 Split re-anchored on the corrected scale

- **Anchor.** NORMAL castle 1 = 560, the straight-line fit of the measured NORMAL castles (561; castle 1 alone, 528, is the noisiest
  point).
- **Linked targets.**

| castle | NORMAL target / measured | PUNISHER target / measured | NGH target / measured |
|---|---|---|---|
| 1 | 560 / 528 (-6 %) | 644 / 654 (+2 %) | 741 / 820 (+11 %) |
| 2 | 579 / 624 (+8 %) | 665 / 660 (-1 %) | 765 / 818 (+7 %) |
| 3 | 597 / 598 (0 %) | 687 / 665 (-3 %) | 790 / 796 (+1 %) |
| 4 | 616 / 650 (+6 %) | 708 / 718 (+1 %) | 815 / 888 (+9 %) |
| 5 | 635 / 676 (+7 %) | 730 / 752 (+3 %) | 839 / 874 (+4 %) |
| 6 | 653 / 654 (0 %) | 751 / 796 (+6 %) | 864 / 851 (-2 %) |
| 7 | 672 / 738 (+10 %) | 773 / 794 (+3 %) | 889 / 891 (0 %) |
| 8 | 691 / 724 (+5 %) | 794 / 826 (+4 %) | 913 / 912 (0 %) |
| 9 | 709 / 738 (+4 %) | 816 / 820 (+1 %) | 938 / 928 (-1 %) |
| 10 | 728 / 798 (+10 %) | 837 / 833 (-1 %) | 963 / 940 (-2 %) |

- **Achievability.**
  - Today's generation is already within -6 to +11 % of the linked curves.
  - Reaching them needs easing, not new content: NORMAL slightly (about +6 % late), NGH early (+6 to +11 % in castles 1-4).
  - PUNISHER needs nothing.
  - Easing lowers selection factors within their existing bounds and touches no clearance, solvability, fairness or anti-spam rule.

## Remaining measurement concerns

1. EncounterModel's `jump` term (NORMAL's threat budget) has the same launcher artifact; left unchanged, listed for review.
2. The interaction cap and the repeated-enemy discount still compress the harder modes. With the corrected totals, the NGH interaction
   target (about 39 at castle 10) is below what NGH already reaches, so it no longer asks for the impossible.
3. Reaction speed, Enforcers and avalanches stay outside the score and are reported beside it (owner decision).
4. Two courses per mode: castle means carry about +-5-10 % noise. The anchor uses a fitted line, and the certification campaign uses
   three seeds per mode.

## After the change (rules 20, local, the same six seeds)

Rules 20 adds three things:
- the launcher-reach correction in the steering estimate and in the lane report;
- the linked targets (NORMAL 560, +30 %, PUNISHER x1.15, NGH x1.3225);
- NORMAL's terrain demand capped at 0.9 at the top.

PUNISHER courses: round 1 (rules 20 without the NORMAL cap, which does not touch PUNISHER). NORMAL and NGH courses: the final rules 20.

| castle | NORMAL measured / target | PUNISHER measured / target | NGH measured / target |
|---|---|---|---|
| 1 | 538 / 560 (-4 %) | 654 / 644 (+2 %) | 824 / 741 (+11 %) |
| 2 | 620 / 579 (+7 %) | 688 / 665 (+3 %) | 820 / 765 (+7 %) |
| 3 | 606 / 597 (+1 %) | 722 / 687 (+5 %) | 834 / 790 (+6 %) |
| 4 | 640 / 616 (+4 %) | 710 / 708 (0 %) | 842 / 815 (+3 %) |
| 5 | 590 / 635 (-7 %) | 738 / 730 (+1 %) | 886 / 839 (+6 %) |
| 6 | 628 / 653 (-4 %) | 728 / 751 (-3 %) | 896 / 864 (+4 %) |
| 7 | 669 / 672 (0 %) | 760 / 773 (-2 %) | 889 / 889 (0 %) |
| 8 | 724 / 691 (+5 %) | 772 / 794 (-3 %) | 946 / 913 (+4 %) |
| 9 | 687 / 709 (-3 %) | 796 / 816 (-2 %) | 914 / 938 (-3 %) |
| 10 | 781 / 728 (+7 %) | 826 / 837 (-1 %) | 888 / 963 (-8 %) |

| | NORMAL | PUNISHER | NGH |
|---|---|---|---|
| mean deviation from the linked target | +0.6 % | 0.0 % | +3.0 % |
| progression castles 1-3 -> 8-10 | +24 % | +16 % | +11 % |

| separation | mean | minimum | early / middle / late |
|---|---|---|---|
| PUNISHER over NORMAL | 14.6 % | 5.8 % (castle 10) | 17.2 / 16.4 / 9.5 % |
| NGH over PUNISHER | 18.4 % | 7.6 % (castle 10) | 20.3 / 19.7 / 14.9 % |

Before (rules 19 on the corrected scale): PUNISHER over NORMAL 12.1 % mean (late 9.9 %), NGH over PUNISHER 16.4 % mean (late 12.1 %).

**Limits.**
- Two courses per mode: single castles move +-5-10 % between runs. Two-seed tuning rounds cannot resolve smaller effects, so tuning stopped
  here.
- An NGH castle-1 starting factor of 0.9 was tried and dropped: its castle 1 was unchanged (824 vs 822).
- What remains short of the design is late PUNISHER-over-NORMAL separation (about 10 %) and the castle-10 dip.
- PUNISHER sits on its target late. NORMAL's late castles stay a little above theirs, through terrain, with the controller's terrain factor at
  its floor.
- The certification campaign, three seeds per mode, measures this properly.
