# 30/15 Split - feasibility assessment (read-only, 2026-10-31)

Data: campaign 41137500091 on the frozen Build 83 baseline 2a7935b (9 courses, means of 3 courses per mode, lane units). Generator constants
read from `DifficultyProfile`, `Progression` and `CourseGenerator` at 2a7935b; the hardening branch changes none of them. Nothing was run or
changed for this assessment.

## 1. Linked 30/15 targets against the generator's output

NORMAL castle 1 is anchored where the generator puts it today (732), as the principle asks. PUNISHER = 1.15 x NORMAL and NGH = 1.15 x
PUNISHER at every castle; each rises 30 % linearly.

| castle | NORMAL measured / 30-15 | PUNISHER measured / 30-15 | NGH measured / 30-15 |
|---|---|---|---|
| 1 | 732 / 732 (0 %) | 846 / 842 (0 %) | 1013 / 968 (+5 %) |
| 2 | 826 / 756 (+9 %) | 874 / 870 (0 %) | 982 / 1000 (-2 %) |
| 3 | 771 / 781 (-1 %) | 837 / 898 (-7 %) | 972 / 1033 (-6 %) |
| 4 | 836 / 805 (+4 %) | 913 / 926 (-1 %) | 1021 / 1065 (-4 %) |
| 5 | 864 / 830 (+4 %) | 936 / 954 (-2 %) | 1064 / 1097 (-3 %) |
| 6 | 858 / 854 (0 %) | 986 / 982 (0 %) | 1040 / 1129 (-8 %) |
| 7 | 923 / 878 (+5 %) | 971 / 1010 (-4 %) | 1062 / 1162 (-9 %) |
| 8 | 921 / 903 (+2 %) | 1006 / 1038 (-3 %) | 1115 / 1194 (-7 %) |
| 9 | 925 / 927 (0 %) | 1018 / 1066 (-5 %) | 1064 / 1226 (-13 %) |
| 10 | 983 / 952 (+3 %) | 1009 / 1094 (-8 %) | 1086 / 1258 (-14 %) |

| | NORMAL | PUNISHER | NGH |
|---|---|---|---|
| progression castle 1 -> 10, measured | +34.3 % | +19.3 % | +7.2 % |
| progression target, old / 30-15 | +38 % / +30 % | +49 % / +30 % | +42 % / +30 % |
| means early (1-3) / middle (4-7) / late (8-10) | 776 / 871 / 943 | 852 / 952 / 1011 | 989 / 1047 / 1088 |

| separation | mean | minimum | maximum | early | middle | late |
|---|---|---|---|---|---|---|
| PUNISHER over NORMAL | 8.9 % | 2.7 % (castle 10) | 15.5 % (castle 1) | 10.0 % | 9.4 % | 7.3 % |
| NGH over PUNISHER | 11.1 % | 4.6 % (castle 9) | 19.8 % (castle 1) | 16.1 % | 10.1 % | 7.6 % |

What this shows:
- NORMAL already follows a 30/15 NORMAL curve within -1 to +9 % at every castle; its progression (+34 %) is close to 30 %.
- PUNISHER matches its linked target to castle 6 and then falls 3-8 % short.
- NGH starts 5 % hot and falls 2-14 % short, worsening late.
- The 30/15 targets ask the harder modes for *more* late difficulty than the old targets (PUNISHER castle 10 1,094 vs 1,045; NGH 1,258 vs
  1,210), which they already could not reach.

## 2. Limiting factors (why PUNISHER and NGH stop rising)

All are constraints the 30/15 principle says must not be weakened.
1. **Terrain demand is at the reach ceiling.** Jump demand (`CourseGenerator.diff`, `adjD`) rises from each mode's head start to its cap:
   - NORMAL 0.10 -> 1.0;
   - PUNISHER 0.33 -> 1.08;
   - NGH 0.73 -> 1.15.

   The terrain factor cannot push demand past `max(d, DEMAND_MAX = 1.1)`, and demand near 1 means gaps near the player's measured reach.
   NGH starts at 0.73 because of its 3,500 m head start, so its terrain lane is flat (681 -> 682). PUNISHER reaches its cap by castle 10.
   Raising demand further means jumps beyond fair execution windows.
