# Steam Deck / Steam Machine checklist — Climb up

Per studio standard §12 and Valve's docs
([recommendations](https://partner.steamgames.com/doc/steamhardware/recommendations),
[compat](https://partner.steamgames.com/doc/steamhardware/compat)). Status is evidence-based; **no physical Steam Deck
test has occurred** — hardware-dependent rows are PENDING, not PASS.

Legend: PASS (evidence) · PARTIAL · PENDING (needs owner/hardware) · UNVERIFIED (no evidence yet) · N/A

| # | Requirement (Valve Verified unless noted) | Status | Evidence / note |
|---|---|---|---|
| 1 | Native Linux x86-64 build exists | **PASS** | `tools/linux/package.sh` builds an ELF app-image; ran locally, GL first frame + clean exit (see HANDOFF). |
| 2 | Launches & runs at 1280×800 | **PARTIAL** | Local Linux run opened a 1280×800 window and rendered; **on-Deck** test PENDING. |
| 3 | 16:10 / 16:9 / other ratios, responsive HUD, readable text | **PARTIAL** | Game targets landscape 16:9–21:9 with text-size options; Deck 16:10 + handheld readability PENDING on hardware. |
| 4 | Controller-only: launch, menus, settings, gameplay, pause, resume, save/load, exit | **PARTIAL** | Desktop has Xbox/gamepad support (proportional stick, View-button pause, controller menu nav). Full controller-only coverage of every screen PENDING hardware verification. |
| 5 | No mandatory mouse/keyboard | **PARTIAL** | Gameplay + pause are controller-driven; verify every menu/dialog is reachable on a pad (PENDING). |
| 6 | Default controller mapping via Steam Input | **UNVERIFIED** | Native gamepad works; a Steam Input config/mapping is not yet authored. |
| 7 | Controller glyphs (ABXY etc., not hardcoded Xbox) | **DONE (build 60)** | CC0 glyph pack integrated: Xbox / Deck, PlayStation and Switch families plus key caps, chosen by the connected pad's family (`PadFamily`); play hints, pause corner and the bindings editor draw them. Not yet seen on a physical Deck. |
| 8 | Automatic Steam on-screen keyboard for text entry | **PENDING** | Game has a player-name text field; `ShowFloatingGamepadTextInput` not yet wired. Needs Steamworks. |
| 9 | Mixed mouse + joystick camera input | **N/A** | Fixed camera (owner-locked: camera anchored on player, tower rotates). No free-look camera. §12.3/§12.4 do not apply. |
| 10 | Offline single-player (launch, play, save, load) | **PASS (desktop local)** | Fully offline; only the OTA check uses network and fails silently. Local Linux run saved settings offline. On-Deck PENDING. |
| 11 | Update checks fail gracefully offline | **PASS** | OTA check is silent, non-blocking, unmetered-only, short timeout; gameplay never waits on it. |
| 12 | No external launcher | **PASS** | Launches directly into the game (jpackage native launcher). |
| 13 | Graphics settings stored per device, not Cloud | **PASS** | Settings saved locally (`settings.json`); not in Steam Cloud. Quality low/med/high present. |
| 14 | Device graphics defaults (Auto/Quality/Battery) | **PARTIAL** | Quality tiers + display modes exist (`GraphicsProfile`/`DisplayManager`); a Deck-tuned 30fps@800p default profile is a pending tuning item. |
| 15 | Vulkan | **N/A / UNVERIFIED** | libGDX LWJGL3 uses OpenGL; ran fine under Mesa (llvmpipe) locally. Deck GPU (RADV, real Vulkan-backed GL) PENDING; renderer rewrite to Vulkan is NOT recommended (§12.6). |
| 16 | Suspend/resume, focus changes, controller reconnect, extended play, clean exit | **PENDING** | Hardware-only; prepare an owner test checklist. |
| 17 | Performance (FPS, frame pacing, thermal, battery) | **PENDING** | No physical measurement; llvmpipe software numbers are not representative. |
| 18 | Steam Cloud saves (cross-device) | **PENDING** | Not integrated; needs Steamworks app + credentials. |

## Summary
- **Implemented & evidenced:** native Linux build, offline play, local settings, direct launch, fixed-camera (mixed-input N/A).
- **Highest-value remaining (safe, no hardware):** integrate the CC0 glyph pack (#7), author a Deck-default graphics profile (#14), confirm controller-only menu coverage (#4/#5).
- **Blocked on Steamworks:** on-screen keyboard (#8), Steam Input config (#6), Steam Cloud (#18).
- **Blocked on hardware (owner):** #2/#3 on-Deck, #16 suspend/resume, #17 performance.
