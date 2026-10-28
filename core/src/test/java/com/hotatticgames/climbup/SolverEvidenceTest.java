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
 * - seed 2 slice 55 (Build 77, about 3,648 m): an aimed cannon hit the pull-up onto the block above a rope; knocked back onto the rope and hauled up onto its top beam, the solver had no move from the beam and went round without a step. Fixed in the solver (it climbs back down onto the rope and tries again).
 * - seed 3 slice 54 (Build 77): a castle step whose jump works only from -0.2..+0.15 m of its middle. Fixed in the solver (it stands on the proven spot, from the proof's stick state).
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
        solves(32, 4); solves(130, 11); solves(178, 0); solves(194, 0); solves(2, 55); solves(3, 54);
    }

    /** Rules 8 (owner 2026-10-25): keys lie on the route, so the formerly reported key-branch slices carry no branch: each key is on a main-route platform. */
    @Test public void formerKeyBranchSlicesNowKeepTheKeyOnTheRoute() throws Exception {
        int checked = 0;
        for (long[] sk : new long[][]{{32, 4}, {130, 11}, {178, 0}, {194, 0}, {7, 3}}) {
            Course c = slice(sk[0], (int) sk[1]);
            for (int[] kr : c.keyRooms) { assertEquals("no key detour", 2, kr[3]); assertTrue(c.get(kr[0]).anchor < 0); checked++; }
        }
        assertTrue("some keys were checked", checked >= 1);
    }
}
