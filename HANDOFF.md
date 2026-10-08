# Handoff

State: playable candidate 0.1.0 on branch `ccr-cb458cd1-w4dh5c`. Core loop, six towers, controls, settings, saves, audio, Android project and CI workflow exist. See `docs/VERIFICATION.md`.

Next steps, in order:
1. Run the `Android build` workflow once (or build locally with the Android SDK), fix whatever the first real Gradle/AGP run reports, install on a phone.
2. Playtest: tune `assets/data/tuning.json` (movement, ledge reach, assists, `fallRespawnDepth`, `restEvery`, `spiralPitch`, `courseHeight`), then `./gradlew :desktop:genCourses` and re-run `:core:test` (towers depend on tuning).
3. Deferred spec items: over-the-air updates, colorblind modes, release signing, grappling hook (if the owner wants it).
Test hooks and commands: README.md. Package-feedback notes for Game Designer: `docs/PACKAGE_FEEDBACK.md`.
