# Design-package feedback (from building Climb Up v6 from the exported package)

Ambiguities, contradictions and gaps that materially affected the build, with the choice made.

1. **Supplied packs vs the package's asset plan.** `ASSETS.md`/CLAUDE.md section 8 only describe a generic "choose ONE coherent CC0 set" per need and an empty "Actual files" table; they say nothing about owner-supplied packs, which pack covers which need, or what to do when two packs are used. The owner's text says "all the asset packs are going to be 3D" but the supplied robot pack is 2D sprites/parts. Choice: Kenney 3D blocks for the world, the robot's 2D parts extruded into voxel 3D and rigged by code. The package should state pack-to-need mapping and the 2D/3D expectation.
2. **No movement numbers.** Part C requires "run/jump/coyote/buffer constants documented and tunable" but gives no values, world scale, spiral direction, gravity feel, camera framing or radius. All invented (tuning.json).
3. **"Instant respawn" vs "they don't really ever fail".** What triggers a reset is unspecified. Choice: falling 12 u below last footing returns to the last checkpoint; shallower falls just cost height.
4. **Grappling hook.** In the owner's words ("a grappling hook or a cable") but absent from the traversal grammar list. Cable implemented, grappling hook not.
5. **Voxel-look vs the Kenney kit**, which is smooth/rounded. Not flagged anywhere in the package.
6. **20 minutes**: "about 20 minutes of climbing" is ambiguous (ideal run vs first-time player). Chose ~6 min perfect-play / ~15-20 min human expectation; content counts (28 segments, 4 worlds, 12 types) are "heuristics, not requirements", so 4 zones, 9 element types + 3 player abilities.
7. **Conflicting process rules.** Spec demands CI on every push, full OTA update system (signed manifests, anti-rollback), full accessibility suite, and "never reduce scope to a prototype" - at odds with a "smallest coherent candidate" brief. OTA/colorblind/etc. were deferred; "Fully offline" and "INTERNET only (OTA)" also pull in opposite directions.
8. **Toolchain facts unusable offline.** Package says Gradle 9.8.1 is current; AGP compatibility for it is not given. Used Gradle 8.14.3 + AGP 8.7.3 (unverified here).
9. **Volume of boilerplate** (iOS/web notes, 620 KB project.json, repeated phase/resource-discipline text) cost reading effort and added nothing to the build.
10. Useful and sufficient: owner verbatim quotes + Part A requirements, the traversal grammar, procedural-generation constraints, anti-slop criteria, must-not-change camera rule, splash handling.
