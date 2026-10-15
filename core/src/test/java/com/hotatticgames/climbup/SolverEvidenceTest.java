package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/**
 * Slices once reported as solver failures, each investigated against the real generated geometry:
 * - seed 32 slice 4 (key detour): the player arrived on the anchor platform with momentum and the solver idled, sliding off the far edge. Fixed in the solver (it now brakes and centres like a person).
 * - seed 130 slice 11: a full-speed run-up from the far end of a platform overshot onto a ledge; the clean line starts from a nearer/slower approach. Fixed in the solver (it now also tries other standing spots and gentle approaches).
 * - seed 178 slice 0 (key branch): the generator's proof window for side branches missed a route platform hanging directly over the landing. Fixed in the generator proof (the window now covers it).
 * - seed 194 slice 0: a razor-thin full-speed window; gentle approach exists. Fixed in the solver.
 */
public class SolverEvidenceTest {
    private static Course slice(long seed, int k) throws Exception {
        Tuning t = TestUtil.tuning(); Course prev = null, c = null;
        for (int i = 0; i <= k; i++) { c = CourseGenerator.chunk(seed, i, prev, t); prev = c; }
        return c;
    }

    private static void solves(long seed, int k) throws Exception {
        Course c = slice(seed, k);
        Autopilot.Report r = Autopilot.run(c, TestUtil.tuning(), 4000f);
        assertTrue("seed " + seed + " slice " + k + " link " + r.failedLink + " " + r.failInfo, r.completed);
    }

    @Test public void formerlyReportedSlicesAllComplete() throws Exception {
        solves(32, 4); solves(130, 11); solves(178, 0); solves(194, 0);
    }

    @Test public void everyKeyBranchLinkIsProvenOnTheFinishedGeometry() throws Exception {
        Tuning t = TestUtil.tuning(); int checked = 0;
        for (long[] sk : new long[][]{{32, 4}, {130, 11}, {178, 0}, {194, 0}, {7, 3}}) {
            Course c = slice(sk[0], (int) sk[1]);
            for (int[] kr : c.keyRooms) {
                int a = kr[0], f = kr[1], mode = kr[3], n = Math.max(1, kr[2]);
                if (mode == 3) { assertTrue(Autopilot.linkExists(c, t, a, f)); assertTrue(Autopilot.linkExists(c, t, f, f + 1)); assertTrue(Autopilot.linkExists(c, t, f + 1, a)); }
                else if (mode == 0) { int prev = a; for (int i = 0; i < n; i++) { assertTrue(Autopilot.linkExists(c, t, prev, f + i)); prev = f + i; } for (int i = n - 1; i >= 0; i--) assertTrue(Autopilot.linkExists(c, t, f + i, i == 0 ? a : f + i - 1)); }
                checked++;
            }
        }
        assertTrue("some key branches were checked", checked >= 1);
    }
}
