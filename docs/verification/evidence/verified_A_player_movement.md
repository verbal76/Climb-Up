# Verification of A_player_movement.md (independent, read-only)

Source checked: repository HEAD `f3ed007`. `git diff f4df3f3 HEAD` touches only `CourseGenerator.java` in `sim/`, so Sim.java, Element.java, Tuning.java, Course.java and tuning.json are byte-identical to what A read; **all of A's line numbers are still exact** (re-located by content and found at the cited lines). Paths are relative to `core/src/main/java/com/hotatticgames/climbup/sim/` unless stated. S: = Sim.java, E: = Element.java, T: = Tuning.java, C: = Course.java, J: = assets/data/tuning.json.

Probes: compiled into `$S/verifyA` (S = /tmp/claude-0/-home-user-Climb-Up/3cc9bd9b-3462-53b3-a9d2-97a9a2be1c43/scratchpad) against `$S/dev2` (newer than Sim.java) with the shipped tuning.json. No repository file was edited; no gradle run.

Totals for the verdict table below: **CONFIRMED 121, PARTIAL 8, REFUTED 0, UNVERIFIABLE 2** (131 claims). No claim of A was outright wrong; the partials are omissions or imprecise wording, two of which matter (6.8 "pad bounces are not jump-cut" and 6.12/2.I knock-off side effects, see New findings N1, N2).

---

## 1. Verdict table

### 1.1 Section 1 - player state

| # | Claim (A) | Verdict | Evidence | Correction |
|---|---|---|---|---|
| 1.1 | DT = 1/60 | CONFIRMED | S:10 | |
| 1.2 | `s,y,vx,vy` setters incl. spawn 314, startOn 328-349, gate 1521, rebase 428 | CONFIRMED | S:314, 328-349, 1521-1522, 428 | |
| 1.3 | `facing` set by moveX>0.15 in all moving modes, rope jump 1972, spring 1891, ledge 1953 | PARTIAL | S:1673,1721,1791,1969,2001,2012,1972,1891,1953 | also set by mantle S:1950 and side catch S:1879 (`facing = side/sideDir`) |
| 1.4 | Mode enum has 7 values; creatures act only in GROUND/AIR; gem pickup skips LEDGE/PULLUP | PARTIAL | S:8; 602,620,746,842,1315 test GROUND/AIR; 572 | the Enforcer swat contact S:873 has no mode test (N6) |
| 1.5 | `onElem` uses: seesaw/ramp `on` 511/520, timers 528, MOVE_Z 507, avalanche 1017, standOff 676 | CONFIRMED | S:507,511,520,528,1017,676 | |
| 1.6 | `onSlab` set 1771, cleared 1773/1782/1794/1795/316; not cleared by knockOff or shoves (harmless) | CONFIRMED | S:1771-1795, 316, 355-359, 604 | also remapped/cleared in rebase S:424 |
| 1.7 | `lastPad` not read inside Sim; read by Autopilot and PlayScreen | CONFIRMED | grep: only writes in Sim (26,330,373,423,1893); Autopilot.java:312,323,341,441,610 | |
| 1.8 | coyote = T.coyote+assist in GROUND/slab/land; T.coyote (no assist) at pull end and beam; 0 in doJump, knockOff, spawn, skull/enforcer/blob, shaking ramp | PARTIAL | S:1676,1771,1793,1898,2049,1994,1997,1649,357,315,747,845,874,1309,1317,1707 | also 0 at slab slam S:1782. **Not** cleared by pad/spring bounce S:1889-1896 (N1) |
| 1.9 | coyote decremented ONLY in stepAir 1727-1730 | CONFIRMED | grep `coyote` - the only `-=` is S:1728; probe P1 shows 0.120 frozen across 0.9 s of ROPE | |
| 1.10 | jumpBuf: press sets jumpBuffer+assist, else decays (537); zeroed in doJump, stepLedge, beam mount end, spawn | PARTIAL | S:537,1649,2029,1994,315 | also zeroed by knockOff S:357 |
| 1.11 | lockout: spawn 0.1; grabLockout 0.28 at 1973/1976/2002/2005/2016/2019/2025/2034; shoves 604/622/747/845/874/1317; blob launch 0.2; beam->rope 0 | CONFIRMED | same lines | |
| 1.12 | lockout ONLY gates tryGrab (not landing, not catchInside) | CONFIRMED | only read at S:538,1755; probe P9 (side catch with lockout 0.28) | |
| 1.13 | `pullToS/Y` never read (only shifted in rebase) | CONFIRMED | S:29,249,428; RunSnapshot saves/restores them but nothing consumes them | |
| 1.14 | `sideIn` set 1863, cleared 536/1873/1876/1877/304 | CONFIRMED | same | also remapped in rebase S:401 |
| 1.15 | `ropeTopT` set 1978, zeroed 1979/2004 | CONFIRMED | | |
| 1.16 | `jumpedUp` true only in doJump; cleared vy<=0, land, leave edge, shoves, pads | CONFIRMED | S:1649,1726,1898,1696,604...,1893 | knockOff leaves it unchanged (A says so in I) |
| 1.17 | `prevJumpHeld` read by nothing in Sim, saved by RunSnapshot | CONFIRMED | S:549; RunSnapshot.java:64,194 | |
| 1.18 | `lastGroundY` every non-AIR step; finite fall test | CONFIRMED | S:564,567,315 | |
| 1.19 | `onT[]`, `tilt[]` roles (ramp timer, belt side, seesaw slope, blob launch, shaking-ramp kick counter) | CONFIRMED | S:515-521, 1703-1705, 1310, 1661, 1675, 452 | |
| 1.20 | floorY = whole-map lowest platform - |amp| - 1 or floorOverride | CONFIRMED | S:177-182; Tower.java:253,263 | |
| 1.21 | checkpoint setters 1900, 1711, 2050, 579, 1518, 312 | CONFIRMED | | |
| 1.22 | bestElem/maxHeight not read in Sim; won at 1902/1712/2052 | CONFIRMED | | |
| 1.23 | falls 372, hits 562; setbacks() = falls+hits | CONFIRMED | S:362,372,562 | |
| 1.24 | invuln 0.7 at respawn and knockOff; gates hazardHit and creature contacts 602/620/746/842/873/1315 | CONFIRMED | S:358,372,561,602,620,746,842,873,1315 | blob seesaw launch S:1308 is not gated (A says so) |
| 1.25 | shoveCd gates 602/620/746/842/873, not blob squash | CONFIRMED | S:1315 has no shoveCd test | |
| 1.26 | club 30 s, -5 per knock, skull breaks it | CONFIRMED | S:587,611,699,1334,1358 | |
| 1.27 | slipCd decays only in stepGround | CONFIRMED | S:1672 is the only decrement | |
| 1.28 | assistForgive = assistJumpForgiveness from PlayScreen:194 | CONFIRMED | PlayScreen.java:194 | |
| 1.29 | HUMAN_HOLD = 6 (Autopilot:43) | CONFIRMED | Autopilot.java:43 | |
| 1.30 | Stick quantised to -1/0/+1 with dead zone 0.05 and written back into InputState | CONFIRMED | S:464,469,482,487 | |

