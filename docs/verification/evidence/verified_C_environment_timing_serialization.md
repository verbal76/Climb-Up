# Verification of audit C (environment, timing, serialization)

Read-only verification against HEAD c9aa5ae (working tree: only HANDOFF.md modified). No repository file changed, nothing committed, no Gradle test suite run.
Paths are relative to `core/src/main/java/com/hotatticgames/climbup/`. `S` = sim/Sim.java, `E` = sim/Element.java, `TW` = sim/Tower.java, `SC` = sim/SliceCodec.java, `IO` = sim/CourseIO.java, `C` = sim/Course.java, `CG` = sim/CourseGenerator.java, `RS` = RunSnapshot.java, `PS` = PlayScreen.java, `GAME` = ClimbGame.java, `RES` = ResumeState.java.
The current main sources were compiled fresh into `$S/verifyC/classes` (S = the scratchpad). Probe sources are in `$S/verifyC/src/com/hotatticgames/climbup/sim/` (VcCodec, VcCopy, VcGen, VcSeam, VcNear). Raw outputs are in `$S/verifyC/gen30.txt` and `$S/verifyC/near.txt`.

---

## 1. Verdict table

Line numbers in C were mostly still correct, or off by 1 to 2 lines. Corrections are noted where they matter.

### §0 Clock and phase model

| # | Claim in C | Verdict | Evidence / correction |
|---|---|---|---|
| 0.1 | There is one clock, `time`. It starts at 0, goes up by DT each step, and a respawn never resets it. | CONFIRMED | S:20, S:490, S:365-379 (no `time` write). Probe VcCopy (5). |
| 0.2 | `cyc` = frac(t/period + phase/2π) and `ang` = 2πt/period + phase. | CONFIRMED | E:141, E:310 |
| 0.3 | Movers report their displacement through sOffsetAt/yOffsetAt, and the renderer shares these functions. | PARTIAL | The sim side is confirmed (S:447, S:504, E:320-351). The renderer was not read, so the renderer half is UNVERIFIED. |
| 0.4 | Production simulates the whole resident world (setRange(0, n−1), and hz = hazardsFor(−6, size+5)). Narrow windows are used only by planners. | CONFIRMED | PS:133/143/153/487, TW:263, S:183. Planner windows: Autopilot.java:361-362. |

### §1 Element types

| # | Claim | Verdict | Evidence / correction |
|---|---|---|---|
| 1.1 | Encodings: wet = STATIC with color 7; holed bridge = STATIC skin 1 with color ≠ 0; belt = skin 4; gem = STATIC skin 3. | CONFIRMED | E:57-58, E:91-106, E:108-110, C:18. Extra check: a hole mask can never equal 7, because holes() never sets bits 0 or 1 (CG:2473-2481). So wet and holed cannot collide. |
| 1.2 | Wet: grip ×0.08, lurch sin(5.3t)·2.2, skid ×1.12 above 1.6 m/s, 0.9 s cooldown. | CONFIRMED | S:1665-1672, tuning.json. The lurch is an acceleration (·dt). |
| 1.3 | Holes open 0.45 s after landing, stay open crumbleRespawn, and close only once you are off the deck. You fall through at 0.2 m. | PARTIAL | S:525-529, S:1695, S:1744 confirmed. Correction: the hole timer is started only by `stepGround` (S:1702). `land()` (S:1885-1906) has no holed branch. So the holes open 0.45 s after the first standing step, and a buffered jump made on landing (S:1905) never triggers them. See N2. |
| 1.4 | CRUMBLE timer starts on land or stand, gone after 0.5 s, back after 3.4 s, and advances only while the element is in `act`. | CONFIRMED | S:1901, S:1700, S:530-533, S:495 |
| 1.5 | MOVE_H/V/Z and SWING formulas, carry, the 0.6/0.5 velocity inherit, swing super-jump 5.5·f⁴ and 3.5·f⁴, MOVE_Z landable only when abs(z) ≤ 1. | CONFIRMED | E:320-351, S:1656-1657, S:1678-1686, S:1697, S:507 |
| 1.6 | PAD/SPRING bounce 21/24, including a side bounce. | CONFIRMED | S:1888-1895. Side bounce: S:1879 (catchInside) and S:1948 (tryGrab). |
| 1.7 | ROPE, CABLE, GOAL behaviour. | CONFIRMED | S:1916-1930, S:1978-1979, S:1712, S:1902 |
| 1.8 | Saws and pendulum: knock-off ±6.9/6.9 with 0.7 s invulnerability, not a teleport. | CONFIRMED | S:355-359, S:562 |
| 1.9 | CANNON: 4 trailing balls, each alive to len+14. "The cycle's own ball is lethal while cyc < 0.7 (E:297)". | PARTIAL | The hit test (S:1615-1625) never calls `lethalAt`. Every trail ball, including back = 0, is lethal for as long as `cannonBallAlive` holds, i.e. flown ≤ len+14 (E:134). So the current ball stays lethal after cyc 0.7, past the lane end. `lethalAt(CANNON)` (E:297) is not used for hits. |
| 1.10 | SPIKE_TRAP, SPIKE_BLOCK and SPIKE_DROP windows, slab top surface, slam > 6 m/s throws you with vy 6. | CONFIRMED | E:175-194, E:296, S:1636-1643, S:1758-1796 (slam: S:1781-1783) |
| 1.11 | KEY, GATE wall/open/checkpoint, finish castle. | CONFIRMED | S:1503-1530 |
| 1.12 | Crab, bee, skull, blob and Enforcer effects (shove 9/≥6, lockout 0.3, cd 0.9, etc.). | CONFIRMED (spot-checked) | S:602-605, S:620-623, S:745-749, S:1315-1319, Enforcer S:843-875. Constants: S:1334-1358. Not every per-tier number was re-checked. |
| 1.13 | SEESAW tilt target, rates, slide 5·tilt, tilt-belt direction in `onT`. | CONFIRMED | S:509-517, S:452, S:1661, S:1675 |
| 1.14 | RAMP variants: shake counter kept in `tilt[]`, spin, belt. | CONFIRMED | S:518-523, S:1703-1708, E:115-117 |
| 1.15 | MORTAR lethal above y+0.35. AIMED tracking, lock and fire constants. | CONFIRMED | E:298, E:151-158, S:1544-1574 |
| 1.16 | `isHazard` covers 9 lethal types. `ridesAnchor` covers SPIKE_TRAP, SPIKE_BLOCK, SPIKE_DROP, SAW_H and CRAB. | CONFIRMED | E:62-64, E:69-71. AIMED also does not ride its anchor. |
| 1.17 | Fall respawn: endless at 6 m below the tower's lowest platform; finite at 12 m below lastGroundY. | CONFIRMED | S:566-567, TW:147, TW:253 |
| 1.18 | Respawn resets crumbleT/gone for all elements, but not tilt, onT, creatures, avl, lastAvlT, aim or featDone. | CONFIRMED | S:376-377. Behavioural: probe VcCopy (5). It also keeps goneT (harmless), clubTime, keys, shoveCd, slipCd and swingT. |

