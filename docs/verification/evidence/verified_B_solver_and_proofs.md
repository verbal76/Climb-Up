# Verification of B (solver and generator proofs)

This is an independent, read-only check at HEAD c9aa5ae. The probe classes came from `$S/dev2`, which contains `forkCorridorsClear`, so they match HEAD.

Paths are relative to `core/src/main/java/com/hotatticgames/climbup/sim/`. Abbreviations: AP = Autopilot.java, CG = CourseGenerator.java, Sim = Sim.java, Co = Course.java, Tw = Tower.java, GI = GeometryIntegrity.java.

Line numbers in AP are unchanged since B was written. CG has drifted by about +40 lines, and every CG citation below uses current lines. `$S` = `/tmp/claude-0/-home-user-Climb-Up/3cc9bd9b-3462-53b3-a9d2-97a9a2be1c43/scratchpad`.

Verdict counts:

| Verdict | Count |
|---|---|
| CONFIRMED | 70 |
| PARTIAL | 8 |
| REFUTED | 2 |
| UNVERIFIABLE | 2 |
| Total | 82 |

## 1. Verdict table

### B section 1: policies and candidates

| # | Claim in B | Verdict | Evidence / correction |
|---|---|---|---|
| 1 | Every candidate is wrapped in Reflex. | CONFIRMED | AP:233, 242-284 |
| 2 | Every policy answers LEDGE/PULLUP with moveY=1. | CONFIRMED | AP:63, 90, 99, 119, 141, 161, 202 |
| 3 | HUMAN_HOLD is 6 steps and is set in trialStart. | CONFIRMED | AP:43, 359. The run (846) and chainClean (596) set it too. |
| 4 | GroundPolicy: the take-off edge formula, a step back past 0.3 m, a jump press within 0.06 m, and the hold/steer phase. | CONFIRMED | AP:65-83. The offset is clamped by `max(0, w-0.5)`. |
| 5 | AirPolicy, RopePolicy, CablePolicy (hold 0.3, steer 0), WalkOffPolicy. | CONFIRMED | AP:86-145 |
| 6 | MidHop hops the first hazard only. | PARTIAL | On a crab deck it hops each live crab in turn (`crabAhead` over every crab on the deck, AP:176, 182-194). Only the first non-crab hazard gets a hop. |
| 7 | HoleHop, Reflex skull/crab/blob, restart of the inner policy. | CONFIRMED | AP:198-284 |
| 8 | Steering modes, and ground moves skipping mode 2. | CONFIRMED | AP:32-41, 479, 528 |
| 9 | Rope 12, cable 8 and pad 6 candidates; no wait parameter. | CONFIRMED | AP:464-471 |
| 10 | Ground: 24 per wait; waits = min(24, ceil(P/0.3)+1); at most 6.9 s; the movingNear rule. | CONFIRMED | AP:292-296, 473-484 |
| 11 | Walk-off, hole-hop (tried first) and mid-hop candidate rules. | CONFIRMED | AP:486-512 |
| 12 | Slow approaches only in the whole-course solver, only when not movingNear and not pair. | CONFIRMED | AP:513-532, 836-843. They are tried only when no main candidate succeeds (AP:553). |
| 13 | Trial limits 10.5 s or 4.5 s; WaitPrefix is exact. | CONFIRMED | AP:534, 538, 397-428 |
| 14 | MotionCache is exact and time-pure. | CONFIRMED | MotionCache.java:30-55 keys on generation and the raw float bits of the time. Sim:500-505 and 1368-1373 cache only `sAt/yAt` and the crab sweep facing, all pure functions of time. |
| 15 | Action table: coyote absent; walk-off only downward; no deliberate ledge targeting; no rope/cable wait in proofs; no run-up in proofs; rope down-climb only from BEAM. | CONFIRMED | As cited in B |

### B section 2: success, arrival and trials

