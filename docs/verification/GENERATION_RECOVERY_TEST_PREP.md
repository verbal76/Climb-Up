# Generation recovery - targeted verification preparation

Owner direction 2026-10-28: test preparation only for R1-R6 of `GENERATION_FAILURE_RECOVERY_AUDIT.md`. No production code changed. Work is on the
isolated branch `test/generation-recovery-prep` (from `093b6bb`). The frozen working branch, CI run 40607998237 and the authorised 30-course batch
are untouched. No workflow was triggered.

New file: `core/src/test/java/com/hotatticgames/climbup/GenerationRecoveryTest.java`.

## Results (local, JUnit 4, javac-compiled `093b6bb` sources)

### Existing tests (unchanged), run individually

| Test | Result |
|---|---|
| BackgroundSaveTest | OK (5 tests), 86 s |
| ResumeTest | OK (1 test), 724 s |
| RunSnapshotTest | OK (5 tests), 799 s |
| StreamingReplayTest | **FAIL** (1 test), 716 s. The same tower replays bit-exactly. The streaming tower first differs at step 400 (1.8e-3 m) and grows to 0.114 m, and the climber skips surface 262181 (slice 4, element 37) that the recording stood on. This is on frozen source 093b6bb; it is not caused by this preparation, which changed no production code. See "Smoke-gate relevance" below. |
| TowerStreamingTest | still running when this was written (more than 28 min) |
| DeterminismTest | still running when this was written (more than 28 min) |

### New tests

| Test | Status | Result |
|---|---|---|
| `saveReload_aSavedClimbReloadsWithIdenticalBytesStateCheckpointKeysProgressAndTime` | runnable | PASS |
| `saveReload_anAutosaveWrittenInTheBackgroundReloadsTheSameClimb` | runnable | PASS |
| `characterisation_todayHistoryHoldsSlicesThePlayerWasNeverAdmittedTo` | runnable; describes today's behaviour and is expected to FLIP with R2 | PASS |
| `forcedBacktrack_theRejectedSliceIsNeverAdmittedResidentOrSaved` | PENDING R2 plus fault hook | ignored |
| `saveDuringRecovery_interruptAndResumeWhileKIsProvisional` | PENDING R2 plus fault hook | ignored |
| `allRecoveryFails_theClimbStaysRecoverableAndIsNeverDeleted` | PENDING R1 plus R2 plus fault hook | ignored |
| `historyH1_aTornRecordDoesNotHideEverySliceWrittenAfterIt` | PENDING history repair | **fails on 093b6bb** (verified with the @Ignore removed: expected 4 records, got 3) |
| `historyH2_anUnreadableHistoryIsNeverSilentlyReplaced` | PENDING R4 | **fails on 093b6bb** (verified: seed 4249 was replaced by a new random seed) |

The `GenerationRecoveryTest` run took 109 s: 3 run and 3 pass, 5 are ignored.

**Save/reload, scenario 1.** A fresh climb is driven by the live autopilot for 45 s through the real PlayScreen step order:
- keepTower, with admission 100 m and the build 150 m ahead;
- the history sync;
- the step;
- the run clock.

It is then saved with `saveRun`, the app is "killed", and the climb is reopened with `openRun(false)` and the PlayScreen CONTINUE path. Verified:
- every slice is byte-identical;
- the snapshot of the resumed state matches the original: position, velocities, timers, element and hazard states, done features, checkpoint
  and keys;
- the run clock and ticks, opened castles, towers and splits (from a fixture, because 45 s reaches no castle) all match;
- 600 further steps with the same scripted inputs give the same height (within 1e-6) and the same mode at every step.

The background-autosave variant checks the same after the queued writes land, and that history.bin has exactly one record per taken slice.

**One comparison detail, by design rather than a defect.**
- `RunSnapshot.apply` sets the step-start position to the current one and marks the step as a teleport (line 216).
- The saved `absS0` is used only to sync moving elements, so a fresh capture after resume reports `absS0 = absS`.
- The test therefore leaves `absS0` out of the field comparison. Whether this changes behaviour is checked by the 600-step continuation, which
  matches.

## Pending tests: what each needs before it can run

Pending bodies are written against `GenerationRecoveryTest.Seams`, a test-side adapter whose methods throw `UnsupportedOperationException`
until the production seams exist. They compile today and cannot pass by accident.

