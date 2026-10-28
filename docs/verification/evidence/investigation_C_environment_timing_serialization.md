# Audit C: environment objects, timed mechanics and what each save path keeps

Read-only audit of the repository at /home/user/Climb-Up (no files changed). Paths are relative to `core/src/main/java/com/hotatticgames/climbup/`.
Abbreviations: **E** = sim/Element.java, **S** = sim/Sim.java, **C** = sim/Course.java, **IO** = sim/CourseIO.java, **SC** = sim/SliceCodec.java, **TW** = sim/Tower.java, **WF** = sim/WindowFollower.java, **RS** = RunSnapshot.java, **PS** = PlayScreen.java, **CG** = ClimbGame.java, **RES** = ResumeState.java.
Tuning values come from assets/data/tuning.json, or from the defaults in sim/Tuning.java when the JSON does not set them: crumbleDelay 0.5, moverCrumbleDelay 1.6 (Tuning.java:19), crumbleRespawn 3.4, hitKnockSide/Up 6.9/6.9, padBounce 21 / held 24, seesawMaxTilt 0.55, seesawRate/Relax 1.0, seesawSlide 5, wetGrip 0.08, wetLurch 2.2 at 5.3 rad/s, wetSkid 1.12, fallRespawnDepth 12, jumpVel 11.5, gravity 34. `T.react()` is 1 / 1.25 / 1.5 depending on difficulty (Tuning.java:60,68).

## 0. Global clock and phase model

- There is one clock, `Sim.time`. It starts at 0 (S:20) and goes up by DT = 1/60 s every step (S:490). **A respawn never resets it** (S:370-379).
- Every periodic element and hazard is a pure function of `time`: `cyc(t) = frac(t/period + phase/2π)` (E:141), and `ang(t) = 2πt/period + phase` (E:310). So the phase a player meets depends on when they arrive (how long they took), on the seeded `phase`, and on `period`. A player who arrives at the same element at a different time meets a different phase.
- Movers report their per-step displacement through `sOffsetAt` and `yOffsetAt` (E:320-351, S:447, S:504). The renderer and the sim share these functions.
- In production the whole resident world is simulated: `PS:153,487` and `TW:263` call `setRange(0, size-1)`, so `act` holds every resident element and `hz = hazardsFor(-6, size+5)` (S:183) holds every resident hazard. Narrow windows (`setWindow`, S:122-143) exist only in planners and proofs.

## 1. Every Element.Type (E:5-43)

Key for the "Clock" column: **T** = phase is a pure function of global time and the seeded phase/period; **A** = trigger on arrival or landing; **S** = seeded per-element values; **L** = live sim state.

