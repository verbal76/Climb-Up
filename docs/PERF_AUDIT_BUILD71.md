# Build 71 performance and frame-pacing audit

Audit of the Build 71 code (released from cc39cd3) on the working branch. Audit first; only measured, visually lossless, low-risk changes were made, and
nothing was built or released.

## What was measured, and on what

| | |
|---|---|
| Machine | Cloud container: 4 CPUs, 15 GB RAM, **no GPU**. OpenGL is Mesa **llvmpipe** (software rendering on the CPU) under Xvfb. |
| JVM | OpenJDK 21 (the shipped desktop builds bundle a jlink'd **JDK 17** runtime; the heap options match the shipped launchers: `-Xms256m -Xmx1024m -XX:+UseG1GC`). |
| Not tested | **No Android device, no Windows PC, no Steam Deck, no real GPU.** Every number below is from the desktop (LWJGL3) build on this machine. |
| Harness | `./gradlew :desktop:perfRun` (`desktop/src/test/.../PerfRun.java`): the real game (ClimbGame, PlayScreen, WorldRenderer, HUD) driven by the test autopilot through the real input path. `PerfProbe` (off unless `-Dclimb.perf=true`) stamps each frame's stages. Per-frame CSV, 10 s warm-up dropped. Optional Flight Recorder profile (`-Dperf.jfr=FILE`). |
| Stages | **stream**: frame start to after tower streaming (includes the old crash line); **sim**: the fixed-step simulation loop (the autopilot's planning subtracted); **world**: after the sim to after the 3D render (includes autosave, particles, juice, tips); **hud**: the 2D HUD. **render** = all of PlayScreen.render on the CPU. **interval** = time between frames, including buffer swap and GPU work. |

What the numbers can and cannot say:

* **Frame pacing (intervals, FPS, lows) here is not representative.** This machine presents frames at a flat 30 Hz: the median interval is exactly 33.33 ms even
  uncapped at 640x360, while the game's own CPU work is about 6 ms. So FPS, 1%/0.1% lows and interval percentiles describe this virtual display, not the game.
* **GPU time is not measurable here.** llvmpipe draws on the CPU, so a frame the game builds in 4 ms can take 200 ms to present.
* **The CPU stage times, allocation and GC are meaningful**, as relative costs. They come from desktop HotSpot. Android runs the same Java code on ART, which is typically slower per call.
* **Input latency** was traced through the code; it could not be measured end to end without a display and a camera.

## A. Baseline

Seed 7, 1280x720 unless noted. The interval percentiles are this virtual display's (see above).

| Profile | Frames | Avg FPS | 1% / 0.1% low | Interval median / p95 / p99 / max (ms) | >33.3 ms | >50 ms |
|---|---|---|---|---|---|---|
| baseline720 (420 s, cap 60) | 12277 | 29.9 | 19.2 / 8.3 | 33.33 / 33.37 / 34.80 / 215.1 | 404 | 42 |
| cpuBound (240 s, 640x360, uncapped) | 6899 | 30.0 | 22.0 / 12.9 | 33.33 / 33.36 / 34.39 / 99.5 | 196 | 19 |
| fallsRestarts (360 s, fall every 20 s, restart every 120 s) | 10253 | 29.3 | 7.2 / 1.8 | 33.33 / 33.37 / 39.28 / 1440.7 | 364 | 74 |

The game's own CPU time per frame (baseline720):

| Stage | Mean | Median | p95 | p99 | Max (ms) |
|---|---|---|---|---|---|
| render (CPU, whole screen) | 8.11 | 6.80 | 16.15 | 22.09 | 209.2 |
| of which test autopilot (not game work) | 1.30 | | | | 139.5 |
| stream | 0.071 | 0.052 | 0.138 | 0.249 | 9.8 |
| sim (fixed step, physics) | 0.094 | 0.085 | 0.154 | 0.238 | 3.2 |
| world (3D render + autosave + effects) | 6.44 | 6.07 | 9.08 | 12.45 | 208.9 |
| hud | 0.186 | 0.159 | 0.346 | 0.523 | 7.4 |

* **Allocation:** a median of 9.85 KB per frame in frames where the autopilot did not replan (its replanning allocates MBs and dominates the averages).
* **GC:** 18 young collections in 7 minutes, 94 ms in total; none of them lined up with a frame-time spike.
* **Autosave:** 51 autosave frames. Their world stage averaged 11.8 ms against 6.4 ms in other frames (+5.4 ms), max 17.6 ms.
* **Physics:** 0.09 ms for about 2 steps per frame. The simulation is never the bottleneck.
* **Generator:** the background tower generator was busy in 838 frames. Those frames were not spikier than idle ones: 2.98% over 33.3 ms when busy, 3.31% when idle.

Scenario coverage: the climbs reached 477 m (baseline720) and 265 m (cpuBound), crossing the meadow and the first castle section. They covered:
- running, jumping, ropes, cables, pull-ups and moving platforms;
- creatures and cannons;
- autosaves and generator activity;
- forced falls through the real respawn path, and restarts through the real new-climb path (fallsRestarts).

The snow, castle 2+, space and finish worlds were **not** reached in these runs.

## B. Frame pacing

* **Fixed step (good):** the simulation runs at 60 Hz on an accumulator; rendering interpolates the player, elements and hazards with `alpha = acc / DT`.
  * The frame time is clamped to 0.1 s, at most 6 steps run per frame, and below about 10 fps the leftover time is dropped (the game slows down rather than spiralling).
  * Hitstop pauses the accumulator (unless reduced motion is on); the slow-speed assist scales it by 0.8.
  * A press latched in a frame that runs no simulation step is kept for the next step, so no input is lost at high refresh rates.
* **Frame limiter:** on desktop, LWJGL3's foreground limiter (`setForegroundFPS`) sleeps to the cap. A frame that misses 16.7 ms is shown at the next cap tick, so a single missed frame shows as 33 ms. With vsync on, the monitor paces instead.
* **Spikes seen here and their cause**, ranked by measured contribution in section C:
  1. Starting a new climb freezes for 1.2-1.3 s (fallsRestarts max interval 1440 ms).
  2. The frame after every respawn takes 190-275 ms to present, while the game's CPU work in it is about 4 ms (GPU or present side; see C3).
  3. A model loads the first time it appears (a world-stage spike of up to about 200 ms, once per model per session).
  4. The autosave adds about 5 ms every 8 s.
  5. Everything else was this display's pacing.

## C. Bottlenecks, ranked by measured contribution

1. **Starting a new climb (desktop): 1.2-1.3 s freeze, of which 1.25 s is generating the first slice on the render thread.**
   * Timed inside `PlayScreen.show`:
     * `openRun`: 1,245-1,280 ms, almost all of it `new Tower`. The history write was 1-3 ms.
     * `HeroRig` (re-parsing the character model): 20-35 ms warm.
     * `WorldRenderer` construction: 25-40 ms warm.
   * Root cause, measured with a headless benchmark (same seed, identical work of 804,877 simulation steps each time):
     * The generator's proving simulation runs at about 300 ns per step under C1 (`-XX:TieredStopAtLevel=1`, 240 ms per slice).
     * It runs at about 1,500 ns per step once HotSpot's C2 has compiled it (1,200-1,300 ms per slice). The first, partly interpreted run is faster (400 ms) than the warmed-up runs.
     * No compile failures, deoptimisation storms or inlining limits explain it. These flags were tried without effect:
       - escape analysis;
       - on-stack replacement;
       - loop predication;
       - type profiling and type speculation;
       - "don't inline" on Sim/Element;
       - Parallel GC.
     * The root cause in the compiled code is **not yet isolated**.
   * It is HotSpot-specific. **Android runs ART, so this result does not carry over to phones** (not measured on a phone).
   * It also means the background generator does about 5x the CPU work it needs to on desktop. It still keeps up: it was busy in about 7% of frames.
   * The first climb of a session is cheaper (366 ms) because the code is not yet C2-compiled.
2. **Per-frame CPU on the main thread.** Flight Recorder samples, autopilot excluded:
   * Hero animation (`HeroRig.update`, animation controller, node transforms) and per-element placement and draw calls (`WorldRenderer.drawElement` / `place`, matrix multiplies) lead.
   * They are followed by **the crash-log line, rebuilt with `String.format` on every frame: 19 of about 200 game-frame samples, about 10%** of the game's own Java time on the main thread.
   * Then the HUD clock text (`ClockText.time`, `String.format` 2-3 times per frame).
   * The renderer's work is real drawing; no wasted work was found there.
3. **The frame after a respawn: 190-275 ms to present on this machine, with only about 4 ms of game CPU.**
   * It happens on every respawn, not just the first. The respawn sets a full-screen fade and an 18-particle burst and snaps the camera.
   * The time is outside PlayScreen.render (swap / GPU), so on llvmpipe it is software rasterising.
   * **It cannot be judged without a real GPU.** It is listed for on-device checking.
4. **Lazy model loading.**
   * Models load on first use: `Models.pack` (hazards, cannons, scenery) and `Models.obj` inside the frame that first draws them. This gives world-stage spikes of up to about 200 ms, once per model per app session.
   * Shaders likewise compile on first use of a new material combination (`glShaderSource` samples during play).
   * This is a one-time hitch per asset, on desktop and phone alike.
5. **Autosave every 8 s: about +5 ms in that frame on this machine.**
   * Benchmarked warm: save.json 0.2-0.4 ms (written twice per autosave), settings.json 0.16 ms, the run snapshot 0.33 ms.
   * The rest is the history append with `fsync` and the records sync. On phone flash an `fsync` can take tens of ms.
   * Removing the duplicate save.json write saves about 0.2 ms per 8 s: not worth touching.
6. **Allocation:** about 10 KB per frame (median) from the game itself. GC is rare and short (about 5 ms per young collection), and no spike was GC-caused.

## D. Input latency (code path; not measured end to end)

1. **Input is read once per rendered frame.**
   - Desktop: GLFW polls events before `render()`.
   - Android: libGDX queues touch events and hands them over on the GL thread before `render()`.
   - The touch stick and the jump button are read through the InputProcessor callbacks.
   - Presses are latched (`jumpLatch`, `swingLatch`) and consumed by the next simulation step in that same frame.
   - Held state is re-read every step.
   - Average wait from touch to poll: about half a frame (8 ms at 60 Hz).
2. **The simulation step that sees the press runs in the same frame.** Jumps start on that step; coyote time and the jump buffer are unchanged.
3. **Interpolation:** the drawn state is between the last two simulation steps (`alpha`), so what is shown trails the simulation by 0-16.7 ms (8 ms on average). This is the standard price of smooth fixed-step rendering.
4. **Presentation:** 1-2 frames of swap or compositor queue (17-33 ms at 60 Hz), plus the display itself.

Estimated touch-to-photon at 60 Hz: **about 35-60 ms** (8 poll + 8 interpolation + 17-33 present + panel), within the normal range for a 60 Hz game. Nothing in the
game's code adds latency beyond the standard fixed-step design.

**No change is proposed; the controls were not touched.** The directive mentions a "single-speed virtual stick" and a "sprint toggle". Build 71 has neither:
* the touch stick is analog (dead zone 0.18, output 0.35-1.0);
* the controller stick is proportional;
* the keyboard is full speed.

Owner clarification needed on what those two items refer to.

## E. Speedrun integrity

**The timer policy (documented, not changed).**
* The run clock counts **whole simulation steps** (`SaveData.runTicks`, 60 per second, `RunRecord.tick` once per simulation step).
* It is independent of rendered frames, refresh rate and frame drops.
* The displayed time (`runClock`) is derived from the ticks.
* It starts at the first move after the climb begins.
* It does not tick while paused, backgrounded, during hitstop, or after the official finish.

**Evidence.**
* `PlayDeterminismTest` shows identical outcomes for identical inputs at 8-360 fps.
* `DeterminismTest` and `ClockTextTest` cover the course and the clock text.
* Nothing in the simulation reads the frame time: the frame time only fills the accumulator.

**Findings to decide on (none changed):**
1. **Slow-speed assist.** `ASSIST: SLOWER SPEED` runs the simulation at 80% of real time. The clock counts simulation time, so an assisted run reports the same time as an unassisted run of the same inputs, while giving the player 25% more real time to react. Times are not marked as assisted.
   - Jump forgiveness (`assistJumpForgiveness`) likewise is not recorded with times.
   - Options: keep, flag assisted times, or exclude them from bests. This is an owner decision.
2. **Below about 10 fps the game slows down.** Dropped time is not simulated, and the clock counts simulation time. So such a run takes longer in real time but not on the clock; it is not an advantage, because the player plays the same simulation.
3. **The clock starts on the first move only.** It starts on the first step with movement or a jump press (`clockLive`). In that check the jump press has already been cleared, so in practice a jump with no stick input does not start the clock until the stick moves.
   - Effect: standing jumps on the start platform are not timed. They give no progress, so this is harmless, but it is a documented quirk.

## F. Fixes

| # | System | Root cause | Change | Measured benefit | Gameplay risk | Visual risk | Testing |
|---|---|---|---|---|---|---|---|
| F1 (**done**) | Crash diagnostics | The crash line was built with `String.format` and string concatenation on every frame, then thrown away unless the game crashed | `CrashContext` takes a live source that is asked only when a crash logger reads it. A stored line is still refreshed about once a second, and on leaving the screen, as the fallback when the live source cannot run. The source is cleared on `hide()` so a left screen is not kept alive | stream stage 0.071 → 0.016 ms per frame (mean); about 0.9 KB per frame less garbage (microbenchmark: 380 ns and 880 B per frame on desktop C2) | None: diagnostic only, never read by the simulation | None | `CrashContextTest` (asked only on read; failing source falls back; clearing keeps the last line). Desktop and Android crash loggers unchanged (`CrashContext.get()`) |
| F2 (**done**) | HUD clock text | `ClockText.time` used `String.format` 2-3 times per frame | Built by hand with a StringBuilder; identical text | 600 → 50 ns and 1,095 → 119 B per call; hud stage 0.186 → 0.132 ms per frame (mean) | None: display only; the clock (ticks) is untouched | None: same characters (verified) | `ClockTextTest.theHandBuiltTextMatchesTheFormattedOneEverywhere` (200,000 values plus edge cases against the old String.format version) and the existing clock tests |
| P1 (proposed, needs approval) | New climb start (desktop) | Slice 0 is generated on the render thread, and on HotSpot C2 the generator runs about 5x slower than under C1 | (a) Isolate the C2 slowdown in the generator's proving simulation (needs a deeper JIT study; could be a small code change once found). (b) Or generate slice 0 behind a short loading state so the frame does not freeze | Up to about 1 s per new climb on desktop | (a) none if output is bit-identical (DeterminismTest fingerprint); (b) none | (b) a brief loading state: a scene-flow change, so owner approval is needed | DeterminismTest fingerprint, PlayDeterminismTest, perfRun restart timing |
| P2 (proposed) | Lazy model and shader loading | Models and shader variants load inside the frame that first draws them | Warm all world models (and draw each once off-screen to compile shaders) during the existing loading or splash time | Removes one-time hitches of up to about 200 ms per asset | None | None (lossless) | perfRun max world stage; cold-start time must stay under the 4 s target, so it must be measured on a phone first |
| P3 (proposed) | Autosave | Serialisation plus `fsync` on the render thread every 8 s (+5 ms here; possibly tens of ms on phone flash) | Snapshot on the render thread, write on a background thread (atomic writes stay as they are) | Removes the periodic autosave hitch | Low, but it touches save consistency, so it needs careful tests (kill-at-any-moment, resume) | None | SaveStore round-trip, resume, kill tests; perfRun autosave frames |
| P4 (investigate on device) | Respawn frame | 190-275 ms to present the frame after a respawn on the software renderer | Profile on a real GPU (Android GPU inspector / RenderDoc) before changing anything | Unknown until measured | — | — | On-device frame capture |

Not changed, on purpose:
* the duplicate save.json write (about 0.2 ms per 8 s);
* `Boolean.getBoolean` in `readInput` (no samples);
* the 6-step cap and dropping time below 10 fps (a timing-semantics change);
* anything visual.

## G. Before / after (F1 + F2)

Same seed, same autopilot route (both climbed to 477 m), same machine, back to back:

| Metric | Before | After |
|---|---|---|
| stream stage mean (includes the crash line) | 0.071 ms | 0.016 ms |
| hud stage mean | 0.186 ms | 0.132 ms |
| median allocation per frame (autopilot idle) | 9.85 KB | 7.20 KB (-27%) |
| game CPU per frame (render minus autopilot), mean / p99 | 6.82 / 12.93 ms | 6.00 / 11.20 ms |
| GC (7 min) | 18 collections, 94 ms | 18 collections, 64 ms |

Only the stream and hud deltas and the allocation drop are attributable to F1 and F2 (about 0.11 ms and 2.7 KB per frame). The rest of the drop in the game
CPU figure is in the world stage, which these changes do not touch: that is run-to-run variation on this machine, not a claimed gain. On a phone the saved
time should be larger in absolute terms (slower string formatting on ART) but has not been measured there.

## H. Outstanding issues and limits

* Nothing was tested on Android, Windows or Steam Deck hardware, or on any real GPU. The frame-pacing, GPU and input-latency numbers must be taken on a device.
* The C2 generator slowdown (P1) is reproduced and narrowed down but its root cause is not isolated. The shipped desktop runtime (JDK 17) was not tested locally (only JDK 21 is installed here).
* The respawn present cost (P4) is unexplained on this software renderer.
* "Single-speed virtual stick" and "sprint toggle" do not exist in Build 71 (see D); please clarify what they refer to.
* Speedrun policy questions for the owner: the slow-speed assist and jump forgiveness are not recorded with times (E1).
## Long session (stability)

* **30-minute run** at 640x360, cap 60: 51,663 frames, a fall every 45 s, a restart every 10 min, 214 autosaves; it climbed to 500 m.
  * 89 young GCs, 333 ms in total.
  * No crash, no stuck state the game caused (the stuck-skips are the test autopilot's), no growth in frame times over the run.
  * About 15 s of 1.3-1.4 s frames around t=1500 s were the **test autopilot** replanning (autopilot_ms of about 1,350 ms per frame), not game work.
  * One 23.6 s stall at the first restart happened while this machine was also compiling and running tests in parallel. A clean repeat (12 minutes, 4 restarts, nothing else running) measured 1,233-1,291 ms per restart, max interval 1,361 ms, so it is attributed to the machine, not the game.
* **Memory: no leak.** The harness's "heap after the last young GC" rose 27 → 72 MB, but that figure counts dead old-generation objects G1 had not yet collected.
  * Full-GC class histograms of the clean repeat show the live set at **8.7 MB (2 min) → 9.8 MB (7 min) → 11.2 MB (12 min)**, across 4 restarts and 15 falls.
  * All of the growth is `double[]`: the harness's own per-frame rows (2.65 MB, 16,986 rows). Nothing else grew by more than 25 KB.

## Option B investigation: one movement speed (owner-approved direction, not yet implemented)

The shipped code keeps Build 71 movement. The measurements below ran in a separate worktree in which `Sim.step` reads both stick axes as direction only. Because the course generator's proofs and the solver drive the same `Sim`, they get exactly the player's input there.

* **Course generation and course codes.** I compared every framed slice (exactly what a course code reproduces) from the start to castle 10 for seeds 1, 7, 21, 99, 130 and 178: 470 slices in total.
  * Direction-only and Build 71 gave **bit-identical slices, 470 of 470**, with the same generation time.
  * Course codes would keep meaning the same courses, so no `RULES_VERSION` bump is needed.
  * Per-seed and global records stay valid, and climbs in progress resume on the same tower.
* **The generator's proofs.** Every link is proven with direction-only input: the generator accepted the same modules with it.
* **The whole-course solver** (the stand-in for a person playing a slice end to end) is where the change matters. In CI with direction-only input it failed on:
  * EndlessTest, seed 1 slice 6 (unframed chain);
  * FramedGenerationTest, seed 5 slice 6;
  * OfficialWorldRulesTest, seed 1.

  The typical case is planning the next move while airborne off a spring (6.9 m/s): the solver used partial air steering there. A player with a direction-only stick handles this by coasting and tapping back to brake, a normal human technique that needs no frame-perfect input. The solver needs policies that do that, used only in the whole-course run so that generation (and so course codes) stays identical.
* **Found along the way (already in Build 71, independent of the stick).** Three of the 470 slices are not completed by the whole-course solver even with today's analog stick:
  * seed 1, slice 67 (air plan after a spring);
  * seed 1, slice 72 (ground plan);
  * seed 21, slice 44 (air plan after a spring).

  CI's solver tests do not climb that high. Each link is proven on its own, so these are probably solver limitations rather than impossible jumps, but that is not verified. They have to be resolved as part of this work.
* **TowerStreamingTest** diverged slightly under direction-only input (streaming against whole tower: y 149.3458 against 149.3437 after 56 window rebuilds). This is a determinism question that must be explained before shipping.
