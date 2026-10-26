package com.hotatticgames.climbup.sim;

import java.util.ArrayList;

/**
 * Observe-only traces for the difficulty reports (owner directive 2026-10-26: measure the current generator and solver before changing them).
 * Nothing is recorded unless a thread installs a trace; recording only reads sizes, heights, counters and clocks the code already has, so it never
 * draws from a random stream, changes a decision, or alters a course, a fingerprint or a solver result (pinned by the report's invariance tests).
 */
public final class GenTrace {
    /** The trace of the current thread's generation, or null (the game and every normal test). */
    public static final ThreadLocal<GenTrace> GEN = new ThreadLocal<>();
    /** The trace of the current thread's solver run ({@link Autopilot#run}), or null. */
    public static final ThreadLocal<Solve> SOLVE = new ThreadLocal<>();

    /** One module (or structure: REST, CASTLE, GEM) the generator tried. Indices are the generator's own; {@link #offset} maps them to the slice. */
    public static final class Module {
        public int attempt;            // the slice attempt it belongs to (only {@link #finalAttempt} survives into the course)
        public String kind, role;      // role: PICK (the weighted choice), FALLBACK (an easier module after the pick failed), FORK, BELT, STRUCTURE
        public String picked;          // for a FALLBACK: the module that was refused first
        public float d, y;             // difficulty at the time, height of the last platform it builds from (generator frame)
        public boolean ok;
        public int e0, e1, h0, h1;     // elements [e0, e1) and hazards [h0, h1) it added (when ok)
        public int[] fails = new int[6];   // refusal counters it raised: layer, hazard placement, plan (solver proof), timing window, conflict, threat budget
    }

    public final ArrayList<Module> modules = new ArrayList<>();
    public final ArrayList<String> sliceFailures = new ArrayList<>();
    public int attempt, finalAttempt = -1, offset;
    public boolean relaxed;
    /** extract(): generator hazard index -> slice hazard index (-1 = dropped as context). */
    public int[] hazardMap = new int[0];

    /** Solver trace: every time the solver starts on a new route link (its own loop variable), with the clock and the setback count. */
    public static final class Solve {
        public final ArrayList<float[]> arrivals = new ArrayList<>();     // {link, sim time, setbacks}
        public final ArrayList<int[]> planFails = new ArrayList<>();      // {link, count}
        public void arrive(int link, float time, int setbacks) { arrivals.add(new float[]{link, time, setbacks}); }
        public void planFail(int link) {
            if (!planFails.isEmpty() && planFails.get(planFails.size() - 1)[0] == link) planFails.get(planFails.size() - 1)[1]++; else planFails.add(new int[]{link, 1});
        }
    }
}
