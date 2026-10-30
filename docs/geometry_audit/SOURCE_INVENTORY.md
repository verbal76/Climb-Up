# Vertical geometry, active corridor and buildable dead zone: source inventory (Stage A)

Read-only source audit. Commit `fd8c0c0` (branch `audit/geometry`), 2026-10-30. Nothing here was run; every number is read from the source
at that commit, and every derived number says how it was derived.

**Working-tree note.** While this audit was being written, the worktree briefly held uncommitted edits to `CourseGenerator.java` (an
experimental `-Dclimb.layerRule=measured` switch `LAYER_MEASURED`, a `layerRuleTally` diagnostic and `reachShortcutQuiet`) and an untracked
`GeometryAudit.java` test; they were gone again when this file was finished. **All line numbers below are those of the committed files at
`fd8c0c0`** (`CourseGenerator.java` 3,377 lines).

## Path abbreviations

| Tag | File |
|---|---|
| TJ | `assets/data/tuning.json` |
| TU | `core/src/main/java/com/hotatticgames/climbup/sim/Tuning.java` |
| CG | `core/src/main/java/com/hotatticgames/climbup/sim/CourseGenerator.java` (at `fd8c0c0`) |
| SIM | `core/src/main/java/com/hotatticgames/climbup/sim/Sim.java` |
| EL | `core/src/main/java/com/hotatticgames/climbup/sim/Element.java` |
| GI | `core/src/main/java/com/hotatticgames/climbup/sim/GeometryIntegrity.java` |
| CO | `core/src/main/java/com/hotatticgames/climbup/sim/Course.java` |
| EM | `core/src/main/java/com/hotatticgames/climbup/sim/EncounterModel.java` |
| AP | `core/src/main/java/com/hotatticgames/climbup/sim/Autopilot.java` |
| DP | `core/src/main/java/com/hotatticgames/climbup/sim/DifficultyProfile.java` |
| TW | `core/src/main/java/com/hotatticgames/climbup/sim/Tower.java` |
| CGEM | `core/src/main/java/com/hotatticgames/climbup/sim/CheckpointGem.java` |
| OTA | `core/src/main/java/com/hotatticgames/climbup/ota/OtaConfig.java` |
| WR | `core/src/main/java/com/hotatticgames/climbup/render/WorldRenderer.java` |
| LST | `core/src/test/java/com/hotatticgames/climbup/sim/LayerSpacingTest.java` |
| DEC | `docs/DECISIONS.md` |
| T5 | `docs/verification/build83/T5_FINDINGS.md` |
| S1 | `docs/verification/build83/S1_FINDINGS.md` |
| R17 | `docs/verification/EXPERIMENT_RULES17.md` |
| SP | `docs/probes/ShortcutProbe.java`, `docs/probes/LapRise.java` |

## Column conventions

- **Config**: `TJ key` (in tuning.json, loaded at runtime, OTA-replaceable unless frozen), `TU default` (a `Tuning` field with a code default
  but no tuning.json entry: still overridable by JSON), `const` (a Java constant), `sysprop` (a `-D` switch; diagnostics unless stated).
  OTA freezes `radius, zoneHeight, zoneCount, castleSpacing, finishCastle, gemSpacing, courseHeight, rampHeight, chunkHeight, spiralPitch,
  restEvery, checkpointEveryRests` (OTA:27).
- **Kind**: `hard` = enforced (a refusal, a collision test, a physical law of the Sim), `heur` = a weight, preference or sampling range.
- **Affects**: `G` gameplay/generation, `R` rendering only, `G+R` both.
- **Purpose**: a quote from the code comment or DEC; `undocumented` where neither states one.

---

# 1. Tower architecture

The tower is a wrapped arc coordinate `s` around an imaginary cylinder; the route is a list of elements in unwrapped arc (it only advances
toward +s), so "another revolution" is decided by the raw arc difference.

| Item | Value | Units | Where | Formula / use | Consumers | Config | Kind | Purpose | Affects |
|---|---|---|---|---|---|---|---|---|---|
| radius | 10.0 | m | TJ:2; TU:7 (default 10) | circumference = 2 pi r (TU:55) | Course ctor (CG:70), renderer projection (WR:915-916, 936, 970) | TJ key, OTA-frozen | hard | "All movement, world and generator numbers" (TU:5); none specific | G+R |
| circumference | 62.832 | m | derived TU:55 | 2 x pi x 10 | every `dsWrap`/`wrap` (CO:105-115), every revolution test | derived | hard | undocumented | G+R |
| Same/other revolution | \|s1 - s2\| >= C/2 = 31.416 m | m (raw unwrapped arc) | CG:1948, 1989, 2035, 2095, 2226, 2262, 2288, 2296; GI:90 | other revolution iff raw arc difference >= half a lap | layer rules, hazard rules, integrity, corridor audit | const (0.5 x C) | hard | "Elements are compared in one frame: unwrapped arcs, so a revolution is circumference of arc" (GI:17-18) | G |
| Arc distance | `dsWrap` = (a-b) mod C into (-C/2, C/2] | m | CO:105-110 | wrapped shortest arc | all arc gates | const | hard | undocumented | G+R |
| castleSpacing | 500 | m | TJ:15; TU:17 | castle n at n x 500; frame yBase is a multiple of it (CG:189-191) | castles (CG:371-397), frames, Enforcer/avalanche plans | TJ, OTA-frozen | hard | "the castles of the endless climb stand at exactly this height apart" (TU:17) | G |
| Red gem (halfway) | castleSpacing x (n + 0.5) | m | CG:371-376 | one exact-height gem between castles | chooseGem (CG:1239) | derived | hard | "ONE red-gem checkpoint halfway between every two castles" (CG:371) | G |
| gemSpacing | 30 (+-15 %) | m | TJ:15; TU:26 | rest every gemSpacing x r(0.85, 1.15) (CG:589, 599) | extend | TJ, OTA-frozen | heur | "red-gem checkpoints about every gemSpacing metres (+-15%)" (TU:26) | G |
| zoneHeight | 1000 (TU default 400) | m | TJ:15; TU:26 | theme = floor(y / zoneHeight) mod zoneCount (CG:237) | theme, renderer palette (WR:561), first-world second half (CG:633, 3336) | TJ, OTA-frozen | hard | "each world ... lasts zoneHeight metres, then they repeat" (TU:26) | G+R |
| zoneCount | 5 | - | TJ:15; TU:26 | modulo | theme | TJ | hard | as above | G+R |
| chunkHeight | 60 | m | TJ:15; TU:28 | slice top = startY + 60 (CG:398) | buildChunk | TJ, OTA-frozen | heur | "the endless tower is generated in chunks of about this height" (TU:28) | G |
| Slice claim window | 80 | m | CG:373-383, 407, 409 | castle / gem / key / conveyor / Enforcer / avalanche heights claimed in startY..startY+80 | buildChunk | const | hard | "a slice claims the heights in its first 80 m" (CG:385) | G |
| EXACT_ROOM | 10 | m | CG:509 | claim cap = next foreign structure - 10 (CG:406); Enforcer claims to cap-25, avalanche to cap-35 (CG:407-410) | buildChunk | const | hard | "Room kept below an exact-height structure that is not this slice's own" (CG:508) | G |
| Context copy | min(10, routeSize) route elements | elements | CG:355-363 | previous slice tail copied as live context; the rest of the previous slice are "ghosts" | every layer/hazard check | const | hard | "the tail of the previous slice (so the planner sees the real approach) plus everything else as geometry-only ghosts" (CG:352) | G |
| Frame | yBase = k x 500, sBase = whole laps | m | CG:186-194 | each slice generated in a small local frame | determinism | const | hard | "so every number the simulation touches stays small at any altitude" (CG:179) | G |
| spiralPitch | 24 | m / rev | TJ:16; TU:31 | **read by no code** (only listed in OTA:27) | none | TJ, OTA-frozen | - | TU:31 "height gained per revolution, enforced by the generator"; DEC:939 "spiralPitch 24 m was never achievable" | none |
| layerGapMin | 10 | m | TJ:16; TU:34 | lagging if layerGap(last) < 10 (CG:623, 2731) | pitch controller, fallbacks | TJ | heur | "below the minimum only vertical modules are favoured ... 9.5 m keeps a launcher's layer spacing" (TU:32-33) | G |
| layerGapMax | 16 | m | TJ:16; TU:34 | rushing if layerGap > 16 (CG:2731) | pitch controller | TJ | heur | "above the maximum vertical ones are damped" (TU:33) | G |
| layerGap(e) | e.y - y(route element nearest s - C, within 8 m) | m | CG:573-578 | +inf if none within 8 m | pitch controller | const 8 m window | heur | "How high e is above the route one revolution back at the same arc" (CG:568-572) | G |
| Pitch damping | non-climbing x0.12 while lagging; climbing x0.2 while rushing | factor | CG:2732-2736 | `climbs()` = PAD, ROPE, MOVER_V, STAIRS, ELEV_FALL (CG:2692) | weights | const | heur | "keep successive revolutions a healthy distance apart" (CG:2725) | G |
| Repeat damping | prev kind x0.35; same kind 3 times -> 0 | factor | CG:2738 | | weights | const | heur | undocumented | G |
| Lagging fallbacks | only `climbs()` modules drawn as challenge fallbacks | - | CG:660-667, 2679-2687 | hop last when lagging | extend | const | hard | DEC:976 (rules 11) | G |
| Required average climb | 10/62.83 = 0.159 to 16/62.83 = 0.255 | m rise per m arc | derived | layerGapMin, layerGapMax / C | - | derived | - | derived here | G |
| Measured rise per revolution | 12.3 -> 12.7 m (NORMAL seed 1 to 1,590 m, after pitch fix); earlier 18.8-20.1 m (rules 8); 20.9-21.4 m (Build 79) | m / rev | DEC:939; DEC:771 | LapRise probe (SP) | - | - | - | measurements, different eras | G |
| Tower residency (live) | REACH 300, MARGIN 120 | m | TW:21 | world rebuilt +-300 m around the player | live Sim window (SIM:206-216) | const | hard | "How far (metres) the world reaches below / above the player" (TW:20) | G+R |
| fallRespawnDepth | 12 | m | TJ:12; TU:21 | respawn if y < lastGroundY - 12; endless: y < floorY - 6 (SIM:671-672) | Sim | TJ / const 6 | hard | undocumented | G |