| # | Claim in B | Verdict | Evidence / correction |
|---|---|---|---|
| 16 | `succeeded` counts GROUND/LEDGE/PULLUP/ROPE/CABLE/BEAM on onElem>a on the route, else lastPad>a. | CONFIRMED | AP:308-317 |
| 17 | `succeededTo` targets an exact element, and pair trials also need staysOn. | CONFIRMED | AP:320-333, 385. Not in B: `staysOn` returns true for any non-GROUND mode (AP:329), so a pair link that ends in a ledge catch skips the brake check entirely. |
| 18 | A LEDGE catch is arrival and the pull-up is in no commit proof. | CONFIRMED (probe P2/P3) | 23 of 120 first-found moves arrive in LEDGE/PULLUP; 47 across 3 PUNISHER slices. PUNISHER seed 2 slice 2, link 60 (margin 0.917) arrives in PULLUP and the pull-up is hit by CANNON anchor 58. |
| 19 | The run does not check setbacks after the pull loop. | CONFIRMED (probe P4) | AP:1040-1047. PUNISHER s2 slice 0's accepted witness has hits=2. The last hit came after the move from 107 succeeded and before a=109 (the pull/settle section). |
| 20 | trialEnded fails on setback, on lower or off-route ground, and below floorY; ROPE/CABLE on earlier elements are not failed early. | CONFIRMED | AP:380-389 |
| 21 | Plan window: route a-1..a+6, decoys a-5..a+7, hazards a-4..a+9, plus side CANNON, ENFORCER and AVALANCHE ±8. | CONFIRMED | Sim:122-143, Co:65-81. The streaming (Tower) branch counts route elements instead (Sim:130-139). |
| 22 | AIMED, MORTAR, BEE and creatures outside the window are never in plan/commit trials. | CONFIRMED | Co:65-81 extends only CANNON backwards. chainClean's execution is unwindowed (AP:596), but its plans are windowed. |
| 23 | Pair mode adds earlier platforms within 12 m arc / 9 m height. | CONFIRMED | Sim:146-162. Not in B: the pair target itself can fall outside the window (new finding N1, probe P1). |
| 24 | margin = successes / trials. | CONFIRMED | AP:17, 535-552 |
| 25 | The window is computed only for non-empty groups, so rope, cable and pad links keep window=1 and the gate is vacuous there. | CONFIRMED | AP:563, 464-471. Groups are added only in GROUND branches. |
| 26 | Group-id collisions: MidHop 18-21 and HoleHop 16 collide with oi=2; WalkOff 24, 25, 27 collide with oi=3; MidHop merges both steer variants, so the window can exceed 1. | CONFIRMED | AP:482, 490, 495, 508. `(int)(hd*2f)%8` gives 2..5, so ids 18..21, which collide with ids 19..21 of oi=2. |
| 27 | Lookahead never refuses, so commit's pad check is vacuous. | CONFIRMED (probe P2) | AP:564, used at CG:2118-2124. 120 links gave 0 ok-mismatches between plain and lookahead plans. It can only differ from the CG:2098 proof on a tiltBelt take-off, because 2122 uses a standing start. |

### B section 3: whole-course run

| # | Claim in B | Verdict | Evidence / correction |
|---|---|---|---|
| 28 | The run starts at t=0 standing with keysFree=false, and keys are granted for gates whose key lies earlier. | CONFIRMED | AP:846-851 |
| 29 | The run executes on the full Sim. | CONFIRMED | AP:1020 |
| 30 | Loop steps 1-11 (stuck>30, followBranch, lost route, slab, endless completion, key detour, brake, BEAM, recover, resync, belt standAt). | CONFIRMED | AP:857-917 |
| 31 | The detour runs only when the climber stands on kr[0], so skipping kr[0] means it never runs. | CONFIRMED | AP:880-885. Worse than B says: `visited.add` runs before the GROUND check (881-882), so one arrival that is not standing uses up the detour for good. A skipped detour does not fail the run (N8). |
| 32 | The plan fallback chain: settle, tryBack, offsets, standAt, reposition, recover, drift, stand still, resync, "plan failed". | CONFIRMED | AP:918-1003 |
| 33 | Look-ahead against every hazard: launchClear, crumbleClear, 25 looks. | CONFIRMED | AP:1008-1016, 616-671 |
| 34 | Optimistic exits: undecided 20 s returns true; launch with no plan returns true; playClear returns true after 10 s; the move is executed after 25 looks. | CONFIRMED | AP:630, 648, 669-670, 1009. Not in B: a LEDGE catch on a non-crumbling deck returns true without the pull-up (AP:624-627). |
| 35 | Any setback during execution fails, so the witness must be hit-free. | REFUTED (probe P4) | Only the execution loop (AP:1024-1032) fails on a setback. Hits during pull-ups (1040-1044), stand-still waits (996), look-ahead holds (1012-1013), pad drift (987) and recover (766-779 counts falls only) are tolerated, because `fallsBefore` is re-baselined per move (1019). A missed jump that lands on a lower route deck is resynced, not failed (903, 1029-1033). PUNISHER s2 slice 0 was accepted with hits=2. |
| 36 | Stall: 2 re-plans per link. | CONFIRMED | AP:1037 |
| 37 | A timeout returns completed=false with an empty failInfo. | CONFIRMED (probe P2) | `run(maxSimSeconds=2)` gives completed=false, failedLink=-1, failInfo='' |
| 38 | A decoy landing has no recovery. | CONFIRMED | AP:778, 903. In endless slices the only decoys are fork branches (handled by followBranch) and none are dead ends, so this is moot there. |

### B section 4: start states and proofs