| Type | Kind | Motion / formula | Clock | Stand/wait safely? | Effect on player |
|---|---|---|---|---|---|
| STATIC | platform | none. skin 1 = wooden bridge; skin 3 = gem checkpoint (C:18); color==WET(7) = wet (E:57-58); skin 4 = plank conveyor (E:108-110); skin 1 + color mask = holed bridge (E:91-106) | — / A for holes | plain: yes. Wet: grip ×0.08 (S:1665), lurch `sin(time·5.3)·2.2` (S:1669), skid ×1.12 when braking above 1.6 m/s, 0.9 s cooldown `slipCd` (S:1668-1672): mostly, with drift. Belt: no, carries you at −phase m/s (S:453, E:110, S:1657). Holed: yes on the solid sections | Holes open HOLE_DELAY 0.45 s after landing (S:525-528, S:1702) and stay open crumbleRespawn 3.4 s. They close only once you are off the deck (S:528). You fall through once your centre is 0.2 m inside a hole (S:1695, S:1744) |
| CRUMBLE | platform | none | A | no | Timer starts when you land (S:1901) or stand (S:1701). Gone after crumbleDelay 0.5 s, back after 3.4 s (S:530-533). The timer advances only while the element is in `act` (S:495) |
| MOVE_H | platform | `s + amp·sin(ang)` (E:322) | T,S | yes (carried, S:1656-1657). skin 1 crumbles after 1.6 s (E:84, S:532) | Carries you. Jump inherits 0.6× its velocity (S:1679-1680). Leaving the edge inherits 0.5× (S:1697) |
| MOVE_V | platform | `y + amp/2·(1−cos ang)` (E:333) | T,S | yes (skin 1 crumbles) | As MOVE_H, plus an upward boost of 0.6·max(0, vy) |
| MOVE_Z | platform | depth `amp·sin(ang)` (E:306) | T,S | yes once on it: `gone` is forced false while you stand on it (S:507) | Can be landed on only while abs(z) ≤ Z_REACH 1.0. Otherwise `gone[i]` is set every step (S:507) |
| SWING | platform | s offset `len·sin(amp·sin ang)`, y = `y+len−len·cos(...)` (E:323, E:334). Frac = sin(ang) (E:308) | T,S | yes (skin 1 crumbles) | A jump near the top of the arc adds `swingSuper 5.5·f⁴` up and `3.5·f⁴` along (S:1682-1685) |
| PAD | platform | none (padSquash is cosmetic) | — | no, it bounces | vy = 21, or 24 with jump held (S:1890-1892). It also bounces you if you walk into or drop onto its side (S:1878, S:1948) |
| SPRING | platform | none | — | no | Launches at angle amp: (sin, cos)·21/24 (S:1891) |
| ROPE | traversal | none | — | yes | Grab within ropeGrabRadius (S:1916-1920). Climb at climbSpeed. Hold up at the top for 0.06 s to mount the beam (S:1978-1979). Jump off at 0.95 run speed (S:1970-1974) |
| CABLE | traversal | none | — | yes | Grab when vy < 4 (S:1925). Shimmy along it. Drop when down is pushed or you pass the end (S:2008-2021) |
| GOAL | platform | none | — | yes | Sets `won` (S:1712, S:1902). Finite towers only |
| SAW_H | hazard (lethal disc R 0.55) | `amp·sin(ang)` (E:324). Rides its anchor (E:70) | T,S | no | Knock-off (S:355-359): vx ±6.9, vy 6.9, 0.7 s invulnerable. Not a teleport |
| SAW_V | hazard (R 0.55) | `y + amp/2(1−cos)` (E:333) | T,S | no | Knock-off |
| PENDULUM | hazard (R 0.5) | swing formula (E:323, E:334) | T,S | no | Knock-off |
| CANNON | hazard (R 0.42) | 4 trailing balls, ball s = `s + dir·len/(0.7·period)·(cyc+back)·period`. Each ball is alive to len+14 (E:123-148). The cycle's own ball is lethal while cyc < 0.7 (E:297) | T,S | no | Every live ball is tested (S:1615-1625). It stays in the hz set while you are within its reach (C:70-73) |
| SPIKE_TRAP | hazard | `spikeHeight`: warning at c ∈ [0.37, 0.55), up 0.62 m over [0.55, 0.55+amp), then retracts in 0.04 (E:175-181). Lethal over [0.57, 0.55+amp) (E:296). Rides its anchor | T,S | between cycles only | Knock-off when y is inside the spike band (S:1636-1637) |
| SPIKE_BLOCK | hazard | static lethal box [y, y+len] (S:1641-1642). Rides its anchor | — | no | Knock-off |
| SPIKE_DROP | hazard + surface | `dropBottom`: pause to 0.15, tremble to 0.27, slam to 0.33, rest to 0.45, slow rise (E:184-191). Only the lower 0.5 m hurts (E:194, S:1638-1640). The top is a surface (S:1759-1775) | T,S | on its top: yes, it carries you (S:1778-1796). A slam faster than 6 m/s throws you off with vy 6 (S:1781-1783) | Knock-off from the underside |
| SPRING | (see above) | | | | |
| KEY | feature | bob `0.05·sin(3t)` (S:1508) | L | — | Touch sets `keys |= 1<<color` and `featDone` (S:1509) |
| GATE | feature | none | L | — | Without the key it is a wall: pushback (S:1519-1524). With the key it opens, uses up the key, and sets the checkpoint to its deck (S:1513-1518). The finish castle sets `finishedRun` (S:1503-1504) |
| CRAB | creature | sweep `amp·sin(ang)` (E:324) while patrolling, then live chase state (S:1377-1453). Rides its anchor | T,S,L | — | Shove (S:602-605): vx ±9, vy ≥ 6, lockout 0.3, shoveCd 0.9. Never lethal |
| CLUB | pickup | none | L | — | Touch gives clubTime 30 s (S:585-588, S:1334). Each knock costs 5 s (S:611). Knocking a skull breaks the club (S:699) |
| BEE | creature | visit for 0.58 of the cycle (E:199-291), plus a drift toward you (S:632-652) | T,S,L | — | Bump (S:620-623): 8 / 5, lockout 0.25, cd 1.0 |
| SEESAW | platform | tilt in `tilt[]` (L). The target is −(x/hw)·0.55 while you stand on it, otherwise 0. It approaches at 1 /s (S:509-517). skin 4 = tilt belt (E:113) | L | no. It slides you downhill at 5·tilt m/s (S:1675). Uphill run is slowed (S:1661). The tilt belt always runs at `phase` m/s toward the last end you stood on, kept in `onT` (S:452, S:515) | Blob landing on the far end launches you at 1.732·jumpVel (S:1308-1312) |
| RAMP | platform | slope amp, surface at the player's x (S:518-523). skin 1 crumbles (0.5 s). skin 2 shakes (`onT` ≥ 0.25 s, so it throws you; the counter is kept in `tilt[]`). skin 3 spins: slope `amp·cos(phase+2πt/period)`, carry 2.6·sin (E:115-117, S:453). skin 4 runs downhill at −phase | T,S (spin) / A (crumble, shake) | plain: yes. 1: no. 2: no (throws vx ±3-5.4, vy 10-13.6 deterministically from a hash of the counter, S:1703-1708). 3: carried and tipped. 4: carried down | `onT` builds while you stand on it and decays at 1.5/s (S:521) |
| MOVE_Z | (see above) | | | | |
| MORTAR | hazard (R 0.42) | ball y = parabola over the first 0.82 of the cycle, otherwise in the barrel (E:160-165). Lethal above y+0.35 (E:298) | T,S | yes beside it | Knock-off |
| AIMED | hazard (shot R 0.40) | tracks you: yaw 4 rad/s, pitch 2.4 rad/s, both ×react, pitch clamped to [−0.35, 0.5]. Locks at c = 0.55 and fires at 0.70 if armed and facing along the lane. Ball speed 9 m/s for 1.6 s from the muzzle tip 0.84 (E:152-158, S:1544-1574, S:1535-1541) | T,S,L (`aimAng`, `aimYaw`, `aimFireT`, `aimArmed`) | outside range 15 m, or on another layer (dy outside [−4, 5]) | Knock-off |
| SKULL | creature | state machine (§2) | L, phase used for the bob only (S:706) | — | Launch 14 / 9.5 along the dash, lockout 0.35 (S:746-749) |
| BLOB | creature | state machine (§2) | L | — | Squash 9 / 7, lockout 0.4 (S:1315-1319), or the seesaw launch |
| ENFORCER | creature | state machine (§2). Traits seeded from `phase` (S:913-917) | L,S | — | Kick 9.5 / 6.5. Swat 8 along, vy capped at −2 (pushes you down, S:874) |
| AVALANCHE | hazard (blocks lethal) | blocks seeded from `phase`, then physics (§2) | A (landing on its deck) + global cooldown `lastAvlT` | — | Knock-off (S:1614, S:1209-1222) |

