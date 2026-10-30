package com.hotatticgames.climbup.sim;

/**
 * The slices of a climb exactly as the game grows them (Tower.grow, R2): every slice is final only once the slice after it has been built on it; when
 * that next slice is refused on every attempt, the provisional slice is built again from its next attempt (at most Tower.BACKTRACKS times). Reports
 * and probes used to call CourseGenerator.chunkFramed slice after slice, which never backtracks: where the game recovers, they stopped
 * (owner master directive 2026-10-29, Group 6: PUNISHER seed 7322 with correction C1 "stopped" at 423 m; Tower.grow finishes it after one backtrack).
 * With no refusal it yields exactly the chunkFramed slices.
 */
final class GameGrowth {
    private final long seed; private final Tuning t;
    private CourseGenerator.Framed fin, prov; private GenTrace provTr; private int provAtt = -1, next = 0; private double provSecs;
    private RuntimeException pending; private int pendingSlice = -1;
    /** Backtracks so far; the trace and generation seconds of the slice {@link #next} returned last (its final build plus the builds it replaced). */
    int backtracks; GenTrace trace; double seconds;
    /** The slice that could not be built when {@link #next} throws. */
    int failedSlice = -1; GenTrace failedTrace;

    GameGrowth(long seed, Tuning t) { this.seed = seed; this.t = t; }

    private CourseGenerator.Framed build(int k, CourseGenerator.Framed on, int from, GenTrace tr, int[] acc) {
        GenTrace.GEN.set(tr);
        try { return CourseGenerator.chunkFramedFrom(seed, k, on, t, from, acc); } finally { GenTrace.GEN.remove(); }
    }

    /** The next final slice (0, 1, 2, ...). */
    CourseGenerator.Framed next() {
        if (pending != null) { failedSlice = pendingSlice; throw pending; }
        int k = next, acc[] = new int[1];
        if (prov == null) {
            provTr = new GenTrace(); long a = GenTrace.now();
            try { prov = build(k, fin, 0, provTr, acc); provAtt = acc[0]; }
            catch (RuntimeException e) { failedSlice = k; failedTrace = provTr; throw e; }
            finally { provSecs = (GenTrace.now() - a) / 1e9; }
        }
        CourseGenerator.Framed q = null; GenTrace qTr = null; double qSecs = 0;
        for (int b = 0; ; b++) {
            qTr = new GenTrace(); long a = GenTrace.now();
            try { q = build(k + 1, prov, 0, qTr, acc); qSecs = (GenTrace.now() - a) / 1e9; break; }
            catch (IllegalStateException e) {
                provSecs += (GenTrace.now() - a) / 1e9;
                if (b >= Tower.BACKTRACKS || provAtt + 1 >= CourseGenerator.ATTEMPTS) { pending = e; pendingSlice = k + 1; failedTrace = qTr; break; }          // the game stops growing after this slice
                GenTrace tr = new GenTrace(); long a2 = GenTrace.now();
                try { prov = build(k, fin, provAtt + 1, tr, acc); provAtt = acc[0]; provTr = tr; backtracks++; }
                catch (RuntimeException e2) { failedSlice = k; failedTrace = tr; throw e2; }
                finally { provSecs += (GenTrace.now() - a2) / 1e9; }
            }
        }
        fin = prov; trace = provTr; seconds = provSecs;
        prov = q; provTr = qTr; provSecs = qSecs; provAtt = q == null ? -1 : acc[0];
        next++;
        return fin;
    }
}
