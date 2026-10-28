# Open owner decisions (2026-10-28 overnight)

Everything authorised was carried on. These points need the owner, and nothing else waits on them.

## 1. CLOSED 2026-10-28: StreamingReplayTest landing-boundary difference. Owner approved option B.

**Owner decision (2026-10-28): option B approved.** The scope is limited to the floating-point landing-boundary discrepancy at step 21389. A
surface-contact difference is allowed only when all of the following hold:
- it lies inside an already-counted timing divergence episode;
- there are at most two episodes;
- the climbs reconverge within 0.1 m;
- checkpoints, keys, hazard consequences (hits, falls) and completion are equivalent;
- no further divergence is concealed.

No other assertion was relaxed, no physics was changed, and the test was not disabled. Implemented in StreamingReplayTest as
`outsideEpisodes` / `Outcome`; see DECISIONS.md.

### Original analysis (kept)

**What happens.** The seed-12 climb's inputs are recorded on one tower and replayed into a streaming tower, which rebuilds around a new floating
origin every 400 steps.
- The same tower replays bit for bit.
- The streaming tower first differs at step 400, by one rounding, and the drift stays below 1.8 mm.
- At step 21389 the whole-world climber is 1.22e-4 m above deck top 301.9033813: just outside the 0.1 mm landing tolerance. The streamed climber,
  re-rounded by the new origin, is inside it.
- One frame touches a surface for 2 steps that the other does not. The climbs rejoin: final gap 1.5e-5 m, one episode.
- The test requires the same surfaces in the same order, so it fails.

It is reproduced on the frozen source (093b6bb) and on every repair branch. None of the repairs changes it.

**Why no engineering fix was made overnight.** With 32-bit floats, any landing threshold has a boundary that two frames can fall on either side of.
Only identical arithmetic in both frames removes the case.

| Option | What it changes | Cost |
|---|---|---|
| A. Double-precision position state in Sim (s, y, velocities, element positions) | Frame drift drops from ~1e-3 m to ~1e-12 m, so a knife edge becomes practically impossible. Every replay, every proof and the generation fingerprint change. | Large Sim refactor: needs rules 16+, re-recorded replays, and the cross-platform determinism workflow run again. |
| B. Test contract: a surface-list difference is allowed only inside an already-counted timing episode (at most 2), when the climbs rejoin (final gap < 0.1 m) | The game is unchanged. The game always replays in the streamed frame, where it is bit-exact. | It changes an assertion, which the owner forbade without approval. |
| C. Keep it as the documented Build 72 limitation, with the test marked as known-failing | Nothing. | CI stays red, so the campaign cannot start under the current gate rule. |

**Recommendation:** B. It is the documented limitation that the test's own header describes. A is the principled long-term cure, scheduled after
Build 82.

## 2. Default policies used in Track 3 (R1-R6), from the audit's recommendations, for confirmation

- **Level 5, the tower cannot grow:**
  - the climb holds;
  - the message "THE TOWER COULD NOT BE BUILT FURTHER. YOUR CLIMB IS SAVED." is shown;
  - nothing is archived or deleted;
  - `generation_failures.txt` records the case.
- **Slow generation:** "BUILDING THE TOWER..." is shown after 0.3 s while the simulation holds. The clock does not run.
- **A climb resumed under different rules or tuning:** it plays on, but sets no records, and the player is told.
- **The first slice of a course cannot be built:**
  - a typed course code is refused with "COURSE <code> CANNOT BE BUILT ON THESE RULES";
  - a random climb draws another seed, up to 5 tries.

## 3. Generation changes in this repair round (no new policy; existing rules enforced)

The saw-fairness rule (owner decision 2026-10-27) is now enforced at slice acceptance. The generator measures every route saw's window exactly as
SawFairnessTest does, from the real arrival. A slice is accepted only with at least half of each saw's cycle open.
- This changes generation: the seed 1 fingerprint goes from a7a4dff4... to 978eaa51....
- The rules version is bumped and the bundled seed-12 replay re-recorded on the integrated source.
