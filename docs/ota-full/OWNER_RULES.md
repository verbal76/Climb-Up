# Owner rules (standing, from Kevin)

- The newest playable build always goes under GitHub **Releases** as a normal release (never as a pre-release, never only as an Actions artifact or a ZIP). It is Build 44 plus more, so it is a newer release, not something to hide next to Build 44.
- Release file names are plain: `Upwardly.apk` (capital U only, not all capitals).
- Keep answers short, yes/no where possible. Answer a question as a question; do not start work on it.
- Do not repeat the 21ee43b explanation; do not ask for approval for work already authorised.
- The public name is written "Upwardly" (capital U, rest lowercase), not all capitals: Android app label on the next APK, Windows EXE name and window title, release file names.
- Every gameplay AND visual change shipped to Android must also be ported to the Windows build (same gameplay core, same art), except things that only exist on Android (the OTA host, update panel, restart button, OTA version text). Windows does not need OTA; it gets the same changes in a new EXE release.
  How: all gameplay/visual work lives in `core/` (+ assets). At each milestone, cut a shared branch containing only `core/`, `assets/` and `docs/gameplay/` changes since the last Windows release, the owner authorises the Windows engineer in its own session to merge it and publish "Upwardly-Windows.zip" as a normal Release. Windows must be re-synced after the backlog items (slab top, dead ends, mortars, rain cloud, parallel routes, aimed cannon) and after the visual passes already shipped (sky and light, far clouds, cable shimmy and inchworm, shadow, ramp edge, cannon barrel/ball, cannonballs off-screen).
