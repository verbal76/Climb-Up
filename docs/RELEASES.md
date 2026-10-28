# Releases — Upwardly

Build/release process (studio standard §6–§9). **No push auto-publishes** — releases are produced only by the owner
explicitly running a workflow (`workflow_dispatch`).

## Workflows
| Workflow | Trigger | Produces | Publishes |
|---|---|---|---|
| `ci.yml` | every push / PR to ccr | `:core:test` + `:desktop:test` | **nothing** (validation only) |
| `android.yml` | **manual dispatch** | signed APK (cert-gated to Build-42 identity) + bundled OTA baseline | GitHub Release (APK) |
| `windows.yml` | **manual dispatch** | Windows EXE zip **and** native Linux tar.gz, from the same commit | ONE GitHub Release (EXE + Linux + manifest + checksums) |
| `ota-publish.yml` | **manual dispatch** | signed OTA content bundle | the `ota` content channel (separate authorization, §10) |

A GitHub Release is **not** a Steam publication; Steam/SteamPipe upload requires separate explicit owner authorization (§8/§10).

## Build numbers
Each workflow currently numbers from its own `github.run_number`. For one **coordinated** build the owner runs
`android.yml` and `windows.yml` and records the pair under a single owner-facing "Build NN" in the release notes.
**Pending improvement:** a single coordinated `release.yml` with `build_number` + `source_sha` inputs that drives all three
platforms into ONE release/tag `build-NN` (designed below; not yet built because it needs a hosted-runner dry run).

## Artifact naming (§7)
Player-facing files use `Upwardly Build NN <Platform>.<ext>` (desktop) and `upwardly-<version>-buildNN.apk` (Android; builds up to 72 were `...-debug.apk`, the debuggable build type):
- `upwardly-<version>-buildNN.apk`  (Android release "Upwardly build NN apk"; from Build 73 the release build type, not debuggable, key-rotated, see `docs/CI_SECRETS.md`)
- `Upwardly Build NN EXE.zip`  (desktop release — implemented)
- `Upwardly Build NN Linux.tar.gz`  (desktop release — implemented)
Branch names / CI ids live in `build-manifest.json`, not in filenames.
(The Android release is titled "Upwardly build NN apk", the name the owner chose for the tester releases; its APK keeps the
`upwardly-<version>-buildNN-debug.apk` filename that the install-over-the-previous-build instructions quote.)

## How to cut a release (owner)
1. Confirm the commit on `ccr-cb458cd1-w4dh5c` you want (`ci.yml` green).
2. Actions → **Android build** → Run workflow (branch ccr) → produces the APK release.
3. Actions → **Desktop (Windows + Linux) build + release** → Run workflow (branch ccr) → produces the EXE + Linux release.
4. Record both under one "Upwardly Build NN" for playtesters (both workflows take a `build_number` input so the Android and desktop releases of one commit share the number).
5. Steam upload (SteamPipe) is a separate, later, explicitly-authorized step.

## Open owner decisions
- **Name:** resolved — "Upwardly" everywhere player-facing (owner decision, 12 October 2026); the package id keeps `climbup`.
- **Coordinated single release workflow** (one tag, all three files): recommended; needs a first hosted dispatch to validate.
- **SFX redistribution:** jeageroni SFX ship inside the game (licensed) but the raw `.ogg` in a public repo is a grey area
  (see `docs/THIRD_PARTY_ASSETS.md` / `docs/DECISIONS.md`) — decide before the repo/Store goes public.
