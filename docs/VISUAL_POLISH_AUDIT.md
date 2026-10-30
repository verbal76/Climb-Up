# Visual polish audit and atmosphere prototype (branch `visual/build83-atmosphere`, from Build 83 head d218fa9)

Scope: rendering only (`core/.../render/`). Nothing in `sim/**`, collision, physics, RNG, input timing, `tuning.json` or balance was touched.
The prototype is **off by default** behind one master switch and, when off, the game builds exactly the shader, lights and draw calls it did before.

## 1. What already exists (audit)

| Area | Where | What it does |
|---|---|---|
| 3D shader + colour grade | `Grade` | libGDX default shader with a saturation 1.22 / contrast 1.10 curve injected before the fog (keeps MSAA, skinning, fog). The 2D sky gets the same saturation. Ambient x0.84, key x1.08. |
| World light rig | `WorldRenderer.render`, `Palette` | Per world: ambient, key (sun/moon, own direction), fill from the opposite side, warm/cool bounce from below (fake GI on undersides). Blended over the last 20 % of each world; Deep Space wraps back to Meadow. |
| Hero light rig | `heroEnv` | Separate 4-light rig: stronger key, fill x1.4, bounce, and a contrasting rim light per world so the bunny never melts into the sky. No fog on the hero. |
| Fog | `env` Fog attribute, `cam.far = 64` | libGDX distance fog in the sky's colour (bottom->top 25 %). With the camera ~10 m from the play plane it only touches geometry far away; in practice it fades in the far vertical edge of the visible climb. |
| Sky | `Background` | 3-stop gradient (Build 77 morning skies for Meadow/Frost, 2-stop for the rest), sunrise lift through the morning worlds, golden hour castle 1 -> 2, two star layers fading in from Dusk to Deep Space (no reduced-motion scroll). |
| Sun / moon | `Background.renderGlow/renderShafts`, `Palette.LIGHT_*` | Per-world glow (position, size, strength) and four soft additive light shafts with a slow sway (sway off under reduced motion). Gated by `quality > 0` and `GfxHooks.skyEffects`. |
| Far parallax layer | `SpaceScene.renderFar` | Planets, far clouds in 5 depth bands (farther = hazier, Build 77 star/cloud fix), sky ships, Deep Space astronauts, drawn with a wide-range camera before the tower. Own environment, no fog. |
| Near scenery | `SpaceScene.renderNear`, `Clouds` | Scanning ship, fly-bys, asteroids; breakable Quaternius clouds in the play plane (avoid every platform/hazard); none in Deep Space. |
| Shadows | `ShadowPass` | Planar projected shadows onto platforms (stencil or depth one-coat), blob shadow for the hero. |
| Vignette | `drawVignette`, `Palette.VIGNETTE` | Soft dark vignette, stronger at night and in space. |
| Particles | `Particles` (pool 96) | Landing dust, bounce rings, crumble debris, gem pop and sparkle, key sparkle stream, footstep dust, creature cues. |
| Ambient motes | `WorldRenderer.ambientMotes` | Pollen / snow / embers / fireflies / space dust, all in the play plane, `quality > 0 && !reducedMotion`. |
| Glow | emissive colours | Hazards glow warm (0.30, 0.05, 0.03); keys/gate flags 25 % of key colour, shape markers 35 %; gems 0.45-0.8 red; turbo chevrons, spinning-ramp rim; every box part 15 % of its colour. No bloom (correctly: no blur over sprites/text). |
| Castles / keys | `gateInst`, `KeyColors` | Ten castles per cycle (every 500 m, 2 per 1000 m world); gate flags in the key colour + shape marker; colourblind-safe palette swaps the instances. |
| World transitions | `Palette.blend*` | Colours/lights/sun blend over the last 200 m of each world; stars, clouds, planets fade by zoneF; space weather off from zone 3.9. |
| Characters | `HeroRig`, `Characters`, `Models` | Skinned rig (29 bones, 32-bone shader), squash/stretch, grip poses; models loaded once (Kenney/Quaternius/Space Kit, CC0). |
| Quality knobs | `quality` 0/1/2, `GfxHooks` | Decorations, rocks, clouds, motes, far layer and sky effects need quality > 0; `renderScale` FBO, mipmaps/anisotropy (desktop). |

