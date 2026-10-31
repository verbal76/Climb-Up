package com.hotatticgames.climbup;

import com.badlogic.gdx.Screen;

/**
 * Release hardening (Build 82 review findings 1 and 6, adapted to Build 83): starting a fresh climb from a menu. The first slices are made in the background while the menu stays up
 * ("BUILDING COURSE"), and the play screen opens only once they exist; a typed course code that cannot be built stays on the menus with its
 * notice, so no play screen is ever shown without a world. Generation is the same pure function as before, on another thread.
 */
final class ClimbStart {
    private boolean active;
    private float t;

    /** Forgets the saved climb (as before) and starts making the fresh one ({@code seed} null: a random course). */
    void begin(ClimbGame g, Long seed) { g.beginFreshRun(seed); g.prepareFreshRun(); active = true; t = 0f; }

    boolean active() { return active; }

    /** What the menu says meanwhile (empty when nothing is being built, or for the first moment, so a prepared climb starts without a flash). */
    String status(float dt) { if (!active) return ""; t += dt; return t > 0.15f ? "BUILDING COURSE..." : ""; }

    /** The play screen once the climb is ready, the title (with the refusal notice) if its course cannot be built, else null. */
    Screen poll(ClimbGame g) {
        if (!active) return null;
        try {
            ClimbGame.Run r = g.takeFreshRunIfReady();
            if (r == null) return null;
            active = false;
            g.preparedRun = r;
            return new PlayScreen(g, false);
        } catch (com.hotatticgames.climbup.sim.Tower.FirstSliceFailed e) {
            active = false;
            return new TitleScreen(g);
        }
    }
}
