# Integration branch: local evidence before the one CI run (2026-10-28)

All runs were made locally with JUnit 4 on javac-compiled sources, one or two processes at a time. Branch `integration/build-82-repairs`, which merges:
- repair/ci-failures
- repair/save-history
- feature/generation-recovery
- repair/ci-trigger

| Check | Result |
|---|---|
| DeterminismTest fingerprint | rules 16, 978eaa5128ad2201d29dabbf: PASS |
| SaveHistoryRepairTest | 7/7 |
| RunSnapshotTest | 5/5 |
| BackgroundSaveTest | 5/5 |
| CheckpointContractTest | 4/4 |
| PhysicsCorrectionsTest | 10/10 |
| MoverSupportTest | 6/6 |
| EnforcerTest | 5/5 |
| BalanceConveyorTest, three unit methods | 3/3. The two castle 6-10 generation methods exceed the local time limit and are left to CI. |
| Seed-12 bundled replay, re-recorded on this source | Byte-identical inputs (sha256 520b17aa...). Finish 388652, events 8e499cce..., states 477c7729..., 1:47:57.550: equal to the ReplayCheck expectations. No re-record needed. |
| SolverRecoveryTest + SolverVerdictTest | 14/14 (Track 1) |
| SawFairnessTest | PASS: 162 saws, worst 0.67 gap / 0.50 floor (Track 1) |
| GenerationRecoveryTest | 10/10 (Track 3) |

Expected red: StreamingReplayTest. It is the float-frame knife edge at step 21389, identical on the frozen 093b6bb, and it is an owner decision
(OPEN_OWNER_DECISIONS.md).

## CI history on PR #12

| Run | Commit | Result |
|---|---|---|
| 40666755282 | 5d5e2f2 | Completed. 500 core tests, 2 failures: StreamingReplayTest (expected before the option B contract) and BalanceConveyorTest.onePerSectionBetweenCastleSixAndCastleTenAndNoneElsewhere (seed 7 section 9: no conveyor). |
| 40686504483 | 1c9d1b7 | Cancelled on the owner's directive after about 3.5 h. It could only repeat a known red: 1c9d1b7 differs from 5d5e2f2 only by the StreamingReplayTest contract, which already passes locally, and it does not contain the conveyor fix. Its partial log adds nothing that 40666755282 does not already show. |

## Conveyor claim gap (fix e0f5655), targeted evidence

- Seed 7, generated on the fixed source up to section 9 (BeltProbe, 3349 s):
  - slice 53: one conveyor, section 7;
  - slice 58: one conveyor, section 8;
  - slice 67 (4334.7-4421.3 m): no conveyor, as before;
  - slice 68 (4421.3-4510.9 m): one conveyor, `belt_claimed_below_start=1`. The section 9 conveyor is now built.
- Before the fix, slice 68 had no conveyor (4421.3-4508.9 m).

## Seed-12 bundled replay re-recorded (rules 16 + conveyor claim fix)

- **Old recording on the fixed source:** it stops at 4937 m, 9 doors, no finish; events differ, states equal.
- **Cause (BeltProbe seed 12):** slice 74 runs from 4822.1 to 4932.5 m, so its claim window ends at 4902.1 m. The castle 9-10 conveyor at
  4928.2 m fell through, so the old reference course lacked a required conveyor. With the fix, slice 75 claims and builds it
  (`belt_claimed_below_start=1`). Sections 7-9 are unchanged.
- **New recording:** 10 doors, 5000 m, finish step 388841, clock 388842, 1:48:00.700, events b84e95773ebe747109d34e86, states 477c77291f9948f63a3e4ea8
  (unchanged). ReplayCheck and BundledReplayTest are updated to these values.
- **Verification:** the controller-free playback of the new file is checked by BundledReplayTest in the CI run on this commit.