### 1.2 Section 2 - transitions

| # | Claim | Verdict | Evidence | Correction |
|---|---|---|---|---|
| 2.1 | G1 jump: jumpBuf checked after the run/carry move; vx += 0.6*platformVs; boost 0.6*max(0,pv); swing f^4 super-boost; doJump sets vy, jumpedUp, coyote 0, jumpBuf 0, onElem -1 | CONFIRMED | S:1677-1688, 1648-1651 | The jump test precedes the `gone[e]`/hole/edge tests (S:1695), so a jump on the very frame a crumble vanishes is still a full ground jump with platform velocity (benign; N9) |
| 2.2 | G2 step-across: within 0.15 m of the end, FIXED neighbour (STATIC, CRUMBLE, GOAL, non-spinning RAMP), span contains s, top in [y-0.05, y+0.2], highest wins, EV_LAND suppressed, land() applies checkpoint/crumble/goal | CONFIRMED | S:1690-1691, 1809-1824 | No hole test (probe P5) |
| 2.3 | G3 walk off: `|ds|>hw+0.2` or gone or over open hole (0.2 m in); vy=min(0,pv), vx+=0.5pv, coyote left set | CONFIRMED | S:1694-1698 | |
| 2.4 | G4 shaking ramp: onT >= 0.25 -> hash kick vx +-(3.0..5.4), vy 10..13.6, coyote 0 | CONFIRMED | S:1703-1708; probe P6 values 5.40/11.20, -5.40/11.20, 3.00/12.40 | |
| 2.5 | G5 crumble/hole timers, checkpoint, GOAL | CONFIRMED | S:1701-1713 | |
| 2.6 | Run model (carry, seesaw uphill/slide, accel/decel, wet grip/lurch/skid) | CONFIRMED | S:1656-1675 | |
| 2.7 | B slab: slam > 6 m/s -> vy 6; jump; walk off; no wet/seesaw/step-across/checkpoint | CONFIRMED | S:1778-1796 | |
| 2.8 | C1 air control: only when not already over-speed same way, else drag 3 | CONFIRMED | S:1719-1720 | |
| 2.9 | C2-C3 gravity, clamp, jump cut to 5.175 | CONFIRMED | S:1723-1726; probe P8 shows 11.5 -> 5.175 | |
| 2.10 | C4 coyote jump inherits no platform velocity, returns before moving | CONFIRMED | S:1727-1730; probe P1 (y unchanged on the jump step) | |
| 2.11 | C5-C6 integration, landing filter, crossing test, highest wins | CONFIRMED | S:1733-1751 | |
| 2.12 | C7 landOnSlope RAMP only, no relVy test | CONFIRMED | S:1833-1848 | |
| 2.13 | C8 landOnSlab, buffered jump applies at once | CONFIRMED | S:1759-1775 | |
| 2.14 | C9-C10 catchInside not lockout-gated; tryGrab gated | CONFIRMED | S:1754-1755; probe P9 | |
| 2.15 | D land(): PAD/SPRING bounce sampled at landing, jumpedUp false, lastPad, vx kept for PAD, coyote not touched | CONFIRMED | S:1889-1897 | "coyote not touched" is a live defect, see N1 |
| 2.16 | D land() else-branch, buffered jump on landing without swing boost | CONFIRMED | S:1898-1905 | |
| 2.17 | E rope grab: radius 0.42, hand in [yBottom, top+0.1], no vy limit, first rope in index order | PARTIAL | S:1913-1931 | first ROPE **or CABLE** in index order (one interleaved loop); probe P4 confirms no vy limit (grab at vy 12) |
| 2.18 | E climb clamp, rope jump dir/vx 5.32/vy 10.12/lockout, let go only at bottom, beam after 0.06 s hold | CONFIRMED | S:1961-1980; probe P3 (vx 5.320, vy 10.120) | |
| 2.19 | "No mid-rope release other than jumping or a knock-off" | CONFIRMED | S:1976 | (knock-off does not actually release you, N2) |
| 2.20 | F beam: mount 0.62 s, input ignored, coyote/jumpBuf at end, walk 0.55x, jump, down to rope (lockout 0, coyote kept), walk off |d|>1.1 with coyote | CONFIRMED | S:1986-2006; probe P1 | |
| 2.21 | G cable: grab |ds|<=w/2, band -0.45/+0.15, vy<4; shimmy 3.6; jump vy 8.625; drop moveY<-0.7 or past w/2+0.1 | CONFIRMED | S:1922-1929, 2008-2021; P4 | the vy tests (cable <4, ledge <=3.5) are applied after this step's gravity, so a body entering with up to ~4.57 / ~4.07 m/s is caught (P4: cable caught at vy0 4.2, ledge at 3.9) |
| 2.22 | H ledge: reach-below 0.9, not-mantle feet in [top-2.05, top-1.0], edgeDist [-0.12, 0.4], toward, first match in index order, any type/edge/mover; sets s/y | CONFIRMED | S:1933-1956 | |
| 2.23 | H PAD/SPRING ledge contact bounces | CONFIRMED | S:1948 | |
| 2.24 | H mantle: feet (top-1.0, top-0.05), edgeDist [-0.45,0.4], jumpBuf not cleared | CONFIRMED | S:1940-1952 | |
| 2.25 | H side catch: tracking rule, sideSlack -0.05/+0.5, slab 1 m/0.5 m | CONFIRMED | S:1855-1868; E:86 | |
| 2.26 | H catchInside abandoned if out of hw+0.28, feet cleared the top, hands below top-0.9 | PARTIAL | S:1873-1876 | also abandoned when `gone[i]` or the head is below the block's underside (`y+height <= top-slab`) |
| 2.27 | H catchInside needs no stick input, ignores lockout | CONFIRMED | probe P9: PULLUP with moveX=0, lockout 0.28 | |
| 2.28 | H stepLedge: re-pinned, gone -> AIR, PULLUP on up or jumpBuf (zeroed), drop on down/away, no time limit, cannot jump away, hanging starts no crumble | CONFIRMED | S:2023-2035; probe P3, P11 | |
| 2.29 | H stepPull: 0.32 s easing, target 0.35 m in, tracks live, end GROUND coyote T.coyote, no gone check, no input read, late jump fires on first ground step | CONFIRMED | S:2037-2055; probe P3 (press at pullT 0.25 -> vy 11.5 on the first ground step) | also completes onto an out-of-plane MOVE_Z (N3) |
| 2.30 | I knockOff: only from 562, invuln gate, AIR/onElem -1/coyote 0/jumpBuf 0, +-6.9 away from `hitBy.sAt(time)`, vy 6.9, no lockout/jumpedUp/onSlab change, any mode | CONFIRMED | S:355-359, 562 | "away from hitBy.sAt" is not "away from the contact": probe P7 (N2b); "no lockout" makes it a no-op on ropes: probe P12 (N2a) |
| 2.31 | I crab shove values, coyote not cleared | CONFIRMED | S:602-605; probe P2 | |
| 2.32 | I bee bump values, coyote not cleared | CONFIRMED | S:620-623 | |
| 2.33 | I skull, Enforcer kick, swat, blob squash, blob launch values and gates | CONFIRMED | S:746-748, 842-847, 873-876, 1315-1319, 1306-1312 | swat contact (873) has no mode test (N6) |
| 2.34 | I castle gate wall in any mode, pushed to the `ps0` side, vx zeroed into it | CONFIRMED | S:1511-1524 | |
| 2.35 | Shoves not in setbacks(), hits are | CONFIRMED | S:362 | |
| 2.36 | J endless respawn only at y < floorY-6; finite at lastGroundY-12; deferRespawn path; completeRespawn resets list; NOT-reset list | CONFIRMED | S:365-379, 566-567, 311-318; Tower.java:243-250 | sideIn is effectively reset (mode GROUND -> S:536 next step) |
| 2.37 | Checkpoint index only ever increases | CONFIRMED | `> checkpoint` at S:1711,1900,2050,1518; `<= checkpoint continue` S:576 | (rebase/Tower remap the index, identity is preserved) |
| 2.38 | K carry, glue (y = ey1 every step), velocity inheritance, surfaceCarry, MOVE_Z gone rule | CONFIRMED | S:1657, 1679-1680, 1696-1697, 450-454, 507; E:320-338 | |

