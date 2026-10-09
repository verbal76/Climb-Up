package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/** One red-gem checkpoint per section between castles, exactly halfway (250, 750, 1250 ...), standing on a platform. */
public class GemCheckpointTest {
    @Test public void exactlyOneGemPlatformHalfwayBetweenEveryTwoCastles() throws Exception {
        Tuning t = TestUtil.tuning();
        for (long seed : new long[]{7L, 55L}) {
            Course prev = null; float y = 0; int k = 0; java.util.List<Float> gems = new java.util.ArrayList<>();
            while (y < 2700f) {
                Course c = CourseGenerator.chunk(seed, k++, prev, t); prev = c; y = c.get(c.routeSize() - 1).y;
                for (int i = 1; i < c.routeSize(); i++) { Element e = c.get(i); if (e.type == Element.Type.STATIC && e.skin == 3) { assertTrue("a gem sits on a wide platform", e.w >= 5f); assertTrue(e.checkpoint); gems.add(e.y); } }
            }
            assertTrue("gems for the first five sections (" + gems + ")", gems.size() >= 5);
            for (int j = 0; j < 5; j++) assertEquals("gem " + (j + 1) + " (seed " + seed + ")", 250f + 500f * j, gems.get(j), 1e-3f);
            for (int j = 0; j < gems.size(); j++) assertEquals("only one per section", 250f + 500f * j, gems.get(j), 1e-3f);
        }
    }

    @Test public void onlyGemPlatformsAreCheckpointsInTheEndlessClimb() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = TestUtil.flat(t); c.gemCheckpoints = true;
        Element rest = new Element(Element.Type.STATIC, 22f, 0.5f, 6f); rest.checkpoint = true; c.add(rest);       // a rest platform: structural flag only
        Element gem = new Element(Element.Type.STATIC, 34f, 1f, 6f); gem.checkpoint = true; gem.skin = 3; c.add(gem);
        Sim s = Sim.startOn(c, t, 0);
        s.mode = Sim.Mode.AIR; s.onElem = -1; s.s = 22f; s.y = 1.5f; s.vy = 0; s.vx = 0;
        InputState in = new InputState(); for (int i = 0; i < 40; i++) { in.clear(); s.step(in); }
        assertEquals("landed on the rest platform", 1, s.onElem); assertEquals("rest platforms are not checkpoints", 0, s.checkpoint);
        s.mode = Sim.Mode.AIR; s.onElem = -1; s.s = 34f; s.y = 2f; s.vy = 0; s.vx = 0;
        int ev = 0; for (int i = 0; i < 40; i++) { in.clear(); s.step(in); ev |= s.consumeEvents(); }
        assertEquals(2, s.onElem); assertEquals("the gem is", 2, s.checkpoint); assertTrue((ev & Sim.EV_CHECKPOINT) != 0);
    }
}
