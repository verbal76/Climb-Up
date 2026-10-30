package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.nio.file.*;
import org.junit.Test;

/**
 * Owner correction directive 2026-10-27, DC1 and DC2: the solver says what it established. A clean witness to the goal is PROVEN_SOLVABLE; a rise no
 * body can make is PROVEN_UNSOLVABLE; a budget run out is UNKNOWN (never a pass); and a ledge catch is an arrival only once its pull-up ends standing.
 */
public class SolverVerdictTest {
    static Tuning T() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }
    static Element el(Element.Type ty, float s, float y, float w) { return new Element(ty, s, y, w); }

    /** Three easy hops onto a goal flag. */
    static Course steps(Tuning t) {
        Course c = new Course(1, t.circumference());
        c.add(el(Element.Type.STATIC, 0f, 0f, 4f)); c.add(el(Element.Type.STATIC, 5f, 0.8f, 3f)); c.add(el(Element.Type.STATIC, 10f, 1.6f, 3f)); c.add(el(Element.Type.GOAL, 15f, 2.2f, 3f));
        c.finishRoute();
        return c;
    }

    @Test public void aCleanWitnessIsProvenSolvable() throws Exception {
        Tuning t = T();
        Autopilot.Report r = Autopilot.run(steps(t), t, 600f);
        assertEquals(r.failInfo, Autopilot.Verdict.PROVEN_SOLVABLE, r.verdict);
        assertTrue(r.completed);
        assertEquals(0, Autopilot.lastReal.setbacks());
    }

    @Test public void aBudgetRunOutIsUnknownNotAPass() throws Exception {
        Tuning t = T();
        Autopilot.Report r = Autopilot.run(steps(t), t, 0.5f);
        assertEquals(Autopilot.Verdict.UNKNOWN, r.verdict);
        assertEquals("budget", r.cause);
        assertFalse(r.completed);
    }

    @Test public void aRiseOutOfAnyReachIsProvenUnsolvable() throws Exception {
        Tuning t = T();
        Course c = new Course(1, t.circumference());
        c.add(el(Element.Type.STATIC, 0f, 0f, 4f)); c.add(el(Element.Type.STATIC, 4f, 6f, 3f)); c.add(el(Element.Type.GOAL, 9f, 6.5f, 3f));
        c.finishRoute();
        Autopilot.Report r = Autopilot.run(c, t, 600f);
        assertEquals(r.failInfo, Autopilot.Verdict.PROVEN_UNSOLVABLE, r.verdict);
        assertEquals("out_of_reach", r.cause);
        assertTrue("the bound is sound: with a pad under the gap it no longer claims impossible", !Autopilot.outOfReach(withPad(t), t, 0));
    }
    static Course withPad(Tuning t) {
        Course c = new Course(1, t.circumference());
        c.add(el(Element.Type.STATIC, 0f, 0f, 4f)); c.add(el(Element.Type.STATIC, 4f, 6f, 3f)); c.add(el(Element.Type.GOAL, 9f, 6.5f, 3f));
        c.finishRoute(); c.add(el(Element.Type.PAD, 2.6f, -1f, 2f));
        return c;
    }

    /** DC2: a deck reached only by a ledge catch, with a blade where the pull-up ends: the catch alone used to prove the link. */
    /**
     * DC2: a hit during the pull-up is never a clean arrival. Rules 19 (owner correction 2026-10-29: a hit is not a failure; outcomes are
     * evaluated, clean moves preferred): the link may still be made as a collision-assisted move when the knocked climber really arrives, and it
     * is then marked as one; under the old strict rule (every hit a failure) it is not made at all.
     */
    @Test public void aLedgeCatchWhosePullUpIsHitIsNoArrival() throws Exception {
        Tuning t = T();
        Autopilot.Result[] r = new Autopilot.Result[3];
        for (int k = 0; k <= 2; k++) {
            int saw = k == 0 ? 0 : 1;
            Course c = new Course(1, t.circumference());
            c.add(el(Element.Type.STATIC, 0f, 0f, 4f)); c.add(el(Element.Type.STATIC, 4.0f, 2.6f, 2f)); c.add(el(Element.Type.GOAL, 9f, 3f, 3f));
            c.finishRoute();
            if (saw == 1) { Element h = el(Element.Type.SAW_H, 3.4f, 2.6f + 0.8f, 1f); h.anchor = 1; h.amp = 0f; h.period = 4f; c.hazards.add(h); }
            boolean was = Autopilot.HIT_STRICT; Autopilot.HIT_STRICT = k == 2;
            try { r[k] = Autopilot.plan(Sim.startOn(c, t, 0), 0, true); } finally { Autopilot.HIT_STRICT = was; }
        }
        assertTrue("without the blade the catch and pull-up make the link", r[0].ok);
        assertTrue("and cleanly", !r[0].viaHit && r[0].successes > 0);
        assertEquals("with the blade on the pull-up no move arrives clean", 0, r[1].successes);
        assertTrue("so if the link is made at all it is a collision-assisted move, marked as one", !r[1].ok || r[1].viaHit);
        assertFalse("under the strict rule (a hit is a failure) the link is not made", r[2].ok);
    }
}
