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
  `RobotRig` extrudes the Foozle robot's sprite parts into voxel-style 3D meshes (`VoxelPart`) and animates them procedurally.
* **App:** `ClimbGame` -> `SplashScreen` (studio logo) -> `TitleScreen` -> `PlayScreen` (+ `SettingsScreen`, `CreditsScreen`). `Ui`/`PixelFont`: immediate-mode UI and an
  original 5x7 bitmap font. `SaveStore`: versioned, atomic JSON with corrupt-file recovery and migrations. `Audio`: procedurally synthesised effects and music.
