package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.util.List;
import org.junit.Test;

/** Rain-cloud platforms: the hero slides (low ground friction), everything else about movement is unchanged (same jump, same timing, same edge rules), and wet platforms exist only in the Frost world. */
public class WetPlatformTest {
    private static Sim platform(boolean wet) throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = new Course(0, t.circumference());
        Element p = new Element(Element.Type.STATIC, 10f, 0f, 30f); p.checkpoint = true; if (wet) p.color = Element.WET; c.add(p);
        Sim s = Sim.startOn(c, t, 0); s.s = 3f;
        return s;
    }

    private static float stopDistance(boolean wet) throws Exception {
        Sim s = platform(wet); InputState in = new InputState();
        for (int i = 0; i < 90; i++) { in.clear(); in.moveX = 1f; s.step(in); }       // get up to speed
        float s0 = s.s, v0 = s.vx;
        for (int i = 0; i < 300 && Math.abs(s.vx) > 0.05f; i++) { in.clear(); s.step(in); }       // let go of the stick
        assertTrue("was at run speed", v0 > 5f);
        return s.s - s0;
    }

    @Test public void aWetPlatformIsSlipperyAndADryOneIsAsBefore() throws Exception {
        float dry = stopDistance(false), wet = stopDistance(true);
        assertTrue("dry stop " + dry, dry < 0.9f);
        assertTrue("wet stop " + wet + " is a real slide (medium: a few metres) but not endless", wet > 3f * dry && wet > 1.8f && wet < 6f);
    }

    @Test public void jumpingWhileSlidingIsTheSameJump() throws Exception {
        Tuning t = TestUtil.tuning(); InputState in = new InputState();
        Sim d = platform(false), w = platform(true);
        for (int i = 0; i < 60; i++) { in.clear(); in.moveX = 1f; d.step(in); w.step(in); }
        in.clear(); in.moveX = 1f; in.jumpPressed = true; in.jumpHeld = true; d.step(in); w.step(in);
        assertEquals(Sim.Mode.AIR, w.mode); assertEquals("same take-off speed", d.vy, w.vy, 1e-4f);
        float topD = 0, topW = 0;
        for (int i = 0; i < 80; i++) { in.clear(); in.moveX = 1f; in.jumpHeld = true; d.step(in); w.step(in); topD = Math.max(topD, d.y); topW = Math.max(topW, w.y); }
        assertEquals("same jump height", topD, topW, 1e-3f);
        // jumping out while sliding after letting go of the stick
        Sim s = platform(true);
        for (int i = 0; i < 60; i++) { in.clear(); in.moveX = 1f; s.step(in); }
        for (int i = 0; i < 20; i++) { in.clear(); s.step(in); }
        assertTrue("still sliding", s.vx > 2f);
        in.clear(); in.jumpPressed = true; in.jumpHeld = true; s.step(in);
        assertEquals(Sim.Mode.AIR, s.mode); assertEquals(t.jumpVel, s.vy, 0.01f);
    }

    @Test public void slidingOffTheEdgeKeepsTheMomentumAndFallsUnderTheNormalRules() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = new Course(0, t.circumference());
        Element p = new Element(Element.Type.STATIC, 10f, 0f, 4f); p.checkpoint = true; p.color = Element.WET; c.add(p);
        Sim s = Sim.startOn(c, t, 0); s.s = 8.3f; InputState in = new InputState();
        for (int i = 0; i < 200 && s.s < 11.6f; i++) { in.clear(); in.moveX = 1f; s.step(in); }          // run to the far end, then let go of the stick
        boolean off = false; float vxOff = 0;
        for (int i = 0; i < 120 && !off; i++) { in.clear(); s.step(in); if (s.mode == Sim.Mode.AIR) { off = true; vxOff = s.vx; } }
        assertTrue("slid off the end", off); assertTrue("with momentum " + vxOff, vxOff > 1f);
    }

    @Test public void wetPlatformsAppearOnlyInTheFrostWorldAndNeverAsCheckpoints() throws Exception {
        Tuning t = TestUtil.tuning();
        List<Course> sl = TestUtil.slices(160, 1130f);
        int wet = 0, wetOutsideFrost = 0;
        double yBase = 0;
        for (Course c : sl) {
            for (int i = 0; i < c.size(); i++) {
                Element e = c.get(i);
                if (!e.wet()) continue;
                wet++; assertFalse("a wet platform is never a checkpoint", e.checkpoint); assertTrue("wide enough to stand on", e.w >= 4.5f);
                if (e.zone != 1) wetOutsideFrost++;
            }
        }
        assertEquals("wet platforms only in the Frost theme", 0, wetOutsideFrost);
        assertTrue("some were generated in the Frost world (" + wet + ")", wet >= 1);
    }
}
