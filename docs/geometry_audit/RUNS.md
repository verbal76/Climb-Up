# Geometry audit runs (branch audit/geometry)
| run | commit | config |
|---|---|---|
| 41081093114 | f9e6c9b | E0 baseline -Dclimb.geomAudit=true |
| 41081097861 | f9e6c9b | E1 radius 11.5 |
| 41081102740 | f9e6c9b | E2 radius 13 |
| 41081107092 | f9e6c9b | E3 layerRule=measured |
| 41081111661 | f9e6c9b | E4 radius 11.5 + measured |
| 41084673954 | 3397a41 | E5 layerRule=lowreach (lane report, geomAudit) |
| 41084678527 | 3397a41 | T5 shortcut audit, default rule: N/P/NGH 7321 (P,NGH), 7323 (N,P,NGH), 30 slices |
| 41084683218 | 3397a41 | T5 shortcut audit, measured |
| 41084688309 | 3397a41 | T5 shortcut audit, lowreach (incl. N 7321) |
Local: NORMAL 7321 30 slices default 3 reachable (2 STATIC with mortar, 1 PAD 10.04 m C1 boundary); measured 4 reachable (2 STATIC, PAD->SWING 9.34 m, PAD->STATIC 10.25 m).
| 41089358746 | 3397a41 | geometry_compare of E0-E5 (baseline, r11_5, r13, measured, r11_5_measured, lowreach) |
| 41089445815 | 3397a41 | S1 solve=1 lowreach (seeds 1,2,7322, all modes) |
| 41089449890 | 3397a41 | S2 solve=1 radius 11.5 |
| 41089453619 | 3397a41 | S3 solve=1 radius 11.5 + lowreach |
