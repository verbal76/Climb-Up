package com.hotatticgames.climbup.sim;

/**
 * Which part of the (ever-growing) tower is simulated around the player. Kept apart from the screen so the exact production rule can be tested headlessly.
 * The whole tower stays resident; only the simulated window moves, up with the climb and down with a fall.
 */
public final class WindowFollower {
    public int center = Integer.MIN_VALUE, size;

    public void update(Sim sim, Course course, boolean force) {
        // the window is centred on where the player is standing now (not on the highest point ever reached: after a long fall he must keep the world around him, not have it jump back up to his best height)
        int idx = sim.onElem >= 0 ? sim.onElem : (center == Integer.MIN_VALUE ? Math.max(Math.max(sim.bestElem, sim.onElem), sim.checkpoint) : center);
        if (center != Integer.MIN_VALUE && sim.mode == Sim.Mode.AIR && sim.vy < -2f && sim.winLo > 0 && sim.y < course.get(sim.winLo).y + 30f) {
            int f = Math.min(course.size() - 1, center + 160);          // falling out of the bottom of the simulated slice: follow the player down so every platform below can still catch him
            while (f > 0 && !(course.get(f).anchor < 0 && course.get(f).isPlatform() && course.get(f).y < sim.y - 1f)) f--;
            center = f - 40; size = course.size();
            sim.setRange(f - 160, f + 120);
            return;
        }
        boolean grew = course.size() > size && center + 160 > size;
        if (!force && !grew && Math.abs(idx - center) < 30) return;
        center = idx; size = course.size();
        sim.setRange(idx - 120, idx + 160);
    }
}
