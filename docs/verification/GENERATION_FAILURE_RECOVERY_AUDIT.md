# Generation failure recovery - forensic audit (read-only)

Owner directive 2026-10-28, "Generation failure recovery forensic audit". Source audited: commit `093b6bb` on `build-74-determinism` (generation frozen
at `e403a97`, rules 15). Nothing in the game, generator, solver or tests was changed for this audit. No workflow was triggered. The smoke-test run
40607998237 (ci.yml on 093b6bb) was still `in_progress` when this audit was written (checked at 01:44 UTC 2026-10-28); it was not touched.

Line numbers refer to `093b6bb`. Paths are under `core/src/main/java/com/hotatticgames/climbup/` unless written in full.

---

## Owner summary

**What already works.**
- Every slice is a pure function of (seed, slice index, previous slice, tuning, code). It is retried up to 24 times, each time with different but
  fixed random choices, and only a slice that passes geometry integrity, launch corridors and a proven, hit-free whole-slice climb is accepted.
- A rejected attempt never reaches the world: each attempt is a fresh generator object, and only the accepted result is returned.
- Inside an attempt the generator already undoes refused modules, backtracks up to six modules, and rebuilds a slice ending that would box in the
  next slice. These are the "small" recoveries, and they work.
- Accepted slices are stored losslessly, so a saved climb reloads bit-for-bit. A history record that was cut short is ignored.

**What is missing.**
1. When all 24 attempts fail, nothing recovers. The game catches the error, **ends the climb, deletes its history and returns to the title**
   (`PlayScreen.keepTower`, lines 478-485). The run, checkpoint, keys, castles and timer are lost; only a Legacy line remains. This is the one
   finding that can ruin a run at 4,500 m. It is release-blocking.
2. The retries cannot fix a problem the previous slice caused. All 24 attempts start from the same previous slice. Earlier campaigns showed
   failures that repeat for exactly that reason (PUNISHER seed 2 slice 49: 12 of 12 normal attempts refused by the same rule). Nothing can go back
   and change the previous slice, even though the player has not reached it yet.
3. Generation can fall behind the climber, and when it does the game thread waits for it (`Tower.admit`, line 182). On a slow phone that is a
   freeze, and possibly an Android "app not responding" kill. Earlier benchmarks modelled waits of up to 302 s for a fast climber on a 5x slower
   device (DECISIONS.md, GenBench run 40384796148), before rules 15 added the whole-slice witness to every attempt.
4. Three gaps in course identity affect seeded speedruns:
   - A climb saved under one rules version continues with slices from the new rules after an app update. Records stay stamped with the old rules.
   - An OTA tuning file would change every course for the same seed and rules. Tuning is not part of course identity.
   - Personal bests are kept per seed and rules but not per difficulty, so NORMAL and PUNISHER times of the same seed are mixed.
5. A history that cannot be read on resume is silently replaced by a brand-new climb (`ClimbGame.openRun`, line 240).

**How serious.** No exhaustion has been observed on rules 15 yet, because the 30-course batch has not run. Exhaustion has happened on every earlier
rules version that had a campaign, so it is a real failure mode, not a theory. Its consequence (losing the run) is certain once it happens.

**Is the 24-attempt design adequate?** For failures inside the slice itself, yes, and it should stay as is. For failures caused by the previous
slice, no: more attempts of the same kind do not help, because they share the cause.

**Would changing the seed derivation help?** No. The attempts already get distinct, well-mixed random streams (salt `k + 1 + attempt * 1000003`).
A different formula would only reshuffle them, would change every fingerprint and force a rules bump, and would not touch failures the previous
slice causes.

**Do we need lookahead or backtracking?** Yes, one slice of each. Lookahead: a slice is only shown to the player once the slice after it has also
been built. Backtracking: if the next slice cannot be built, the unseen slice before it is rebuilt with its next attempt, deterministically.
Both are part of what defines the course, so every device gets the same course.

**What prevents a failure at 4,500 m from ruining a run (after the recommended changes).**
- The run is never deleted because generation failed.
- The failure is caught about 150 m above the player, while 100+ m of proven course is still ahead of them.
- The deterministic backtrack replaces only the slice the player has not seen.
- If even that fails, the climb is kept and saved intact with the failure recorded, and you decide what the player sees.

**Recommended before Build 82.**
- R1: never delete the run on a generation failure.
- R2: one-slice lookahead and backtrack, so the course stays deterministic.
- R3: no game-thread freeze while waiting for the next slice.
- R4: never silently replace an unreadable saved climb.
- R5: a rules or tuning gate when a saved climb resumes.
- Plus decisions on the player-facing behaviour in the cases listed in section 16.

---

## 1. Executive summary

