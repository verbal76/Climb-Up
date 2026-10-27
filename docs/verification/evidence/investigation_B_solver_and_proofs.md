# B. Solver and generator proofs: what they can and cannot represent

Read-only audit at HEAD c8223ae. Paths are relative to `core/src/main/java/com/hotatticgames/climbup/sim/`. AP = Autopilot.java, CG = CourseGenerator.java, EM = EncounterModel.java, GI = GeometryIntegrity.java, MC = MotionCache.java.

## 1. Policies and the candidate move set

Every candidate is wrapped in `Reflex` (AP:233, AP:242-284). Each policy answers LEDGE/PULLUP with `moveY=1`, so it always pulls up (e.g. AP:63, 90, 99, 119, 141, 161, 202). Sim enforces a 6-step (100 ms) stick hold (`HUMAN_HOLD`, AP:43; set in trialStart AP:359).

| Policy | Inputs it produces | Parameters |
|---|---|---|
| GroundPolicy AP:54-84 | Phase 0: stands still `wait` s (AP:67). Then walks to take-off edge = `es1[a] + dir*(halfW - min(offset, w-0.5))` (AP:70), stepping back if more than 0.3 m past it (AP:74), and presses jump within 0.06 m (AP:75). Phase 2: jump held for `hold` s (AP:80). Air steering `steer(b, mode)`, or mode 3 = always `dir` (AP:81). | wait, offset, hold, steerMode, speed (feathered walk, AP:45-50) |
| AirPolicy AP:86-93 | Pad/spring launch: jumpHeld constant, steer(b, mode) | steerMode 0..2, held true/false |
| RopePolicy AP:95-113 | Climbs until hand is within `delta` of the rope top, then jumps toward `dir` (AP:105-106). Holds `hold`. Air: `dir` while more than 0.5 m from b, else steer mode 0 (AP:111) | delta, hold |
| CablePolicy AP:115-134 | Traverses toward `dir` until `k` from the end, then either jumps or drops with moveY=-1 (AP:128). Hold is fixed at 0.3 (AP:132). Steer mode 0 | k, drop |
| WalkOffPolicy AP:136-145 | Waits, walks off the edge without a jump, then steers | wait, steerMode |
| MidHopPolicy AP:148-195 | Waits, runs, hops `hopDist` before the hazard (or before the nearest live crab, AP:182-194) with jumpHeld while vy>0, then continues as GroundPolicy (offset 0, hold 0.9) at `restSpeed` | wait, hopDist, steer {0,3}, restSpeed, crab |
| HoleHopPolicy AP:198-230 | Hops each dropped-plank hole `hopDist` before its near edge, then GroundPolicy | hopDist, steer {0,3} |
| Reflex AP:242-284 | Only on GROUND. Hops a dashing skull on its own deck (AP:262-267). Hops a crab that followed off its deck (AP:268-271). Steps out of a blob shadow (AP:272-279). Then restarts the inner policy from scratch (AP:248, 251) | none (reactive) |
| PullPolicy AP:286-288 | moveY=1 | none |

Steering modes (AP:32-41): 0 = toward target centre, released within 0.25 m. 1 = always sign(d). 2 = near edge of target. 3 = always dir (in callers). **Ground moves skip mode 2** (AP:479, 528). AirPolicy uses 0..2 (AP:471).

### Candidate set in `planTo` (AP:455-566)

