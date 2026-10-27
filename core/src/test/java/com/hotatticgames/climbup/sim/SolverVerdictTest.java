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
    @Test public void aLedgeCatchWhosePullUpIsHitIsNoArrival() throws Exception {
        Tuning t = T();
        boolean[] ok = new boolean[2];
        for (int saw = 0; saw <= 1; saw++) {
            Course c = new Course(1, t.circumference());
            c.add(el(Element.Type.STATIC, 0f, 0f, 4f)); c.add(el(Element.Type.STATIC, 4.0f, 2.6f, 2f)); c.add(el(Element.Type.GOAL, 9f, 3f, 3f));
            c.finishRoute();
            if (saw == 1) { Element h = el(Element.Type.SAW_H, 3.4f, 2.6f + 0.8f, 1f); h.anchor = 1; h.amp = 0f; h.period = 4f; c.hazards.add(h); }
            ok[saw] = Autopilot.plan(Sim.startOn(c, t, 0), 0, true).ok;
        }
        assertTrue("without the blade the catch and pull-up make the link", ok[0]);
        assertFalse("with the blade on the pull-up the link is not made", ok[1]);
    }
}