| Area | Finding | Classification | Risk |
|---|---|---|---|
| Attempt loop (24 attempts, relaxed after 12) | Exists, deterministic, accepts only PROVEN_SOLVABLE plus integrity plus corridors | EXISTS AND VERIFIED | - |
| Rejected attempt isolation | Fresh generator per attempt; only the returned `Course` is used | EXISTS AND VERIFIED (by construction plus determinism tests) | Low |
| Module-level recovery (fallbacks, undo, backtrack <= 6) | Exists | EXISTS AND VERIFIED | - |
| Slice-boundary guarantee (closing rest leaves the next slice room) | Exists (`closeSlice`, continuation probe) | EXISTS BUT INCOMPLETE (a probe of first steps, not a proof that the next slice can be built) | Medium |
| Retry exhaustion during play | Run archived, history deleted, back to title | MISSING recovery; confirmed defect | **High, release-blocking** |
| Retry exhaustion of slice 0 | Exception escapes `PlayScreen.show` (no catch on the fresh-climb path) | MISSING; confirmed by code path, not by an observed failure | High |
| Previous-slice-caused failure | No backtracking across slices | MISSING | High |
| Generation behind the climber | Game thread blocks on the background future | EXISTS BUT INCOMPLETE (correct, but freezes) | High on phones |
| Determinism of the accepted course | Pure function; single-threaded generator; only correctly rounded `Math.sqrt` plus `StrictMath`; codec bit-exact | EXISTS AND VERIFIED (DeterminismTest, GenPrint on Linux, Windows and Android) | - |
| Determinism under rules 15 on all platforms | determinism.yml not run since the rules 15 freeze | EXISTS BUT UNVERIFIED for rules 15 | Medium |
| Resume across a rules change | No gate | EXISTS BUT INCOMPLETE | Medium (real at Build 82 for any climb in progress) |
| OTA tuning in course identity | Not included | DESIGN DECISION REQUIRED | Medium (dormant until a tuning OTA ships) |
| Records per difficulty | Not keyed by difficulty | EXISTS BUT INCOMPLETE | Medium (speedrun integrity) |
| Unreadable history on resume | New climb starts and overwrites it | EXISTS BUT INCOMPLETE | Medium |
| History append | `fsync` on the render thread | EXISTS (correct), performance note | Low |

---

## 2. Current generation architecture (verified against the source)

1. **Seed.** A random climb draws `seed = (nanoTime ^ currentTimeMillis * phi) & 0x7fffffff | 1` (`ClimbGame.drawSeed`, line 256). A typed
   course code is decoded by `SeedCode` (the code does not carry the difficulty). The difficulty selects the `Tuning` copy
   (`ClimbGame.tuningFor`, lines 209-215; `Tuning.hardCopy`).
2. **Slice 0 ahead of time.** On the title screen and the seed screen, `Tower.prepare(seed, tuning)` (Tower lines 74-78) builds slice 0 on the
   `tower-prep` thread. `Tower.firstSlice` (lines 81-88) uses it, or builds it on the calling thread. If the prepared one failed, the error is
   swallowed and the build is repeated on the game thread, where it fails again.
3. **A slice.** `CourseGenerator.chunkFramed(seed, k, prev, tuning)` (lines 143-151) shifts the previous slice into the new frame (a copy) and
   calls `chunkIn` (lines 91-103):
   - `for attempt in 0..23`: a new `CourseGenerator(seed, t, salt = k + 1 + attempt * 1000003)`, whose `Random` is seeded with
     `seed * 0x9E3779B97F4A7C15 + 12345 + salt * 0x632BE59BD9B4E019` (line 70). Attempts 12-23 are `relaxed`: they waive the shortcut spacing to
     the previous slice, never body clearance.
   - Each attempt runs `buildChunk(prev)`, `checkIntegrity()` (GeometryIntegrity), `checkLaunchCorridors()`, `extract()`, then
     `continuous(out)`. `continuous` is the whole-slice `Autopilot.run` with a 4,000 s simulated budget, and it accepts only `PROVEN_SOLVABLE`
     (lines 113-118). Then comes `progFinish`.
   - Any `IllegalStateException` rejects the attempt and is classified by `failureClass` (lines 124-130) as FAILED_VALIDATION,
     PROVEN_UNSOLVABLE, UNKNOWN or GENERATION. The first passing attempt is returned. After 24 failures the last exception is re-thrown
     (line 102).
4. **Inside an attempt.** Module picks fall back to continuations at the castle's own demand, then at demand 0.15 (lines 561-572). If nothing fits,
   up to 2 undos here and 6 per slice are tried (lines 574-576) before "cannot extend course" (line 578). The closing rest is placed so that the
   next slice's first steps fit (`closeSlice`, lines 326-340). It may climb out first, re-place the rest, and undo modules; after 6 tries it
   throws.
5. **Streaming.** `Tower` (whole file, 394 lines) keeps every accepted slice as a compressed blob (`SliceCodec`, lossless, format 3).
   `requestNext()` (lines 159-163) submits slice `k = sliceCount` to the single `tower-gen` thread, built from `top` (the newest accepted
   slice). `poll()` / `take()` (lines 166-176) append a finished slice. `admit()` (lines 179-188) lets a slice join the resident window once its
   bottom is at or below `admitAbs`. If the slice does not exist yet, admit builds it **on the calling (game) thread** (line 182).
6. **Game loop.** `PlayScreen.keepTower()` (lines 478-485) runs before every simulation step. It sets
   `admitAbs = maxAbs + ADMIT_AHEAD (100 m)` and `ensureAbove(maxAbs, BUILD_AHEAD = 150 m)` (lines 467-469), then calls `maintain(sim)`.
   Every frame, `ClimbGame.syncHistory` (lines 275-279) appends new blobs to `history.bin` with `HistoryStore.append`, which does an `fsync`.