### §2 Creatures and avalanche

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 2.1 | Crab state machine, sight 4 m, notice 0.35 s, cool-down 2.5 s, hop gap 3.2 / dy 1.8, falls with its deck, gone after 1.5 s. | CONFIRMED | S:1338-1356, S:1377-1420, S:591-594 |
| 2.2 | Bee visit 0.58 of the cycle, drift 2.2·react, drift reset while away. | CONFIRMED | S:612-652, E:198 ff. |
| 2.3 | Skull states 0-5, launch only during the dash, club breaks. | CONFIRMED | S:689-766 |
| 2.4 | Blob 1/3/4, seesaw launch 1.732·jumpVel, not clubbable. | CONFIRMED | S:1259-1320. There is no swing or featDone path. |
| 2.5 | Enforcer states and seeded traits. | CONFIRMED (structure) | S:777-912 |
| 2.6 | Avalanche: armed/running/spent, cooldown from the global lastAvlT, warning 1.4, stagger 0.35, g 24, decks a0±8. **Spent state is not in featDone/doneFeat.** | CONFIRMED | S:1010-1093 (spent at S:1018 and S:1092), E:257-266. `stepAvalanche` never writes featDone. TW:259 copies featDone only. |

### §3 Timing table

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 3.1 | All rows (periodic: pure functions of global time; arrival triggers; seesaw and avalanche not reset by respawn). | CONFIRMED | Same evidence as §1/§2. Respawn non-reset was verified behaviourally (VcCopy 5). |

### §4 Serialization

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 4.1 | CourseIO element rows are exact Float.toString, 14 fields. | CONFIRMED | IO:24-38 |
| 4.2 | SliceCodec writes zone/dir/color/skin as bytes and anchor as a short. | CONFIRMED | SC:52-62. Probe VcCodec: color 132 → −124, gate skin 130 → −126. |
| 4.3 | Tower copies elements, remaps anchors, hazKey = slice·65536+i. | CONFIRMED | TW:362-392 |
| 4.4 | keyRooms are kept everywhere (TW remaps them). | CONFIRMED | IO:18/47, SC:27/44, TW:376 |
| 4.5 | **forks**: CourseIO keeps them; SliceCodec does not; the Tower world never has them. | CONFIRMED | SC:17-49 (no forks), TW:362-379 (never copied). Probe: decoded forks = 0, CourseIO forks = 1. |
| 4.6 | routeCount: world = −1, so routeSize = every element. | CONFIRMED | TW:346, C:26 |
| 4.7 | Decoy index: the world has empty arrays; streaming setWindow handles it. | CONFIRMED | `indexDecoys` is never called on the world. S:130-139. |
| 4.8 | gemCheckpoints: CourseIO N, SliceCodec Y, world from Tuning. | CONFIRMED | Probe: CourseIO gem = false. SC:22/41, TW:105/113. |
| 4.9 | prog: CourseIO N, SliceCodec Y (format 2), the world keeps it on the top Framed. | CONFIRMED | Probe: CourseIO prog = null. SC:28/45. |
| 4.10 | yBase/sBase: CourseIO N, SliceCodec Y. | CONFIRMED | Probe: SC yBase 1000 / sBase 240 kept. |
| 4.11 | Sim field coverage across rebase, RunSnapshot and checkpoint resume (time, s/y, onSlab→AIR, sideIn N, crumble/tilt for resident elements, featDone via doneKeys, creatures for resident hazards, avl when ≠ 0, lastAvlT). | CONFIRMED | S:385-431, RS:56-104, RS:184-224, PS:129-153, RES:23-28 |
| 4.12 | `pristine()` is effectively never true, because crabJump and crabFall default to −1. | CONFIRMED | S:198, S:416, RS:107-110 (index 18 and 21 ≠ 0). Effect: larger run.json only. |
| 4.13 | Checkpoint-only resume: new Sim at time 0; only seed, cp, keysHeld and openedUpTo are kept. | CONFIRMED | PS:139-144 (startOn → new Sim), RES:23-28. `doneFeat` is empty in a fresh Tower, so applyDone re-marks castle gates only (TW:270). |

