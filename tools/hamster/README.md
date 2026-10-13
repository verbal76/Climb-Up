The hamster (owner-supplied .blend, no skeleton) is exported with Blender's `bpy` (`export_hamster.py <file.blend> <outdir>`), then split into
body / arms / feet / ears parts with their own nodes and pivots (pivots and regions are in the split step recorded in docs/DECISIONS.md);
`HeroRig.updateHam` rotates those nodes in code. Texture reduced to 1024 px.

Post-processing applied to the shipped `assets/hero/hamster.g3dj` (2026-10-13, see docs/DECISIONS.md): `python3 tools/hamster/rebase_arm_frames.py`
(arm bone rest frames aligned with the bunny's so the copied clips swing the arms correctly) followed by `python3 tools/hamster/remove_paw_stub.py`
(removes the LowerArm-skinned paw stub that showed beside the ball hands and caps the arm ends). Both are deterministic JSON-in/JSON-out scripts
run from the repo root on the builder's output; HeroRig draws the ball hands.