7. **Resume.** `ClimbGame.openRun` (lines 222-253) reads `history.bin` (header: magic and seed only) and builds
   `new Tower(seed, runTuning(), hist, cpSlice)`, which only decodes. New slices continue from the decoded top slice.

---

## 3. Failure-path inventory (Part 4)

### A. Module failures
A refused module is rolled back (`rollbackTo(n0, h0)`), and encounters are pruned with it (DC5). The generator tries fallbacks, then undo and
backtrack, and then fails the attempt. A rejected module cannot stay in the result: `extract()` builds the output from the generator's final
lists, and every refusal path rolls back first (DC5 register-then-validate). Contamination between attempts is impossible by construction. Each
attempt is a new `CourseGenerator`, and the shared input `prev` is a shifted copy that the generator only reads. **EXISTS AND VERIFIED** by the
DC5 work and the fingerprint tests.

A difficulty note: continuations fall back to demand 0.15 (line 569) when the castle's own demand fails. That is a deliberate, recorded part of
rules 13 (`fallback_at_demand_failed` tally), not a recovery side effect. Its rate is measurable per castle in `fallbacks.csv`.

### B. Section failures
A slice whose links are each proven but whose whole climb is not proven is refused by `continuous` (lines 113-118). Only `PROVEN_SOLVABLE`
passes. `UNKNOWN`, `PROVEN_UNSOLVABLE` and `FAILED_VALIDATION` all reject the attempt. No path accepts a slice without `continuous`: the only
`return out` is on line 99, after all checks. The DC1 verdict tests cover this (SolverVerdictTest; coverage register "verdicts"). **EXISTS AND
VERIFIED.**

### C. Retry exhaustion (confirmed defect)
- *Exception:* the last attempt's `IllegalStateException`, re-thrown by `chunkIn` (line 102).
- *Thread:* the `tower-gen` thread, stored in the `Future`. It is re-thrown on the game thread by `Tower.take()` (line 173,
  `IllegalStateException("tower generation failed at slice k")`), or by `admit()` / `extend()` (lines 182, 204).
- *What the player sees:* `PlayScreen.keepTower` catches it (lines 479-484). It logs the error, archives the climb as a Legacy line
  (`Legacy.archive` calls `RunRecord.forgetClimb`), **deletes history.bin**, persists, and goes to the title. No freeze and no crash.
- *Progress:* lost. Height, checkpoint, keys, opened castles, run clock and splits are reset. Only the Legacy line (height, towers, clock) and any
  personal bests already recorded remain. The run snapshot is not written.
- *Another candidate:* none. *Existing recovery:* none.

### D. Failure during the first slice
- *Prepared slice failed:* the error is swallowed (Tower line 85) and slice 0 is built again on the game thread inside
  `new Tower(seed, t)`, called from `ClimbGame.openRun` line 247, called from `PlayScreen.show` line 117.
- *Fresh-climb path:* there is no catch here (`openRun` catches only on the resume path, line 240). The exception leaves `show()`, which is a crash
  on desktop and the uncaught-exception handler on Android (AndroidLauncher lines 36-37).
- A failed prepared slice cannot poison later attempts: `firstSlice` clears `prepared` before using it, and a newer `prepare` replaces it.

**MISSING**: confirmed from the code path. No slice 0 has been observed to exhaust its attempts.

### E. Failure during active gameplay
The mechanism is the same at every height. Slice k is requested when the tower top is less than 150 m above the highest point climbed, and admitted
when its bottom is within 100 m above it. So an exhausted slice is normally detected while the player still has the rest of the resident course
(roughly 100-150 m) ahead.

| Height | What happens today |
|---|---|
| 100 m | Slice 1 or 2 is built in the first seconds of play. Exhaustion ends the climb almost immediately. |
| 1,000 / 2,500 / 4,500 m | The same: the run ends and its history is deleted. |
| Just before or after castle 10 | The same. Castle 10 is placed by whichever slice contains 5,000 m (`buildChunk`, lines 274-283); exhaustion there loses a nearly finished run. |
| Beyond 5,000 m (endless) | The same machinery. Curves are capped (DifficultyProfile), and DeterminismTest covers growth beyond castle ten. |

The streamed architecture could recover: the failure is detected ahead of the player, and the unseen slice is not yet admitted. Only the
`keepTower` policy and the missing backtrack prevent it.

### F. Failures caused by the previous slice
The previous slice is fixed for all 24 attempts. Existing protections:
- `closeSlice`'s continuation probe (lines 326-340). It checks that plain first steps fit against the previous slice with the layer rule. It is a
  probe of the shapes the next slice falls back to, not a proof that the next slice can be built in full: castle approaches, claimed Enforcers and
  avalanches, keys and belt saws at exact heights are not part of it.
- Relaxed attempts 12-23, which waive the shortcut spacing.

Evidence that failures repeat because they share a cause (earlier rules):
- PUNISHER seed 2 slice 49: 12 of 12 normal attempts refused at element 8 by the spiral-layer rule against the previous slice (DECISIONS.md, rules
  10 work). This led to the continuation probe.
- NORMAL seed 2 stopped at 881 m (rules 10), the source of the rules 11 "climb out first" fix.
- PUNISHER seed 1 at 1,667 m: an avalanche claim climbed past the red gem on every attempt (fixed by `nextForeignStructure`).
- NORMAL seed 2024 slice 48 failed 24 of 24 during the DC2 correction (a belt exit proven from a standing start), fixed before the freeze.