| # | Claim in B | Verdict | Evidence / correction |
|---|---|---|---|
| 39 | startOn makes a new Sim with keysFree. | CONFIRMED | Sim:321-323 |
| 40 | GROUND start at the deck centre; with a mid hazard at `es1-halfW+1`, always on the -s side; RAMP and SEESAW offsets; crumble timer reset. | CONFIRMED | Sim:340-349 |
| 41 | The start has the stick "just released". | UNVERIFIABLE | It is not set in startOn or spawnAtCheckpoint (Sim:311-318). It is plausibly the default Sim state. |
| 42 | ROPE starts at the bottom, CABLE at the -s end +0.4, PAD/SPRING in AIR. | CONFIRMED | Sim:327-339 |
| 43 | quietStart: 0.15 s steps up to 9 s, 24 quiet steps, skipped when moving, crumbling or not GROUND. | CONFIRMED | CG:2183-2188, 2202-2206 |
| 44 | provenStart uses the belt landing. | CONFIRMED | CG:2193-2201 |
| 45 | commit: quiet start, belt landing, margin, window gate, dx -0.5/+0.4, EM conflict, threat budget. | CONFIRMED | CG:2089-2140. The dx re-plans need `ok` only, with no margin (2114). |
| 46 | heldBefore uses the standing start and never the belt landing, and failed links are skipped. | CONFIRMED | CG:2077-2084, 2090. A cannon on the landing is always proven (2079-2080). |
| 47 | chainClean users: DROPS with enemies, Enforcer, Avalanche. | PARTIAL | The DROPS check runs only when `endless && P.progression()` and the row has enemies (CG:2369-2372). Enforcer at CG:778, avalanche at CG:868-869. |
| 48 | chainClean: no waiting, repositioning or look-ahead; a pad flies with no input; BEAM is rejected; every setback fails. | CONFIRMED | AP:594-613. The setback check after the pull-up is at 602. |
| 49 | pairOk: startOn, no quietStart, 9 spots, parked skull, margin ≥ min(minLinkMargin, 0.05). | CONFIRMED | CG:1286-1302. Non-GROUND starts use the centre only (1292). |
| 50 | routeLinkStillOk: no quietStart, no belt landing, ok only; window only for gap saws. | CONFIRMED | CG:2325-2331 |
| 51 | widenForGem uses margin 0.12. | CONFIRMED | CG:1113 |
| 52 | tryDecoy/placeDecoy are never called. | CONFIRMED | grep finds only the definitions at CG:1561, 1688. |
| 53 | linkExists. | CONFIRMED | AP:829-832 |
| 54 | continuous is called only from chunkIn, and finite generate() does not run it. | CONFIRMED | CG:93, 69-77. generate() also runs no checkIntegrity and no checkLaunchCorridors. |

### B section 4: re-proving and order

| # | Claim in B | Verdict | Evidence / correction |
|---|---|---|---|
| 55 | commit `from` = min(n0-1, n0-2, anchor-1-roamReach). | CONFIRMED | CG:2060-2066 |
| 56 | Earlier links are covered only by geometric rules. | CONFIRMED | |
| 57 | Re-proved links keep their need, and heldBefore=false links are exempt for ever. | PARTIAL | heldBefore is recomputed at every commit (CG:2077-2084). A link is exempt for as long as it fails the standing-start proof, which in practice is permanent. |
| 58 | Belt links fall in the exempt set. | UNVERIFIABLE (no probe) | It holds only if the standing-start proof fails or its margin is below need. That is plausible, since a standing start at the belt's end is carried off. |
| 59 | chainClean results are never re-run. | CONFIRMED | Its only callers are CG:778, 868-869 and 2371. |
| 60 | tryAvalanche attaches after commit, and rollback re-plans it. | CONFIRMED | CG:858-880, 1204-1213 |
| 61 | Forks and key rooms come after the route; key rooms last. | CONFIRMED | CG:292, 1326-1368 |
| 62 | Fork re-proof covers a-3..t+2; key room rIdx-7..rIdx+5. | CONFIRMED | CG:1413, 1445, 1541, 1276 |
| 63 | Nothing re-proves earlier branches' pair links. | CONFIRMED (probe P2) | No pairOk call is reached after a fork registers. In 8 forks across 3 slices the re-proofs still held. |
| 64 | Re-proofs drop the margin. | CONFIRMED | CG:2328 |
| 65 | hazardOk checks the pads that exist at placement; checkLaunchCorridors is other-revolution only, PAD_FLIGHT_MAX 12 m. | CONFIRMED | CG:1946-1954, 1894-1912, 1877. The audit skips context-vs-context pairs (1903). hazardOk's own-slice pad test is NOT limited to other revolutions (1946-1950). |
| 66 | decoyOk keeps clear of route pad columns and contReserve. | CONFIRMED | CG:1656, 1664-1668. Only route pads (`j<rs && anchor<0`) are covered, not fork pads (N5). |

### B section 4: structures

