package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.*;
import org.junit.After;
import org.junit.Test;

/**
 * The plan cache (speed only, owner overnight directive 2026-10-30, priority A): its key holds everything a plan reads, a hit answers exactly what
 * the plan would have (result, chosen move, report tallies), callers cannot corrupt a stored answer, and with the cache off nothing is opened.
 * Generation equivalence over whole climbs (off / shadow / on) is PlanCacheProbe's job.
 */
public class PlanCacheTest {
    static Tuning T() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }

    @After public void restore() { PlanCache.MODE = PlanCache.DEFAULT; Autopilot.HIT_STRICT = false; }

    /** A short course with a moving platform and a saw, so the plans wait, time and dodge. */
    static Course course(Tuning t) {
        Course c = new Course(5, t.circumference());
        c.add(new Element(Element.Type.STATIC, 0f, 0f, 4f));
        Element m = new Element(Element.Type.MOVE_H, 4.6f, 0.6f, 2f); m.amp = 1.2f; m.period = 3f; c.add(m);
        c.add(new Element(Element.Type.STATIC, 9f, 1.2f, 3f));
        Element saw = new Element(Element.Type.SAW_H, 9f, 1.6f, 1f); saw.amp = 1f; saw.period = 2.5f; saw.anchor = 2; c.hazards.add(saw);
        c.add(new Element(Element.Type.STATIC, 13f, 2f, 3f));
        return c;
    }

    @Test public void theKeyReadsEveryFieldButTheMotionHandle() {
        for (Class<?> k : new Class<?>[]{Sim.class, Element.class, Course.class, Tuning.class}) {
            int n = 0;
            for (Class<?> x = k; x != null && x != Object.class; x = x.getSuperclass())
                for (Field f : x.getDeclaredFields()) if (!Modifier.isStatic(f.getModifiers()) && !PlanCache.EXCLUDED.contains(x.getSimpleName() + "." + f.getName())) n++;
            assertEquals(k.getSimpleName() + ": every instance field is in the key", n, PlanCache.fields(k).length);
        }
        assertEquals("only the motion-cache handle is left out", 3, PlanCache.EXCLUDED.size());
    }

    @Test public void anyChangeToWhatAPlanReadsChangesTheKey() throws Exception {
        Tuning t = T(); Course c = course(t); Sim s = Sim.startOn(c, t, 0);
        int[] k0 = PlanCache.key(s, 0, 1, false, true, false, false);
        assertArrayEquals("deterministic", k0, PlanCache.key(s.copy(), 0, 1, false, true, false, false));
        Sim v = s.copy(); v.vx += 1e-6f;                                   assertFalse("climber velocity", java.util.Arrays.equals(k0, PlanCache.key(v, 0, 1, false, true, false, false)));
        Sim w = s.copy(); w.step(new InputState());                         assertFalse("the clock and every moving thing", java.util.Arrays.equals(k0, PlanCache.key(w, 0, 1, false, true, false, false)));
        assertFalse("the link", java.util.Arrays.equals(k0, PlanCache.key(s, 0, 2, true, true, false, false)));
        assertFalse("margin flag", java.util.Arrays.equals(k0, PlanCache.key(s, 0, 1, false, false, false, false)));
        assertFalse("look-ahead flag", java.util.Arrays.equals(k0, PlanCache.key(s, 0, 1, false, true, true, false)));
        Autopilot.HIT_STRICT = true; assertFalse("the hit rule", java.util.Arrays.equals(k0, PlanCache.key(s, 0, 1, false, true, false, false))); Autopilot.HIT_STRICT = false;
        float ph = c.hazards.get(0).phase; c.hazards.get(0).phase = ph + 0.01f; assertFalse("a hazard's phase", java.util.Arrays.equals(k0, PlanCache.key(s, 0, 1, false, true, false, false))); c.hazards.get(0).phase = ph;
        float w2 = c.get(3).w; c.get(3).w = w2 + 0.01f; assertFalse("an element further along", java.util.Arrays.equals(k0, PlanCache.key(s, 0, 1, false, true, false, false))); c.get(3).w = w2;
        float g = t.gravity; t.gravity = g + 0.01f; assertFalse("tuning", java.util.Arrays.equals(k0, PlanCache.key(s, 0, 1, false, true, false, false))); t.gravity = g;
        assertArrayEquals("all restored", k0, PlanCache.key(s, 0, 1, false, true, false, false));
    }

    @Test public void offOpensNothing() {
        PlanCache.MODE = PlanCache.Mode.OFF;
        assertNull(PlanCache.open()); assertNull(PlanCache.current());
    }

    /** The same plans with the cache off and on: identical answers, identical report tallies, and a stored answer callers cannot change. */
    @Test public void aHitAnswersExactlyWhatThePlanWouldHave() throws Exception {
        Tuning t = T(); Course c = course(t); Sim s = Sim.startOn(c, t, 0);
        GenTrace off = new GenTrace(), on = new GenTrace();
        Autopilot.Result[] ro = new Autopilot.Result[3], rn = new Autopilot.Result[3];
        GenTrace.GEN.set(off);
        try { for (int i = 0; i < 3; i++) ro[i] = Autopilot.planTo(s, 0, 1, false, true, i == 2); } finally { GenTrace.GEN.remove(); }
        PlanCache.MODE = PlanCache.Mode.ON; PlanCache.resetStats();
        PlanCache pc = PlanCache.open(); GenTrace.GEN.set(on);
        try {
            rn[0] = Autopilot.planTo(s, 0, 1, false, true, false);
            rn[0].ok = !rn[0].ok; rn[0].factory = null; rn[0].trials = -1;          // a caller scribbling on its answer
            rn[1] = Autopilot.planTo(s, 0, 1, false, true, false);
            rn[2] = Autopilot.planTo(s, 0, 1, false, true, true);
        } finally { GenTrace.GEN.remove(); PlanCache.close(pc); }
        assertNull(PlanCache.current());
        assertTrue("the second plan was answered from the cache", PlanCache.HITS.get() >= 1);
        for (int i = 1; i < 3; i++) {
            assertEquals(ro[i].ok, rn[i].ok); assertEquals(ro[i].trials, rn[i].trials); assertEquals(ro[i].successes, rn[i].successes);
            assertEquals(ro[i].window, rn[i].window, 0f); assertEquals(ro[i].viaHit, rn[i].viaHit); assertEquals(ro[i].hitSuccesses, rn[i].hitSuccesses);
            assertEquals(ro[i].factory == null, rn[i].factory == null);
            if (ro[i].factory != null) {
                Sim a = Autopilot.trialEnd(s, 0, ro[i].factory, 20f), b = Autopilot.trialEnd(s, 0, rn[i].factory, 20f);
                assertEquals("the same move", a == null, b == null);
                if (a != null) { assertEquals(a.time, b.time, 0f); assertEquals(a.s, b.s, 0f); assertEquals(a.y, b.y, 0f); assertEquals(a.onElem, b.onElem); }
            }
        }
        assertEquals("report tallies replayed exactly", off.counts, on.counts);
        assertEquals("phase names replayed", off.phaseNanos.keySet(), on.phaseNanos.keySet());
    }

    @Test public void shadowFindsNoDifferenceAndChangesNothing() throws Exception {
        Tuning t = T(); Course c = course(t); Sim s = Sim.startOn(c, t, 0);
        Autopilot.Result ref = Autopilot.planTo(s, 0, 1, false, true, false);
        PlanCache.MODE = PlanCache.Mode.SHADOW; PlanCache.resetStats();
        PlanCache pc = PlanCache.open();
        Autopilot.Result a, b;
        try { a = Autopilot.planTo(s, 0, 1, false, true, false); b = Autopilot.planTo(s, 0, 1, false, true, false); } finally { PlanCache.close(pc); }
        assertEquals(1, PlanCache.HITS.get());
        assertEquals(PlanCache.MISMATCH_LOG.toString(), 0, PlanCache.MISMATCHES.get());
        assertNotSame("shadow returns the real computation", a, b);
        assertEquals(ref.trials, b.trials); assertEquals(ref.ok, b.ok);
    }
}
