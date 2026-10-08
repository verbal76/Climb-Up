package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

public class SimTest {
    @Test public void tuningIsSane() throws Exception {
        Tuning t = TestUtil.tuning();
        for (java.lang.reflect.Field f : Tuning.class.getFields()) {
            Object v = f.get(t);
            if (v instanceof Float) { float x = (Float) v; assertTrue(f.getName(), !Float.isNaN(x) && !Float.isInfinite(x) && x >= 0); }
        }
        assertTrue(t.gravity > 0 && t.jumpVel > 0 && t.runSpeed > 0 && t.radius > 3);
    }

    @Test public void jumpHeightMatchesPhysics() throws Exception {
        Tuning t = TestUtil.tuning();
        Sim s = Sim.startOn(TestUtil.flat(t), t, 0);
        float y0 = s.y, peak = y0;
        InputState in = new InputState();
        for (int i = 0; i < 120; i++) { in.clear(); in.jumpHeld = true; in.jumpPressed = i == 2; s.step(in); peak = Math.max(peak, s.y); }
        float expected = t.jumpVel * t.jumpVel / (2 * t.gravity);
        assertEquals(expected, peak - y0, 0.15f);
        assertEquals(Sim.Mode.GROUND, s.mode);
    }

    @Test public void shortHopWhenReleased() throws Exception {
        Tuning t = TestUtil.tuning();
        Sim s = Sim.startOn(TestUtil.flat(t), t, 0);
        float peak = s.y; InputState in = new InputState();
        for (int i = 0; i < 90; i++) { in.clear(); in.jumpHeld = i < 3; in.jumpPressed = i == 1; s.step(in); peak = Math.max(peak, s.y); }
        assertTrue("released early => lower jump", peak < 1.2f);
    }

