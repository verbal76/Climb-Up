# Architecture

* **sim/** (no rendering, headless-testable): `Sim` is a deterministic fixed-step (60 Hz), cloneable platformer simulation on a wrapped arc coordinate `s`
  (circumference = 2*pi*radius) and height `y`. Modes: GROUND, AIR, ROPE, CABLE, LEDGE (fingertip hang), PULLUP. `Element` describes one traversal piece
  (static, crumble, moving H/V, swing, bounce pad, rope, cable, goal). `Course` is the ordered route. `Tuning` (from `assets/data/tuning.json`) holds all numbers.
* **Generation:** `CourseGenerator` composes authored modules (hop, stairs, crumble run, mover H/V, pad chain, rope, cable, swing, grab gap) under a pitch
  controller (fixed height per revolution), layer-clearance rules (no overlaps or trampoline shortcuts between revolutions), rests and checkpoints.
  Every candidate link is proven by `Autopilot` (a look-ahead planner that plays the real `Sim` with parameterised policies) and must have a
  minimum tolerance (`minLinkMargin` = fraction of the macro grid that succeeds). Towers ship as data (`assets/courses/*.json`, built by `CourseBank`).
* **render/**: `WorldRenderer` places every element on a circle of radius R around a hidden axis, rotated so the player's arc position is always at screen
  centre (camera never moves sideways). Kenney OBJ blocks, procedural ropes/cables/pads, sky + parallax clouds/stars, fog, blob shadow, particles.
  `HeroRig` plays the Quaternius "Character" (skinned g3dj converted from glTF by `tools/gltf_to_g3dj.py`) with libGDX's `AnimationController`: Idle/Walk/Run/Jump/Jump_Idle/Jump_Land by simulation state, Wave at the summit; hangs and ropes reuse the airborne/run poses.
* **App:** `ClimbGame` -> `SplashScreen` (studio logo) -> `TitleScreen` -> `PlayScreen` (+ `SettingsScreen`, `CreditsScreen`). `Ui`/`PixelFont`: immediate-mode UI and an
  original 5x7 bitmap font. `SaveStore`: versioned, atomic JSON with corrupt-file recovery and migrations. `Audio`: procedurally synthesised effects and music.

## Streaming tower and floating origin (build 43 candidate)
* **Storage vs. residency.** `Tower` stores every generated slice as a losslessly compressed blob (`SliceCodec`, ~1 KB each) and expands only the slices within `REACH` (300 m) of the player into the shared `Tower.world` `Course`, with `MARGIN` (120 m) hysteresis. Memory is therefore bounded by the window, not by how high the player has climbed; the history costs ~16 KB per km.
* **Framed generation.** `CourseGenerator.chunkFramed` generates each slice in a small local frame (`yBase`, `sBase`); the generator maths is absolute through `CourseGenerator.base`. A slice depends on the whole previous slice, which is why it is stored rather than re-derived from a seed.
* **Floating origin.** The window is rebuilt with `originY` (multiple of 500 m) and `originS` (whole laps) subtracted, so every float the simulation and renderer hold stays small (local heights < 1,600 m). A rebuild yields a `Tower.Remap` (old -> new element and hazard indices, `dy`) which `Sim.rebase` (and `Autopilot.Driver.rebase`, `WorldRenderer.rebased`) apply; physics is unchanged by a rebuild.
* **Identity and state.** Elements are keyed by (slice, local index); collected keys and opened castles are re-applied after reloading a slice (`doneFeat`, `openedUpTo`).
* **Falls and respawn.** Only passing below the lowest platform of the whole tower respawns the player (`Sim.floorOverride`); the checkpoint's slice is loaded first (`respawnPending`, `Tower.maintain`), then `Sim.completeRespawn`.
* **Persistence.** Each named player has their own folder (`players/p<id>/`, see `Profiles`, docs/gameplay/PLAYERS.md); settings are shared. `SaveData` (v7, one per player) holds seed, checkpoint (slice, local), keys, castles opened, run ticks; the slice history is `history.bin` (`HistoryStore`, append-only, torn tail ignored). A climb from an older format is archived as a legacy record with a one-time notice.