A different random stream (a new seed derivation) cannot help when the cause is the previous slice's geometry. Changing the previous slice can.
**Bounded one-slice backtracking is required** (section 10).

### G. Save and resume
- A failed attempt never writes anything. Only accepted slices are appended, after `take()`.
- `HistoryStore` appends `[length][blob]` with `fsync`. A truncated last record is ignored on read (lines 60-66). There is no per-record
  checksum: a record that decodes to garbage, or not at all, makes `SliceCodec.decode` throw "corrupt slice".
- **Resume of an unreadable history** (`openRun`, line 240): the exception is swallowed, `save.seed = 0`, and a **new climb** starts.
  `history.reset` then overwrites the old file. The old climb is silently lost and not even archived. EXISTS BUT INCOMPLETE.
- **The exhaustion path deletes everything** (section C).
- Keys, opened castles and the timer are in SaveData and RunSnapshot, which a generation failure does not corrupt; the exhaustion path resets them
  deliberately. Seed and difficulty metadata live in SaveData (`seed`, `difficulty`, `climbRules`). The generation rules are stamped
  (`Legacy.stampNewClimb`, line 31) but **not checked on resume** (`openRun` lines 224-239). Splits live in SaveData and the records.

### H. Performance
- Generation runs on one low-priority daemon thread. A slice costs the generator's proofs plus, since rules 14, the whole-slice witness, on every
  attempt. Measured: NORMAL a mean 8.5 s per slice and worst 42.7 s, PUNISHER 12.6-15.2 s and 69.9 s on a CI runner before rules 14-15
  (DECISIONS.md, GenBench run 40384796148). NGH about 10.6 s per slice locally (correction-phase probe). On-device speed has never been measured;
  the 3x and 5x phone factors in the streaming model are assumptions.
- **Synchronisation points on the game thread:**
  1. `Tower.admit` builds a missing slice synchronously when the climber's admit height reaches it (line 182).
  2. `Tower.firstSlice` when the prepared slice is not ready (line 85 or 87).
  3. `PlayScreen.show` `tower.extend()` for the hidden world picker and test hooks (lines 120, 127).
  4. `HistoryStore.append` with `fsync` (line 49), via `syncHistory` every frame a slice was added.
- A slice that needs many attempts multiplies its cost: about 24 times the slice cost when exhausted, which ranges from minutes on a desktop to
  tens of minutes on a slow phone. That is a guaranteed freeze if the climber reaches it first. The determinism design is right to make the window
  wait for the slice (`admitAbs`, Build 74); the problem is that it waits by blocking the render thread.
- Memory: about 1-2 KB per stored slice, a window of about 600 m, and cached expansions within 2 slices (Tower line 351). A one-slice lookahead
  buffer costs one more expanded slice.

---

## 4. Existing retry behaviour (Part 4, summarised)
24 attempts per slice. Attempts 0-11 are normal; 12-23 relax only the shortcut spacing to the previous slice. Each attempt is a distinct
deterministic random stream and the first passing attempt is accepted. Refusals are tallied by class (`attempt_fail_<class>`) and per slice
(`GenTrace` `sliceFailures`, `attemptNanos`; LaneReport `chunks.csv`: `slice_attempts`, `failed_attempts`, `final_attempt_s`,
`failed_attempts_s`, `relaxed`).

## 5. When all 24 retries are exhausted
See 3C and 3D. During play the climb is archived and its history deleted (confirmed). For slice 0 the exception escapes `PlayScreen.show`.

## 6. Risks from prior-section geometry
See 3F. Correlated causes are documented on rules 10-14 and on the pre-freeze rules 15 build. The continuation probe narrows the problem but does
not close it. The structures that bind the next slice to exact heights (castle approach, key, belt saw, claimed Enforcers and avalanches) are where
a previous slice is most likely to leave a slice that cannot be built.

---

## 7. Determinism and seed preservation (Part 5)

**How the accepted slice is chosen.** It is the first attempt `a` in 0..23 that passes every check, where each attempt is a pure function of
`(seed, k, a, prev slice, Tuning contents, code)`. The difficulty enters through the tuning copy, not the salt. The public seed is never
changed: `Course.seed` stays the player's seed, and attempts only vary the salt.

| # | Condition | Same world? | Evidence |
|---|---|---|---|
| 1-3 | Same seed, difficulty, rules and tuning | Yes | DeterminismTest `theSameSeedGrowsTheSameTowerTwiceIncludingBeyondCastleTen`, fingerprint `theGenerationRulesAreFingerprinted` (rules 15 fingerprint pinned at the freeze) |
| 4 | Different hardware | Yes in design; verified on rules <= 14 | Only correctly rounded `Math.sqrt` and `StrictMath` trig in sim (checked: no `Math.sin/cos/atan2/pow` in `sim/*`); `java.util.Random` is specified; release 17 is strict FP (JEP 306). determinism.yml (GenPrint for 8 seeds, plus replays) on Linux, Windows and Android has **not run on rules 15** |
| 5 | Generated at different times | Yes | One generator thread; the result does not depend on scheduling; the window admits by height (`admitAbs`), not by completion time (Build 74) |
| 6 | Save and resume | Yes for stored slices (bit-exact codec); new slices continue from the decoded top slice | DeterminismTest `aTowerResumedFromStoredSlicesContinuesExactlyAsTheFreshOne`, ResumeTest, BackgroundSaveTest, WorldConsistencyTest (codec keeps every field) |
| 7 | Several slices ahead | Yes | Same pure function; `Tower.extend` loop in tests |
| 8 | A slice needing retries | Yes: retries are part of the function | Seeds with failed attempts are in the fingerprint set (for example NORMAL s2024) |
| 9 | Emergency recovery | Not applicable today (none exists) | - |
| 10 | Beyond castle 10 | Yes | DeterminismTest (beyond castle ten) |