### What was missing

1. **Depth in the 3D picture.** The tower is effectively one plane 10-14 m from the camera; the libGDX fog (quadratic to 64 m) leaves it untouched, so the cylinder's edges do not recede and the scene reads flatter than the "built around a cylinder" look wants.
2. **The far layer competes with the play.** Planets are fully saturated and often sit directly behind the hero or a platform (e.g. Dusk 2250 m and Night 3750 m shots below); nothing grades them into the distance.
3. **No cue for "a long way down".**
4. **No selective glow.** Every box part has the same 15 % emissive as the important things, so gems/keys/hazards rely on colour alone to stand out.
5. **Castles inside a world look identical** (only castle 1 -> 2 has its golden hour).
6. **Sky:** no zenith deepening; ambient motes all sit in the play plane (no depth in the air).

## 2. What the prototype adds

Master switch: **`Atmos.ENABLED`** = `Atmos.DEFAULT_ON` (compile-time constant, `false`) OR the JVM system property **`-Dclimb.atmos=true`**. Not a player setting; read once. Android never sets it, so release builds are unchanged.

| Feature | How | Guard rails |
|---|---|---|
| Depth haze (aerial perspective) | Shader: per-vertex view depth along the camera direction minus the play plane's own depth (`u_atmosView`), linear ramp 1 m -> 6 m behind the lane to a per-world haze colour (half world palette, half the live fog colour; fully the fog colour during golden hour). | Measured from the plane, so the camera pulling back in a fall changes nothing; only under `fogFlag`, so the **hero (no fog) is never touched**; strongly coloured emissive (hazards, gems, keys, flags) keeps 85 % of its clarity. |
| Depth-graded far layer | `Atmos.fogFarLayer` gives `SpaceScene.env` the world fog colour so the same shader hazes planets, far clouds, ships and astronauts (hundreds of metres behind the plane -> full haze, 0.26-0.40). | libGDX's own fog there stays negligible (far camera range 2500 m). The sun glow and shafts are untouched. |
| Low mist below | Shader: fragments more than 5.5 m below the camera (bottom of the screen and below) mix toward a per-world mist colour over 12 m. | Strength 0.16-0.26; glow-protected; shows mainly in long falls (where the camera leads down). |
| Selective glow | Shader: emissive with chroma (max-min channel) above ~0.12-0.24 gets +45 % more emission; grey/faint emissives (clouds, creatures, the 15 % on every box) get none. | No blur, no flashing, no pulsing added. Only under `fogFlag` (never the hero). |
| Sky refinement | `Atmos.drawSky`: deeper, cooler zenith over the top 45 % and a haze band rising over the bottom 42 %, drawn over the gradient **under** the sun glow, far layer and shafts. | Strengths <= 0.30; zenith halved in golden hour. |
| Per-castle identity | `Atmos.CASTLE[10]`: one subtle rgb multiplier (0.93-1.07) per climb between castles, blended over the last 25 m before each castle; on the sky colours (so also clear colour and fog) and 60 % of it on the world ambient + key light. Start and castle 1 -> 2 (golden hour) neutral. | **Never** on the hero's lights; under COLOURBLIND-SAFE COLOURS only the sky gets it (keys/gates are lit exactly as before). |
| Depth motes | `AtmosMotes`: pool of 36 small emissive cubes 1.6-6.5 m **behind** the lane (pollen, ice glints, embers, fireflies, star dust), slow drift, gentle fade. | HIGH quality only, off under REDUCED MOTION, own `RandomXS128` (never the game's or `MathUtils`' stream), depth-tested behind every platform/hazard/hero, no per-frame allocation. |

