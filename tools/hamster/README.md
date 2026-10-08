The hamster (owner-supplied .blend, no skeleton) is exported with Blender's `bpy` (`export_hamster.py <file.blend> <outdir>`), then split into
body / arms / feet / ears parts with their own nodes and pivots (pivots and regions are in the split step recorded in docs/DECISIONS.md);
`HeroRig.updateHam` rotates those nodes in code. Texture reduced to 1024 px.
