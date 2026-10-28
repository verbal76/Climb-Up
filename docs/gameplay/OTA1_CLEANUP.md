# UPWARDLY OTA1 gameplay cleanup (E2)

Branch `exp/upwardly-cleanup-ota1`, based on `exp/ota-full@3fbf70d`. `core/` there is byte-identical to Build 44 (`9a9b9be`). Only `core/` and this file changed.
Before delivery E1 should re-check: `git diff 3fbf70d origin/exp/ota-full -- core` is empty.

| # | Defect | Root cause | Fix | Tests | Status |
|---|---|---|---|---|---|
| 1 | Blue mover sinks hero | `block-moving-blue` is 0.5 m tall but was placed as if 0.3 m: visible top 0.2 m above the sim surface | `WorldRenderer.moverBlockDy` derives offset from model height (render only) | `MoverSupportTest` | done |
| 2 | "Purple" shake-and-fall sinks, missed jumps | Same block, warm-tinted (crumbling movers). Also ramps (below). Jump window on crumbling movers verified correct in sim | as 1 and 3 | `MoverSupportTest`, `RampSlopeTest` | done |
| 3 | Fall-through at edges | Replaying flailing play on generated towers: every body that sank into a platform from above was on a RAMP. Landing used the surface at the pre-move x; running uphill through a jump the slope rises past the feet, a rising body is never landed, the hero falls through | `Sim.landOnSlope`: fallback that lands on a ramp whose surface at the NEW x reached feet that were above it. No change to existing landings, no solid-side collision, one-way pass-through from below untouched | `RampSlopeTest` (fails without fix) | done |
| 4 | SAVE & EXIT / CONTINUE / NEW RUN | Resume only restored the checkpoint | `RunSnapshot` (hero state, world clock, crumble/gone/tilt, checkpoint, keys, done features) in separate atomic `run.json`; falls back to checkpoint resume if missing/damaged/mismatched. Pause menu SAVE & EXIT; title NEW RUN with CANCEL/confirm | `RunSnapshotTest` | done |
| 5 | Spike-mat flicker | Mat top 0.03 above platform and 0.01 under the blob shadow: near-coplanar, 16-bit depth. INFERRED, not reproduced | Mat top raised to +0.06 (>= 1.5 cm margin) | `VisualDepthTest` | mitigated, needs device look |
| 6 | Brown post through purple spiked platform | NOT LOCALIZED. Cable posts, rope beams and swing pivots never overlap a moving platform's swept volume (40 seeds x 14 slices) | none | - | not done: needs screenshot/seed/height |
| 7 | Cable hand-over-hand | Already present in baseline; pose snapped between hang and move | `HeroRig.lineMove` eases in/out (render only) | none (visual) | done, needs device look |
| D | Title patch 21ee43b | not in baseline | applied by hand, own commit; LEGACY RUNS under hero picker | - | done |
| D | UPWARDLY | - | title lettering, credits, about line (in-game only) | - | done; native label/icon = separate APK item |

## Compatibility
- Generator output hash identical before/after (seeds 160-189 x 14 slices, 761 ramps): no generatorRuleset bump.
- `save.json` schema unchanged (v6). New optional file `run.json` (version 1); older builds ignore it.
- Everything is in `core/` (OTA module). `PixelFont` gained an `&` glyph. No native change needed.

## Equivalence (sim digests, SelfTest, before = branch before ramp fix, after = with it)
8 of 9 requests byte-identical (`digest:12`, `digest:1`, `chaos:1-4`, `mixed:2-3`). `mixed:1:20000` differs: expected, a ramp-landing case. Render-only fixes cannot change digests.

## Verification
Run locally (JVM): baseline `:core:test` 130 tests, 0 failures; final `:core:test` on HEAD see report. Not run: CI, emulator, physical device.

## Unresolved
Defect 6; Defect 5 and 7 need a look on device. Known pre-existing limit kept: a body rising into a platform from below that does not clear its top falls back through (one-way semantics).
