# Build 83 master directive, Stage 1 findings (S1-A, S1-B, S1-C)

Branch `fix/build-83-root-causes`. Every run here is generation only, on seeds 7321-7323 in every mode, to 5,000 m. The instrumentation is observe-only (the generation fingerprint test passes, and the 9 baseline courses match the frozen Build 83 campaign slice for slice).

| Run | What it changes | GitHub run |
|---|---|---|
| Baseline + T4/S1-C probes | nothing (rules 18) | 40917945177 (`eb36efc`) |
| Spacing off | rules 16 layer spacing (9.5 m if either platform launches) | 40918396027 (`4bf9fb4`) |
| Re-draw off | rules 16 spiked block where an Enforcer bars a bee or crab deck | 40918401525 (`4bf9fb4`) |
| Pendulums off | no pendulums (the draw is kept) | 40918406620 (`4bf9fb4`) |

## S1-B: rules 17 isolation (same seeds, per course)

| Mode | Run | Terrain | Hazards | Enemies | Interaction | Total | Ramp castle 1 to 10 | Castle step sd | Avalanches | Links with a mechanic |
|---|---|---|---|---|---|---|---|---|---|---|
| NORMAL | baseline | 5,949 | 1,307 | 531 | 280 | 8,017 | +34.2% | 44.2 | 0.3 | 51.3% |
| NORMAL | spacing off | 5,352 | 1,392 | 560 | 287 | 7,548 | +39.9% | 18.0 | 0.7 | 37.4% |
| NORMAL | re-draw off | identical to baseline (the re-draw is PUNISHER/NGH only) | | | | | | | | |
| NORMAL | pendulums off | 6,017 | 1,335 | 567 | 288 | 8,154 | +39.1% | 33.2 | 0.7 | 51.0% |
| PUNISHER | baseline | 6,153 | 1,544 | 865 | 349 | 8,860 | +13.5% | 29.3 | 11.0 | 38.7% |
| PUNISHER | spacing off | 5,870 | 1,698 | 893 | 350 | 8,768 | +34.4% | 31.1 | 12.4 | 20.4% |
| PUNISHER | re-draw off | 6,220 | 1,556 | 866 | 356 | 8,941 | +16.0% | 35.2 | 12.4 | 38.6% |
| PUNISHER | pendulums off | 6,183 | 1,560 | 853 | 347 | 8,896 | +23.2% | 31.5 | 9.5 | 38.9% |
| NGH | baseline | 6,547 | 1,885 | 1,096 | 383 | 9,875 | +9.6% | 13.5 | 10.4 | 32.1% |
| NGH | spacing off | 6,562 | 2,029 | 1,302 | 394 | 10,260 | +14.2% | 53.7 | 18.3 | 12.5% |
| NGH | re-draw off * | 6,532 | 1,834 | 1,060 | 382 | 9,774 | -2.6% | 51.8 | 12.7 | 32.1% |
| NGH | pendulums off | 6,592 | 1,849 | 1,092 | 385 | 9,878 | +7.3% | 37.8 | 13.9 | 33.0% |

\* NGH seed 7321 with the re-draw off stopped at 4,839 m (`cannot extend course (layer=40 haz=104 ...)`), so its NGH castle 10 is two courses and its ramp is not comparable.

Total refusals across the three courses of each mode (N / P / NGH):
- **Layer refusals:**
  - baseline: 87,524 / 140,116 / 188,812;
  - spacing off: 163,361 / 297,936 / 390,082.
- **Deck-in-lethal-box refusals:**
  - baseline: 0 / 7,864 / 21,343;
  - spacing off: 0 / 5,144 / 24,447.

**Readings:**
- **The layer-spacing change dominates. It is the source of both the gain and most of the losses.**
  - The gain is terrain variety: mechanic links 37 → 51%, 20 → 39% and 12.5 → 32%, and NORMAL terrain +11%.
  - It also costs NGH enemy pressure. With the spacing off, the NGH enemy lane is 1,302, exactly rules 16's figure, so the −15% is this change.
  - It also costs avalanches (NGH 18.3 against 10.4 a course) and the PUNISHER ramp (+34% against +13.5%).
  - With the spacing off, NORMAL's castle-to-castle swing (owner observation 1) falls from 44 to 18.
  - The likely mechanism (to be confirmed): more terrain mechanics fill each castle's demand, so the progression controller selects fewer hazards and enemies. Avalanche trigger decks (plain STATIC decks) also become rarer.