**Shared mutable state.** `CourseGenerator.lastForDebug`, `wetTried/wetOk`, `Autopilot.why`, `Autopilot.lastReal` and the static counters are
written by the generator threads. None of them feeds a decision: `why` reaches only a refusal message, whose class is decided by its fixed prefix.
`tower-prep` and `tower-gen` can run at the same time; this is harmless today, but these statics are not thread-safe diagnostics. The
`MotionCache` and `GenTrace` are thread-confined. The HashMap and HashSet in Autopilot (lines 446 and 919) are used for lookups and membership, not
iterated into decisions.

**Gaps in course identity (real, not hypothetical):**
- **Rules change mid-climb.** A climb saved on rules N, resumed on a build with rules M, keeps slices built with N and grows new slices with M.
  Records go to `climbRules = N` (`PlayScreen.runRules`, line 531). Any player who updates to Build 82 with a climb in progress gets such a mixed
  course.
- **OTA tuning.** `ClimbGame` lines 52-58 let an applied OTA payload replace the bundled tuning, which every slice reads. The rules version and
  the fingerprint cover only the bundled tuning, so two players on the same seed and rules but different tuning get different worlds.
- **Difficulty in records.** `RecordsData.seedBests(seed, rules)` (line 83) has no difficulty. NORMAL and PUNISHER runs of one seed are
  different courses but share personal bests. The seed code does not carry the difficulty either: the course identity a player shares is
  (code, difficulty), and the game does not show or record it that way.

---

## 8. Section commitment and persistence (Part 7)

| Event | What it does today | Can the slice still be replaced safely? |
|---|---|---|
| Generation and validation succeed (`chunkIn` returns) | Result held in the `Future` | Yes |
| `take()` / `append()` | Becomes `top` (the next slice is built from it) and gets a blob | Not without discarding the next slice too; today nothing does |
| Written to history (`syncHistory`, same or next frame) | `fsync`ed to disk | Only by truncating the tail record (append-only file; possible, but not implemented) |
| Admitted (`botAbs[k] <= maxAbs + 100 m`) | Joins the resident world and becomes visible | **No**: the player can see it |
| Player reaches it | - | No |

Today a slice is effectively permanent the moment it is taken, long before the player can see it: it is written to disk and the next slice is
built on it. The gap between "taken" and "admitted" (about 50-150 m, at least one slice) is exactly the room a recovery needs.

**Buffer options:**

| Option | Recovery reach | Cost | Determinism |
|---|---|---|---|
| 0 (today) | None across slices | - | Yes |
| **1: slice k is admitted only once slice k+1 exists; k+1 failing backtracks k** | Fixes previous-slice causes one slice deep | One more slice built ahead (about 60-80 m more lookahead, one expanded slice of memory); in the common case no extra work | Yes, if "final" is defined by the rule, not by timing (section 10) |
| 2 | Two slices deep | Double the lookahead and a longer first wait on slow devices | Yes |
| Whole course ahead | Complete | Minutes to tens of minutes at start | Yes, but an unacceptable start |

Recommendation: **buffer 1**, with the depth kept a named constant so it can be raised later if the measured data ever needs it.

---

## 9. Background generation and performance
See 3H. The decisive question is whether generation keeps ahead of the climber on the slowest supported device. That has never been measured.
Blocking `admit()` is correct for determinism and wrong for the player: it should wait without freezing (section 10, R3).

---

## 10. Recovery architecture alternatives (Part 6)

**Level 1 - normal generation.** Exists, unchanged.

**Level 2 - deterministic alternatives (the 24 attempts).** Exists, unchanged. Changing the seed derivation is not recommended (owner summary).

