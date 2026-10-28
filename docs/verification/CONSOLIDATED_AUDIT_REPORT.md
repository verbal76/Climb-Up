# Consolidated engineering audit: player capability, physics, solver and procedural generation

Date: 2026-10-27. Source audited: `build-74-determinism` at c9aa5ae (sim code unchanged since; the only later commits are docs).
Status: **discovery and coverage mapping complete; no correction in this report has been applied.** The owner reviews it before the broad
correction phase is authorised. Build 82 remains on hold. No full-course campaign was run for this report.

## How this was produced

1. Three investigations read the source: A player movement/physics/collision, B solver and generator proofs, C environment objects, timing and
   serialization (`evidence/investigation_*.md`).
2. Three independent verifiers re-checked every claim against the current source and, where a claim is about behaviour, ran short headless probes
   on the compiled game (`evidence/verified_*.md`, which hold the verdict tables, exact probe output, and the coverage matrices).

| Investigation | Claims checked | Confirmed | Partial | Refuted | Unverifiable | Behavioural probes |
|---|---|---|---|---|---|---|
| A movement | 131 | 121 | 8 | 0 | 2 | 12 |
| B solver / proofs | 82 | 70 | 8 | 2 | 2 | 4 (+2 detail) |
| C environment / saves | 47 | 39 | 7 | 0 (1 sub-claim) | 1 part | 5 |

3. Where the verifiers disagreed I read the code myself. One disagreement: C said every creature contact needs GROUND or AIR; A said the
   Enforcer swat does not. `Sim.java:843` (kick) tests `mode == GROUND || mode == AIR`; `Sim.java:873` (swat) has no mode test. **A is right.**
   I also re-read the pad landing (`Sim.java:1889-1896`, coyote untouched) and `knockOff` (`Sim.java:355-359`, no lockout) and confirm both.

Refuted claims (both were also in `coverage.json`):
- "The whole-slice witness must be hit-free" (MV-33). Only hits during move execution fail it; an accepted PUNISHER seed 2 slice 0 witness had 2 hits.
- "A missed landing fails the move, no retry from below" (MV-41). A miss onto a lower route deck is resynced and resumed.

Coverage maps (in the evidence files): platform entry/exit matrix (verified_A section 4), generator placement-and-proof table and solver coverage
register per MV id (verified_B sections 3-4), Sim field table across copy / save / respawn / unload / resume and the platform x hazard
interaction matrix (verified_C sections 3-4).

## Severity scale

- **CRITICAL**: a generated course can be impossible, or the game can soft-lock or lose a save. *No CRITICAL finding was confirmed.* Every probe
  that tested completability found the course completable; the Sim is deterministic and has no damage model, so a witness that reaches the top
  proves a route exists.