| # | Claim in B | Verdict | Evidence / correction |
|---|---|---|---|
| 67 | Fork types and forkLayersClash. | CONFIRMED | CG:1381-1551, 1800-1807 |
| 68 | No dead ends are built. | CONFIRMED | |
| 69 | Key rooms are finite and legacy; keyOnRoute has no proof, and only continuous exercises it. | PARTIAL | Key rooms are never built in endless (CG:1226 skips exact castles). The detour does run in continuous for the slice that places the key (keyRooms carries mode-2 entries, CG:1174, 1316-1323), but only if the run stands on kr[0]. Skipping it fails nothing. |
| 70 | Castle stairs are proven by commit; the gem is a flag or a re-proven widened deck. | CONFIRMED | CG:652, 1093, 1103-1119. widenForGem re-proves only links i-1 and i and skips geometryOk (N10). |
| 71 | Context: the last 8 route elements plus hazards with anchor ≥ from-2. | PARTIAL | Hazards anchored at from-2 and from-1 get anchors -2 and -1, whose decks are absent (CG:240). Hazards anchored on the previous slice's side elements (anchor ≥ rs) are neither copied nor ghosted (CG:240, 245). |
| 72 | Ghosts are never simulated. | CONFIRMED | |
| 73 | The closing rest is checked geometrically only. | CONFIRMED | CG:306-367 |
| 74 | continuous never simulates the previous slice's hazards or geometry. | PARTIAL | element 0 is the previous slice's closing rest, and the hazards anchored on it are kept (`anchor >= ctx-1`, CG:419-429). |

### B sections 5 and 6

| # | Claim in B | Verdict | Evidence / correction |
|---|---|---|---|
| 75 | Phase table: one witness phase per slice, pad vx=0, crumble reset, restartHold. | CONFIRMED | |
| 76 | "belt landing ±0.3". | PARTIAL | Only when `beltSawNow && endless && P.progression()`, i.e. at the belt's own placement, never in re-proofs (CG:2099-2103). |
| 77 | launchClear, crumbleClear and followBranch exist only in the run. | CONFIRMED | Called only from clearOfEverything and runInner |
| 78 | The lattice is fixed; waits are capped at 6.9 s. | CONFIRMED | |
| 79 | Proof windows ignore distant hazards and ghosts. | CONFIRMED | |
| 80 | The list of non-Sim filters. | PARTIAL | They exist as described. The EncounterModel line numbers were not re-checked. |
| 81 | continuous covers one time origin and is not run for finite towers. | CONFIRMED | |
| 82 | The coverage note "the witness must be hit-free" (MV-33), as repeated in B. | REFUTED | See #35. |

## 2. Probes run

Sources are in `$S/verifyB/com/hotatticgames/climbup/sim/`, classes in `$S/verifyB/out`, and raw output in `$S/verifyB/*.txt`. PUNISHER is `hardCopy(1)`.

### P1/P2: `VerifyBProbe 1 2 2 40` (PUNISHER, seed 2, slices 0-2, first 40 links each)

```
== slice 0: gen 6.5s routeSize=111 size=115 hazards=37 forks=4 keyRooms=0
  fork start=72 first=111 len=1 rejoin=73 ... fork start=88 first=114 len=1 rejoin=89 (rejoin-anchor=1)
  window(a=2,-2..+6) contains a+7=false a+8=false (target a+8 requested)
  fork 2 pad 113 corridor box holds side element 114 (PAD ds=-7.8 dy=10.3)
  links checked=40 lookahead-vs-plain ok mismatches=0 ledge/pullup arrivals=9
  run(maxSimSeconds=2): completed=false failedLink=-1 failInfo='' simTime=3.72
== slice 1: gen 3.4s routeSize=80 size=81 hazards=25 forks=1 keyRooms=1
  keyRoom [55, -1, 0, 2, 24, -1]
  links checked=40 lookahead-vs-plain ok mismatches=0 ledge/pullup arrivals=9
== slice 2: gen 8.1s routeSize=99 size=109 hazards=23 forks=3 keyRooms=0
  fork start=30 first=99 len=6 rejoin=35 (rejoin-anchor=5) lastType=PAD anchor=30
  fork start=36 first=105 len=3 rejoin=39 (rejoin-anchor=3) ...
  fork 0 pad 104 corridor box holds side element 105 (STATIC ds=10.4 dy=3.9)
  links checked=40 lookahead-vs-plain ok mismatches=0 ledge/pullup arrivals=5
```

No line reading "fork ... FAILS on final slice" appeared, so every fork pair link re-proved ok with margin ≥ 0.05.

### P3: `PullProbe 1 2 2` (every route link of the same 3 slices; the first-found move, then a pull-up with moveY=1)

```
slice 0 link 35: after pull-up onto 36 no next-link plan from the real post-pull-up state
slice 2 link 60 (STATIC) arrive PULLUP on 61: pull-up [window] -> mode=AIR setbacks+1 after 16 steps
slice 2 link 60 (STATIC) arrive PULLUP on 61: pull-up [all hazards] -> mode=AIR setbacks+1 after 16 steps
LEDGE/PULLUP arrivals=47 pull-up setback or not GROUND [window]=1 [all hazards]=1; next link unplannable after pull-up=1
```