- **The pendulums and the Enforcer re-draw have small effects**, within the seed noise of three courses, except one point. With the re-draw off, NGH seed 7321 cannot finish on the rules 18 base. The re-draw does not cause the NGH enemy drop.

## S1-A (T4): spiral-clearance refusals, by how far they miss

**Body refusals** (two revolutions' bodies with standing room meet), N / P / NGH:
- **Only the margins meet** (1.5 m vertical, 2.5 m arc):
  - 31,805 / 52,293 / 77,102, which is 85% of body refusals in every mode;
  - 7,867 / 11,326 / 17,460 of them miss by 0.25 m or less.
- **The bodies themselves overlap:** 5,636 / 8,887 / 13,300.
- **The margins are not derived from physics.** Two platforms clash below 5.1 m apart (0.8 m slab + 2.8 m standing room + 1.5 m margin). A jump reaches 1.95 m and a head clears 3.2 m above the deck.
  - Whether the bodies of other revolutions collide in the game (as a ceiling) has to be checked in T5 before the margin is judged.

**Spacing refusals by rule** (sum N / P / NGH, with the physics for each):

| Rule | Refusals | Required | Physics (tuning.json) | Classification |
|---|---|---|---|---|
| Lower platform is a pad | 10,530 / 22,112 / 28,736 | 9.5 m | 24² / (2·34) = 8.47 m launch + 1.30 m ledge reach = 9.77 m | **Physics-based; 9.5 m is about 0.27 m too loose** (to verify in T5 with the arc offset) |
| Launcher above a platform whose climber can be lifted (skull, spike drop) | 12,781 / 25,207 / 42,801 (MOVE_V 8.5k / 17k / 28.5k, PAD 4.3k / 8.2k / 14.3k) | 9.5 m | The rule's own comment gives 4.57-4.6 m for the lift | **Conservative**: required 9.5 m against about 4.6 m of reach |
| Lower platform is a horizontal or depth mover | 8,438 / 7,582 / 5,187 | 9.5 m | A mover carries along the lane or in depth, not up: 3.25 m (the arc includes its travel) | **Wrong motion assumption** (to confirm in Sim) |
| Lower platform is an elevator (MOVE_V) | 2,234 / 3,182 / 3,091 | 9.5 m | amplitude + 3.25 m | Partly conservative; all of these miss by 2 m or less |
| Spring, seesaw, ramp, swing (lower) | 4,132-6,376 each | 9.5 m | spring and swing super-jump launch; seesaw and ramp kick-offs to measure in T5 | To measure |

The plain 4 m rule never refuses on its own: the body test already holds plain platforms 5.1 m apart wherever their arcs meet.

**Deck-in-lethal-box refusals:** 7,864 (PUNISHER) and 21,342 (NGH) are an avalanche's box. There is one NGH refusal by a spike drop and none by any other hazard.

## S1-C: the avalanche danger box against the real blocks

- **The box** is `hzHalf` = deck width / 2, and `hzLo` / `hzHi` = deck y to deck y + `len`. Here `len` is the block count (8-12): the box height is the block count in metres, an accident of the default case.
- **The real blocks:**
  - They are spawned 11 m (`AVL_DROP`) plus up to 2.5 m above one of the four decks ahead, or above the climber's predicted position up to ±10 m.
  - They fall to 16 m below and spread up to ±30 m along the lane (the 2 m cell grids are in the run 40917945177 log).
  - They collide only with the trigger route's nearby decks (±8), so they pass through every element of another revolution.
- **Refused decks** sit 4-14 m above the trigger deck, within 4 m of its arc (a deck of the revolution above). Blocks do pass through those cells, so these refusals protect the column directly above the trigger deck. **They are not oversized.**
- **Blocks passing through another revolution's element** (once per block, per proof simulation): 765,000 (PUNISHER) and 763,000 (NGH). **Every one of them was outside the box.** The box misses the fall columns over decks 1-4 ahead, where blocks pass through decks, pads, elevators, ramps and ropes of the revolution above (at spawn) and below (on the fall).
- **Conclusion:**
  - The box is the wrong shape: too narrow, not too large. Shrinking it would weaken the I4 protection, so that correction is off the table.
  - It is also not the avalanche frequency lever. Frequency is lost in the proof (T1: no plan while 8-9 blocks are live).
  - Whether a block passing through another revolution's deck matters to play depends on whether the climber can be there during the block's 12 s life. The case that matters is being knocked off onto the revolution below. That goes to T5 before any change is proposed.
