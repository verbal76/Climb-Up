# Handoff

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