**Level 3 - beyond 24 attempts, same previous slice.** Options: more attempts; a different module order; alternative "starting templates" for
the first decks. Assessment: these help only failures inside the slice, which the 24 attempts already cover well, and none helps the
previous-slice cause. A starting template (for example a plain climb-out stair before the slice's first module) is in effect what `closeSlice`
already does from the other side. **Not recommended as a separate level** unless the measured data shows exhaustion inside the slice itself.

**Level 4 - previous-slice recovery (recommended, one slice deep).**
- Rule (part of what defines the course): slice k-1 is final when slice k can be built from it within 24 attempts. If not, slice k-1 takes its
  next passing attempt (continuing its own attempt sequence after the accepted one) and slice k is tried again. This repeats up to B times
  (proposed B = 8), then Level 5.
- Every device runs the same search in the same order, so every device ends with the same course, whatever its speed. In the common case (slice k
  builds) the course is **bit-identical to today's**, so every existing fingerprint, replay and saved climb stays valid. Only seeds that today end
  the run get a different (now completed) course.
- Requirements:
  1. Admission: slice k-1 joins the world only once slice k exists. This replaces "build it now" in `admit()` with "wait for it" (R3).
  2. Persistence: history stores final slices only. Either the newest slice is written once its successor exists, or it is written provisionally
     and cut off on backtrack. The first is simpler and keeps the file append-only.
  3. Resume: a resumed tower knows only final slices. It regenerates the provisional tail deterministically, which gives the same result.
  4. Diagnostics: count each backtrack and each recovered slice.
- What can never change: an admitted slice, anything on disk that was admitted, the player's position, the seed. Backtracking applies only to the
  single unseen, provisional slice.
- Endless play beyond 5,000 m and castle 10 follow the same rule; there is no special case.

**Level 5 - unrecoverable.** If Level 4 also fails (B backtracks without a buildable slice k):
- Never accept unverified geometry and never change the seed.
- Keep the run: no archive and no deletion. Save the climb exactly (snapshot plus history of final slices).
- Write a failure record: seed, difficulty, rules, tuning hash, slice, attempt classes and the last messages, to a local diagnostics file.
- Let the player keep climbing the proven course that is already admitted (at least about 100 m). What happens when they reach its end is an
  **owner decision** (section 16).

---

## 11. Recommended minimum implementation (Part 11)

### REQUIRED BEFORE BUILD 82

| # | Change | Where | Changes generation output? | Rules bump |
|---|---|---|---|---|
| R1 | A generation failure never ends, archives or deletes the run. The tower enters a "cannot grow" state; the run stays saved and the failure record is written | `Tower.take/admit/extend`, `PlayScreen.keepTower` | No | No |
| R2 | One-slice lookahead with bounded deterministic backtracking (Level 4), plus final-only history and provisional-tail regeneration on resume | `Tower` (admission, history hand-over), `CourseGenerator.chunkIn` (start attempt index), `ClimbGame.syncHistory` / `openRun` | Only for courses that today end the run | Recommended (owner decision; see 16-D) |
| R3 | No game-thread blocking: when the next admitted slice is not ready, the simulation holds (no steps, run clock stopped, inputs ignored) and shows a small unobtrusive "building" indicator; it resumes on the same step as on any other device | `Tower.admit`, `PlayScreen` loop | No | No |
| R4 | An unreadable saved climb is never silently replaced: keep the files (renamed backup), archive the record, and tell the player once | `ClimbGame.openRun`, `HistoryStore` | No | No |
| R5 | Rules and tuning gate on resume: the history header (or SaveData) carries rules version, difficulty and tuning hash; a climb from other rules or tuning is handled by the owner's decision (16-B) | `HistoryStore` header, `ClimbGame.openRun` | No | No |
| R6 | Slice 0 exhaustion: a typed course code is refused with a clear message (the seed is never altered); a random climb draws a new random seed (the player chose none). Prepared slice failures are kept, not swallowed and re-run on the game thread | `Tower.prepare/firstSlice`, `ClimbGame.openRun`, `SeedScreen` | No | No |

### RECOMMENDED
- Personal bests keyed by (seed, rules, difficulty) (`RecordsData`), with a migration that leaves today's single-difficulty records as they are.
- The seed display and share line show the difficulty with the code.
- Per-record CRC32 in `history.bin` (format bump of the history file only, not of generation); corrupt records then fail cleanly.
- `syncHistory` through `appendLater` (it already exists), so the render thread never `fsync`s.
- Make the diagnostic statics (`Autopilot.why`, `lastReal`, `CourseGenerator.lastForDebug`) thread-local, or keep them out of the generator
  threads.
- Run determinism.yml once on the frozen rules 15 commit after the 30-course batch, as a manual dispatch with owner approval (it is validation
  only).

### OPTIONAL FUTURE WORK
- Buffer depth 2 if the measured backtrack data ever shows double failures.
- A second generator thread (one slice ahead each) for slow devices, deterministic because the results are pure functions.
- Measured on-device generation speed (Android, Steam Deck) as a standing performance check.

**Complexity.**
- R1, R4, R5 and R6 are small (game-side policy and file format).
- R2 is the only structural change: admission and history hand-over in Tower, plus a start-attempt parameter in `chunkIn`. It is contained in
  `Tower` and does not touch the generator's internals.
- R3 is small to medium (a hold state in the play loop).

**Fingerprints.**
- R1, R3, R4, R5 and R6 change no generated slice.
- R2 changes only courses that today end the run. The pinned rules 15 fingerprint seeds do not exhaust, so their fingerprint stays identical. Saved
  climbs stay valid.

---

## 12. Failure statistics and measurement plan (Part 8)

What the existing tooling already records per slice:
- `chunks.csv`: `slice_attempts`, `failed_attempts`, `final_attempt_s`, `failed_attempts_s`, `relaxed`, plus generation wall time.
- `GenTrace` tallies: `attempt_fail_<class>`, `continuity_<verdict>_<cause>`.
- `generation_failures.csv` / `solver_failures.csv`.
- The new batch summary.

So metrics 1-4, 8 and 9 come **from the authorised 30-course batch at no extra cost**.

| # | Metric | Source | Unit |
|---|---|---|---|
| 1 | Candidate rejection rate | `failed_attempts / slice_attempts` | per attempt, by mode and castle |
| 2 | Slices recovered by retry | slices with `failed_attempts > 0` and generation complete | per slice |
| 3 | Retry distribution | `slice_attempts` mean, p50, p90, p99, max by mode and castle | per slice |
| 4 | Exhaustion rate | generation INCOMPLETE with the failed slice | per slice and per course |
| 5 | Emergency (Level 4) recovery rate | new counter after R2 | per slice |
| 6 | **Player-facing failure rate** | Level 5 events (after R2); today equal to metric 4 | per course |
| 7 | False acceptance | an independent re-verification of accepted slices: the LaneReport solver (the same verdict machinery), plus a separate, larger-budget `Autopilot.run`, plus a full-course streaming climb (the bundled replay style) on a sample | per slice; release-blocking if any |
| 8 | Inconclusive rejection | `continuity_UNKNOWN_<cause>` tallies (budget, detour, ...) | per attempt; never counted as impossible |
| 9 | Latency | `gen_wall_s`, `final_attempt_s`, `failed_attempts_s` | per slice: mean, p99, max |
| 10 | Difficulty integrity | lane totals and Enforcer and avalanche counts of slices with `failed_attempts > 0` vs `= 0`, and of relaxed vs normal attempts; `fallback_at_demand_failed` | per castle |

**Statistical caution.**
- Attempts share the previous slice and the slice's exact-height structures, so they are not independent (section 3F). No product of per-attempt
  rates may be quoted as a per-slice probability.
- Report per attempt, per slice and per course separately.
- With zero failures in N trials, the 95 % upper bound is about 3/N (the rule of three):

| Courses with no failure | Upper bound per course | Approximate upper bound per slice (about 70 slices per course) |
|---|---|---|
| 30 courses | <= 10 % | about 1.4e-3 |
| 300 courses per mode | <= 1 % per mode | about 1.4e-4 |
| 900 courses | <= 0.33 % | about 5e-5 |

**Recommended sizing (owner decision):**
- Read the 30-course batch first.
- If it shows exhaustion, R2 is mandatory, and the cost of the large run is better spent after R2.
- A 900-course measurement can be **generation-only**: `solve = 0`, because the whole-slice witness already runs inside generation. That roughly
  halves the cost, but it is still on the order of 150-200 runner-hours at the measured slice costs. It needs explicit approval and is not part
  of this audit.

---

## 13. Targeted verification plan (Part 9)

**Missing test infrastructure (to build with R1-R6):**
1. A test-only fault hook in `chunkIn`: a package-private `FaultPlan` predicate `(seed, k, attempt) -> force refusal of class X`. It is inert unless
   set by a test and draws no random numbers.
2. A generator seam in `Tower`: a package-private function, so tests can inject a slow or throwing generator.
3. A small history-file fixture writer for corrupt records.

| # | Injection | Expected | Pass when | Reuse |
|---|---|---|---|---|
| 1 | Attempt 0 of slice k refused | Attempt 1 accepted | Same slice bytes as an unforced run whose attempt 0 fails naturally; world has no attempt-0 element | DeterminismTest, SliceCodec.same |
| 2 | Attempts 0-4 refused | Attempt 5 accepted | as 1 | - |
| 3 | Attempts 0-22 refused | Attempt 23 (relaxed) accepted | as 1; `relaxed` recorded | - |
| 4 | All 24 refused, k-1 unadmitted | Level 4: k-1 next attempt, then k | Run continues; two instances give equal bytes; no admitted slice changed | new |
| 5 | Forced UNKNOWN witness | Attempt refused, class UNKNOWN | Never accepted; tally `continuity_UNKNOWN_*` | SolverVerdictTest |
| 6 | Forced integrity violation | Refused, FAILED_VALIDATION | as 5 | IntegrityFrameTest |
| 7 | Incompatible boundary (k cannot start from k-1) | Level 4 recovery | as 4 | SliceBoundaryTest fixtures |
| 8 | Worker throws a RuntimeException (not ISE) | Treated as a failed attempt or Level 5, never a crash or deleted run | Run saved; failure record written | new |
| 9 | Generator slower than the climber | Simulation holds, run clock stopped, no game-thread wait over one frame | Same step count and events as a fast-generator run (replay hash) | StreamingReplayTest, PlayDeterminismTest |
| 10 | Kill during history write | Truncated record ignored; tail regenerated | Resume equals the uninterrupted run | BackgroundSaveTest |
| 11 | Resume after a rejected attempt | Same course | Bytes equal | ResumeTest |
| 12 | Two independent instances | Equal bytes | `SliceCodec.same` for every slice | DeterminismTest |
| 13 | Compare accepted bytes after recovery | Equal across instances and resume | as 12 | new |
| 14 | Recovery around 4,900-5,000 m | Castle 10 placed exactly once at its height | Castle count 10; key reachable | new (start with `climb.startHeight`-style tower growth) |
| 15 | Recovery beyond castle 10 | as 4 | as 4 | DeterminismTest beyond castle ten |
| 16 | A rejected candidate never resident | No element key from a refused attempt | element keys only from accepted slices | WorldConsistencyTest |
| 17 | No rejected candidate in history | history records == final slices | count and bytes | BackgroundSaveTest |
| 18 | No progress lost on Level 5 | SaveData, snapshot and history unchanged except the failure record | fields equal | RunSnapshotTest, CheckpointContractTest |
| 19 | Cross-platform identity | GenPrint (with a forced-retry seed) equal on Linux, Windows and Android | determinism.yml output equal | determinism.yml |
| 20 | Difficulty preserved | Recovered slice built with the same Tuning (mode) and demand path | `Tuning.hard` and profile equal; lane totals within the castle's normal band | ModeProfileTest |

All of these are focused unit tests or short tower growths (tens of slices at most). None needs a campaign.

---

## 14. Exact source references

| File | Function | Lines | Behaviour |
|---|---|---|---|
| sim/CourseGenerator.java | constructor | 69-72 | Random seeded from seed and salt |
| sim/CourseGenerator.java | `chunkIn` | 91-103 | 24 attempts, relaxed after 12, accept first passing, re-throw last |
| sim/CourseGenerator.java | `continuous` | 113-118 | Only PROVEN_SOLVABLE accepted |
| sim/CourseGenerator.java | `failureClass` | 124-130 | Refusal classes |
| sim/CourseGenerator.java | `chunkFramed` / `shift` | 143-162 | Previous slice copied into the new frame |
| sim/CourseGenerator.java | `buildChunk` | 249-316 | Context, ghosts, exact-height structures, extend, close, decoys, rooms |
| sim/CourseGenerator.java | `closeSlice` | 326-340 | Boundary probe and bounded rebuild |
| sim/CourseGenerator.java | `extend` fallbacks / backtrack | 561-578 | Module-level recovery |
| sim/Tower.java | `prepare` / `firstSlice` | 74-88 | Slice 0 ahead; failure swallowed, then re-run on the caller |
| sim/Tower.java | `requestNext` / `poll` / `take` | 159-176 | Background slice; exception re-thrown on the game thread |
| sim/Tower.java | `admit` | 179-188 | Admission by height; builds on the game thread if missing |
| sim/Tower.java | `extend` | 202-206 | Blocking growth |
| sim/SliceCodec.java | `encode` / `decode` | 21-55 | Lossless format 3 |
| HistoryStore.java | `append` / `appendNow` / `read` | 35-70 | `fsync`; truncated tail ignored; no checksum |
| ClimbGame.java | `openRun` | 222-253 | Resume without a rules gate; unreadable history leads to a new climb |
| ClimbGame.java | `syncHistory` | 275-279 | Appends every taken slice |
| PlayScreen.java | `show` | 117-127 | Run opened; blocking extends for test hooks |
| PlayScreen.java | `keepTower` | 467-485 | BUILD 150 m / ADMIT 100 m; **failure archives the climb and deletes history** |
| Legacy.java | `archive` | 35-43 | `RunRecord.forgetClimb` |
| RecordsData.java | `seedBests` | 83 | Keyed by seed and rules, not difficulty |

---

## 15. Release-blocking findings

1. **RB-1 (confirmed):** generation exhaustion during play deletes the climb (`PlayScreen.keepTower`, lines 479-484). Fix: R1.
2. **RB-2 (confirmed code path):** slice 0 exhaustion on a fresh climb escapes `PlayScreen.show`. Fix: R6.
3. **RB-3 (design gap, failures evidenced on earlier rules):** no recovery from previous-slice-caused exhaustion. Fix: R2.
4. **RB-4 (risk, magnitude unmeasured on device):** game-thread blocking when generation falls behind. Fix: R3, plus one on-device measurement.

Not release-blocking but required for seeded speedrunning integrity: R4, R5, records per difficulty.

## 16. Owner decisions required

- **A. Level 5 player experience.** When even the backtrack fails, the climber keeps the proven course already built. At its end:
  - (a) the climb is saved and paused with a short explanation and the option to keep the save for a future update;
  - (b) the climb ends as an archived run;
  - (c) another choice.
  
  The seed never changes.
- **B. Resume across a rules or tuning change.** For a climb from older rules or tuning:
  - (a) it can be continued, but it is marked "not comparable" and records are not set;
  - (b) it is finished as an archived run;
  - (c) it keeps playing only as far as its stored slices.
- **C. Waiting indicator (R3).** A small "building the tower" indicator while the simulation holds, not a loading screen. Is that acceptable?
- **D. Rules version for R2.** R2 leaves every course that builds today unchanged. Bump to rules 16 to mark that the climbs which used to end are
  now completed, or keep rules 15?
- **E. OTA tuning.** Should OTA tuning be allowed to change generation at all? If yes, the tuning hash becomes part of the course identity and the
  records.
- **F. Measurement size.** The 30-course batch first. Then, if wanted, a generation-only 300-per-mode run as a separate, explicitly approved
  campaign.
- **G. Records per difficulty and the share line showing the difficulty.**

## 17. Implementation sequence after approval

1. Test infrastructure: the fault hook and the Tower generator seam (test-only, inert).
2. R1 and R6 (no generation change), with tests 8 and 18.
3. R3, the hold state, with test 9.
4. R2: lookahead, backtrack and final-only history, with tests 1-4, 7, 10-17 and 20. Rules decision D.
5. R4 and R5 (history header, gate), with tests 10 and 11 and the owner decisions B and E.
6. Recommended items.
7. One CI run of the affected tests, then determinism.yml once (manual dispatch, with approval).

Each step is a bounded repair cycle under the three-pass rule of the repair mission. None needs a campaign. The authorised 30-course batch supplies
the first rules 15 statistics (section 12) and is not delayed by any of this.