Notes:
- `isHazard()` (E:62-64) covers the 9 lethal types only. Creatures shove you through `stepFeatures` and are never lethal, except avalanche blocks, which are tested in `hazardHit` (S:1614).
- `ridesAnchor` (E:69-71) covers SPIKE_TRAP, SPIKE_BLOCK, SPIKE_DROP, SAW_H and CRAB. Their offset is `carryS`/`carryY`, a pure function of time (S:1591-1602). SAW_V, PENDULUM, CANNON and MORTAR do not ride a mover.
- Falling: in the endless climb, a fall more than 6 m below the lowest platform of the whole tower respawns you (S:566, TW:147, TW:253). On finite towers the limit is 12 m below `lastGroundY` (S:567).
- A respawn resets `crumbleT` and `gone` for **all** elements (S:376-377). It does **not** reset `tilt`, `onT`, creature arrays, `avl`, `lastAvlT`, `aim*` or `featDone`.

## 2. Creatures and the avalanche (state is per hazard index, `growAim` S:186-199)

| Creature | States | Triggers / timing (base value / react()) | On player | Persistence |
|---|---|---|---|---|
| Crab (S:1377-1453) | mode 0 sweep (pure time), 1 chase, 2 return. Also `crabTurn` 0.35, `crabStuck` 0.6, then `crabCool` 2.5. Hop: `crabJump` takes 0.55 s, height 1.0, gap up to 3.2, rise up to 1.8 (S:1351-1356). Follows up to 3 decks (`deckOff`). Falls with a crumbled deck or open hole (S:1393), and is gone after 1.5 s (S:591-594) | Sees you within 4 m and dy [−0.3, 2.2], only over its own deck (S:1396-1397). Reacts after 0.35 s (S:1400). Chase speed 1.5× its peak patrol speed (S:1401). Waits up to 1.5 s while you are airborne (S:1408) | shove (S:602-605). The club knocks it off: featDone (S:598-601) | Chase state lives in sim arrays and survives rebase only while its slice stays resident (S:409-418). Knocked or fallen: featDone, kept in `Tower.doneFeat` (TW:259) |
| Bee (S:612-652, E:198-291) | present while u = cyc/0.58 < 1. Enters from ±14 m (dir picks the side), buzzes, dives at u ∈ (0.38, 0.62). Drift `mobS`/`mobY` toward you at 2.2·react m/s, within its envelope (EncounterModel) | T + seek radius 7 m. The drift resets to 0 when it is away (S:613) | bump 8 / 5 | featDone if clubbed. The drift is in `mobS`/`mobY` |
| Skull (S:689-766) | 0 init, 1 hover (bob), 2 wind-up loop (0.5) and swoop (to 0.8 total), 3 dash 16·react m/s, 4 turn 0.5, 5 fly to a neighbouring deck at 8 m/s. Goes home after 1.5 s without you on its 3 decks (E:208-218) | on its deck for 0.35 s (notice) and rested 0.6 s. Roams to deck ±1 within dy 3 (S:707-716) | launch during the dash only (S:745-749). Clubbing it gives featDone and breaks the club | as above. Planner helper `parkSkullsAtFarEnd` (S:265-273) |
| Blob (S:1259-1320) | 1 sit, 3 leap (1.7 s, apex 11 m; picks its spot 0.55 s before landing, S:1281-1292), 4 rest 1.0 s. Home after rest + 1.5 s | on its deck or a neighbour, 0.3 s notice. Not clubbable (no featDone path) | squash 9 / 7; on a seesaw, launch 1.732·jumpVel and sets `tilt[a]` (S:1310) | `mobState`/`mobT`/`mobS`/`mobTgt`/`deckOff` |
| Enforcer (S:777-912) | 1 wait (seeded position, S:786-787), 2 chase/run 3.6/4.4/5.2, 3 kick (wind-up 0.45/0.36/0.28, active 0.15, recover 0.75/0.55/0.4), 4 swat (vy 12.5 with seeded steering, landing judgment S:925-948), 5 hop (0.5 s, gap up to 3.6, dy up to 2.2), 6 run off at 7 m/s, 7 fall, 8 leap away. Gone at 10 m up, after 2.5 s, or 2 s falling (S:908) | notice 0.5/0.4/0.3 × (0.8-1.2 seeded) (S:805-806). Burst 6.5/7.5/8.5 s × temper 0.85-1.15 (S:801-802), or ends once you are past its last deck | kick, swat | its leaving sets featDone (kept). Mid-chase state lives in the same arrays (HAZ_STATE) |
| Avalanche (S:1010-1093, NGH variant S:1112-1206) | avl[o] = 0 armed, 1 running, 2 spent. Per block: arc and height relative to the trigger deck, velocity, angle, spin, status (2 waiting with its delay), rest clock, flags (S:80-86) | landing on the anchor deck (S:1017). If `time − lastAvlT < period` it is spent without firing (S:1018). Warning 1.4 s, then releases staggered 0.35 s (NGH: 0.20-0.30 s). Gravity 24, deck collisions over index a0±8 (S:1047), block-on-block collisions. A block ends 16 m below the deck, after 12 s, after 0.6 s at rest, or 30 m off along the lane | knock-off | **Spent state is not in featDone or doneFeat.** It is kept only in `avl[]` while resident, and in the RunSnapshot `aKey`/`aState`. `lastAvlT` is global and is saved by RS |