| Start | Candidates |
|---|---|
| a is ROPE (AP:464-466) | deltas {0, .5, 1, 1.6, 2.4, 3.4} x HOLDS {0.9, 0.22} = 12. **No wait parameter.** |
| a is CABLE (AP:467-469) | k {0, .6, 1.4, 2.4} x {jump, drop} = 8. **No wait.** |
| AIR on a PAD/SPRING (AP:470-471) | AirPolicy 3 steer x 2 held = 6 |
| otherwise (ground, AP:472-484) | waits x OFFSETS {0, .45, .95, 1.5} x HOLDS {0.9, 0.22} x steer {0,1,3} = 24 per wait. waits = 1 unless `movingNear` (AP:292-296: elements a..a+2 moving/spinning, or a moving hazard anchored a0-1..a0+2, with a0-1 counting only for cannon/aimed/mortar), then min(24, ceil(maxPeriod/0.3)+1) at 0.3 s steps (AP:474-477). So at most 6.9 s of phase. |
| + walk-off (AP:486-492) | Only when GROUND, both are platforms and b is more than 0.3 m lower: nWaits x steer {0,1,3} |
| + hole hops (AP:493-496) | holed deck: hd {.15, .45, .8} x steer {0,3}, tried first |
| + mid hops (AP:497-512) | **Only the first** SAW_H / SPIKE_BLOCK / SPIKE_TRAP / CRAB within the deck (`break`, AP:510): nWaits x hd {1, 1.5, 2, 2.5} x steer {0,3} |
| + slow approaches (AP:513-532) | Only when `SLOW_APPROACH` is set (whole-course solver, AP:836-843), GROUND, not pair, and **not movingNear**. MidHop at restSpeed {.5,.3} x 4 hd x 2 steer. GroundPolicy speed {.5,.3} x 4 offsets x 2 holds x 3 steer, wait 0. |

Limits: each trial runs 10.5 s, or 4.5 s from a crumbling deck (AP:534, 538). `WaitPrefix` shares the standing-wait prefix and is exact (AP:397-428). MotionCache stores only time-pure poses, keyed by exact time bits and generation (MC:37-40; Sim.java:500-505, 1368-1373), so it is exact, not an approximation.

### Physically distinct actions, present or absent

| Action | Status | Evidence |
|---|---|---|
| Variable jump height (early release) | Coarse: 2 hold values. Hops hold while vy>0. | AP:27, 80, 174 |
| Mid-air reversal or deliberate overshoot-and-return | Absent as an intent. Steering only aims at b; a reversal happens only when sign(d) flips. | AP:32-41 |
| Deliberate ledge-catch targeting | Absent. Ledge catches are a by-product of Sim; near-edge mode 2 is excluded for ground moves. LEDGE counts as arrival. | AP:479, 310 |
| Walking off an edge | Present only for a lower platform target (more than 0.3 m lower, both platforms). | AP:486 |
| Coyote jump (jump after leaving the edge) | Absent. | none |
| Catching an element other than the target | Allowed in route mode (any later route element counts), steered only to b. Pair mode requires the exact element. | AP:308-314, 320-325 |
| Waiting on a rope or cable | Absent in proofs (no wait parameter). Present in the solver run (stand-still loop, look-ahead holds). | AP:464-469; AP:993-1001, 1008-1016 |
| Riding a mover | Implicit through GroundPolicy's wait on the moving deck. No "ride, then walk to a spot". | AP:67 |
| Standing still for a hazard window | Only when movingNear (hazards anchored a0-1..a0+2). A hazard anchored elsewhere (another revolution, a+3 and beyond, an aimed cannon or mortar further away) gets wait = 0 in proofs. | AP:292-296, 474 |
| Longer run-up (walk back first) | Absent in proofs (start is the deck centre). Present in the run (tryBack, reposition). | AP:928-957 |
| Hopping a second mid-deck hazard, or a SPIKE_DROP / GATE / SKULL / BLOB | Not planned (first hazard only; skull and blob go to Reflex). | AP:500, 510 |
| Climbing down a rope, or waiting on a beam | Only as a run-loop recovery from BEAM. | AP:889-896 |

## 2. Success, arrival and what a trial checks

