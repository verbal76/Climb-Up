# Handoff

## Latest batch — playtest fixes + single-source CI (branch `ccr-cb458cd1-w4dh5c`)
Owner (Kevin) is playtesting EXE on PC + APK on phone. This batch, all on the one branch `ccr-cb458cd1-w4dh5c`:
- **Grip poses fixed (all characters):** `HeroRig.gripLine` flung both hands OUT to the sides; now rope hands converge onto the line (one high/one low, hand over hand) and cable hands spread apart ON the bar. Worst on the astronaut skeletons. Verified by the new `:desktop:rigShot` harness (renders every character in every grip/stance to `assets/build/rigshot/*.png` with a reference bar on the line).
- **Idle arms rest:** new `HeroRig.armsRest()` AIMS the arms straight down (works on both the astronaut and bunny/hamster skeletons) so standing no longer reads as a T-pose. Not applied during run or idle beats.
- **Aimed cannon:** ball now leaves the muzzle tip (`AIM_MUZZLE`), the barrel HOLDS its fired line while the ball crosses (`ballFlying`), it LEADS a moving player (`AIM_LEAD`), and it stands on its own pillar BEHIND the lane (`WorldRenderer.AIM_DEPTH`) so you land on clear grass. Plain CANNON stays the fixed straight-line shot (owner's requested mix). Barrel/ball alignment proven correct by `:desktop:cannonShot`.
- **Cable posts** moved flush behind the lane (no longer punch through platforms).
- **Single-source CI:** `windows.yml` rewritten to build **and** release the EXE from `ccr-cb458cd1-w4dh5c` (one branch for both platforms, like `android.yml`); retires the old `exp/upwardly-windows` + `exp/windows-release` three-branch dance. APK title "— Android APK", EXE title "— Windows EXE".
- Branch note: `ccr` and the old `exp/upwardly-windows` had byte-identical `core/src/main`; the "it never shipped" was insufficient fixes (grips/cannon never actually looked right), not lost branches.

Tests: GripProbe PASS (limb scale guard, all 6 characters); AimedCannonTest +2 (muzzle, barrel-hold); cannon/mortar/ballpath/fit green. Full `:core:test` run at this checkpoint.
Open/deferred: hamster whiskers clip the face (model-geometry nit); Steam Deck compatibility pass — deferred by owner until grips resolved (now resolved); general "other assets clipping" — only the two cited (cannon pillar, cable post) addressed.
Next action: push `ccr-cb458cd1-w4dh5c` → `android.yml` (APK) + `windows.yml` (EXE) both build+release from the same commit; watch both runs; send the owner both links.

## Current state (version 1.1.4, OTA-capable release candidate)
- Streaming tower, floating origin, v6 save format, solver fixes: validated at source commit `6199e59` (see docs/VERIFICATION.md); unchanged by the OTA work (diff since is OTA, CI, version, docs only).
- Signed OTA (runtime 2, channel `release`, `ota` release tag): docs/OTA.md. Secrets and signing identity: docs/CI_SECRETS.md. Publish later content by editing `assets/data/tuning.json` and raising `tools/ota/content_version.txt`.
- Release flow: push to `ccr-cb458cd1-w4dh5c` -> `android.yml` (secrets proof, full suite, APK signed as Build 42, cert gate, OTA baseline sign+verify, APK release, OTA baseline publish).
- Owner to do: delete the leftover `verify-secrets` branch on GitHub; install and playtest on a phone (frame time, rebuild hitches, long falls, OTA check in Settings > About).


State: endless candidate 0.1.0 on branch `ccr-cb458cd1-w4dh5c`. Core loop, endless sliced tower, hazards, springs, keys + castles, crabs + club, controls, settings, saves, audio, Android project and CI exist. See `docs/VERIFICATION.md`, `docs/DECISIONS.md` (section "Endless climb").

Next steps:
1. Playtest on the phone: tune `assets/data/tuning.json` (hazardStartY/hazardRampY/minTimingWindow/chunkHeight/rampHeight and movement numbers) and re-run `:core:test`.
2. Done in the OTA-baseline candidate (not yet built or pushed): shared debug keystore, family-test OTA (docs/OTA.md). Open: colorblind palette modes, grappling hook.
Test hooks and commands: README.md.

## Latest batch (see docs/DECISIONS.md and docs/VERIFICATION.md)
Seesaw + floating bridges, ramps (plain/crumbling/shaky/sinking/ski jump), fall-to-lower-level rule, audio watchdog, title hero picker, hamster rebuilt on the bunny skeleton, four astronaut characters, character juice, space scenery (`render/SpaceScene.java`), prompt audit (`docs/PROMPT_AUDIT.md`). Owner rule: release builds only when a batch is complete (no incremental pushes).
Open: colourblind/subtitle options, phone verification. OTA candidate: see docs/OTA.md (needs one replacement APK).
