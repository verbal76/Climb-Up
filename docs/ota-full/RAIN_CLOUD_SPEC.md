# Rain cloud + slippery platform (owner-approved new mechanic, one package; start only when the owner says go)

Looks and mechanic ship together so the cloud always has a purpose.
- A foreground cloud above the play area with a purplish-blue underside fading up through the cloud (reads as a rain cloud), rain falling from it onto ONE platform, which gets a wet shimmer.
- Landing on the wet platform makes the hero slide (low friction). The hero looks at the screen (breaks the fourth wall) and flails arms and legs trying to stop, "uh-oh".
- Jumping still works while sliding (same jump, same timing): jump out of the wet area, or ride it out and slide off the edge and try to grab something; otherwise fall under the normal fall rules.
- Constraints: nothing else in the movement, jump, camera or difficulty changes; generator places wet platforms only where a completable route exists (solver proves it for many seeds); fair, readable before the player commits; deterministic simulation with regression tests; visual part respects reduced motion.
- Delivery: core change on the gameplay branch, shipped as one OTA through the existing channel (and carried into the Windows build after the owner approves it on the phone).
