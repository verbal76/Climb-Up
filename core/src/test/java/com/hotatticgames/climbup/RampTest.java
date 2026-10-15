package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

public class RampTest {
    private static final float SLOPE = 0.576f, L = 3.5f;

    /** start platform (0), ramp (1) rising toward +s, block (2) at the top. */
    private static Course course(Tuning t, int skin) {
        Course c = new Course(0, t.circumference());
        Element p = new Element(Element.Type.STATIC, 6f, 0f, 4f); p.checkpoint = true; c.add(p);
        Element r = new Element(Element.Type.RAMP, 8f + 0.2f + L / 2f, SLOPE * L / 2f, L); r.amp = SLOPE; r.skin = skin; r.len = 1.6f; c.add(r);
        Element b = new Element(Element.Type.STATIC, 8.2f + L + 1.5f, SLOPE * L, 3f); c.add(b);
        return c;
    }

    private static Sim climb(Tuning t, Course c, int steps) {
        Sim s = Sim.startOn(c, t, 0);
        InputState in = new InputState();
        for (int i = 0; i < steps && !(s.mode == Sim.Mode.GROUND && s.onElem == 2); i++) { in.clear(); in.moveX = 1f; in.jumpPressed = s.mode == Sim.Mode.GROUND && s.onElem == 0 && s.s > 7.7f; in.jumpHeld = true; s.step(in); }
        return s;
    }

    @Test public void plainRampWalksUpToATallerBlock() throws Exception {
        Tuning t = TestUtil.tuning();
        Sim s = climb(t, course(t, 0), 400);
        assertEquals(2, s.onElem);
        assertEquals(0, s.falls);
    }

    @Test public void rampSurfaceFollowsTheSlope() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = course(t, 0);
        Sim s = Sim.startOn(c, t, 1);
        InputState in = new InputState();
        for (int i = 0; i < 25; i++) { in.clear(); in.moveX = 1f; s.step(in); }
        assertEquals(Sim.Mode.GROUND, s.mode); assertEquals(1, s.onElem);
        assertEquals(SLOPE * (s.s - c.get(1).s) + c.get(1).y, s.y, 0.12f);
    }

    @Test public void crumblingRampFallsApartWhenStoodOn() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = course(t, 1);
        Sim s = Sim.startOn(c, t, 1);
        InputState in = new InputState();
        for (int i = 0; i < (int) ((t.crumbleDelay + 0.5f) / Sim.DT); i++) { in.clear(); s.step(in); }
        assertTrue(s.gone[1] || s.mode == Sim.Mode.AIR);
    }

    @Test public void shakingRampThrowsYouAround() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = course(t, 2);
        Sim s = Sim.startOn(c, t, 1);
        InputState in = new InputState();
        boolean bounced = false;
        for (int i = 0; i < 90; i++) { in.clear(); s.step(in); if ((s.consumeEvents() & Sim.EV_BOUNCE) != 0) bounced = true; }
        assertTrue("standing on a shaking ramp bounces you", bounced);
    }

    @Test public void sinkingRampDropsAwayAndRecovers() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = course(t, 3);
        Sim s = Sim.startOn(c, t, 1);
        InputState in = new InputState();
        for (int i = 0; i < 90; i++) { in.clear(); s.step(in); }
        assertTrue("it sinks under a standing player: " + s.tilt[1], s.tilt[1] > 1f);
        s.s = c.wrap(6f); s.y = 0f; s.vy = 0; s.vx = 0; s.mode = Sim.Mode.GROUND; s.onElem = 0;       // step off to the start platform
        for (int i = 0; i < 400; i++) { in.clear(); s.step(in); }
        assertEquals(0f, s.tilt[1], 1e-3f);
    }
}
