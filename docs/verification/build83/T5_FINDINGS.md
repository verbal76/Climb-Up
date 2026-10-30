# T5: solver and physics validation (Groups 2 and 3, first pass)

**Branch:** `fix/build-83-root-causes`.

**Runs:**
- GitHub run 40936899187 (commit `4da36cd`), 22 jobs, all succeeded.
- Probe: `core/src/test/java/com/hotatticgames/climbup/sim/T5Probe.java`. It is diagnostics only; the game and the generator are unchanged.

## Method

**Reach.** A lower platform of each kind is placed at arc 0. A plain target deck goes one revolution up (raw arc + circumference + offset) at heights in 0.25 m steps.

The independent search is not the solver's move logic. It uses the real 60 Hz `Sim.step`, a direction-only stick held for at least 100 ms, and input families made of:
- a wait (over a mover's period, in 0.4 s steps);
- a run-up (none, or 0.1–0.8 s either way);
- a jump hold (0.1, 0.25 or 0.6 s, or held throughout for launchers);
- air steering (toward, none or away).

Success means standing on the target, or touching it when it is a pad. A trial fails if the climber stands on any other element, so only direct moves count.

The solver (`Autopilot.linkExists`) and the generator's rule (`layersClash`) are evaluated on the same fixture.

**Interpreting the numbers:**
- A success proves the move is possible.
- A maximum is a **measured lower bound**, not a proven physical limit.
- Nothing here is classified as impossible.

**Audit.** In every accepted course (30 slices per standard seed and mode), each pair of platforms on different revolutions is tested on its own with the same search and the solver. The upper platform is 0.5–11 m higher and within 10 m of arc. For a pad or spring the deck it is run onto is included, as are the hazards anchored on the lower platform.

**Parity.** Each route link's planned move is replayed in the full world, with every element and hazard, using the same inputs. It is compared with the windowed proof step by step, and the next link is planned again from the state the move really arrives in.

## Results

### 1. Platforms are one-way

A deck of another revolution 1.4–3.8 m above the climber does not stop a jump: the apex stays 1.85 m. At 1.4–1.8 m the climber passes up through it and lands on top.

**The body-clearance margins in `layersClash` are therefore not about head clearance.** Whether they protect anything else (readability, the solver window) is an open question; it is listed below.

### 2. Measured reach onto the next revolution

Maximum height difference (m), taken over the search and the solver, at each arc offset. The last column is the generator's current rule.

| Lower | 0 | 1.5 | 3 | 4.5 | 6 | 7.5 | 9 | Generator |
|---|---|---|---|---|---|---|---|---|
| STATIC | 3.75 | 3.75 | 3.75 | 3.75 | 3.75 | 2.00 | – | 4 m; body 5.1 m; only within 5.5 m of arc |
| MOVE_H | 3.75 | 3.75 | 3.75 | 3.75 | 3.75 | 3.75 | 2.75 | **9.5 m** (it never lifts the climber) |
| MOVE_Z | 3.75 | 3.75 | 3.75 | 3.75 | 3.75 | 2.00 | – | **9.5 m** |
| SEESAW | 3.75 | 3.50 | 3.75 | 3.75 | 3.25 | 2.25 | – | **9.5 m** |
| RAMP (plain / shaking / spinning) | 4.75 | 4.25 | 4.00 | 4.50 | 4.75 | 3.50 | – | **9.5 m** |
| SWING | 6.50 | 6.75 | 6.75 | 6.50 | 6.50 | 6.50 | 6.75 | 9.5 m |
| MOVE_V, amplitude 3.5 | 7.25 | 7.25 | 7.25 | 7.25 | 7.25 | 5.25 | – | 9.5 m (reach = amplitude + 3.75) |
| MOVE_V, amplitude 5.5 | 9.25 | 9.25 | 9.25 | 9.25 | 9.25 | 7.50 | 3.00 | 9.5 m (0.25 m margin) |
| Falling elevator, amplitude 3 | 7.00 | 6.75 | 7.00 | 7.00 | 6.75 | 5.00 | – | 9.5 m |
| SPRING | 8.50 | 8.50 | 8.50 | 8.50 | 8.50 | 8.50 | 8.50 | 9.5 m, **only within 5 m of arc** |
| **PAD** | 8.25 | 8.25 | 9.50 | **10.25** | **10.00** | **10.00** | 8.00 | **9.5 m, only within 5 m of arc** |
| STATIC with a skull | 3.75 | 3.75 | 3.75 | 3.75 | 3.75 | 3.50 | 2.50 | 9.5 m above a launcher (rules 17) |
| STATIC with a spike drop | 3.75 | 4.50 | 3.75 | 3.75 | 3.75 | 3.00 | – | 9.5 m above a launcher (rules 17) |

Notes on the table:
- **Plain-platform spacing:** the 4 m rule is physically justified, with 0.25 m to spare.
- **Pads and springs:** their reach exceeds the rule.
  - A pad reaches 10.25 m up at 4.5 m along the arc, against a rule of 9.5 m.
  - Pads and springs reach up to about 9 m along the arc. The rule's arc gate (`arcHalf + arcHalf + 2.5`, about 5 m for these sizes) does not check that far at all.
- **Sideways movers, depth movers, seesaws and ramps:** they reach what a plain jump does, give or take 1 m, yet they keep the 9.5 m launcher spacing.
- **Swings and elevators:** they reach well below 9.5 m. An elevator's reach is its amplitude + 3.75 m.
- **Skull fixture:** the skull's launch did not raise the measured reach. That fixture is limited (one skull phase); it is not evidence that skull decks are safe.

### 3. The generator accepts direct shortcuts between revolutions (CONFIRMED DEFECT)

Pairs in accepted courses that a climber can reach directly, skipping at least 5 route platforms (the median skipped is about 11, one revolution). Per course, over the first 30 slices:

| Mode | Shortcuts per course | Beyond the rule's arc gate (> 5 m) | Pads > 9.5 m up within the gate | Lower platform kinds |
|---|---|---|---|---|
| NORMAL | 15.0 | 8.3 | 6.0 | pads 84%, elevators, a swing, a spring, plain decks |
| PUNISHER | 33.0 | 19.0 | 12.0 | pads 80%, elevators 13% |
| NGH | 44.3 | 26.3 | 12.3 | pads 82%, elevators 11% |

- The generator's layer check refused **none** of them.
- Example (NGH seed 7321, slice 2): PAD 30 → STATIC 42, 9.81 m up and 4.2 m along the arc, skipping 12 route platforms.
- **The defect predates rules 17.** The rules 16 spacing has the same arc gate and the same 9.5 m.
- **The solver finds only a third of these moves**, with its few pad policies: 32 of 122 in NGH.
  - The validation recorded in the code ("ShortcutProbe: no plain pair of revolutions reachable") used the solver as its judge, so it could not see them.

### 4. Windowed proofs against the full world

- **The first run's disagreements were a probe error.** The probe had replayed the planned move without the solver's reflex layer (`Autopilot.make`), and its arrival test ignored pads.
- **After the fix (`f7a50ce`):** locally, all 277 planned moves of NORMAL seed 7321, slices 0–3, replay exactly in the windowed simulation. The full world agrees step for step, with no outcome difference.
- **Pending:** the GitHub rerun on every mode.
- **Strict drift** (position differing by less than 0.001 m) is not yet reported separately. It will be, with whether it ever crosses a collision boundary.

### 5. Natural arrival against a clean start

The next link is planned from the state the move really arrives in, then from a clean start on the same deck. These counts come from the first run; the rerun recounts them.

| Mode | Passes clean, fails natural | Passes natural, fails clean |
|---|---|---|
| NORMAL | 1–3 per 8 slices | 0–1 per 8 slices |
| PUNISHER | 4–10 per 8 slices | 0–3 per 8 slices |
| NGH | 2–6 per 8 slices | 0–2 per 8 slices |

- The generator proves each link from a clean start, except where it already rehearses arrival (SafeStart for saws, the whole-slice witness).
- About 1% of PUNISHER links are proven from an easier state than a climber arrives in.
- The whole-slice witness (which does arrive naturally) still passed every slice of the campaigns, so these links are traversable with a recovery or a wait. This is a margin overstatement, not an impossible link.
- To be classified per case in Group 1C.

## Classification

| Item | Classification | Action |
|---|---|---|
| Pad / spring / elevator / plain shortcuts between revolutions accepted | **Confirmed defect** (the rule's arc gate and the pad height are below the measured reach) | Correction C1: the tightening part (never refuses less than today), under the existing authorisation |
| 9.5 m spacing for sideways and depth movers, seesaws, ramps, swings, elevators | **Conservative beyond physics** (reach 3.75–7.25 m) | Proposal for the owner: it is the rules 17 spacing / variety trade-off |
| 9.5 m above a launcher over skull and spike-drop decks | **Conservative** (measured 3.75–4.5 m; the skull fixture is limited) | Owner proposal, after a better skull fixture |
| Body margins (1.5 m vertical, 2.5 m arc) | **Not head clearance** (platforms are one-way); their real purpose is not yet established | Open: test whether removing them changes solver-window parity or readability before any proposal |
| Solver misses about two thirds of real pad shortcuts | **Solver false negatives** (bounded pad policy set) | Group 1D: decide whether it matters for route proofs, or only for shortcut checks |
| Windowed against full world | Parity holds (pending the GitHub rerun) | Rerun queued |
| Clean-start proofs pass where natural arrival fails, about 1% (PUNISHER) | **Contributing factor** (proof optimism) | Group 1C |

## Not yet tested

- Rope and cable catches as cross-revolution targets.
- Enemy knock-backs other than a skull.
- Lower platforms carrying a creature.
- Other cross-turn interactions (Group 3A: falling blocks against moving platforms, the knocked-off climber).
- The NGH seed 7321 failure (Group 6).
- Execution tolerance (Group 7).