### P3b: `PullDetail`

```
link 60 STATIC(w=4.0)->STATIC(w=1.0) dy=0.91 margin=0.917 (22/24) window=1.00
  hazard CANNON anchor=58 ... SPIKE_BLOCK anchor=59 ... SPIKE_BLOCK anchor=62 ... SAW_H anchor=63
arrival t=1.10 mode=PULLUP on=61; after pull-up: mode=AIR setbacks+1 hitBy=CANNON anchor=58
```

### P4: `WitnessHits` (the accepted continuous witness re-run, `lastReal` counters)

```
hard=1 seed=2 slice 0: witness completed=true simTime=128.8 links=110 hits=2 falls=0
hard=1 seed=2 slice 1..4: hits=0
hard=0 seed=1 slice 0..3: hits=0
```

`WitnessHitWhere` traced the last hit to CANNON anchor 105 at s=35.66, y=80.26. In the dbg trace (`$S/verifyB/dbg_slice0.txt`) it falls between `loop a=107 t=124.83` and `loop a=109 t=126.20`. The move from 107 had already succeeded, so the hit happened in the unchecked pull/settle section (AP:1040-1047). The slice was still accepted.

## 3. Placement-and-proof table

The list order is the execution order.

| Path | Checks in order | Dependency-order defects |
|---|---|---|
| Route module (tryModule→commit, CG:2344-2377, 2042-2146) | Add es+hz, then geometryOk: clearOfOtherLayers, then hazardOk per hazard (not CLUB), then padCorridorClear for the new elements, then sawsClearOfCreatures, then protectedRowsOk. Roll back. heldBefore proofs on the old course. Re-add. geometryOk again. Per-link proofs from `from`: margin, window gate, mid-hazard dx, pad lookahead (vacuous). EM conflict. Threat budget. setLinkNeed. DROPS chainClean if endless&&progression&&enemies. Castle-approach rollback (CG:619-625). varRecord. tryAvalanche. | Route pads always have their landing inside the module (build PAD/SPRING end with a deck, CG:2761-2811), so padLanding resolves. The re-proof `from` = anchor-1-roamReach is shorter than the AIMED (15 m, any direction) and BEE (7 m) reach, so earlier links in range are never re-proven (N3). chainClean results of earlier structures are not re-run (N4). |
| Rest (addRest, CG:1715-1734) | commit(e, 0.12). Climb-first tryModule. | Same as a route module |
| Exact castle (CG:628-660) | addRest. commit(stairs+deck, GATE). addRest. pendingCastles. | GATE is exempt from the corridor check. The key comes later (placeKey). |
| Finite castle (tryCastle, CG:1178-1202) | HOP/STAIRS modules. commit(deck, gate). addRest. | The key room is proven later. A gate whose room fails is removed after all proofs (CG:1241). Harmless. |
| Gem flag / addRest(gem) | A flag only, or a commit | none |
| widenForGem (CG:1103-1119) | Mutates e.w. clearOfOtherLayers. Neighbour gap. Re-proves links i-1 and i only (margin 0.12). | Mutates a committed deck with no geometryOk (hazard standing room), EM or threat check. Other links whose window or flight passes the widened deck are not re-proven (N10). |
| Endless key (placeKey/keyOnRoute, CG:1139-1176) | Candidate filter (STATIC, no mid hazard). Prefers keyClear+threatAt==0; the fallback ignores both. Adds the KEY. | No proof of collection. Continuous walks to it only if it stands on kr[0] (N8). |
| Key room (finite, tryKeyRoom CG:1244-1283) | K added + decoyOk(K). P added + decoyOk(P), whose padCorridorClear sees `padLanding`=null (nx=c.size()), so a column only. KEY added. pairOk out/back/pairOkAir. routeLinkStillOk rIdx-7..+5. Registration. | P really lands on rIdx, but its flight corridor is never checked, before or after registration. No forkCorridorsClear equivalent. Finite generate() runs no corridor or GI audit or witness (N6). |
| Reserved fork (CG:1381-1420) | Per element: add + decoyOk(fork) (pad: column only before registration). indexDecoys. pairOkAir(pad→j). pairOk(e→n0). pairOk chain. routeLinkStillOk e-3..j+2. c.forks.add. forkCorridorsClear. Falls back to tryPadFork. | Fixed in c9aa5ae. Remaining: branch elements are never checked against existing hazards' same-revolution standing room. Other-revolution conflicts are caught only by GI I4 at slice audit (whole attempt). |
| Pad fork (CG:1427-1454) | Add pad + decoyOk. pairOkAir(n0→t). pairOk(a→n0). routeLinkStillOk a-3..t+2. Registration. forkCorridorsClear. | as above |
| Open fork / sub-fork (tryFork CG:1485-1551, 1353-1362) | as reserved | Rejoin candidates t ∈ a+7..a+9 (forkReach 7/9) can never be proven: pairOkAir's window (anchor-2..+6) leaves t out of `act`, and Sim collides only with `act` (Sim:1737, 1813) (N1). Later forks and sub-forks re-prove only route links. Earlier branches' pair links and branch-pad corridors are not re-proven (decoyOk covers route pads only) (N5). |
| Enforcer (CG:764-782) | commit(decks, 0.12) without the robot. Robot appended. chainClean(n0-1, n0+len). | Inserted after the decks' proofs and outside geometryOk, EM conflict and the threat budget (N7). Never re-proven after later modules (N4). chainClean flies pads with no input (conservative). |
| Avalanche (CG:858-880) | Trigger deck chosen (avlTriggerDeck: nothing anchored) at selection. 4 more decks committed without it. Attached. chainClean(avlCand-1, avlCand+4), plus 3 waits for NGH. | The trigger rule is not re-checked at attach (CG:861 vs 864), so the next module's buildHaz j==0 or club can anchor on the trigger deck. The hazard is live in windows ±8 (Co:76), but links avlCand-8..avlCand-2, committed earlier, are never re-proven with it. No geometryOk (N7, N9). |
| DROPS with enemies | commit, then chainClean(cn0-1, end) | Not re-run later (N4) |
| Context copy (CG:237-249) | Copy the route tail and hazards [from-2, rs). Ghosts get anchor < from-2 and prev side elements. | Negative-anchor hazards. Side-anchored hazards are dropped. Ghost pad "landing" is the next ghostElems list element (CG:1909, 1953): wrong for the last ghost route pad (whose next is a ghost decoy) and for ghost fork pads (should be the rejoin) (N11). |
| Decoys/spurs | Dead code | none |
| Slice audits (chunkIn CG:93) | buildChunk, checkIntegrity (GI), checkLaunchCorridors, extract, continuous, progFinish | Run after every structure has registered, so they are the only checks that see final dependencies. They cover other revolutions only (corridors, I1/I4). |