2. **Hazard placement is clearance-limited.**
   - Refused/attempted modules are 47-50 % in NORMAL, 54-58 % in PUNISHER and 60-62 % in NGH, mostly by layer clearance in the tighter
     towers.
   - PUNISHER mortars are accepted in under 3 % of late tries; NGH `deck_in_hazard_refused` is 31,075.
3. **Enemies are anti-spam bounded.** The enemy factor may rise only to 1.1 (owner rule), and more than 3 of one kind in a 25 m window
   counts 25 %.
4. **Interaction is capped at 40 per castle** (2 per 25 m window). PUNISHER and NGH sit at 39-40 late; their interaction targets (43.8 and
   50.3 at castle 10 under 30/15 with their current mixes) are unreachable.

Measured late ceilings, with every constraint intact: PUNISHER about 1,010-1,020 (castles 8-10), NGH about 1,065-1,115.

## 3. What the 30/15 Split can be within those constraints

With the ceilings fixed, the curves can only meet 30/15 by the whole system moving down, so that the harder modes finish at today's late
values and start lower:

| option | NORMAL c1 -> c10 | PUNISHER c1 -> c10 | NGH c1 -> c10 | what it means |
|---|---|---|---|---|
| keep NORMAL as today (anchor 732) | 732 -> 952 | 842 -> 1094 | 968 -> 1258 | P and NGH cannot reach it: late separation stays about 3-8 % |
| full 30/15 under the NGH ceiling | 640 -> 832 | 736 -> 957 | 846 -> 1100 | NORMAL 13-15 % easier than today; P and NGH start 13-17 % easier; all three progress 30 % |
| 30/15 with NGH/P tapering to ~10 % late | 669 -> 870 | 769 -> 1000 | 885 -> 1100 | NORMAL 9-12 % easier (back to its originally designed 870 at castle 10); P starts 9 % easier, NGH 13 % easier; NGH/P 15 % early -> 10 % late, so NGH progresses about 24 % |

## 4. The smallest changes (if the third row is chosen)

| change | affects generation? |
|---|---|
| `DifficultyProfile.totals()` replaced by the linked curve: NORMAL anchor and +30 % slope, PUNISHER = 1.15 x NORMAL, NGH = 1.15 x PUNISHER (NGH's late separation left to what its ceiling allows) | yes |
| NORMAL's easing floor (terrain factor 0.8 today) lowered so the controller can actually bring NORMAL down to its curve | yes (NORMAL) |
| PUNISHER and NGH terrain head starts lowered so their terrain rises over the climb instead of starting near the ceiling | yes (P, NGH) |
| interaction targets held below the 40 ceiling, the shortfall moved to categories that can deliver it | yes |
| category mixes (shares) otherwise kept per mode (identity) | - |
| reports: a 30/15 section (per-castle linked targets, per-castle separation, mean/min/max, early/middle/late, per lane); the frozen KPI 7B check kept unchanged | no |
| reaction speed, Enforcers and avalanches: reported beside the score, not folded into the controller's score (folding them in would make the controller place less content in PUNISHER/NGH for the same target) | no |

Every course in every mode would change: rules 20, a new fingerprint, the bundled replay re-recorded, and the campaign evidence of 2a7935b no
longer certifies generation. The hardening work is generation-neutral and carries over.

## 5. Validation plan (after approval of the target option)

1. Local, one course per mode at a time, at most two processes: tune the anchor, the NORMAL floor and the head starts against the linked
   curve. Each NORMAL course takes about 3-5 minutes locally and PUNISHER/NGH 10-40 minutes, so a round of three is about an hour.
2. When stable: rules 20, fingerprint, replay re-record; focused tests (determinism, solver/recovery, difficulty-score and mode-profile tests,
   bundled replay).
3. Then one certification campaign proposed for approval: the 9-course campaign (seeds 1-3 x 3 modes, 5,000 m) plus the T5 audit, about
   1.5 h wall-clock, the same size as 41137500091; then one full CI and packaging on that exact SHA.