### §4 Numbered findings

| # | Claim | Verdict | Evidence / correction |
|---|---|---|---|
| F1 | SliceCodec drops forks and Tower never stitches them. The live world and the live Autopilot have no fork information. | CONFIRMED, scope narrowed | Probe VcGen: 14 regenerations from a decoded (fork-less) previous slice produced **identical** next slices. `buildChunk` never reads `prev.forks` (CG:231-252). So generation and determinism are unaffected. The only consumer is Autopilot.branchOf (Autopilot.java:781), which runs live only in demo or the `climb.autopilot` hook (PS:159, PS:308). Also, `SliceCodec.same()` does not compare forks or the frame (SC:67-73), so no test using it can see the loss. |
| F2 | CourseIO drops gemCheckpoints, prog and the frame. "EndlessTest:36-38 … only holds when prog is null". | PARTIAL | The drop is CONFIRMED (probe). The quoted condition is REFUTED: in that test `a1.prog` is non-null, and slice 2 regenerated from the CourseIO-restored slice 1 is still identical (probe: `true`). This is because `Progression.of(null)` (CG:923) gives the same factors as slice 1's real state this early in the climb. The guarantee is coincidental and would not hold where progression factors differ. CourseIO has no caller in main code (it is used only in tests and tools), so the game is unaffected. Note also that the `CourseIO.toJson` equality used by DeterminismTest:43 and EndlessTest:34-38 cannot see prog or gemCheckpoints. |
| F3 | Unloading a slice resets every creature and avalanche in it. A spent avalanche re-arms. | CONFIRMED (code) | S:404-421 copies only `m.haz[i] ≥ 0`. TW:259 saves featDone only. Not reproduced behaviourally (it needs a window rebuild with a return to the deck). |
| F4 | Checkpoint-only resume resets time, Enforcers, clubbed crabs, avalanches, taken keys and non-castle gates. | CONFIRMED | PS:139-144, RES:23-28, TW:270. Addition: `startOn` also applies planner start tweaks (safe pocket, a crumbling start deck starts crumbling: S:342-349). |
| F5 | RunSnapshot omits sideIn/sideDir and onSlab (restored as AIR). | CONFIRMED | RS:25-49 (no fields), S:304, RS:63 |
| F6 | Respawn does not reset tilt, onT, creatures, avl or lastAvlT. | CONFIRMED (behavioural) | VcCopy (5): tilt 0.3, onT 0.9, mobState 3, crabPos 7, avl 2, lastAvlT 5, aimFireT 4, clubTime 12, keys 2, featDone true all survive. Only crumbleT and gone reset. |
| F7 | SliceCodec byte narrowing: a mask bit 7 corrupts the higher bits; GATE skin ≥ 128 wraps. Both latent. | CONFIRMED (behavioural) and refined | VcCodec: mask 0x84 → 0xffffff84. This adds **section 8**, the take-off section, as a hole (map `..H....H..` → `..H....HH.`). Gate skin 130 → −126. Latent: today's holed bridges are at most 9 m wide, so at most 6 sections and highest bit 4 (CG:2447, CG:2827 with CG:2473-2481). |

### §5 Cross-slice behaviour

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 5.1 | REACH 300 / MARGIN 120, rebuild triggers, origin snapped to castleSpacing, sBase a multiple of the circumference, rebase shifts y only. | CONFIRMED | TW:21, TW:224-236, CG:125-133, S:427-428 |
| 5.2 | `admit` makes growth device-independent. | CONFIRMED | TW:179-188, PS:479 |
| 5.3 | World order: slice route, then slice decoys, then the next slice route. So index i+1 is not always the next route deck. | CONFIRMED | TW:366-368. Probe VcSeam. |
| 5.4 | Index-arithmetic decks (creatures, Enforcer, avalanche) can resolve to decoys or other slices near a slice end. **C left this unverified.** | CONFIRMED (behavioural) | VcSeam (PUNISHER seed 2, slices 0-4): crabs on local deck 2 with follow reach 3. In the world, the offset −2 (slice-local deck 0, the shared start platform) resolves to world 302, a **decoy PAD of slice 2, 46 m lower**, and the offset −3 to another decoy PAD. Generated slices place such encounters: VcGen found 6 creatures and 1 avalanche whose reach crosses a slice seam over 62 PUNISHER and 62 NORMAL slices of seed 3. In the probed case it is geometrically harmless (dy −46 m fails canHop and ROAM_DY). But a crab can no longer follow onto the real start deck in the world, which differs from the slice-local proof. |
| 5.5 | Respawn rebuilds around the checkpoint slice, then `completeRespawn`. | CONFIRMED | TW:243-251 |
| 5.6 | WindowFollower is unreferenced. | CONFIRMED | grep: no references outside sim/WindowFollower.java |

**Counts:** 47 claims checked. 39 CONFIRMED, 7 PARTIAL (0.3, 1.3, 1.9, F1 scope, F2), 0 fully REFUTED. One sub-claim was refuted: F2's "only when prog is null". 1 UNVERIFIED part: the renderer half of 0.3.

---

## 2. Probes run (exact output)

The common setup was `javac -d $S/verifyC/classes -cp $GDX <current main sim/*.java>`. Then `java -cp $S/verifyC/classes:$GDX com.hotatticgames.climbup.sim.<Probe>`. JAVA_TOOL_OPTIONS banner lines are removed below.

### P1 VcCodec: SliceCodec and CourseIO round trip

