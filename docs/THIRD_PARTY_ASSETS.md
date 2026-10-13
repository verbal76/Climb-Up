# Third-party assets & licenses — Climb up

Commercial-readiness inventory (studio standard §13/§19). Full per-file provenance is in `ASSETS.md`; this is the
license summary plus the controller glyph library. "Verified" = license text/source checked; "Stated" = owner-provided.

| Component | Source / author | License | Evidence | Commercial OK? |
|---|---|---|---|---|
| libGDX 1.14.2 (engine) | libGDX contributors | Apache-2.0 | `assets/licenses/libGDX_NOTICE.txt` | **Yes** (verified) |
| steamworks4j (desktop, Steam API wrapper) | code-disaster | MIT (over Valve Steamworks SDK) | Maven POM | Yes — platform SDK exception; only ships in the desktop build |
| 3D models — platformer kit (blocks, props, flag/chest) | Kenney | CC0 1.0 | `assets/licenses/Kenney_PlatformerKit_License.txt` | **Yes** (verified) |
| 3D models — hazards, hero, animated bee/crab, castle | Quaternius "Ultimate Platformer Pack" | CC0 1.0 | `assets/licenses/Quaternius_UltimatePlatformer_License.txt` | **Yes** (verified) |
| 3D models — Space Kit (astronauts, planets, rocks, ships, ramp) | Quaternius "Ultimate Space Kit" | CC0 1.0 (stated; pack page screenshot) | pack page CC0; license .txt not in the uploaded pack | Yes (stated) — **credit Quaternius**; archive the pack's own license .txt before store |
| SFX pack (56 effects) | **jeageroni** (itch.io) | Author's license: free commercial/non-commercial, no attribution, modify OK; **no standalone redistribution** | itch page (owner-checked 2026-10-09) | Yes **inside the game**; raw `.ogg` in a PUBLIC repo is a grey area — resolve before public repo/Store |
| Music (gameplay + menu tracks) | Owner (Kevin), generated with **Suno** | Owner's own; Suno plan terms apply | owner-supplied | Owner's call — confirm Suno commercial terms for the owner's plan before Store |
| `fall.ogg` | Owner (Kevin), Suno | Owner's own; Suno terms | owner-supplied | as above |
| Original synth SFX, pixel font, UI, VFX, title art, app icon | Hot Attic Games (this build) | Original | `tools/gen_*.py`, `ui/PixelFont.java`, `render/*` | **Yes** |
| Studio splash logo | Owner-supplied `Hot_Attic_Games_Master_Logo…` | Owner's | `branding/master/studio_splash_e3d9bb56.png` | **Yes** |

## Controller glyph library (studio-approved `Source.zip`)
- **Pack:** JulioCacko "Input Prompts" (itch.io / FAB). **Asset license: CC0 1.0 Universal** (code license MIT). Owner-provided
  listing + itch page both state CC0; 128×128 PNGs, color-blind friendly, "no generative AI".
- **Families in the archive:** `Keyboard_Mouse/`, `XGamepad/` (Xbox/Deck ABXY), `P4Gamepad/` (PS4), `P5Gamepad/` (PS5),
  `SGamepad/` (Switch) — each with Default/Alt/Light etc. variants.
- **Status: INTEGRATED (2026-10-13, build 60).** 146 glyphs copied, renamed and otherwise unmodified, into `assets/glyphs/{xbox,ps,switch,key}/`
  (XGamepad/Default, P5Gamepad/Default, SGamepad/Default, Keyboard_Mouse/Dark); the original archive stays outside the repo. License text saved
  as `assets/licenses/JulioCacko_InputPrompts_License.txt`; credited on the in-game CREDITS screen. The desktop build draws them in the play
  hints, the pause corner and the bindings editor (`desktop/.../input/Glyphs.java` maps control -> file; `GlyphsTest` checks every file exists).
  Known gaps shown as text: the Switch set has no stick-click image, the keyboard set has no "4" key.
- **Before commercial release:** verify the original license source (itch/FAB page) and save its license text — do not rely
  only on the owner listing.

## Pending license actions before a public/commercial release
1. Archive the Quaternius Space Kit's own CC0 license .txt (currently only the Platformer Pack's is saved).
2. Decide the jeageroni raw-`.ogg`-in-public-repo question (ship-inside is fine; redistribution is not).
3. Confirm Suno commercial terms for the owner's plan (music, `fall.ogg`).
4. ~~Save the glyph pack's CC0 license text under `assets/licenses/` when the glyphs are integrated.~~ Done (build 60): `assets/licenses/JulioCacko_InputPrompts_License.txt`; the itch/FAB page's own license wording is still to be re-verified before a commercial release.
