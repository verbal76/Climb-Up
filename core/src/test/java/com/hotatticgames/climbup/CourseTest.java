package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.util.EnumMap;
import org.junit.Test;

public class CourseTest {
    @Test public void shippedTowersAreCompletableByTheAutopilot() throws Exception {
        Tuning t = TestUtil.tuning();
        for (int i = 1; i <= 6; i++) {
            Course c = TestUtil.tower(i);
            long t0 = System.currentTimeMillis();
            Autopilot.Report r = Autopilot.run(c, t, 9000f);
            System.out.printf("tower%02d: %d elements, completed=%s, autopilot time %.0fs (%dms)%n", i, c.size(), r.completed, r.simTime, System.currentTimeMillis() - t0);
            assertTrue("tower " + i + " failed at link " + r.failedLink, r.completed);
            assertTrue("a first-time climb takes a while", r.simTime > 240f);
        }
    }

    @Test public void towersMatchTheDesignedStructure() throws Exception {
        Tuning t = TestUtil.tuning();
        EnumMap<Element.Type, Integer> seen = new EnumMap<>(Element.Type.class);
        for (int i = 1; i <= 6; i++) {
            Course c = TestUtil.tower(i);
            assertEquals(Element.Type.GOAL, c.get(c.goalIndex()).type);
            assertTrue(c.get(0).checkpoint);
            float prevTop = -1; int cps = 0, gap = 0, maxGap = 0;
            for (int k = 0; k < c.size(); k++) {
                Element e = c.get(k);
                seen.merge(e.type, 1, Integer::sum);
                if (e.isPlatform()) { assertTrue("unbroken overall ascent", e.y > prevTop - 0.01f || e.type == Element.Type.CRUMBLE); prevTop = Math.max(prevTop, e.y - 3f); }
                if (e.checkpoint) { cps++; maxGap = Math.max(maxGap, gap); gap = 0; } else gap++;
                assertTrue(Float.isFinite(e.s) && Float.isFinite(e.y));
            }
            assertTrue("checkpoints exist regularly (every <= 14 route elements)", maxGap <= 14);
            assertTrue(cps >= 15);
            // spiral layers must never overlap: non-neighbouring elements at the same angle need real vertical separation
            for (int a = 0; a < c.size(); a++) for (int b = a + 1; b < c.size(); b++) {
                Element ea = c.get(a), eb = c.get(b);
                assertFalse("layer clash in tower " + i + " between " + a + " and " + b, CourseGenerator.layersClash(c, ea, eb));
            }
        }
        for (Element.Type ty : Element.Type.values()) assertTrue("tower bank uses element type " + ty, seen.getOrDefault(ty, 0) > 0);
    }

    @Test public void generatorIsDeterministicPerSeed() throws Exception {
        Tuning t = TestUtil.tuning();
        Tuning small = TestUtil.tuning(); small.courseHeight = 40f;
        String a = CourseIO.toJson(CourseGenerator.generate(777, small)), b = CourseIO.toJson(CourseGenerator.generate(777, small)), c = CourseIO.toJson(CourseGenerator.generate(778, small));
        assertEquals(a, b); assertNotEquals(a, c);
    }

    @Test public void freshSeedsAreValidAndDifferent() throws Exception {
        Tuning t = TestUtil.tuning(); t.courseHeight = 90f;
        String prev = null;
        for (long seed : new long[]{11, 12, 13, 14}) {
            Course c = CourseGenerator.generate(seed, t);
            Autopilot.Report r = Autopilot.run(c, t, 3000f);
            assertTrue("seed " + seed + " failedLink=" + r.failedLink, r.completed);
            String j = CourseIO.toJson(c);
            assertNotEquals(prev, j); prev = j;
        }
    }

    @Test public void everyLinkHasMeasurableTolerance() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = TestUtil.tower(3);
        int checked = 0; float worst = 1f;
        for (int i = 0; i < c.size() - 1; i += 3) {
            Autopilot.Result r = Autopilot.plan(Sim.startOn(c, t, i), i, true);
            assertTrue("link " + i, r.ok);
            worst = Math.min(worst, r.margin()); checked++;
            assertTrue("link " + i + " margin " + r.margin(), r.margin() >= 0.04f);
        }
        System.out.println("links checked=" + checked + " worst margin=" + worst);
    }
}