The probe course has a 12 m holed bridge (9 sections, mask bits 2 and 7), a gate with skin 130, 1 fork, gemCheckpoints and prog.

```
before: holeSections=9 color=132 gateSkin=130 forks=1 gem=true prog=[1.0, 2.0, 3.0]
SliceCodec: color=-124 (hex ffffff84) gateSkin=-126 forks=0 gem=true prog=[1.0, 2.0, 3.0] yBase=1000.0 sBase=240.0 SliceCodec.same=false
hole map before ..H....H.. after ..H....HH.
CourseIO: color=132 gateSkin=130 forks=1 gem=false prog=null routeCount=3 SliceCodec.same=false
```

### P2 VcCopy: Sim.copy fidelity, deferred respawn, floorOverride and respawn reset

Setup: seed 1, managed exactly as PS:133.

```
(1) fields differing right after copy(): [minPlatY=-4.165796 vs Infinity, minPlatSize=83 vs -1, deferRespawn=true vs false, floorOverride=-4.165796 vs NaN, hitBy=...Element@7e0b37bc vs null, hzv=[..] vs null, hzTmp=[..] vs [..]]
(2) 3000 random steps, original vs copy (ignoring the known non-copied fields): first divergence step=1616 [s=8.743888 vs 4.0, y=-10.256111 vs 0.0, vx=0.5833338 vs 0.0, vy=-24.0 vs 0.0, mode=AIR vs GROUND, onElem=-1 vs 0, lockout=0.0 vs 0.1, falls=0 vs 1, events=27 vs 155, respawnPending=true vs false, teleported=false vs true, gone=[..] vs [..], invuln=0.0 vs 0.7]  final y -10.256111 / 0.0 falls 0/1 hits 0/0
(3) fall below floor: original respawnPending=true falls=0 y=-11.1752405 | copy respawnPending=false falls=1 y=0.0 mode=GROUND
    next step: original time=0.016666668 (frozen) copy time=0.033333335
(4) floorY after setRange: original=-123.0 copy=-4.165796
(5) after completeRespawn: tilt=0.3 onT=0.9 mobState=3 crabPos=7.0 avl[0]=2.0 lastAvlT=5.0 aimFireT=4.0 clubTime=12.0 keys=2 featDone=true | crumbleT=-1.0 gone=false goneT=2.0 (element 11 RAMP)
```

The copy stepped bit-identically to the original for 1615 steps. It diverged only at the first fall, and only because `deferRespawn` is not copied.

### P3 VcGen: generation determinism after decoding, co-occurrence and seam reach

Command: `VcGen 30 3 3` covers seed 3, slices 0-30, NORMAL and PUNISHER, 164 s. A first run with `VcGen 3`, seeds 1-2 and slices 0-3, gave the same verdicts.

```
EndlessTest slice2 equal after CourseIO restore: true a1.prog==null? false a1.gemCheckpoints=false a1.forks=1
STATIC | AIMED on=18   STATIC | AVALANCHE on=7   STATIC | BEE near=52   STATIC | BLOB on=20   STATIC | CANNON on=34
STATIC | CLUB on=28    STATIC | CRAB on=147      STATIC | ENFORCER on=6 STATIC | KEY on=8    STATIC | MORTAR near=111
STATIC | SAW_H on=30 near=76   STATIC | SAW_V near=91   STATIC | SKULL on=25   STATIC | SPIKE_BLOCK on=31 near=183
STATIC | SPIKE_DROP on=122     STATIC | SPIKE_TRAP on=38   STATIC/s1 | SKULL on=19   STATIC/s1/holed | SKULL on=16
STATIC/s2 | GATE on=7   STATIC/s3 | SAW_V near=1   STATIC/s3 | SPIKE_BLOCK near=1
ridesAnchor hazards on movers=0 (in a gap, carried by the take-off mover: 0)  lethal hazards on crumbling decks=0
creatures whose roam reach crosses the slice end=6 enforcers=0 avalanches=1 sliceswithforks=60 decoded-prev divergences=0
  h0 s3 k1..k3, h1 s3 k1..k3: prev forks=1..5 decodedPrev forks=0 next slice identical=true
  creature reach crosses slice end: h1 s3 k0 CRAB a0=1 reach=3 rs=118 decoys=4  (x3)
  avalanche a0+-8 crosses slice end: h1 s3 k1 a0=5 rs=80 decoys=2
  creature reach crosses slice end: h1 s3 k10 BLOB a0=1 reach=1 rs=86 decoys=2
  creature reach crosses slice end: h1 s3 k14 BEE a0=0 reach=1 rs=72 decoys=10 (x2)
```

The table above is condensed into columns. The full output is in `$S/verifyC/gen30.txt`.

### P4 VcSeam: index-arithmetic decks in the stitched world vs the slice

Command: `VcSeam 1 2 4` covers PUNISHER, seed 2, slices 0-4, in a full-window world.

```
CRAB slice 3 local anchor 2 (rs 110) k=-3: slice-local NO deck (outside route), world index 301 = PAD DECOY(anchor 230) key s2/107 dy=-51.62 ds=28.03  <- a deck the world sim may use
CRAB slice 3 local anchor 2 (rs 110) k=-2: slice-local deck 0, world index 302 = PAD DECOY(anchor 235) key s2/108 dy=-46.03 ds=-21.94  <- a deck the world sim may use
(the same two lines for each of the 4 crabs)
hard 1 seed 2 slices 0..4: offsets checked=211 mismatches=8 of which deck-like world elements=8
```

### P5 VcNear: route neighbours and spatial overlap

