# Upwardly (formerly Climb up): audit for the Game Designer

What changed from the original package (spec v6, `CLAUDE.md`) and why. Written from the repository history, `docs/DECISIONS.md`, the earlier `docs/PROMPT_AUDIT.md` (builds up to 23), `docs/PACKAGE_FEEDBACK.md`, and the owner's later instructions. Items marked (?) are things the repository cannot answer: ask the owner.

## 1. The two big misses

### 1a. The OTA system was specified in a way that does not match what the owner wants
| | What the spec said | What the owner actually wants |
|---|---|---|
| Scope | "Content, tuning AND game logic expressed as sandboxed data/scripts the game interprets (no native code)" | Fix gameplay bugs, change visuals and assets, and add features after install, without reinstalling. |
| Checking | Silent, at most once a day, unmetered only. Manual "check now" lives only in Settings. | Look for an update **immediately at every launch**. Nobody will dig through Settings > About. |
| Applying | Only on the next cold start. "No restart prompts, no popups." | A short in-game panel (same style as the game): checking, downloading, applying, then the game **restarts itself** and the player is back playing. |
| "Least intrusive" | Read by the designer as "invisible". | The owner means "least effort for the player": no hunting, no manual restart. |
| Shipping | Publish through GitHub. | Same, and the owner must be able to see and test it on a phone within minutes. |

What was built, in order:
1. Build ~42: a signed **data-only** OTA (`tuning.json`, a few KB). It followed the spec literally, so it **could not deliver a gameplay fix**.
2. "OTA-FULL" programme (two engineers): a **module host**. The APK is a thin host that loads the game as a signed code module (DexClassLoader), with signed asset overlays, rollback, anti-rollback, crash-loop protection, save guards. This is the only way to change game code over the air. It deliberately breaks the spec line "no native code / no arbitrary code execution", and it is not allowed on Google Play (so a store edition must be built without it; a gate for that exists).
3. Live channel: GitHub Actions signs and publishes to a branch (`ota-exp-channel`); the app checks it at every start; Settings > About > CHECK; automatic title-screen apply and relaunch (v13+, relaunch fixed in v17).

Recommended OTA design for the next spec (what it should say):
- **Two editions.** Android sideload edition with code+asset OTA through a module host (what exists). Store/Steam editions with no code loading; Steam updates itself; Windows needs no OTA.
- **Automatic, visible, short.** Check at every launch (and when resuming after a while). Download in the background. When ready and the player is on the title screen: themed panel, then self-restart. Never during a climb; if a climb is in progress, wait for the title screen or Save & Exit.
- **Version shown to the player:** game version + OTA version only (e.g. "V1.1.5 OTA 26"). Drop the build counter from player UI (it only exists because Android needs a rising versionCode).
- **What OTA can and cannot change.** Can: game code, art, audio, data, text, in-game name. Cannot: the app name/icon under the launcher icon, permissions, package id, signing, the host itself. These need a new APK (an in-place update that keeps saves). The spec should list this so nobody promises it.
- **Safety kept as is:** signed manifest, per-file hashes, anti-rollback, rollback after 2 unconfirmed launches, atomic staging, bundled fallback.
- **Channels:** experimental vs production kept separate; promote by publishing the same signed release to production only after the owner approves on a device.
- **Rules the spec should adopt:** the newest playable build is always a normal GitHub Release (never a pre-release, never only an Actions artifact); the next OTA number is always one above the highest published (a collision cost a release once); CI cost matters (the owner has a small usage budget): ship from its own branch, avoid running the full emulator matrix per push.

### 1b. Look and feel drifted from the brief
Spec: "Voxel-look 2.5D", use the supplied asset packs, the cylinder "doesn't really exist", camera locked on the player.
Built:
- The hero is the Quaternius **smooth low-poly** character (it replaced a voxel-extruded robot in build ~14). Platforms are the Kenney block set (rounded). `docs/PACKAGE_FEEDBACK.md` flagged "voxel vs the smooth kit" at the very start and nobody resolved it. **The look is therefore smooth 3D with a pixel-font UI, not voxel.**
- The sky had flat 2D "bar chart" cloud strips (procedural voxel look) next to real 3D clouds: visually inconsistent. Replaced in v22 by 3D clouds in far layers (still a first pass).
- (?) The owner said the overall feel was not what they asked for. The repo has no written statement of what the target feel was beyond the A1 quote. The designer should ask for 3 reference screenshots or games, and write the look down as rules (palette, shape language, camera distance, UI style).