## 4. Solver coverage register

| MV | Status claimed (solver / proof) | Planner move? | Generator proof? | Whole-course run? | Flag |
|---|---|---|---|---|---|
| 01 | P / P | Yes, run speed (AP:73). Slow speeds in the run only (AP:527). | Yes | Yes | ok |
| 02 | P / P | Incidental (real Sim) | Yes | Yes | ok |
| 03 | P / P | 2 holds (AP:27) | Yes | Yes | ok |
| 04 | N / N | No | No | No | ok |
| 05 | N / N | No | No | No | ok |
| 06, 07 | N / N | No | No | No | ok |
| 08 | P / P | By-product | Yes | Yes | ok |
| 09 | P / P | WalkOff, downward only (AP:486) | Yes | Yes | ok |
| 10 | P / P | Steer modes | Yes | Yes | ok |
| 11 | V / V | Yes | Yes | Yes | ok. Arrival also counts LEDGE etc. |
| 12-15 | P / P | By-product | Yes | Yes | ok. MV-13: the probe found 47 LEDGE/PULLUP arrivals and one pull-up hit in a link proven at 0.917. The pull-up is unproven. |
| 16 | P / N | Pull-up only (AP:63) | No | Pull loop is unchecked (AP:1040) | ok, but the run tolerates hits in it (probe P4) |
| 17 | P / P | RopePolicy | Yes, no wait | Yes, with waits | ok |
| 18 | N / N | No | No | No | ok |
| 19 | P / N | Recovery only (AP:889) | chainClean rejects | Yes | ok |
| 20 | P / P | CablePolicy | Yes, no wait | Yes | ok |
| 21 | P / P | AirPolicy | The pad-arrival lookahead is vacuous (AP:564, CG:2118) | launchClear is optimistic (AP:648) | Proof note should say the pad-arrival check is vacuous |
| 22 | **V** / P | AirPolicy | As MV-21 | Same launchClear optimism as pads | **VERIFIED unsupported; should be PARTIAL** |
| 23 | P / P | By-product | Yes | Yes | ok |
| 24 | P / P | Not deliberate | Yes | Yes | ok |
| 25 | P / P | Waits only when movingNear | Yes | Yes | ok |
| 26 | **V** / P | Ground moves; 4.5 s limit | Timer reset at start (Sim:347) | crumbleClear, but playClear returns true after 10 s and the move runs after 25 looks (AP:669, 1009) | **VERIFIED unsupported; PARTIAL** |
| 27 | P / P | HoleHop | Yes | Yes | ok |
| 28-31 | P / P | Incidental | Yes | Yes | ok. MV-29: heldBefore and routeLinkStillOk use a standing start, so belt links are exempt and fork re-proofs near a belt fail. |
| 32 | P / P | No | Incidental | slabStepOff | ok |
| 33 | P / P | Reflex/MidHop | Yes, setback = fail | Fails only inside execution | **Note "witness must be hit-free" REFUTED (probe P4)** |
| 34 | P / P | Recover | Yes | Yes | ok |
| 35 | P / P | Reflex | Yes | Yes | ok |
| 36 | P / P | Reflex/plan | chainClean once, never re-run | Yes | Note should add "not re-proven after later modules" |
| 37 | P / N | Reflex | No | Yes | ok |
| 38 | P / P | No | chainClean, 1 or 4 arrival times | Yes | ok. Note should add "earlier links in the ±8 window not re-proven" |
| 39 | P / P | Gate opens with keysFree in proofs | Route key unproven | Detour only when standing on kr[0]; skipping is silent | ok, but understated |
| 40 | NA / NA | | | | ok |
| 41 | P / N | | | A missed landing on a lower route deck is resynced (AP:903, 1029-1033) | **Note "the solver fails the move (no retry from below)" REFUTED** |
| 42 | N / N | | | | ok |
| 43 | **V** / P | No deliberate branch move | Pair proofs at placement only | followBranch only when carried onto a branch. The live Tower world has no forks registered (Tw:346-377). | **VERIFIED unsupported; PARTIAL** |
| 44 | N / N | No | No | Incidental pickup only | ok |