Command: `VcNear 25 4` covers seed 4, slices 0-25, NORMAL and PUNISHER. Full output is in `$S/verifyC/near.txt`. Excerpts used in §4:

```
AVALANCHE ~ CABLE 1, CRUMBLE 1, RAMP 1, ROPE 3        ENFORCER ~ STATIC/gem 1
CRAB ~ PAD 7, SPRING 4, MOVE_V/crumbling 4, MOVE_Z 2, STATIC/holed 3, STATIC/wet 30, ROPE 17, CABLE 5
SKULL ~ MOVE_H 1, MOVE_H/crumbling 1, SWING 1, ROPE 8   BLOB ~ SEESAW 1, MOVE_V 1, MOVE_H/crumbling 1, STATIC/belt 1
BEE ~ SEESAW 2, SPRING 2, MOVE_V(/crumbling) 3, STATIC/belt 2, STATIC/wet 4
SAW_H ~ MOVE_H 4, MOVE_V 3, SWING 2, SEESAW 2, RAMP 4, PAD 3, STATIC/holed 2, STATIC/wet 8
SPIKE_BLOCK ~ SWING 9, MOVE_V 5, PAD 5, SPRING 5, SEESAW 4, MOVE_Z 3, STATIC/holed 6   SPIKE_DROP ~ RAMP/belt 1, MOVE_Z 1, MOVE_V 1
spatial overlap: CANNON x ROPE 10, x MOVE_V/crumbling 3, x SWING 1, x SPRING 2, x RAMP/spin 1, x SEESAW 1;
                 SAW_V x MOVE_V 3, x MOVE_Z 3; SAW_H x MOVE_V 2; SPIKE_DROP x MOVE_V 1, x MOVE_Z 1, x RAMP/belt 1; AIMED x ROPE 4
```

---

## 3. Sim field table

Column key:
- **copy**: carried by `Sim(Sim o)` (S:245-259).
- **RS**: saved in RunSnapshot (RS:56-104, RS:184-224).
- **respawn**: what `completeRespawn` and `spawnAtCheckpoint` do (S:311-318, S:370-379).
- **unload**: what `rebase` does to the state of elements and hazards whose slice left the window (S:385-431).
- **cp-resume**: the checkpoint-only path (PS:139-144).

Values: Y = kept/copied, N = dropped/not copied, R = reset to a fresh value, (r) = recomputed, sh = shifted by dy, map = index remapped.

| Field(s) | copy | RS | respawn | unload / rebase | cp-resume | Note |
|---|---|---|---|---|---|---|
| time | Y | Y | kept | kept | R → 0 | |
| s, y, vx, vy | Y | Y (absolute) | R (to the cp, v = 0) | y sh, s not (multiple of circumference) | spawn | |
| facing, mode, onElem | Y | Y (slab GROUND → AIR) | R | onElem map | spawn | |
| lastPad | Y | Y (key) | R → −1 | map | R | |
| onSlab | Y | **N** | R → −1 | map | R | |
| coyote, jumpBuf | Y | Y | R → 0 | kept | R | |
| lockout | Y | Y | R → 0.1 | kept | R | |
| pullT, pullFrom/To S,Y | Y | Y | kept (stale, unused in GROUND) | Y sh | R | |
| ledgeSide | Y | Y | kept | kept | R | |
| sideIn, sideDir (private) | Y | **N** (sync → −1) | kept, then cleared next step (S:536) | map | R | F5 |
| ropeTopT | Y | Y | kept (self-clears, S:1978) | kept | R | |
| jumpedUp, prevJumpHeld | Y | Y | jumpedUp R | kept | R | |
| lastGroundY, maxHeight | Y | Y | lastGroundY R | sh | R | |
| onT[], tilt[] | Y (clone) | Y (non-resting resident) | **kept** | kept if resident, **lost** if unloaded | R | F6; shake counter (N6) |
| minPlatY, minPlatSize (private) | **N** | — | — | invalidated (S:429) | (r) | recomputed by setRange; harmless |
| floorY | Y | (r) | kept | sh | (r) | |
| checkpoint | Y | Y (cpSlice/cpLocal) | set | map (can become −1, N4) | Y | |
| bestElem | Y | Y (key) | progress() | map | via cp | |
| won, finishedRun, falls, hits | Y | Y | falls++ | kept | partly (RunRecord) | |
| landSpeed, events, ps0/py0, teleported | Y | N (cosmetic) / (r) | set | py0 sh | R | |
| **deferRespawn, respawnPending** | **N** | capture refused while pending | respawnPending cleared | deferRespawn set by TW:258 | set (PS:152) | **N1** |
| **floorOverride** | **N** | (r) | — | re-set TW:263 | set | **N1** |
| motion, motionGen, mstep (pkg) | Y (shared cache) | N | — | — | — | planner only |
| assistForgive | Y | N (settings) | kept | kept | from settings | |
| es0/ey0/es1/ey1, edy | Y | (r) syncElements | kept | (r) from the rebuilt element (S:390-398) | (r) | |
| crumbleT[], gone[] | Y | Y | **R (all)** | kept if resident, lost if unloaded | R | |
| goneT[] | Y | Y | kept (harmless) | as above | R | |
| padSquash[] | Y | N | kept | as above | R | cosmetic |
| invuln | Y | Y | R → 0.7 | kept | R | |
| keys, keysFree | Y | keys Y | kept | kept | keys via keysHeld | |
| featDone[] | Y | Y (doneKeys) | **kept** | map; unloaded → Tower.doneFeat | castle gates only | F4 |
| aimAng, aimFireT, aimArmed, aimYaw | Y | Y (HAZ_STATE) | **kept** | map; lost if unloaded | R | |
| crabPos/Vel/Mode/Stuck/Cool/Turn/Seen, crabJump/From/FromDeck/Fall | Y | Y | **kept** | map; lost if unloaded | R | F3 |
| mobState/T/S/Y/Dir/Tgt/Seen, deckOff | Y | Y | **kept** | map; lost if unloaded | R | F3 |
| avl[] | Y | Y (state ≠ 0) | **kept** | map; lost if unloaded (re-arms) | R | F3 |
| lastAvlT | Y | Y | **kept** | kept | R → −1e9 | |
| avlImpacts … enfFallNoDeck, avlRicochets, avlPeak (telemetry) | Y | N | kept | kept | R | report-only |
| squashT, slipCd | Y | slipCd Y, squashT N | kept | kept | R | |
| lastKeyColor, lastGateColor, lastGateNo | Y | lastKeyColor only | kept | kept | R | effects |
| clubTime, swingT, shoveCd | Y | Y | **kept** | kept | R | |
| **knocked, hitBy** | **N** | N | — | hitBy → null | R | cosmetic: hitBy is set before every knock-off (S:1612) |
| crabS/Y, hitS/Y | Y | N | kept | Y sh | R | effects |
| act, hz, winLo, winHi | Y (shared refs; never mutated in place) | (r) | — | (r) setRange | (r) | |
| humanHold, heldX/Y, heldXT/YT | Y | N | kept | kept | R | 0 in play |
| beeTmp, beeEnv, hzTmp, hzv, enfMargin, avlTouched | N (scratch) | — | — | — | — | written before every read: S:614, S:1370-1373, S:966, S:1046/1086 |

