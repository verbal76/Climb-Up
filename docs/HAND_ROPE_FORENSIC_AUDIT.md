# Upwardly — Forensic audit: character hands / rope grip / face obstruction

**Scope:** every attempt since 12:00 PM Eastern (16:00 UTC) on 2026-10-12 to fix the character hands and rope-climbing appearance, with the runtime architecture, the render-vs-game discrepancy, and the evidence an independent reviewer needs. **No fix is proposed or implemented here.**

**Written:** 2026-10-12 ~22:30 UTC, from the repository, the working trees, the session transcript, the scratchpad render archive, the published Build 55 APK and the owner's uploaded screenshots. All times below are **UTC** (EDT = UTC−4; noon EDT = 16:00 UTC).

**Evidence labels:** `VERIFIED` = read directly from git history, source, a hash, a file timestamp or the transcript; `INFERRED` = follows from verified code but was not separately measured; `HYPOTHESIS` = plausible, not established; `UNKNOWN` = no evidence either way.

**Working-tree status at audit time:** branch `ccr-cb458cd1-w4dh5c`, HEAD `5e9bb11`. `/home/user/climb-up-consol` has two uncommitted modified files (`core/.../render/HeroRig.java`, `desktop/.../desktop/FullShot.java`, exact diff in §2.3); `/home/user/Climb-Up` is clean. Nothing was changed, reverted, cleaned, built, rendered, committed or pushed during this audit; the only write is this document.

---

## Section 1 — Complete chronological history

### 1.0 Pre-noon context (before 16:00 UTC; the system every later attempt built on)

| Time (UTC) | Commit | What | Files |
|---|---|---|---|
| 00:11 | `6907911` | Counter-scale the hamster's fists so its arm stretch stops ballooning the paws (`stretchArms` scale `(a,k,a)` on arms, `(1/a², 1/k², 1/a²)` on fists). Adds `maxHandScale()` + GripProbe balloon guard. | HeroRig, GripProbe |
| 02:24 | `0446e34` | "hands actually grip the rope/cable; idle arms rest" — `gripLine` aim at the line, `armsRest`; adds the **RigShot** harness. | HeroRig, RigShot, desktop/build.gradle |
| 02:56 | `f8ed876` | CI change. **Android Build 54 was built from this commit** (push-triggered; android.yml became manual-only at 03:28, `f81cba2`). Build 54 predates every ball-hand change: it ships the **original hamster paws, no balls**. | — |
| 14:29 | `bd1bd1d` | Rope grip: `gripLine` targets the rope's own line (`tx=0`, `ty = shoulder.y + len·h`, `tz = shoulder.z + ROPE_REACH 1.3`), `GRIP_HI 0.26 / GRIP_LO 0.02`, body pushed back off the rope by `lineBack()` (`ASTRO_BACK 0.82`, `BUN_BACK 0.37`); astronaut arm lengthen `ASTRO_STRETCH 0.35`, widen 0.08; `fistWorld()` diagnostic + FullShot `GRIP` print. | HeroRig, WorldRenderer (+1), FullShot |
| 15:15 | `7e0bf56` | **Ball hands introduced.** `tools/models/strip_hand_geometry.py` removes triangles whose dominant-weight vertex is on the LowerArm *and* projects beyond a wrist threshold along the elbow→hand axis: threshold **0.90** for the four astronauts, **0.19** for the hamster. `HeroRig`: `handBall = ham || astro`; two unskinned spheres (`ModelBuilder.createSphere(2,2,2,12,12)`) placed at `lerp(fist, lowerArm, 1−BALL_AT)` with `BALL_AT 0.6`; radius `BALL_RATIO 0.20` (hamster) / `BALL_ASTRO 0.11` (astronauts) × `SCALE / scale(0)`; colour near-black (astro) / beige `(0.93,0.90,0.85)` (hamster). Rendered after the body in `HeroRig.render`. | 5 × g3dj, HeroRig, strip tool |
| 15:26 | `8c6377d` | `BALL_ASTRO 0.11 → 0.09` (owner: astronaut balls too big). | HeroRig |
| 15:40 | owner | "This is your closest one, the hand sizing looks right. now the hands need to show in front of the rope … the legs should angle in so the feet touch the sides of the rope." (screenshot `images/5.png`: Finn on the rope, hands at head height, rope over the visor) | — |

**Mesh facts established for the strip (VERIFIED by parsing the git blobs, §2.4):** the hamster strip removed **598 of 13,560 triangles (4.4 %)**, all beyond |x| ≈ 1.29 — i.e. only the outer tips of the paws; the vertex array was untouched (8,428 verts before and after). The Finn astronaut strip removed **1,136 of 6,258 triangles (18 %)**, x-extent 2.32 → 1.60. **The hamster's paws were therefore never removed.** This is the root of the later "two hands" problem (§4, §9).

### 1.1 Attempt 1 — hands in front of the rope + feet rotate in (15:41–16:15; committed `c2a1b63` at 16:15:34)

