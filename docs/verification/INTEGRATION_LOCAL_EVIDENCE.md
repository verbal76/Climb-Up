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