---

## 4. Interaction matrix

**Generation evidence:**
- P3 covers seed 3, slices 0-30 × 2 modes. P5 covers seed 4, slices 0-25 × 2 modes.
- In all of them, every hazard, creature, key, club and gate is anchored to a STATIC deck. That includes the bridge, holed bridge, gem and castle-deck skins (P3).
- No anchor was ever a mover, crumble, ramp, seesaw, pad or rope, so the `carryS`/`carryY` ride code (S:1591-1602) is not exercised by generated content.
- Platform/hazard pairs therefore meet only as route neighbours or by spatial overlap (P5).

Legend for each cell:
- Co-occurrence: **A** = anchored on it (observed); **N** = route neighbour within anchor−1..+2 (observed); **O** = spatial overlap with its swept box (observed); **p** = a code path exists but was not observed; **–** = not generated.
- Sim effect: the result of a contact.
- `?` = untested or surprising. `!` = the pair is placed without any proof that the combination is fair.

**Fairness of placement:** every link is proven in the real Sim by `commit` or `chainClean` for one or a few arrival times. The `!` marks are reserved for states the proofs never see:
- retry state after a fall (F6, N6);
- world state at slice seams (5.4);
- unloaded or reloaded creatures (F3);
- checkpoint-only resume (F4).