Slices: `ATTEMPTS = 24` per slice (CG:94); from attempt 12 `relaxed = true` (CG:111): ghosts are checked with `layersBodiesClash` only (CG:1986),
"waive only its shortcut spacing ... bodies of different revolutions stay clear" (CG:1983-1985; DEC:941).

---

# 2. Platform architecture

## 2.1 Element dimensions and thickness

| Item | Value | Where | Notes | Config | Kind | Purpose | Affects |
|---|---|---|---|---|---|---|---|
| Platform reference | `(s, y=top, w)`; s = centre arc | EL:3 | all platform `y` are walking-surface heights | const | hard | "Platforms use (s, y=top, w)" (EL:3) | G+R |
| Collision slab `slab()` | 1.0 m if w >= 5 or GOAL, else 0.5 m | EL:92 | used for side entry / pass-under (SIM:2133, 2145) | const | hard | "matches the renderer: wide blocks and the goal are 1 m, narrow ones 0.5 m" (EL:91) | G+R |
| Drawn block height | 1.0 m (w >= 5 or GOAL) / 0.5 m | WR:226-229 | `thick = w >= 5`; rounded width | const | - | same rule as slab() | R |
| Generator deck slab (body box) | 0.8 m (`vLo` default y - 0.8) | CG:1927; GI:24 `DECK = 0.8` | **not** equal to slab() for w >= 5 (1.0) | const | hard | "Deck slab thickness under a platform's walking surface (CourseGenerator.vLo's default)" (GI:23) | G |
| Standing room | 2.8 m above a deck (`vHi` default y + 2.8) | CG:1932 | | const | hard | DEC:979 "standing room 2.8 m" | G |
| edgeOverhang | 0.2 m | TJ:6; TU:12 | feet may stand 0.2 m past a deck's edge (SIM:1964, 2014, 2037, 2112) | TJ | hard | undocumented | G |
| Player halfWidth / height | 0.28 / 1.25 m | TJ:6; TU:12 | body box (SIM:1752); hazard box shrunk to 0.25 / y+0.12..y+1.15 (SIM:1869) | TJ | hard | undocumented | G |
| STEP_ACROSS | 0.2 m | SIM:539, 2080-2096 | walk onto a fixed neighbour up to 0.2 m higher | const | hard | "a fixed neighbour whose top meets it at about the same height ... is simply walked onto" (SIM:2076-2078) | G |
| One-way platforms | a rising body passes up through any deck; landing only when falling (`relVy <= 0`) and feet crossed the top | SIM:2016-2019 | T5 sec. 1: "A deck of another revolution 1.4-3.8 m above the climber does not stop a jump" | const | hard | T5:34-38 | G |
| Landing search window | elements with \|ey1 - y\| <= 8 m | SIM:2012 | | const | hard | undocumented | G |
| Z_REACH (depth mover) | 1.0 m | EL:343; SIM:606 | MOVE_Z is "gone" when \|z\| > 1 | const | hard | "You can only land on it while it is in your plane" (EL:20) | G |
| Mover crumble | moverCrumbleDelay 1.6 s | TU:19 | TU default only | TU default | hard | "movers that fall apart ... hold a little longer" (TU:19) | G |
| Crumble | crumbleDelay 0.5 s, crumbleRespawn 3.4 s | TJ:11; TU:16 | | TJ | hard | undocumented | G |
| Holed bridge | HOLE_SEC 1.3 m sections, HOLE_DELAY 0.45 s, HOLE_DONE 2.45 s; HOLE_FOOT 0.2 m | EL:109-111; SIM:715 | sections fall for good (rules 19) | const | hard | EL:107-111 | G+R |
| Seesaw | seesawMaxTilt 0.55 m/m, rate 1, relax 1, slide 5 m/s, uphill 0.45; beltSeesawDead 0.5 m | TJ:14; TU:23-24 | tipped end displacement = 0.55 x w/2 = 1.93-2.2 m for w 7-8 (derived) | TJ | hard | "seesaw bridge: slope (height per metre), tip / relax speed ..." (TU:23) | G |
| RAMP_SLOPE | 0.576 m/m | CG:17 | rise = 0.576 x L | const | hard | undocumented | G |
| Rope beam | BEAM_TOP 0.24 m above rope.y, BEAM_HALF 1.1 m, mount 0.62 s, top hold 0.06 s | SIM:33, 2317-2337 | **a standable surface that is not an Element platform** | const | hard | "The beam above a rope is a small standing place" (SIM:2316) | G |

## 2.2 Spiral-layer footprint of each element (body box used by every cross-revolution rule)

`arcHalf` (CG:1917-1925), `vLo` (CG:1926-1928), `vHi` (CG:1929-1934). "dLo" = y - vLo, "dHi" = vHi - y.

| Type | arcHalf | dLo (below y) | dHi (above y) | Comment in source |
|---|---|---|---|---|
| default (STATIC, CRUMBLE, PAD, SPRING, GOAL, MOVE_Z, SEESAW arc) | w/2 | 0.8 | 2.8 | (GI:23-24, DEC:979) |
| MOVE_H (incl. SLIDE_FALL) | w/2 + amp | 0.8 | 2.8 | "a mover's whole travel" (GI:8) |
| MOVE_V (incl. ELEV_FALL) | w/2 | 0.8 | amp + 2.8 | |
| SWING | w/2 + len sin(amp) | 0.8 | len + 0.4 (pivot) | "a swing's arc" (GI:8) |
| ROPE | 0.8 | len | 0.6 | "a rope's length" (GI:8) |
| TARZAN | len sin(amp) + 0.5 | len + 1.4 | 0.6 | "the rope's end at full swing, plus the body's half width and a margin"; "the rider's feet hang handHeight 1.15 m below the rope's end, plus 0.25 m" (CG:1922, 1927) |
| CABLE | w/2 (span) | 2.4 | 0.4 | undocumented |
| SEESAW | w/2 | 2.8 | 2.8 | undocumented (tilt not added to dHi, see sec. 9) |
| RAMP | w/2 | amp x w/2 + 3.4 | amp x w/2 + 2.8 | undocumented |

Element-level geometry checks on these boxes: I2 same-revolution decks (`arc < arcHalf + arcHalf - 0.05` and slabs share height with DECK 0.8,
`top()` = MOVE_V y+amp, SWING y + len(1 - cos amp); GI:78-81, 96-104), I3 rope through deck (GI:82-86: arc < halfW - 0.1, rope.y > deck.y + 0.3,
rope bottom < deck.y - 0.8 - 0.3).

## 2.3 Route modules: rises, gaps, widths (all `build*`; d = terrain demand 0..1, `attempt` 0..7 shrinks gaps)

Gaps are edge-to-edge from the previous platform's +s edge `eR`. `reach(dy)` is the generator's jump-reach helper (sec. 3.3).
Net rise = last platform y - start platform y. Every module is `heur` (sampling ranges) feeding the `hard` proofs and layer checks; all `G`.
None of these values is in tuning.json except where a TJ key is named.

