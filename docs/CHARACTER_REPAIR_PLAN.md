# Upwardly — Final diagnostic review and unified character repair plan

Prepared 2026-10-13 (00:30–01:3x Z) at the independent reviewer's request. Diagnostic only: no production code, model, animation, camera, gameplay, build, commit, push, merge, OTA or release was touched. HEAD `5e9bb11`; Build 55 = `d69890f` (the only difference between the two in `core/`, `assets/` and `android/` is the title string in `Platform.java` and one test). Companion documents: `docs/HAND_ROPE_FORENSIC_AUDIT.md` (chronology, attempts, Addenda A–B) and the scratchpad `gs_compare/DIAGNOSTIC_PHASE_REPORT.md` (harness repair, device comparison). Evidence for this report: scratchpad `gs_matrix/` (renders + `report.txt`), `gs_compare/matrix_*.png` (per-character state sheets), `gs_compare/matrix_table.md` (per-capture numbers), `audit/bones.txt`, `audit/ham_tris.txt`, `audit/anim_analysis.txt`, `audit/inventory*.txt`.

Legend: **VERIFIED** = measured from code, assets or the faithful harness; **DEVICE** = confirmed against the Build 55 device screenshot; **INFERRED** = follows from verified facts but not itself measured; **HYPOTHESIS** = untested.

---

## Phase 1 — the diagnostic harness (`desktop/src/test/.../GameplayShot.java`) versus the Android runtime

### 1.1 Item-by-item audit (VERIFIED from source unless noted)