- **Problem:** owner 15:40 (above).
- **Diagnosis at the time:** the balls sat at the arm's depth so the rope (a box at world z∈±0.055) drew over them; legs splayed because `climbLegs` only draws knees up.
- **Correction:** new per-frame `ballFront` (`BALL_FRONT 0.17`, +z toward the camera on a vertical rope) and `ROPE_BALL_X 0.3` (ball x multiplied toward the rope's line), applied to **both astronauts and hamster**; new `feetToLine()` (aim thigh then shin inward with `aimTo`, `KNEE_X` 0.14→0.11, `FOOT_SHUFFLE 0.12`); `footInShin` bind capture; diagnostic `footWorld()` + FullShot `FEET` print.
- **Temporary debug added and removed before commit:** `climb.dbgFeet` prints in `feetToLine`, instrumentation inside `commitBone`, a `climb.ballRed` red-ball tint (render `ropered/`, 16:07). None remains (verified by grep at 16:14 and now).
- **Renders:** FullShot `ropeshots/` (15:49, 15:54, 15:56, 15:57, 15:58, 16:05, 16:11, 16:14), `ropered/` (16:07), `final/feet_fixed.png` (16:14, sent). GripProbe PASS 16:13.
- **Measured:** Finn feet ±0.06/0.07, hamster ±0.09 (rope half-width ≈0.055 per commit note).
- **Result:** owner 16:10 "hands look good, can you just rotate the feet inward"; 16:17 "the feet look good, but the hamsters hands blend into its body … just a light beige blob". **Approved on renders that were not camera-faithful (§5).**

### 1.2 Attempt 2 — brown hamster balls (16:17–16:32; committed `af0e20b` 16:32:14)

- **Correction:** hamster ball colour `(0.93,0.90,0.85) → (0.46,0.29,0.17)`. Colour only.
- **Renders:** RigShot `tint/` (16:31), FullShot `tintfs/ham_brownpaws.png` (16:32, sent).
- **Side investigation (16:18–16:35):** whisker clipping — found whisker vertices skinned to the arm bones; no `.blend`/Blender available; `AskUserQuestion` 16:35 (owner later chose "re-bind to head").
- **Result:** owner 17:13–17:14: "hamster hands look twice the size they should be", "the brown on the hands is too dark", "lets shoot for the yellowish tan color on the feet" (swatch `images/6.png`).

### 1.3 Attempt 3 — hamster balls half size, feet-tan colour (17:13–17:17; committed `3280b87` 17:17:25)

- **Correction:** `BALL_RATIO 0.20 → 0.10`; colour → `(0.78,0.51,0.34)` (the hamster texture's fur/foot diffuse). Astronauts unchanged.
- **Renders:** FullShot `handfix/ham_hands.png` (17:16, sent).
- **Result:** owner 17:30: "the hands on that pic look good, but the arms going to them have issues, 1 is mangled and twisted … one arm is ripped of the hand. and in the latest both arms are t posed" (the T-pose came from attempt 4).

### 1.4 Attempt 4 — re-skin whiskers to the head (17:19–17:29; committed `f0cd1ef` 17:29:09) — **REGRESSION, reverted in 1.5**

- **Correction:** `tools/hamster/reskin_whiskers.py`: every arm-dominant vertex with |x| > 1.0 re-weighted 100 % to the head bone (1,030 verts). Prototypes in scratchpad: `reskin_proto.py` (thresholds 1.1, 1.0), `reskin_push.py` (±z pushes).
- **Renders:** RigShot `wh11/`, `base/`, `pz/`, `nz/`, `wh10/` (17:19–17:27); GripProbe PASS 17:28; FullShot `final2/ham_final.png` (17:29, sent).
- **Failure:** the forearm/paw mesh also extends past |x| = 1.0, so it was re-bound to the head → arms splayed into a T-pose (owner 17:30). Model blob `85c701c3`.

### 1.5 Attempt 5 — revert the re-skin; ball back on the wrist (17:31–17:44; committed `cf6a9e1` 17:44:39)

- **Diagnosis:** (a) the re-skin regression; (b) pre-existing: with `ROPE_BALL_X` the ball was pulled to the rope while the hamster's arm end (fist at x ≈ ±0.15) stayed out → "ripped off".
- **Correction:** `hamster.g3dj` restored to the `3280b87` blob (`98b27df9`); `reskin_whiskers.py` deleted; `ballFront` gated to astronauts only, so the hamster's ball fell back to the wrist path (`BALL_AT` lerp, no pull, no front).
- **Renders:** `armdiag/` (17:34), RigShot `armfix/` (17:37), FullShot `armfs/` + `seam.png` (17:38), `ba90/` (`BALL_AT=0.9` test, 17:42, no improvement), **`noball/` (`climb.handBall=0.001`, 17:45)**.
- **Decisive finding (17:47, VERIFIED):** with the balls shrunk to nothing, the arm ends show the hamster's **original white cupped paws — never removed**. The hamster had been rendering **two hands per arm** (its own paw + the tan ball). `ham_fork.png` sent; `AskUserQuestion` 17:48.
- **Result:** owner 18:02: keep the tan balls; "bring both hands in more to the rope and it's perfect".

### 1.6 Attempt 6 — second paw strip by texture colour (18:02–18:03; **never committed**)

- Scratchpad `strip_paw.py`: remove triangles whose vertices are arm-skinned, |x| > 0.95 **and** sample white in `hamster.png`. Rendered `paw1/` (18:03), then `git checkout -- assets/hero/hamster.g3dj` **in the same command** (VERIFIED from the transcript), so only `paw1` used it.
- **Abandoned** (18:04): "removed most of the mitt but the ball sits central while the arm end is outboard".

### 1.7 Attempt 7 — sideways pull of the hamster balls (18:06–18:11; committed `b4eba68` 18:11:08)

- **Correction:** new field `ballPullX` (reset to 1 each frame); hamster on a vertical rope: `ballPullX = lerp(1, HAM_BALL_X, grip)` applied in the wrist (`BALL_AT`) path — sideways only, no forward push; `HAM_BALL_X` tested 0.3 (`pull30/`, 18:07) and **0.12** (`pull12/`, 18:09) → 0.12 chosen. Astronauts unchanged.
- **Result:** owner 18:38: "You put them behind the rope again, why and it's hard to test the arm placement when your rendering shows sky through the arms".

### 1.8 Attempt 8 — fill the open cup-paws + hamster balls in front like the astronauts (18:41–18:59; committed `8eafc8e` 18:59:00)

Sequence of sub-attempts (all VERIFIED from the transcript):
1. 18:48 ball at the fist's own x **with** `ballFront` for the hamster (`front/`, 18:49) → paws further out than expected; see-through remains.
2. 18:51 double-sided material (cull off) on the hamster (`nocull/`, `final3/`) → sky-through gone but z-fighting "weird lines" (owner 18:5x: "pretty close" + weird lines). **Removed.**
3. 18:55 `tools/hamster/fill_holes.py`: cap every open boundary loop with a fan to a centre vertex (12 loops, +12 verts, +122 tris); first run back-facing (`fill/`), `FAN_FLIP=True` (`fill2/`, 18:57).
4. HeroRig: hamster now gets `ballFront` **and** `ballPullX = ROPE_BALL_X` (identical treatment to the astronauts); `HAM_BALL_X` removed.
- GripProbe PASS 18:58. Committed with the model (blob `fdf39508`, **the model in Build 55**).
- **Result:** owner 18:59 (circled right paw, uploads `e8ee77ae` 18:59 / `92580828` 19:02): "Doesn't look connected, or a bad mesh fill? Everything else looks fine, do not change anything else".

### 1.9 Attempt 9 — close the ball-to-paw gap (19:00–19:28; committed `4ea89c3` 19:28:02) ← **Build 55 code state for the hands**

- 19:01 `fill_holes.py` centre-UV tweak + re-fill from the `b4eba68` blob (`fill3/`) → tan flap unchanged → that tweak was **reverted** at 19:17 (`git checkout 8eafc8e -- assets/hero/hamster.g3dj tools/hamster/fill_holes.py`; comparison `fill_compare.png` showed no visible difference).
- 19:05 `ballPullX = astro ? ROPE_BALL_X : climb.hamPull (default 0.3)` made tunable. (Session context compaction 19:07.)
- 19:10 first manual-classpath FullShot: **hamster fists measured at x ≈ ±0.15, z ≈ 0** ("the fists already land near the rope; pull 0.3 yanks the ball to ±0.04 and detaches it from the paw").
- 19:12 sweep `hamPull` 0.3 / 0.6 / **1.0** (`measure/`, `pull_0.6`, `pull_1.0`, `pull_compare.png`, `zoom_right.png`) → 1.0 chosen.
- **Committed code:** `if (astro) ballPullX = ROPE_BALL_X;` (hamster keeps 1: ball on its own fist, pushed forward 0.17 only). GripProbe PASS; `RigMatrixGuardTest`, `RigScaleDriftTest`, `ClimbPhaseTest` green (19:27).
- Sent 19:31: `ham_gap_fix.png` (crop ×2.3), `SHEET_rope.png` (FullShot, all six), `SHEET_cable.png` (**RigShot**, synthetic bar — no FullShot seed produced a cable crossing).
- **Result:** owner 19:51: "Notice how the hamster is the only one with a different grip" (uploads `826d1c70`, `90c717cd`).

### 1.10 Attempt 10 — the "wings" rework (19:52–20:29) — **reverted**; only the bunny cable fix committed (`b0f7135` 20:29:27)

- **Diagnosis 19:53 (`grip_diff.png`):** the hamster's upper arms splay out into "wings" and its hands clasp at chest height; the others keep arms tucked and reach up.
- **Code rework (uncommitted at the time):** all characters routed through a line-specific stretch; hamster `HAM_WIDEN`/`HAM_REACH`/`HAM_REACH_CABLE` tunables; bunny stretch. Sweeps (all FullShot/RigShot, consol classes first): `t0/` (19:57), `real/` (20:00), `htune/` reach 0.15/0.25/0.35 (20:02–20:03: "reach barely moves the hands, pinned at ±0.25"), `hwiden_-0.3/-0.5/-0.7` (20:05: **negative widen pushes the hands wider**), `hw/` widen 0.3/0.5/0.08 (20:06: "0.08 looks best — no wings, hands a touch wide"), `final2/` + `FINAL_rope/FINAL_cable` (20:09), GripProbe **FAIL** (bunny real paws BALLOONED, `maxHandScale 2.06`) → bunny uniform stretch → PASS (bunny 1.04, hamster 1.54), `FINAL2_*` (20:17–20:18), `hamster_wings_fix.png` (20:19, sent 20:20), `handface_cmp.png` (20:22).
- 20:20 `AskUserQuestion` on facing. Owner 20:22–20:25: "They all face the camera, you just put the astronauts hands in front of their skinny faces" and "you just spread his hands off the rope again" (uploads `6fb01eda`, `0906e0df`).
- 20:25 **`git checkout -- HeroRig.java`: the entire widen/reach rework was discarded.** Only the bunny cable fix was re-applied: `else if (!astro && line && cable) stretchArmsBy(grip, BUN_STRETCH, BUN_STRETCH)` with `BUN_STRETCH 0.4` (uniform, so the bunny's real paws are not distorted). GripProbe PASS; hamster fists back at ±0.15.
- Renders `rope3/` (20:18), `finR/`/`finC/` → **`DONE_rope.png`** (FullShot) and **`DONE_cable.png`** (RigShot), sent 20:29. (`hamster_hands_onrope.png`, 20:27: "spread" = the discarded experiment vs "on rope" = committed.)
- **Result:** owner 20:52 "For now I except all of those renders". Those renders were produced by FullShot **without** the game camera (§5); at 21:47 the owner showed `DONE_rope`'s HAMSTER cell on his phone (upload `198ba9af`) and said "My screenshot looks nothing like this".

### 1.11 Builds and name (20:53–21:46) — no hand changes

- 20:54 `AskUserQuestion` build → owner: Android + Windows/Linux. Dispatch refused (403, no `actions:write`) until the owner granted it (21:12).
- 21:01 `d69890f` display name "Upwardly" (strings.xml, workflow titles, DECISIONS). 21:13 **Build 55** (Android run #55, `d69890f`) and Desktop Build 27 dispatched; APK published 21:19. 21:14 `14b5268` CLAUDE.md dispatch note.
- 21:20 owner: cluttered menu (Build 54 title screen, upload `f1ce490c`). 21:31 "This is live gameplay on the newest build hamster is still broken" (Build 54 — `f8ed876`, no ball hands at all). 21:35 "You sure about that? Homeboy" (Build 55 confirmed via App info; hamster still broken; title still CLIMB UP; uploads `389b3f53`, `bdb4517c`, `a9c6cb72`). 21:38 "Looks like you missed some stuff" (nine uploads incl. **`e893336e` — Build 55 hamster on the rope, the ground truth for §5/§7**).
- 21:42 `5e9bb11` in-game title → "UPWARDLY" (`Platform.MOBILE.title()`), `PlatformSeamTest`, APK filename. 21:43 **Build 56 dispatched from `5e9bb11`; cancelled at 21:46** after the owner's "Do not push a build without the hamster fix" (run 38312369071, conclusion `cancelled`, VERIFIED).
- Menu-declutter work (`TitleLayout.java` rewrite, `TitleScreen` edits) was started 21:32–21:36 and **reverted to HEAD** at 21:39; WIP copies saved in scratchpad `menu_wip/`.

### 1.12 Attempt 11 — the harness camera (21:46–21:55; **uncommitted**)

- 21:46 `honest/` FullShot render (still no camera snap). 21:50 **found that FullShot never calls `WorldRenderer.snapCamera()`** (PlayScreen does, line 139). 21:51 added `wr.snapCamera(sim)` at the top of `FullShot.capture()` (uncommitted). `truecam/` (21:51): "now matches your game — hands bunched at the chest"; `truecam3/` + `truecam_cmp.png` (21:53, sent 21:54).
- 21:54 HeroRig edit (uncommitted): `ballPullX = astro ? ROPE_BALL_X : Float.parseFloat(System.getProperty("climb.hamPull", "0.3"))` — i.e. the hamster is pulled to the line again (numerically the same as attempt 8, which the owner rejected at 18:59). `fix03/` + `fix03_cmp.png` (21:55). Context compaction 21:57.

### 1.13 Attempt 12 — post-compaction parameter sweep A–I (21:58–22:10; **uncommitted; stopped by the owner at 22:11**)

Classpath `cp_consol.txt` (consol classes first) and `fs.sh` built 22:00–22:01; HeroRig recompiled with `javac` at 22:03 into `core/build/classes/java/main`.

| Label | Dir | Time | `hamWiden` | `hamReach` | `hamBack` | `hamPull` | Hamster fists L / R (x, y, z) | Judgement at the time |
|---|---|---|---|---|---|---|---|---|
| base012 | `base012/` | 22:01 | 0.7 (default) | 1.5 | 0.37 | **0.3** (uncommitted default) | (0.17, 3.18, −0.03) / (−0.14, 3.11, −0.01) | "wings + balls merged at the belly". Bunny (0.05,3.17,−0.01)/(−0.02,3.12,0.00); Finn (−0.03,3.24,−0.06)/(0.05,3.05,−0.03) |
| A | `A/` | 22:03 | 0.08 | 0.35 | 0.82 | 0.3 | (0.25, 3.15, **−0.67**) / (−0.24, 3.14, −0.65) | hands far behind the rope — fail |
| B | `B/` | 22:05 | 0.08 | 1.5 | 0.37 | 0.3 | (0.31, 3.16, −0.17) / (−0.29, 3.13, −0.14) | "wings gone, two paws straddle the rope" — declared "the fix" at 22:10 (**not accepted by the owner**) |
| F | `F/` | 22:06 | 0.08 | 0.35 | 0.60 | 0.3 | z −0.45 / −0.43 | behind the rope — fail |
| G | `G/` | 22:07 | 0.08 | 0.9 | 0.45 | 0.22 | z −0.28 / −0.26 | behind the rope — fail |
| H | `H/` | 22:07 | 0.08 | 1.5 | 0.37 | 0.15 | same fists as B (pull moves only the ball) | ≈ B |
| I | `I/` | 22:07 | 0.08 | 2.2 | 0.28 | 0.20 | (0.32, 3.17, −0.05) / (−0.30, 3.12, −0.02) | ≈ B |

Candidates "C", "D", "E" were named in reasoning but **never run**. Crops: `zoom_cmp`, `closeup_cmp`, `b_cmp`, `sweep_cmp`, `B_tight` (×7). No code implements B: `HAM_WIDEN`'s default in the working tree is still 0.7 (§2.3).

**Harness caveat for every row (VERIFIED, §5):** these renders used the uncommitted `hamPull 0.3` code (not Build 55's `pull 1`), and FullShot updates the rig only once per capture.

---

## Section 2 — Source history and diff audit

### 2.1 Relevant commits since noon (all on `ccr-cb458cd1-w4dh5c`, all pushed, CI green)

| UTC | SHA | Files | Hands/rope relevance |
|---|---|---|---|
| 16:15:34 | `c2a1b63` | HeroRig (+87/−5), FullShot (+4) | ballFront/ROPE_BALL_X, feetToLine, footWorld |
| 16:32:14 | `af0e20b` | HeroRig | hamster ball brown |
| 17:17:25 | `3280b87` | HeroRig | ball 0.20→0.10, tan |
| 17:29:09 | `f0cd1ef` | hamster.g3dj, reskin_whiskers.py (new) | whisker re-skin (regression) |
| 17:44:39 | `cf6a9e1` | hamster.g3dj (restored), HeroRig, reskin_whiskers.py (deleted) | gate ballFront to astro |
| 18:11:08 | `b4eba68` | HeroRig | ballPullX + HAM_BALL_X 0.12 |
| 18:59:00 | `8eafc8e` | hamster.g3dj, HeroRig, fill_holes.py (new) | fill cups; hamster = astro treatment |
| 19:28:02 | `4ea89c3` | HeroRig | hamster pull back to 1 |
| 20:29:27 | `b0f7135` | HeroRig | bunny uniform cable stretch |
| 21:01:01 | `d69890f` | android.yml, windows.yml, strings.xml, DECISIONS | name only — **Build 55 source** |
| 21:14:23 | `14b5268` | CLAUDE.md | docs only |
| 21:42:57 | `5e9bb11` | Platform.java, PlatformSeamTest, android.yml | title only — Build 56 (cancelled) |

Full per-commit HeroRig/FullShot diffs are saved at `scratchpad/audit/herorig_diffs.txt` (634 lines) and reproducible with `git show <sha> -- core/src/main/java/com/hotatticgames/climbup/render/HeroRig.java`.

### 2.2 Superseded / reverted implementations (VERIFIED)

- Whisker re-skin by |x| threshold (`f0cd1ef`) → reverted (`cf6a9e1`).
- `HAM_BALL_X` sideways-only pull (`b4eba68`) → removed (`8eafc8e`).
- Double-sided hamster material (18:51) → removed before `8eafc8e`.
- Hamster pulled to the line with `ROPE_BALL_X` (`8eafc8e`) → reverted to pull 1 (`4ea89c3`) → **re-introduced as `hamPull 0.3` in the uncommitted working tree (21:54)**.
- The entire widen/reach rework + all-character line stretch (19:56–20:25) → discarded by `git checkout`; survives only as the uncommitted `HAM_WIDEN`/`HAM_BACK` tunables (22:02) and the B-sweep parameters.
- `fill_holes.py` centre-UV variant (19:01) → reverted (19:17).
- `strip_paw.py` (18:02) → reverted in the same command; never committed.
- Menu rework (21:32–21:36) → reverted to HEAD; copies in `scratchpad/menu_wip/`.

### 2.3 Uncommitted modifications (exact; preserved, not reverted)

`core/src/main/java/com/hotatticgames/climbup/render/HeroRig.java` (vs `5e9bb11`):

```diff
@@ -457,7 +457,7 @@
                 if (!cable && (astro || ham)) {
                     feetToLine(climbPhase, moving);
                     ballFront = BALL_FRONT * grip;
-                    if (astro) ballPullX = ROPE_BALL_X;                 // hamster keeps ballPullX = 1
+                    ballPullX = astro ? ROPE_BALL_X : Float.parseFloat(System.getProperty("climb.hamPull", "0.3"));
@@ -519,8 +519,9 @@
-    /** The hamster's arms are tiny stubs: ... */
-    private void stretchArms(float grip) { stretchArmsBy(grip, 0.7f, HAM_REACH); }
+    /** The hamster shares the astronauts' skeleton; ... */
+    private void stretchArms(float grip) { stretchArmsBy(grip, HAM_WIDEN, HAM_REACH); }
+    private static final float HAM_WIDEN = Float.parseFloat(System.getProperty("climb.hamWiden", "0.7"));
@@ -562,7 +563,8 @@
-    private float lineBack() { return astro ? ASTRO_BACK : BUN_BACK; }
+    private static final float HAM_BACK = Float.parseFloat(System.getProperty("climb.hamBack", "0.37"));
+    private float lineBack() { return astro ? ASTRO_BACK : (ham ? HAM_BACK : BUN_BACK); }
```

Behavioural effect of the uncommitted HeroRig: **only the first hunk changes behaviour** (hamster ball x multiplied by 0.3 on a vertical rope — the 18:59-rejected geometry); the other two hunks keep the committed defaults (0.7, 0.37) and only add `-D` tunables. Note the `System.getProperty` call sits inside the per-frame path (line 460) — a temporary-debug pattern, not production code.

`desktop/src/test/java/com/hotatticgames/climbup/desktop/FullShot.java`:

```diff
@@ -87,6 +87,7 @@
             void capture(WorldRenderer wr, Sim sim, float dt, float time, FrameBuffer fbo, int ch, String label) {
+                wr.snapCamera(sim);   // position the view the way the real game does (PlayScreen snaps the camera) ...
                 fbo.begin();
```

The compiled class `core/build/classes/java/main/.../HeroRig.class` (22:03) and `desktop/build/classes/java/test/.../FullShot.class` (21:51) reflect the **uncommitted** sources.

### 2.4 Model assets — blob history of `assets/hero/hamster.g3dj` (VERIFIED)

| Commit | Blob | Size | Mesh (`part_ham`) | Note |
|---|---|---|---|---|
| `f8ed876` (**Build 54**) | `40001a78…` | 1,698,085 | 8,428 verts, 40,680 idx (13,560 tris), x∈[−1.40, 1.40] | original paws |
| `7e0bf56` / `cf6a9e1` / `b4eba68` | `98b27df9…` | 1,942,626 | 8,428 verts, 38,886 idx (12,962 tris), x∈[−1.29, 1.29] | strip removed 598 tris (tips only); file larger because the tool re-serialised with spaces |
| `f0cd1ef` | `85c701c3…` | 1,683,457 | (re-skinned; reverted) | regression |
| `8eafc8e` → `d69890f` (**Build 55**) → `5e9bb11` → working tree | `fdf39508…` | 1,692,202 | 8,440 verts, 39,252 idx (13,084 tris), x∈[−1.29, 1.29] | cups capped (+12 verts, +122 tris) |

Skeleton (same in all versions): 31 nodes, 28 skinned bones, `Fist.L/R` present, **no finger bones**; `Foot.L/R` and `PoleTarget.L/R` are Root-parented helpers. 18 clips (Idle, Walk, Run, Jump, Jump_Idle, Jump_Land, Wave, …). Single material `mat1` (textured, `hamster.png`).

Astronaut (`astronaut_finnthefrog.g3dj`): `2bd06e71…` original → `c2027cf8…` after `7e0bf56`, unchanged since: `p44_0` 5,695 verts, 18,774 → 15,366 idx (1,136 tris removed), x-extent 2.32 → 1.60; separate `p22_0` mesh on `n22_Pistol` (its parts are cleared at runtime, HeroRig:302). 46 nodes, 21 skinned bones, finger bones Pinky/Middle/Index (×3) and Thumb (×2) per hand, **no `Fist` node** → HeroRig uses `_Middle1.L/R` as the hand node (HeroRig:155,158). The other three astronauts were stripped with the same tool/threshold (not individually re-parsed here).

### 2.5 Other working-tree facts

- Temporary debug code still present in production source: none beyond the `System.getProperty("climb.hamPull")` in the uncommitted hunk. `DEBUG` (`climb.heroDebug`), `NO_RECLAMP/NO_POSE/NO_BLEND`, `CYCLE` (`climb.animCycle`) are pre-existing, default-off diagnostic switches.
- 30 `climb.*` system-property tunables in HeroRig (list: `grep -n System.getProperty core/.../HeroRig.java`), including `handBall 0.10`, `handBallAstro 0.09`, `ballFront 0.17`, `ropeBallX 0.3`, `ballAt 0.6`, `hamReach 1.5`, `bunStretch 0.4`, `astroStretch 0.35`, `astroBack 0.82`, `bunBack 0.37`, `ropeReach 1.3`, `gripHi 0.26`, `gripLo 0.02`, `kneeX 0.11`, `footShuffle 0.12`, `camDist 9.6` (WorldRenderer) — plus the three uncommitted (`hamPull`, `hamWiden`, `hamBack`).
- No camera-positioning or animation-timing change was made to production code today (WorldRenderer's only change was +1 line in `bd1bd1d`, pre-noon; `ClimbPhaseTest`/travel-per-frame change is `0698fca`, 2026-10-11).
- 15 git worktrees exist (`git worktree list`); only `Climb-Up` and `climb-up-consol` are on the working branch. `climb-up-players` (exp/feature-players, 2026-10-11) is referenced by the old `scratchpad/cp.txt` classpath and by `shot.sh` — **not used for any of today's grip renders** (every FullShot/RigShot command put the consol classes first; the single exception at 19:10:49 was re-run eleven seconds later with the correct order).

---

## Section 3 — Current runtime architecture (the shipped/playable path)

**A. Character selection and loading.** `g.settings.character` (persisted settings) → `TitleScreen.java:93` (`world.setCharacter(g.settings.character); g.persist()`) and `PlayScreen.java:136` (`new WorldRenderer(g.tuning, course, g.models, g.settings.quality, g.settings.character)`), `:170` (`applySettings` → `world.setCharacter`). `WorldRenderer.java:85-87` constructs `hero = new HeroRig(character)`; `:142 setCharacter` disposes and re-creates it. `Characters.java`: 0 BUNNY `hero/hero.g3dj`, 1 HAMSTER `hero/hamster.g3dj`, 2–5 astronauts `space/astronaut_*.g3dj`; `scale()` 0.50 astronauts / 0.37 others; `astronaut(c)=c>=2`; `textured(c)=c==1`.

**B. Model assets.** Loaded in `HeroRig.java:295` with `G3dModelLoader` from `Gdx.files.internal(Characters.model(c))`. The APK packages `../assets` directly (`android/build.gradle` `sourceSets.main.assets.srcDirs`). **VERIFIED: Build 55's packaged `assets/hero/hamster.g3dj` (sha256 `49361c3f…`, 1,692,202 B) and `astronaut_finnthefrog.g3dj` (`f3846bdd…`) are byte-identical to the git blobs at `d69890f` and to the working tree.** The OTA system delivers `tuning.json` only (`OtaConfig` "no executable code and no assets"; `OtaClient` allowlist) — it cannot replace models.

**C. Original hand geometry.** Hamster: paws are part of the single skinned mesh, weighted to `LowerArm.L/R` (per `strip_hand_geometry.py` and the 16:24–16:35 vertex analysis); after the strip+fill they are **cup shapes minus tips, capped** — still present (§2.4). Astronauts: splayed "glove" geometry skinned to `LowerArm` with vestigial finger bones; triangles beyond 0.90 along the elbow→hand axis removed; what remains of the gloves in-game is **UNKNOWN** (not characterised).

**D. Replacement hands.** Two unskinned sphere `ModelInstance`s per rig (`ballL/ballR`, HeroRig:312-313), radius `ballRad = (astro ? 0.09 : 0.10) × SCALE / 0.37` → **hamster 0.100, astronauts ≈0.122 world units** (the astronaut balls are slightly larger). Colours: astronaut `(0.12,0.12,0.14)`, hamster `(0.78,0.51,0.34)`, with 12 % emissive. Drawn after the body in `HeroRig.render` (:715-718).

**E. Skeleton/bone attachment.** `collect()` (:153-160) matches node-id suffixes: `_UpperArm`, `_LowerArm`, `_Fist` **or** `_Middle1` (hand), `_UpperLeg`, `_LowerLeg`, `_Foot`. The ball is placed at the hand node's **world** position (`inst.transform × fist.globalTransform`, `placeBall` :491-500), then (vertical rope, astro or hamster) `x *= ballPullX`, `z += ballFront`; otherwise lerped 40 % toward the LowerArm (`BALL_AT 0.6`). The balls therefore follow the bones but are **not** skinned and are not affected by the fist counter-scale.

**F. Animation.** `AnimationController` on the clips (:315, `play()` :325-329 with cross-fade unless `climb.noBlend`). State machine `pick()` (:347-364): `ROPE → CLIMB` when |y − py0| > 0.002 else `HANG`; `HANG/CLIMB/SHIMMY` play **`Jump_Idle`** (loop, blend 0.12 s) (:415). `climbPhase` advances by real travel (:392); `lineMove` eases 0↔1 at 14/s (:394). Procedural posing on top: `reclampLimbScales` (:142-152, every frame after `ac.update`), `stretchArmsBy` (:530-538), `gripLine` (:570-605), `feetToLine` (:630-642) / `climbLegs` (:608-617), `raiseArms`, `flail`, `armsRest`, `slipFlail`.

**G. Rope-climbing logic.** `Sim.Mode.ROPE` (sim); `WorldRenderer` draws each rope as a box `ModelInstance` (`ropeInst`, :981-997) at `(R·sin φ, midY, −R + R·cos φ)`, φ = `dsWrap(midS, camS)/R` → **(0, midY, 0)** for the rope the hero is on (half-width ≈0.055). The hero is drawn at `x=0, z=heroZ (0 on a rope)`, φ=0 (`drawPlayer` :1019-1039).

**H. Hand positioning / grip calculations (vertical rope).** Per frame in `HeroRig.update`: body transform set (:437-438) → `ac.update` (:439) → `reclampLimbScales` (:440) → stretch: hamster `stretchArmsBy(grip, 0.7, 1.5)` on **every** grip (:451), astronaut `(0.08, 0.35)` rope only (:452), bunny `(0.4, 0.4)` cable only (:453) → `gripLine` (:456): each arm aimed (weight 1) from the shoulder at `(0, shoulder.y + len·h, shoulder.z + 1.3)` in model space, `h` alternating between `GRIP_HI 0.26` and `GRIP_LO 0.02` with `climbPhase` → `feetToLine` (:458) → `ballFront = 0.17·grip`, `ballPullX` (:459-460) → body re-placed with `shift` (hand height to `sim.y + handHeight`) and pushed back by `behind = lineBack()·grip` (astro 0.82, bunny/hamster 0.37) (:466-476) → `placeHandBalls` (:479). Measured in FullShot (post-camera-fix, uncommitted `hamPull` code; §1.13 base012): hamster fists x ±0.15, y 3.11–3.18, z ≈ 0; bunny ±0.04; Finn ±0.04 with 0.19 vertical spread.

**I. Camera and projection.** `WorldRenderer.java:40` `PerspectiveCamera(40°, 16, 9)`; `:467` FOV 40 + kick; `:477-480` position `(0.1·shake, camY + 2.2, 9.6 + camPull)`, lookAt `(0, camY + 1.15, −0.4)`, up `(0,1,0)`; `camY` smoothed toward `py + 0.3 + lead` at 7–10/s (:443-446), snapped on teleport (:445) and by `snapCamera()` (:147, called from `PlayScreen` :139, :156, :163, :811). `CAM_DIST` 9.6 (`climb.camDist`). Vertical FOV is fixed, so a 20:9 phone shows the same hero height as a 16:9 harness with more width.

**J. Runtime transforms that can override hand placement.** (1) the clip pose every frame (`ac.update`); (2) `reclampLimbScales` (scale reset); (3) stretch scaling of arm bones — **which also scales any mesh skinned to the LowerArm, i.e. the residual paws (INFERRED)**; (4) `gripLine` rotation-only re-aim (commitBone keeps scale); (5) the body transform re-set with `behind`; (6) `placeBall` offsets. Nothing hides hand node parts; the only part removal at runtime is the astronaut pistol (:302).

**Per-frame order (VERIFIED):** `WorldRenderer.render` (:435): camera → background → `batch.begin(cam)` (:501) → clouds → element loop incl. ropes (:510-520) → hazards (:521) → space near → `drawPlayer` (:531: `hero.update` then `hero.render`) → `batch.end` (:533). Rope/hand occlusion is depth-tested within one batch, so it is purely geometric (ball z vs rope z).

---

## Section 4 — All previous hand-replacement implementations

| # | Commit | Original hands hidden how | Fingers/paws actually removed? | Ball creation / size | Attachment | Follows animation | Both hands on rope | Face | Why it failed the requirement |
|---|---|---|---|---|---|---|---|---|---|
| I1 | `7e0bf56` (+`8c6377d`) | Mesh strip beyond wrist threshold (0.90 astro / 0.19 hamster), LowerArm-dominant verts only | **No** — hamster 4.4 % of tris, astronauts 18 %; paws/gloves largely remain | sphere; hamster 0.20·scale, astro 0.11→0.09 | lerp fist→lowerArm (0.6) | yes (bone-driven) | not measured | UNKNOWN | residual geometry; balls behind the rope |
| I2 | `c2a1b63` | same model | same | same | on rope: x·0.3, z+0.17 (both) | yes | balls on the line (render) | UNKNOWN | hamster: ball pulled off its arm → "ripped off" (owner 17:30) |
| I3 | `cf6a9e1` | same | same | 0.10, tan | hamster back to wrist lerp (no pull/front) | yes | no (balls at ±0.15 at wrist depth) | — | two visible hands per arm discovered (17:47) |
| I4 | `b4eba68` | same | same | same | hamster x·0.12 at wrist depth | yes | balls on line but behind rope | — | behind rope; see-through cup paws (owner 18:38) |
| I5 | `8eafc8e` | strip + **cups capped** (`fill_holes.py`) | still no — caps make the paws solid, not invisible | same | hamster x·0.3, z+0.17 (= astro) | yes | balls on line, in front | — | ball detached from the (still visible) paw → sky gap (owner 18:59) |
| I6 | `4ea89c3` (**Build 55**) | same | same | same | hamster x·1.0, z+0.17 (ball on own fist) | yes | balls at ±0.15 beside the line | rope passes over the hamster's face | owner: hamster "only one with a different grip"; in-game: balls bunched at chest, paws visible beside them |
| I7 | uncommitted 21:54 | same | same | same | hamster x·0.3 (re-creates I5) | yes | — | — | not validated by the owner; same geometry he rejected at 18:59 |
| I8 | sweep B (params only) | same | same | same | + widen 0.08 instead of 0.7 | — | harness only | — | never implemented, not accepted |

**Changes that solved one problem and created another:** `ROPE_BALL_X` on the hamster (hands on the line → detached from the arm); whisker re-skin (whiskers → T-pose); double-sided material (see-through → z-fighting lines); halving the ball (size → wrist seam/crescents exposed, 17:39); pulling to pull 1 (gap closed → hands off the line / bunched); the widen rework (wings gone → "spread his hands off the rope").

---

## Section 5 — Render versus actual game discrepancy

### 5.1 How each preview was generated (VERIFIED from source and transcript)

| Harness | Scene | Character asset | Camera | Rig update cadence | Animation state / timestamp | Rope | Resolution | Real gameplay scripts? | Real runtime assembly? |
|---|---|---|---|---|---|---|---|---|---|
| **FullShot** (`:desktop:fullShot` / `java …FullShot`) | real `WorldRenderer(t, course, models, quality=2, ch)` on generated chunks (seed 11, slices 0–15) driven by `Autopilot.Driver` | same loader/path as the game | **real camera, but `snapCamera` never called before 21:51** → `camY` starts at 0 and `render()` smooths it by only ~12 % per call — and `render()` is called **once per capture** (the frame loop steps the sim without rendering) → the hero was framed from far **below** | **`hero.update` runs only inside `wr.render`, i.e. once per captured label** (FullShot.java 62-72, 89-95; WorldRenderer 1038) → animation cross-fade ~14 % into `Jump_Idle`, `lineMove` ≈ 0.23, yaw/lean unsettled; arm aims are applied fully in one call | `rope_climb` = `Sim.Mode.ROPE` with |Δy| > 0.004 for 10 consecutive sim steps (`SETTLE`) | real rope element at (0, midY, 0) | 1280×720 (16:9), FBO, `glClearColor` sky override | sim + autopilot: yes; `PlayScreen`/HUD/touch: no | same `HeroRig`, same assets; **working-tree code, not the APK's** |
| **RigShot** (`:desktop:rigShot`) | bare `HeroRig` on a sky-blue FBO with a magenta (review-tweaked to rope-coloured) reference bar | same | own `PerspectiveCamera(40°)` at dist 9, 3/4 angle 0.25 rad (0.9 rad for ledge), looking at `y+1.2` | `rig.update` **every sim frame** (RigShot:118) | by `rig.anim` predicate, `SETTLE 9` | synthetic bar at (0, y+handH−0.5, 0) | 720×720 | sim + autopilot; no renderer | `HeroRig` only; own lights |
| **GripProbe** | no rendering | same | — | every frame | — | — | — | — | numeric limb/hand scale only |
| **DesktopLauncher `shot.sh`** | full game via `climb-up-players` classes | players worktree (2026-10-11 code) | real | real | — | — | configurable | yes | **not used for any grip render today** |

### 5.2 Known differences that explain "preview looked right, game did not" (ranked)

1. **Camera height (VERIFIED, dominant).** Every FullShot image sent to the owner before 21:51 (`feet_fixed`, `ham_brownpaws`, `ham_hands`, `ham_final`, `ham_fork`, `pull12/send`, `fill2/ham`, `ham_gap_fix`, `SHEET_rope`, `FINAL*_rope`, `hamster_wings_fix`, `hamster_hands_onrope`, `DONE_rope`) was rendered with the camera well below the hero, looking up. Adding `snapCamera` (21:51) changed the hamster from "one ball each side of the rope" to "balls bunched at the chest" — matching the owner's Build 55 capture (`e893336e`). The approvals at 16:10, 16:17 and 20:52 were given on these images.
2. **Single-frame rig update in FullShot (VERIFIED).** Still present after the camera fix: body/leg/head posture is mostly the previous clip's; `lineMove`/`climbPhase` are not at steady state. ~~Hands/arms are plausibly representative (aims are weight-1, one call), posture is not.~~ **Withdrawn (owner observation 22:3x Z, see Addendum A):** the hand-versus-head relationship depends on the clip's torso/shoulder/neck pose, which the single-update harness gets wrong; FullShot renders therefore cannot be trusted for face obstruction either.
3. **Code identity (VERIFIED).** Renders from 21:55 onward (`fix03`, `base012`, A–I) used the uncommitted `hamPull 0.3`; Build 55 runs pull 1. Renders between 19:56 and 20:25 used the discarded widen rework. `paw1` used a model never committed.
4. **Cropping and magnification (VERIFIED).** Owner-facing images were crops at 150–700 % of a 1280×720 frame; the game shows the hamster at ~1/8 of screen height (`e893336e`). Admitted at 21:46.
5. **Climb-cycle moment (HYPOTHESIS).** FullShot captures 10 steps into `rope_climb`; the owner's screenshot is an arbitrary moment of the hand-over-hand cycle; hand heights alternate (`GRIP_HI/LO`), so single frames differ.
6. **Cable sheets were RigShot (VERIFIED).** `SHEET_cable`, `FINAL_cable`, `DONE_cable` (accepted 20:52) are synthetic-scene renders; no FullShot seed (2, 5, 8, 11, 14, 19, 26, 33) produced a cable crossing.
7. **Asset or OTA divergence — RULED OUT for Build 55 (VERIFIED):** APK models hash-identical to git; OTA cannot carry models.
8. **Device factors (UNKNOWN):** phone `quality` setting (render scale), 20:9 aspect, reduced-motion.

**What the owner actually sees (Build 55, upload `e893336e`, 21:38):** hamster on the rope near the top of a tower segment, face partly behind the rope, **two tan balls bunched together at the chest on/just beside the line, white paw shapes visible beside/behind them** (the un-removed paws), feet closed on the rope. The owner's 22:11 statement — "you can see the hands and the new ball hands" — is consistent with §2.4 and the 17:45 `noball` render.

---

## Section 6 — Conflict and override analysis (nothing changed)

- **Animation tracks vs procedural pose:** the clip sets all bones every frame; `gripLine`/`feetToLine` re-aim the limb bones afterwards (rotation only), so the arm *direction* is procedural while bone *scale* is the clip's bind scale re-asserted by `reclampLimbScales`, then the hamster stretch. Authoritative for arm direction: `gripLine`. Authoritative for hand *mesh* placement: skinning to LowerArm (paws) and the ball offsets (balls) — **two independent systems draw a hand at the same joint** (duplicate geometry, VERIFIED for the hamster).
- **Scale inheritance:** `stretchArmsBy` scales UpperArm and LowerArm `(1.7, 2.5, 1.7)` for the hamster at full grip and counter-scales the `Fist` node `(1/2.89, 1/6.25, 1/2.89)`; the residual paw/cup vertices are skinned to **LowerArm**, not Fist, so they inherit the full stretch (INFERRED; not measured) — a candidate explanation for the enlarged white "wings"/paws beside the balls.
- **Local vs global:** `gripLine` targets are model-space (before the body is pushed back by `behind` in world space); the forward reach `ROPE_REACH 1.3` (model) × scale 0.37/0.50 is what lands the hands near z=0 after the push-back. Copying the astronaut's world-space `back 0.82` onto the hamster put its hands 0.65 behind the rope (sweep A, VERIFIED) — the two spaces do not transfer across scales.
- **Character-specific proportions:** hamster scale 0.37 vs astronaut 0.50; the hamster's "arm stubs" are the bunny skeleton re-positioned to hamster landmarks (`build_rigged_hamster.py`, DECISIONS.md:51), so arm length/shoulder width differ from the astronauts; `GRIP_HI/LO` are shared.
- **Camera-facing:** yaw `facing·6°` on the rope, φ=0; all characters face the camera (owner confirmed 20:22).
- **Frame-order dependencies:** `placeHandBalls` runs after the second `inst.transform` set, so balls use the pushed-back body (correct); FullShot's once-per-capture update is the only cadence anomaly found.
- **Visibility resets:** none. **Separate render/gameplay asset paths:** none.

---

## Section 7 — Current state inventory (Build 55 = what the owner plays; working tree differs only by the uncommitted `hamPull`)

| | BUNNY (0) | HAMSTER (1) | FINN (2) | RAE (3) | FERNANDO (4) | BARBARA (5) |
|---|---|---|---|---|---|---|
| Original hands visible | YES (by design) | **YES** (verified: mesh + owner screenshot + `noball`) | PARTIAL/UNKNOWN (18 % stripped) | UNKNOWN | UNKNOWN | UNKNOWN |
| Rounded replacement hands visible | NO (none) | YES | YES | YES | YES | YES |
| Correct proportions | UNKNOWN (owner: "the rabbit is fine") | size/colour approved by owner 17:30; arms/"wings" rejected | hand size approved 17:13 | UNKNOWN | UNKNOWN | UNKNOWN |
| Left hand contacts rope | likely (fist x +0.05, harness) — not verified in gameplay | **UNKNOWN/partial** — balls bunched near the line in gameplay; fists ±0.15 (harness) | likely (harness x −0.03) — not verified in gameplay | UNKNOWN | UNKNOWN | UNKNOWN |
| Right hand contacts rope | likely (−0.02, harness) | UNKNOWN/partial | likely (+0.05, harness) | UNKNOWN | UNKNOWN | UNKNOWN |
| Face unobstructed | UNKNOWN | NO — rope passes over the face (by design of `BUN_BACK`; gameplay screenshot) | **NO — owner reports on device that the head is "completely covered by their fists"** (20:22 and 22:3x; verbal, no capture on file) — my renders showed the head above the fists and are therefore wrong on this point (Addendum A) | same class as Finn — owner's statement covers "the astronauts" (no per-character capture) | same | same |
| Animation transitions correct | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN |
| Verified using actual gameplay | NO | **YES** (`e893336e`, Build 55; and Build 54 `a9c6cb72`/`bdb4517c`) | NO on Build 55 (`images/5.png` predates the ball hands) | NO | NO | NO |

**Satisfied (owner-stated):** feet rotate in (16:17); hamster hand size and colour (17:30); astronaut hand size (17:13); "everything else looks fine" after the fill (18:59). **Unresolved:** hamster hands grip the rope like the astronauts (open since 19:51); residual original hands visible (owner 22:11); astronaut hands in front of faces (20:22, no decision taken); hamster whiskers (open since 16:17, re-skin reverted).

---

## Section 8 — Evidence package

1. **Commit history:** §2.1; `git log --since=2026-10-12T16:00:00Z` on `ccr-cb458cd1-w4dh5c`.
2. **Diffs:** `scratchpad/audit/herorig_diffs.txt` (bd1bd1d…b0f7135, HeroRig+FullShot); `scratchpad/audit/uncommitted.diff`.
3. **Current source SHA:** `5e9bb11f21ca4c4d2503fef3ecd2cc9766a58876` (both worktrees). **Build 55 source:** `d69890f`. **Build 54 source:** `f8ed876`.
4. **Branch:** `ccr-cb458cd1-w4dh5c` (draft PR #1).
5. **Modified files:** `core/src/main/java/com/hotatticgames/climbup/render/HeroRig.java`, `desktop/src/test/java/com/hotatticgames/climbup/desktop/FullShot.java` (consol worktree only; §2.3).
6. **Runtime scene/asset paths:** `PlayScreen.java`, `render/WorldRenderer.java`, `render/HeroRig.java`, `render/Characters.java`, `assets/hero/hamster.g3dj` + `hamster.png`, `assets/hero/hero.g3dj`, `assets/space/astronaut_{finnthefrog,raetheredpanda,fernandotheflamingo,barbarathebee}.g3dj`; tools `tools/models/strip_hand_geometry.py`, `tools/hamster/fill_holes.py`, `tools/hamster/build_rigged_hamster.py`.
7. **Test/render commands used:** `xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew :desktop:fullShot -Dclimb.probeChars=N -Dclimb.shotDir=…`; `… ./gradlew :desktop:rigShot -Dclimb.probeChars=2 …` (+`-Dclimb.animCycle=true` for cable); `… ./gradlew :desktop:gripProbe`; manual: `cd assets && java -cp <consol classes>:<jars> com.hotatticgames.climbup.desktop.FullShot -D…` (`scratchpad/fs.sh`, `cp_consol.txt`); `./gradlew :core:test --tests "*RigMatrixGuardTest" --tests "*RigScaleDriftTest" --tests "*ClimbPhaseTest"`; `:core:test --tests "*PlatformSeamTest"`.
8. **Validation results:** GripProbe PASS at 16:13, 17:28, 18:58, 19:15, 20:14 (after FAIL at 20:12, bunny 2.06), 20:26; rig tests green 19:27, 21:42; push CI green on every commit (runs on ci.yml); Android run #55 success, #56 cancelled. **None of these validate appearance.**
9. **Screenshots/renders:** scratchpad dirs listed in §1 (`ropeshots, ropered, final, tint, tintfs, handfix, wh11, base, pz, nz, wh10, armdiag, armfix, armfs, ba90, noball, paw1, pull30, pull12, front, nocull, final3, fill, fill2, fill3, measure, pull_0.6, pull_1.0, oldfill, allchars, rig, sheet, t0, real, htune, hr, hw, final2, finalcab, bcab, seed_*, fc2, rope3, hbefore, rev, finR, finC, honest, truecam, truecam3, fix03, base012, A, B, F, G, H, I`); composites `*_cmp.png`, `SHEET_*`, `FINAL*_*`, `DONE_*`, `ham_gap_fix.png`, `truecam_cmp.png`, `fix03_cmp.png`, `sweep_cmp.png`, `B_tight.png`. Owner uploads (ground truth): `/root/.claude/uploads/<session>/` — `ffdca443` 13:48, `cd0bd99b` 18:38, `3e800e0f` 18:52, `e8ee77ae` 18:59, `92580828` 19:02, `826d1c70`/`90c717cd` 19:51–52, `6fb01eda`/`0906e0df` 20:21–22, `f1ce490c` 21:20, `aae33b1c` 21:31, `389b3f53`/`bdb4517c`/`a9c6cb72` 21:35, nine files 21:38 (incl. **`e893336e`**, `f3fb4d41`), `198ba9af` 21:47; `images/5.png` 15:40, `images/6.png` 17:14. Model dumps: `scratchpad/audit/g3dj/*.g3dj`, `g3dj_structure.txt`. APK: `scratchpad/audit/apk55/build55.apk` (sha256 verified) + `hamster_from_apk.g3dj`.
10. **Errors/warnings:** no runtime errors recorded; GripProbe FAIL 20:12 (bunny balloon, fixed); CI `:core:test` locally exceeded a 500 s timeout once (19:27, slow solver tests, not a failure).
11. **Failed approaches:** §4 and §9.
12. **Speculative assumptions remaining:** the stretch-scaling of residual paws as the "wings" mechanism (INFERRED); astronaut glove remnants (UNKNOWN); phone quality/aspect effects (UNKNOWN); whether a single-frame harness update materially changes hand placement (INFERRED minor).

Also pending: **one unread GitHub notification** queued for this session (activity on PR #1, ~22:13 UTC) — not read. An idle Gradle daemon (PID 878) is resident from earlier compiles; no build, test or render is running.

---

## Section 9 — Root cause assessment

**Ranked suspected causes**

1. **(HIGH, VERIFIED) The hamster's original hands were never removed.** `strip_hand_geometry.py` cut only the paw tips (598/13,560 triangles); `fill_holes.py` then made the leftover cups solid. The game therefore draws the skinned paw remnants **and** the tan balls — every ball-offset change since noon has been repositioning a second hand relative to a first hand that was supposed to be gone. Confirmed by mesh analysis (§2.4), the 17:45 `noball` render, the APK hash, and the owner's 22:11 observation.
2. **(HIGH, VERIFIED) Validation was not representative.** FullShot rendered from an un-snapped camera (below the hero) with a once-per-capture rig update, and the owner was shown magnified crops; RigShot (used for all cable sheets) is a synthetic scene. Approvals and "verified in the REAL renderer" claims in commit messages (`c2a1b63`, `4ea89c3`, `b0f7135`, HANDOFF.md:12) rest on these images.
3. **(MEDIUM, VERIFIED geometry / INFERRED cause) Hamster arm treatment.** Stretch 0.7/1.5 on every grip + `BUN_BACK 0.37` + `ROPE_REACH 1.3` puts the hamster's fists at x = ±0.15, chest height, with wide splayed upper arms; pulling the balls to the line (×0.3) collapses them to ±0.045 (bunched), leaving them at ±0.15 puts them beside the line. The residual paws, skinned to the stretched LowerArm, are plausibly what reads as "wings". Neither ball position reads as a grip while (1) persists.
4. **(LOW–MEDIUM, HYPOTHESIS) Shared grip heights** (`GRIP_HI/LO`) land at chest level on the hamster's proportions but at helmet level on the astronauts.
5. **(UNKNOWN) Astronaut glove remnants** after the 0.90 strip, and the astronauts' hands-in-front-of-face complaint (20:22) — not investigated.

**Why repeated fixes did not resolve it:** every attempt after `7e0bf56` adjusted ball offsets, colours, sizes, arm scale or mesh caps while the duplicate hand geometry remained, and each was judged on a harness that did not show what the player sees. The feedback loop was therefore tuning the wrong variable against the wrong image.

**Attempts that should not be repeated:** ball offset/pull/front/`BALL_AT` tuning without removing the paw geometry; re-skinning by |x| threshold (T-pose); double-sided material (z-fighting); negative widen (hands wider); transplanting astronaut world-space `back`/stretch values unscaled (A/F/G: hands behind the rope); the all-character line stretch without a uniform-scale guard for real paws (bunny balloon); judging any result on FullShot without `snapCamera`, on crops, or on RigShot for in-game appearance.

**Existing corrections worth preserving (owner-approved or structurally sound):** `feetToLine` (feet rotate in, approved 16:17); hamster ball size 0.10 / tan colour and astronaut 0.09 (approved); `fill_holes.py` caps (solid paws, "everything else looks fine"); `cf6a9e1`'s revert of the whisker re-skin; bunny uniform cable stretch `b0f7135` (GripProbe PASS; its visual acceptance was on a RigShot sheet); the uncommitted **`FullShot.snapCamera` fix**; diagnostics `fistWorld`/`footWorld`/`maxHandScale`; the `GripProbe`/rig-guard tests. The uncommitted `hamPull 0.3` default should be treated as unvalidated (it re-creates the 18:59-rejected geometry).

**Do the game and the preview use different character configurations?** No — same loader, same model files (hash-verified), same `HeroRig`. They differed in **camera state, update cadence, cropping, and (after 21:55) uncommitted code**, not in assets.

**Evidence still missing:** (a) a frame-faithful capture harness (per-frame `hero.update`, `snapCamera`, no crop, phone aspect) compared side-by-side with device captures of the same state; (b) device captures of each astronaut on the rope on Build 55; (c) several in-game frames of the hamster across the climb cycle; (d) an explicit inventory of the residual hamster paw triangles and astronaut glove triangles (by bone weight and position) to establish what must be hidden; (e) the phone's quality setting; (f) an owner decision on the astronaut hands-vs-face question (20:22).

---

## Section 10 — Independent-review handoff

**A. What the owner requested.** Hands must visibly grip the rope: in front of the line, both hands on it, like the astronauts; feet closing on the rope; hamster hands the feet's tan colour and astronaut-like size; no gap between hand and arm; no "wings"; the original hands must not show alongside the ball hands; the astronauts' hands should not sit in front of their faces; no build without the hamster fix.

**B. Everything attempted since noon.** Twelve distinct attempts (§1.1–1.13): hands-in-front + feet (committed), brown balls, half-size tan balls, whisker re-skin (regressed, reverted), ball-on-wrist, texture-based paw strip (abandoned), sideways pull, cup fill + astronaut treatment, pull back to 1 (Build 55), widen rework (reverted) + bunny cable fix, harness camera fix + `hamPull 0.3` (uncommitted), parameter sweep A/B/F/G/H/I (uncommitted, parameters only).

**C. Currently implemented (Build 55 / HEAD).** Ball hands for hamster + astronauts (`handBall`), strip+fill hamster model (paws still present), `gripLine`/`feetToLine` posing, hamster stretch 0.7/1.5, astronaut 0.08/0.35, bunny 0.4 uniform on cable, ball on rope: astronauts x·0.3 z+0.17, hamster x·1.0 z+0.17. Working tree adds tunables and switches the hamster to x·0.3.

**D. Demonstrably broken (owner-verified on device).** Hamster on Build 55: balls bunched at the chest, original paws visible beside them, not reading as a grip (`e893336e`, 21:38; owner 21:35, 21:44, 22:11). Title read "CLIMB UP" on Build 55 (fixed in `5e9bb11`, not yet built).

**E. Why previous validation was unreliable.** §5: un-snapped camera, once-per-capture rig update, crops, RigShot for cables, code/model states that differed from the build, and numeric probes (GripProbe/fist coordinates) that measure scale and position, not appearance.

**F. What a reviewer needs to design a reliable correction.** The residual-geometry inventory (§9 missing-evidence d); a faithful harness (a); device captures (b, c); a decision on whether the replacement is "hide the original hands completely" (mesh/part-level) versus "re-pose the original hands" (which the skeleton cannot do — no finger bones on the hamster, vestigial ones on the astronauts); the shared vs per-character grip heights question; the §6 transform order.

**G. Repository state.** Branch `ccr-cb458cd1-w4dh5c` at `5e9bb11` in `/home/user/Climb-Up` (clean) and `/home/user/climb-up-consol` (two modified files). Remote `origin` at `5e9bb11`. Draft PR #1 open. Releases: Build 55 APK (`d69890f`), Desktop Build 27 (`d69890f`), Build 56 cancelled.

**H. Uncommitted changes.** Yes — `HeroRig.java` (hamster `ballPullX` → 0.3 default; `HAM_WIDEN`/`HAM_BACK` tunables with unchanged defaults) and `FullShot.java` (`snapCamera` call). Preserved as-is.

**I. Automated processes.** None running that were started today's session except an idle Gradle daemon; no workflow is in progress (Build 56 cancelled 21:46). One GitHub notification remains unread. No scheduled wake-ups or check-ins are armed.

---

## Addendum A — owner observation after the audit (22:3x Z): astronaut heads covered by fists in-game, visible in my renders

**Observation (owner, on device):** "you keep showing me the astronaut renders where I can see their heads, but in the game their head's completely covered by their fists." No screenshot on file; treated as device truth.

**What my renders showed:** every astronaut render (FullShot `truecam3/2_rope_climb.png`, `base012/2_rope_climb.png`, `fix03_cmp.png`, the `DONE_rope`/`SHEET_rope`/`FINAL*_rope` sheets) shows the green/orange head **above** two stacked dark balls at neck/chest level.

**Mechanism (VERIFIED from code; magnitude INFERRED, no render permitted):**
- `FullShot` calls `wr.render()` only inside `capture()` (FullShot.java 62-72, 89-95), and `HeroRig.update()` runs only inside `WorldRenderer.drawPlayer` (WorldRenderer.java:1038). So at the moment of a rope capture the rig has received **one** update since its previous capture (which, for every character, was the `idle` label → `Idle` clip looping).
- That single update calls `play("Jump_Idle", -1, 1f, 0.12f)` (HeroRig.java:415) → `AnimationController.animate(..., transition 0.12 s)` then `ac.update(1/60 s)` → the `Jump_Idle` pose is blended in at ≈14 %; the skeleton is ≈86 % the **standing `Idle` pose** (upright torso, head high). `gripLine` then aims the arms from wherever the clip left the shoulders, with the hands' height expressed as a fraction of arm length above the shoulder (`GRIP_HI 0.26 / GRIP_LO 0.02`).
- In the game the `Jump_Idle` clip is fully blended within 0.12 s and keeps advancing every frame. The torso/neck/head and shoulder positions of that clip differ from `Idle`; the head-to-hand relationship on screen therefore differs from the harness. The owner's report (fists covering the head) is the direction expected if `Jump_Idle` carries the torso/head lower or forward relative to the shoulders than `Idle` does — **this is the inferred part; it has not been measured.**
- Secondary contributors in the same direction: `lineMove` ≈ 0.23 in the harness vs → 1 in play (changes which height each hand sits at, not the range); `ballFront` +0.17 toward the camera with the astronaut body pushed back 0.82, so anything at head height in front of the helmet occludes the visor completely.

**Consequence for the audit:**
- The statement in §5.2(2) that "hands/arms are plausibly representative" is withdrawn. After the `snapCamera` fix the harness is camera-faithful but **not pose-faithful**; before the fix it was neither. No FullShot image produced today is reliable for face obstruction or for the hands' height relative to the head.
- `RigShot` has the opposite defect: it updates the rig every frame (pose-faithful) but uses a synthetic 3/4 camera and bar; `GripProbe` renders nothing. **No harness used today was faithful in both camera and pose.** A reviewer needs one that calls `hero.update()` every frame through the real `WorldRenderer` with `snapCamera`, uncropped, at phone aspect — and device captures to compare against.
- §7 astronaut rows updated: face obstructed = NO (owner-reported), my renders wrong on this point.
- §9 "missing evidence" gains: (g) measurement of the `Jump_Idle` vs `Idle` head/shoulder offsets at full blend, (h) at least one device capture of an astronaut on the rope on Build 55.

*End of the original audit and Addendum A. Addendum B below was added at the independent reviewer's request (2026-10-13, 00:0x–00:3x Z) and is diagnostic only.*

---

## Addendum B — defect #4: the hamster's running arms (reviewer's "ADDITIONAL REQUIRED DIAGNOSTIC")

**Reported (owner, on the Android Build 55):** "the hamster runs with both arms extended outward at its sides." No device capture of the hamster running is on file; the description is treated as device truth. Everything below was obtained read-only from the committed code (`5e9bb11`, identical to Build 55 for every file involved), the shipped model, and the repaired faithful harness (`GameplayShot`, Part C/`DIAGNOSTIC_PHASE_REPORT.md` §C), which runs the real `WorldRenderer`/`HeroRig`/`AnimationController` every simulated frame with the real camera. **No production file was changed.**

### B.1 Which animation plays when the hamster runs — VERIFIED (code + runtime)

- `HeroRig.pick()` returns `Anim.RUN` for `GROUND` with |vx| ≥ 0.4 (HeroRig.java:356); `update()` then plays the **`Walk`** clip below 3.8 u/s or the **`Run`** clip at or above it, each as a looping clip with a 0.12 s / 0.10 s cross-fade (HeroRig.java:411). Tuning `runSpeed` is 5.6 with `groundAccel` 48 (`data/tuning.json:3`), so a fully deflected stick, and the harness autopilot, reach the `Run` clip within 5 frames; `Walk` only plays while the stick is partly deflected.
- The hamster's clips are the **bunny's clips copied verbatim** by `tools/hamster/build_rigged_hamster.py` (docstring lines 3–4; "bunny local rotations are kept", line 84; animation copy, lines 170–183): every rotation key is the bunny's; only the translation keys are re-based onto the hamster's bone offsets (line 181).
- Runtime confirmation (harness `gs_run2/report.txt`, same simulated frames for every character): `RIG anim=RUN … ac.current=Run@0.208 … 0.639 … transition=0.100/0.100 queued=false`, all twelve limb scale triplets `1.00`. So on the ground the hamster is the raw `Run` clip through the hamster skeleton — nothing else.

### B.2 Do arm keyframes exist? — VERIFIED (`audit/anim_analysis.txt`)

Yes. Per clip, for the hamster (identical counts for the bunny):

| clip | UpperArm.L/R | LowerArm.L/R | Fist.L/R | Shoulder.L/R |
|---|---|---|---|---|
| `Run` (708 ms) | 18 rotation keys, 96.4° spread | 18 keys, 67.4° / 52.8° | 18 keys, 40.2° | 2 keys (static) |
| `Walk` (1250 ms) | 31 keys, 69.5° | 31 keys, 31.1° / 29.5° | 2 keys (static) | 2 keys |
| `Idle` (2083 ms) | **2 keys, 0.0° (static)** | 51 keys, 18.4° | 2 keys | 51 keys, 10.8° |
| `Jump_Idle` (625 ms) | 16 keys, 3.3° | 16 keys, 3.0° / 2.3° | 2 keys | 2 keys |

The arms **are** animated while running; the defect is not missing keys.

### B.3 Arm / shoulder bone bindings — VERIFIED (`audit/inventory.txt`, `build_rigged_hamster.py:101`)

- Skin weights: `UpperArm.L/R` 209 vertices per side (mesh x 0.85–1.20), `LowerArm.L/R` 158 per side (the paw stub, x 1.20–1.29), `Fist.L/R` **0** vertices, no finger bones. The ball hands are unskinned spheres placed at the `Fist` node blended 40 % back toward `LowerArm` (`placeHandBalls`, HeroRig.java:487–499).
- Rest-pose joint landmarks written by the builder (line 101): `Shoulder (0.70·s, 1.25, 0) → UpperArm (0.85·s, 1.25, 0) → LowerArm (1.05·s, 1.25, 0) → Fist (1.25·s, 1.25, 0)` — **every arm joint at the same height**, so the hamster's rest arm direction is (±1, 0, 0): straight out to the side, 90° from straight down, 0° from "out". The bunny's rest arm direction is 43° from down / 49° from out (hanging diagonally down and out); the astronauts' is a T-pose bind corrected by their own clips.

### B.4 Why identical keys give a different pose — VERIFIED by two independent computations

A clip key is an **absolute local rotation of the bone's frame**. What is seen is the direction of the bone vector (joint → child joint) after that rotation. The builder kept each bone's local frame (the bunny's rest rotation) but moved the child joints onto a horizontal line, so the same key rotates a differently-pointing bone: the whole swing is offset by the angle between the two rest directions (≈47°). The `Run` clip, authored to pump a hanging arm, therefore sweeps the hamster's arm **around a horizontal rest** — fore and aft in the horizontal plane at shoulder height — instead of below the shoulder.

Offline (bone matrices from the g3dj, `audit/anim_analysis.py`), upper-arm angle from straight down at the clip's quarter points:

| | `Run` | `Walk` | `Idle` | `Jump_Idle` |
|---|---|---|---|---|
| hamster | 75–87° | 28–87° | 90° (static) | 92–96°, only 20–22° from straight out |
| bunny | 37–52° | 37–52° | 41–43° | 61–62° (L) / 95–102° (R) |
| Finn | 35–68° | — | — | — |

Runtime (faithful harness, `gs_run2`, frames 15/23/31/39 of the same course for all three characters, `Run@0.208–0.639`, body yawed 68° toward the direction of travel as in play):

| character | upper arm from down (L / R per frame) | hand height vs shoulder (world) |
|---|---|---|
| hamster | 89/78, 83/71, 90/82, 73/88 — **71–90°** | hands y 0.49, shoulders y 0.51 (hips 0.23): **at shoulder height**, out to the sides at z ±0.46–0.50 |
| bunny | 59/33, 76/27, 61/38, 24/70 — 24–76° | hands y 0.21, shoulders y 0.41: at hip height |
| Finn | 68/27, 73/25, 28/66, 25/70 — 25–73° | pumping below the shoulders |

`gs_compare/run_arms_gs_run2.png` shows the three rows side by side: the bunny's and Finn's fists pump at the waist while the hamster's balls sit at shoulder height, one in front of the chest and one hidden behind the body, and never come down. At the 68° yaw of normal running one arm points toward the camera and one away; whenever the body faces the camera more squarely (a direction reversal — the yaw eases from +68° to −68° at 18/s and passes through 0 — the first steps of a run, a landing) both arms read as "extended outward at its sides", which is the owner's description. See B.8 for the turn captures.

### B.5 Blending and transitions — VERIFIED, not the cause

`Idle → Run` cross-fades in 0.10 s (6 frames) and `Walk → Run` likewise; every running capture shows `transition=0.100/0.100` (finished) with nothing queued. `reclampLimbScales()` restores every arm scale to 1.00 during RUN (the `limbs=` line). Bones without tracks in `Run` keep their last pose, but all eight arm/shoulder bones have tracks. There is no blend or transition defect; the pose is the clip's.

### B.6 Procedural arm logic during running — VERIFIED: nothing overrides the clip, and nothing corrects it

In `HeroRig.update()`:
- `armsRest()` runs **only** for `IDLE` without a beat (HeroRig.java:478). Its own comment (503–508) records the same underlying problem and the decision not to touch running: *"both skeletons' bare Idle clips rest the arms out in an A/T-pose (the bunny-skeleton hamster worst of all) … Not applied during run (the clip swings them)."* The standing T-pose is masked; the running one is not.
- `stretchArms`/`stretchArmsBy`, `gripLine`, `feetToLine`, `raiseArms`, `flail` run only when `grip > 0.01` (HANG/CLIMB/SHIMMY/PULLUP, lines 445–448) — never in RUN.
- `slipFlail` runs only on wet platforms (line 441); `lean` tilts the whole body (433–438), not the arms.
- `placeHandBalls()` (479) places the balls on the hand bones, so the balls follow the horizontal arms.

### B.7 Other characters — VERIFIED for the bunny and Finn at runtime; the other astronauts by inference

The bunny (its own skeleton and clips) and Finn (the Quaternius astronaut skeleton, 42 bones, its own `Run`) pump their arms normally in the same frames (table in B.4). Rae, Fernando and Barbara share Finn's skeleton, clip set and bind pose (`audit/g3dj_structure.txt`, `inventory2.txt`) and were not rendered running; they are expected to match Finn (INFERRED). **The defect is specific to the hamster**, i.e. to the re-rigged model, not to `HeroRig` or the clips.

### B.8 Running while facing the camera (turn frames) — harness captures

The harness was extended with a `run_front` state: running (|vx| ≥ 0.4) while |body yaw| ≤ 35°, captured every frame from the first, excluding the first 30 frames of a slice (the yaw starts at 0 when a renderer is created, which is not a turn). In course slice 0 of seed 11 this occurred twice for both characters (`gs_front/`): frames 150–152, the first running steps after topping the rope (`Run` clip 1–3 frames in, cross-fade 17–50 %, body still yawed near the rope's ±6°), and frames 201–203, the first steps of a slow walk (`Walk` 1–3 frames in). The autopilot made no mid-run reversal in that slice, so these are "first steps facing the camera", a moment the player sees every time the hamster leaves a rope, not a sprint reversal.

| frame | hamster upper arm from down (L / R) | bunny upper arm from down (L / R) |
|---|---|---|
| 150 / 151 / 152 (`Run`) | 85/45, 84/56, 83/66 | 50/65, 48/70, 45/72 |
| 201 / 202 / 203 (`Walk`) | 94/86, 93/80, 92/75 | 62/92, 61/88, 59/85 |

`gs_compare/run_front_gs_front.png` (hamster row over bunny row, same frames): in all six hamster frames both arms stick straight out sideways with a ball at shoulder height on each side — the owner's "both arms extended outward at its sides" — while the bunny's arms hang at its sides (frames 150–152) or carry one arm still raised from the preceding grip pose (frames 201–203, where the hamster's are up as well because the clip has only just started blending in). The left-arm figures of 83–94° in every hamster frame, against 45–62° for the bunny, are the clip difference of B.4 seen face-on; the right-arm figures of 45–66° at frames 150–152 are the fading grip pose, not the clip. These frames are blend frames and are weaker evidence than the steady-state run frames of B.4; they are included because they show the viewpoint the owner describes.

### B.9 History — VERIFIED from git

- `8cf471d` (Oct 8 20:34 Z) — the first, procedural hamster: *"arms no longer rest in a T-pose"* (hand-animated run swing).
- `eb8defd` (Oct 8 22:02 Z) — *"Rigged hamster on the bunny skeleton"*: `build_rigged_hamster.py` and the horizontal arm chain date from here; the bunny's clips replaced the procedural swing.
- `0446e34` (Oct 12 02:24 Z) — `armsRest()` added for the standing pose only.
- `cf6a9e1` (Oct 12 17:44 Z) — the whisker re-skin was reverted because it "T-posed the arms" on the rope; the running pose was not examined.
- No run-state render exists in the session scratchpad from before today (searched); `RigShot`'s `climb.animCycle` mode would have shown the RUN pose but no such image is on file. That the running arms were never validated after the re-rig is INFERRED from this absence, not proven.

### B.10 Cause statement

- **VERIFIED:** the hamster's arm joints were laid out as a horizontal chain (all four at height 1.25) while the bunny's rotation keys — authored for an arm that hangs 43° from vertical — were copied unchanged. The `Run` and `Walk` clips therefore swing the arms around a horizontal rest, keeping them at shoulder height and out to the sides; the ball hands ride on those bones. No runtime code masks it during running (only during idle), and the device plays exactly this code and model.
- **HYPOTHESES (untested, for the reviewer's plan; none executed):** (a) re-base every arm rotation key in the builder by the rest-direction delta (bunny rest → hamster rest), which would restore a hanging, pumping arm while leaving the joints where the skinned stub and ball anchors are; (b) move the hamster's arm joints along the bunny's rest direction instead — fixes the clips but relocates/skews the skinned stub and every ball anchor and grip pose; (c) a runtime `aim()`-based correction during RUN, like `armsRest()`, would mask the symptom without fixing the clip. Any of these changes the bones the rope/cable/ledge grip poses are built on, so the grip states would have to be re-validated with the faithful harness together with `run`, `run_front` and `idle` before any device build.

### B.11 Device-match status and inventory entry

- **Device match: INCOMPLETE** — no device capture of the hamster running exists. The harness runs the Build-55-identical code and model (`hero/hamster.g3dj` sha256 `49361c3f…`) and reproduces the owner's description geometrically; one device frame of the hamster mid-turn would close it.
- **Defect inventory (extends §7):** #4 hamster running arms — state RUN (`Run`/`Walk` clips) — cause VERIFIED (B.10) — affects hamster only — no code or model change made — harness evidence `gs_run/`, `gs_run2/`, `gs_front/`, composites `gs_compare/run_arms_gs_run2.png`, `gs_compare/run_front_gs_front.png`.
- A successful rope-grip correction will not touch this defect: the grip code never runs while running (B.6).

*End of Addendum B. No code, model, animation, build, commit, push or publication was made for it; the only files written are the untracked desktop harness, this document, and scratchpad renders.*