| Module (Kind) | Lines | Elements and geometry | Net rise (m) | Purpose quoted |
|---|---|---|---|---|
| HOP | CG:3160-3178 | one STATIC, dy r(0, 0.8) tier 0, r(0, 1.4) tier >= 1; gap = reach(dy) x lerp(0.45, 0.92, d) x r(0.9, 1.1); w 2-4 (tier 3: half the time 1-2). Wide hop: frac >= r(0.66, 0.74) every `wideHopEvery` m (DP NORMAL 36, PUNISHER/NGH 18; DP:72, 80, 87). Dip (20 %, only from the route's high point): 1-2 steps of -r(0.4, 1.1) | 0 to 1.4 (dip: -0.4 to -2.2) | "now and then the route steps down one or two blocks before it climbs on" (CG:3161); "the open air a fork drops through" (CG:3173) |
| STAIRS | CG:3191-3200 | 2-3 steps, each dy r(0.9, 1.3), gap reach(dy) x lerp(0.4, 0.7, d), w 1-2 (last 2-3) | 1.8 to 3.9 | undocumented |
| CRUMBLE | CG:3201-3210 | 2 (3 if d > 0.5) CRUMBLE w 2, dy r(0.2, 0.5) each, gap lerp(0.7, 1.8, d) x r(0.9, 1.1); then STATIC w 3, 1.5 m gap, +r(0.2, 0.6) | 0.6 to 2.1 | undocumented |
| MOVER_H | CG:3211-3218 | MOVE_H w 2 at y0 + 0.3, amp 1.4 + 1.0 d, period r(3.2, 4.4); gaps lerp(1.0, 1.8, d); exit w 3 at +r(0.3, 1.0) | 0.6 to 1.3 | undocumented |
| SLIDE_FALL | CG:3235-3242 | MOVE_H skin 1 w 2.5 at y0 + 0.3, amp 1.2 + 0.8 d, period r(3, 4); exit +r(0.3, 0.8) | 0.6 to 1.1 | "a slider that falls apart" |
| MOVER_Z | CG:3227-3234 | MOVE_Z w 3 at y0 + 0.3, depth amp 3.0 + 0.8 d, period r(4.6, 6.0); exit +r(0.3, 1.0) | 0.6 to 1.3 | "slides toward and away from the camera" |
| MOVER_V | CG:3219-3226 | MOVE_V w 3 at y0, rise 3.5 + 2 d, period r(5, 6.5), 1.0 m gaps; exit w 3 at y0 + rise | 3.5 to 5.5 | undocumented |
| ELEV_FALL | CG:3243-3250 | MOVE_V skin 1 w 3, rise 2.2 + 0.8 d, period r(3, 3.8); exit at y0 + rise | 2.2 to 3.0 | "an elevator that falls apart while you ride it" |
| PAD | CG:3251-3263 | 1 pad (2 pads if tier >= 1, d > 0.3, coin flip) w 2, gap lerp(1, 2, d) x r(0.9, 1.1); each pad raises the next by r(3.6, 5.0) x (1 - 0.03 attempt); exit w 3, gap 1.0 + 1.2 d | 1 pad: 3.6 to 5.0; 2 pads: 7.2 to 10.0 | undocumented |
| ROPE | CG:3264-3272 | rope bottom y0 + 0.4, L r(4, 6), at eR + lerp(1.4, 2.6, d); exit w 3 at rope.y - handHeight + 0.4 | L - 0.35 = 3.65 to 5.65 | undocumented |
| CABLE | CG:3273-3279 | cable at y0 + 2.6, span lerp(5, 8.5, d), start eR + r(1.0, 1.8); exit at cable.y - handHeight - 0.25 | 1.2 | undocumented |
| SWING | CG:3280-3288 | SWING w 2 at y0 - 0.35, len 5, amp 0.5 rad (arc extent 2.40 m, end rise 0.61 m), period r(3.6, 4.4); exit at y0 + r(0.5, 1.0) | 0.5 to 1.0 | undocumented |
| SPRING | CG:3289-3302 | SPRING w 2, angle r(0.32, 0.62) x (1 - 0.04 att), landing w 3.5 at dy r(-0.8, 2.8) (0 if unreachable), horizontal = vx0 x tf x r(0.86, 0.97) + 1.2 using padBounce 21 | -0.8 to 2.8 | undocumented |
| SEESAW | CG:3303-3313 | SEESAW w 7-8, dyIn r(-0.4, 0.3); exit w 3 at +r(-0.5, 0.6), gap lerp(0.5, 1.3, d) | -0.9 to 0.9 | "it tips toward you, so run across" |
| BRIDGE | CG:3314-3332 | STATIC skin 1 w 6-8, dy r(0, 0.9); 60 % from zone 1: rope L r(4.5, 6) over it, exit at rope.y - handHeight + 0.4; else exit at +0.6 | rope: dy + L - 0.05 = 4.45 to 6.85; else 0.6 to 1.5 | "sometimes a rope hangs above its middle to climb off to a higher block" |
| RAMP (flush) | CG:3333-3359 | L = 2.2 x r(1.3, 1.75) = 2.86-3.85 m, rise 0.576 L = 1.65-2.22; block flush at y0 + rise; ski jump +r(0, 0.7) | 1.65 to 2.92 | "a walkway up to a block that is taller than a jump" |
| RAMP -> TURBO | CG:291-299, 3349-3351 | skin 5, angle r(0.15, 0.55) rad, speed r(11, 15) m/s; landing deck w 4 (d < 0.3) / 3 at y0 + r(-1.0, 0.8) above the ramp top, at lerp(0.95, 0.7, d) of the full powered flight | ramp rise + (-1.0 to 0.8) = 0.65 to 3.0 | "harder climbs need the cancel" (CG:286-290) |
| RAMP -> TARZAN | CG:309-323, 3337 | rope len r(4.5, 6), amp r(0.55, 0.75) rad, period r(2.4, 3.0); pivot so the end hangs at y0 + handHeight + r(0.6, 1.0); landing w 3 at y0 + r(-0.5, 1.6) | -0.5 to 1.6 | "(up to 1.6 m higher: a release on the upswing gains height, and a swing that only travels sideways drifts toward the revolution below)" (CG:317) |
| GRAB | CG:3360-3365 | STATIC w 3 at dy r(0.7, 1.3), gap reach(dy) x (1.28 - 0.06 att) | 0.7 to 1.3 | owner A5 (fingertip grab) |
| LONG | CG:3366-3373 | weight 0 (CG:2711): never generated | - | "long walkable corridors deferred" (CG:2711) |
| FORK (route part) | CG:3179-3190 | entry w 4 dy r(0, 0.4); `forkRisers` risers (NORMAL 3, PUN/NGH 5; DP:72-87) w 2.5 at ye + 0.4 + 1.8 i/(n-1), gaps 2.5-2.8 then 1.5-1.8; rejoin w 4 at ye + 2.8 | 2.8 to 3.2 | "a wide gap to drop through, a gentle climb with full headroom beneath it" (CG:26-29) |
| HAZ | CG:3019-3099 | 1-3 gaps; per gap dy r(-0.3, 0.6) (saw: <= 0.1); gap clamp [2.3, 3.05] (saw <= 2.6); decks w 2.5 (last 3-4) | -0.9 to 1.8 | "One or two hazard-guarded gaps" |
| TRAP (buildMid) | CG:3102-3151 | one deck w 8 (crabs 9 + 2(n-2)) at dy r(0, 0.6) | 0 to 0.6 | "A wide platform with a hazard in the middle" |
| MORTAR | CG:2869-2897 | 5-6 decks (+2 with late()); gap clamp [2.2, 3.0]; w r(2.4, 3.2), last 3.5. Rise per deck 0 (NORMAL, PUNISHER) or r(0.2, 0.45) (NGH) | 0 (NGH 1.0 to 3.6) | "NGH rows rise MORTAR_RISE0-1 m a deck" (CG:2841-2846) |
| DROPS | CG:2956-3016 | 3-5 decks w r(5.0, 5.8); first gap reach(0.2) x lerp(0.45, 0.75, d), then r(1.1, 1.7); dy first r(0, 0.4), then r(-0.2, 0.3); optional intermediate w r(4.0, 4.4) at CRAB_HOP_DY + r(0.06, 0.14) = 1.86-1.94 above (crabs below pursuit height) or r(-0.2, 0.3); closing w r(3.0, 3.8) at r(-0.1, 0.3) | about -0.9 to 1.9 | "the crabs' platform is raised just past the crabs' hop above its neighbours" (CG:2964-2967) |
| CREATURE | CG:2904-2933 | skull deck w 8-9 / blob w 7-8 at dy r(0, 0.6); skull exit w r(3, 3.6) at r(-0.1, 0.4); blob-on-seesaw w 7-8 dy r(-0.4, 0.3), exit r(-0.5, 0.6) | -0.9 to 1.0 | "a creature's deck" |
| WET | CG:2826-2832 | one STATIC w r(2.4, 3.0) (35 %) or r(4.5, 5.5), dy r(0, 0.6) | 0 to 0.6 | "A wide platform under a rain cloud" |
| Rest | CG:1889-1908 | w 5-6 (attempts 0-3), 4, 3, then 4; dy r(0.3, 1.0) x (1 - 0.08 att), attempts >= 8 r(-0.8, 0.2); gap reach(dy) x (0.5 - 0.03 min(att, 7)); if none fits: climb first (PAD, MOVER_V, STAIRS, ROPE) | -0.8 to 1.0 | "no room at this height: climb first, then try to rest" |
| Exact castle | CG:765-797 | rest, then k = ceil(R / 1.25) (+1 at 40 %) stairs, each part <= 1.3 m, w r(2.2, 4.0), gap reach(part) x r(0.35, 0.72); deck w 9 skin 2 at exactly castleTarget; gate len 7.5, w 3.4; R in [0.05, 7.5] | R <= 7.5 | "a staircase of small steps that lands the deck at EXACTLY castleTarget" (CG:764) |
| Castle approach guard | last platform <= castleTarget - 1.6 (exact) / + 1.0 (gem) | CG:756-762 | | "keeps the last platform at least 1.6 m below the castle deck" (CG:755) |
| Enforcer encounter | CG:901-920 | len (3-5) decks w r(3.2, 5.2), exit w r(3, 4.5); gaps r(1.3, 2.6) x (1 - 0.08 att); dy r(-0.2, 0.8) each | -(len+1) x 0.2 to (len+1) x 0.8 | "clean, solid, flat decks it can hop between" (CG:896-899) |
| Start platform | STATIC w 7 at s 4, y 0 | CG:332, 348 | | | 

**Rise per arc (derived here, not measured)**: with each random range at its midpoint and d = 0.5, module rise divided by the arc it consumes
(from the previous +s edge to the new last +s edge): ROPE ~0.69, MOVER_V ~0.56, PAD (1 pad) ~0.53, BRIDGE+rope ~0.50, ELEV_FALL ~0.33,
STAIRS ~0.32, RAMP ~0.28, GRAB ~0.15, HOP tier>=1 ~0.14, FORK route part ~0.12, CABLE ~0.11, MOVER_Z ~0.11, CRUMBLE ~0.10, REST ~0.09,
MOVER_H ~0.08, HOP tier 0 ~0.08, BRIDGE (no rope) ~0.08, NGH MORTAR ~0.07, SWING ~0.06, CREATURE ~0.03, TRAP ~0.03, HAZ ~0.03, DROPS ~0.02,
SEESAW 0, MORTAR (NORMAL/PUNISHER) 0. The pitch band needs an average of 0.159-0.255 (sec. 1), so only ROPE, MOVER_V, PAD, BRIDGE+rope,
ELEV_FALL, STAIRS and RAMP climb faster than the band; every hazard, creature and mortar/drops row flattens the spiral.

## 2.4 Side structures (decoys, forks, key rooms) that sit between revolutions

| Item | Value | Where | Kind | Purpose |
|---|---|---|---|---|
| Fork entry room (route) | forkRoom >= 2.2 m gap, or next element > 2.1 m above | CG:1618-1623 | hard | "its top beyond the hands' reach: nothing to catch on the way down" (CG:1620) |
| ceilAt | below every element within arcHalf + 1 + 1.2 m whose vHi > y0 - 12 and y < y1 + 3: ceil = vLo - 0.9 | CG:1625-1629 | hard | "below every element over it by the overlap clearance" (CG:1624) |
| Reserved fork branch | first at x0 + 1.7, last landing at padC - 3.6; widths 3 / 2.4 / 1.4 (PUNISHER middle); gaps >= 1.1; y = min(y0 - 3.2 -> J.y - 5.4 ramp, ceil - 1.7, prev + 1.2); pad <= ceil - 0.2, >= J.y - 5.6, J.y - pad <= 7.0; hop spacing `branchHop` 4.4 (N) / 3.4 (P, NGH) | CG:1538-1579; DP:72-87 | hard+heur | "drop through the wide gap ... hop along under the risers, and bounce off a pad" (CG:1534-1536) |
| Opportunistic fork | branch y <= ceilAt - 1.7 (minus up to 0.3), step <= +1.5, start y - 3.2 toward tg.y - 6.6; w 2; steps every ~3.6 m; pad y in [tg.y - 7.4, ceil - 0.2], tg.y - pad <= 7.6; pad under a >= 1.2 m gap or 1.9 m past a deck 2.4 m lower | CG:1655-1723 | hard+heur | "0.9 m clearance + 1.7 m = the body (1.25 m) and a low hop" (CG:1691); "(held, it rises about 8.5 m)" (CG:1697) |
| Pad fork | pad at start.y - 2.6 - r(0, 0.6), <= ceil - 0.2; target tg.y - pad <= 7.6, \|ds\| - halfW <= 4.5 | CG:1586-1615 | hard | "it drifts about 4.5 m" (CG:1592) |
| Dead-end spurs | 2-3 (long 4-6) steps; up r(0.2, 0.8), down -r(0.3, 1.0); left spur first two steps -r(1.0, 1.4) or +r(1.4, 1.7); gap reach(\|dy\|) x r(0.40, 0.60) | CG:1747-1800 | heur | "a left spur ... climbs or drops steeply first, so it never hangs low over a route platform" (CG:1784) |
| Lures | 2-3 blocks w 1-2 at p.y + r(3.2, 5.6), behind (-s) | CG:1791-1797 | heur | "scattered blocks that look like stepping stones but lead nowhere" |
| Bait ledges | w 4.5 at p.y - r(0.2, 0.5) with a SPIKE_TRAP; or two decks with SAW_V amp 2.0 | CG:1755-1770 | heur | |
| decoyOk same-revolution | overlap: edge gap < 1.0 and d.y - 0.6 < vHi - 1.6 and d.y + 1.6 > vLo + 0.8; rope: gap < 1.2, d.y in (rope bottom - 0.5, rope.y + 2.2); no ceiling over a route deck: arc < sum halfW + 0.3 and d.y in (e.y + 0.5, e.y + 3.9); no stepping stone toward later route: gap < 6.5 and e.y - d.y in (-7, 3.6) | CG:1820-1824, 1843-1848 | hard | CG:1822-1824 quotes |
| decoyOk launch corridor (any revolution) | no decoy within 10 m + d.w/2 of arc and from e.y - 1.5 to e.y + padBounceHeld^2/2g + 2.8 = e.y + 11.27 m of any PAD/SPRING | CG:1837-1842 | hard | "the held launch's apex plus the standing room a body needs under a deck, 2.8 m as in the layer rules" (CG:1834-1836); DEC:825 |
| corridorOk (dead ends) | route PAD/SPRING a-4..a+6: 10 m + d.w/2, -1.5..+9 m | CG:1853-1860 | hard | CG:1852 |
| Key room | key deck w 3 at R.y - r(1.25, 1.65) beside, or a pad at R.y - r(3.6, 5.6) + deck 2.8 m on; key at deck + 0.95 | CG:1401-1419 | heur | "mostly under the route ahead" (CG:1404) |
| Key on route | key at A.y + 0.95; keyClear: no platform 0.2-2.6 m above the deck overlapping it | CG:1316-1332 | hard | "Nothing hangs low over route platform a" (CG:1315) |
| Continuation probe | two STATIC w 3 steps, gap {2, 3} + 1.5, rise {0, 0.6, 1.2}, against layersClash | CG:469-497 | hard | "only rises its ordinary modules make without launching or hanging anything below them" (CG:464-468) |
| Gem | GEM_MAX_DEV 4.5 m, GEM_MIN_W 3.5 m, GEM_LEAD 4 m; checkpoints thinned below 0.45 x gemSpacing = 13.5 m | CG:1252, 1276, 1278, 536 | hard | CG:1251, 1277 |
| Avalanche trigger deck | STATIC, w >= 2.5, nothing anchored, not within castleTarget - 30 | CG:988-993 | hard | CG:987 |

---

# 3. Player movement physics and derived reach

## 3.1 Inputs

| Item | Value | Units | Where | Consumers | Config | Kind | Purpose | Affects |
|---|---|---|---|---|---|---|---|---|
| runSpeed | 5.6 | m/s | TJ:3; TU:8 | SIM:1920, 1987; reach() CG:277 | TJ | hard | undocumented | G |
| groundAccel / groundDecel | 48 / 60 | m/s^2 | TJ:3 | SIM:1923-1924 | TJ | hard | undocumented | G |
| airAccel / airDrag | 28 / 3 | m/s^2 | TJ:3 | SIM:1990-1991 | TJ | hard | "launched faster than you can run ... only drag eases it" (SIM:1991) | G |
| gravity | 34 | m/s^2 | TJ:4 | SIM:1994; generator reach(), turbo, tarzan, corridor | TJ | hard | undocumented | G |
| jumpVel | 11.5 | m/s | TJ:4 | doJump SIM:1909-1912 | TJ | hard | undocumented | G |
| jumpCutMul | 0.45 | - | TJ:4 | release caps vy at 5.175 m/s (SIM:1996) | TJ | hard | undocumented | G |
| maxFall | 24 | m/s | TJ:4 | SIM:1995 | TJ | hard | undocumented | G |
| coyote / jumpBuffer | 0.12 / 0.13 | s | TJ:5 | SIM:635, 1937, 1998-2001 | TJ | hard | undocumented | G |
| Simulation step | DT = 1/60 | s | SIM:10 | semi-implicit Euler: vy -= g dt, then y += vy dt (SIM:1994, 2004) | const | hard | | G |
| handHeight | 1.15 | m | TJ:6; TU:12 | grabs, ropes, cables, ledges (SIM:2184, 2210, 2239, 2250, 2341, 2358) | TJ | hard | undocumented | G |
| ledgeReachX | 0.4 | m | TJ:10 | ledge catch needs edgeDist in [-0.12, 0.4] (mantle [-0.45, 0.4]) (SIM:2228) | TJ | hard | undocumented | G |
| ledgeReachBelow | 0.9 | m | TJ:10 | **hands may be up to 0.9 m below the top** (SIM:2223, 2148) | TJ | hard | undocumented | G |
| ledgeReachAbove | 0.15 | m | TJ:10 | hands more than 0.15 m above the top -> mantle (step-up) instead of hang (SIM:2224) | TJ | hard | "the hands are already above the top" (SIM:2224) | G |
| Ledge grab speed gate | vy <= 3.5 m/s | m/s | SIM:2217, 2147 | | const | hard | "so a leap past a ledge is not snatched" (SIM:2123) | G |
| pullUpTime | 0.32 | s | TJ:10 | SIM:2374 | TJ | hard | | G |
| grabLockout | 0.28 | s | TJ:9 | after rope/cable/ledge release | TJ | hard | | G |
| KNOCK_LOCKOUT | 0.3 | s | SIM:155, 404 | no grabs after a hazard knock | const | hard | "knocked off a rope, the climber re-grabbed it the next frame" (SIM:154) | G |
| Assists | assistJumpForgiveness 0.08 s, assistSlowFactor 0.8 | s / - | TJ:18 | added to coyote/buffer | TJ | hard | | G |
| Platform jump boost | jump vy += 0.6 x max(0, platform vy); vx += 0.6 x platform vs | - | SIM:1939-1949 | | const | hard | undocumented | G |
| swingSuper / swingSuperVx | 5.5 / 3.5 (x frac^4) | m/s | TU:20 (TU default only) | SIM:1944-1947 | TU default | hard | "jumping from the top of a swing's arc catapults you" (TU:20) | G |

## 3.2 Derived vertical reach (computed here from the values above; discrete = the Sim's 60 Hz integration, continuous = v^2 / 2g)

| Launch | vy (m/s) | Continuous apex (m) | Discrete apex (m) | Source of vy |
|---|---|---|---|---|
| Jump | 11.5 | 1.945 | **1.850** | TJ:4; T5:36 "the apex stays 1.85 m" |
| Jump released early | 5.175 | 0.394 | 0.351 | jumpCutMul |
| Rope jump | 11.5 - 0.12 x 11.5 = 10.12 | 1.506 | 1.422 | SIM:2257 |
| Cable jump | 11.5 - 0.25 x 11.5 = 8.625 | 1.094 | 1.023 | SIM:2347 |
| Pad bounce | 21 | 6.485 | 6.311 | TJ:7 padBounce |
| Pad bounce, jump held | 24 | 8.471 | **8.272** | TJ:7 padBounceHeld |
| Spring held, angle 0.32 / 0.62 rad | 22.78 / 19.53 | 7.632 / 5.611 | 7.443 / 5.449 | SIM:2163 (v cos(angle)) |
| Swing super-jump (frac 1) | 17.0 | 4.250 | 4.108 | SIM:1946 |
| Blob launch off a seesaw | 11.5 x sqrt 3 = 19.92 | 5.835 | 5.669 | EL:245, SIM:1568 |
| Shaking ramp throw (max) | 10 + 3 x 1.2 = 13.6 | 2.720 | 2.607 | SIM:1975 |
| Slab bounce | 6 | 0.529 | 0.481 | SIM:28 |
| Tarzan release (rope vy excluded) | +0.55 x 11.5 = 6.325 | 0.588 | 0.536 | SIM:72, 2279 |
| Timed rebound (added) | +11.5 x (0.5..1.0) | up to 1.945 | up to 1.850 | SIM:80, 414-421 |

Ledge reach above the feet (from SIM:2223-2228): a hang catch needs `hand >= top - ledgeReachBelow`, i.e. **top <= feet + handHeight +
ledgeReachBelow = feet + 2.05 m**; a mantle needs feet in (top - 1.0, top - 0.05). So:

| Quantity | Value | Derivation |
|---|---|---|
| Max landing height, plain jump | 1.85 m | discrete apex |
| **Max ledge catch, plain jump** | **3.90 m** | 1.85 + 2.05 |
| Measured plain reach (T5, 0.25 m steps) | 3.75 m (4.0 m never) | T5:46; CG:2045; LST:93-95 |
| Max ledge catch, held pad | 10.32 m | 8.272 + 2.05 |
| Measured pad reach | 10.25 m | T5:56; CG:2051 |
| Documented ledge reach (comments) | "handHeight + ledgeReachAbove = 1.3 m above them, 3.25 m in all" | CG:1939-1940; DEC:768-769; LST:78 (see sec. 9 Q1) |

## 3.3 Generator jump-reach helper

`reach(dy) = runSpeed x 0.92 x t - 0.1`, t = (v + sqrt(v^2 - 2 g dy)) / g, continuous, "Conservative horizontal reach (edge to edge) of a plain
jump to a platform dy higher, without coyote/overhang bonuses" (CG:271-278). Values (derived): dy 0 -> 3.385 m; 0.2 -> 3.293; 0.4 -> 3.196;
0.6 -> 3.092; 0.8 -> 2.980; 1.0 -> 2.857; 1.2 -> 2.721; 1.4 -> 2.565; 1.6 -> 2.376; 1.8 -> 2.118; 1.9 -> 1.907; 0 beyond 1.945.
Used by HOP, STAIRS, BRIDGE, SEESAW, CREATURE, TRAP, HAZ, MORTAR, DROPS, WET, GRAB, rests, castles, spurs (CG:2.3 lines). Kind: heur (sizes gaps);
the Sim proof is the hard check. EM:222-224 has an identical copy.

---

# 4. Special traversal envelopes

| Mechanism | Envelope (values) | Where | Config | Purpose | Affects |
|---|---|---|---|---|---|
| Rope | grab if \|arc\| <= ropeGrabRadius 0.42 and hand in [rope bottom, rope.y + 0.1]; climb 3.4 m/s; feet range rope.y - 1.15 down to bottom - 0.2; top -> BEAM | SIM:2200-2205, 2245-2264; TJ:9 | TJ + const | "pushing on at the very top: haul up from behind the beam and stand on it" (SIM:2261) | G |
| Rope beam | stand at rope.y + 0.24, walk +-1.1 m at 0.55 runSpeed, jump plain (feet apex rope.y + 2.09, ledge catch to rope.y + 4.14, derived) | SIM:2317-2337 | const | SIM:2316 | G |
| Cable | grab if within span, hand in [y - 0.45, y + 0.15] and vy < 4; shimmy 3.6 m/s; hang y - 1.15; drop at span + 0.1 | SIM:2206-2213, 2339-2352; TJ:9 | TJ + const | | G |
| Tarzan | catch on the rope line: along in [0.6, len], perp <= 0.42; climb 0.5 x 3.4 m/s, min grip 0.8 m; release vy += 0.55 jumpVel keeping rope velocity; re-catch lockout 1.0 s. Rope speed at the end (derived): len x amp x 2 pi / period = 5.2 to 11.8 m/s | SIM:61, 72, 2189-2197, 2272-2292; CG:309-323 | const | "when you let go decides where you fly" (SIM:2270) | G |
| Turbo ramp | launch at turboAngle 0.15-0.55 rad, 11-15 m/s; TURBO_POWER 0.55 s at TURBO_GRAVITY 0.3 g, no drag/steer; cancel keeps 0.35 of vx. Derived flight: apex 0.12-2.78 m above the ramp top, 1.6-7.7 m along; 1 m below the top at 6.7-13.9 m along | SIM:69, 1957-1963, 1988-1994; CG:291-299 | const | "a long, flat, fast flight" (SIM:63-67) | G |
| Pad / spring launch corridor (hazards) | column: \|ds\| < w/2 + hzHalf + 1.2 (pad) / 2.5 (spring), from e.y + 0.3 to e.y + 8.47 + 1.8 = e.y + 10.27; flight: toward the landing deck (\|to\| <= PAD_FLIGHT_MAX 12 m) up to \|to\| + land.halfW + hzHalf + 0.5, height up to max(e.y, land.y) + 10.27 | CG:2184-2194 | const + TJ (padBounceHeld, gravity) | "a launch climbs ~8.5 m ... so no hazard or creature goes in the column above it"; "the launch drifts forward to the deck it lands on" (CG:2177-2182); DEC:800 | G |
| Moving platforms | MOVE_H offset amp sin; MOVE_V rises 0..amp (0.5 amp (1 - cos)); SWING s = len sin(amp sin), y rise len (1 - cos); MOVE_Z depth amp sin | EL:359-391 | const | | G+R |
| Mover amplitudes generated | MOVE_H 1.4-2.4 m; SLIDE_FALL 1.2-2.0; MOVE_V 3.5-5.5; ELEV_FALL 2.2-3.0; MOVE_Z depth 3.0-3.8; SWING len 5 amp 0.5 | CG:3211-3250, 3280-3288 | const | | G |
| Spike-drop slab ride | slab top dropTop = dropBottom + DROP_H (1.0), rides up to y + amp + 1.0 (rows: amp 2.5-3.6 -> top 3.5-4.6 m above the deck; TRAP crusher amp 3.3 -> 4.3); a fall faster than 6 m/s throws you off at 6 m/s | EL:202-214; SIM:28, 2030-2067; CG:2950, 3004, 3133 | const | "The top of a spike slab is a surface" (SIM:2029) | G |
| Seesaw | tilt to 0.55 m/m toward the stander; slide 5 m/s; blob landing at the far end launches 1.732 jumpVel | TJ:14; SIM:1567-1571 | TJ + const | | G |
| Shaking ramp | every RAMP_SHAKE_EVERY 0.25 s a throw vx 3.0-5.4, vy 10-13.6 | SIM:535, 1972-1977 | const | "hard enough to make you fight for it" (SIM:1975) | G |

---

# 5. Hazard and enemy envelopes

## 5.1 Lethal / footprint box used by the generator (`hzS`, `hzHalf`, `hzLo`, `hzHi`, CG:2118-2153)

| Type | hzS (arc centre) | hzHalf | hzLo | hzHi | Real motion (Sim/Element) | Box covers real envelope? |
|---|---|---|---|---|---|---|
| SAW_H | s | amp + 0.55 | y - 0.55 | y + 0.55 | sweeps +-amp (EL:363); gap saw y = top + 0.62..0.82, amp 0.2-0.45 (CG:3072-3073); floor saw y = deck + 0.62, amp 1.1 (CG:3126-3127) | yes |
| SAW_V | s | 0.55 | y - 0.55 | y + amp + 0.55 | rises 0..amp (EL:372); y = top + 0.35, amp 1.5-2.3 (CG:3058-3059) | yes (to top + 3.2) |
| PENDULUM | s | len sin(amp) + 0.5 | y - 0.5 | y + len (1 - cos amp) + 0.5 | ball at lowest y = top + 0.65..0.8; len 1.1-1.4 - 0.15 intensity; amp = asin(sweep/len); period 2 pi sqrt(len/9.81) (CG:3067-3069) | ball yes; chain and pivot (y + len) not in box (drawn, WR:1185-1189) |
| CANNON | s + dir len/2 | len/2 + 0.42 | y - 0.42 | y + 0.42 | muzzle cur.s - cur.w/2 + 0.7 at cur.y + 2.35; len = lane to past the gap + 1.2; flight 0.7 of period; **every ball flies CANNON_BEYOND 14 m past the lane and hits**, CANNON_TRAIL 4 balls (EL:141-166; SIM:1875-1884; CG:3081-3083) | **no**: the 14 m beyond the lane is outside the box (sec. 9 Q10) |
| AIMED | s | 0.6 | y - 0.4 | y + 0.6 | turret at cur.y + 2.0; wakes within AIM_RANGE 15 m; arms only for a player dy in [-4, 5]; pitch -0.35..0.5 rad; ball from AIM_MUZZLE 0.84 at 9 m/s for 1.6 s = 15.24 m (EL:170-176; SIM:1803-1842) | **no**: ball path (up to ~+7.3 / -5.2 m, derived) not in box |
| MORTAR | s | 0.42 | y (muzzle, deck - 3.0) | y + amp + 0.42 | amp = MORTAR_DEPTH + rise + 2 height = 5.5 + rise; ball apex 2.5 + rise above the lower deck; MORTAR_FLIGHT 0.82; lethal only above y + 0.35 (EL:168-185, 337; CG:2890-2891) | ball yes; barrel and drawn column below the muzzle not in box (sec. 7) |
| SPIKE_TRAP | s | w/2 (1.2) | y | y + 0.7 | spikes up to 0.62 (EL:193-199) | yes |
| SPIKE_BLOCK | s | w/2 | y | y + len | gap block: y = top - 1.1, len 1.3, w 0.9-1.3; deck block y = deck - 0.1, len 1.0, w 1.3 (CG:3078, 3129) | yes |
| SPIKE_DROP | s | w/2 (0.8 / 0.9) | y | y + amp + 1.0 | slab bottom y..y + amp, DROP_H 1.0, spikes lower 0.5 (EL:202-214); rows amp 2.5-3.6 (CG:3004), crusher 3.3 (CG:3133) | yes |
| GATE | s | w/2 (1.7) | y | y + len (7.5) | castle door wall; excluded from I4 (`physical`, GI:107) | n/a (sec. 9 Q15) |
| CRAB | s | amp + 0.6 | y | y + 0.7 | patrol +-amp; chase on deck; follows CRAB_FOLLOW 3 decks, hops gaps <= CRAB_HOP_GAP 3.2 m, \|dy\| <= CRAB_HOP_DY 1.8 m in 0.55 s, 1.0 m high; falls with a crumbled deck for 1.5 s (SIM:1597-1615) | own deck only; follow decks not in box |
| BEE | s | amp + 0.6 | y - len - 1.4 | y + len + 0.6 | flies in from +-14 m arc and +4 m (EL:320-330); drift BEE_DRIFT 2.2 m/s up to BEE_REACH 2.4 and the decks either side (EM:164); seeks within BEE_SEEK 7 m, dy -4..3 (SIM:744); dive (EL:217-223) | **no** (non-lethal shove) |
| SKULL | s | w/2 (deck) | y | y + 2.3 | hovers SKULL_HOVER 1.75 above deck, loop radius 0.6; dash at 0.55; roams to neighbouring route decks within ROAM_DY 3.0 m at SKULL_FLY 8 m/s (EL:226-236; SIM:808-885) | own deck; roaming not in box |
| BLOB | s | w/2 (deck) | y | y + 2.3 | leap to BLOB_APEX 11 m above its deck (EL:239, 312-315) | **no** by design: "the blob's leap goes off the top of the screen and is not standing-room clutter" (CG:2149) |
| ENFORCER | s | ENF_HALF + 0.2 = 0.6 | y | y + 1.6 | chases len (3-5) decks; hop gap <= 3.6, dy <= 2.2, peak 1.0; swat jump 12.5 m/s (~2.2-2.3 m); exit leap 13 x (0.8-1.2) m/s (apex up to ~3.5 m, gone at 10 m or 2.5 s); a fall lasts ENF_FALL_T 2 s (EL:248-309; SIM:896-1031) | waiting footprint only |
| AVALANCHE | s (trigger deck) | w/2 | y | y + len (len = block count 2-12) | blocks spawn AVL_DROP 11 + up to 2.5 m above decks 1-4 ahead (or the climber's lead up to 12 m), fall to AVL_FLOOR 16 m below, life 12 s, collide only with route decks -8..+8 of the trigger (SIM:1177-1186, 1205-1216, 1324-1331; EL:275-307) | **no** (S1 sec. S1-C: "The box is the wrong shape: too narrow") |
| default | s | w/2 | y | y + len | | |

## 5.2 Hazard placement rules (all `hard`, `G`)

| Rule | Condition refused | Where | Purpose |
|---|---|---|---|
| Same revolution, platform | arc < halfW + 0.35 + hzHalf and hzHi > y - 0.3 and hzLo < y + 1.5 (owner deck exempt) | CG:2288-2291 | "A hazard must not touch standing room on its own spiral layer (except the platform a trap sits in)" (CG:2155) |
| Same revolution, rope/cable/Tarzan | arc < arcHalf + 0.35 + hzHalf and hzHi > vLo - 0.3 and hzLo < vHi + 0.3 | CG:2292 | as above |
| Other revolution (placement) | \|dsWrap\| < hzHalf + arcHalf + 2.0 and hzLo - 1.5 < vHi and hzHi + 1.5 > vLo | CG:2293 | "and must keep clear of other layers" (CG:2155) |
| Hazard vs previous slice's hazards | arc < hzHalf + hzHalf' + 1.5, vertical +-1.5, other revolution | CG:2296 | undocumented |
| I4 (audit and placement) | hazard box inside another revolution's body box, or two revolutions' hazard boxes overlap - **no margins** | GI:109-117, 41-47, 52-66; CG:2579-2581 | "a hazard's lethal box never lies inside the body of another revolution, nor inside another revolution's hazard" (GI:14) |
| insertionClear (Enforcer, avalanche) | corridors + I4 + other revolution arc + 2.0 / +-1.5 | CG:2253-2266 | "the checks every hazard gets at placement" (CG:2248) |
| Launch corridor (placement and slice audit) | `corridorBlocked` for any pad/spring of another revolution | CG:2185-2240 | sec. 4 |
| Saws vs creatures | no SAW_H within a roaming creature's reach or beside a cannon lane | CG:2339-2348 | "a creature that roams onto a saw's link cut its timing window" (CG:2336-2337) |
| Protected rows | no crab/bee/skull/blob on or (below pursuitFromY) reaching a deck with SPIKE_DROP, MORTAR, CANNON, AIMED | CG:2353-2375 | "Owner structural rules (2026-10-26)" |
| Hazard-free start | intensity 0 below hazardStartY 40 m (TU default 90) | TJ:16; TU:29; CG:239-242, 630 | "the first hazardStartY metres stay free of hazards and creatures" (CG:630) |

## 5.3 Push / knock velocities applied to the climber

| Source | vx (m/s) | vy (m/s) | Lockout (s) | Where | Discrete rise from rest (derived) |
|---|---|---|---|---|---|
| Hazard hit (saw, spikes, pendulum, cannon, mortar, aimed, avalanche) | +-6.9 | = 6.9 (set) | 0.3 (KNOCK_LOCKOUT), invuln 0.7 | TJ:13; SIM:400-407 | 0.643 |
| Crab shove | +-9 | max(vy, 6) | 0.3 | SIM:707-710 | 0.481 |
| Bee | +-8 | max(vy, 5) | 0.25 | EL:221; SIM:726-729 | 0.327 |
| Skull dash | +-14 | max(vy, 9.5) | 0.35 | EL:233; SIM:865-867 | 1.249 |
| Blob squash | +-9 | max(vy, 7) | 0.4 | EL:243; SIM:1574-1577 | 0.663 |
| Blob seesaw launch | - | 19.92 | 0.2 | SIM:1567-1571 | 5.669 |
| Enforcer kick | +-9.5 | max(vy, 6.5) | 0.3 | EL:254; SIM:961-966 | 0.568 |
| Enforcer swat | +-8 | min(vy, -2) | 0.3 | EL:257; SIM:992-995 | (downward) |

---

# 6. Cross-revolution rule set: exact decision procedure

## 6.1 `layersClash(c, e, f)` (CG:1947-1963), in evaluation order

1. **Same revolution?** If \|e.s - f.s\| < C/2 (31.416 m raw) -> **false** (CG:1948). "same revolution: handled by route order".
2. **C1 measured reach** `reachShortcut` (CG:2033-2043), only if both are platforms (`isPlatform`: not ROPE, CABLE, TARZAN or a hazard; EL:93) and
   C1_OFF is false (sysprop `climb.c1Off`, CG:2032):
   - lower = lower y, upper = the other; dy = upper.y - lower.y; if dy <= 0 -> no C1 clash.
   - gap = \|dsWrap(e.s, f.s)\| - arcHalf(lower) - arcHalf(upper) (edge-to-edge, arcHalf includes a MOVE_H's travel and a SWING's arc).
   - r = reachAt(lower, gap) (CG:2003-2019): table by lower type (PAD, SPRING, SWING, RAMP, SEESAW, MOVE_H; MOVE_V uses amp-dependent
     {(-3..3, a + 3.75), (4.5, a + 2), (6, a - 2.5)}; everything else - STATIC, CRUMBLE, GOAL, MOVE_Z - REACH_PLAIN).
   - `envelope` (CG:2024-2029): r = max of table reaches whose gap is within 1.5 m of `gap`; a gap below the first entry also takes the first;
     -1 if nothing is within 1.5 m.
   - **clash if r > 0 and dy < r + SHORTCUT_MARGIN (0.5 m)** -> **true** (CG:1949). No arc gate: C1 looks as far as the table reaches.
3. **Arc gate.** If \|dsWrap\| > arcHalf(e) + arcHalf(f) + 2.5 (edge gap > 2.5 m) -> **false** (CG:1950).
4. **Body clearance.** If vLo(e) - 1.5 < vHi(f) and vHi(e) + 1.5 > vLo(f) -> **true** (CG:1951). For an upper element above a lower one this
   is dy < dLo(upper) + dHi(lower) + 1.5 (sec. 2.2): plain over plain 5.1 m; over a MOVE_V amp + 5.1; over a SWING (len 5) 7.7 m; over a ramp
   ~6.0-6.2 m; a ROPE above anything: its bottom must be 4.3 m above a deck; a deck above a rope top: 2.9 m.
5. If either is not a platform -> **false** (CG:1952).
6. dy = \|e.y - f.y\|; if dy >= 9.5 -> **false** (CG:1954).
7. If dy < PLAIN_LAYER_DY (4.0) -> **true** (CG:1955). (Never decisive in practice: step 4 already refuses every platform pair below 5.1 m inside
   the same arc gate; S1:68 "The plain 4 m rule never refuses on its own".)
8. If `launches(lower)` -> **true** (CG:1957). launches = PAD, SPRING, SEESAW, RAMP, SWING, or any `isMoving()` platform (MOVE_H, MOVE_V,
   MOVE_Z, SWING) (CG:2083-2085). This is the **9.5 m blanket rule**: 4 <= dy < 9.5 within the arc gate.
9. If R17_OFF_SPACING (sysprop `climb.r17OffSpacing`, CG:153) -> return launches(upper) (the rules 16 "either platform" rule).
10. -> **false** (CG:1962). Rules 19 removed the rules 17 `liftsClimber` exception for skull and spike-drop decks (CG:1959-1962).

## 6.2 Variants

| Function | Steps | Where | Notes |
|---|---|---|---|
| `layersBodiesClash` | 1, 3, 4 only | CG:1988-1992 | used by I1 (GI:77) and by relaxed attempts against ghosts (CG:1986) - **relaxed attempts therefore also waive C1** |
| `forkLayersClash` | 1, 2 (C1), 3, 4, 5, then dy < (launches(lower) ? 9.5 : 4) | CG:2094-2102 | for fork branches; with R17_OFF_SPACING off it is equivalent to `layersClash` |
| `ghostClash` | relaxed ? layersBodiesClash : layersClash | CG:1986 | previous slice |

## 6.3 Where the layer rules are applied (who is compared with whom)

| Call site | Element tested | Compared with | Rule | Where |
|---|---|---|---|---|
| `clearOfOtherLayers(idx)` in `geometryOk` (every route module, rest, castle, Enforcer deck, gem widening) | each new element | this working course's elements 0..idx-4 (the 3 preceding are skipped), and every ghost | layersClash; ghostClash | CG:2107-2112, 2565-2569, 1267 |
| `decoyOk` (dead ends, lures, forks, key rooms) | each decoy | every element of the working course in another revolution; every ghost; the reserved continuation steps | fork ? forkLayersClash : layersClash; ghostClash; layersClash | CG:1813-1829 |
| `continuationSteps` / `stepClear` | probe steps after the closing rest | route from ctx-1 up to the last 3, and all side structures | layersClash | CG:481-497 |
| MORTAR_ADAPT (off) | lifted mortar decks | lower / any layers | layersClash, ghostClash | CG:2855-2868, 2882-2887 |
| Slice audit I1-I4 | every element/hazard added by the slice | this slice, its context and the ghosts | layersBodiesClash (no spacing), I2, I3, I4 | GI:28-49; CG:456-459 |
| Launch-corridor audit | every pad/spring vs hazards of another revolution (and ghost pads vs this slice's hazards) | | corridorBlocked | CG:2201-2219 |
| Element in a hazard box of another revolution | each new element | hazards placed before it, ghost hazards | GI.elementClear | CG:2579 |

Defined but never called at `fd8c0c0`: `liftsClimber` (CG:1972-1981), `clashesWithOtherLayers` (CG:1803-1807).

## 6.4 What the proofs see (dynamic checks)

- Link proofs simulate route elements a-2 .. a+6 plus decoys anchored near them, and hazards anchored a-3 .. a+3 plus cannons/aimed/bees/
  mortars/Enforcers/avalanches reaching in (AP:463-464; SIM:161-182; CO:73-95); `setWindowNear` adds earlier route platforms within 12 m of arc
  and 9 m of height (SIM:185-201). Elements one revolution away (about 11 route platforms back, T5:71) are **not simulated** by link proofs.
- The whole-slice witness and `chainClean` run with `act = null, hz = null` (AP:905, 949; SafeStart.java:29, 62) over the working course (this
  slice plus its 10-element context); the previous slice's ghosts are never simulated by any proof.
- The live game simulates the resident window (TW:21, +-300 m; SIM:206-216) with every hazard; hazards test the body with wrapped arcs
  (SIM:1868-1906), so only the vertical tests separate revolutions.

---

# 7. Visual-only geometry (renderer; collision unaffected unless stated)

| Item | Geometry | Where | In any generator box? | Purpose |
|---|---|---|---|---|
| Platform blocks | thick 1.0 m (w >= 5, GOAL) / 0.5 m, depth zs 1.7 m | WR:225-242 | yes (body vLo 0.8; slab() 0.5/1.0) | EL:91 |
| Castle deck plinth | deck depth 4.0 m; two plinth boxes centred at -h-0.35 (0.7 tall) and -h-0.95 (0.5 tall): drawn to 2.2 m below the deck top | WR:231-247 | no (vLo = y - 0.8) | "a stone plinth that widens toward the bottom, like the castle's footing" (WR:243) |
| Castle tower | `tower2` model at scale 0.4 on the gate's (s, y), door wall marker at y + 2.4 | WR:985-995, 1312-1323 | only via GATE hzHi = y + 7.5, which I4 ignores (GI:107) | "the castle with a door on both sides" (WR:989) |
| Hanging rocks (dusk, night) | under platforms, scaled so the bottom stays ROCK_CLEAR 3.2 m above any platform, castle or rope top below; none if a rope passes | WR:253-262, 447, 458-479 | no | "the bottoms of the big rocks get in the way... they cannot block platforms" (WR:446) |
| Mover blocks | 0.5 m (MOVE_H) / 0.3 m tall | WR:275-282, 441-444 | partly | WR:443 |
| Swing / pad / spring art | swing block at -0.2; pad 0.18 + 0.14 base; spring coils to ~0.66 m and an arrow at 1.6 m along the launch | WR:290-311 | no (spring art above y) | |
| Rope hanger | beam 2.5 x 0.26 at rope top (dz 0.45 behind lane), posts 1.7 m tall at dz 0.85 | WR:313-326 | rope body only (vHi y + 0.6) | "entirely behind the walking lane" (WR:318-320) |
| Tarzan hanger | same beam and posts, fixed | WR:328-336 | arc body | |
| Cable posts | 2.6 m posts at dz 0.6, centred 1.2 m below | WR:338-345 | no | "posts stand BEHIND the lane" |
| Side cannon | own slab (2.6 wide, 1.5 deep, top at h.y - 2.35 = deck level), pedestal CANNON_LIFT, cap 0.16, all at AIM_DEPTH 1.7 m behind the lane; body about 1.0 m of lane behind the muzzle (mouth 0.835 m along the barrel, laneUnits at depth 1.7) | WR:863-869, 949-966, 1197-1230 | no (depth behind the lane) | "How far BEHIND the walking lane every cannon's own slab stands" (WR:868) |
| Aimed turret | slab top h.y - 2.0, pedestal AIM_LIFT, at depth 1.7; ball glides from the mouth over BALL_SWOOP 1.6 m | WR:867-871, 1231-1269 | no | |
| `AIM_DEPTH` | 1.7 m, sysprop `climb.aimDepth` | WR:869 | - | as above |
| **Mortar column** | cannon tipped up, axle at muzzle - 0.835; cap 1.7 x 0.18 below the wheels; a **1.5 m wide, 2.0 m tall column in the play plane (dz 0)**: column from muzzle - 3.32 to muzzle - 1.32 m, i.e. **4.32 to 6.32 m below the deck the gap starts from** (derived: PK 0.37, CANNON_WHEEL_R 0.348) | WR:855, 863, 1270-1286 | no: hzLo = muzzle (deck - 3.0) | "a cannon sunk in the ground below the path with its cradle tipped straight up" (WR:1271). Being shortened on another branch, per the task statement (not visible at this commit) |
| Spike drop frame | two 0.1 m posts from deck to deck + amp + 1.6, top bar at y + amp + 1.5; slab drawn 0.5 m | WR:1449-1457 | frame no (box to y + amp + 1.0) | |
| Pendulum | beam 1.6 m and chain to the pivot at y + len, depth 0.25 | WR:1182-1190 | chain/pivot no | |
| SAW_V track | post from y - 0.6, height amp + 1.2 | WR:1191-1194 | partly | |
| Blob leap | drawn up to 11 m over its deck | EL:239, 312-315 | no (intended) | CG:2149 |
| Enforcer exit / fall | leaps to 10 m above its deck; falls 2 s past every lower revolution | EL:309; SIM:1018-1029 | no | |
| Crab fall | falls 1.5 s with a crumbled deck | SIM:696-699, 1732 | no | |
| Avalanche blocks | fall from 11-13.5 m above to 16 m below the trigger deck, +-30 m along (S1-C); pass through every element of other revolutions | SIM:1177-1186; S1:72-84 | no (box too narrow) | gameplay-relevant (blocks hit the climber) |
| Shadows | platform half depths 0.62-2.0 m | WR:1581-1589 | - | |

---

# 8. Rule vs measured reach (launchers that trigger the 9.5 m blanket rule)

The blanket applies only inside the arc gate (edge-to-edge gap <= 2.5 m), for 4 <= dy < 9.5 m, when the **lower** platform launches.
C1 applies at any gap up to the last table entry + 1.5 m. "Body" = the vertical threshold of rule step 4 for a plain STATIC upper deck
(dLo 0.8). "Blanket-only band" = dy refused by the 9.5 m rule and by nothing else (inside the arc gate), derived from the three thresholds.

| Lower type (launches) | C1 table | C1 max reach (m) | + 0.5 margin | vs 9.5 m | C1 arc range (edge gap) | Body threshold | Blanket-only band (gap <= 2.5 m) | T5 measured (T5:44-57) |
|---|---|---|---|---|---|---|---|---|
| PAD | REACH_PAD CG:2051 | 10.25 (gap 2.0) | 10.75 | **above** | <= 9.5 m | 5.1 | 8.75-9.5 only where gap < -1.0 (arcs overlap > 1 m; C1 there 8.25 + 0.5); none for -1 <= gap <= 2.5 (C1 >= 10.0) | 8.25-10.25 at 0-9 m |
| SPRING | REACH_SPRING CG:2050 | 8.5 | 9.0 | below by 0.5 | <= 12.5 m | 5.1 | 9.0-9.5 | 8.5 at 0-9 m |
| SWING | REACH_SWING CG:2049 | 6.75 | 7.25 | below by 2.25 | <= 8.6 m | 7.7 (len 5) | 7.7-9.5 | 6.5-6.75 |
| RAMP (all skins incl. turbo) | REACH_RAMP CG:2048 | 4.75 | 5.25 | below by 4.25 | <= 5.85 m | ~5.9-6.2 (amp x w/2 + 5.1) | ~6.0-9.5 | 3.5-4.75 (plain/shaking/spinning only; turbo not measured) |
| SEESAW | REACH_SEESAW CG:2047 | 3.75 | 4.25 | below by 5.25 | <= 4.0 m | 5.1 | 5.1-9.5 | 2.25-3.75 |
| MOVE_H (incl. SLIDE_FALL) | REACH_MOVE_H CG:2046 | 3.75 | 4.25 | below by 5.25 | <= 5.6 m | 5.1 | 5.1-9.5 | 2.75-3.75 |
| MOVE_Z | REACH_PLAIN CG:2045 | 3.75 | 4.25 | below by 5.25 | <= 6.0 m | 5.1 | 5.1-9.5 | 2.0-3.75 |
| MOVE_V (route, amp 3.5-5.5) | amp + 3.75 (CG:2012-2015) | 7.25-9.25 | 7.75-9.75 | below for amp < 5.25, above at amp 5.25-5.5 | <= 7.5 m | amp + 5.1 = 8.6-10.6 | 8.6-9.5 at amp 3.5; none from amp 4.4 | amp + 3.75 (3.5: 7.25; 5.5: 9.25) |
| ELEV_FALL (MOVE_V skin 1, amp 2.2-3.0) | amp + 3.75 | 5.95-6.75 | 6.45-7.25 | below by 2.25-3.05 | <= 7.5 m | 7.3-8.1 | 7.3-8.1 to 9.5 | amp 3: 6.75-7.0 |
| (not a launcher) STATIC / CRUMBLE / GOAL | REACH_PLAIN | 3.75 | 4.25 | n/a (4 m plain spacing) | <= 6.0 m | 5.1 | n/a | 2.0-3.75 |

Documented purposes: the 9.5 m rule - "no trampoline shortcuts between layers" (CG:1957); "keeps the full 9.5 m to the revolution above them"
(CG:2082); DEC:766-772 (rules 8, 2026-10-25: "the 9.5 m 'no trampoline shortcuts' spacing stays wherever either platform can launch the
climber"); R17:84-106 and CG:1942-1945 (rules 17, 2026-10-28: lower platform only); CG:1959-1962 (rules 19, 2026-10-29: no liftsClimber
exception); DEC:885 and DEC:904 ("an anti-shortcut preference"); C1 - CG:1993-2002, DEC:1338-1340, T5 sec. 3 ("CONFIRMED DEFECT", 15 / 33 / 44
shortcuts per course before C1); classification of the blanket as "Conservative beyond physics (reach 3.75-7.25 m)" for movers, seesaws,
ramps, swings and elevators - T5:110-114. S1 "Spacing refusals by rule" (S1:58-68): pad lower 10,530 / 22,112 / 28,736 refusals; horizontal
or depth mover lower 8,438 / 7,582 / 5,187 ("Wrong motion assumption"). DEC:1422-1427: at full height the layer rule refuses 37-81 % of NGH
hazard modules, "mostly missing by 0.25 m or less" against a launcher of the revolution below.

---

# 9. Open questions (source ambiguous, inconsistent or unmeasured)

1. **Ledge reach parameter.** The layer-rule derivation (CG:1939-1940, DEC:768-769, LST:78) uses handHeight + ledgeReachAbove = 1.3 m; the Sim
   catches a ledge whose top is up to handHeight + **ledgeReachBelow** = 2.05 m above the feet (SIM:2223, 2148). With the discrete jump apex
   1.85 m that is 3.90 m, which agrees with the measured 3.75 m at 0.25 m steps and with the pad's 8.272 + 2.05 = 10.32 vs measured 10.25.
   The 4 m plain spacing therefore has about 0.1 m of physical margin, not 0.75 m. The same substitution changes the `liftsClimber` comment's
   3.94 / 4.57 m (CG:1966-1969) to roughly 4.5 / 5.1 m (lockouts of 0.3 / 0.35 s reduce this; not measured). LST's `plainReachIsBelowFourMetres`
   asserts with the 1.3 m figure.
2. **spiralPitch 24** is in TJ:16 and TU:31 ("enforced by the generator") but no code reads it (only OTA:27's frozen list); DEC:939 says it
   "was never achievable".
3. **Tuning defaults differ from tuning.json**: zoneHeight 400 vs 1000, hazardStartY 90 vs 40, hazardRampY 1400 vs 950 (TU:26, 29 vs TJ:15-16).
   CG:259 says intensity "reaches 1 at about 1,170 m"; with TJ it is 40 + 950 = 990 m. moverCrumbleDelay, swingSuper and swingSuperVx exist only
   as TU defaults.
4. **Deck thickness**: body boxes use 0.8 m (CG:1927, GI:24), collision and drawing use 1.0 m for w >= 5 and GOAL (EL:92, WR:226-229), and
   castle decks are drawn to 2.2 m below their top (WR:243-247). The lowest 0.2 m (castle: 1.4 m) of such decks is outside every box.
5. **Seesaw**: vHi = y + 2.8 ignores the raised end (0.55 x w/2 = 1.93-2.2 m), while vLo = y - 2.8. Undocumented.
6. **Rope beam and other non-platform launch points**: ropes, cables and Tarzans are not platforms, so C1, the 4 m and the 9.5 m rules never
   apply to them; only the body rule does. From a rope's standing beam a climber catches a ledge up to rope.y + 4.14 m (derived), while the body
   rule only keeps another revolution's deck 2.9 m above the rope top. In reverse, a pad (8.27 m apex) under another revolution's rope or cable
   is only kept 4.3 m below the rope's bottom by the body rule, inside the arc gate only. T5:119-126: "Not yet tested: Rope and cable catches as
   cross-revolution targets".
7. **Turbo ramps** are RAMP skin 5, so C1 uses REACH_RAMP, measured on plain, shaking and spinning ramps only (T5:52). The derived turbo flight
   reaches 1.6-7.7 m along at its apex and 6.7-13.9 m along at 1 m below the top, past REACH_RAMP's 5.85 m range. Not measured.
8. **Tarzan release** (rope end speed up to ~11.8 m/s along the lane, derived) has no C1 table and no spacing rule. Not measured.
9. **Relaxed attempts waive C1** against the previous slice (CG:1986 uses layersBodiesClash); the comment (CG:1983-1985) predates C1 and speaks
   only of the 4 m / 9.5 m spacing. Whether waiving C1 is intended is undocumented.
10. **Cannon balls past the lane**: the I4/placement box covers the lane only (CG:2126, 2134, 2141), but balls fly and hit 14 m beyond it
    (EL:141-154; SIM:1875-1884). The aimed cannon's box is 0.6 m (CG:2128) while its ball flies 15.24 m at -0.35..+0.5 rad. Whether either can
    reach another revolution's body is not established (the aimed cannon arms only for a player within dy -4..5, SIM:1812).
11. **Body margins** (1.5 m vertical, 2.5 m arc): T5:110-114 and S1:50-56: "not head clearance"; "their real purpose is not yet established".
12. **Avalanche box** height = block count (S1-C: "an accident of the default case"; "The box is the wrong shape: too narrow").
13. **Mortar visual column** (sec. 7) sits in the play plane 4.3-6.3 m below the deck, outside the mortar's box and every layer rule; with a
    layer gap of 10 m the revolution below's standing room ends 7.2 m below, so the column clears it by ~0.9 m only when the gap is 10 m or more.
14. **Creatures across revolutions**: bee seek band dy -4..3 (SIM:744) and aimed-cannon tracking band dy -9..8 (SIM:1811) use wrapped arcs, so
    they can notice a climber of another revolution where cross-revolution platforms come close vertically (plain pairs as close as 4.18 m when
    more than 2.5 m apart sideways, DEC:770). Not measured.
15. **Castle tower volume**: the gate (len 7.5 m) is excluded from I4 (`physical`, GI:107), and the castle deck's body ends 2.8 m above it, so a
    later revolution's deck can sit 5.1 m above the castle deck, inside the drawn tower if the tower is taller than that. The tower model's
    height is not in the source (model file). hazardOk does check the gate box against elements existing at placement (CG:2283-2294), not ones
    built later.
16. **Margins differ between hazard checks**: placement other-revolution +2.0 arc / +-1.5 (CG:2293), insertionClear +2.0 / +-1.5 (CG:2262),
    ghost-hazard pairs +1.5 / +-1.5 (CG:2296), I4 no margin (GI:109-117). Rationale undocumented.
17. **Proof window**: link proofs do not simulate other revolutions (sec. 6.4); cross-revolution safety rests on the static rules alone during
    generation, while the live game simulates everything resident.
18. **Spike-drop ride**: a ridden slab top reaches 4.6 m above its deck (rows) - plus a jump and ledge, ~8.5 m (derived); T5 measured 4.5 m with
    a limited fixture. Owner decision (DEC:1341-1342) places it in "category C", outside the rules.
19. **MORTAR_ADAPT** (sysprop `climb.mortarAdapt`, off) is not part of `fd8c0c0`'s default generation; the transient `LAYER_MEASURED`
    experiment seen in the working tree is not in `fd8c0c0` at all.