    @Test public void coyoteAndBufferedJump() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = TestUtil.flat(t);
        Sim s = Sim.startOn(c, t, 0);
        InputState in = new InputState();
        // walk off the right edge, press jump within coyote time
        int off = -1;
        for (int i = 0; i < 300 && off < 0; i++) { in.clear(); in.moveX = 1; s.step(in); if (s.mode == Sim.Mode.AIR) off = i; }
        assertTrue(off > 0);
        in.clear(); in.moveX = 1; in.jumpPressed = true; in.jumpHeld = true; s.step(in);
        assertTrue("coyote jump rises", s.vy > 5f);
    }

    @Test public void padBounces() throws Exception {
        Tuning t = TestUtil.tuning();
        Element pad = new Element(Element.Type.PAD, 15f, 0f, 2f);
        Course c = TestUtil.flat(t, pad);
        Sim s = Sim.startOn(c, t, 0);
        float peak = 0; InputState in = new InputState(); boolean bounced = false;
        for (int i = 0; i < 240; i++) { in.clear(); in.moveX = i < 100 ? 1f : 0f; s.step(in); if ((s.consumeEvents() & Sim.EV_BOUNCE) != 0) bounced = true; peak = Math.max(peak, s.y); }
        assertTrue(bounced); assertTrue("pad launches higher than a jump", peak > 4.5f);
    }

    @Test public void ropeClimbAndLeap() throws Exception {
        Tuning t = TestUtil.tuning();
        Element rope = new Element(Element.Type.ROPE, 15.5f, 6f, 0f); rope.len = 5.6f;
        Course c = TestUtil.flat(t, rope);
        Sim s = Sim.startOn(c, t, 0);
        InputState in = new InputState(); boolean roped = false;
        for (int i = 0; i < 200 && !roped; i++) { in.clear(); in.moveX = 1; in.jumpHeld = true; in.jumpPressed = i == 40; s.step(in); roped = s.mode == Sim.Mode.ROPE; }
        assertTrue("grabbed the rope by jumping into it", roped);
        float y0 = s.y;
        for (int i = 0; i < 60; i++) { in.clear(); in.moveY = 1; s.step(in); }
        assertTrue("climbs up", s.y > y0 + 1.5f);
        in.clear(); in.jumpPressed = true; in.moveX = -1; s.step(in);
        assertEquals(Sim.Mode.AIR, s.mode); assertTrue(s.vx < -3f);
    }

    @Test public void ledgeGrabSavesAShortJump() throws Exception {
        Tuning t = TestUtil.tuning();
        Element hi = new Element(Element.Type.STATIC, 21.2f, 1.2f, 4f);  // left edge at 19.2, start edge at 14
        Course c = TestUtil.flat(t, hi);
        Sim s = Sim.startOn(c, t, 0);
        // Place the player short of the ledge, falling toward it
        s.s = 18.9f; s.y = -0.3f; s.vx = 3f; s.vy = 0f; s.mode = Sim.Mode.AIR; s.onElem = -1; s.lockout = 0f;
        InputState in = new InputState(); boolean grabbed = false;
        for (int i = 0; i < 40 && !grabbed; i++) { in.clear(); in.moveX = 1; s.step(in); grabbed = s.mode == Sim.Mode.LEDGE; }
        assertTrue("grabs ledge", grabbed);
        for (int i = 0; i < 40 && s.mode != Sim.Mode.GROUND; i++) { in.clear(); in.moveY = 1; s.step(in); }
        assertEquals(Sim.Mode.GROUND, s.mode); assertEquals(1, s.onElem); assertEquals(1.2f, s.y, 0.001f);
    }

    @Test public void crumbleFallsAndRespawns() throws Exception {
        Tuning t = TestUtil.tuning();
        Element cr = new Element(Element.Type.CRUMBLE, 16f, 0f, 2f);
        Course c = TestUtil.flat(t, cr);
        Sim s = Sim.startOn(c, t, 1);
        InputState in = new InputState();
        for (int i = 0; i < (int) ((t.crumbleDelay + 0.2f) / Sim.DT); i++) { in.clear(); s.step(in); }
        assertTrue(s.gone[1]); assertEquals(Sim.Mode.AIR, s.mode);
        for (int i = 0; i < (int) ((t.crumbleRespawn + 0.5f) / Sim.DT) && s.gone[1]; i++) { in.clear(); in.moveX = 0; s.step(in); if (s.y < -3) break; }
    }

    @Test public void fallingFarReturnsToCheckpointAndNeverSoftLocks() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = TestUtil.flat(t);
        Sim s = Sim.startOn(c, t, 0);
        InputState in = new InputState();
        for (int i = 0; i < 400 && s.falls == 0; i++) { in.clear(); in.moveX = 1; s.step(in); }
        assertEquals(1, s.falls);
        assertEquals(Sim.Mode.GROUND, s.mode); assertEquals(0, s.onElem);
        // and the player can act again immediately
        in.clear(); in.jumpPressed = true; in.jumpHeld = true; s.step(in);
        assertTrue(s.vy > 5);
    }

    @Test public void deterministicAndCloneable() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = TestUtil.tower(1);
        Sim a = Sim.startOn(c, t, 0), b = a.copy();
        java.util.Random r = new java.util.Random(5);
        InputState in = new InputState();
        for (int i = 0; i < 3000; i++) {
            in.clear(); in.moveX = r.nextInt(3) - 1; in.moveY = r.nextInt(3) - 1; in.jumpHeld = r.nextBoolean(); in.jumpPressed = r.nextInt(25) == 0;
            a.step(in); b.step(in);
        }
        assertEquals(a.s, b.s, 0f); assertEquals(a.y, b.y, 0f); assertEquals(a.falls, b.falls);
    }

    @Test public void fuzzNeverProducesInvalidState() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = TestUtil.tower(2);
        for (int seed = 0; seed < 4; seed++) {
            Sim s = Sim.startOn(c, t, c.lastCheckpointAtOrBefore(40 + seed * 20));
            java.util.Random r = new java.util.Random(seed);
            InputState in = new InputState();
            for (int i = 0; i < 12000; i++) {
                in.clear(); in.moveX = r.nextFloat() * 2 - 1; in.moveY = r.nextInt(3) - 1; in.jumpHeld = r.nextBoolean(); in.jumpPressed = r.nextInt(20) == 0;
                s.step(in);
                assertTrue(Float.isFinite(s.s) && Float.isFinite(s.y) && Float.isFinite(s.vx) && Float.isFinite(s.vy));
                assertTrue(s.s >= 0 && s.s < c.circumference + 1e-3);
                assertTrue("player never leaves the tower's vertical range", s.y > -20 && s.y < t.courseHeight + 30);
            }
        }
    }
}