### 1.3 Section 3 - constants

| # | Claim | Verdict | Evidence | Correction |
|---|---|---|---|---|
| 3.1 | Every Tuning default and its line (T:7-37) | CONFIRMED | Tuning.java:7-37 | |
| 3.2 | tuning.json values equal the Java defaults for movement; `moverCrumbleDelay`, `swingSuper`, `swingSuperVx` absent | CONFIRMED | J (full file read) | |
| 3.3 | zoneHeight/hazardStartY/hazardRampY JSON 1000/40/950 vs Java 400/90/1400 | CONFIRMED | J; T:26,29 | |
| 3.4 | Derived numbers: apex ~1.85 m discrete, tap ~0.4 m, running jump ~3.8 m, pads ~6.3/8.3 m | CONFIRMED | probe P10: apex 1.850, tap 0.438, running 3.827 m, pad 6.311 / 8.272 m, airtime 0.683 s | |
| 3.5 | assistSlowFactor applied outside Sim (PlayScreen:299) | CONFIRMED | PlayScreen.java:299 | |
| 3.6 | Sim/Element hard-coded constants and lines | CONFIRMED | S:10,28,33,436-440,464,609,1334,1358; E:93,192-194,227,304 | |
| 3.7 | Sim never reads `T.hard`; only `T.react()` | CONFIRMED | grep `hard` in Sim.java: no hit; `react()` only in creature/cannon code | |
| 3.8 | No player movement, collision or grab constant changes with difficulty | CONFIRMED | same | |

### 1.4 Section 4 - collision and contact

| # | Claim | Verdict | Evidence | Correction |
|---|---|---|---|---|
| 4.1 | Platforms one-way from above; no ceiling or side wall; closed gates are the only walls | CONFIRMED | S:1745-1747; no other position clamp in Sim | |
| 4.2 | Contact is 1-D along the arc + height; depth only for MOVE_Z | CONFIRMED | | while standing on a MOVE_Z the player is out of plane but still collides with in-plane hazards and lands on in-plane platforms after jumping (inferred from code, no z in hazardHit/landing) |
| 4.3 | STATIC: wet colour 7, holed bridge (skin 1 + colour mask, 0.45 s, 3.4 s, 0.2 m), gem skin 3, conveyor skin 4 | CONFIRMED | E:57-58,93-110; S:525-528,1695,1744; C:18 | holes stay open 3.4 s **and** until you leave the deck (S:528 `!(GROUND && onElem==i)`) |
| 4.4 | CRUMBLE timer from landing/standing, gone at 0.5 s, back after 3.4 s, keeps running after leaving | CONFIRMED | S:530-532 | |
| 4.5 | MOVE_H/V carry; skin 1 crumbles after 1.6 s | CONFIRMED | E:84; S:532 | |
| 4.6 | MOVE_Z landable only while |z|<=1, carries while stood on | CONFIRMED | S:507, 1740 | |
| 4.7 | SWING carry and catapult values | CONFIRMED | E:323,334; S:1682-1686 | |
| 4.8 | PAD/SPRING bounce on landing, ledge contact, side catch | CONFIRMED | S:1889-1897, 1948, 1878 | |
| 4.9 | ROPE/CABLE contact rules | CONFIRMED | S:1916-1929 | |
| 4.10 | GOAL slab 1 m, landing/standing/pull -> won | CONFIRMED | E:86; S:1902,1712,2052 | GOAL never occurs in endless chunks (tally probe) |
| 4.11 | SEESAW surface/tilt/uphill/slide/belt/blob launch | CONFIRMED | S:509-517,1661,1675,452,1306-1312 | |
| 4.12 | RAMP surface, landOnSlope, skins 1-4, step-across target unless spinning | CONFIRMED | S:518-523,1703,1817; E:84,90,108,117 | |
| 4.13 | SPIKE_DROP underside band lethal, top landable, slam bounce | CONFIRMED | S:1638-1640, 1759-1796; E:192-196 | |
| 4.14 | Disc hazards radii (saw 0.55, pendulum 0.5, cannon 0.42 every trailing ball, mortar 0.42 above muzzle+0.35) | CONFIRMED | E:138,150,171,298; S:1615-1635 | |
| 4.15 | SPIKE_TRAP window, SPIKE_BLOCK solid lethal box not standable, AIMED 0.4, AVALANCHE trigger and discs | CONFIRMED | S:1636-1642,1576-1582,1017-1019; E:62-64 | |
| 4.16 | KEY/GATE/CLUB touch, gate wall/opens/checkpoint | CONFIRMED | S:1503-1524, 585-588 | KEY and CLUB pickup are not mode-gated either |
| 4.17 | Creatures shove only, never `hits`, GROUND/AIR only | PARTIAL | S:602,620,746,842,1315 | Enforcer swat contact S:873 has no mode test (N6) |
| 4.18 | Hazard body box half-width 0.25, y+0.12..y+1.15; riding hazards SPIKE_TRAP/SPIKE_BLOCK/SPIKE_DROP/SAW_H/CRAB | CONFIRMED | S:1609; E:69-71 | |
| 4.19 | A hit is always knockOff, never a direct respawn | CONFIRMED | S:562 | |
| 4.20 | Hazards hit in every mode | CONFIRMED | S:561-562 (no mode test) | |

