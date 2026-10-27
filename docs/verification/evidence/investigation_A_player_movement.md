# A. Player movement, physics and collision - source audit

Scope: `core/src/main/java/com/hotatticgames/climbup/sim/Sim.java` (read in full, lines 1-2061) plus `Tuning.java`, `Element.java`, `InputState.java`, `Course.java`, `CheckpointGem.java`, `assets/data/tuning.json`. Repository HEAD `f4df3f3`. All paths below are relative to `core/src/main/java/com/hotatticgames/climbup/sim/` unless stated. "S:" = Sim.java, "E:" = Element.java, "T:" = Tuning.java, "C:" = Course.java, "J:" = assets/data/tuning.json.

Coordinate model: arc position `s` wrapped on a ring of circumference `2*pi*radius` (C:89-99, T:55); `y` = feet height. Body is a box of half-width `halfWidth` and height `height` (T:12). Fixed step `DT = 1/60` (S:10).

---

## 1. Player state (traversal-relevant fields)

| Field (S:line) | Set by | Read by | Affects future reachability? |
|---|---|---|---|
| `s, y` (22) | every mode step; spawn 314; startOn 328-349; gate wall 1521; rebase 428 | everything | yes |
| `vx, vy` (22) | stepGround/Air/etc, doJump 1649, land 1888-1905, knockOff 358, shoves 604/622/747/845/874/1309/1317, gate 1522 | stepAir integration 1719-1733; landing relVy 1745; tryGrab `toward` 1946, cable `vy<4` 1925, ledge `vy>3.5` 1933, catchInside 1875; aim lead 1559 | yes |
| `facing` (23) | moveX>0.15 in Ground/Air/Rope/Beam/Cable/Slab; rope jump 1972; spring 1891; ledge 1953 | rope jump direction when stick neutral 1971; club swing reach 599; shove side fallback 603 | yes (rope jump with neutral stick goes `facing`) |
| `mode` (24) `{GROUND, AIR, ROPE, CABLE, LEDGE, PULLUP, BEAM}` (8) | see section 2 | dispatch 540-548; creatures only act in GROUND/AIR (602, 620, 746, 842, 1315); gem pickup skips LEDGE/PULLUP 572 | yes |
| `onElem` (25) | land 1898, grab 1918/1926/1950/1953, catchInside 1879, spawn 315; -1 on leave | all attached modes; seesaw/ramp `on` 511/520; crumble/hole timers 528; MOVE_Z kept in plane while stood on 507; avalanche trigger 1017; creature `standOff` 676 | yes |
| `onSlab` (27) | landOnSlab 1771; cleared 1773, 1782, 1794, 1795, spawn 316 | dispatch 541 (`GROUND && onElem<0 && onSlab>=0` -> stepSlab) | yes (slab = surface). NOT cleared by knockOff 355-359 or shoves (harmless: land() sets onElem>=0) |
| `lastPad` (26) | pad/spring bounce 1893, startOn 330; reset -1 at respawn 373 | not read inside Sim; read by Autopilot (312, 323, 441, 610...) and PlayScreen 551 | no (planner bookkeeping) |
| `coyote` (29) | `T.coyote+assistForgive` each GROUND step 1676, slab 1771/1793, land 1898; `T.coyote` (no assist) at pull end 2049, beam 1994/1997; 0 in doJump 1649, knockOff 357, spawn 315, skull/enforcer/blob shoves, shaking ramp 1707 | decremented ONLY in stepAir 1727-1730 | yes (see rare cases 6.1) |
| `jumpBuf` (29) | press: `T.jumpBuffer+assistForgive` 537, else decays; zeroed doJump 1649, stepLedge 2029, beam mount end 1994, spawn 315 | stepGround 1677, land 1905, stepAir coyote 1729, stepRope 1970, stepCable 2015, stepLedge 2028, stepBeam 2002, stepSlab 1794, landOnSlab 1773 | yes |
| `lockout` (29) | spawn 0.1 (315); grabLockout 0.28 after rope/cable/beam jump/leave and ledge drop (1973, 1976, 2002, 2005, 2016, 2019, 2025, 2034); shoves 0.25-0.4 (604, 622, 747, 845, 874, 1317); blob launch 0.2 (1309); beam->rope sets 0 (2004) | ONLY gates `tryGrab` 1755 (ropes, cables, ledges). NOT landing, NOT catchInside | yes |
| `pullT, pullFromS/Y, pullToS/Y` (29) | ledge/mantle/catchInside start; stepPull/stepBeam advance | stepPull 2037-2055, stepBeam 1990-1996; `pullToS/Y` only shifted in rebase 428 (never read) | transient |
| `ledgeSide` (30) | tryGrab 1950/1953, catchInside 1879 | stepLedge 2026/2033, stepPull 2042-2043 | transient |
| `sideIn, sideDir` (31) | trackSideEntry 1863; cleared 536 (any non-AIR mode), 1873, 1876, 1877, syncElements 304 | catchInside 1754 | yes (extra catch, see 2.H) |
| `ropeTopT` (32) | stepRope 1978; 0 at 1979, 2004 | stepRope -> BEAM 1979 | transient |
| `jumpedUp` (34) | true only in doJump 1649; false on vy<=0 1726, land, leave edge, shoves, pads | jump cut 1725 | yes (variable height) |
| `prevJumpHeld` (35) | 549 | nothing in Sim (saved by RunSnapshot) | no |
| `lastGroundY` (36) | every non-AIR step 564; spawn 315 | finite-course fall respawn 567 | yes in finite courses only |
| `onT[]` per element (37) | RAMP: += dt while stood on, decays 1.5x off it (521); shaking-ramp reset 1704; tilt-belt SEESAW: side sign 515 | shaking ramp kick 1703; tilt-belt carry 452 | yes |
| `tilt[]` per element (38) | SEESAW approach 516; blob launch 1310; SHAKING RAMP reuses it as a kick counter 1704 | seesaw surface 517, uphill slow 1661, slide 1675; ramp kick hash 1705 | yes |
| `floorY` (40) / `floorOverride` (50) | setRange 182 (whole-map lowest platform minus amp minus 1 m, or override from Tower) | fall respawn 566 | yes |
| `checkpoint` (41) | land 1900, stepGround 1711, pull end 2050, gem touch 579, gate open 1518, spawn 312 | respawn target 367 | yes |
| `bestElem` (42) | `progress()` 164 | not read in Sim (progress/score) | no |
| `maxHeight` (43) | 551 | not read in Sim | no |
| `won` (44) | GOAL land 1902 / stand 1712 / pull 2052 | game flow | - |
| `falls` (45), `hits` (66) | respawn 372; hazard hit 562 | `setbacks()` 362 (solver rejects moves with setbacks) | solver-relevant |
| `landSpeed` (46) | land 1888, slab 1771 | not read in Sim (feedback) | no |
| `invuln` (65) | 0.7 at respawn 372 and knockOff 358; decays 561 | gates hazardHit 561-562 and every creature contact (602, 620, 746, 842, 873, 1315) | yes |
| `shoveCd` (111) | 0.9-1.0 after any shove | gates creature shoves (602, 620, 746, 842, 873) - NOT blob squash 1315 | yes |
| `keys` (67), `featDone[]` (69), `keysFree` (68) | key touch 1509; gate open 1514; club/creature knocked 587/600/618/699 | gate wall 1513-1524 | yes (gates are walls) |
| `clubTime, swingT` (111) | club pickup 587 (30 s, 1334); swing start 557; -5 s per knock 611; skull breaks it 699 | creature knock-off windows 598, 616, 697 | yes (removes creatures permanently) |
| `slipCd` (108) | wet skid 1670; decays only in stepGround 1672 | wet skid 1670 | minor |
| `squashT` (107) | blob squash 1318 | drawing only (comment 108) | no |
| `assistForgive` (54) | PlayScreen.java:194 (= `assistJumpForgiveness` 0.08 when the assist is on) | coyote and jump buffer lengths | yes |
| `humanHold, heldX/Y, heldXT/YT` (474-475) | autopilots only (`Autopilot.HUMAN_HOLD = 6`, Autopilot.java:43) | input hold 483-486 | planner-only |
| `deferRespawn, respawnPending` (49) | PlayScreen/Tower | step frozen while pending 481 | yes (managed window) |

