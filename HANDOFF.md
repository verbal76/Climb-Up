# Handoff

## Current state (2026-10-13, build 58 — final hamster running fix; branch `ccr-cb458cd1-w4dh5c`)
- **Active branch:** `ccr-cb458cd1-w4dh5c` (open draft PR #1 → `main`; nothing is merged into `main`, by owner instruction). All game work lives here.
- **What build 58 changes vs build 57:** only `assets/hero/hamster.g3dj` (regenerated: `rebase_arm_frames.py` → `remove_paw_stub.py` → new `tools/hamster/retarget_run_arms.py`) plus docs and the published-release title in `android.yml`. The hamster's `Run`/`Walk` upper-arm keys are re-aimed so the arms hang beside the torso (17° outward tilt derived from the body width and hand-ball size), elbows bend forward with the bunny's own angles, and the arms swing fore and aft in opposition to the legs. No code, physics, camera, geometry, rope, cable, ledge, jump or fall change: see `docs/DECISIONS.md` (2026-10-13, last two bullets) and `docs/CHARACTER_REPAIR_PLAN.md` ("Build 58 addendum").
- **Build process:** manual dispatch only. `android.yml` on `ccr-cb458cd1-w4dh5c` (GitHub MCP `actions_run_trigger`, `workflow_id: android.yml`, `ref: ccr-cb458cd1-w4dh5c`; input `test_build=true` makes a draft release and skips the OTA publish). It runs `:core:test`, signs with the Build-42 identity (certificate gate), names the APK `upwardly-<version>-build<N>-debug.apk` (N = the run number), uploads it as a workflow artifact, verifies the OTA baseline and publishes the GitHub Release `v<version>-build<N>` titled "Upwardly build N apk". Version `1.1.5` (`build.gradle` `appVersionName`); the build code is the run number. Pushes run only `ci.yml` (validation, publishes nothing). The OTA channel is left untouched while it already carries the bundled content version (currently v1).
- **Validation gate for any character/grip change:** the faithful desktop harness `desktop/src/test/.../GameplayShot.java` (see `docs/CHARACTER_REPAIR_PLAN.md` Phase 1) — e.g. `-Dclimb.chars=1,0 -Dclimb.states=run -Dclimb.settle=8 -Dclimb.seq=40 -Dclimb.seqGap=1` for a run cycle, `-Dclimb.startOn=cable|ledge` for the scripted states.
- **Known remaining issues (all pre-existing, none introduced by build 58):** hamster rope climb — the upper hand ball can touch the lower muzzle in one climb phase (eyes never covered); Barbara/Rae raise an arm in front of the face while falling (`Jump_Idle` clip pose); hamster cable pose keeps its head above the cable; hamster legs/ears/spine keep a rest-frame offset with no visible symptom; the hamster's `Walk` clip (slow stick) carries the same arm correction but was verified offline only; the hamster's hand balls rest against the body at the back-swing extreme (by design, ≤ 23 % of the ball radius). Older open items: glyph-pack integration, colourblind palette modes, Steam items needing Steamworks, title-menu declutter.
- **Owner-controlled publication rules still in force:** no merge into `main`, no OTA update publication, no Google Play / Steam release; only the GitHub test release of build 58 was authorised.

## Previous batch — character hands, rope grip and hamster arms repair (2026-10-13, branch `ccr-cb458cd1-w4dh5c`)
Owner/reviewer-controlled repair after the 12 October hand-fix attempts were found to have been judged on a non-faithful harness. Diagnosis: `docs/HAND_ROPE_FORENSIC_AUDIT.md`; plan + results: `docs/CHARACTER_REPAIR_PLAN.md`; decisions: `docs/DECISIONS.md` (2026-10-13 entry).
- **Hamster model regenerated** (`assets/hero/hamster.g3dj`) by two new deterministic tools run on the pristine builder output: `tools/hamster/rebase_arm_frames.py` (arm bone rest frames aligned with the bunny's so the copied clips swing the arms correctly: no more horizontal arms while running, jumping, falling or leaving a rope) then `tools/hamster/remove_paw_stub.py` (the paw stub that showed beside the ball hands is gone; arm ends capped). Ball size/colour, feet, muzzle caps unchanged.
- **`HeroRig.gripLine`:** rope hands are aimed at the rope's own depth (`lineBack()/SCALE`) instead of a fixed reach ahead, so both hamster hands land on the line; astronauts grip below the shoulder line (`ASTRO_GRIP_HI/LO`) so the balls no longer cover the visor. Bunny unchanged in effect. No sim, camera, tuning or generator change.
- **Faithful validation harness** `desktop/src/test/.../GameplayShot.java` (real WorldRenderer/HeroRig on real simulated frames, phone aspect; scripted `climb.startOn=cable|ledge`). Use it as the gate for any character/grip change; the old `FullShot`/`RigShot` previews are not representative.
- **Verified in the harness (all six characters):** astronaut face coverage 0 % on the rope (was 10–49 %), hamster both hands on the rope, no stub, arms bunny-equal in every clip; feet, cable and ledge poses unchanged. **Not yet verified on a device** — owner test build pending (android.yml `test_build` input = draft release, no OTA publish).
- Known residuals: hamster upper ball can touch the lower muzzle in one climb phase; Barbara/Rae raised arm in front of the face while falling (clip pose, pre-existing); hamster cable pose (head above the cable) pre-existing and untouched; hamster legs/ears/spine keep a frame offset with no visible symptom.

## Previous batch — playtest fixes + single-source CI (branch `ccr-cb458cd1-w4dh5c`)
Owner (Kevin) is playtesting EXE on PC + APK on phone. This batch, all on the one branch `ccr-cb458cd1-w4dh5c`:
- **Grip poses fixed (all characters):** `HeroRig.gripLine` flung both hands OUT to the sides; now rope hands converge onto the line (one high/one low, hand over hand) and cable hands spread apart ON the bar. Worst on the astronaut skeletons. Verified by the new `:desktop:rigShot` harness (renders every character in every grip/stance to `assets/build/rigshot/*.png` with a reference bar on the line).
- **Idle arms rest:** new `HeroRig.armsRest()` AIMS the arms straight down (works on both the astronaut and bunny/hamster skeletons) so standing no longer reads as a T-pose. Not applied during run or idle beats.
- **Aimed cannon:** ball now leaves the muzzle tip (`AIM_MUZZLE`), the barrel HOLDS its fired line while the ball crosses (`ballFlying`), it LEADS a moving player (`AIM_LEAD`), and it stands on its own pillar BEHIND the lane (`WorldRenderer.AIM_DEPTH`) so you land on clear grass. Plain CANNON stays the fixed straight-line shot (owner's requested mix). Barrel/ball alignment proven correct by `:desktop:cannonShot`.
- **Cable posts** moved flush behind the lane (no longer punch through platforms).
- **Single-source CI:** `windows.yml` rewritten to build **and** release the EXE from `ccr-cb458cd1-w4dh5c` (one branch for both platforms, like `android.yml`); retires the old `exp/upwardly-windows` + `exp/windows-release` three-branch dance. APK title "— Android APK", EXE title "— Windows EXE".
- Branch note: `ccr` and the old `exp/upwardly-windows` had byte-identical `core/src/main`; the "it never shipped" was insufficient fixes (grips/cannon never actually looked right), not lost branches.

Grip VERIFIED in the REAL renderer (`:desktop:fullShot`, not just the synthetic harness): astronauts' hands grip the rope overhead; hamster grips at chest (short-arm limitation, acceptable — a longer stretch splays, reverted). The grip-fix APK (Android build 54) + EXE (Desktop build 26) were published from `f8ed876`.

### Overnight standard-compliance pass (master standard v2.0, provided by owner)
- **Linux x86-64 target added** (`:desktop:linuxJar` + `tools/linux/package.sh`, jpackage native app-image). BUILT + EXECUTED locally: ELF launcher, GL first frame at 1280×800, clean exit, smoke PASS. Third platform now exists.
- **CI publishing safety (§9/§10):** push no longer auto-publishes. `android.yml`/`windows.yml`/`ota-publish.yml` are **manual dispatch only**; new `ci.yml` runs tests on push (publishes nothing). `windows.yml` now builds Windows EXE **and** Linux tar.gz into ONE release with standard filenames + manifest + checksums. VERIFIED: the post-change push triggered only `ci.yml`.
- **Docs (§19):** PLATFORM_SUPPORT, UPDATES, RELEASES, STEAM_HARDWARE_CHECKLIST, BRANCH_LEDGER, THIRD_PARTY_ASSETS.
- **Branch audit:** `ccr` is the authoritative superset; no valuable unmerged game work on any branch (BRANCH_LEDGER).
- **Glyph pack** (`Source.zip`, JulioCacko Input Prompts, CC0) received + archived; integration PENDING (YELLOW).

Tests: full `:core:test` green; GripProbe PASS (all 6 characters); AimedCannonTest 9/9; Linux smoke PASS; all workflow YAML validated.
Open/deferred (RED/owner/hardware): glyph integration (YELLOW, assets in hand); Steam on-screen keyboard + Steam Input config + Steam Cloud (need Steamworks); physical Deck/phone/Windows tests; coordinated single `release.yml` (needs a hosted dry-run); name decision "Climb up" vs "Upwardly"; hamster whiskers model nit.
Next action: owner playtests the published grip-fix APK/EXE; when satisfied, cut a coordinated numbered build (dispatch `android.yml` + `windows.yml`); then the glyph-integration YELLOW task.

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
