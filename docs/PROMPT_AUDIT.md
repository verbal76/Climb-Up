# Prompt audit: what the spec said vs. what had to be added

Audit of `CLAUDE.md` (spec v6) against the game as built (build 23). Purpose: feed back into the prompt-maker so the next generated prompt already contains what this build had to invent.

Legend — **S** = stated in the spec (Part A owner words or a Part C engineering item); **I** = implied by the spec but needed real design to make concrete; **A** = added (not in the spec at all). Category: **CORE** = mechanic/rule that changes how the game plays; **STYLE** = look, sound, feel, UI, polish.

## 1. Summary

| Bucket | Count (approximate, from the lists below) | Share |
|---|---|---|
| S – in the spec, built as written | 22 | ~26% |
| I – implied, had to be designed | 23 | ~27% |
| A – added during the session (owner chat or my own decision) | 40 | ~47% |

Of the 63 items that were I or A: **core mechanics ≈ 27 (43%)**, **style/design/feel/tooling ≈ 36 (57%)**.
Short version: the spec fixed the *genre, camera, goal, controls, tech and rules* (what the game is). It did not contain a single concrete obstacle spec, number, level layout, character, or sound — those were all supplied by design choices or by you in chat.

## 2. In the spec and built as written (S)

CORE: 2.5D platformer; spiral tower built around a non-existent cylinder; camera anchored on player, tower rotates; jump; trampoline pads; moving platforms (up/down/left/right); ropes; swinging platforms; crumbling platforms; cable grab/shimmy; ledge grab + pull-up ("fingertips"); checkpoints with quick retry; win at the top; no combat, no health, no progression, no power-ups; procedural generation with a solver proving every stage completable; difficulty rises.
STYLE/PLATFORM: Android, landscape, floating touch stick + buttons, libGDX, 60 fps target, 48/56 dp touch targets, voxel-look, studio splash from your logo, package ID, GitHub Actions APK, saves (progress/stats), pause menu, settings menus listed.

## 3. Implied but needed real design (I)

CORE
1. **Movement numbers** (run speed, jump height, gravity, coyote time, jump buffer) — spec says "documented and tunable", gave none.
2. **Cylinder math**: arc coordinate wrapping at a 62.8 m circumference, collision on a ring.
3. **"Spiral" shape**: pitch controller keeping layers a fixed height apart; layer-clearance rule so one revolution never intersects the next.
4. **Fairness definition**: "near-miss, last-second catches" became a measurable *success margin* per jump (share of input timings that work) and a minimum.
5. **Rest platforms/checkpoint rhythm** ("challenge alternates with recovery") → a checkpoint every few modules.
6. **Dead ends / misleading platforms** (from "puzzle-like", "find a way around") → decoy platforms that never become the route.
7. **Death = respawn at checkpoint** with state restoration (crumbled platforms reset) and brief invulnerability.
8. **Autopilot / planner** as the "automated solver" — had to be invented (policies, timing sweeps).
9. **"They don't really ever fail"** → resolved as: falling costs distance only.

STYLE/UI
10. Touch layout details (stick side, jump placement, left-handed option); HUD content (height, best, progress bar); pause/menu flow; title screen layout.
11. Contextual tips ("tips as things appear") → one-shot toast per obstacle type.
12. Accessibility list → text size, reduced motion, vibration, assists (several implemented; colourblind palette and subtitles *not* complete).
13. Four visual "worlds" (scope table said 4 themes) → palette/tint per height band.
14. Character feel: facing, squash/stretch, landing dust, near-miss pose, wiggle, idle gags.
15. Audio mix: music + SFX buses, volume sliders.
16. Save format versioning/migration, atomic writes.
17. Desktop test hooks for screenshots (tooling).
18–23. Credits screen, ASSETS.md provenance, version/build code from run number, README, VERIFICATION.md, HANDOFF.md.

## 4. Added (A)

### 4a. Core mechanics added — none of these were in the spec
| # | Addition | Origin |
|---|---|---|
| 1 | **Endless tower** (spec: "about 20 minutes, a top to reach"; you asked for "never-ending") | you |
| 2 | Chunked generation: each slice starts on the last rest platform; deterministic per (seed, slice) | me |
| 3 | Difficulty ramp by height (gaps, mover amplitude, hazard density) | me, from your "incrementally harder" |
| 4 | **Timed hazards**: saw blades (horizontal/vertical), cannon + spiked balls, pop-up spike traps, spiked stone blocks, chain-slab that slams down | you (pack) + me |
| 5 | Hazard timing-window rule (every timed hazard must leave a workable window) | me |
| 6 | **Angled spring platforms** | you |
| 7 | **Keys + coloured castles** with gate walls | you |
| 8 | **Provable key rooms** (key is always reachable, even if you must go back down) | you + me |
| 9 | **Crabs** that shove you off a platform (non-lethal, ping-pong) | you |
| 10 | **Floating spiked club** + Swing button to knock crabs off | you |
| 11 | **Bees** that fly in, bump you, leave | you |
| 12 | **Breakable clouds** you jump through | you |
| 13 | **Seesaw bridge** (tips under you, slides you off if you stop) | you |
| 14 | Floating wooden bridge, optionally with a rope above its middle | you |
| 15 | **Fall rule**: a fall only ends below the lowest loaded platform; you can land on lower levels | you |
| 16 | Hazard hit = respawn with 0.7 s invulnerability; air speed no longer decays above run speed | me |
| 17 | Window-based simulation (only ~280 elements near the player are simulated) | me (technical) |
| 18 | Resume the *exact* tower from save (exact-float slice data) | me |
| 19 | Planner extensions: mid-hazard policies, key-room detours, multi-offset landing checks | me |
| 20 | Climbing animations as rules: ledge grab, hang, shimmy | you/me |
| 21 | Character select (bunny / hamster), pure cosmetic | you |
| 22 | Seesaw/bridge tuning set (tilt, slide, uphill slow-down) | me |
| 23 | Audio watchdog (restart silent music) | me |
| 24 | Rule that creatures/hazards never kill — only displace | you (correction) |
| 25–27 | Per-world module weights; zone-gated obstacles (bridges/seesaw from world 2); castle frequency rules | me |

