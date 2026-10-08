# Assets and provenance

Every external asset used by the game must have a row here with its exact source URL, creator, license and download date.
Planned resolutions from Game Designer are listed first; the build appends one row per actual file.

## Asset strategy

Asset sources in priority order: 1) appropriately licensed free assets (CC0/public domain; verify every license); 2) for whatever remains uncovered, original/procedural work created by the builder.

| Need | Resolution | License | Source / instructions |
|---|---|---|---|
| characters | external cc0 | CC0 1.0 / public domain | Kenney (https://kenney.nl/assets); Quaternius (https://quaternius.com); Poly Haven (https://polyhaven.com); OpenGameArt.org (CC0-filtered) (https://opengameart.org); itch.io (CC0-tagged assets) (https://itch.io/game-assets/assets-cc0) Choose ONE visually coherent set (consistency over variety). If no coherent clean-license set covers this need, build the original procedural fallback: Generate meshes from primitives and parametric/lathe/extrude builders with vertex colors or simple materials; deterministic seeds. |
| environment | external cc0 | CC0 1.0 / public domain | Kenney (https://kenney.nl/assets); Quaternius (https://quaternius.com); Poly Haven (https://polyhaven.com); OpenGameArt.org (CC0-filtered) (https://opengameart.org); itch.io (CC0-tagged assets) (https://itch.io/game-assets/assets-cc0) Choose ONE visually coherent set (consistency over variety). If no coherent clean-license set covers this need, build the original procedural fallback: Generate meshes from primitives and parametric/lathe/extrude builders with vertex colors or simple materials; deterministic seeds. |
| materials | external cc0 | CC0 1.0 / public domain | Poly Haven (https://polyhaven.com); ambientCG (https://ambientcg.com) Choose ONE visually coherent set (consistency over variety). If no coherent clean-license set covers this need, build the original procedural fallback: Generate tileable textures with noise/pattern shaders baked at build time. |
| ui_kit | external cc0 | CC0 1.0 / public domain | Kenney (https://kenney.nl/assets); OpenGameArt.org (CC0-filtered) (https://opengameart.org); itch.io (CC0-tagged assets) (https://itch.io/game-assets/assets-cc0) Choose ONE visually coherent set (consistency over variety). If no coherent clean-license set covers this need, build the original procedural fallback: Build the UI from engine-native drawing primitives (rounded rects, gradients, icons drawn as vectors) with a shared theme. |
| vfx | external cc0 | CC0 1.0 / public domain | Kenney (https://kenney.nl/assets) Choose ONE visually coherent set (consistency over variety). If no coherent clean-license set covers this need, build the original procedural fallback: Use engine particle systems and shaders with a shared small palette. |
| font | external cc0 | CC0 1.0 / public domain | Kenney (https://kenney.nl/assets) Choose ONE visually coherent set (consistency over variety). If no coherent clean-license set covers this need, build the original procedural fallback: Use a CC0 font from a blanket-CC0 source, or generate a bitmap font from code. |
| sfx | external cc0 | CC0 1.0 / public domain | Kenney (https://kenney.nl/assets); OpenGameArt.org (CC0-filtered) (https://opengameart.org); itch.io (CC0-tagged assets) (https://itch.io/game-assets/assets-cc0); Freesound (CC0-filtered) (https://freesound.org) Choose ONE visually coherent set (consistency over variety). If no coherent clean-license set covers this need, build the original procedural fallback: Synthesize effects with a small sfxr-style synthesizer (waveform, envelope, filter) rendered at build time with seeded parameters per effect. |
| music | external cc0 | CC0 1.0 / public domain | Kenney (https://kenney.nl/assets); OpenGameArt.org (CC0-filtered) (https://opengameart.org); itch.io (CC0-tagged assets) (https://itch.io/game-assets/assets-cc0); Freesound (CC0-filtered) (https://freesound.org) Choose ONE visually coherent set (consistency over variety). If no coherent clean-license set covers this need, build the original procedural fallback: Synthesize loopable music from a small procedural sequencer (scales, chord progressions, simple instruments) rendered at build time. |


## Owner-supplied branding (use as-is; never replace or regenerate)

| Slot | File in this package | Original name | Size | SHA-256 | License |
|---|---|---|---|---|---|
| studio_splash | `branding/master/studio_splash_e3d9bb56.png` | Hot_Attic_Games_Master_Logo_ALPHA_FINAL.png | 1536x1024 | e3d9bb5653eafb783eede827606e7ac73a4e45564a1c25b1ed13ad1429f48c4e | owner-supplied |

## Actual files

| File | Source URL | Creator | License | Downloaded |
|---|---|---|---|---|
| `assets/models/*.obj, *.mtl, Textures/colormap.png` (blocks, props, flag/chest/jewel; 40 models) | owner-supplied pack `kenney_platformer-kit.zip` (https://kenney.nl/assets/platformer-kit) | Kenney (www.kenney.nl) | CC0 1.0 (`assets/licenses/Kenney_PlatformerKit_License.txt`) | 2026-10-08 |
| `assets/hero/hero.g3dj` (converted from `Character.gltf` by `tools/gltf_to_g3dj.py`) | owner-supplied pack `Ultimate Platformer Pack by Quaternius.zip` (https://quaternius.com / quaternius.itch.io) | Quaternius | CC0 1.0 (`assets/licenses/Quaternius_UltimatePlatformer_License.txt`) | 2026-10-08 |
| `tools/legacy_robot/*.png` (no longer in the game; only the app-icon source) | owner-supplied pack `Foozle_2DC0001_Cute_Robot.zip` | Foozle, commissioned from mayakhan95 | CC0 1.0 (`tools/legacy_robot/Foozle_CuteRobot_License.txt`) | 2026-10-08 |
| `assets/branding/studio_splash.png` | owner-supplied (`branding/master/studio_splash_e3d9bb56.png`) | Hot Attic Games | owner-supplied, unmodified | 2026-10-08 |
| `assets/audio/music_game.ogg` | OWNER-SUPPLIED by Kevin ("Here is some music"); transcoded from the supplied WAV (OGG Vorbis q4, 44.1 kHz); license/provenance as supplied by the owner | owner | owner-supplied | gameplay music |
| `assets/audio/*.wav` (12 effects, 1 menu music loop) | original - synthesised by `tools/gen_audio.py` | Hot Attic Games / this build | original, no third-party material | generated |
| app icon (`android/src/main/res/mipmap-*`, `drawable-nodpi`, `docs/play_store_icon_512.png`) | original - `tools/gen_icon.py` (uses the CC0 robot head) | Hot Attic Games / this build | original + CC0 | generated |
| pixel font, title lettering, HUD, ropes/cables/pads/beams, clouds/stars | original - drawn in code (`ui/PixelFont.java`, `render/*`) | Hot Attic Games / this build | original | n/a |
| libGDX 1.14.2 (library) | https://libgdx.com | libGDX contributors | Apache-2.0 (`assets/licenses/libGDX_NOTICE.txt`) | via Maven Central |

Gaps filled with original/procedural work (no external download was needed): audio, font, UI kit, VFX, title art, icon, materials (the Kenney palette texture covers surfaces).