## 3. Timing table

| Mechanic | Initial state | Seed-dependent | Arrival-dependent | Trigger | Safe wait | Recovery |
|---|---|---|---|---|---|---|
| MOVE_H/V/Z, SWING | phase at t = 0 | phase, period, amp | yes (global clock) | — | yes on the platform. Waiting off it costs nothing | yes, the cycle repeats |
| Saws, pendulum | t = 0 phase | yes | yes | — | outside the sweep | cycle |
| Cannon / mortar | t = 0 phase | yes (mortars deliberately out of step) | yes | — | outside the lane | cycle |
| Spike trap / drop | t = 0 phase | yes | yes | — | trap: off the pad. Drop: on its top or in the safe pockets | cycle |
| Aimed cannon | aimAng 0, yaw 0, aimFireT −1e9 | phase/period | yes | in range plus layer, then locks at c = 0.55 | out of range or layer | cycle |
| Crumble / crumbling movers / ramp skin 1 | idle (−1) | no | — | landing or standing | no | gone 3.4 s, then back. A respawn restores everything |
| Holed bridge | closed | the hole mask (`color`) | — | landing | yes on the solid sections | holes reset 3.4 s after they open, once you are off the deck. Respawn resets them |
| Shaking ramp | onT 0, count 0 | count hash (deterministic) | — | stand 0.25 s | no | jump off |
| Spinning ramp | t = 0 heading = phase | phase, period | yes | — | partly (it carries and tips you) | — |
| Belts (STATIC/RAMP skin 4) | constant | phase = speed | no | — | no | walk against it |
| Seesaw / tilt belt | tilt 0, onT 0 | phase = belt speed | no | standing | no | relaxes at 1 /s once you are off. A respawn does **not** zero it |
| Wet platform | — | — | lurch uses global time | braking | mostly | skid cooldown 0.9 s |
| Crab | sweep (time) | phase/period/amp | sweep yes | sight plus 0.35 s | off its deck or out of sight | gives up after 0.6 s stuck plus 2.5 s cooldown |
| Bee | t-phase visit | phase/period | yes | seek within 7 m | between visits | leaves after its visit |
| Skull / blob | init on first step in hz | phase is the bob only | rest timer runs from init | on its deck (or ±1) | off its 3 decks | goes home after 1.5 s |
| Enforcer | waits at a seeded position | 9 seeded traits | no | on its decks for the notice time | before its first deck | leaves after its burst. Single use |
| Avalanche | armed | block layout from phase, period = cooldown | yes (the cooldown counts from the last one fired, globally) | landing on its deck | — | single use. A respawn does not re-arm it while resident |

