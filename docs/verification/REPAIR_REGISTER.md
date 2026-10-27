# Repair register - bounded repair mission (owner amendment 2026-10-28)

At most three corrective passes per defect class (DC numbers from `CONSOLIDATED_AUDIT_REPORT.md`). A pass is one coherent implementation attempt
followed by focused verification; running a test or reading a log is not a pass. A compile fix or a test-harness bug is not a pass. A failure found
later (smoke gate, campaign) that belongs to a class uses that class's remaining passes; there is no pass 4.

| DC | Root cause (short) | Correction | Pass | Files | Focused evidence | Status | Remaining concerns | Commit |
|---|---|---|---|---|---|---|---|---|
| DC1 | Optimistic defaults and masked failures; two verdicts instead of four | Four verdicts with causes; hit-free witness start to goal, every route key taken; undecided look-aheads are not clear; override removed | 2 of 3 (pass 2: `outOfReach` bound ignored higher unreachable decks, so PROVEN_UNSOLVABLE was never reached) | Autopilot, CourseGenerator, LaneReport | SolverVerdictTest 4/4; probes N s2, P s2, NGH s1 slices 0-12 | PASS (provisional) | Batch measures UNKNOWN rate | 5cb56fa, e403a97 |
| DC2 | Proof start state differs from the real arrival | A ledge catch is an arrival only once its pull-up ends standing; re-proofs from the proven start and margin | 2 of 3 (pass 2: belt exit proven from a standing start dead-ended NORMAL s2024 slice 48) | Autopilot, CourseGenerator | SolverVerdictTest (pull-up blade case); s2024 slice 48 builds; DeterminismTest 6/6 | PASS (provisional) | - | 5cb56fa, e403a97 |
| DC3 | Retry and resume do not restore a clean proven state | One checkpoint contract (reset in-progress encounters, keep keys, gates, time, resolved ones); fall-frame save is the respawned climb | 1 of 3 | Sim, RunSnapshot, ClimbGame | CheckpointContractTest 4/4, RunSnapshotTest 5/5, PlayDeterminismTest 8/8 | PASS (provisional) | - | 744a480, db5ad4c |
| DC4 | Transient player state survives forced mode changes | Pad bounce wins over jump tap; coyote and buffer cleared on grabs and shoves; knock lockout 0.3 s from the real contact; swat gated | 1 of 3 | Sim | PhysicsCorrectionsTest 10/10 (each fails on the old Sim) | PASS (provisional) | - | 8c2ae8d |
| DC5 | Generator checks run before their dependency exists or go stale | Propose, register, validate, accept or roll back; one landing rule; re-proofs from real reach; encounters re-played; insertion checks | 2 of 3 (pass 2: module hazards placed against the audit's I4 rule) | CourseGenerator, GeometryIntegrity, Course | Probes N s2, P s2, NGH s1 slices 0-12 clean integrity; FramedGenerationTest | PASS (provisional) | Generation retries are bounded (24 per slice) | 3e08cf0, e403a97 |
| DC6 | Live streamed world differs from the proof world | Route-aware `Course.deck` shared by slice and tower; forks stitched | 1 of 3 | Course, Sim, Tower, SliceCodec | WorldConsistencyTest 3/3 | PASS (provisional) | - | 8c4d1ec |
| DC7 | Solver metric and window aliasing | Unique move-style ids; waits on ropes and cables beside movers; pair window reaches rejoins | 1 of 3 | Autopilot | SolverRecoveryTest, coverage gate | PASS (provisional) | - | 8c4d1ec |
| DC8 | Completion paths skip validity checks | Pull-up lets go of vanished or out-of-plane decks; no jump off a vanished deck; no step onto an open hole | 1 of 3 | Sim | PhysicsCorrectionsTest | PASS (provisional) | - | 8c2ae8d |
| DC9 | Serializer, clone and oracle field lists drift | Slice format 3 (forks, wide colour and skin); `same()` compares forks; CourseIO keeps gem rule and progression; Sim copy fields | 1 of 3 | SliceCodec, CourseIO, Sim | WorldConsistencyTest codec case | PASS (provisional) | - | 8c4d1ec |
| DC10 | Coverage register overstates evidence | Statuses corrected; VERIFIED needs a behavioural test; verdicts and removed transitions backed by code | 1 of 3 | coverage.json, VerificationCoverageGateTest | Coverage gate 5/5 | PASS (provisional) | - | e93e926 |

## Gates

| Gate | Status | Evidence |
|---|---|---|
| Focused regression set on the frozen source | running | local JUnit batches |
| Smoke-test gate (ci.yml full suite on the frozen source) | pending | |
| 30-course acceptance batch | pending | SEED_LEDGER.md |
