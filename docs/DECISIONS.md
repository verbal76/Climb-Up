# Engineering decisions (candidate 0.1.0)

* **Toolchain:** libGDX 1.14.2, Java 17 bytecode, Gradle 8.14.3 wrapper, AGP 8.7.3, compile/target SDK 35, minSdk 26. (The package's "Gradle 9.8.1" was not used: AGP/Gradle compatibility could not be checked offline.)
* **CI:** `workflow_dispatch` + tag pushes only (owner asked not to spend hosted CI during development), not every push as the spec says.
* **World:** circumference = 2*pi*10; one-way platform tops (jump up through, land on top); collisions are in wrapped arc space so falling can land on lower revolutions (setback, not instant death).
* **Failure:** falling more than 12 units below the last footing returns to the last checkpoint instantly (crumbled tiles restored). Checkpoint flags every rest platform.
* **Movement:** run 5.6 u/s, jump apex 1.94 u (hold) with variable height, coyote 0.12 s, buffer 0.13 s, pad launch 21/24 u/s (apex 6.5/8.5 u). All in `tuning.json`.
* **Controls:** floating stick (left) + jump anywhere on the right half; left-handed swap. No second button (grab is automatic).
* **Content:** 4 zones (Meadow, Frost, Dusk, Night = grass/snow blocks with palette tints and sky blends), 9 element types + grab/pull/catch as player abilities, 6 pre-validated 520 m towers (~330 elements, ~6 min of perfect autopilot play, intended ~15-20 min for a person).
* **Robot:** the supplied Cute Robot is 2D, the owner wants 3D: each part sprite is voxel-extruded at runtime; animation is procedural (idle, run, jump, fall, land squash, rope, cable, ledge hang, pull-up).
* **Assets gaps filled originally:** audio (synth), font (5x7 bitmap), UI, VFX, title art, icon.
* **Permissions:** `VIBRATE` only (normal permission). No INTERNET (OTA not implemented).
* **Deferred / not in this candidate:** over-the-air updates; colorblind palette modes (HUD uses shapes+text, not color alone, but no palette option); grappling hook (named once in the owner's words, absent from the traversal grammar - cable implemented); Play-Store release signing.