## 4. Serialization coverage

Legend: Y = kept, N = not kept, (r) = recomputed, (a) = assumed by design.

### Course fields

| Field | CourseIO (JSON) | SliceCodec | Tower world (stitch/rebuild) | RunSnapshot |
|---|---|---|---|---|
| elements, all 14 fields | Y, exact `Float.toString` (IO:24-38) | Y, bit-exact floats. **zone, dir, color and skin are bytes; anchor is a short** (SC:51-63) | Y, copied (TW:388-391). Anchor remapped to world indices (TW:368, 381-382) | N (rebuilt from history, RS:12-13) |
| hazards | Y (IO:16, 45) | Y (SC:26, 43) | Y, anchor via `toWorld` (TW:372-375). Identity hazKey = slice·65536 + i | N |
| keyRooms | Y (IO:18, 47) | Y (SC:27, 44) | Y, indices remapped; kr[2] and kr[3] are counts (TW:376) | N |
| forks | Y (IO:19-20, 48-49, added in rules 14) | **N, not written** (SC:22-28) | **N, never stitched into the world** | N |
| routeCount | Y (IO:13, 50) | Y (SC:22, 46) | **world.routeCount = −1** (TW:346), so `routeSize()` = every element (C:26) | — |
| decoy index | (r) `indexDecoys` (IO:51) | (r) (SC:46) | N: empty arrays. The streaming path in `setWindow` handles this (S:130-139) | — |
| gemCheckpoints | **N** (fromJson leaves it false) | Y (SC:22, 41) | Y, set from Tuning (TW:105, 113) | — |
| prog (Progression) | **N** | Y, format 2 (SC:28, 45) | N on world. It lives on `top` Framed (TW:115, 151) | — |
| yBase / sBase frame | **N** | Y (SC:23, 39) | used for the origin (TW:365). sBase is a multiple of the circumference (CG chunkFramed: CourseGenerator.java:127) | origin saved (RS:65) |

### Sim fields

