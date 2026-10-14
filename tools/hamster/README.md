The hamster (owner-supplied .blend, no skeleton) is exported with Blender's `bpy` (`export_hamster.py <file.blend> <outdir>`), then split into
body / arms / feet / ears parts with their own nodes and pivots (pivots and regions are in the split step recorded in docs/DECISIONS.md);
`HeroRig.updateHam` rotates those nodes in code. Texture reduced to 1024 px.

Post-processing applied to the shipped `assets/hero/hamster.g3dj` (2026-10-13, see docs/DECISIONS.md), in this order on the builder's output:
1. `python3 tools/hamster/rebase_arm_frames.py` - arm bone rest frames aligned with the bunny's so the copied clips swing the arms correctly;
2. `python3 tools/hamster/remove_paw_stub.py` - removes the LowerArm-skinned paw stub that showed beside the ball hands and caps the arm ends;
3. `python3 tools/hamster/retarget_run_arms.py` - re-aims the Run/Walk upper-arm keys so the arms hang beside the torso (outward tilt derived from the
   body width and the hand-ball size, elbows bending forward) instead of reproducing the bunny's 43-degree-out running pose on the hamster's wide body.
All three are deterministic JSON-in/JSON-out scripts run from the repo root; each prints what it measured and refuses to write on a broken premise.
HeroRig draws the ball hands. Pristine builder output: `git show 5e9bb11:assets/hero/hamster.g3dj`.

Build 63 (owner: "make the skeleton and rigging the same in the hamster as it is in the bunny"): the result of the three steps above is kept as
`tools/hamster/hamster_stub_rig.g3dj` (not shipped) and the shipped `assets/hero/hamster.g3dj` is made from it by
4. `python3 tools/hamster/retarget_to_bunny.py` - the bunny's skeleton, bind pose and all 18 clips verbatim; the hamster mesh moved bone by bone
   into them (arms and legs stretched onto the bunny's joints, the spine as one narrowed piece, the head and ears rigid), the ear bones alone
   keeping the hamster's own joint offsets. `--analyze` prints the measurements without writing. HeroRig detects the long arms (`stubArms`
   false) and poses the hamster exactly like the bunny; the three older scripts and their rebased arm frames only matter for the stub rig.