Input (`InputState.java:5-6`): `moveX, moveY, jumpHeld, jumpPressed, swingPressed`. S:482/469: every stick axis is quantised to -1/0/+1 (dead zone `STICK_DEAD = 0.05`, 464) and written BACK into the caller's `InputState` (487). Consequently every later threshold (`> 0.15`, `> 0.3`, `< -0.6`, `< -0.7`, `> 0.5`) is just "pushed that way".

---

## 2. Mode transitions (all implemented paths)

Notation: `hw = el.halfW()`, `top = ey1[i]`, `hand = y + handHeight`.

### A. GROUND -> * (`stepGround`, S:1653-1714; order inside is significant)

| # | To | Condition | Line | Sets |
|---|---|---|---|---|
| G1 | AIR (jump) | `jumpBuf > 0` (checked after this step's run/carry move) | 1677-1688 | `vx += 0.6*platformVs(e)` (platform arc speed incl. belt/spin carry, 445); `boost = 0.6*max(0, platformVy)`; SWING: `f = swingFrac`, `k = f^4`, `boost += swingSuper*k`, `vx += sign(f)*swingSuperVx*k` (EV_BOUNCE if k>0.5); `doJump(boost)`: `vy = jumpVel+boost`, `jumpedUp=true`, coyote=0, jumpBuf=0, onElem=-1 (1648-1651) |
| G2 | GROUND on neighbour (step across) | body within 0.15 m of (or past) the current surface's end, a FIXED neighbour (STATIC, CRUMBLE, GOAL, non-spinning RAMP; not gone) whose span contains `s` (no overhang) and whose surface at `s` is within `[y-0.05, y+STEP_ACROSS(0.2)]`; highest wins | 1690-1691, 1809-1824 | `land(nb, surfaceAt(nb))` with the EV_LAND bit suppressed; land() still applies checkpoint/crumble/goal |
| G3 | AIR (walk off / drop) | `|ds| > hw + edgeOverhang(0.2)` OR `gone[e]` OR holed bridge with holes open and centre over a hole by `HOLE_FOOT=0.2` | 1694-1698 | `vy = min(0, platformVy)`, `vx += 0.5*platformVs`, jumpedUp=false. **coyote is left at `T.coyote+assist` (set at 1676)** |
| G4 | AIR (shaking ramp kick) | RAMP skin 2, `onT >= RAMP_SHAKE_EVERY (0.25 s)` | 1703-1708 | deterministic hash `h` of (counter `tilt[e]`, index): `vx = +-(3.0 + 0.6*(h%5))` (3.0-5.4), `vy = 10 + 1.2*(h%4)` (10-13.6), coyote 0, EV_BOUNCE |
| G5 | stay GROUND | otherwise: crumble timer started (1701), hole timer started (1702), checkpoint if `isCheckpoint && e > checkpoint` (1711), GOAL -> won (1712) | | |

Run model inside stepGround: carry `s += platformDs(e) + surfaceCarry(e)*dt; y = ey1[e]` (1657); target `moveX*runSpeed`; SEESAW uphill slow `target *= max(0.2, 1 - seesawUphill*up)` with `up = tilt*sign(target)/seesawMaxTilt` (1661); accel `groundAccel` when pushing same way or nearly stopped else `groundDecel` (1662-1663); wet (STATIC color 7, E:57-58) multiplies accel by `wetGrip` (1665), adds lurch `sin(time*wetLurchRate)*wetLurch*dt` (1669) and, braking from `|vx|>1.6`, a skid `vx *= wetSkid` once per 0.9 s (1668-1670); `s += vx*dt` (1674); SEESAW slide `s -= tilt*seesawSlide*dt` (1675).

### B. GROUND on a spike-slab top (`stepSlab`, S:1778-1796) - GROUND with `onElem=-1, onSlab>=0`

| To | Condition | Line | Sets |
|---|---|---|---|
| AIR (slam bounce) | slab top moving down faster than `SLAB_SLAM_SPEED=6` m/s | 1781-1783 | `vy = SLAB_BOUNCE_SPEED (6)`, coyote 0, EV_BOUNCE |
| AIR (jump) | `jumpBuf>0` | 1794 | `doJump(0.6*max(0,pv))` |
| AIR (walk off) | `|ds| > w/2 + edgeOverhang` | 1795 | `vy = min(0,pv)`; coyote left set |
Carry: `y = top`, `s += carryS diff` (1785-1786); run as ground but no wet/seesaw/stepAcross/checkpoint logic.

### C. AIR -> * (`stepAir`, S:1716-1756), checked in this order

1. Air control (1718-1720): if stick pushed and NOT already faster than run speed in that direction -> `approach(vx, moveX*runSpeed, airAccel*dt)`; else `approach(vx, 0, airDrag*dt)` (so neutral stick, or over-speed with stick the same way, only drags at 3 m/s^2).
2. Gravity `vy -= gravity*dt`, clamp `-maxFall` (1723-1724).
3. Jump cut (1725): `jumpedUp && !jumpHeld && vy > jumpVel*jumpCutMul` -> `vy = jumpVel*jumpCutMul` (5.175 m/s). `jumpedUp` cleared when `vy<=0` (1726).
4. **Coyote jump** (1727-1730): `coyote -= dt`; if `jumpBuf>0 && coyote>0` -> `doJump(0)` (no platform velocity inherited), return before moving.
5. Integrate `s += vx*dt; y += vy*dt` (semi-implicit Euler) (1733); `trackSideEntry()` (1734).
6. **Landing** (1736-1751): over all simulated elements (`act` window): `isPlatform()` (E:87: not ROPE/CABLE/hazard), not `gone`, `|ey1-y| <= 8`, `|ds| <= hw + edgeOverhang`, not over an open hole, `vy - platformVy(i) <= 0`, and feet crossed the top this step: `py >= ey0[i] - 0.08 && y <= ey1[i] + 0.0001`. Highest top wins -> `land(best)`.
7. `landOnSlope` (1752, 1833-1848): RAMP only, surface measured at the NEW `s` (`el.y + rampSlope*x`), `py >= ey0-0.08 && y <= surf`; `vy = min(vy,0)` then `land(best, surf)`. Catches bodies that sank into a slope while rising (no relVy test).
8. `landOnSlab` (1753, 1759-1775): SPIKE_DROP hazard tops (`dropTop = dropBottom + 1.0`, E:196) incl. carry; `|ds| <= w/2 + edgeOverhang`, `vy - slabVy <= 0`, crossing test as 6 -> GROUND/onSlab; buffered jump applies at once (1773).
9. `catchInside` (1754, 1870-1881) if `sideIn>=0` - NOT lockout-gated (see H).
10. `tryGrab` (1755, 1910-1959) only if `lockout <= 0` (see D-F).

### D. `land(i, in, surface)` (S:1885-1906) - the outcome of every landing

- PAD or SPRING (1889-1897): `v = jumpHeld ? padBounceHeld (24) : padBounce (21)` sampled at the landing step; PAD `vy = v`; SPRING `vy = v*cos(amp)`, `vx = v*sin(amp)` (amp = angle from vertical, E:8); `jumpedUp=false` (no jump cut), mode AIR, `lastPad=i`, `padSquash=0.25`, EV_BOUNCE. vx is kept for a PAD. coyote not touched.
- Everything else: `vy=0`, GROUND, `onElem=i`, coyote reset, EV_LAND; checkpoint if `isCheckpoint(el) && i > checkpoint` (1900); crumble timer start if `crumbles()` (1901; holed bridges are NOT `crumbles()`, their timer starts on the first stepGround 1702); GOAL -> won (1902); then **buffered jump on landing** if `jumpBuf>0`: `vx += 0.6*platformVs`, `doJump(0.6*max(0, platformVy))` (1905) - no swing super-boost here (that is only in stepGround 1682).

### E. Rope (`tryGrab` 1916-1921, `stepRope` 1961-1980)

| From -> To | Condition | Line | Sets |
|---|---|---|---|
| AIR -> ROPE | `lockout<=0`, `|ds| <= ropeGrabRadius (0.42)`, `yBottom <= hand <= el.y + 0.1`. **No vy limit** (ropes are tested before the `vy > 3.5` ledge cut-off), first rope in index order wins | 1917-1920 | `vx=vy=0`, `s = es1[i]`, EV_GRAB/EV_ROPE, progress |
| ROPE climb | `y += moveY*climbSpeed*dt` clamped to `[yBottom-0.2, el.y-handHeight]` | 1964-1968 | `s = es1[i]`, `vx=0` |
| ROPE -> AIR (jump) | `jumpBuf>0` | 1970-1974 | dir = sign(moveX) if pushed, else `facing`; `vx = dir*runSpeed*0.95` (5.32); `doJump(-0.12*jumpVel)` -> vy 10.12; lockout 0.28 |
| ROPE -> AIR (let go) | `moveY < -0.7` AND `y <= yMin + 0.01` (only at the very bottom) | 1976 | `vy=0`, lockout 0.28 (vx already 0) |
| ROPE -> BEAM | at top (`y >= yMax-0.01`) holding up (`moveY>0.3`) for `ROPE_TOP_HOLD=0.06 s` | 1978-1979 | pullT 0, EV_PULL |

There is no mid-rope release other than jumping (or a hazard knock-off).

### F. Beam (rope top) (`stepBeam`, S:1986-2006; constants S:33)

- Mount animation `BEAM_MOUNT_TIME = 0.62 s` (input ignored; y eased to `el.y + BEAM_TOP (0.24)`), at end `coyote = T.coyote`, `jumpBuf = 0` (1990-1995).
- Standing: `y = el.y+0.24`, `vy=0`, `coyote = T.coyote` every step (1997); walk `vx = moveX*runSpeed*0.55` (3.08 m/s, instant, 1998-2000).
- BEAM -> AIR jump: `jumpBuf>0`: `vx = moveX*runSpeed` if pushed else 0, `doJump(0)` (full jumpVel), lockout 0.28 (2002).
- BEAM -> ROPE: `moveY < -0.7`: to rope top `y = el.y - handHeight`, `lockout = 0`, ropeTopT 0 (2004). coyote stays at `T.coyote` (see 6.1).
- BEAM -> AIR walk off: `|d| > BEAM_HALF (1.1)` -> `vy=0`, lockout 0.28, coyote stays `T.coyote` (coyote jump possible) (2005).

### G. Cable (`tryGrab` 1922-1929, `stepCable` 2008-2021)

| From -> To | Condition | Line | Sets |
|---|---|---|---|
| AIR -> CABLE | `lockout<=0`, `|ds| <= w/2`, `el.y-0.45 <= hand <= el.y+0.15`, `vy < 4` | 1925-1928 | `vx=vy=0`, `y = el.y - handHeight` |
| CABLE shimmy | `s += moveX*cableShimmy*dt` (3.6 m/s) | 2011 | `y` pinned |
| CABLE -> AIR jump | `jumpBuf>0` | 2015-2016 | `vx = moveX*runSpeed` (0 with neutral stick), `doJump(-0.25*jumpVel)` -> vy 8.625, lockout 0.28 |
| CABLE -> AIR drop | `moveY < -0.7` OR shimmied past `w/2 + 0.1` | 2018-2019 | `vx = moveX*2`, `vy=0`, lockout 0.28 |

### H. Ledges, mantle, side-catch, pull-up

**Ledge hang (AIR -> LEDGE)** `tryGrab` 1933-1956, only when `lockout<=0`, after ropes/cables, and only if `vy <= 3.5` (1933). For each platform in index order (FIRST match wins, not the nearest; decoys included), not gone:
- `hand >= top - ledgeReachBelow (0.9)` (1939);
- not mantle (`hand <= top + ledgeReachAbove (0.15)`) and `y < top - 0.1` (1941) -> feet in `[top-2.05, top-1.00]`;
- horizontal: `edgeDist = |ds| - hw` in `[-0.12, +ledgeReachX (0.4)]` (1943-1944): body centre up to 0.4 m outside the face (the 0.28 m half-width means the body side is 0.12 m short = the "fingertips" reach) or 0.12 m inside it;
- moving toward it: `vx > 0.3` or stick toward the platform (1946-1947). Any platform type, any edge (both ends), any mover.
- Sets: LEDGE, `vx=vy=0`, `facing=side`, `s = es1 - side*(hw + 0.24)`, `y = top - handHeight + 0.08` (1953-1955).
- PAD/SPRING instead bounce as if landed (`y = top; land`) (1948).

**Mantle / step-up (AIR -> PULLUP, no hang)** same loop, when `hand > top + 0.15`: feet in `(top-1.0, top-0.05)` and `edgeDist in [-0.45, 0.4]`, toward, `vy<=3.5` -> PULLUP directly (1949-1952). jumpBuf is NOT cleared.

**Side catch (AIR -> PULLUP)** `trackSideEntry` 1855-1865 / `catchInside` 1870-1881. Platform sides and undersides are not solid (no ceiling or wall collision exists anywhere for platforms). While AIR, if the body's centre crosses from `|ds| >= hw` to `< hw` with vertical overlap (feet below `top + sideSlack` where sideSlack = -0.05 flat, +0.5 RAMP/SEESAW, 1868; head above `top - slab() + 0.05`, slab() = 1 m for w>=5 or GOAL else 0.5 m, E:86), it is remembered. Later steps: abandoned if out of `hw+0.28`, feet cleared the top, or hands below `top - 0.9`; waits while `y >= top-0.05` or `vy > 3.5`; else PULLUP from inside with `ledgeSide = sideDir` (PAD/SPRING bounce instead). **Not gated by lockout and needs no stick input.**

**LEDGE (`stepLedge`, 2023-2035)**: re-pinned to the (possibly moving) edge every step (2026-2027). LEDGE -> AIR if `gone[i]` (2025, lockout 0.28). LEDGE -> PULLUP on `moveY > 0.5` OR `jumpBuf > 0` (jumpBuf zeroed) (2028-2031). LEDGE -> AIR on `moveY < -0.6` or stick away `> 0.6` (2033-2034; `vx=vy=0`, lockout 0.28). No hang time limit; you cannot jump away from a ledge. Hanging does NOT start a crumble timer.

**PULLUP (`stepPull`, 2037-2055)**: over `pullUpTime (0.32 s)`, y eased up first (`k*1.6`) then s forward after `k>0.45`, toward `es1 - side*(hw - 0.35)` (0.35 m in from the near edge) unless the body is already further in (lifted straight up, 2043). Tracks the platform live (`es1`, `ey1`). End: GROUND, `coyote = T.coyote`, checkpoint, crumble start, GOAL win, progress (2048-2054). **Does not check `gone[i]`**: a pull onto a platform that crumbled mid-pull completes, then the next stepGround drops you (1695). No input is read during the pull; a jump pressed in its last 0.13 s is still buffered and fires on the first GROUND step.

### I. Launches, shoves, hits (forced AIR)

| Source | Line | Gate | Effect |
|---|---|---|---|
| `knockOff()` (all lethal hazards) | 355-359, called only from 562 | `invuln <= 0` and `hazardHit()` | AIR, onElem -1, coyote 0, jumpBuf 0, `vx = +-hitKnockSide (6.9)` away from `hitBy.sAt(time)`, `vy = hitKnockUp (6.9)`, invuln 0.7, `hits++`. Does NOT set lockout, jumpedUp=false, or onSlab=-1. From any mode. |
| Crab shove | 602-605 | shoveCd, invuln, GROUND/AIR, `|dx| < 0.55+hw`, vertical overlap | `vx = +-9`, `vy = max(vy, 6)`, lockout 0.3, shoveCd 0.9, jumpedUp false. **coyote not cleared** |
| Bee bump | 620-623 | same, BEE_R 0.5 | `vx = +-BEE_PUSH_X (8)`, `vy = max(vy, 5)`, lockout 0.25, shoveCd 1.0. **coyote not cleared** |
| Skull dash hit | 746-748 | state 3 only | `vx = dir*14`, `vy = max(vy, 9.5)`, coyote 0, lockout 0.35, shoveCd 0.9 |
| Enforcer kick | 842-847 | state 3 active window | `vx = dir*9.5`, `vy = max(vy, 6.5)`, coyote 0, lockout 0.3 |
| Enforcer swat | 873-876 | state 4 contact | `vx = +-8`, `vy = min(vy, -2)` (driven DOWN), lockout 0.3 |
| Blob squash | 1315-1319 | invuln only (NOT shoveCd), GROUND/AIR, within `BLOB_R+hw`, `surf-0.6 < y < surf+1.4` | `vx = +-9`, `vy = max(vy, 7)`, lockout 0.4, shoveCd 0.9, squashT 0.45 |
| Blob seesaw launch | 1306-1312 | NO invuln/shoveCd gate; you stand GROUND on the SEESAW and the blob lands on the far end (side recorded at shadow lock 1288) | `vy = jumpVel*BLOB_LAUNCH (sqrt3) = 19.92` (approx 5.8 m), vx unchanged, lockout 0.2, sets `tilt = side*seesawMaxTilt` |
| Shaking ramp | 1703-1708 | see G4 | |
| Slab slam | 1781-1783 | see B | |
| Castle gate (closed) | 1519-1524 | any mode, `|dx| < w/2 + hw`, vertical overlap | wall: `s` pushed to `e.s +- (half+0.01)` on the side you came from (`ps0`), vx zeroed if moving into it, EV_BLOCKED |

Shoves are not counted in `setbacks()` (362); hazard hits are.

### J. Falls and respawn

- Endless/managed world (shipped game): `floorY = lowest platform of the whole tower - |amp| - 1` (179-182, or `floorOverride` from Tower, Tower.java:253). Respawn only when `y < floorY - 6` (566). A fall therefore ends wherever the body next lands on an earlier part of the spiral; there is no "fell N metres" reset.
- Finite course (no floor): respawn when `y < lastGroundY - fallRespawnDepth (12)`; `lastGroundY = y` on every non-AIR step (564, 567).
- `respawn()` (365-368): if `deferRespawn` only flags `respawnPending` (step frozen, 481) and Tower.maintain rebuilds and calls `completeRespawn` (Tower.java:243-250).
- `completeRespawn` (370-379): `falls++`, invuln 0.7, `lastPad=-1`, all `crumbleT=-1` and `gone=false` (this also resets holed bridges), then `spawnAtCheckpoint`: centre of the checkpoint platform, GROUND, `vx=vy=0`, coyote 0, jumpBuf 0, lockout 0.1, `facing=1`, onSlab -1 (311-318). NOT reset: keys, featDone (gates stay open, knocked creatures stay gone, avalanches stay spent), clubTime, tilt/onT (seesaw tilt, ramp counters, belt direction), creature states, sideIn, ropeTopT, slipCd, shoveCd.
- Checkpoint index only ever increases (`i > checkpoint`): land 1900, stand 1711, pull 2050, gem 576-579, castle gate 1518.

### K. Carry by movers, belts, ice

- Movers (MOVE_H, MOVE_V, SWING; MOVE_Z depth): positions from `Element.sAt/yAt` (E:313-338); per-step arc delta from `sOffsetAt` (447) and rise from `yOffsetAt` (504). Standing: `s += platformDs + surfaceCarry*dt`, `y = ey1` (1657) - the body is glued (no separation even if a platform drops faster than gravity; only spike slabs throw you off, B).
- Velocity inheritance: jump `+0.6*platformVs`, vertical boost `+0.6*max(0, platformVy)` (1679-1680, 1905); walk-off `+0.5*platformVs`, `vy = min(0, platformVy)` (1696-1697); coyote jump inherits nothing (1729).
- `surfaceCarry` (450-454): RAMP skin 3 (spin) `SPIN_CARRY 2.6 * sin(spinAngle)`; belts `beltV = -phase` (RAMP skin 4, STATIC skin 4, E:108-110); SEESAW skin 4 (tilt belt) `(onT != 0 ? onT : 1) * phase` toward the end last stood on (452, 515).
- MOVE_Z: `gone = |z| > Z_REACH (1.0)` unless you stand on it (507); not landable while out of plane; no s/y motion.
- Ice: section A (wetGrip, lurch, skid).

---

## 3. Movement constants

Tuning fields (T: default / J: shipped value). Fields absent from tuning.json fall back to the Java default.

| Field | Java (T:line) | tuning.json | Use |
|---|---|---|---|
| radius | 10 (7) | 10 | ring circumference 62.83 m |
| runSpeed | 5.6 (8) | 5.6 | ground/air target, rope/cable jump vx, beam 0.55x |
| groundAccel / groundDecel | 48 / 60 (8) | 48 / 60 | 0->5.6 in 0.117 s, stop in 0.093 s |
| airAccel / airDrag | 28 / 3 (8) | 28 / 3 | 0->5.6 in 0.2 s; neutral drag 3 m/s^2 |
| gravity | 34 (9) | 34 | also Enforcer ballistic |
| jumpVel | 11.5 (9) | 11.5 | apex 1.945 m continuous (about 1.85 m with the 60 Hz semi-implicit step), time to apex 0.34 s; flat running jump about 3.8 m |
| jumpCutMul | 0.45 (9) | 0.45 | cut to 5.175 m/s; minimum tap jump about 0.4 m |
| maxFall | 24 (9) | 24 | 0.4 m per step |
| wetGrip / wetLurch / wetLurchRate / wetSkid | 0.08 / 2.2 / 5.3 / 1.12 (10) | same | ice |
| coyote / jumpBuffer | 0.12 / 0.13 (11) | same | + assistJumpForgiveness 0.08 each when the assist is on |
| halfWidth / height / handHeight / edgeOverhang | 0.28 / 1.25 / 1.15 / 0.2 (12) | same | body box, hands, stand/land overhang |
| padBounce / padBounceHeld | 21 / 24 (13) | same | about 6.5 m / 8.5 m continuous (about 6.3 / 8.3 discrete) |
| climbSpeed / ropeGrabRadius / cableShimmy / grabLockout | 3.4 / 0.42 / 3.6 / 0.28 (14) | same | |
| ledgeReachX / ledgeReachBelow / ledgeReachAbove / pullUpTime | 0.4 / 0.9 / 0.15 / 0.32 (15) | same | |
| crumbleDelay / crumbleRespawn | 0.5 / 3.4 (16) | same | CRUMBLE and crumbling RAMP; holes stay open 3.4 s |
| moverCrumbleDelay | 1.6 (19) | **absent** -> 1.6 | crumbling MOVE_H/MOVE_V/SWING (skin 1) |
| swingSuper / swingSuperVx | 5.5 / 3.5 (20) | **absent** -> 5.5 / 3.5 | swing catapult, max vy 17 (about 4.25 m) |
| fallRespawnDepth | 12 (21) | 12 | finite courses only |
| hitKnockSide / hitKnockUp | 6.9 / 6.9 (22) | same | knockOff |
| seesawMaxTilt / Rate / Relax / Slide / Uphill | 0.55 / 1 / 1 / 5 / 0.45 (23) | same | |
| beltSeesawDead | 0.5 (24) | 0.5 | tilt-belt calm middle (513) |
| assistJumpForgiveness / assistSlowFactor | 0.08 / 0.8 (37) | same | slow factor is applied outside Sim (PlayScreen.java:299 scales accumulated time; Sim's DT is unchanged) |

Sim constants: `DT 1/60`, `SWING_TIME 0.34` (10); `SLAB_BOUNCE_SPEED 6`, `SLAB_SLAM_SPEED 6` (28); `BEAM_MOUNT_TIME 0.62`, `BEAM_TOP 0.24`, `BEAM_HALF 1.1`, `ROPE_TOP_HOLD 0.06` (33); `RAMP_SHAKE_EVERY 0.25` (436); `SPIN_CARRY 2.6` (438); `STEP_ACROSS 0.2` (440); `STICK_DEAD 0.05` (464); `HOLE_FOOT 0.2` (609); `CLUB_SECONDS 30` (1334); `CLUB_HIT_COST 5` (1358). Hard-coded thresholds: ledge/side-catch rise cut-off 3.5 m/s (1875, 1933), cable vy < 4 (1925), rope jump 0.95x run / -0.12 jumpVel (1972-1973), cable jump -0.25 jumpVel (2016), cable drop vx = 2*moveX (2019), hang offset 0.24 / +0.08 (1954-1955), pull target 0.35 in (2042), knock invuln 0.7 (358, 372).
Element: `Z_REACH 1.0` (E:304), `HOLE_SEC 1.3`, `HOLE_DELAY 0.45` (E:93), `BLOB_LAUNCH 1.7320508` (E:227), `DROP_H 1.0`, `DROP_SPIKE_H 0.5` (E:192-194).

**Difficulty**: `Tuning.hardCopy(level)` (T:70) is a JSON clone with only `hard` changed. Sim never reads `T.hard`; it only calls `T.react()` = `REACT[hard]` = {1, 1.25, 1.5} (T:60, 68), which scales creature and cannon timings/speeds only (skull 696-762, blob 1262-1302, crab 1387-1420, bee drift 641, Enforcer 781ff, aimed cannon turn 1566). **No player movement, collision or grab constant changes with difficulty.** (What differs per difficulty is content: DifficultyProfile/CourseGenerator, out of this file's scope.)

---

## 4. Collision and contact by element type

General: platforms are one-way from above (landing requires `relVy <= 0` and a top crossing, 1745-1747). There is no head/ceiling collision and no solid side wall for any platform; sides are handled only by ledge grab / mantle / side-catch (H). The only player-blocking walls are closed castle GATEs (1519-1524). Contact geometry is 1-D along the arc (`|ds|` vs half-widths) plus height; the depth axis exists only for MOVE_Z (in or out of plane).

| Type | Platform? (E:87) | Player contact |
|---|---|---|
| STATIC | yes | stand/land/ledge/step-across; color 7 = wet ice; skin 1 + color mask = holed bridge (sections open 0.45 s after the first standing step, open 3.4 s, fall through at 0.2 m inside a hole; landing skipped over holes, 1744); skin 3 = gem checkpoint (endless, C:18); skin 4 = conveyor `-phase` |
| CRUMBLE | yes | timer from landing/standing; `gone` after 0.5 s; back after 3.4 s (530-532); the timer keeps running after you leave |
| MOVE_H / MOVE_V | yes | carry; skin 1 crumbles after 1.6 s |
| MOVE_Z | yes | landable only while `|z| <= 1`; carries while stood on |
| SWING | yes | carry along the arc `len*sin(amp*sin(ang))`; jump near arc ends adds up to +5.5 vy / +3.5 vx; skin 1 crumbles after 1.6 s |
| PAD | yes | any landing, ledge contact or side-catch bounces 21 / 24 (held) vertically |
| SPRING | yes | bounce at angle `amp`: `vy = v*cos`, `vx = v*sin` |
| ROPE | no | grab radius 0.42, climb, beam at top |
| CABLE | no | grab within span, hand band -0.45/+0.15, vy < 4 |
| GOAL | yes (slab 1 m) | landing/standing/pull -> `won` |
| SEESAW | yes | surface `y + tilt*x` at the player's position; tilts toward you at rate 1 up to 0.55; uphill slow and downhill slide; skin 4 tilt-belt; blob launch |
| RAMP | yes | surface `y + rampSlope*x`; landOnSlope; skin 1 crumbles (0.5 s), 2 shakes (kick every 0.25 s on it), 3 spins (slope `amp*cos`, carry 2.6*sin), 4 conveyor; step-across target unless it spins |
| SPIKE_DROP | hazard; top is a surface | underside band `[bottom, bottom+0.5]` lethal (1638-1640); top `bottom+1.0` landable via landOnSlab; slam faster than 6 m/s throws you up at 6 m/s |
| SAW_H/SAW_V/PENDULUM/CANNON/MORTAR | hazard | lethal discs: saw 0.55, pendulum 0.5, cannon 0.42 (every trailing ball, 1615-1624), mortar 0.42 only while above the muzzle +0.35 (E:298) |
| SPIKE_TRAP | hazard | lethal while extended (cyc window, E:296) if feet in `[y-0.5, y+spikeHeight-0.08]` (1637) |
| SPIKE_BLOCK | hazard | solid lethal box `[y, y+len]` (1641-1642); not standable |
| AIMED | hazard | ball disc AIM_R 0.4 (1576-1582) |
| AVALANCHE | hazard list | trigger: GROUND on its anchor deck after cooldown (1017-1019); falling blocks are lethal discs (1209-1222) |
| KEY / GATE / CLUB | feature | touch pickup (1509, 587); closed gate = wall, opens with matching key (or `keysFree`) and sets checkpoint to its deck (1513-1518) |
| CRAB / BEE / SKULL / BLOB / ENFORCER | creature | shoves/launches only (I); never `hits`; GROUND/AIR only |

Hazard body box (1609): half-width `0.25` (halfWidth-0.03), from `y+0.12` to `y+1.15`. Hazards that sit on their anchor (SPIKE_TRAP, SPIKE_BLOCK, SPIKE_DROP, SAW_H, CRAB; E:69-71) ride a moving anchor via carryS/carryY (1591-1602). A hit always means knockOff (I), never a direct respawn; a respawn can follow only if the knock-back fall goes below the floor (J). Hazards hit in EVERY mode (rope, cable, ledge, pull-up, beam included); creatures do not touch the player on ropes, cables, ledges, pull-ups or beams.

---

## 5. Update order in `Sim.step` (S:480-568)

1. Return if `respawnPending` (481).
2. Quantise stick, apply humanHold, write back into `in` (482-487).
3. `ps0/py0` saved, `time += DT` (489-490).
4. Element loop (495-534): movers' positions (MOVE_Z `gone` uses the mode/onElem from BEFORE this step's move); SEESAW tilt and RAMP surface at the player's start-of-step `s`; pad squash; holed-bridge timers; crumble timers (`gone` set here).
5. `sideIn=-1` if not AIR; jumpBuf refresh/decay; lockout decay (536-538).
6. Mode step (540-548).
7. `prevJumpHeld`, wrap `s`, `maxHeight` (549-551).
8. Timers: shoveCd, squashT, clubTime, swingT / start a club swing (553-557).
9. `stepFeatures()` (558): crabs/bees/club, skulls, blobs, Enforcers, avalanches, keys, gates - in hazard index order; each may shove, launch or wall the player AFTER this step's movement/landing.
10. `stepAimed()` (559), `stepGemPickup()` (560).
11. `invuln` decay, else `hazardHit()` -> knockOff (561-562).
12. `lastGroundY` (564); fall test -> respawn (566-567).

Order-dependent outcomes:
- A jump pressed this step acts this step (jumpBuf set at 537 before the mode step).
- A crumble that expires in step 4 is seen by step 6 of the same frame (drop, or no landing).
- Seesaw/ramp heights are computed at the start-of-step position; standing on a ramp, `y` lags the slope by one step (1657 vs 518-523).
- Landing then creature shove then hazard hit within one step: the last writer wins; knockOff (11) overrides any shove or landing in that step.
- `invuln` is decremented before the hit test in the same branch (561: `if invuln>0 decay else test`), so the first hit test happens the step after invuln reaches 0.
- Gate push-out (9) happens after movement, so a body may be inside a gate for the movement phase (e.g. landing tests) of that step.
- Within the landing loop the highest qualifying top wins; ledge grabs take the FIRST qualifying element in `act` order.

---

## 6. Mechanics a solver could miss (rare or unusual)

1. **Stale coyote = mid-air jump.** `coyote` only decrements in stepAir (1727-1728). Paths that leave AIR-capable coyote:
   a) BEAM sets `coyote = T.coyote` every step (1997); stepping down to the ROPE (2004) keeps 0.12 s frozen for the whole rope stay; climbing to the bottom and letting go (1976) gives AIR with coyote 0.12 -> a full `jumpVel` jump in mid-air (1729).
   b) BEAM walk-off (2005) leaves coyote 0.12 -> coyote jump off the beam end.
   c) Walking off (G3) then a rope/cable/ledge/mantle grab within 0.12 s freezes the remaining coyote; release (rope bottom, cable drop, ledge drop) restores it.
   d) Crab and bee shoves (604, 622) do not clear coyote: shoved off the ground, you can still jump within 0.12 s (vx +-9 / +-8 plus jumpVel).