### 1.5 Section 5 - update order

| # | Claim | Verdict | Evidence | Correction |
|---|---|---|---|---|
| 5.1 | Order 1-12 as listed | CONFIRMED | S:480-568 | |
| 5.2 | MOVE_Z gone uses pre-step mode/onElem | CONFIRMED | S:507 | |
| 5.3 | A crumble expiring in the element loop is seen the same frame | CONFIRMED | S:532 before 540 | but a jump that frame still fires (S:1677 precedes 1695) |
| 5.4 | Ramp `y` lags the slope by one step | CONFIRMED | S:518-523 vs 1657 | |
| 5.5 | Last writer wins; knockOff overrides | CONFIRMED | S:558-562 | |
| 5.6 | invuln decay-else-test | CONFIRMED | S:561 | |
| 5.7 | Gate push after movement | CONFIRMED | S:558 after 540 | |
| 5.8 | Highest landing wins, first ledge wins | CONFIRMED | S:1748, 1934-1956 | |

### 1.6 Section 6 - mechanics a solver could miss

| # | Claim | Verdict | Evidence | Correction |
|---|---|---|---|---|
| 6.1a | Beam -> rope keeps coyote 0.12; let go at bottom -> full mid-air jump | CONFIRMED (probe P1) | `back to rope ... coyote=0.120`; `let go ... coyote=0.120`; `press jump ... vy=11.500 ... EV_JUMP=true`; control from a fresh rope: `vy=-1.133` (no jump) | |
| 6.1b | Beam walk-off leaves coyote -> coyote jump | CONFIRMED (P1) | `beam walk-off ... coyote=0.120`; `beam coyote jump ... vy=11.500` | |
| 6.1c | Walk off then grab within 0.12 s freezes remaining coyote; release restores it | CONFIRMED (code) | S:1727-1728 only decrement; S:1918/1926/1953 do not touch coyote; S:1976/2019/2034 leave it | not probed separately; the freeze mechanism is the one P1 shows |
| 6.1d | Crab/bee shoves leave coyote -> jump within 0.12 s | CONFIRMED (probe P2) | `shoved ... vy=6.000 coyote=0.120`; `next step w/ press ... vx=-8.533 vy=11.500`; control `vy=5.433` | |
| 6.2 | Buffered jump survives a grab: rope -> jumps off next step; ledge -> instant pull-up; late pull-up press fires on first ground step | CONFIRMED (probe P3) | jump pressed 3 steps before the catch: `mode=ROPE ... jumpBuf=0.080` then `mode=AIR vx=5.320 vy=10.120`; ledge `LEDGE jumpBuf=0.130` -> `PULLUP jumpBuf=0.000`; pull `GROUND jumpBuf=0.047` -> `AIR vy=11.500` | |
| 6.3 | Rope catch while rising; ledges vy<=3.5, cables vy<4 | CONFIRMED (probe P4) | `vy0=12.0 rope->ROPE cable->AIR ledge->AIR` ... `vy0=3.9 ... cable->CABLE ledge->LEDGE` | thresholds are post-gravity (see 2.21) |
| 6.4 | Side catch without input or lockout; pad sides bounce | CONFIRMED (probe P9) | `result: mode=PULLUP on=1 ... lockout=0.197` with moveX 0 | |
| 6.5 | Mantle window | CONFIRMED | S:1940-1952 | |
| 6.6 | Step-across up to +0.2/-0.05, never onto movers, pads, seesaws, spinning ramps | CONFIRMED | S:1817-1820 | also never onto SPRING, MOVE_Z, SWING (follows from `fixed`) |
| 6.7 | Swing catapult only from a standing jump, not a buffered landing jump; cuttable | CONFIRMED | S:1682-1686 vs 1905; jumpedUp true in doJump | |
| 6.8 | Pad held/unheld sampled at landing; pad side contact bounces; "pad bounces are not jump-cut" | PARTIAL (probe P8) | `bounced ... vy=21.000 coyote=0.037`; `press (released at once) ... vy=11.500`; `+1 ... vy=5.175`; apex 0.44 m vs 6.31 m without the press | A bounce itself is not cut, but a residual coyote lets a jump press right after the bounce replace it with a normal, cuttable jump (N1) |
| 6.9 | Blob seesaw launch ~5.8 m, not invuln-gated | CONFIRMED | S:1308-1312; 11.5*1.732 = 19.92 m/s -> 5.84 m | |
| 6.10 | Enforcer swat drives vy down | CONFIRMED | S:874 | |
| 6.11 | Knock-off rarely respawns in endless | CONFIRMED | S:566 | |
| 6.12 | knockOff side from `hitBy.sAt(time)` (cannon current-cycle ball, carried hazards' uncarried home, avalanche/aimed trigger or turret arc), jumpedUp kept, no lockout | CONFIRMED (probe P7, P12) | P7 `carried=1 sawActualS=2.00 player=1.50 -> knock vx=+6.90 (correct sign -)`; static control `-6.90` | effects quantified in N2a/N2b |
| 6.13 | Pull-up onto a crumbled platform completes then drops | CONFIRMED | S:2037-2055 (no gone test), 1695 | |
| 6.14 | Shaking-ramp kick counter in `tilt[]` not reset on respawn; n-th kick differs between attempts | CONFIRMED (probe P6) | `after respawn tilt[ramp]=1.0`; `attempt1 vx=5.40 vy=11.20 / attempt2 vx=-5.40 vy=11.20 / attempt3 vx=3.00 vy=12.40 / fresh sim vx=5.40 vy=11.20` | more exactly: the kick sequence continues across attempts, so the 2nd attempt's first kick can go the opposite way |
| 6.15 | Hanging on a crumbling ledge never starts its crumble | CONFIRMED | S:2023-2035 (no crumbleT write) | |
| 6.16 | Holed bridge timer at first standing step; step-across onto an open hole drops next step | CONFIRMED (probe P5) | `t=0.383 mode=GROUND on=1 ... EV_LAND=false` then `t=0.400 mode=AIR` then lands on the floor below at t=1.000 | |
| 6.17 | Gem pickup by touch, STATIC only, volume, low hop collects | CONFIRMED | S:571-581; CheckpointGem.java:41-44 (top = 0.90+0.13+0.446 = 1.476) | |
| 6.18 | Gate open = checkpoint; closed gate only wall | CONFIRMED | S:1518-1524 | |
| 6.19 | Input quantisation | CONFIRMED | S:469 | |
| 6.20 | Respawn keeps world mutations; only crumbles and holes restored | CONFIRMED | S:376-377 | |
| 6.21 | `Sim.copy()` drops deferRespawn, respawnPending, floorOverride; minPlatSize restarts at -1 | CONFIRMED | S:245-259 | copy also drops `hitBy` and `knocked` (harmless: hazardHit rewrites hitBy before knockOff) |
| 6.22 | rebase can leave GROUND with onElem -1 -> course.get(-1) crash | UNVERIFIABLE | S:422 maps to -1 without changing mode; stepGround S:1654-1657 would index -1 | Tower.ensureWindow S-side guard: the rebuilt range always includes `sliceAt(abs)` of the player (Tower.java:233-235), and a respawn rebuild happens while AIR; no path found that drops the player's element, so this is latent only |
| 6.23 | EV_FALL_NEAR declared, never raised | CONFIRMED | only S:13; no other reference in the codebase | |
| 6.24 | Tuning keys absent from JSON cannot be retuned OTA without adding them | UNVERIFIABLE | keys are absent (J); whether the OTA loader can add new keys is outside the files read | Tuning.parse ignores unknown fields only; an OTA tuning.json that adds the key would work (T:72-76), so "cannot ... without adding them" is true but trivially so |

---

## 2. Behavioural probes run

All sources under `$S/verifyA/src/com/hotatticgames/climbup/sim/`; run with `java -cp "$S/verifyA:$S/dev2:$GDX" com.hotatticgames.climbup.sim.<Class>`. Outputs saved at `$S/verifyA/out.txt`, `movez.txt`, `regrab.txt`, `tally.txt`.

| Probe | Source | Showed | Key output lines |
|---|---|---|---|
| P1 stale coyote | VerifyAProbes.java `p1` | Beam->rope keeps 0.12 s coyote through 0.9 s of rope; let go at the bottom then a full 11.5 m/s jump in mid-air. Fresh-rope control cannot. Beam walk-off coyote jump works. | `back to rope: ... coyote=0.120` / `let go: ... coyote=0.120 ... lockout=0.280` / `press jump: ... vy=11.500 coyote=0.000 ... (EV_JUMP=true)` / `control press: ... vy=-1.133 coyote=0.000 jumpBuf=0.130` / `beam coyote jump: ... vy=11.500` |
| P2 shove coyote | `p2` | Crab shove from the ground leaves coyote 0.12; a press next step turns the 6 m/s shove into an 11.5 m/s jump while keeping vx -8.5. | `shoved: ... vx=-9.000 vy=6.000 coyote=0.120` / `next step w/ press: ... vx=-8.533 vy=11.500` / `next step no press: ... vy=5.433` |
| P3 buffered jump vs grabs | `p3` | Jump pressed in the air 3 steps before a rope catch: caught, then jumps off the next frame. Ledge: grab then automatic pull-up next frame. Jump pressed at pullT 0.25: jump on the first ground step. | `t=0.067 mode=ROPE ... jumpBuf=0.080` / `step after grab: t=0.083 mode=AIR ... vx=5.320 vy=10.120` / `ledge next: ... mode=PULLUP ... jumpBuf=0.000` / `pull done: ... jumpBuf=0.047` / `first ground step: ... vy=11.500` |
| P4 rising catches | `p4` | Rope at any vy, cable below ~4.57 entering vy, ledge below ~4.07. | `vy0=12.0 rope->ROPE cable->AIR ledge->AIR` / `vy0=4.2 rope->ROPE cable->CABLE ledge->AIR` / `vy0=3.9 rope->ROPE cable->CABLE ledge->LEDGE` |
| P5 step-across into hole | `p5` | Step-across onto an open hole section (no EV_LAND), drop next step, land on the floor below. | `holesOpen(1)=true overHole(x=-2.8)=true` / `t=0.383 mode=GROUND on=1 s=1.867 y=0.100 ... EV_LAND=false` / `t=0.400 mode=AIR` / `t=1.000 mode=GROUND on=2 ... y=-6.000` |
| P6 shaking ramp | `p6` | Kick counter survives respawn; attempt 2 kicks the opposite way from attempt 1 and from a fresh sim. | `after respawn tilt[ramp]=1.0 onT=0.0` / `attempt1 kick vx=5.40 vy=11.20 \| attempt2 vx=-5.40 vy=11.20 \| attempt3 vx=3.00 vy=12.40 \| fresh sim vx=5.40 vy=11.20` |
| P7 knock direction | `p7` | Saw riding a MOVE_H: player between the saw's home and its live position is thrown toward/through the saw. Static saw at the same live spot: correct. | `carried=1 sawActualS=2.00 player=1.50 hits=1 -> knock vx=+6.90 (correct sign -)` / `carried=0 sawActualS=2.00 player=1.50 hits=1 -> knock vx=-6.90 (correct sign -)` |
| P8 pad + residual coyote | `p8` | Walk off a deck 0.1 m above an adjacent pad; bounce leaves coyote 0.037; a tap right after the bounce replaces the 21 m/s bounce with a cut jump (apex 0.44 m instead of 6.31 m). | `bounced: ... vy=21.000 coyote=0.037` / `press (released at once): ... vy=11.500` / `+1: ... vy=5.175` / `apex y=0.44` vs control `apex y=6.31` |
| P9 side catch | `p9` | Side catch with lockout 0.28 and neutral stick still pulls up. | `result: t=0.083 mode=PULLUP on=1 ... lockout=0.197` |
| P10 numbers | `p10` | Movement numbers. | `held standing: apex=1.850 m, airtime=0.683 s` / `tap standing: apex=0.438 m` / `held running: ... dist=3.827 m` / `pad v=21 apex gain=6.311 m` / `pad v=24 apex gain=8.272 m` |
| P11 MOVE_Z pull-up | VerifyAMoveZ.java | Hang on a depth mover: dropped when it leaves the plane (z>1). Pull-up started in plane: completes at z=1.63 and the player then stands on the out-of-plane platform (gone suppressed while standing). | `t=0.217 PULLUP/1/gone=true z=1.00` / `t=0.367 GROUND/1/gone=true z=1.63` / `t=0.383 GROUND/1/gone=false z=1.70` / HANG: `t=0.217 AIR/-1/gone=true z=1.00` |
| P12 knock-off on a rope | VerifyARegrab.java | Saw 0.5-0.75 m beside a rope, stick neutral, lockout 0: every knock-off is followed by a re-grab of the same rope one frame later; hit again each time invuln ends (0.72 s): 7 hits in 5 s, never knocked off. | `saw offset 0.50: hits=7 regrabs=7 final mode=ROPE y=6.74 \| hit@0.02(vx=-6.9) regrab@0.03 hit@0.73(vx=-6.9) regrab@0.75 ...` (first run without zeroing startOn's spawn lockout 0.1 gave `hits=1 regrabs=0`: the only thing that stops the regrab is a lockout) |
| Tally | VerifyATally.java | Element types the generator puts in `elements` (3 seeds x 6 chunks): only platform types + ROPE/CABLE; KEY/GATE/CLUB/creatures only in `hazards`. | `elements: {CABLE/0=16, CRUMBLE/0=46, MOVE_H/0=17, MOVE_H/1=11, MOVE_V/0=19, MOVE_V/1=8, MOVE_Z/0=17, PAD/0=60, RAMP/0=40, ROPE/0=70, SEESAW/1=18, SPRING/0=19, STATIC/0=1019, STATIC/1=39, STATIC/3=3, STATIC/4=8, SWING/0=18}` |

---

## 3. New findings (not in A, or in A but not in coverage.json)

| ID | Finding | Evidence | In A? | In coverage.json? |
|---|---|---|---|---|
| N1 | **Pad/spring bounce does not clear coyote.** After a bounce reached within 0.12 s of leaving footing (walk off onto an adjacent lower pad, walk into a pad's side, or a stale-coyote fall), a jump press within the remaining coyote calls `doJump(0)` and *replaces* the 21/24 m/s bounce with a normal 11.5 m/s jump that is also jump-cut (`jumpedUp=true`). Spring: the angled vx is kept but vy becomes 11.5. | S:1889-1896 (no `coyote=0`), 1727-1729; probe P8: apex 0.44 m vs 6.31 m | No (A 6.8 says pad bounces are not cut) | No (MV-21/23 and TR-13 do not mention it) |
| N2a | **Knock-off from a rope (and plausibly a cable) is a no-op loop.** knockOff sets no lockout, so the AIR body re-grabs the same rope the next frame (within 0.42 m); the hazard hits again whenever invuln ends. The comment "it knocks you off whatever you hold" (S:354) is not what happens on ropes. | S:355-359, 1755, 1917; probe P12: 7 hits / 7 re-grabs in 5 s, final mode ROPE. Cable: vy 6.9 blocks the cable grab only until vy < 4 (~0.085 s, ~0.6 m), a wide cable is then re-grabbed (inferred, not probed) | A states "no lockout (immediate re-grab possible)" but not the loop | MV-33 says "knock-back ... any mode"; nothing about re-grab |
| N2b | **Knock-off direction uses a different hazard position than the hit test.** Hit test: `hzPose + carryS` (live, riding the anchor), cannon trail balls `back>0`, the aimed ball, avalanche blocks. Knock side: `hitBy.sAt(time)` = uncarried home / current-cycle ball / turret / trigger. A player between those two points is thrown toward or through the hazard. | S:356 vs 1616-1635, 1576-1582; probe P7 | Partly (6.12 lists the cases) | No |
| N3 | **Pull-up completes onto a MOVE_Z that left the plane**, and the player then stands on it out of plane (gone suppressed while standing), whereas a hang on the same mover is dropped. Renderer jumps the hero to the platform's depth at that moment (WorldRenderer.java:1482). | S:2037-2055, 507, 2025; probe P11 | No | No (MV-25/TR-22 only mention crumbled decks) |
| N4 | **Respawn does not restore per-element dynamic state other than crumbles/holes**: shaking-ramp kick counter (`tilt` of RAMP skin 2), seesaw tilt, tilt-belt direction (`onT`), ramp `onT`. Kick sequence of a shaking ramp therefore depends on how many kicks happened before (P6). Planner copies inherit the live counter (S:254) but generator proofs start fresh, so the proof sees kick #0 while a retrying player sees kick #n. | S:370-379, 1703-1706; P6 | Yes (6.14, 6.20) | Partly (MV-42 says tilt persists; nothing about kick determinism vs proofs) |
| N5 | **Buffered jump also survives a pad bounce and a side catch** (jumpBuf is never cleared by land()'s PAD branch, catchInside or mantle). Pressing jump just before a pad bounce and catching a rope within 0.13 s leaps off the rope at once; a mantle/side-catch with a buffered jump leaps on the first ground step after the pull if pressed in its last 0.13 s. | S:1889-1896, 1877-1879, 1949-1951 (no jumpBuf writes); P3 | Partly (6.2 for rope/cable/ledge/pull) | MV-07 lists rope/cable/ledge only |
| N6 | **Enforcer swat contact is not mode-gated**: the swat only starts while you are AIR (S:813 `inReach` needs mode AIR), but the contact during its leap (S:873) tests position, invuln and shoveCd only, so a player who catches a rope, cable or ledge (or is pulling up / on a beam) during the leap is still swatted off (mode set to AIR). All other creature contacts test GROUND/AIR. Inferred from code, not probed. | S:873-876 vs 602,620,746,842,1315 | No (A says creatures never touch the player in those modes) | No |
| N7 | **Beam mount discards any jump pressed during the 0.62 s mount** (jumpBuf=0 at mount end, S:1994), unlike every other mode where the buffer carries over. Input drop, not a traversal change. | S:1990-1995 | Mentioned as a fact | No |
| N8 | **`isPlatform()` is true for feature/creature types** (KEY, GATE, CRAB, CLUB, BEE, SKULL, BLOB, ENFORCER, AVALANCHE) because `isHazard()` lists only the lethal hazards. Harmless today: those live only in `course.hazards` (tally), and the landing/grab loops iterate `elements`. Latent if a feature is ever added to `elements`, or for code that calls `isPlatform()` on hazards (`deckOf` takes `course.get(anchor)`, an element, so fine). | E:62-64, 87; tally probe | No | No |
| N9 | **Ground jump wins over gone/hole on the same frame**: stepGround tests jumpBuf (S:1677) before `gone[e]`/holes (S:1695), so a jump pressed on the frame a crumble vanishes is a full platform jump with inherited velocity (in addition to the normal coyote grace). Benign leniency. | S:1677-1698 | No | No |
| N10 | **Effective grab thresholds are post-gravity**: cable `vy<4` and ledge `vy<=3.5` are tested after this step's `vy -= g*dt`, so the entering speed limits are ~4.57 and ~4.07 m/s. Solvers that model the thresholds on the pre-step velocity are off by one step (0.57 m/s). | S:1723, 1925, 1933; P4 | No | MV-13/MV-20 state 3.5 / 4 |
| N11 | **Holes stay open while you stand on the deck**, beyond the 3.4 s: the close condition requires you not to be GROUND on it (S:528). Standing still on a bridge keeps its holes open indefinitely. | S:528 | No (A: "open 3.4 s") | MV-27 "planks drop 0.45 s after standing" only |
| N12 | **Gate wall acts in every mode** (incl. rope, cable, ledge, pull-up, beam), shifting `s` and zeroing vx; ROPE/LEDGE re-pin s next step, CABLE keeps the push (blocks shimmy), PULLUP overwrites s from pullFromS. | S:1511-1524 | Yes (I) | MV-39 does not say "any mode" |

Transitions present in Sim but absent from coverage.json `transitions` (TR-01..TR-33):
- AIR -> AIR: pad/spring bounce overridden by a residual-coyote jump (N1).
- AIR -> GROUND -> AIR within one step: buffered jump on landing (S:1905) and on slab landing (S:1773); only implied by MV-05.
- PULLUP -> GROUND onto an out-of-plane MOVE_Z (N3) (TR-22 covers the crumbled-deck variant only).
- ROPE/CABLE/LEDGE/BEAM/PULLUP -> AIR via the Enforcer swat (N6) (TR-32 is hazards only).
- AIR(knocked off a rope) -> ROPE next frame (N2a).
- GROUND -> AIR jump on the frame the deck vanishes (N9).
- GROUND -> GROUND step-across onto an open hole, then -> AIR (P5) (TR-06 / TR-03 separately, not the chain).

---

## 4. Platform entry / exit matrix

Legend: Y = allowed, N = not possible, C = conditional (condition given), ! = surprising. Line refs are Sim.java unless marked E:. "Land" = stepAir landing loop S:1736-1751 (requires `isPlatform`, not gone, not over open hole, relVy<=0, top crossing). Side catch = trackSideEntry/catchInside S:1855-1881. Ledge/mantle = tryGrab S:1933-1956 (lockout<=0, vy<=3.5, toward). Step-across target = S:1817 `fixed`. Exits: walk off S:1694-1698; jump S:1677; coyote jump S:1727-1729; forced = knockOff S:355 (all), shoves S:602-1317 (GROUND/AIR).

Entries:

| Type | Land from above | landOnSlope | Side catch | Ledge hang | Mantle | Step-across target | Rope grab | Cable grab | Slab land | Carried onto / spawn |
|---|---|---|---|---|---|---|---|---|---|---|
| STATIC | Y | N | Y | Y | Y | Y (! no hole test, P5) | N | N | N | spawn Y (checkpoint centre, S:314) |
| CRUMBLE | C not gone | N | C not gone | C not gone | C not gone | Y C not gone (land starts timer) | N | N | N | spawn N (not a checkpoint) |
| MOVE_H / MOVE_V | Y (relVy vs platform) | N | Y | Y (re-pinned live) | Y | N | N | N | N | carried while GROUND |
| MOVE_Z | C |z|<=1 | N | C |z|<=1 | C |z|<=1 (dropped when it leaves) | C |z|<=1 | N | N | N | N | carried out of plane while GROUND (!, N3: pull-up completes onto it out of plane) |
| SWING | Y | N | Y | Y | Y | N | N | N | N | carried |
| PAD | -> bounce (never GROUND) | N | -> bounce | -> bounce (toward needed) | -> bounce | N | N | N | N | startOn only (AIR) |
| SPRING | -> angled bounce | N | -> bounce | -> bounce | -> bounce | N | N | N | N | startOn only |
| ROPE | N | N | N | N | N | N | Y any vy, radius 0.42, first rope/cable by index (!) | N | N | startOn at bottom |
| CABLE | N | N | N | N | N | N | N | C vy<4 post-gravity, hand band | N | startOn |
| GOAL | Y (won) | N | Y | Y | Y (pull -> won) | Y | N | N | N | (absent in endless) |
| SEESAW | Y (surface at x) | N | Y (slack +0.5) | Y | Y | N | N | N | N | carried by tilt/slide/belt |
| RAMP | Y | Y (rising body stopped) | Y (slack +0.5) | Y | Y | C not spinning (skin 3) | N | N | N | belt/spin carry |
| SPIKE_DROP (top) | N (not isPlatform) | N | N | N | N | N | N | N | Y (relVy vs slab) | carried by slab (stepSlab) |
| SAW_H/SAW_V/PENDULUM/CANNON/MORTAR/AIMED/SPIKE_TRAP/SPIKE_BLOCK/AVALANCHE | N | N | N | N | N | N | N | N | N | lethal contact only |
| KEY / CLUB | N (hazards list) | N | N | N | N | N | N | N | N | touch pickup, any mode |
| GATE | N | N | N | N | N | N | N | N | N | wall (closed, any mode) / pass (open) |
| CRAB/BEE/SKULL/BLOB/ENFORCER | N | N | N | N | N | N | N | N | N | shove only (GROUND/AIR; Enforcer swat any mode, N6) |

Exits:

| Type | Walk off edge | Jump | Coyote jump | Launch / bounce | Crumble / hole drop | Carried off | Shove / knock | Pull-up onto (completion) | Climb off top | Drop |
|---|---|---|---|---|---|---|---|---|---|---|
| STATIC | Y | Y | Y | N | C holed bridge: hole open, 0.2 m in (holes stay open while you stand, N11) | C conveyor skin 4 | Y | Y | N | N |
| CRUMBLE | Y | Y (also the frame it vanishes, N9) | Y | N | Y gone after 0.5 s (from first touch, keeps running) | N | Y | Y (! completes onto a gone deck, then drops) | N | N |
| MOVE_H / MOVE_V | Y (+0.5 vs, vy=min(0,pv)) | Y (+0.6 vs, +0.6 max(0,vy)) | Y (no inheritance) | N | C skin 1 after 1.6 s | Y (glued, no separation) | Y | Y | N | N |
| MOVE_Z | Y | Y | Y | N | N (out-of-plane suppressed while standing) | carried in depth only | Y | Y (! even out of plane, P11) | N | N |
| SWING | Y | Y + catapult f^4 (standing jump only) | Y | catapult | C skin 1 | Y | Y | Y | N | N |
| PAD / SPRING | - | - | ! residual coyote can replace the bounce (N1) | Y bounce 21/24 (held at landing) | N | N | Y (in flight) | N (bounces instead) | N | N |
| ROPE | - | Y vx 5.32 vy 10.12, lockout 0.28 (! fires from a buffered press made before the catch, P3) | ! stale coyote after bottom release (P1) | N | N | N | Y knockOff (! re-grabbed next frame, N2a) | - | Y beam after 0.06 s hold | C only at the very bottom, moveY<-0.7 |
| BEAM (rope top) | Y |d|>1.1, coyote kept | Y full jump | Y (P1) | N | N | N | Y knockOff; Enforcer swat (N6) | - | - | -> ROPE (lockout 0, coyote kept) |
| CABLE | - | Y vy 8.625, vx = stick | ! frozen coyote restored on drop | N | N | N | Y knockOff (re-grab once vy<4, inferred) | - | N | Y moveY<-0.7 or past w/2+0.1 |
| LEDGE (any platform) | - | N (jump = pull-up) | ! frozen coyote restored on drop | N | Y if deck gone (lockout 0.28) | live re-pin | Y knockOff; Enforcer swat (N6) | Y up or buffered jump | - | Y down / away |
| GOAL | Y | Y | Y | N | N | N | Y | Y | N | N |
| SEESAW | Y | Y | Y | Y blob far-end launch 19.9 m/s, ungated | N | Y slide / tilt belt | Y | Y | N | N |
| RAMP | Y | Y | Y | C skin 2 kick every 0.25 s (! sequence persists across respawn, P6) | C skin 1 after 0.5 s | C skins 3/4 | Y | Y | N | N |
| SPIKE_DROP top | Y | Y (+0.6 max(0,pv)) | Y | Y slam > 6 m/s -> vy 6 | N | Y with slab and anchor | Y | N | N | N |

---

## 5. Findings by root-cause class

| Class | Findings | Severity | Reason (gameplay / generation / solver) | Effect on the actual game |
|---|---|---|---|---|
| **R1 Transient grace state not cleared on forced mode change** (coyote/jumpBuf survive transitions that are not "leaving footing") | 6.1a-d stale coyote (BEAM->ROPE->release, grab freeze, crab/bee shove); N1 pad/spring bounce keeps coyote; 6.2 + N5 buffered jump survives rope/cable/ledge grabs, pad bounce, side catch, mantle | **HIGH** for N1 (an ordinary "walk off onto a pad, then tap jump" turns a 6.3 m bounce into a 0.44 m hop - a fall and a lost climb on a mandatory pad link); **MEDIUM** for the stale coyote and buffered-grab cases (extra mid-air jump the solver does not model: TR-09/TR-10 `solver_recognises=false`; buffered press makes a fresh rope catch leap off at once) | Players get an unexplained double jump after rope/beam/shove and an unexplained "dead" pad bounce; a press meant as an early jump turns a rope catch into an immediate leap. The generator never relies on these, but the human can hit them, and N1 can make a pad link fail for a human that the proof judged safe. |
| **R2 Hazard response does not detach / commit the body** (knockOff sets no lockout; response reference differs from hit test) | N2a rope re-grab loop; N2b knock direction from `hitBy.sAt` not the hit pose; 6.12 | **MEDIUM**: knock-off is a no-op on ropes (hit loop, `hits` and setbacks accumulate while the player is still hanging); wrong-way knock can throw the player across a carried saw or toward a ball | Rope next to a saw: player stays on the rope and is hit every 0.72 s while idle (P12); solver counting setbacks per move is unaffected but its model of "knocked off -> falls" is wrong for ropes. Carried saws/cannon trails/aimed balls push the player in a direction that does not match what they saw. |
| **R3 Completion paths that skip validity checks** (pull-up / mode end not re-checking that the target still exists or is tangible) | 6.13 pull-up onto a crumbled deck; N3 pull-up onto an out-of-plane MOVE_Z; N9 jump on the frame the deck vanishes | **LOW-MEDIUM**: crumbled case drops you a frame later (harmless); MOVE_Z case lets a player stand on a platform that is visually 1.6-3 m deep, with a visible depth pop | Visual inconsistency and a small leniency; no soft-lock. A solver that treats an out-of-plane MOVE_Z as unreachable is conservative, so no false "solvable". |
| **R4 Per-element dynamic state not restored on respawn** | N4 shaking-ramp kick counter, seesaw tilt, belt direction, ramp onT; 6.20 | **MEDIUM**: shaking-ramp outcome differs per attempt (P6: attempt 2 kicks the opposite way) while generator proofs replay from a fresh counter; violates "a restart that leaves stale state" anti-slop criterion in CLAUDE.md | Retry of a shaking ramp is not the same challenge the proof verified; "quick retry" is not a clean reset. |
| **R5 Inconsistent gating between sibling rules** | N6 Enforcer swat not mode-gated (all other creature contacts are); N10 grab vy thresholds evaluated post-gravity; 2.17 ropes and cables share one first-by-index loop; N7 beam mount discards a buffered jump (no other mode does) | **LOW-MEDIUM**: N6 can knock the player off a rope/ledge/beam, contradicting A section 4 and coverage; N10 shifts solver thresholds by 0.57 m/s | Mostly invisible; N6 matters only in PUNISHER Enforcer encounters near ropes/ledges. |
| **R6 Missing hole/occupancy check on an alternate entry path** | 6.16 / P5 step-across lands on an open hole; N11 holes never close while you stand | **LOW**: step-across into a hole drops you exactly where walking would; standing keeps holes open (no exploit: you are on solid plank) | No real gameplay impact; relevant only to solver modelling of hole timing. |
| **R7 Latent invariant gaps (not reachable today)** | 6.22 rebase GROUND with onElem -1; N8 `isPlatform()` true for feature/creature types; 6.21 copy drops deferRespawn/floorOverride; 6.23 EV_FALL_NEAR dead | **LOW**: no reachable path found (Tower keeps the player's slice resident; features never enter `elements`) | None today; would crash or mis-collide if the streaming or course-building invariants change. |
| **R8 Verification gap in the coverage register** | Transitions listed in section 3 missing from TR-01..33; MV-06, MV-07, MV-18, MV-27, MV-37 have no sim test; N1, N2a, N3, N6 absent | **MEDIUM** (verification): the register claims "creatures GROUND/AIR only" and "pad bounce" verified while the code allows N6 and N1 | The coverage gate cannot catch regressions or defects in these paths. |
