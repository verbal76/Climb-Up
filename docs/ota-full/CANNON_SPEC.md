# Cannon overhaul (owner-approved design; not started, start when the owner says go)

Three ways a cannon works, by how far into the game you are:
1. **Early game, side cannon.** It fires left or right, along the line it sits on. It turns to face whichever side of it the player is on, and the ball leaves the end of the barrel.
2. **Late game, aimed cannon.** It aims at the player specifically: turns left and right and angles up or down, then fires at where the player is.
3. **Mortar (any stage after the first worlds).** The cannon sits on ground below the running path and points straight up. The ball rises to about twice the player's height above the player, then falls back into the cannon and launches again. Staggered rows (about six in a row) with islands between them make a timing section.

Cannonballs: they keep flying until they leave the screen, then disappear (no popping out while still visible).

Constraints (owner rules): every section must stay completable (solver proof over many seeds); every cannon needs a readable warning and a workable timing window; a hit knocks you away (no damage, no death, no checkpoint teleport); visual aiming must match where the ball really goes; reduced-motion respected. Needs: simulation change, level generator placement and fairness rules, rendering (barrel rotation), animation, tests.
Suggested build order: (1) side cannon faces you + balls fly off-screen, (2) vertical mortars with stagger patterns, (3) aimed cannons.

# Level design additions (owner, same package family; not started)
- **Spike slab top is safe.** The slab that slams down on a beat (spikes underneath) must NOT hurt when you stand or land on its top. Its top is a surface: it carries you up and down like an elevator, so the player can use it to reach new routes. Only the spiked underside hurts.
- **Dead ends.** More dead-end routes (visible, fair, never required) so choosing a path matters.
- **Parallel routes.** Every now and then the tower offers two or more stacked routes to pick from, each with different traps and different difficulty, that rejoin. Not every section; occasional.
All need generator and simulation work, a solver proof that every route set is completable and that the alternatives are really different, and a generatorRuleset decision.
