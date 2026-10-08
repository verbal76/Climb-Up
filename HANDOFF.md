# Handoff

State: endless candidate 0.1.0 on branch `ccr-cb458cd1-w4dh5c`. Core loop, endless sliced tower, hazards, springs, keys + castles, crabs + club, controls, settings, saves, audio, Android project and CI exist. See `docs/VERIFICATION.md`, `docs/DECISIONS.md` (section "Endless climb").

Next steps:
1. Playtest on the phone: tune `assets/data/tuning.json` (hazardStartY/hazardRampY/minTimingWindow/chunkHeight/rampHeight and movement numbers) and re-run `:core:test`.
2. Open: shared debug keystore (installs over each other), OTA updates, colorblind palette modes, grappling hook.
Test hooks and commands: README.md.

## Latest batch (see docs/DECISIONS.md and docs/VERIFICATION.md)
Seesaw + floating bridges, ramps (plain/crumbling/shaky/sinking/ski jump), fall-to-lower-level rule, audio watchdog, title hero picker, hamster rebuilt on the bunny skeleton, four astronaut characters, character juice, space scenery (`render/SpaceScene.java`), prompt audit (`docs/PROMPT_AUDIT.md`). Owner rule: release builds only when a batch is complete (no incremental pushes).
Open: shared debug keystore, OTA updates, colourblind/subtitle options, phone verification.