- `succeeded(s,a)` (AP:308-314): GROUND, LEDGE, PULLUP, ROPE, CABLE or BEAM on `onElem > a`, on the route (`anchor<0`, AP:317), and for GROUND not `gone`. Otherwise (AIR) `lastPad > a` on the route, so being launched by a later pad counts as arrival.
- `succeededTo(s,tgt)` (AP:320-325) is the exact element, or AIR with lastPad==tgt. In pair trials it also requires `staysOn` (40 idle steps still on tgt, AP:328-333; called at AP:385).
- **A LEDGE catch is arrival.** The pull-up is not part of the link's trial. The next link is proven from a standing start, so a hit during a pull-up lies in no commit proof. Only chainClean (AP:600-602) and the run (AP:1040-1044) play it, and the run does not check setbacks after that pull loop.
- `trialEnded` (AP:383-389) fails on any setback (hits+falls, Sim.java:362; a shove is not a setback). It fails on GROUND on an element below a or off the route. It fails when falling below `floorY` = min(y_a, y_next) - 4.5 (AP:380). ROPE or CABLE on an earlier or dead-end element is not failed early (only GROUND is checked at AP:386).
- Planning window (trialStart AP:358-364): `setWindow(a-1, a+6)` gives route elements a-1..a+6, decoys anchored a-5..a+7, and hazards `hazardsFor(a-4, a+9)` (Sim.java:122-143). That adds side CANNONs whose balls reach the stretch, the ENFORCER of the encounter, and AVALANCHEs within ±8 (Course.java:65-81). **AIMED, MORTAR, BEE and creatures anchored outside a-4..a+9, and every other-revolution hazard with a distant index, are not simulated in any plan/commit trial.** Pair mode adds earlier route platforms within 12 m arc and 9 m height (Sim.java:146-162).
- `Result.margin` = successes / trials over the whole candidate list (AP:17). `window` = best group's wins / nWaits (AP:563). It is computed only when the group list is non-empty: **rope, cable and pad links leave window = 1**, so the window gate in commit (CG:2067) is vacuous there. Group ids collide: MidHop `16+(hd*2)%8` and HoleHop 16 against ground ids `oi*8+hi*4+sm` with oi=2; WalkOff `24+sm` against oi=3 (AP:482, 490, 495, 508). MidHop puts both steer variants in one group (`+ sm*0`), so its window can exceed 1.
- **Lookahead never refuses**: `if (lookahead && !r.ok && firstOk != null) r.ok = true` (AP:564). So commit's check that the arrival on a pad "leaves a launch the next link can be made from" (CG:2075-2081, `plan(b,i,false,true)`) passes whenever any move works. It adds nothing over CG:2055.

## 3. Whole-course run (`runInner`, AP:844-1057)

Start: `Sim.startOn(c,t,0)` at t=0, keysFree=false. Keys are granted for gates whose key lies in an earlier slice (AP:846-851). Execution is on the full Sim (`act=null`, AP:1020). Per loop iteration:

1. More than 30 iterations on one link: fail **"stuck at one link"** (AP:860).
2. On a fork branch (GROUND/ROPE/CABLE): `followBranch` (AP:783-805). It plans pair moves along the branch to the rejoin, and from a branch pad lets the launch fly and re-plans every 6 steps. Failure: **"lost on a fork branch"** (AP:861-868).
3. `a` outside the route: **"lost the route"** (AP:869).
4. On a spike-slab top: step toward the slab's own deck for up to 2 s, else **"stuck on a spike slab top"** (AP:870-878; slabStepOff AP:760-765).
5. Endless mode with a reaching goalIndex: completed (AP:879).
6. Key room: `detour` once per anchor, only if the climber stands exactly on `kr[0]` (AP:880-885, 717-746). It settles, runs execLink chains out and back with shuffle-and-replan (AP:687-713), and walks to the key. A route key is mode 2, a walk across the deck. Failure: **"detour <why>"**. If the climber skips past kr[0] (pad, shove), the detour never runs.
7. Mid-deck hazard and no crab: brake to stop (AP:886-888).
8. BEAM on a rope: climb back down (AP:889-896).
9. AIR from a shove (not this link's own launch): `recover` (AP:766-779), steering toward es1[a] for up to 6 s and accepting route or branch decks. Failure: **"recover"** (AP:897-902).
10. Resync on another route deck, rope, cable or ledge (pull up first) (AP:903-909; resync clears a stale lastPad, AP:341).
11. Balance conveyor next: `standAt` centre + `restartHold` to reproduce the proof start (AP:911-917).
12. `plan(lookahead)`, then plain plan (AP:918-919). Fallback chain while the plan fails: `settle` (AP:923-927, 748-756). Walk back for a run-up, two spots (AP:928-940). Six offsets with a plain plan (AP:941-957). `standAt` centre + restartHold (AP:958-964). Mid-hazard decks: `repositionPlan`, 6 offsets x waits 0..3 s, tried on a copy first (AP:965-980, 569-580). AIR shove: recover (AP:981-983). Own pad launch: drift with re-plans every 6 steps for 2.5 s (AP:984-991). GROUND/ROPE/CABLE on a non-crumbling deck: stand still up to 5 s, re-planning every 12 steps (AP:993-1001). Carried to another route element: resync (AP:1002). Else **"plan failed"** (AP:1003).
13. **Look-ahead against every hazard** (AP:1008-1016). `clearOfEverything` (AP:616-631) replays the move on a copy with every hazard and element of the slice live. A move onto a pad or spring then needs `launchClear` (AP:640-650): the forced launch with re-plans every 6 steps. A move onto a crumbling deck (pulled up first) needs `crumbleClear` (AP:656-665): an exit move found within 0.3 s and played clean. On failure it waits 12 steps and re-plans, up to 25 times (about 5 s), on GROUND/ROPE/CABLE only. **Optimistic exits:** an undecided 20 s returns true (AP:630). A launch with no plan found returns true (AP:648). `playClear` returns true after 10 s even without success (AP:669). After 25 looks the move is executed anyway.
14. Execute for 20 s. Any setback: **"setback while executing the move"** (AP:1031); there is no checkpoint retry, so the witness must be hit-free. Shoved back onto an earlier deck or a branch for 0.5 s: resync (AP:1029-1033). Stall: up to 2 re-plans per link (AP:1037), then **"move did not reach the next link within 20 s"** (AP:1038).
15. Pull up (AP:1040-1044). `a` = lastPad or onElem, or the slab's deck (AP:1045-1047). Goal registration (AP:1049-1052). A timeout (`maxSimSeconds`) returns completed=false with an empty failInfo (AP:857, 1054).

A dead end (decoy) landing has no recovery branch. Planning from a decoy deck against `a` cannot succeed: GroundPolicy walks toward a's edge (AP:70), and trialEnded fails on non-route ground (AP:386).

## 4. Generator proofs

### Start states

- `Sim.startOn` (Sim.java:321-352) is a **new Sim (time 0)**.
  - GROUND: deck centre, v=0, lockout 0.1, facing +1, stick just released (Sim.java:311-318). With a mid hazard: `es1 - halfW + 1.0`, always on the -s side (Sim.java:342-345). RAMP and SEESAW use fixed back offsets. The crumble timer is reset (Sim.java:347).
  - ROPE: at the bottom. CABLE: at the -s end +0.4.
  - PAD: AIR with vx=0 and vy=padBounce. SPRING uses the amp angle (Sim.java:327-331).
  - keysFree=true (Sim.java:323).
- `quietStart` (CG:2139-2144, 2158-2162) advances the clock in 0.15 s steps, up to 9 s, to the first moment with no hit or shove during 24 standing steps. It does nothing on moving or crumbling decks or when not GROUND.
- `provenStart` (CG:2149-2157) = startOn + quietStart. On a tiltBelt deck it uses instead the landing the proven jump from i-1 makes.

### Which proof uses which start

| Proof | Start | Hazards live | Margin / extra |
|---|---|---|---|
| commit (CG:1999-2103) | startOn+quietStart per link (CG:2048). Belt: prior landing (CG:2049-2054) | plan window (sec. 2) | margin >= need (CG:2066). Window gate if a hazard touches i/i±1 or a saw is on the link (CG:2067). Mid-hazard decks re-planned at dx -0.5/+0.4 (CG:2068-2073). Pad-arrival lookahead, vacuous (CG:2075-2081). EM conflict (CG:2086). Threat budget (CG:2091-2097) |
| `heldBefore` pre-check (CG:2034-2041) | startOn+quietStart, **never the belt landing** | window | A link that did not hold before is skipped permanently (CG:2047) |
| chainClean (AP:594-613): DROPS rows with enemies (CG:2325-2328), Enforcer (CG:777), Avalanche (CG:867-868) | provenStart(from), optional `wait` (NGH avalanche {0.7,1.6,2.9} s, CG:826) | **all hazards of `c`** (`act=null, hz=null`, AP:596); plans inside still windowed | Plan, execute, pull up. Any setback fails. **No waiting, repositioning or look-ahead.** A pad launch flies with no input (AP:601). BEAM is rejected (AP:602) |
| pairOk (CG:1285-1301): fork and key-room links | startOn, **no quietStart**, 9 spots across the deck (CG:1287). Skull parked at the far end (CG:1295-1299) | setWindowNear | margin >= min(minLinkMargin, 0.05) |
| routeLinkStillOk (CG:2281-2287): re-proof after forks / pad forks / key rooms | startOn, **no quietStart, no belt landing** | window | **ok only, no margin**. Window kept only for gap saws |
| widenForGem (CG:1112) | startOn+quietStart | window | margin 0.12 |
| tryDecoy (CG:1667-1686) | startOn | window | Route links a-7..a+5, margin 0.05 if near. **placeDecoy/tryDecoy are never called** (only definition sites CG:1542, 1548) |
| linkExists (AP:829-832, tests) | startOn | window | ok |
| continuous (CG:106-111) | `Autopilot.run(out)`: element 0 of the extracted slice, standing, t=0 | all hazards **of the extracted slice only** | Whole-slice witness; called only from chunkIn (CG:92). **Finite `generate()` (CG:68-76) does not run it** |

### Re-proving and order dependence

- commit re-proves links from `from = min(n0-1, n0-2, h.anchor-1-roamReach)` (CG:2017-2023). Earlier links, including other-revolution links a new hazard can reach, are covered only by geometric rules: hazardOk (CG:1901-1928), checkLaunchCorridors and GeometryIntegrity.
- Re-proved links keep their recorded need (CG:2064), but `heldBefore=false` links are exempt for ever (CG:2047). Belt links fall in that set because heldBefore uses the standing start (CG:2038 vs 2049).
- chainClean results (Enforcer, Avalanche, DROPS) are **never re-run after later modules** are committed. Only the per-link commit re-proofs and the final continuous witness touch those links again.
- tryAvalanche attaches the hazard after its decks were committed. Commit is not re-run with it present (CG:857-879). Rollback re-plans it (CG:1207-1211).
- Forks and key rooms come after the route is final (CG:291, 1325-1367). Key rooms come last, against the final geometry (CG:1365).
  - Each new fork re-proves route links a-3..t+2 (CG:1412, 1443, 1523). A key room re-proves rIdx-7..rIdx+5 (CG:1275).
  - **No later structure re-proves earlier fork branches' pair links.** Sub-forks (CG:1352-1361) and key rooms can invalidate them.
  - Re-proofs drop the margin requirement (routeLinkStillOk).
- Launch corridors: hazardOk checks the pads that exist at placement (CG:1907-1911). checkLaunchCorridors (CG:1873-1887) audits every pad of this slice and the previous one against hazards of both, but only **other-revolution** hazards (CG:1894). It is a geometric box (CG:1857-1866, PAD_FLIGHT_MAX 12 m).
- decoyOk keeps branches out of route pad columns (10 m arc, apex+2.8 m) and clear of `contReserve` (CG:1635-1647).

### Structures

- **Forks** (reserved CG:1380-1418; pad CG:1425-1451; open CG:1467-1532): pair proofs for the drop, each hop, and the pad bounce to the rejoin, plus route re-proofs. Layer rule `forkLayersClash` (CG:1779-1786).
- **Dead ends**: none built (dead code above). Returnability was only formula-sized (CG:1594) in any case.
- **Key rooms**: finite and legacy (CG:1243-1282) pair proofs out and back plus route re-proofs. The endless route key `keyOnRoute` (CG:1169-1175) has **no generator proof**; only the continuous detour exercises it, when it lies in this slice.
- **Castles and gems**: exact castle stairs are proven by commit (CG:651). The gem is a flag on an already-proven deck (CG:1092), or a widened deck re-proven (CG:1102-1118).
- **Slice boundary**:
  - The working course holds the previous slice's last 8 route elements and their hazards (anchor >= from-2) as live elements (CG:237-239). Older geometry and hazards become ghosts, used only in layer, hazard and corridor checks and never simulated (CG:242-244, 1614, 1926).
  - The closing rest must admit two plain probe steps clear of other layers (CG:305-319, 339-367). This is geometric; no Sim proof.
  - `extract` keeps elements from ctx-1 and hazards anchored >= ctx-1 (CG:417-435). **continuous therefore never simulates the previous slice's hazards or geometry**, and the next slice's run restarts at t=0 standing on the shared rest.

## 5. Proof starts vs real arrival

| Aspect | Proof | Real arrival | Addressed by |
|---|---|---|---|
| Time / phase | t=0 (+ quietStart, up to 9 s) or waits of 0..6.9 s. Each slice's witness at t=0 | Global clock (Sim.java:490; elements are functions of t: Element.java:141, 310, 313), arbitrary at arrival | Run: waits, look-ahead, stand-still. NGH avalanche 3 extra waits. **One witness phase per slice** |
| Velocity | 0 (pad vx=0) | Landing vx/vy, run-up into a pad | commit pad-lookahead (vacuous, AP:564); launchClear in the run |
| Position | Deck centre / back pocket / rope bottom / cable -s end | Wherever landed or caught | Mid-hazard dx ±0.5/0.4 (CG:2068); pairOk 9 spots; belt landing + ±0.3 (CG:2049-2059); run settle/standAt |
| Mode | Standing GROUND | LEDGE then PULLUP (not proven), caught mid-rope, AIR on a pad | Run pull-up loop; chainClean pull-up |
| Crumble timer | Reset 0 (Sim.java:347) | Already running | crumbleClear (run only) |
| Creature state | Fresh, park-at-far-end for pair only | Crab chasing or followed, skull mid-cycle | Reflex; repositionPlan; continuous |
| Stick hold | Just released | Whatever was held | restartHold (AP:916, 963) |

launchClear, crumbleClear and followBranch exist **only in the run** (and therefore in continuous). They do not strengthen per-link proofs. continuous turns "every link holds from a standing t~0 start" into "one sequenced witness exists from this slice's t=0 start". It does not show that a person arriving at another time, speed or spot, or after a respawn, can continue.

## 6. Completeness gaps and non-Sim approximations

- Search is a fixed lattice (sec. 1).
  - There are no rope or cable waits and no mid-flight decisions beyond steer modes.
  - Waits are capped at 6.9 s and gated by movingNear (anchor-local).
  - Only the first mid hazard gets a hop. There is no deliberate ledge targeting.
  - A link that only a non-lattice input solves is refused. That is conservative for proofs, but it limits what the solver can witness.
- Proof windows ignore distant hazards (AIMED, MORTAR, BEE, other revolution) and ghost elements. Only clearOfEverything in the run sees the whole slice, and nothing sees the previous slice dynamically.
- Not using Sim (filters only; none is re-checked by Sim except where the continuous witness happens to cross it):
  - `reach()` gap sizing (CG:198-204; EM:222-226).
  - EncounterModel `reaches`, `threat`, `conflict` and the bee envelope (EM:49-91, 114-206): protectedRowsOk CG:1974-1987, sawsClearOfCreatures CG:1951-1960, threat budget CG:2091, key and key-room quiet choice CG:1151, 1234.
  - Layer rules: 4 m / 9.5 m, body boxes (CG:1747-1786).
  - hazardOk boxes (CG:1901-1928) and corridorBlocked (CG:1857-1866).
  - GeometryIntegrity I1-I4 (GI:28-49).
  - decoyOk pad column (CG:1643-1647).
  - continuationSteps probe (CG:351-367), quiet() (CG:2158-2162) and staysOn (AP:328).
- The final Sim witness is `continuous` (CG:106-111). It covers this slice's route, any key detour that starts on a route deck the run lands on, and branches only when carried onto them. It covers one time origin. It is not run for finite towers.