## 5. New findings

The headings give: root-cause class, then severity.

### N1. Pair-proof window excludes far rejoins (window narrower than proof target; LOW)

setWindowNear(anchor-2, anchor+6) (AP:361) leaves out rejoins at anchor+7..+9. tryFork tries those (forkReach 7 NORMAL, 9 PUNISHER/NGH; CG:1488). The probe confirms `act` holds neither a+7 nor a+8. Sim collides only with `act` (Sim:1737, 1813, 1835, 1913), so these candidates are always refused. The effect is wasted proof work and an effective reach of 6.

### N2. The witness tolerates hits and misses outside the move-execution loop (fallback masks failure / baseline reset; HIGH)

Evidence: AP:1019, 1040-1044, 996, 1012, 987, 776, 903, 1029-1033. Probe P4 found an accepted slice with 2 hits.

Completability still holds, because the Sim is deterministic and has no damage. But the "clean" guarantee claimed in B and in coverage MV-33 is false.

### N3. Re-proof range shorter than hazard reach (later placement invalidates earlier proof; MEDIUM)

`from = anchor-1-max(0, roamReach)` (CG:2064). For AIMED (roamReach -1, AIM_RANGE 15 m, any bearing, Sim:1551-1553) this re-proves only from anchor-1, or n0-2. Earlier links within 15 m that were committed before the cannon existed are never re-proven. BEE: re-proved from anchor-2, while the threat budget itself uses anchor-3 (CG:2073).

### N4. chainClean results are one-shot (later placement invalidates earlier proof; MEDIUM)

Enforcer, avalanche and DROPS encounters are never re-run when later modules add AIMED cannons, bees or cannons that reach back into them.

### N5. Branch pads are not protected (later placement invalidates earlier proof; MEDIUM)

decoyOk's launch-corridor rule covers route pads only (CG:1665-1667). Later forks and sub-forks may put platforms or pads in an earlier fork pad's corridor, and earlier branches' pairOkAir is never re-run.

The probe found two such corridor-box intrusions: slice 0 pad 113 with element 114, and slice 2 pad 104 with element 105. Re-proofs still passed in that sample.

### N6. Key-room pad landing never resolved (check before dependency registered / wrong landing; LOW, finite only)

At the decoyOk call (CG:1265), padLanding gives null, because the pad is the last element and nx = size (CG:1867). It really lands on rIdx. Nothing re-checks it afterwards, and finite builds run no audits.

### N7. Enforcer and avalanche bypass placement checks (insertion bypasses placement checks; LOW)

CG:777 and CG:866 append hazards with no geometryOk, protectedRowsOk, sawsClearOfCreatures, EM conflict or threat budget. Other-revolution conflicts are caught only by the slice audits.

### N8. Key detour skippable and consumed (fallback masks failure; MEDIUM)

`visited.add` runs before the standing check (AP:881-882). A skipped detour never fails the run, and later slices grant the gate's key (AP:848-851). Endless key collectability is therefore never enforced.

### N9. Avalanche trigger-deck rule checked only at selection (check before dependency registered; LOW)

The rule is checked at CG:861 but not at attach (CG:864). Earlier links inside its ±8 live window are not re-proven.

### N10. widenForGem mutates committed geometry with a partial re-proof (later mutation without re-proof; LOW)

CG:1110-1117: only clearOfOtherLayers, a neighbour gap and 2 link proofs.

### N11. Ghost pad landing is a list neighbour (wrong dependency resolution; LOW)

CG:1909 and 1953. For the last ghost route pad and for ghost fork pads, the corridor is modelled as a column only, or toward the wrong element.

### N12. Live Tower world differs from the proof world (proof environment differs from live game; MEDIUM)

