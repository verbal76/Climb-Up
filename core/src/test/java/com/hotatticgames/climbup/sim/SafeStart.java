package com.hotatticgames.climbup.sim;

/**
 * Owner decision 2026-10-27 (SawFairnessTest): a link's timing is measured from a start a player can really have, not from the slice's first instant.
 * The climber arrives the way the course is played: the previous route link's proven move (from its own proven start) is run until it lands on
 * platform {@code a}. They walk to the spot the link was proven from, stop, and wait there standing still, with every hazard of the course active (cannonballs from other platforms included,
 * nothing repositioned, reset or disabled). The jump is evaluated at the earliest of those moments, every 0.15 s up to {@link #MAX_WAIT} s, at which the
 * planner finds a move AND that move clears every hazard of the course on its whole way (Autopilot.clearOfEverything). The timing window is the planner's at that
 * moment. Failing to arrive, being hit or carried off while waiting, or finding no clean move within the bound are failures, not skipped cases.
 * Deterministic: no randomness, a fixed step grid and a fixed bound.
 */
public final class SafeStart {
    private SafeStart() {}

    /** The longest a player is expected to stand and watch before a jump (s); the same bound the generator's quiet start uses (60 x 0.15 s). */
    public static final float MAX_WAIT = 9f;

    public static final class Result {
        public boolean ok;
        public float waited = Float.NaN, window = 0f;
        public String why = "";
        @Override public String toString() { return ok ? String.format("jump after %.2f s, window %.2f", waited, window) : "FAIL: " + why; }
    }

    public static Result evaluate(Course c, Tuning t, int a) {
        Result out = new Result();
        Sim land = arrival(c, t, a);
        if (land == null) { out.why = "no proven move from " + (a - 1) + " lands on " + a; return out; }
        Sim s = land.copy(); s.act = null; s.hz = null; s.humanHold = Autopilot.HUMAN_HOLD;      // the whole course from here on: every hazard active
        InputState in = new InputState(); int sb = s.setbacks();
        int every = Math.round(0.15f / Sim.DT), max = Math.round(MAX_WAIT / Sim.DT);
        // the waiting spot is the one the link was proven from (CourseGenerator.provenStart: the platform's middle, or its safe pocket behind a hazard in
        // the middle); from the real landing the climber walks there and stops, under every hazard (seed 4 slice 6: two saws on one platform, timed from
        // the landing spot 0.33 of their cycle, from the proven spot 0.83). The timing is judged from a standstill, as the test always measured it.
        float spot = CourseGenerator.provenStart(c, t, a).s, t0 = s.time;
        Autopilot.standAt(s, spot, in);          // the solver's own walk-and-stop with a direction-only stick (a fixed sequence of inputs, as a player would make)
        if (s.setbacks() != sb) { out.why = String.format("hit walking to the waiting spot on %d (%.2f s, %s)", a, s.time - t0, s.hitBy == null ? "fall" : s.hitBy.type + " of platform " + s.hitBy.anchor); return out; }
        if (s.mode != Sim.Mode.GROUND || s.onElem != a) { out.why = String.format("left platform %d walking to the waiting spot (mode %s)", a, s.mode); return out; }
        boolean standing = false;
        for (int step = 0; step <= max; step++) {
            if (!standing && Math.abs(s.vx) < 0.05f) standing = true;
            if (standing && step % every == 0 && s.mode == Sim.Mode.GROUND && s.onElem == a) {
                Autopilot.Result r = Autopilot.plan(s, a, true);
                if (r.ok && Autopilot.clearOfEverything(s, a, r.factory)) { out.ok = true; out.waited = step * Sim.DT; out.window = r.window; return out; }
            }
            in.clear(); s.step(in);
            if (s.setbacks() != sb) { out.why = String.format("hit while waiting on %d after %.2f s (%s)", a, step * Sim.DT, s.hitBy == null ? "fall" : s.hitBy.type + " of platform " + s.hitBy.anchor); return out; }
            if (s.mode != Sim.Mode.GROUND || s.onElem != a) { out.why = String.format("carried off platform %d while waiting (%.2f s, mode %s)", a, step * Sim.DT, s.mode); return out; }
        }
        out.why = String.format("no move that clears every hazard within %.0f s on %d", MAX_WAIT, a);
        return out;
    }

    /** The climber landing on route platform {@code a} by the previous link's proven move (the slice's first platform: its proven start). */
    static Sim arrival(Course c, Tuning t, int a) {
        if (a <= 0) return CourseGenerator.provenStart(c, t, a);
        Sim prev = CourseGenerator.provenStart(c, t, a - 1);
        Autopilot.Result pr = Autopilot.plan(prev, a - 1, false);
        if (!pr.ok || pr.factory == null) return null;
        Sim land = Autopilot.trialEnd(prev, a - 1, pr.factory, 10.5f);
        if (land == null) return null;
        land = land.copy(); land.act = null; land.hz = null;          // (every hazard active from here on, as while waiting)
        InputState in = new InputState(); int sb = land.setbacks();
        for (int q = 0; q < 120 && (land.mode == Sim.Mode.LEDGE || land.mode == Sim.Mode.PULLUP); q++) { in.clear(); in.moveY = 1f; land.step(in); }      // a caught ledge: pull up, as a player does (and as the solver does)
        return land.setbacks() == sb && land.mode == Sim.Mode.GROUND && land.onElem == a ? land : null;
    }
}