Quality: LOW = no haze/mist/glow/sky overlay/motes (strengths zero; the free castle colour shift remains); MEDIUM = everything except motes; HIGH = all.
Cost: one extra varying + ~12 ALU ops per fragment under fog, one full-width ShapeRenderer rect pair per frame, up to 36 extra tiny draw calls at HIGH. No allocations per frame, no extra render target, no post pass.

### Files
- `core/src/main/java/com/hotatticgames/climbup/render/Atmos.java` (new: switch, data tables, shader injection, sky refinement)
- `core/src/main/java/com/hotatticgames/climbup/render/AtmosMotes.java` (new)
- `core/src/main/java/com/hotatticgames/climbup/render/WorldRenderer.java` (6 guarded hooks, each `atmos != null` / `atmosMotes != null`)
- `core/src/test/java/com/hotatticgames/climbup/render/AtmosTest.java` (new, 6 tests)
- `desktop/src/test/java/com/hotatticgames/climbup/desktop/AtmosShot.java` + `atmosShot` task in `desktop/build.gradle` (A/B harness)

### Try it
- Desktop: `./gradlew :desktop:run -Dclimb.atmos=true` (the run task forwards `climb.*`).
- Screenshots: `xvfb-run -a -s "-screen 0 1920x1080x24" ./gradlew :desktop:atmosShot [-Dclimb.atmos=true] [-Dclimb.w=1600 -Dclimb.h=720] [-Dclimb.quality=1] [-Dclimb.reducedMotion=true]`
- To ship it later: flip `Atmos.DEFAULT_ON` (or wire a setting); to remove it: delete the two classes and the six guarded hooks.

## 3. Validation actually run (Linux sandbox, JDK 21, Xvfb + Mesa llvmpipe software GL)
- `:core:compileJava :desktop:compileJava` - OK.
- `:core:test --tests render.* --tests KeyColorsTest --tests VisualDepthTest` - 25 tests, 0 failures (includes the new `AtmosTest`). The full core suite (generator/solver sweeps) was **not** run, per the no-long-simulation rule; `sim/**` is unchanged.
- `:desktop:test` - 129 tests, 0 failures.
- `:desktop:atmosShot` real-renderer captures, switch off and on, at 1280x720 (16:9) and 1600x720 (20:9 phone), ten heights (one per castle climb, all five worlds); plus HIGH vs MEDIUM+reduced-motion and LOW smoke runs. The final player positions printed by the off and on runs are identical at every height (the renderer does not feed back into the simulation). Shader compiled without errors on Mesa (desktop GL); **not** compiled on a GLES2 phone driver.
- Inspected every composite: hero, HUD-free play plane, hazards and keys unchanged in clarity; far planets now recede (the hero no longer fights the planet behind him at 2250 m and 3750 m); cylinder edges recede slightly; golden hour stays warm; the sun glow is intact; nothing washed out. Space stays clear by design (lowest haze).

Composites (left OFF, right ON) in `docs/visual/`: `atmos_1280x720_h{750,1750,2250,2750,3750,4750}.png`, `atmos_1600x720_h{250,1250,3250,4250}.png`.

## 4. Risks and next steps
- **GLES precision / drivers:** the injected code is plain GLSL ES 1.0 under `#ifdef fogFlag` with a fall-back to the unmodified shader if the anchors are not found, but it has only been compiled on Mesa. Run the Android build with the switch forced on (`DEFAULT_ON = true` on a test build) on the owner's phone before enabling it.
- **Edge readability:** destination platforms at the far left/right edge on 20:9 take up to ~15-20 % haze. Hazards and keys are protected; if the owner finds edge platforms harder to read, lower `HAZE_A` or raise `HAZE_NEAR`.
- **Castle tints are subtle by design** (<= 7 %); they read as a mood shift over minutes rather than a hard change at the gate. A short colour "swell" at each castle door would make the change felt (off under reduced motion).
- **Not covered:** title screen (uses the same `WorldRenderer`, so it follows the switch), performance on a real phone (the cost is small but unmeasured on device), owner taste. Pixel-level comparisons include the game's unseeded cosmetic particles, so exact OFF-vs-OFF identity was checked by code path (all hooks guarded), not by image diff.