| Reviewer item | Android runtime | Harness | Same? |
|---|---|---|---|
| Character assets / models | `Characters.model(c)` from the APK's `assets/` (`android/build.gradle` packs `../assets`) | `Characters.model(c)` from the working-tree `assets/` | **Yes** — all six `.g3dj`, `hero/hamster.png` and `data/tuning.json` are byte-identical to the files inside the Build 55 APK (sha256 compared: hamster `49361c3f…`, Finn `f3846bdd…`, Rae `a7b53b64…`, Fernando `60198bed…`, Barbara `94816828…`, bunny `51c8abf4…`) |
| WorldRenderer / HeroRig | `new WorldRenderer(tuning, course, models, quality, character)` (PlayScreen:136), `HeroRig` created inside (WorldRenderer:87; `setCharacter` only re-creates the same class) | identical constructor call, same compiled classes (the report prints `CLASSES … HeroRig=file:/home/user/Climb-Up/core/build/…`; the consol worktree's experimental `HeroRig` is not on the classpath) | **Yes** |
| Animation clips | `G3dModelLoader` on the same file (HeroRig:295) | same | **Yes** |
| Transition behaviour | `play()` → `AnimationController.animate(id, loops, speed, null, blend)` with the per-state blend times (HeroRig:325–329, 407–418); `ac.update(dt)` once per rendered frame | same code, dt = 1/60 s per rendered frame | **Yes** (a 90/120 Hz phone advances the same clip time in smaller steps; a dropped frame advances more — timing, not pose, differences) |
| Rope geometry and position | a `ROPE` course element drawn by `WorldRenderer.drawElement` (0.11-wide box, at the arc offset of the element the sim holds) | the same element of the same procedural course (`CourseGenerator.chunk(seed 11)`), drawn by the same code; the sim holds it | **Yes** (the device plays a different procedural course, so the rope's *surroundings* differ; the rope itself and the hero's relation to it are the same code and the device screenshot measured the same rope width, 16 px, and centre, 50.0 % of the width) |
| Event feeding | `world.heroEvents(ev, sim.landSpeed)` per fixed step (PlayScreen:444) | the same call after every `sim.step` (one step per rendered frame) | **Yes** |
| Procedural arm and leg controls | `HeroRig.update()` inside `drawPlayer` (WorldRenderer:1038), every rendered frame | the same path, every rendered frame (`wr.render` each frame) | **Yes** |
| Camera calculations | `resize(w,h)` (PlayScreen:175), `snapCamera` once at play start (:139), then `render()`'s own smoothing, lead and respawn snap (WorldRenderer:443–480) | `wr.resize(2400,1080)`, `wr.snapCamera(sim)` once, then the same `render()` per frame; respawn through `sim.respawn()` → `sim.teleported` | **Yes** |
| Character scale and transforms | `Characters.scale`, `HeroRig` body transform, `place()` | same | **Yes** |
| Simulation / rendering order | fixed steps (`sim.step`, events, `heroEvents`) then `world.render(sim, acc/DT, dt, time, true)` (PlayScreen:284–313) | step → events → `heroEvents` → `render(sim, 1, dt, time, true)` | **Yes** (alpha differs, see 1.2) |
| Viewport aspect | the whole panel: `sensorLandscape`, immersive mode, `windowLayoutInDisplayCutoutMode=shortEdges` (manifest, `values-v28/v31/styles.xml`), `numSamples=0`, 16-bit depth; `GfxHooks.renderScale` stays 1 on Android (only the desktop `GraphicsManager` sets it) so the 3D scene is drawn at the full back-buffer size; the owner's screenshot is 2404×1080 | 2400×1080 FBO (RGBA8888 + 16-bit depth, no MSAA), aspect 2.222 vs 2.226 | **Yes** within 0.2 % |
| Continuous animation through real simulated frames | — | yes: every frame of the autopilot-driven sim is rendered; captures are taken from that stream, never posed; the report prints `ac.current … transition …` for each capture | **Yes** |
| No synthetic rope, no chosen camera angles, no crops as primary evidence | — | the rope is the sim's element; the camera is `WorldRenderer`'s; captures are the full 2400×1080 frame (the per-character sheets crop the hero *for layout only* and name the full frames they come from) | **Yes** |

The Android app has no portrait mode (`sensorLandscape`); "Android portrait aspect ratio" in the request is read as the Android panel aspect, 20:9 landscape, which is what the harness uses.

### 1.2 Every remaining difference, and whether it matters

| # | Difference | Effect | Material to defects 1–4? |
|---|---|---|---|
| D1 | Interpolation alpha: the phone draws the hero between the previous and current sim step (`acc/DT`); the harness draws the current step (alpha 1) | the hero (and the camera target) are up to one sim step of travel apart (≤ 0.09 world units, ≈ 14 px at run speed; 0 while hanging) | No: it moves the whole hero, not the pose |
| D2 | PlayScreen's camera reactions are not replayed: `world.kick()` after a jump/bounce (FOV spring, peak ≈ 0.1°/0.4°), `world.shake()` on hard landings and hits (≤ 0.05 units for 0.25 s), hit-stop `freeze()` (sim pauses 0.05–0.09 s while rendering continues), `platformLanded` dips, particles | sub-degree FOV and sub-0.05-unit camera offsets for a fraction of a second; a few frames of animation advancing during hit-stop | No |
| D3 | Input: the autopilot plays, not a thumb | different timings/paths; the autopilot never hangs still on a rope and jumps straight off the rope top | No for poses; it means **`rope_hang` (hanging still) has no harness evidence yet** and `rope_leave` shows the jump-off, not a pull-up |
| D4 | Settings: harness quality 2 and reduced-motion off (the defaults); the owner's values are unknown | quality affects decorations, the drop shadow, particles — not the hero's bones; reduced-motion removes lean, shimmy roll, flail, inch and camera motion — not the grip targets or clip poses | No for the hand/arm geometry; a reduced-motion device would show slightly different body lean/sway |
| D5 | Course content: seed-11 chunks vs the device's run | different platforms around the hero | No |
| D6 | GPU: Mesa llvmpipe (software GL) vs the phone's GLES driver | shading/filtering precision, depth precision where the ball and stub overlap, texture sharpening path (`GfxHooks.sharpen`) | No for geometry; **yes for pixel-exact colour/edge questions** — not validated |
| D7 | Panel 2404 vs 2400 px wide | 0.17 % horizontal scale | No |
| D8 | HUD and touch controls (2D) are not drawn | the owner's screenshots carry them over the frame's lower corners | No (the hero is centred) |
| D9 | Floating origin / tower rebase (`setOrigin`, `rebased`) not used | world-y magnitudes differ | No |

Verdict: for the four defects (bone geometry, hand placement, face occlusion geometry, running arms) the harness is **VALIDATED**: it runs the Build-55-identical code and assets, through the real renderer, camera and animation system, on real simulated frames, and its hamster-on-rope frame matched the device screenshot (rope centre, rope width, head height, hand arrangement, duplicate stubs: `DIAGNOSTIC_PHASE_REPORT.md` §D). It is **NOT validated for pixel-exact rendering** (D6) and it carries **no evidence for hanging still** (D3). Do not read any harness number as "verified on device" unless Phase 5 says so.

### 1.3 Harness work done in this phase (test source only; explained here as required)

What was missing and why it was added: the validation matrix asks for jumping, falling, approaching, grabbing and leaving the rope, and for objective face-clearance and hands-on-rope criteria; the harness only captured settled states and printed positions. Added: state labels `jump`, `fall`, `rope_grab` (first 12 frames on the rope, every 2nd frame), `rope_leave` (first 12 frames after the rope), `rope_approach` (the frames 6/4/2 before a grab, from a ring of the last rendered frames), a per-state capture cadence, captures restricted to the asked-for states, a `FACE` line (CPU-skins the Head-dominated vertices with the live bone matrices, keeps the camera-facing half, projects them with the game camera and counts how many fall inside either ball's projected disc), and a `GRIP` line (each ball's sideways and depth offset from the rope's line and its height relative to the head and shoulders, with an `onLine` test: |dx| ≤ ball radius + 0.03, not behind the line by more than its radius). One cadence bug found and fixed during the run (a brief state beginning in the frame another sequence ended was skipped). The file remains untracked.

---

## Phase 2 — the four defects

### Defect 1 — hamster duplicate hands (VERIFIED, DEVICE)

- **What remains of the original paw (triangle level, `audit/ham_tris.txt`):** the original mesh had 466 all-`LowerArm` triangles per side (x 1.20–1.40 in model units) plus 70 triangles per side straddling the `LowerArm`/`UpperArm` boundary. The strip (`tools/models/strip_hand_geometry.py`, `7e0bf56`) removed 299 per side (the tips beyond x ≈ 1.29); `fill_holes.py` (`8eafc8e`) then added 3 vertices and 37 triangles per side as a cap at x ≈ 1.26–1.28, and 6 vertices / 48 triangles on the muzzle (Head-weighted, x ±0.06–0.15, y 2.20–2.31, z 0.84). **Shipped today per side: 204 all-LowerArm triangles (146 vertices, x 1.20–1.29) + 70 boundary triangles + 37 cap triangles = 311 triangles of paw stub**, driven by `LowerArm.L/R` (weight 0.98–1.00 at the cap; the boundary triangles mix `UpperArm`). `Fist.L/R` drive 0 vertices; there are no finger bones.
- **Visible cups / caps:** the cap closes the strip cut, so the stub is a closed white blob; it is what appears beside each ball on the rope (device screenshot and harness). The 48 muzzle triangles sit on the nose; no evidence they are visible at game scale (not measured).
- **Why it shows:** `stretchArmsBy(grip, 0.7, 1.5)` scales the upper and lower arm bones by (1.7, 2.5, 1.7) each, compounded; the stub is skinned to the lower arm and ends up 0.31–0.54 world units from its ball (`RESIDUAL LowerArm distToBall min 0.308/0.315` on `1_rope_climb_2`; bbox ≈ 0.19 × 0.25 × 0.22). At idle the same stub sits inside/abutting the ball (0.016–0.095 < r 0.100), which is why the duplicate only shows while gripping.
- **Would removal touch forearm, wrist or fur?** The whole `LowerArm`-skinned region *is* the stub (x 1.20–1.29); the upper arm's geometry (209 vertices per side, x 0.85–1.20, the arm root) is untouched by a bone-based removal. Removing every triangle with a `LowerArm` corner (274 per side) opens exactly one new rim per side: 27 edges / 31 vertices at x 1.17–1.20, y 1.33–1.50, z −0.14–0.22, all `UpperArm`-dominated — one loop to cap. (Removing only the 204 all-LowerArm triangles leaves 27 newly opened edges on a ragged partial ring of the mixed triangles; the clean cut is the former.) The arm box already contains 154 open edges per side in the shipped mesh (the original shell was never closed), unrelated to this.
- **Ball position and scale:** radius 0.100 world (approved size and tan colour, `BALL_RATIO 0.10`, HeroRig:47/306–310), placed at the fist blended 40 % back to the lower-arm joint (`BALL_AT 0.6`), pushed +0.17 toward the camera on the rope, never pulled sideways (`ballPullX` 1 for the hamster). At rest it covers the stub; on the rope the stretched stub escapes it.

### Defect 2 — rope grip (VERIFIED, hamster DEVICE-matched; astronauts see Defect 3)

Hamster, faithful climb cycle (`gs_matrix` `1_rope_climb_0..4`, every 5th frame, `lineMove` 1.0, `Jump_Idle` fully blended):

| hand | sideways offset from the rope (dx) | depth in front (dz) | height vs head centre | on the line? |
|---|---|---|---|---|
| left ball | +0.178 … +0.193 | +0.16 … +0.22 | −0.05 … −0.12 (chin) | **no** (1.8 radii off) |
| right ball | −0.002 … +0.010 | +0.16 … +0.21 | −0.11 … −0.25 (chest) | yes |

- The two hands do not both reach the line: `gripLine` aims each arm at the line's lateral position but through a target 1.3 units *ahead* (`ROPE_REACH`), so the aim direction is mostly forward; the short stretched arm (≈ 0.40 world) lands 0.18–0.19 beside the line for the higher hand. The lower hand lands on it. This produces the bunched, diagonal pair seen on the device (one ball on the rope below the chin, the other beside it), with the two white stubs further out and behind (dz −0.10 … −0.31).
- Left/right reach, elbow/wrist: `stretchArmsBy` lengthens the bones along their **local Y**; in the hamster the arm bones' local Y is 42°–58° off the bone (`audit/bones.txt`: UpperArm local child direction (0.13, 0.66, 0.74), LowerArm (−0.55, 0.53, 0.65); the bunny's are exactly (0, 1, 0)). So the "lengthen" partly widens the hamster's arm across its axis — one of the reasons the stretched stubs look mangled and sit off-axis. (See Defect 4: the same rig fact.)
- Front/back: both balls are in front of the rope (dz +0.16 … +0.22); the body sits 0.37 behind it (`BUN_BACK`), so the rope crosses the face (0 % of the face is covered by the balls: `FACE coveredAny 0–1 %` across the cycle).
- Transitions: `rope_grab` frames 0–11 show the Jump_Idle cross-fade completing at frame 7 and `lineMove` easing 0.23 → 0.95; the left ball starts 0.45 off the line and converges to 0.21 by frame 11; nothing snaps.
- Candidate "B" from the 12 October sweep (`hamPull` 0.3 etc.) is **not** carried forward: every sweep image came from the single-update, unsnapped harness (audit §5, Addendum A) and `ballPullX` < 1 detaches the ball from the arm (`cf6a9e1` message). No parameter sweep was run in this phase.

Feet: `feetToLine` holds both feet on the line (feetL/R x ±0.08, z −0.37, i.e. on the rope's lateral line, behind it) throughout the cycle — this is the approved behaviour and is unchanged by anything proposed below.

### Defect 3 — astronaut hands over the face

Mechanism (VERIFIED from code, numbers from the harness): the astronauts' hands are aimed to `shoulder.y + len × h` with `h` between `GRIP_LO 0.02` and `GRIP_HI 0.26` of the arm length (≈ 1.0 world with the 1.35 stretch) → the balls sit 0.02–0.26 above the shoulder line, which for a helmeted head is the visor; the body is pushed 0.82 behind the rope (`ASTRO_BACK`) and the balls 0.17 in front of it, so in projection the balls (r ≈ 17 px) land on the helmet's front. All four astronauts share one skeleton (node trees and bind matrices identical to 4 decimals, `audit/bones.txt`) and the same arm/glove geometry; they differ only in the head: Finn's head reaches furthest forward (z to 0.79 model units; Rae 0.54, Fernando 0.43, Barbara 0.34, which is wider: x ±0.59) and the helmet/neck ring bottom is common (y 1.74). So one grip height serves all four, but face clearance must be checked on Finn (most forward) and Barbara (widest).

Per-character measured occlusion across grab → climb → leave: **see §2.3a (filled from `gs_matrix` when the render completes; the hamster rows above come from the same run)**.

### Defect 4 — hamster running arms (VERIFIED; cause in Addendum B of the audit)

Evidence reviewed and confirmed: `Run`/`Walk` have full arm tracks copied from the bunny; the hamster's arm joints were laid out as a horizontal chain (all at height 1.25 in `build_rigged_hamster.py:101`) while every rotation key was copied unchanged; nothing in `HeroRig` overrides the arms while running (`armsRest()` is idle-only, HeroRig:478). Runtime: hamster upper arms 71–90° from straight down in `Run` (bunny 24–76°, Finn 25–73°), hands at shoulder height, out to the sides.

The skeleton comparison (`audit/bones.txt`) widens this beyond running. Because the builder kept the bunny's local rotations and only moved the joints, **every bone whose child sits in a different direction than in the bunny is driven off-direction by every clip**: Shoulder 41.7°, UpperArm 48.9°, LowerArm 58.2°, UpperLeg 48.9° (the bunny's thigh rests bent forward, the hamster's straight down), Ear1/Ear2 24–27°, Root/Abdomen/Torso/Head 12–15°. The arm chain is the only one the owner has reported, and the only one with a visible symptom in the sheets (legs run and stand plausibly; ears and spine look right), but the plan below fixes the arm chain at the rig level, not with a running-only patch, and records the rest as a known residual.

Same cause, other states (VERIFIED in `gs_matrix`): `jump` 90–92°, `fall` 94–96°, and `rope_leave` (the jump off the rope top, face-on) 90°/90° in all 8 frames — a full T-pose the player sees every time the hamster leaves a rope; `rope_approach` frames show the same spread arms in the air. `idle` reads 26°/26° only because `armsRest()` aims the arms down.

Role of `build_rigged_hamster.py`: it defines the landmarks (line 101), keeps the bunny local rotations (line 84), copies the clips with translation keys re-based (lines 170–183), and computes skin weights from vertex position. It needs Blender (`bpy`) and the hamster `.blend` to re-run; the repair below operates on the shipped `.g3dj` directly and must be mirrored in the builder for future rebuilds.

Effects of correcting the arm chain (what the plan must preserve or re-validate): rope grip (aim-based, frame-agnostic — targets unchanged; the stub orientation and the stretch axis change), jumping/falling (arms become the bunny's `Jump`/`Jump_Idle` arms, raised like the bunny's instead of a T), idle (`armsRest` becomes nearly a no-op), ball placement (follows the fist, which now hangs where the arm points), skinning (unchanged — vertices and weights are not touched), paw geometry (rotates with the bones as any skinned geometry does).

### 2.3 Measured tables

#### 2.3a Face occlusion and grip, per character, grab → climb → leave (faithful harness, game camera)

Run: `gs_matrix` (seed 11, slices 0–2, 364 captures, 312 distinct; `gs_compare/matrix_final/matrix_table.md` has every capture's numbers; `matrix_<n>_<name>.png` are the per-character state sheets). `FACE` = share of the camera-facing half of the Head-dominated vertices (helmet top included) that lies inside either ball's projected disc, so it under-states what a viewer calls "the face": the balls sit on the lower front (mouth/visor), where the coverage is much higher than the whole-front figure; the eyes stay visible above the balls in every astronaut frame of this run. `dx`/`dz` = the ball's sideways / depth offset from the rope's line (world units; + = in front); `dyHead` = ball height minus head-node height.

| character (head geometry) | rope_grab frames 0–7 | rope_climb frames 0–4 | rope_leave 0–7 | hands on the line | ball height vs head |
|---|---|---|---|---|---|
| Finn (head to z 0.79, most forward) | FACE 17–26 % | **10–31 %** | 0 % (`Jump` arms 36–50°) | both from grab frame 2 on: L dx 0.006–0.096, R 0.047–0.141; dz +0.02 … +0.22 | L −0.09 … +0.10 (at the head centre), R −0.20 … +0.09 |
| Rae (head to y 2.92, tallest) | 14–30 % | **17–34 %** | 0 % | identical arm numbers (shared skeleton) | identical |
| Fernando (head to z 0.43) | 14–22 % | **13–20 %** | 0 % | identical | identical |
| Barbara (head x ±0.59, widest) | 25–42 % | **27–49 %** | 0 % | identical | identical |
| Hamster | FACE 0–1 %; L ball 0.45 → 0.21 off the line, R on it | 0–1 %; L ball 0.18–0.19 off the line, R on it | 0–1 % but arms 90°/90° (T-pose, `Jump`) | left hand never; right hand yes | L at the chin (−0.05 … −0.12), R at the chest (−0.11 … −0.25) |
| Bunny (real paws, no balls; fist bones measured, r 0.05) | both fists converge onto the line by grab frame 3–4 (L dx 0.30 → 0.09, R 0.26 → 0.02) | both on the line (L dx 0.07–0.08, R ≤ 0.006; dz ≈ 0) | `Jump` arms 40–46° | yes (climb) | not measurable: the bunny's `_Head` node is a root-level helper at the feet |

Reading: the astronauts' hands *are* on the rope (defect 2 is not an astronaut problem) but one ball sits at head-centre height and both are 0.02–0.22 in front of a head that is 0.82 behind the line — defect 3 is a height/depth problem, worst on the widest head (Barbara) and the most forward one (Finn). On the sheets the balls cover the mouth/visor band of every astronaut with the eyes showing above; on Barbara the near-black balls meet a black-and-white face, so the lower face and the hands merge into one dark mass, which is the most likely source of "completely covered" (INFERRED — a device frame would settle it). The hamster's problem is the opposite: heights are fine (balls below the chin), but the higher hand misses the line sideways by 1.8 radii, and the stubs duplicate the hands. The bunny grips correctly today.

Other states from the same run (upper-arm angle from straight down, both arms, all captures): `run` — only the hamster carries its arms horizontally (74–89° here, 71–90° over all runs; astronauts 24–74°, bunny 25–67°); `jump` — hamster 90–102°, astronauts 34–87°, bunny 39–68°; `fall` — hamster 92–96° (both arms level), astronauts and bunny 58–106° (`Jump_Idle` raises one arm and drops the other — the bunny's own look); `rope_leave` (the jump off the rope top, face-on) — hamster 90°/90° for 12 frames, every other character 36–50°; `rope_approach` (buffered frames, no numbers) — the sheets show the hamster arriving with spread arms and the others in the `Jump` pose; `idle` — hamster 26°/26° (`armsRest`), astronauts and bunny 16°/16°. The sheets `matrix_1_hamster.png` … `matrix_0_bunny.png` show all of this on full game-camera frames.

---

## Phase 3 — unified repair strategy

The four defects share two roots: (R1) the hamster rig's arm chain is laid out and bound in a frame the copied clips were not authored for (defect 4, and it misdirects the rope stretch that exposes defect 1 and bends defect 2); (R2) the grip pose aims at a point ahead of the body instead of at the rope's final position, with hand heights tuned for the bunny's small head (defects 2 and 3). Previous attempts changed ball placement, mesh thresholds and push-back distances one at a time, validated on a harness that did not show the game. The order below fixes R1 first because every later measurement (stub, ball, grip) depends on the arm bones.

| step | file / asset | root cause | change | why better than before | side effects to re-check | test | reversal |
|---|---|---|---|---|---|---|---|
| **1. Re-base the hamster arm chain** | `assets/hero/hamster.g3dj` via a new deterministic script `tools/hamster/rebase_arm_frames.py` (JSON in, JSON out; also folded into `build_rigged_hamster.py` for future rebuilds) | R1 | For `Shoulder`, `UpperArm`, `LowerArm`, `Fist` × L/R: set the rest **global** rotation to `Rw · Qbunny` where `Rw` is the minimal rotation taking the bunny's rest bone direction onto the hamster's (41.7°/48.9°/58.2° about the respective axes; the Fist takes the LowerArm's); recompute each bone's local rest rotation and its children's local translations so every joint stays exactly where it is; rewrite the bind (`bones[]`) entries and the 2 static translation keys of those bones in every clip. **Rotation keys, vertices, weights, indices, textures: untouched.** Result: each arm bone's local +Y points at its child exactly as in the bunny, so the bunny's keys now give the bunny's world arm directions on the hamster, and `stretchArmsBy` lengthens along the arm | It fixes the frame the clips assume instead of patching one state; the earlier procedural hamster (`8cf471d`) and `armsRest()` only masked symptoms | all hamster clips (run, walk, jump, fall, hang, wave beats); idle with `armsRest`; rope stretch now along the bone (the stub direction changes); the UpperArm root blob swings down ≈ 49° at the clips' rest; ball placement (unchanged at rest) | offline: `anim_analysis.py` must report hamster `Run`/`Walk`/`Jump_Idle` upper-arm angles equal to the bunny's ±1°; harness: `idle, run, run_front, jump, fall, rope_approach, rope_grab, rope_climb, rope_leave, cable_shimmy, ledge_hang` for the hamster; `RESIDUAL` and `limbs` lines | `git revert` of the asset (previous blob `49361c3f…`), script kept |
| **2. Remove the paw stub, cap the arm** | `assets/hero/hamster.g3dj` via `tools/models/strip_hand_geometry.py` extended with a bone-weight + connectivity mode (no |x| threshold): delete every triangle with a `LowerArm.L/R`-dominated corner (274 per side, including the 37 fill-cap triangles), cap the single 27-edge loop at x ≈ 1.17–1.20 with one centre vertex weighted to `UpperArm`; keep the 48 muzzle triangles | defect 1 | The upper-arm root (209 vertices) is the visible arm; the ball is the paw. `BALL_AT` stays 0.6 (keeps the ball against the arm's end while the arm is stretched: at 2.5× the arm end reaches ≈ 1.55 and the ball's near edge ≈ 1.61 model units) | Previous strips cut by position and left 311 triangles of stub behind; the cap tool then closed it into a solid white blob | silhouette on the rope and in run/idle (no stub); seam at the cap; `RESIDUAL LowerArm n` must read 0 | harness as in step 1; `RESIDUAL` must show no LowerArm/Fist/Finger vertices; sheets reviewed at full frame | `git revert` of the asset |
| **3. Grip geometry, derived once (no sweep)** | `core/.../render/HeroRig.java` (`gripLine`, grip-height constants) | R2 | (a) aim each arm at the line's **final** position: `tz = pB.z + lineBack()` instead of `+ ROPE_REACH 1.3` — the aim direction then carries enough inward component that the hand lands on the line for every arm length (geometric check: hamster |dx| ≈ 0.05, astronauts ≈ 0.04, both < r + 0.03); (b) astronaut-family grip heights below the helmet: `h_astro` derived from the shared helmet bottom (y 1.74 model, the same for all four) so that the ball's top sits ≥ 0.03 world below it — one constant pair for the four astronauts, the bunny and hamster keep their current heights unless the matrix says otherwise; (c) `ballFront 0.17`, hamster `ballPullX = 1`, ball radii and colours, `feetToLine`, `BUN_BACK`, `ASTRO_BACK` unchanged | Single geometric rule replacing a reach magnitude that was never related to the body's push-back; heights derived from measured head geometry rather than guessed | all six characters on rope and cable (the cable branch is untouched but shares the method); `shift` (body height vs rope) follows `handModelY`; the bunny's rope hands move onto the line too — accepted or gated by family after the matrix | `GRIP onLine` true for both hands in ≥ 90 % of `rope_grab`+`rope_climb` frames for all six; `FACE coveredAny ≤ 2 %` for the four astronauts and the hamster in every rope frame; feet positions within ±0.02 of today's | constants are the only edits; revert the commit |
| **4. Validate, then device** | harness → owner build (authorised separately) | — | full matrix (Phase 4) on all six characters; then one Android build; the owner captures the four scenarios (hamster rope, hamster leaving a rope, astronaut on rope, hamster running) | closes the Phase 5 hardware gaps | — | compare the device frames with the harness frames by the same measurements (rope x, rope width, head/ball px) | — |

Rules carried from the request: hamster feet, ball colour and size (tan, 0.10) preserved; no oversized or dark hamster hands (the astronauts' near-black balls are their glove colour, unchanged); no change to physics, climbing responsiveness, jump behaviour, camera or course generation (none of the four steps touches `Sim`, `WorldRenderer` camera code, tuning or the generator); character identity/proportions unchanged (vertices and textures are not edited except deleting the stub).

Explicitly not proposed: a RUN-only `aim()` mask (conceals R1); moving the hamster's arm joints along the bunny's rest direction (would pivot the skinned geometry about joints outside it); `ballPullX` < 1 for the hamster (detaches the ball); re-basing the legs, ears and spine now (no reported symptom; the feet-on-rope behaviour is approved; recorded as residual in §C).

---

## Phase 4 — validation matrix

Characters: hamster (H), bunny (B), Finn/Rae/Fernando/Barbara (A = astronauts, identical skeleton, different heads). Camera: the gameplay camera in every row (G); face-on frames (F) occur naturally when the body yaw is near 0 (rope, first steps, reversals; harness `run_front`); side (S) is the normal ground view (yaw 68°). All evidence = full 2400×1080 harness frames unless marked DEVICE.

| state | expected appearance | current observed defect | evidence available | evidence still missing | objective acceptance |
|---|---|---|---|---|---|
| Idle (G, F at beats) | arms hanging at the sides; ball hands beside the hips (H/A), paws (B) | H: masked by `armsRest` (26° from down); A/B fine | `gs_matrix *_idle_*` (H), `gs_faithful*` (A, B) | device idle frame (any) | upper arm 15–45° from down; `FACE` 0 %; no stub (`RESIDUAL` n = 0 after step 2) |
| Running (G, S) | arms pumping below the shoulders, hands at waist/hip height | **H: arms horizontal 71–90°, balls at shoulder height** (defect 4); A/B correct | `gs_run2`, `gs_matrix *_run_*`, `gs_compare/run_arms_gs_run2.png` | DEVICE frame of the hamster running | H upper arm within ±10° of the bunny's at the same clip time; hands below the shoulder line |
| Running face-on (F) | both arms down/pumping | **H: T-pose** (`gs_front`) | `gs_compare/run_front_gs_front.png` | DEVICE frame mid-turn | as above |
| Jumping (G) | bunny-like raised arms (`Jump` clip) | **H: 90–92° horizontal** | `gs_matrix *_jump_*` | — | H within ±10° of the bunny's `Jump` arms |
| Falling (G) | bunny-like `Jump_Idle` arms | **H: 94–96° horizontal** | `gs_matrix *_fall_*` | — | as above |
| Approaching rope (G, mostly F) | arms reaching, body facing the rope | **H: spread arms in the air** | `gs_matrix *_rope_approach_*` (3 buffered frames before each grab) | — | as jumping/falling |
| Initial rope grab (F) | both hands close onto the line within the 0.12 s cross-fade; face clear | H: left hand 0.45 → 0.21 off the line over 12 frames; A: balls on the visor from frame 0 | `gs_matrix *_rope_grab_0..7` | DEVICE frame of a grab | by frame 12: both `onLine`; `FACE ≤ 2 %` (A, H) |
| Continuous climbing (F) | hand over hand on the line at chest height, face visible, feet on the line, no stub | **H: left ball 0.18 off the line, white stubs 0.31–0.54 away (defects 1, 2); A: balls on the face (defect 3)** | `gs_matrix *_rope_climb_*`, `gs_faithful*`, DEVICE `e893336e` (H) | DEVICE frame of an astronaut climbing | both `onLine` ≥ 90 % of frames; `FACE ≤ 2 %`; `RESIDUAL` n = 0; feet unchanged |
| Hanging still (F) | as climbing, hands static | not observed (the autopilot never hangs) | — | harness run with a scripted pause, or a device frame | as climbing |
| Climbing transitions (F) | smooth hand alternation, no snap | H: none seen; A: pending | `rope_grab` → `rope_climb` sequences | — | no `GRIP` jump > 0.1 between consecutive captures |
| Leaving rope (F/G) | pull-up or jump-off with arms in the clip's pose | **H: full T-pose for 12 frames after the top (defect 4)** | `gs_matrix *_rope_leave_*` | DEVICE frame of the hamster leaving a rope | H within ±10° of the bunny's `Jump` arms |
| Cable, ledge (G) | approved today | — | `gs_faithful*` (`cable_shimmy`, `ledge_hang`) | re-run after steps 1–3 | unchanged within ±0.02 for feet/hands vs today's captures |

---

## Phase 5 — Android fidelity assessment

1. **Hamster duplicate hands:** reproduced — **DEVICE** (matched against the Build 55 screenshot `e893336e`: same rope centre/width, head height within the camera's lag range, the diagonal ball pair, the white stubs beside them).
2. **Rope grip:** reproduced for the hamster — **DEVICE** (same screenshot); astronauts reproduced in the harness only.
3. **Astronaut face obstruction:** reproduced in the harness (balls on the visor; the measured fraction per character is in §2.3a) — **not confirmed on hardware**; the owner's description says "completely covered", the harness shows partial coverage varying through the cycle. Matching the description is not the same as matching a screenshot; this stays INCOMPLETE until one device frame exists.
4. **Hamster horizontal running arms:** reproduced in the harness (steady run 71–90°, face-on frames, and the T-pose after leaving a rope) — **not confirmed on hardware**.
5. Compared with Build 55 device screenshots: only the hamster-on-rope case (one screenshot).
6. Unverified on hardware: astronaut face obstruction (all four), the hamster's running arms, every transition state, the bunny, Rae/Fernando/Barbara on the rope.
7. Remaining differences: §1.2 D1–D9; none material to geometry; D6 (GPU) means edge/colour judgements need the device.
8. Trustworthy enough to guide a production correction? **Yes for steps 1–3 as the gate for geometry**, with the device round in step 4 before any sign-off, and with the explicit condition that no harness result is called "verified on device".

---

## Phase 6 — final report

**A. Harness fidelity.** Validated for geometry, camera, animation state and timing on real simulated frames (Phase 1), one device match on record; not validated for pixel-exact rendering; no hanging-still evidence.

**B. Root causes.**

| defect | root cause | status |
|---|---|---|
| 1 duplicate hands | 311 stub triangles per side still skinned to `LowerArm`, scaled ≈ 4× by the compounded rope stretch (itself misdirected, R1) | VERIFIED, DEVICE |
| 2 rope grip | `gripLine` aims 1.3 units ahead instead of at the line's final position; stretch off-axis (R1); the higher hand lands 0.18 beside the line | VERIFIED (hamster DEVICE) |
| 3 astronaut face | grip heights 0.02–0.26 of arm length above the shoulder on a helmeted head, body 0.82 back, balls 0.17 forward → balls over the visor | VERIFIED geometry; device INCOMPLETE |
| 4 running arms | arm chain bound in a frame 42–58° off the bunny's while the bunny's keys are copied verbatim (R1) | VERIFIED; device INCOMPLETE |

**C. Verified facts vs hypotheses.** Verified: everything marked above; the astronaut skeletons are identical; the hamster legs/ears/spine carry the same class of frame mismatch (49°/24–27°/12–15°) without a reported symptom. Hypotheses (to be proven by the step-1/2/3 harness runs): the re-based arm chain gives bunny-equal arm angles (expected exactly, by construction); the upper-arm root blob reads as a hanging arm after re-basing; the ball at `BALL_AT 0.6` stays attached to the capped arm while stretched; one astronaut grip height clears all four helmets; the bunny's hands improve rather than regress under the new aim rule; the 48 muzzle caps are invisible at game scale.

**D. Repair order.** 1 rig re-base → 2 stub removal + cap → 3 grip geometry (aim at the line's final position; astronaut heights below the helmet) → 4 full matrix, device build, device captures, sign-off. One change set, three commits, each reversible on its own.

**E. Files/assets proposed for modification.** `assets/hero/hamster.g3dj` (steps 1–2, regenerated by scripts, vertices untouched except the stub deletion), `tools/hamster/rebase_arm_frames.py` (new), `tools/hamster/build_rigged_hamster.py` (mirror the re-base for rebuilds), `tools/models/strip_hand_geometry.py` (bone-weight mode), `core/src/main/java/com/hotatticgames/climbup/render/HeroRig.java` (`gripLine` target depth; astronaut grip-height constants), `ASSETS.md`/`docs/DECISIONS.md` entries, and the harness (test source) kept as the gate. Nothing else.

**F. Risks to approved appearance.** Hamster: arm motion changes in every state (intended); the arm root blob orientation at rest/idle changes slightly even with `armsRest`; the rope silhouette loses the white stubs; the stretched arm now reads as one lengthened blob ending in the ball. Astronauts: hands move from the visor to the chest (intended) — the pose becomes "pulling the rope hand under hand" rather than "reaching up". Bunny: rope hands move onto the line (small, likely an improvement; gate by family if not). Unchanged: feet on the rope, ball sizes/colours, cable and ledge poses, camera, physics.

**G. Regression matrix.** Phase 4, run on all six characters before and after each step; plus the existing unit tests (`HeroRig` limb-scale guards: `minLimbScale`, `maxHandScale`) and the asset validation tests.

**H. Android evidence gaps.** One device frame each of: an astronaut on a rope (Finn), the hamster running, the hamster leaving a rope, the hamster hanging still; ideally with the quality and reduced-motion settings noted.

**I. Complexity and validation time.** Step 1 ≈ 2–3 h (script + offline proof + harness); step 2 ≈ 1–2 h; step 3 ≈ 1–2 h; each harness matrix pass ≈ 20 min for six characters; device round = one owner-authorised build + 10 min of captures. Total ≈ one engineering day plus one device round.

**J. GO / NO-GO.** **GO (conditional)** for steps 1–3 as one coordinated change set, gated by the harness matrix and followed by the device round in step 4 — subject to owner approval, and with these conditions: no parameter sweeps; derived values only; no change outside the files in E; the device captures in H are obtained before defect 3 is declared fixed. NO-GO for any running-only patch, any sideways ball pull, and any change to camera, physics or generation.

**K. Confirmation.** Worktrees preserved: primary (`5e9bb11`, two untracked diagnostic files plus this document), consol (its two experimental modified files untouched), players (untouched). Nothing was reset, stashed, cleaned, checked out, committed, merged, pushed, built for distribution or published. The only files written in this phase: this document, the harness test source, and scratchpad scripts/renders.

---

## Repair results (2026-10-13, 01:1x–02:2x Z; local only, nothing committed, pushed or built)

Executed in the planned order with the faithful harness as the gate after each step. Evidence: scratchpad `step1_ham/`, `step2_ham/`, `step2b_ham/`, `step3_matrix/` (six characters, 156 captures), `step3_cable/`, `step3_ledge/`, `base_cable/` (Build 55 code+model on the same cable), composites `gs_compare/final_*.png`, `gs_compare/step*_zoom.png`, table `gs_compare/before_after.md`.

**Files changed (production):** `assets/hero/hamster.g3dj` (regenerated: sha256 `49361c3f…` → `b88930a3…`; 13,084 → 12,600 triangles, 8,440 → 8,508 vertices), `core/src/main/java/com/hotatticgames/climbup/render/HeroRig.java` (`gripLine`: rope target = the line's own depth `lineBack()/SCALE`; astronaut grip heights `ASTRO_GRIP_HI/LO` −0.20/−0.44; `ROPE_REACH` removed). **New tools:** `tools/hamster/rebase_arm_frames.py`, `tools/hamster/remove_paw_stub.py`. **Docs:** `docs/DECISIONS.md`, `ASSETS.md`, `tools/hamster/README.md`. **Harness (test source, untracked):** scripted `climb.startOn=cable|ledge` starts, because the autopilot never reaches a cable (they only generate above the first zone, so cable slices are chained like the tower) or hangs from a ledge.

**Step 1 (arm frames).** Offline: hamster `Run`/`Walk`/`Idle`/`Jump_Idle` upper-arm directions equal the bunny's at every sampled time (previously 75–96° from down in `Run`/`Jump_Idle`). Harness: run 24–70° (was 74–89°), jump 40–68° (was 90–102°), fall 60–101° (was 92–96°, both arms level), rope-leave 40–81° (was 90°/90°), idle 16°/16° (was 26°/26° under `armsRest`). Mesh and skinning intact in every state; the rope stretch now lengthens along the arm and both hamster hands land on the line even before step 3.

**Step 2 (stub).** 548 `LowerArm`-skinned triangles removed, four rims capped (22 + 10 edges per side). First attempt drew a dark ring at each arm end: the rim vertices belong to several texture islands, so a fan on them swept the atlas; fixed by giving the cap its own vertices with one fur texel's UV. After: `RESIDUAL LowerArm` absent in every capture (was 158 vertices per side, 0.31–0.54 from the ball), no stub or ring visible at 3× zoom.

**Step 3 (grip).** Measured on the six-character matrix (same simulated frames as the baseline):

| | before (Build 55) | after |
|---|---|---|
| hamster rope climb, hands on the line | left 0/5, right 5/5 (left 0.18–0.19 beside it) | **both 4/4** (left +0.05…+0.07, right −0.03…−0.01) |
| hamster rope grab (first 7 frames = cross-fade) | left 0/8, right 6/8 | left 1/4, right 3/4 — converging; both on by the climb |
| astronauts, face covered by the balls (camera-facing head) | grab 14–42 %, climb 10–49 % (Barbara worst) | **0 % in every rope frame, all four** |
| astronauts, hands on the line (climb) | 5/5 | 4/4, ball dx ≤ 0.03; balls 0.31–0.58 below the head centre (chest) |
| bunny rope climb | both on (left +0.07…+0.08) | both on (+0.04 / +0.04) |
| feet on the rope (x/z) | hamster ±0.09 / −0.37; astronauts ±0.05–0.08 / −0.93 | identical to 0.01 |
| running / jumping / falling / leaving (astronauts, bunny) | — | within 1–4° of before |
| cable traversal (Finn, bunny) | — | unchanged code path; hands on the cable, same pose |
| cable traversal (hamster) | head above the cable, hands at the cable beside the face | **identical** to before (joints within 0.03): pre-existing look, not touched |
| ledge hang (Finn, hamster, bunny) | — | hands on the edge, body hanging; hamster arms raised to the ledge, no stub |

**Remaining / noted.** (1) Hamster rope: in 1 of 4 climb frames the upper ball touches the lower muzzle (face metric 5 %, mean 2 %; eyes never covered) — the hamster keeps the bunny's grip heights by design; a hamster-only height would need an owner call. (2) Astronaut `fall`: Barbara 24 %, Rae 11 % of the camera-facing head behind a ball — the `Jump_Idle` clip's raised arm passing in front of the face while falling, unchanged from before and not a rope state. (3) The hamster's cable pose (head above the cable) is pre-existing and unchanged. (4) The hamster's legs, ears and spine keep their 12–49° frame offset (no visible symptom; feet-on-rope unchanged). (5) The body on the rope sits ~0.25 world units higher relative to the grab point for the astronauts (the hands moved down, `shift` keeps the hands at the sim's grip point); the sim is untouched. (6) Not verified on hardware: everything above is harness evidence on the Build-55-equivalent runtime; the four device captures listed in §H of the plan are still the gate for sign-off.

**Regression checks.** Core unit tests and desktop tests: see the chat report for the final counts. Simulation, camera, tuning, course generation, ball sizes and colours, `ballFront`, push-back distances, cable and ledge code: unchanged.

## Build 59 addendum — hamster running arms (2026-10-13, final)

**Owner report on build 57.** Everything else accepted; the hamster still runs with its arms extended outward from its sides, swaying rather than hanging beside the torso and swinging fore and aft like the bunny (the approved reference).

**Root cause (measured).** Step 1 made the hamster reproduce the bunny's world arm directions *exactly* — and the bunny's running upper arm points 43° outward from straight down (rest direction (0.66, −0.73, −0.19); 26–68° from down over the Run cycle, which is what the step-1 harness numbers "24–70°" were). On the bunny's narrow round body that is a hanging arm; on the hamster — upper-arm joints at x = ±0.965 on a torso 0.72–0.87 wide, 0.227-unit arm bones — the same directions are wings. A second, hidden problem: the bunny bends its forearms across its belly (mean sideways bend −0.06 in Run, −0.17 in Walk); once the hamster's upper arm hangs, that bend drives the hand ball (radius 0.27, centred 60 % down a 0.227 forearm whose elbow hangs only 0.19 outside the torso) into the body. Nothing is wrong with the frames any more; the bunny's running *pose* is wrong for this body.

**Fix (`tools/hamster/retarget_run_arms.py`, model file only; HeroRig untouched).** For clips `Run` and `Walk`, every `UpperArm.L/R` rotation key becomes `C_S · key · T`:
- `C_S` — one constant rotation in the Shoulder frame, equal to the world rotation taking the bunny's rest upper-arm direction onto straight-down-with-outward-tilt (27.1°). Pre-multiplying every key by one unit quaternion commutes with libGDX's key slerp (in-between frames are exact) and the correction co-rotates with the torso's own motion in the clip (verified: retargeted direction = `C_world` × original within 4.8° Run / 5.9° Walk, the torso bob).
- `T` — a twist about the upper arm's own axis (changes where the elbow bends, never where the upper arm points; least squares over the cycle: Run −11°/+6°, Walk −49°/+48°) that turns the forearm bend into the fore/aft plane pointing forward. Elbow angles are the bunny's exactly (Run 28–76°, Walk 16–33°).
- **Tilt 17°, derived not chosen:** the smallest whole degree at which the hand ball never presses more than a quarter of its radius into the body (every vertex not skinned to arms/head/ears) at any of 60 samples of either cycle, the script evaluating the clips as libGDX plays them (per-channel key lerp/slerp, parent·local globals, linear-blend skinning with the bind matrices). Worst case: Run −0.062 (23 % of the radius, right hand at the back-swing extreme against the lower back), Walk −0.041. The ball cannot clear the body outright without holding the arm out — the very look being removed.
- Untouched: LowerArm/Fist keys, every other clip, rest pose, bind matrices, geometry, weights; the script asserts exactly four tracks changed. Regeneration from the pristine model `5e9bb11`: `rebase_arm_frames.py` → `remove_paw_stub.py` (sha256 `b88930a3…`, the build-57 model, reproduced bit-for-bit) → `retarget_run_arms.py` (sha256 `65ed83d4…`).

**Offline (tool output).** Run upper arm 4–52° from down (was 26–68°), fore/aft swing −52..+39°; Walk 10–37° (was 36–52°); forearm bend sideways 0.00/−0.02 (was −0.06/−0.17), forward +0.67 / +0.35.

**Faithful harness (game camera, seed 11, slice 0, 29 consecutive frames = Run clip time 0.14–0.64 s of 0.71 s; `final_fix/run_sheet.png`, `zoom_sheet.png`).**

| | hamster before (build 57) | hamster after | bunny (same frames) |
|---|---|---|---|
| upper arm from straight down | 24–70° | **3–60°** (L 4–60, R 3–51; the range is the fore/aft swing) | 20–77° |
| forearm from straight down | — | 2–107° (forward at the front of the swing) | 24–96° |
| look (side view = normal gameplay) | arms out toward/away from the camera, swaying | arms hang beside the torso, elbows bent, balls beside the belly swinging fore and aft | reference |
| look (front view, `run_front`) | wings | arms at the sides, balls beside the torso | — |

**Regression (hamster, same simulated frames as the step-3 matrix).** idle 16°/16°, jump 40–68°, fall 60–101°, rope grab 97–110°, rope climb 95–110° with both hands on the line (L +0.05…+0.07, R −0.03…−0.01), rope leave 40–81°, feet on the rope x ±0.09 / z −0.37: all identical to build 57. Cable traversal and ledge hang (scripted `startOn`): the ARMS / HANDS / FACE diagnostics at the common capture frames (29 and 41) are byte-identical to build 57. No stub, no residual LowerArm vertices.

**Not covered.** `Walk` is verified offline only (the autopilot never walks); the first 7 frames of a run are the Idle→Run cross-fade (unchanged mechanism). Device confirmation remains the owner's.