- **HIGH**: a human meets something the proofs never verified on a path they will commonly take, or the verification claims more than it proves.
- **MEDIUM**: correct in normal play but unproven, or the proof is weaker than stated; wasted generation work; rare gameplay inconsistency.
- **LOW**: latent (not reachable with today's content), cosmetic, or tooling-only.

## Summary by defect class

| Class | Name | Severity | Findings | One-line effect |
|---|---|---|---|---|
| DC1 | Acceptance is weaker than it reports (optimistic defaults, masked failures, two verdicts instead of three) | HIGH | 8 | "Proven" slices can contain hits; timeouts are counted as unsolvable |
| DC2 | Proof start state differs from the real arrival state | HIGH | 6 | A link proven at 92% margin can hit the player during the pull-up that follows it |
| DC3 | Retry and resume do not restore a clean, proven state | HIGH | 9 | The second attempt after a fall is not the challenge that was proven |
| DC4 | Transient player state survives forced mode changes | HIGH (one item) / MEDIUM | 10 | A jump tap just after a pad bounce cancels the bounce (6.3 m becomes 0.44 m) |
| DC5 | Generator checks run before their dependency exists, or go stale after later placement | MEDIUM | 11 (1 fixed) | Corridor checks degrade to a column; earlier proofs are not re-run |
| DC6 | The live streamed world differs from the world the proofs used | MEDIUM | 5 | Creatures near slice seams and on fork platforms use decks no proof saw |
| DC7 | Solver metric and window aliasing | MEDIUM | 5 | The timing-window gate is vacuous on rope, cable and pad links |
| DC8 | Completion paths skip validity checks | LOW | 6 | Pull-up onto a vanished or out-of-plane platform; hole edge cases |
| DC9 | Serializer, clone and test-oracle field lists drift from the model | LOW (one MEDIUM) | 9 | Fields silently dropped; the equality checks that should catch it skip the same fields |
| DC10 | Coverage register inaccuracies and never-generated combinations | MEDIUM (verification) | 9 | The register states VERIFIED where evidence supports PARTIAL |

---

## DC1. Acceptance is weaker than it reports

**Underlying cause.** When the solver cannot decide, or a fallback succeeds, the code returns "ok" and continues, and the final report has only
`completed` and `failInfo`. Nothing records how a link was passed or why a run stopped.

| ID | Finding | Evidence | Sev |
|---|---|---|---|
| 1.1 | `plan(lookahead)` never refuses: `if (lookahead && !r.ok && firstOk != null) r.ok = true`. Commit's check that a pad arrival "leaves a launch the next link can use" therefore adds nothing. | AP:564, CG:2118; probe: 120 links, 0 differences | MEDIUM |
| 1.2 | Optimistic exits in the run's look-ahead: undecided 20 s returns true; launch with no plan returns true; `playClear` returns true after 10 s; the move is executed anyway after 25 looks; a LEDGE catch returns true without the pull-up. | AP:624-630, 648, 669, 1009 | MEDIUM |
| 1.3 | The continuous witness tolerates hits outside the move-execution loop (pull-ups, waits, launch drift, recovery) because the fall/hit baseline is reset per move; misses onto a lower route deck are resynced. | AP:1019, 1040-1044, 996, 1012, 987, 903, 1029-1033; probe: accepted PUNISHER s2 slice 0 with hits=2 | HIGH |
| 1.4 | The key detour is consumed before the standing check, and skipping it never fails the run; later slices grant the key. Endless key collectability is not enforced by the witness. | AP:880-885, 848-851 | MEDIUM |
| 1.5 | Three verdicts collapse into two: a timeout gives `completed=false, failedLink=-1, failInfo=''`; `continuous` treats it as a refusal. Nothing can prove unsolvability (the search is incomplete), so "unsolvable" and "gave up" are indistinguishable. | AP:857, 1054; CG:109-111; probe P2 | MEDIUM |
| 1.6 | Every `plan(lookahead)` followed by `plan(plain)` returns the same answer; when no move works the lattice is simulated twice. | AP:605, 919, 926, 963, 998, 1014 | LOW |
| 1.7 | Endless route key (`keyOnRoute`) has no generator proof; only the witness walks to it, and only if it stands on its deck. | CG:1139-1176 | MEDIUM |
| 1.8 | Finite `generate()` runs no integrity audit, corridor audit or witness. | CG:69-77 | LOW (tooling only) |

**Effect.** Gameplay: none directly (completability still holds). Generation: false-negative refusals waste attempts (up to 24, then relaxed mode,
then a throw), which is what the earlier "slice failed every attempt" campaign stops looked like. Verification: claims of "clean witness" and
"pad arrival proven" are not true as stated.

**Surgical correction.**
- Make the report carry a verdict enum `PROVEN_SOLVABLE / UNKNOWN(reason) / FAILED(reason)` plus counters of how each link was passed
  (plain, fallback, optimistic exit, resync). `continuous` refuses only `FAILED`; `UNKNOWN` gets a bounded retry with a larger budget and is
  reported separately.
- Remove the lookahead override at AP:564 (or make it return a distinct "undecided" state), and drop the duplicate plain plans.
- Count setbacks over the whole witness, not per move, and decide explicitly (owner policy below) whether an accepted witness may contain hits.
- Fail the run when a key detour is required but skipped.

**Scope and regression risk.** Autopilot.java and the `continuous` call site. Changing what counts as a refusal changes which slices are
accepted, so the generation fingerprint, bundled replays and determinism fingerprints change, and the rules version must bump. Risk: stricter
acceptance can raise retry counts on PUNISHER and NGH; it should be measured on a few slices before any campaign.

## DC2. Proof start state differs from the real arrival state

**Underlying cause.** Each link is proven from a synthetic start (standing at the deck centre at t=0, or after a quiet start), while a real player
arrives at a different time, position and mode. Most importantly, catching a ledge counts as arrival, so the pull-up is never part of any proof.

| ID | Finding | Evidence | Sev |
|---|---|---|---|
| 2.1 | LEDGE/PULLUP arrival is success; the pull-up is in no commit proof, and `staysOn` returns true for any non-GROUND mode, so ledge arrivals skip the brake check. | AP:308-317, 329; probe: 47 ledge arrivals in 3 PUNISHER slices; link 60 (margin 0.917) is hit by the CANNON at anchor 58 during the pull-up; link 35's post-pull-up state has no next-link plan | HIGH |
| 2.2 | `routeLinkStillOk` (fork and key-room re-proofs) has no quiet start and no margin: a route link may be left with a single working move. | CG:2325-2331 | MEDIUM |
| 2.3 | `pairOk` has no quiet start; non-GROUND starts use the centre only. | CG:1286-1302 | LOW |
| 2.4 | `heldBefore` uses a standing start and never the belt landing, so belt links fall into the permanently exempt set. | CG:2077-2084, 2090 | MEDIUM |
| 2.5 | The commit's mid-hazard dx re-plans need only `ok`, not margin. | CG:2114 | LOW |
| 2.6 | One time phase per slice in the witness; periodic hazards are proven at one arrival time per link. | B section 5 | MEDIUM |

**Effect.** Gameplay: a human can be hit during a pull-up on a link the generator reported as safe (probe 2.1). This matches the owner's
"risky but fair" intent only by luck. Generation: re-proofs can quietly reduce a link's margin to one move.

**Surgical correction.**
- Treat LEDGE arrival as "arrival pending": extend the link trial through the pull-up and a short settle, and fail it on a setback there.
- Give `routeLinkStillOk` the same quiet start and the original margin requirement as the first proof.
- Add the belt landing to `heldBefore`.

**Scope and regression risk.** Autopilot trial loop and CourseGenerator proof helpers. Stricter proofs refuse more links, so expect fewer ledge
links next to periodic hazards (a visible content change) and more retries. Fingerprint, replays and rules version change.

## DC3. Retry and resume do not restore a clean, proven state

**Underlying cause.** Respawn resets only crumbles and holes, slice unload discards creature state, and the checkpoint-only resume keeps four
fields. There is no single definition of "the world state at this checkpoint", so each path restores a different subset.

| ID | Finding | Evidence | Sev |
|---|---|---|---|
| 3.1 | Respawn keeps seesaw tilt, belt direction (`onT`), the shaking-ramp kick counter, creature state, avalanche state, `lastAvlT`, aimed-cannon state, club time and shove cooldown. | Sim:370-379; probe: all survive | HIGH |
| 3.2 | The shaking-ramp kick direction is a hash of a counter that survives respawn: attempt 2 kicks the opposite way from attempt 1 and from the proof. | Sim:1703-1708; probe: +5.40, -5.40, +3.00 vs fresh +5.40 | MEDIUM |
| 3.3 | Avalanche blocks keep falling after a respawn and tumble down the tower toward the checkpoint; only the 0.7 s invulnerability protects the respawned body. | verified_C interaction matrix | MEDIUM |
| 3.4 | Unloading a slice resets every creature and avalanche in it; a spent avalanche re-arms. | Sim:404-421, Tower:259 | MEDIUM |
| 3.5 | A save (autosave, pause, SAVE & EXIT) taken in the frame of a fall finds `respawnPending`, `RunSnapshot.capture` returns null, and the run file is deleted; the next CONTINUE is a checkpoint-only resume. | PlayScreen:307, 332, 1026-1033; RunSnapshot:57; ClimbGame:302, 322 | MEDIUM-HIGH |
| 3.6 | Checkpoint-only resume restarts time at 0 and brings back every Enforcer, clubbed crab, avalanche, key-room key and non-castle gate. | PlayScreen:139-144, ResumeState:23-28 | MEDIUM |
| 3.7 | `Sim.copy` drops `deferRespawn`, `respawnPending`, `floorOverride`: a copy of a managed sim respawns at a fall where the real game freezes (identical for 1615 steps, diverged at the first fall). | Sim:245-259; probe | LOW (no proof affected today) |
| 3.8 | `rebase` can set `checkpoint = -1`; a lower gem can then become the checkpoint. Needs a checkpoint more than 300 m below the player. | Sim:425 | LOW (latent) |
| 3.9 | `RunSnapshot` omits `sideIn/sideDir` (side catch in progress) and `onSlab` (restored as AIR above the slab). | RunSnapshot:25-63 | LOW |

**Effect.** Gameplay: the spec's core loop is "checkpoints and quick retry", and the anti-slop criteria reject "a restart that leaves stale
state". A retry can be harder (a tilted seesaw, a crab mid-chase, blocks still falling) or easier (a spent avalanche or a departed Enforcer)
than the proven first attempt. 3.5 can silently downgrade an ordinary pause into a lossy resume.

**Surgical correction.**
- Define one `CheckpointState` restore used by respawn, rebuild and resume: for every element and hazard within the respawn rebuild range, reset
  per-element dynamic state (tilt, onT, kick counter, creatures, avalanche, aimed cannon) to the generated initial state, keeping only
  owner-intended persistent state (opened gates, collected keys, `featDone`).
- Clear in-flight avalanche blocks on respawn.
- In the save path, when `respawnPending` is set, complete the respawn (or save the pending checkpoint) instead of deleting the run file.
- Copy the three missing fields in `Sim.copy`; persist `sideIn/sideDir`.

**Scope and regression risk.** Sim respawn, Tower rebuild, RunSnapshot/PlayScreen save path. Run-file format may need a version bump with a
migration (spec section 11). Determinism of a run with a fall changes, so recorded replays that contain a fall must be re-recorded.
Owner decision needed on what should persist (below).

## DC4. Transient player state survives forced mode changes

**Underlying cause.** Coyote time, the jump buffer and the grab lockout are cleared on some transitions and not others, and coyote only counts
down in AIR. There is no single rule for what a forced transition (bounce, shove, knock-off, catch) resets.

| ID | Finding | Evidence | Sev |
|---|---|---|---|
| 4.1 | A pad or spring bounce does not clear coyote. Walking off onto an adjacent lower pad, then tapping jump within the coyote window, replaces the 21 m/s bounce with an ordinary jump that is also jump-cut: apex 0.44 m instead of 6.31 m. | Sim:1889-1896, 1727-1729; probe P8 | HIGH |
| 4.2 | Stale coyote gives a mid-air jump: beam to rope keeps 0.12 s through any time on the rope, and a release at the bottom allows a full jump; walking off then grabbing freezes coyote and the drop restores it; crab and bee shoves leave coyote (6 m/s shove becomes an 11.5 m/s jump). | Sim:1727-1728 (only decrement); probes P1, P2 | MEDIUM |
| 4.3 | The jump buffer survives rope, cable and ledge grabs, a pad bounce, a side catch and a mantle: a press made just before a rope catch leaps off the next frame; just before a ledge, an instant pull-up. | Sim:1889-1896, 1877-1879, 1949-1951; probe P3 | MEDIUM |
| 4.4 | `knockOff` sets no grab lockout: a hazard next to a rope knocks the player into AIR, they re-grab the same rope the next frame, and are hit again whenever invulnerability ends (7 hits in 5 s, never knocked off). | Sim:355-359; probe P12 | MEDIUM |
| 4.5 | The knock-off direction uses `hitBy.sAt(time)` (uncarried home, current-cycle ball, turret, trigger), not the position that actually hit: a player between those points is thrown toward or through the hazard. | Sim:356 vs 1616-1635; probe P7 | MEDIUM |
| 4.6 | The Enforcer swat contact has no mode test, unlike every other creature contact: a player on a rope, cable, ledge, beam or in a pull-up can be swatted off. | Sim:873 vs 843 (read directly) | LOW-MEDIUM |
| 4.7 | Cable (`vy < 4`) and ledge (`vy <= 3.5`) grab limits are tested after this step's gravity, so the real entering limits are about 4.57 and 4.07 m/s. | Sim:1723, 1925, 1933; probe P4 | LOW |
| 4.8 | Ropes have no vertical-speed limit on the grab and share one first-by-index loop with cables. | Sim:1913-1931; probe P4 | LOW |
| 4.9 | The beam mount discards any jump pressed during its 0.62 s (no other mode does). | Sim:1990-1995 | LOW |
| 4.10 | Creatures other than the Enforcer swat cannot shove the player on a rope, cable, ledge, pull-up or beam; lethal hazards hit in every mode. A rope is a refuge from crabs, bees, skulls and blobs. | Sim:602, 620, 745, 1315, 843 | LOW (design question) |

**Effect.** Gameplay: 4.1 makes a mandatory pad link fail for a player who taps jump at the natural moment, on a link the proof judged safe.
4.2 and 4.3 give unexplained extra jumps or leaps the solver does not model (proofs never use them, so nothing becomes impossible). 4.4 means
"knocked off" does not happen on ropes. Solver: none of these are deliberate moves, so they cannot make a proven course unsolvable, but they
make the human experience differ from the proof.

**Surgical correction.**
- One `onForcedTransition(kind)` helper: pad/spring bounce, shove, knock-off, swat and side-catch clear coyote; a grab clears the jump buffer
  (or keeps it deliberately, owner choice); knock-off sets the same grab lockout as a shove (0.3 s).
- Use the live hit pose for the knock-off direction.
- Gate the swat by mode like the kick (or make every creature contact mode-free; owner choice).
- Compare grab limits on the pre-gravity velocity, or document the post-gravity values in tuning.

**Scope and regression risk.** Sim.java only, a few lines each, but this is player physics: every generated course and every replay can change,
so rules version, generation fingerprint and all bundled replays change. Some items are game-feel decisions (below). Low risk of new
unsolvability because the generator never relies on these behaviours; verify with the whole-slice witness on a handful of slices.

## DC5. Generator checks run before their dependency exists, or go stale after later placement

**Underlying cause.** Each placement path calls its own subset of checks at the moment it adds something. A check that needs information
registered later (a fork's landing) sees nothing, and nothing re-runs earlier proofs when later placement changes their surroundings. Only the
slice audits run after everything is registered, and they cover other-revolution conflicts only.

| ID | Finding | Evidence | Sev |
|---|---|---|---|
| 5.1 | Fork pads were checked before the fork was registered, so their launch corridor was a column only. **Fixed in c9aa5ae** (`forkCorridorsClear`): NORMAL seed 2 slices 0-40 now build with no failed attempt (3 corridor-only failures before), PUNISHER seed 2 slices 0-40 with no corridor refusal. | CG:1468-1481 | fixed |
| 5.2 | Key-room pads: the landing is never resolved (the pad is the last element), so only the column is checked, before or after registration. Finite builds only. | CG:1265, 1867 | LOW |
| 5.3 | Previous-slice ("ghost") pads take the next list element as their landing: wrong for the last ghost route pad and for ghost fork pads. | CG:1909, 1953 | LOW |
| 5.4 | Re-proof range after a new hazard is `anchor-1-roamReach`, shorter than AIMED (15 m any direction) and BEE (7 m) reach: earlier links within reach, committed before the hazard existed, are never re-proven. | CG:2064, 2073 | MEDIUM |
| 5.5 | `chainClean` encounters (Enforcer, avalanche, DROPS with enemies) are proven once and never re-run when later modules add hazards that reach back into them. | CG:778, 868-869, 2371 | MEDIUM |
| 5.6 | Earlier fork branches and their pads are not protected or re-proven by later forks; the probe found 2 corridor intrusions (re-proofs still passed). | CG:1665-1667; probe | MEDIUM |
| 5.7 | The avalanche trigger-deck rule ("nothing else anchored on it") is checked at selection, not at attach; links in its +-8 live window committed earlier are not re-proven with it. | CG:861 vs 864 | LOW |
| 5.8 | Enforcer, avalanche and KEY are appended without `geometryOk`, `protectedRowsOk`, `sawsClearOfCreatures`, encounter-model conflict or threat budget. | CG:777, 866 | LOW |
| 5.9 | `widenForGem` widens an already-committed deck and re-proves only two links, with no `geometryOk`. | CG:1103-1119 | LOW |
| 5.10 | Context copy gives hazards anchored at `from-2` and `from-1` negative anchors (their decks are absent), and drops hazards anchored on the previous slice's side elements. | CG:237-249 | LOW |
| 5.11 | The slice corridor audit catches same-structure cases only after a whole attempt is built, so a structural clash throws away the whole slice instead of one candidate. (This produced the earlier campaign stops.) | CG:1876-1894 | MEDIUM |

**Effect.** Gameplay: a hazard can sit in a launch path or reach a link whose proof predates it. No impossible course was found, because the
witness replays the final slice, but the witness is route-only, one time phase, and (DC1) tolerant. Generation: wasted attempts and
slice-level failures.

**Surgical correction (one mechanism, not per-path patches).**
- Split placement into *register* then *validate*: every structure (module, fork, key room, Enforcer, avalanche, key, gem widening) registers
  its elements, landings and hazards first, then calls one shared `validateAround(changedRange)` that (a) checks every pad's corridor against
  its true landing from a single landing map (route next, fork rejoin, key-room return), (b) re-proves every link within the reach of every new
  hazard (reach taken from the hazard type, not `roamReach`), (c) re-runs any `chainClean` encounter whose window a new hazard enters, and
  (d) applies the standard placement checks to every hazard regardless of who added it.
- Keep the slice audits as the safety net.

**Scope and regression risk.** CourseGenerator.java, a medium refactor of how placement paths call checks. Generated content changes (some
current placements will be refused). Generation time may rise because of wider re-proof ranges; measure on a few slices. Fingerprint, replays,
rules version change.

## DC6. The live streamed world differs from the world the proofs used

**Underlying cause.** Proofs run on one slice with `routeCount` set; the live Tower stitches slices into one list with `routeCount = -1` and no
fork list. Code that finds decks by index arithmetic (`anchor + k`) or by `routeSize()` behaves differently in the two.

| ID | Finding | Evidence | Sev |
|---|---|---|---|
| 6.1 | In the live world `routeSize()` counts every element, so creature roaming, avalanche decks and `canHop` accept fork-branch platforms as decks; the proofs never do. | Tower:346, Sim:665-668, 1002-1005, 1455-1456 | MEDIUM |
| 6.2 | Near a slice seam, `anchor +- k` resolves to the other slice's decoys: a crab's deck -2 became a decoy PAD 46 m lower (harmless there, but not the proven deck). 6 creatures and 1 avalanche in 124 generated slices had reach crossing a seam. | probes VcSeam, VcGen | MEDIUM |
| 6.3 | Forks are not stitched into the world (and SliceCodec does not store them), so the live autopilot (demo / test hook) has no branch information. Generation is unaffected (14 regenerations from a decoded slice were identical). | SliceCodec:17-49, Tower:362-379 | LOW |
| 6.4 | `roamDeck` and `avlDeck` accept PAD, SPRING and GOAL decks (a crab can stand on a pad without bouncing); `avlTop` treats a seesaw as flat. | Sim:665-670, 1002-1008 | LOW |
| 6.5 | `movingNear` and `maxPeriod` index raw `a..a+2`, which can be decoys in the live world. | AP:293, 300 | LOW |

**Effect.** Gameplay: creatures and avalanches near seams or forks act on decks the proof never saw; observed cases were harmless. Solver: the
live autopilot is not the proof solver.

**Surgical correction.** Give the Tower world an explicit route-membership table (per element: route index or branch id, with stitched forks)
and replace index arithmetic in creature/avalanche deck lookup with "next/previous route deck" queries that work identically on a slice and on
the world. Exclude PAD/SPRING/GOAL from roam/avalanche decks; use the seesaw's live surface in `avlTop`.

**Scope and regression risk.** Sim deck lookups, Tower stitching, SliceCodec format (forks). Creature behaviour near seams and forks changes, so
replays crossing those points change. SliceCodec format change needs a version and migration of stored history.

## DC7. Solver metric and window aliasing

| ID | Finding | Evidence | Sev |
|---|---|---|---|
| 7.1 | The timing-window gate is computed only for GROUND candidate groups; rope, cable and pad links keep window = 1, so the gate is vacuous there. | AP:563, 464-471 | MEDIUM |
| 7.2 | Candidate group ids collide (MidHop 18-21 and HoleHop 16 with oi=2; WalkOff 24/25/27 with oi=3); MidHop merges both steer variants. The window can be overstated. | AP:482, 490, 495, 508 | MEDIUM |
| 7.3 | The pair-proof window `anchor-2..anchor+6` excludes fork rejoins at +7..+9, which `tryFork` still tries; they always fail (wasted work, effective reach 6). | AP:361; CG:1488; probe | LOW |
| 7.4 | AIMED cannons, mortars, bees and other-revolution hazards outside the plan window are never in per-link trials. | Sim:122-143, Course:65-81 | MEDIUM |
| 7.5 | `MidHop` hops each live crab but only the first non-crab hazard on a deck. | AP:176-194 | LOW |

**Effect.** The "timing window" fairness measure overstates how forgiving some links are; distant aimed hazards are proven only by the witness.

**Surgical correction.** Unique group ids from a single enum; compute the window for every candidate family (rope/cable wait offsets, pad
arrival offsets); widen the pair window to the fork reach (or cap `forkReach` at 6); include hazards by their reach, not by anchor index.

**Scope and regression risk.** Autopilot.java. Margins and windows change, so link acceptance changes: fingerprint and replays change.

## DC8. Completion paths skip validity checks

| ID | Finding | Evidence | Sev |
|---|---|---|---|
| 8.1 | Pull-up completes onto a crumbled deck, then drops at once. | Sim:2037-2055 | LOW |
| 8.2 | Pull-up completes onto a depth mover that has left the plane, and the player then stands on it out of plane (a hang on the same mover is dropped). | probe P11 | LOW-MEDIUM |
| 8.3 | A jump pressed on the frame a crumble vanishes is a full ground jump with platform velocity. | Sim:1677 before 1695 | LOW |
| 8.4 | Step-across can land on an open hole of a holed bridge (dropped next step). | probe P5 | LOW |
| 8.5 | Holes stay open while the player stands on the bridge, past their 3.4 s. | Sim:528 | LOW |
| 8.6 | Holed bridges start their hole timer only from `stepGround`; a buffered jump on landing never triggers them. | Sim:1702 | LOW |

**Effect.** Small leniencies and a visible depth pop (8.2); no soft-lock; proofs and play agree.
**Correction.** Re-check the target's validity at pull-up completion and at the ground-jump test; add the hole test to step-across.
**Scope and risk.** Sim.java, small; player physics, so replays and fingerprint change.

## DC9. Serializer, clone and test-oracle field lists drift from the model

| ID | Finding | Evidence | Sev |
|---|---|---|---|
| 9.1 | SliceCodec does not store forks or compare the frame in `same()`. | SliceCodec:17-49, 67-73 | LOW |
| 9.2 | CourseIO (tests and tools only) drops `gemCheckpoints`, `prog` and the yBase/sBase frame. | CourseIO:11-22; probe | LOW |
| 9.3 | The equality oracles used by DeterminismTest, EndlessTest and LaneReportTest (`CourseIO.toJson`, `SliceCodec.same`) cannot see the dropped fields; EndlessTest's restore passes by a fresh-progression coincidence early in the climb. | probe | MEDIUM (verification) |
| 9.4 | SliceCodec narrows `color` and `skin` to bytes: a holed bridge of 9+ sections with bit 7 set also opens its take-off section after a reload; castle 128+ wraps. Not reachable today (bridges are at most 6 sections). | SliceCodec:52-62; probe | LOW (latent) |
| 9.5 | `Sim.copy` drops `deferRespawn`, `respawnPending`, `floorOverride` (see 3.7). | | LOW |
| 9.6 | `RunSnapshot` omits `sideIn/sideDir` and `onSlab` (see 3.9). | | LOW |
| 9.7 | `pristine()` is effectively never true (crab fields default to -1), so run files are larger than needed. | Sim:198, RunSnapshot:107-110 | LOW |
| 9.8 | `lethalAt(CANNON)` disagrees with the real cannon hit rule (every trailing ball is lethal for its whole flight). Anything that uses `lethalAt` would under-report cannon danger. | Element:297 vs Sim:1615-1625 | LOW |
| 9.9 | `isPlatform()` is true for feature and creature types (KEY, GATE, CRAB...); harmless while those live only in `hazards`. | Element:62-64, 87 | LOW (latent) |

**Correction.** One field manifest per model class with a reflection test that fails when a field is not classified (copied / saved / reset /
intentionally transient), and equality oracles that compare every serialized field. Widen the codec fields to shorts with a format version.
**Scope and risk.** Tests and codecs; SliceCodec format version bump with migration; no gameplay change.

## DC10. Coverage register inaccuracies and never-generated combinations

| ID | Finding | Evidence |
|---|---|---|
| 10.1 | MV-22 (springs), MV-26 (crumbling), MV-43 (forks) are marked solver VERIFIED; the evidence supports PARTIAL. | verified_B section 4 |
| 10.2 | MV-33 and MV-41 notes are refuted (see the top of this report). | probe P4 |
| 10.3 | Seven transitions the Sim performs are missing from `coverage.json` (pad bounce overridden by a coyote jump; buffered jump on landing; pull-up onto an out-of-plane mover; Enforcer swat off a rope/ledge/beam; knock-off then rope re-grab; jump on the vanish frame; step-across onto a hole). | verified_A section 3 |
| 10.4 | MV-06, MV-07, MV-18, MV-27, MV-37 have no simulation test. | verified_A section 5 |
| 10.5 | Generated content never anchors a ride-the-anchor hazard to a moving deck and never places a blob on a seesaw (0 in 176 slices), although both code paths exist; they are covered only by unit tests, if at all. | verified_C N7 |
| 10.6 | The rope as a refuge from creatures, cannon lethality outside `lethalAt`, and `EV_FALL_NEAR` (declared, never raised) are undocumented. | verified_A, verified_C |
| 10.7 | `placeDecoy`/`tryDecoy` and `WindowFollower` are dead code. | grep |
| 10.8 | The coverage gate checks that cited tests exist, not that a status is backed by a behavioural test. | VerificationCoverageGateTest |
| 10.9 | Group of untested co-occurrences marked `?` in the interaction matrix (crab on a pad, avalanche over a seesaw, swing catapult into a hazard zone, blob over a hole). | verified_C section 4 |

**Correction.** Correct the register from this evidence, add the missing transitions, require each VERIFIED entry to cite a behavioural test,
and add tests for the never-generated paths (or remove the dead paths).
**Scope and risk.** Docs and tests only.

---

## Recommended correction order (for the owner to approve, change or reject)

1. **DC10 + DC9 oracles** (docs and tests only, no behaviour change): make the register and the gate honest first, so later corrections are
   measured against true statuses.
2. **DC1** (verdicts and acceptance accounting): lets every later change be judged by PROVEN / UNKNOWN / FAILED instead of a single boolean.
3. **DC5** (register-then-validate in the generator), then **DC2** (pull-up inside the proof, re-proof quality): the generation-side soundness.
4. **DC3** (one checkpoint restore) and **DC4** (forced-transition rule): the player-facing fairness of retry and of pads, ropes and shoves.
5. **DC6, DC7, DC8**: streaming consistency, solver metrics, completion checks.

Each step ends with focused tests and a few-slice generation probe; one rules-version bump, fingerprint refresh and replay re-record at the end
of the whole phase rather than per step. Only after that: Campaign A, then NGH Round 2, as the directive orders.

## Decisions only the owner can make

1. **Retry persistence (DC3).** Should a retry after a fall reset creatures, avalanches, seesaw tilt and the shaking ramp to their starting
   state (recommended: yes; opened gates and keys stay)?
2. **Pad + jump tap (DC4.1).** Should tapping jump right after landing on a pad keep the full bounce (recommended: yes, the bounce wins)?
3. **Extra mid-air jumps (DC4.2/4.3).** Remove the stale-coyote and buffered-grab leaps (recommended), or keep them as hidden leniency?
4. **Ropes and creatures (DC4.6/4.10).** Should a rope protect you from creatures (then gate the Enforcer swat too), or should creatures reach
   you on ropes (then the generator must prove it)?
5. **Witness cleanliness (DC1.3).** Must an accepted slice's proof run be completely hit-free, or is "reaches the top with hits allowed outside
   the move being proven" acceptable?

Build 82 remains on hold. No correction above has been made; the only corrections already applied are the slice-audit scoping (4478d7d) and the
fork-pad corridor re-check (c9aa5ae), which the owner asked to keep.