## 2. Other deviations from the spec (what changed, who asked, recommendation)
| Area | Spec v6 | Now | Origin | Recommendation |
|---|---|---|---|---|
| Win condition | Reach the top | Endless tower, plus an "official 5,000 m run" with ten castles and a timed finish and speed-run clock | owner | Update the spec: endless + official run |
| Combat / enemies | None. No enemies, no weapons, no health | Crabs that shove you, bees, a floating spiked club with a Swing button to knock crabs off, hazards that knock you off (never kill) | owner | Spec needs a "displacement hazards, no damage, no death" rule; the club is a pickup, which touches "no power-ups" |
| Progression | None; player never stronger | Still none in mechanics. Added: personal bests, splits, character choice (cosmetic) | owner | Fine; state "stats and cosmetics only" |
| Keys and castles | Not in spec | Coloured keys, castle gates, red-gem checkpoints (exactly one per section, halfway between castles) | owner | Add to the obstacle catalogue |
| Worlds | 4 themes | 5 (Meadow, Frost, Dusk, Night, Deep Space) | owner | Update; each world now has its own lighting and light in the sky |
| Characters | One hero | Bunny, hamster, four astronauts, all on one animated skeleton | owner | Spec a "character set" with a rig requirement |
| Obstacles | Traversal grammar of ~12 verbs; grappling hook mentioned by owner but not in the list | Many more (saws, cannons, spike traps, seesaws, bridges, ramps, springs, breakable clouds, new mover types). Cable yes, grappling hook never built | owner + builder | The designer should write a real obstacle catalogue with numbers |
| Movement numbers | "Documented and tunable", none given | All invented (`tuning.json`) and tuned by the owner over many builds | builder | Capture the final numbers as the baseline in the spec |
| Fail behaviour | "They never really fail", instant respawn | Falling below the lowest platform returns to the last checkpoint; a hazard hit knocks you away with a short grace period and does NOT teleport you to a checkpoint | owner | Write it down as a rule |
| Save and continue | "Persist checkpoint, best height, settings" | Exact-state resume (run snapshot), Save & Exit, Continue, confirmation before New Run, separate run file | owner | Spec the full list of state that must be restored |
| Name | "Climb up" | **Upwardly** (capital U only, never all capitals, in names and file names; the pixel-font title is capitals because of the font). Title lettering reads "UPWARDLY"; the big title's D can look like an O (?) | owner | Update the spec and the rules for casing |
| Platforms | Android only | Android plus a Windows EXE, with Steam planned within days (Windows branch exists) | owner | Add Windows/Steam to the spec; decide what is shared (one gameplay core) |
| Package identity | `com.hotatticgames.climbup`, never change | The OTA-capable app, `com.hotatticgames.climbup.otaexp`, **is now the production Android release** (owner statement). It started as a separate test app, so it is a different app from Build 44: saves and settings did not carry over. Its display name is "UPWARDLY" (to become "Upwardly" on the next APK) | builder + owner | The spec still says the package id must never change. Decide: keep `.otaexp` as the permanent id (and rename it in the spec), or migrate to `com.hotatticgames.climbup` before a public release. Plan the save migration either way |
| Versioning | Semantic version + build code | Players should see game version + OTA version; build code stays internal | owner | Update |
| Distribution | Just me / sideload | Still sideload on Android; Steam for Windows | owner | Update |
| Audio | CC0 only | Owner-supplied music, effects and the hamster model; some licences unconfirmed (logged in ASSETS.md); the falling sound was made by the owner with Suno | owner | Add an "owner confirms licence" step to the spec |
| Accessibility | Full list | Partial: text size, reduced motion, haptics, assists, left-handed. Colourblind palette and subtitles incomplete | builder | Mark must-have vs later |
| OTA key | Pinned key | Existing OTA key reused for the experimental module channel by owner decision | owner | Document the key policy |

## 3. Things the spec asked for that are not done
Colourblind palette and subtitles (partial), the unmetered-connection condition for OTA (needs an extra permission the spec also forbids), grappling hook, Play Store AAB release, verification on a physical device (the owner is the only tester; emulator checks exist), the brown-post fix was done late and unverified, a second review of voxel look.

## 4. How the owner works (put this in the next prompt)
- Plain language, short, yes/no where possible. A question is a question: do not start work on it. Do not repeat explanations. Do not ask for approval for work that was already authorised.
- Releases: the newest playable build goes to Releases as a normal release, with a plain file name ("Upwardly.apk", "Upwardly-Windows.zip").
- The owner tests on a Pixel; claims must say what was and was not verified.
- Usage budget is limited: avoid repeated test sweeps and CI loops; reuse evidence.
- The owner wants the live feel to be "the game just updates itself".

## 5. Suggested next steps for the designer
1. Resolve the look: voxel or smooth 3D. Write rules.
2. Rewrite the OTA section as in 1a, including the two-edition model and the can/cannot-change list.
3. Add the obstacle catalogue, the fail/respawn rules and the save-state list from `docs/DECISIONS.md`.
4. Decide identity/migration for the public release (package id, saves, Play vs sideload vs Steam).
5. Put the owner's working rules (section 4) into the standing instructions.