| Sim field | Tower rebuild (`rebase` S:385-431) | RunSnapshot (RS capture 56-104 / apply 184-224) | Checkpoint-only resume (SaveData + RES) |
|---|---|---|---|
| time | kept | Y (RS:60, 190) | **N: a new Sim starts at time 0** (PS:140) |
| s, y, vx, vy, facing, mode, onElem | y shifted by dy (S:428). s not shifted (a). onElem remapped (S:422) | Y. Absolute coordinates, element identities (RS:63-67, 186-193). GROUND on a slab is saved as AIR (RS:63) | N: spawns on the checkpoint |
| coyote, jumpBuf, lockout, pullT, pull from/to, ropeTopT, ledgeSide, jumpedUp, prevJumpHeld | kept, heights shifted | Y | N |
| onSlab | remapped (S:424) | **N** (saved as AIR) | N |
| sideIn / sideDir (side-entry catch) | remapped (S:401) | **N** (`syncElements` sets sideIn = −1, S:304) | N |
| lastGroundY, maxHeight, bestElem, lastPad | shifted / remapped | Y (RS:65-67, 196-201) | bestElem only through cp |
| checkpoint | remapped. Tower keeps `cpRef` (TW:242) | Y, cpSlice/cpLocal (RS:68-70, 202) | Y, SaveData cpSlice/cpLocal (CG:282-286) |
| keys, lastKeyColor | kept | Y | keys Y (`keysHeld`, RES:14, 25) |
| falls, hits, won, finishedRun | kept | Y | partly (RunRecord) |
| invuln, clubTime, swingT, shoveCd, slipCd | kept | Y (RS:61, 80) | N |
| squashT, padSquash, knocked, crabS/Y, hitBy/hitS/hitY, lastGateColor/No, landSpeed | kept, or hitBy cleared (S:429) | **N** (cosmetic or effect state) | N |
| es0/ey0/es1/ey1/edy | (r) from the rebuilt element (S:390, 398) | (r) `syncElements` (RS:222, S:292-305) | (r) |
| crumbleT, gone, goneT, tilt, onT | kept for resident elements (S:399). **Lost for unloaded ones** | Y for non-resting resident elements (RS:71-79, 205-209) | N (all fresh) |
| featDone | remapped. Unloaded entries go to `Tower.doneFeat` (TW:259, 266-272) | Y via `doneKeys` (RS:99-100, 220-221) | **N: only castle gates come back, via `openedUpTo`** (RES:23-28, TW:270). Keys taken, clubbed crabs, gone Enforcers and key-room gates (skin 0) return |
| per-hazard creature state (23 floats: aim, crab, mob*, deckOff, crabJump..., aimYaw) | remapped for hazards that stay resident (S:406-418). **Reset to fresh for hazards whose slice was unloaded** | Y for resident hazards (RS:81-89, 211-214). `pristine()` is effectively never true, because crabJump and crabFall default to −1 (S:198) while RS:108 expects 0, so every resident hazard is written | N |
| avl[] (avalanche) | remapped for resident hazards (S:419-421). **Lost when unloaded, so a spent avalanche re-arms** | Y when state ≠ 0. Old saves are widened (RS:90-98, 133, 216-219) | N |
| lastAvlT | kept | Y (RS:98, 215) | N (−1e9) |
| floorY / floorOverride | shifted / re-set (TW:263) | (r) (PS:133) | (r) |
| assistForgive, humanHold/held* | kept | N (settings; humanHold is autopilot only) | N |
| floating origin | TW:347 | Y, `originAbsY`/`originAbsS` (RS:65, 163-166) | (r) around cpSlice |
| Tower.openedUpTo | — | N (lives in SaveData, PS:134, RES:26) | Y |