2. **Buffered jump survives a grab.** tryGrab does not clear jumpBuf: press jump just before catching a rope or cable -> you jump off on the next step (1970, 2015); just before a ledge -> instant pull-up (2028). Jump pressed late in a pull-up fires on the first ground step.
3. **Rope catch while rising.** Ropes have no vy limit (1917), unlike ledges (`vy <= 3.5`) and cables (`vy < 4`).
4. **Side-catch without input or lockout.** catchInside (1870-1881) lifts a body that entered a platform's side up to 0.9 m below the hands, ignoring lockout and stick direction; PAD/SPRING sides bounce.
5. **Mantle window.** Feet up to 1.0 m below a top and 0.45 m inside its edge -> scramble straight on, no hang (1949-1952).
6. **Step-across** onto a fixed neighbour up to 0.2 m higher (or 0.05 m lower) without a jump (1809-1824); never onto movers, pads, seesaws or spinning ramps.
7. **Swing catapult** only from a standing jump on a SWING near its arc ends (1682-1686), not from a buffered landing jump (1905); jumpedUp is true, so releasing jump cuts it.
8. **Pad held vs unheld** is sampled on the landing step (1890); pad side contact also bounces (1948, 1878). Pad bounces are not jump-cut.
9. **Blob seesaw launch** about 5.8 m straight up (1306-1312), not gated by invuln; an upward route a solver may not model.
10. **Enforcer swat drives vy DOWN** (`min(vy, -2)`, 874).
11. **Knock-off is a setback but rarely a respawn** in the endless game: respawn needs `y < floorY - 6` (lowest platform of the whole tower), so a knock-back normally lands on lower spiral layers (566). In finite courses 12 m below lastGroundY respawns (567).
12. **knockOff side** uses `hitBy.sAt(time)` (356): for cannon trail balls this is the current cycle's ball, for carried hazards the uncarried home, for avalanches/aimed cannons the trigger or turret arc, so the push direction can point toward the actual contact. It also leaves `jumpedUp` as it was (a jump still held keeps 6.9 m/s; released, it is cut to 5.175 next step) and sets no lockout (immediate re-grab possible).
13. **Pull-up onto a crumbled platform** completes into GROUND on a `gone` element, then drops at once (2037-2055, 1695).
14. **Shaking ramp**: you cannot stand on it longer than 0.25 s; the kick vector is a deterministic hash of a per-ramp counter stored in `tilt[]` that is NOT reset on respawn (1703-1706, 370-379), so the n-th kick differs between attempts.
15. **Hanging on a crumbling ledge** never starts its crumble (only standing, landing and pull completion do: 1701, 1901, 2051).
16. **Holed bridge**: the timer starts on the first standing step (1702), not at landing; step-across can land you over an open hole (no hole test in 1809-1824), and you drop on the next step.
17. **Gem pickup by touch** (any mode except LEDGE/PULLUP), STATIC only, volume = platform width + 0.28 by `deck-1.25 .. deck+1.476` (CheckpointGem.java:41-44), so a low hop over a gem platform collects it (S:571-581).
18. **Castle gate open = checkpoint** at its deck (1518); a closed gate is the only wall in the game.
19. **Input quantisation**: analogue stick magnitude never matters (469); thresholds act as "any tilt".
20. **Respawn keeps world mutations**: opened gates, knocked creatures, spent avalanches, the club, seesaw tilts and belt directions persist; only crumbles and holes are restored (376-377).
21. **`Sim.copy()` drops `deferRespawn`, `respawnPending` and `floorOverride`** (copy constructor 245-259 does not copy them; `minPlatSize` restarts at -1): a planner copy of a managed sim respawns immediately instead of deferring, and its `floorY` is recomputed from the resident course if `setRange` is called on it.
22. **Potential crash path (not observed, inferred)**: `rebase` maps a non-resident `onElem` to -1 (422) and a non-resident `onSlab` to -1 (424) without changing `mode`; GROUND with `onElem=-1, onSlab=-1` would call `stepGround(-1)` (541) and index `course.get(-1)`. Safety depends on Tower keeping the player's element resident.
23. **`EV_FALL_NEAR`** is declared (13) but never raised in Sim.
24. **Tuning keys absent from tuning.json** (`moverCrumbleDelay`, `swingSuper`, `swingSuperVx`) cannot be retuned over the air without adding them; `zoneHeight`/`hazardStartY`/`hazardRampY` in JSON (1000/40/950) differ from the Java defaults (400/90/1400).