| Platform \ mechanic | Lethal hazards (saw, pendulum, cannon, mortar, aimed, spike trap/block/drop) | Crab | Bee | Skull | Blob | Enforcer | Avalanche | Ice / belt / key / gate / club / gem |
|---|---|---|---|---|---|---|---|---|
| STATIC plain | A (all types). Knock-off (S:355); slab top is a surface (S:1758) | A: shove 9/6 (S:602) | A (post) / N | A: dash launch | A: squash 9/7 | A (its decks are fresh STATIC, CG:770-774) | A trigger deck must be STATIC, not wet, w ≥ 2.5, no other anchor (CG:845-850) | key/gate/club A; gem A |
| STATIC wet | N (SAW_H 8, SPIKE_BLOCK 12, SAW_V 5, mortar 4). The generator forbids a hazard module right after ice (CG:510) | N 30: shoves on ice keep momentum; low grip makes recovery slower `?` | N 4 | N 2 | – | – (wet blocked by enfCalm only) | not a trigger (CG:847) | – |
| STATIC holed bridge | N (SAW_H 2, SAW_V 3, SPIKE_BLOCK 6, drop 2, trap 2, aimed 1) | N 3. A crab falls through an open hole (S:1392) | – | A 16: the skull hovers, holes do not affect it | p (a blob over a hole does not fall: blobLands has no hole test) `?` | – | – | club N 1 |
| STATIC belt (skin 4) | N (SAW_H 2, drop 1). Carry −phase (S:453) | – | N 2 | – | N 1 | – | – | – |
| STATIC gem (skin 3) | N (SAW_V, SPIKE_BLOCK take-off) | – | A 2 (post) | – | – | N 1 (Enforcer decks next to a gem) | – | checkpoint pickup S:571 |
| CRUMBLE | N (trap 4, block 2, saw 1, cannon 1, drop 1). Hazards are never on it (P3: 0) | N. A crab may walk onto it and falls with it (S:665-670, S:1392) | N 1 | – | – (roamDeck excludes it for blobs and skulls) | – | N 1: blocks land on it while it is not gone (avlDeck S:1002) | – |
| MOVE_H / MOVE_V | N + O (SAW_V×MOVE_V 3, SAW_H×MOVE_V 2, cannon lanes ×MOVE_V 1-3, drop×MOVE_V 1). Gap saw or cannon timing composed with the mover phase, proven by commit | N (MOVE_V 2). roamDeck excludes movers, so crabs never step onto one | N | N 1 (MOVE_H) | N 1 | – | p: a mover can be a target deck a0+1..+4. Blocks use the live ey1 (S:672, S:1008) `?` | key N 1 (MOVE_H) |
| Crumbling movers (skin 1) | N + O (cannon ×MOVE_V/crumbling 3, cannon ×MOVE_H/crumbling 1) | N 4 | N 2 | N 1 | N 1 | – | – | club N 1 |
| MOVE_Z | N + O (SAW_V×MOVE_Z 3, drop×MOVE_Z 1). Out of plane it is `gone` | N 2 | – | – | – | – | p (gone when out of plane, so not a deck) | club N 1 |
| SWING | N + O (SPIKE_BLOCK 9, cannon×SWING 1). Catapult jump into a hazard zone `?` | – | – | N 1 | – | – | – | club N 1 |
| PAD / SPRING | N + O (SPIKE_BLOCK 5/5, SAW_V 3/3, cannon 1/2+2, mortar 3, aimed×SPRING 1). The launch corridor is audited (CG:1890-1900) | N 7/4. **roamDeck accepts a PAD or SPRING as a deck** (S:665-670); a crab on a pad never bounces `?` | N 2 (SPRING) | p (same rule) `?` | – | – | p (avlDeck accepts pads) | key N 1 |
| ROPE / CABLE | N + O (cannon×ROPE 10, aimed×ROPE 4, SPIKE_BLOCK~ROPE 26). Lethal hazards hit in **every mode** (no mode test in S:1608) | N 17/5. **Creatures cannot shove you on a rope, cable, ledge, pull-up or beam**: every shove needs GROUND or AIR (S:602, S:620, S:745, S:1315, Enforcer S:843) | N 5/2 | N 8 | N 5 | – | N 3/1: not decks (S:1005) | – |
| SEESAW (and tilt belt) | N + O (SPIKE_BLOCK 4, SAW 2+2, cannon 3, aimed 1). Tilt persists through respawn (F6) `!` | – (roamDeck excludes it) | N 2 | – | A p: blob-on-seesaw module (CG:2444-2452) **never observed** in 176 slices. N 1 observed. Launch 1.732·jumpVel (S:1305-1310) `?` | – | p: **avlTop treats a seesaw as flat at d.y and ignores tilt** (S:1008, S:672) `?` | – |
| RAMP plain / crumble / shake / spin / belt | N + O (cannon×RAMP/spin 1, drop×RAMP/belt 1, SPIKE_BLOCK ~ RAMP/crumbling 1). Shake counter is not reset on respawn `!` (N6) | N 4 (not a deck) | N 1 | – | N 1 | – | N 1: blocks follow the ramp slope, spinning ones included (S:1008) | key N 1 |
| GOAL (finite towers only) | – | – | – | – | – | – | – | – |

**Cross-cutting cells:**
- **Club** removes crabs, bees and skulls; a skull breaks it (S:598-600, S:616-618, S:697-699). Blobs and the Enforcer are immune.
- **Key/gate:** planners use `keysFree` (S:323), so the gate wall (S:1519-1524, which sets `s` in any mode) is never in a link proof. Key fetching is proven separately; route keys have no generator proof (coverage MV-39).
- **Avalanche × creatures:** no interaction code. Blocks pass through crabs and skulls and collide with decks and with other blocks only (S:1047-1083).
- **Avalanche × respawn:** blocks keep falling after a respawn and tumble down the tower, where the checkpoint is. Only the 0.7 s invulnerability protects the respawned body `?` `!`.
- **Seams (5.4):** creatures (crab, blob, bee), the Enforcer and avalanches anchored within reach of a slice end use different decks in the world than in their proof `!`.

---

## 5. New findings (not in C)

**N1. `Sim.copy` misses `deferRespawn`, `respawnPending`, `floorOverride` and `minPlatY`/`minPlatSize`, plus cosmetic `knocked`/`hitBy` (S:245-259).**
- Probe P2: the copy stepped identically for 1615 random steps. At the first fall the managed original froze (respawnPending) while the copy respawned at once.
- A copy of a frozen sim is not frozen.
- A copy that calls `setRange` uses the resident-only floor: −4.17 instead of −123 (P2 4).
- Solver trials abort on `setbacks()` and planners call `setWindow`, not `setRange`. So today this affects only the live demo or autopilot driver after a fall, and never a proof.
- Scratch fields (hzv, enfMargin, avlTouched, beeTmp/beeEnv) are rewritten before each read, so they are safe.

**N2. Holed bridges start their hole timer only from `stepGround` (S:1702).** `land()` (S:1885-1906) has no holed branch. A buffered jump on landing therefore never triggers the holes, and they open one step later than a crumble's timer starts. The proof and the game share the same code, so this is consistent but undocumented.

**N3. Test oracles are blind to the dropped fields.**
- `SliceCodec.same` ignores forks and yBase/sBase (SC:67-73).
- `CourseIO.toJson` equality, used by DeterminismTest:43, EndlessTest:34-38 and LaneReportTest:33, ignores prog and gemCheckpoints.
- F2's EndlessTest equality holds only because of a fresh-Progression coincidence at slice 2 (CG:923).

**N4. `rebase` can set `checkpoint = −1`** when the checkpoint's slice leaves the window (S:425).
- While it is −1, any resident gem, a lower one included, satisfies `i > checkpoint` (S:576, S:1700, S:1897), so the checkpoint can regress downward.
- Tower's `cpRef` protects the respawn itself (TW:242).
- Latent: gems and castles are at most about 250 m apart and the window reaches 300 m below, so it needs a checkpoint more than 300 m below the player.