Traversal-relevant items that are **not** kept, ordered by impact:
1. **SliceCodec drops `forks`, and Tower never stitches forks into the world** (SC:22-28, TW:362-379). The live world has no fork list. So a slice reloaded from history, and the live Autopilot solver, lose fork-branch information.
2. **CourseIO drops `gemCheckpoints`, `prog` and the yBase/sBase frame** (IO:11-22). A JSON fixture loses castle progression state and gem-only checkpoints, and has no frame. EndlessTest:36-38 regenerates from a CourseIO round trip, which only holds when `prog` is null.
3. **Unloading a slice resets every creature and avalanche in it** (S:406-421 copy only `m.haz[i] ≥ 0`). Only `featDone` survives, through `doneFeat`. A spent avalanche (state 2) is not a featDone item, so it re-arms after a window rebuild that unloaded it. With the global `lastAvlT` cooldown, whether it fires again depends on timing.
4. **Checkpoint-only resume** (no run.json, or a snapshot rejected at PS:137 or CG:228) keeps only seed, cp, keysHeld and openedUpTo. `time` restarts at 0, every Enforcer, clubbed crab and avalanche returns, and so do the keys taken (re-taking one is harmless) and non-castle gates.
5. **RunSnapshot omits `sideIn`/`sideDir`** (the mid-air side-entry catch, S:1855-1881). A save taken in the frames a body has slipped into a block's side resumes without the catch and can fall through. It also omits `onSlab`, which is handled by restoring as AIR just above the slab (RS:63).
6. **Respawn does not reset `tilt`, `onT`, creature state, `avl` or `lastAvlT`** (S:370-379). A retry after a fall does not start these mechanics fresh. Whether they are kept also depends on whether the respawn rebuild unloaded their slices (TW:243-250).
7. SliceCodec byte narrowing (SC:54, 62): `color` is written as a byte and read sign-extended. A hole mask with bit 7 set would corrupt the higher bits. `holes()` puts the highest bit at n−2, so this needs a bridge of 9 or more sections, roughly 11.7 m or wider (CourseGenerator.java:2430-2436). GATE `skin` holds the castle number, so castle 128 and above (about 64 km) would wrap. Both are latent. CourseIO stores full ints.

## 5. Cross-slice behaviour (TW)

- Every slice is kept as deflated bytes (TW:52, 135-137). Only slices within ±REACH 300 m of the centre height are expanded into `world` (TW:21, 222). The window is rebuilt (TW:227-237) when:
  - the player comes within MARGIN 120 m of either end (unless that end is slice 0 or the last admitted slice);
  - the window has grown past 2·REACH + 60 m;
  - or abs(local y) ≥ 1500.
  - The origin snaps to a multiple of `castleSpacing` (500 m, TW:224). The arc origin = sBase[first slice], which is always a multiple of the circumference, so `rebase` shifts y by dy only, and arcs (mobS, crabPos, avl, player s) are not shifted (S:414-421, 428).
- Growth without a rebuild: `admit()` (TW:179-188) stitches new slices while their bottom is at or below `admitAbs` = maxAbs + 100 (PS:479, PS:469). That makes it independent of the device. Indices of existing elements stay the same, and `sim.ensureCapacity` plus `setRange(0, n−1)` extend the arrays (TW:241, PS:487).
- Stitching: slice k's local 0 (its start) is the previous slice's last route element (TW:367, 385). World order is slice k's route (from `base`), then slice k's decoys, then slice k+1's route (TW:366-368). So **world index i+1 is not always the next route deck**: at a slice boundary it is the first decoy. `Course.nextRoute` covers the streaming case (C:31).
  - Sim code that steps through decks by index arithmetic: the Enforcer (`a0+deckOff`, S:790, 828), skull/blob/crab ±1 decks (S:663, 707-711, 1407-1409), avalanche target decks a0+1..a0+4 and collision decks a0±8 (S:1027, 1047).
  - Their deck tests `roamDeck` and `avlDeck` rely on `j < routeSize()`. In the world that is every element, because routeCount = −1 (TW:346, C:26), and neither test checks `anchor < 0` (S:665-670, 1002-1006). Near a slice end, live creatures and avalanche blocks can therefore treat decoy platforms or another slice's elements as decks, which differs from the slice-local proof. I did not confirm whether the generator keeps every encounter clear of the slice end; this needs verification.
- `hazardsFor` (C:65-81) brings in a cannon whose reach covers the window, an Enforcer within anchor−1..anchor+len, and an avalanche within ±8. In production the whole resident world is simulated, so this widening matters only for planners (S:138, 142).
- On a rebuild (TW:257-264):
  - featDone of unloaded hazards goes into `doneFeat`, and `applyDone` re-marks it on reload, along with gates up to `openedUpTo`.
  - Element dynamic state of unloaded elements is dropped.
  - Hazard state of unloaded hazards is dropped (it starts fresh with mobState 0 on the next step).
  - `hitBy` is cleared. `deferRespawn` is set, so a respawn waits for `maintain`.
- Respawn (TW:243-251): the window is rebuilt around the checkpoint's slice, then `completeRespawn`. A fall to a far checkpoint can unload the slice the player fell from, which resets its creatures and avalanches.
- WindowFollower (WF:7-25) is **not referenced anywhere** in main or test code outside its own file. The production window is Tower.ensureWindow/maintain.