### 4b. Style / design added
| # | Addition | Origin |
|---|---|---|
| 1 | Quaternius "blue guy" bunny as the hero (skinned, 12+ clips) | you |
| 2 | Hamster hero, split into body/arm/foot/ear nodes and animated in code | you |
| 3 | Full per-character animation set: run, jump, big jump, hang, climb, idle, fall, land, hit | you |
| 4 | Animated crab and bee models | you |
| 5 | Quaternius platformer pack models (cannon, saws, spikes, key, gate/tower, clouds…) converted to g3dj | you |
| 6 | Kenney block set for platforms; wooden bridge built from planks | me |
| 7 | Your music playlist (3 tracks + procedural tracks) | you |
| 8 | Your sound-effects pack mapped to events via `sfx.json` (random variants) | you |
| 9 | Procedural SFX for events the pack lacks (cannon, saw, spikes, key, door…) | me |
| 10 | Title lettering "CLIMB UP", hero-picker button | me/you |
| 11 | Toasts for key/door/blocked/club/swing/shove | me |
| 12 | Particles: dust, burst, puffs, ambient motes | me |
| 13 | Parallax skyline backdrop, fog, sky gradient per world | me |
| 14 | Damage-free "hit react" flinch animation | you |
| 15–16 | Camera lead on fast rises/drops; FOV kick | me |

## 5. Where the spec was thin (what the prompt-maker should capture next time)

1. **Obstacle catalogue with parameters** — spec lists traversal verbs only; every hazard, creature, key/door idea came from your chat or from me.
2. **Numbers** — jump height/length, gap limits, speeds: left to the builder.
3. **Win/fail contradiction** — "they win by getting to the top" vs "never-ending". The prompt needs one answer (endless vs finite).
4. **Hazard stance** — spec says "environmental hazards that set you back" *and* "no health/damage". Whether hazards reset you or merely push you had to be settled by you in chat.
5. **Characters** — "player character set" with no brief (species, silhouette, personality).
6. **Asset source vs licence** — the policy says CC0; the music, sound pack and hamster licences were *unconfirmed* (logged in ASSETS.md). Needs a "owner confirms licence" step.
7. **Background/scenery plan** — not in the spec at all; (see section 6).
8. **Update/OTA** — fully specified in the spec but **not built**; also colourblind palette and subtitles are listed but incomplete. A prompt should mark must-have vs later.
9. **Prioritisation** — spec "Large" scope + "first build is complete" can't both hold; need a build order.

## 6. Your new asset set

Inventory (nothing missing): 11 planets, 4 rocks, 3 large rocks, 1 ramp, 4 spaceships (Finn the Frog, Rae the Red Panda, Fernando the Flamingo, Barbara the Bee) and 4 astronauts of the same animals — each as `.gltf` **and** `.blend` (all gltf are self-contained, no external files). The hamster has only a `.blend`. The four astronauts each carry **18 animations and a skeleton**, so they can be animated properly (no code-driven rigging needed).

Planned use (not implemented yet):
- **Planets** — far-background parallax layers, inside and outside the spiral, drifting slowly; reduced-motion setting freezes them.
- **Spaceships** — ambient fly-bys in the background; rare close pass that buzzes the player (cosmetic only, no collision, no knock-back, to respect "no combat").
- **Rocks / large rocks** — platform skins, scenery clusters, and cover for the crab/bee/saw areas; keeps generation coherent.
- **Ramp** — a candidate new traversal element (slope you run up to launch); needs a sim + planner rule and fairness check, so it would be a core change, not just art.
- **Astronauts** — four more selectable characters; their 18 clips need mapping to run/jump/big-jump/hang/climb/idle/fall/land/hit (+ shimmy/pull-up).
- **Licences** — I have not seen licence text for any of these; please confirm before they are shipped (spec policy is CC0 only).

## 7. What this build does not yet do

OTA updates; colourblind palette; subtitles; shared debug keystore (every build has a different signature, so builds cannot install over one another); nothing has been run on a phone. Whole-tower solver run still fails about 1 in 30 finite test seeds (the endless generator itself solved 336 of 336 sections in the last stress run).