| Seam | Needed by | Purpose |
|---|---|---|
| Fault hook in `CourseGenerator.chunkIn` (package-private, test-only, inert, draws no random numbers): refuse every attempt of slice k when slice k-1 is attempt a / always / slowly | 2, 3, 5 | controlled failure of K+1 |
| Tower generator seam (package-private function the tests can wrap) | 2, 3, 5 | towers and runs that use the fault plan |
| `Tower.acceptedAttempt(k)` | 2 | proves K was rebuilt on a later attempt |
| `Tower.finalCount()` | 3, 5 | final vs provisional slices |
| Admission and residency log (test-only) | 2 | the rejected K was never admitted or resident; K was admitted only after K+1 existed |
| Cannot-grow state and failure record (R1) | 5 | Level 5 invariants |
| History repair (truncate to the last whole record before appending) | H1, scenario 3 torn-record case | torn writes |
| Kept-aside unreadable history plus archive (R4) | H2 | no silent replacement |

### Expected behaviour if all recovery fails (test 5)
Owner decision A sets what the player sees. These invariants hold whatever is chosen:
- The tower reports "cannot grow" instead of throwing.
- The climber keeps the proven, admitted course.
- Nothing unverified is ever admitted.
- Nothing is archived or deleted: `save.seed`, history.bin (final slices), run.json and the clock are exactly as before.
- A failure record (seed, difficulty, rules, slice, refusal classes) is written.
- Reopening the game resumes the same climb in the same state.

## Save/history design problems found

1. **H1 (new, confirmed failing). A torn history record corrupts everything after it.** `HistoryStore.appendNow` appends after whatever bytes
   are at the end of the file. A record cut short (disk full or an interrupted write) stays in place:
   - the next append lands behind it, and `read()` then consumes the next record's bytes as the torn record's body;
   - the result is a garbage "slice" (the test read 3 records instead of 4), followed by a `SliceCodec.decode` failure on resume;
   - with the checkpoint in a later slice, the climb cannot be resumed, and H2 then replaces it.
   
   Fix: on open (or before the first append), truncate the file to the end of the last whole record. Better, add a per-record CRC (the history
   file format, not generation).
2. **H2 (audit 3G, confirmed failing). An unreadable history is replaced by a brand-new random climb.** `openRun`, line 240.
3. **Provisional slices are persisted today.** C1 confirms that history.bin holds slices the player was never admitted to (`syncHistory` and
   `autosave` hand over everything the tower has taken). R2 must hand over only final slices.
4. **No finality marker in existing history files.** Files written before R2 cannot say which records were final. On upgrade, every stored
   record has to be treated as final; the alternative, inferring from the snapshot, is unsafe because a player who fell may have had higher
   slices resident earlier. As a result, a backtrack directly after an old file's last record is not possible, and that case goes to Level 5.
   R2 should write an explicit `finalCount`, in the history header or SaveData, so later files say it themselves.
5. **History records carry no slice index.** Duplicates or gaps can only be found by count. Final-only append keeps the file strictly
   append-only (no truncation for backtracking), which keeps this safe. The CRC or index from item 1 would make it checkable.
6. **The history header has no rules version, difficulty or tuning hash.** This is R5, already in the audit.

## Is final-only persistence still the right approach? Yes.
- Every slice the player can see is admitted. Under R2's rule, admitted implies final, and final means its successor exists.
- `RunSnapshot.maxSlice` is computed from resident element keys and the checkpoint. A snapshot therefore refers only to final slices, so
  `openRun`'s check `hist.size() > snap.maxSlice` keeps working unchanged.
- `saveRun` already syncs the history before writing run.json, and `autosave` queues the history before the snapshot on the same ordered
  writer. Writing final slices in place of taken slices keeps that order.
- On resume, the provisional tail is regenerated by the same deterministic search, including any backtrack it had needed. The resumed climb
  therefore continues with the same course. Pending scenario 3 asserts this byte for byte.
- The file stays append-only: nothing that was written ever needs removing, because only final slices are written.
- The extra cost on resume is regenerating one or two slices, the same work the tower does today on any resume past its top.
- What it needs in addition: the finality marker (item 4) and the torn-record repair (item 1). Without the repair, final-only persistence can
  still be defeated by a single torn write.

## Smoke-gate relevance (StreamingReplayTest)
StreamingReplayTest is part of the ci.yml suite, so the running smoke-gate run 40607998237 should hit the same failure.
- The divergence is the known streaming versus whole-tower float drift: the floating-origin rebuild (rebuilt element arrays, a different origin)
  against one resident course. Here it grew large enough to change which platform is stood on.
- It belongs to an existing defect class (DC6, "the live streamed world differs from the world the proofs used"), not to the recovery work. DC6
  is at pass 1 of 3.
- Under the repair mission it must be diagnosed and repaired within DC6's remaining passes before the 30-course batch may start.
- It was not investigated further here (test preparation only).