The Tower world has `routeCount = -1` (Tw:346), so `routeSize()` counts every element. roamDeck, avlDeck and canHop (Sim:665-668, 1002-1005, 1455-1456) then accept fork-branch platforms as decks in live play, but never in generator proofs (extract sets routeCount, CG:432).

Each slice's decoys sit right after its route in the world list (Tw:368, Co:30-31), so a creature's `anchor + deckOff` can address them. Forks are not copied into the world (Tw:376 copies keyRooms only), so `branchOf` is always null live. movingNear and maxPeriod index raw a..a+2 (AP:293, 300), which can be decoys.

### N13. Three verdicts collapse into two (no verdict distinction; MEDIUM)

`Report` has only `completed` and `failInfo`. A timeout gives completed=false, failedLink=-1 and failInfo='' (probe P2). continuous turns every !completed into a refusal (CG:109-111).

Nothing distinguishes "the heuristic lattice gave up", "the budget ran out" and "unsolvable". Nothing can prove unsolvability, since the search is incomplete. The cost is false-negative refusals: up to 24 attempts, relaxed mode after 12, then a throw.

### N14. Redundant fallback plans (dead or redundant work; LOW)

Because of AP:564, every `plan(lookahead)` followed by `plan(plain)` (AP:605, 919, 926, 963, 998, 1014; CG crumbleClear 659) gives the same ok and factory. When no move works, the lattice is simulated twice.

### N15. Ledge arrivals bypass staysOn (proof start differs from real arrival; MEDIUM)

`staysOn` returns true for any non-GROUND mode (AP:329), so the survivability check is skipped for every ledge arrival in pair proofs. Clean pull-ups are not proven: probe P3 found one hit (link 60) and one pull-up end state with no next-link plan (slice 0 link 35).

### N16. routeLinkStillOk has neither quietStart nor margin (proof start differs / optimistic; MEDIUM)

Fork and key-room re-proofs can reduce a route link to a single working move. A cannon-swept deck can also spuriously fail at t=0, which wastes forks (CG:2328).

## 6. Findings by root-cause class

| Class | Instances | Severity (one-line effect) |
|---|---|---|
| Optimistic default on undecided | Lookahead fallback (AP:564), which makes the CG:2118 pad check vacuous; clearOfEverything 630; launchClear 648; playClear 669; execute after 25 looks (1009); LEDGE true without pull-up (624-627) | MEDIUM: pad-arrival and launch fairness are unproven per link; only the witness's luck covers them |
| Fallback masks failure / baseline reset | N2 (hits tolerated outside execution, misses resynced); N8 (detour skipped silently) | HIGH: the accepted witness contains hits (probe P4); the "clean" claim and MV-33/41 are false |
| Proof start differs from real arrival | LEDGE/PULLUP arrival with the pull-up unproven (#18, probe P3); staysOn skipped for ledges (N15); standing t=0 starts; routeLinkStillOk and pairOk without quietStart (N16); belt standing start in heldBefore; one t=0 phase per witness slice | HIGH: links proven at 0.917 can hit during the arrival pull-up; proofs do not chain |
| Check before dependency registered / wrong landing | Fork pads (fixed c9aa5ae); key-room pad (N6); ghost pad list-neighbour landing (N11); avalanche trigger rule (N9) | LOW-MEDIUM: corridor checks silently degrade to a column |
| Later placement invalidates earlier proof without re-proof | AIMED/BEE re-proof range (N3); chainClean one-shot (N4); earlier branches and branch pads (N5, #63); avalanche ±8 (N9); widenForGem (N10); heldBefore exemption ratchet (#57) | MEDIUM: earlier proofs become stale; only continuous (route only, one phase) might catch it |
| Insertion bypasses placement checks | Enforcer, avalanche, KEY (N7, N8) | LOW: rules are enforced asymmetrically; slice audits catch other-revolution cases |
| Metric aliasing | Group-id collisions and MidHop steer merge (#26); window=1 for rope/cable/pad (#25) | MEDIUM: the timing-window gate is overstated or vacuous on hazard links |
| Window narrower than proof target | Far fork rejoins (N1); distant AIMED, MORTAR, BEE and other-revolution hazards outside plan windows (#22) | LOW (N1, wasted work) / MEDIUM (#22, unseen hazards in per-link proofs) |
| No verdict distinction | N13 | MEDIUM: timeouts and heuristic gaps look like unsolvable slices |
| Proof environment differs from live game | N12 (Tower routeCount=-1, decoys as decks, no forks) | MEDIUM: creature and avalanche interactions with branches happen live but never in proofs |
| Unproven structure | keyOnRoute; finite generate() has no audits or witness (#54, #69) | MEDIUM (endless key) / LOW (finite tooling) |
| Dead or redundant work | placeDecoy/tryDecoy (#52); redundant plain plans (N14) | LOW: performance and maintenance only |