**N5. A save in the step frame that set `respawnPending` deletes run.json.**
- `keepTower()` and `maintain` run before each step (PS:307, PS:479). Autosave (PS:332) and `pause()`/SAVE & EXIT (PS:1026-1033) run after the step loop.
- If the frame's last step was a fall, `RunSnapshot.capture` returns null (RS:57) and `GAME:302/322` calls `store.deleteRun()`.
- The next CONTINUE then becomes a checkpoint-only resume (F4): time 0, doneFeat lost, key-room gates and keys back, Enforcers and avalanches back.

**N6. The shaking ramp's kick counter (`tilt[]` on RAMP skin 2) is not reset on respawn** (S:376-377, S:1703-1708). The kick direction and size are a hash of the counter, so a retry meets a different kick sequence from the first attempt and from the proof.

**N7. Generated content never anchors a ride-the-anchor hazard to a moving deck, and never places a blob on a seesaw.**
- Both have code paths: buildHaz j==0 anchors to `last` of any type (CG:2575-2611), and blob-on-seesaw is at CG:2444-2452.
- 176 slices showed 0 of either.
- The rides-mover code (S:1591-1602) and the blob seesaw launch (S:1305-1310) are therefore covered only by unit tests, if at all.

**N8. `roamDeck` (S:665-670) and `avlDeck` (S:1002-1006) accept PAD, SPRING and GOAL decks, and `avlTop` ignores seesaw tilt (S:1008).** A crab can follow onto a pad and stand on it without bouncing, and avalanche blocks collide with an untilted phantom plank. Neither was observed as a direct anchor, but crabs sit next to pads (7) and springs (4) and avalanches next to ramps and crumbles.

**N9. Creatures cannot shove a climber on a rope, cable, ledge, pull-up or beam** (the shove tests require GROUND or AIR: S:602, S:620, S:745, S:1315, Enforcer S:843). Lethal hazards hit in every mode (S:1608). A rope therefore shelters you from crabs, bees, skulls, blobs and the Enforcer, but not from cannons. This is undocumented, though consistent across proof and play.

---

## 6. Findings by root-cause class

Severity reflects the effect on real gameplay, generation, solver or proofs. The effect named is on the shipped game.

| Root-cause class | Findings | Severity | Reason / concrete effect |
|---|---|---|---|
| **Respawn restores only part of the world** | F6, N6; avalanche × respawn cell | **HIGH** | Retry after a fall is a core loop ("checkpoints and quick retry"), and no proof covers retry state: tilt, onT, shake counter, mid-chase crabs, a skull parked and rested, an armed aimed cannon, avalanche blocks still tumbling. A retry can be harder than the proven first attempt, or easier (a spent avalanche or a departed Enforcer never comes back). |
| **State lost on slice unload / window rebuild** | F3 | MEDIUM | Creatures and avalanches whose slice is unloaded come back fresh, and a spent avalanche re-arms (it fires again if `lastAvlT` + period has passed). The player meets them again only after a long fall or a respawn rebuild that unloads that slice. |
| **Checkpoint-only resume keeps a minimal subset** | F4, N5 | MEDIUM | After a rejected or deleted run.json, CONTINUE restarts with time 0. Every Enforcer, clubbed crab and avalanche, the key-room keys and gates, and the creature state return. N5 makes this reachable from an ordinary pause or autosave in the frame of a fall. |
| **Index arithmetic over a stitched world (seam)** | 5.4 / C §5, N8 | MEDIUM | Creature, Enforcer and avalanche decks are found as `anchor ± k`, and `routeSize()` is the whole world. Near a seam they resolve to the other slice's decoys (P4) instead of the proven decks. Generation places such encounters (P3: 6 creatures and 1 avalanche in 124 slices). The probed case is harmless (46 m away), but the world behaviour is unproven. |
| **Serializer field list drifts from the model** | F1, F2, F5, N3 | LOW | Forks: affects only the live demo or autopilot (P3 proves generation is unaffected). CourseIO: tests only. sideIn/onSlab: a save in a handful of frames. The oracles that should catch drift (`same`, `toJson` equality) skip the same fields. |
| **Clone (copy constructor) field list drifts from the model** | N1 | LOW | Solver trials diverge from the real managed sim only at a fall or a pending respawn, and trials already abort on setbacks. Risk grows if a trial ever calls `setRange` (floorOverride lost). |
| **Integer narrowing in the binary codec** | F7 | LOW (latent) | A ≥ 9-section holed bridge with bit 7 set would also open section 8 (its take-off section) after a reload or resume. Castle ≥ 128 would wrap its gate number, and `openedUpTo` and `afterCastle` would break. Neither is reachable with today's generator or finish castle. |
| **Index invalidation on remap** | N4 | LOW (latent) | `checkpoint = −1` lets a lower gem become the checkpoint. It needs the checkpoint more than 300 m below the player. |
| **Trigger asymmetry between landing paths** | N2 | LOW | A holed bridge is not triggered by a land-and-buffered-jump. Proofs and play agree, but the bridge can be crossed "for free" by bounce-jumping. |
| **Proof coverage gaps for co-occurrence** | N7, N8, N9, interaction cells marked `?` | LOW | These combinations exist in code but are never generated, or never proven outside the first-attempt state (blob on seesaw, a hazard riding a mover, a crab on a pad, a seesaw deck under an avalanche, rope immunity to creatures). |
| **Cannon lethality defined twice** | 1.9 | LOW | `lethalAt(CANNON)` (E:297) disagrees with the real hit rule (S:1615-1625). Any code that uses `lethalAt` for cannons, such as a renderer warning or a planner heuristic, would under-report danger after cyc 0.7. |
