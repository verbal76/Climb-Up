package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

/** Build 77 (owner): the balance conveyor - a tipping bridge whose plank belt runs toward the low end, a calm middle, one per section from Castle 6 to Castle 10. */
public class BalanceConveyorTest {
    private static Course bridge(Tuning t) {
        Element sw = new Element(Element.Type.SEESAW, 30f, 0f, 8f); sw.skin = 4; sw.phase = 2.6f;
        return TestUtil.flat(t, sw);
    }
    private static Sim standOn(Course c, Tuning t, float dx) {
        Sim s = Sim.startOn(c, t, 1); s.s = c.wrap(s.es1[1] + dx); s.vx = 0f;
        return s;
    }

    @Test public void theBeltNeverStopsAndKeepsTheWayItLastRan() throws Exception {      // Build 79 (owner: moving at all times; only its direction follows the end you are on)
        Tuning t = TestUtil.tuning(); Course c = bridge(t); Element e = c.get(1);
        Sim s = standOn(c, t, 0f);
        assertEquals("standing on the pivot the belt already runs at full speed", Math.abs(e.phase), Math.abs(s.surfaceCarry(1)), 1e-4f);
        s = standOn(c, t, -2.2f); TestUtil.run(s, 20, 0f, false, -1);
        assertTrue("on the left end it runs left", s.surfaceCarry(1) < 0f);
        s.s = c.wrap(s.es1[1]); TestUtil.run(s, 1, 0f, false, -1);
        assertTrue("back on the calm middle it keeps running left", s.surfaceCarry(1) < 0f);
        assertEquals("at full speed", Math.abs(e.phase), Math.abs(s.surfaceCarry(1)), 1e-4f);
        s.mode = Sim.Mode.AIR; s.onElem = -1; s.y += 20f; s.vy = 0f; TestUtil.run(s, 30, 0f, false, -1);
        assertTrue("and with nobody on it", s.surfaceCarry(1) < 0f);
    }

    @Test public void theBeltRunsTowardTheLowEndAndReversesWithTheTilt() throws Exception {
        Tuning t = TestUtil.tuning(); Course c = bridge(t);
        Sim s = standOn(c, t, 2.2f);
        TestUtil.run(s, 20, 0f, false, -1);
        assertTrue("standing right of the middle tips the right end down", s.tilt[1] < 0f);
        assertTrue("and the belt carries you right, toward the low end", s.surfaceCarry(1) > 0f);
        float start = c.dsWrap(s.s, s.es1[1]);
        TestUtil.run(s, 20, 0f, false, -1);
        assertTrue("standing still you are carried off downhill faster than the plank alone would slide you", c.dsWrap(s.s, s.es1[1]) - start > 20 * Sim.DT * 0.5f * t.seesawSlide * Math.abs(s.tilt[1]));
        Sim l = standOn(c, t, -2.2f);
        TestUtil.run(l, 20, 0f, false, -1);
        assertTrue("on the left the left end goes down", l.tilt[1] > 0f);
        assertTrue("and the belt runs left", l.surfaceCarry(1) < 0f);
        Sim x = standOn(c, t, 2.2f);
        TestUtil.run(x, 30, 0f, false, -1);
        float before = x.surfaceCarry(1);
        x.s = c.wrap(x.es1[1] - 2.2f);
        // read while the climber is still on the belt (repair 2026-10-28): it then carries them off its low end, and the fall's retry starts the belt
        // fresh (the checkpoint contract, DC3), so a reading after the fall measured the retry, not the crossing
        for (int i = 0; i < 120 && x.mode == Sim.Mode.GROUND && x.onElem == 1; i++) TestUtil.run(x, 1, 0f, false, -1);
        assertTrue("crossing to the other side reverses the tilt and the belt (" + before + " -> " + x.surfaceCarry(1) + ")", before > 0f && x.surfaceCarry(1) < 0f);
        assertTrue("and the tilt follows (" + x.tilt[1] + ")", x.tilt[1] > 0f);
    }

    @Test public void theSameRunGivesTheSameBridgeEveryTime() throws Exception {
        Tuning t = TestUtil.tuning(); Course c = bridge(t);
        Sim a = standOn(c, t, 1.4f), b = standOn(c, t, 1.4f);
        TestUtil.run(a, 150, -1f, false, 40); TestUtil.run(b, 150, -1f, false, 40);
        assertEquals(a.s, b.s, 0f); assertEquals(a.y, b.y, 0f); assertEquals(a.tilt[1], b.tilt[1], 0f);
    }

    @Test public void onePerSectionBetweenCastleSixAndCastleTenAndNoneElsewhere() throws Exception {
        float sp = TestUtil.tuning().castleSpacing;
        for (long seed = 1; seed <= 8; seed++) {
            int[] per = new int[13];
            for (Course c : TestUtil.slices(seed, 5300f)) for (int i = 0; i < c.size(); i++) {
                Element e = c.get(i);
                if (!e.tiltBelt()) continue;
                assertTrue("a balance conveyor is always on the route (seed " + seed + ")", i < c.routeSize());
                per[Math.min(12, (int) Math.floor(e.y / sp) + 1)]++;
            }
            for (int n = 1; n <= 12; n++) assertEquals("seed " + seed + " section " + n + " (castle " + (n - 1) + " to " + n + ")", n >= CourseGenerator.BELT_SAW_FIRST && n <= CourseGenerator.BELT_SAW_LAST ? 1 : 0, per[n]);
        }
    }

    @Test public void everySliceWithABalanceConveyorIsSolvable() throws Exception {
        Tuning t = TestUtil.tuning(); int n = 0;
        for (long seed = 1; seed <= 3; seed++) for (Course c : TestUtil.slices(seed, 5300f)) {
            boolean has = false; for (int i = 0; i < c.routeSize(); i++) if (c.get(i).tiltBelt()) has = true;
            if (!has) continue;
            Autopilot.Report r = Autopilot.run(c, t, 4000f); n++;
            assertTrue("seed " + seed + ": solvable across the balance conveyor (link " + r.failedLink + ": " + r.failInfo + ")", r.completed);
        }
        assertEquals("four each", 12, n);
    }
}
