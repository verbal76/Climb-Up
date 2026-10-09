package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/** Pushing on at the top of a rope hauls the climber up onto the beam; he can stand there, jump from it, or step back down. */
public class RopeTopTest {
    private static Sim onRope() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = new Course(0, t.circumference());
        Element a = new Element(Element.Type.STATIC, 10f, 0f, 4f); a.checkpoint = true; c.add(a);
        Element r = new Element(Element.Type.ROPE, 16f, 8f, 1f); r.len = 8f; c.add(r);
        Sim s = Sim.startOn(c, t, 0);
        s.mode = Sim.Mode.ROPE; s.onElem = 1; s.s = s.es1[1]; s.y = r.y - 3f; s.vx = s.vy = 0;
        return s;
    }
    private static void run(Sim s, float up, float x, boolean jump, int steps) {
        InputState in = new InputState();
        for (int i = 0; i < steps; i++) { in.clear(); in.moveY = up; in.moveX = x; if (jump && i == 0) { in.jumpPressed = true; in.jumpHeld = true; } s.step(in); }
    }

    @Test public void holdingUpAtTheTopMountsTheBeamAndStandsOnIt() throws Exception {
        Sim s = onRope(); Element r = s.course.get(1);
        run(s, 1f, 0f, false, 240);
        assertEquals(Sim.Mode.BEAM, s.mode);
        assertFalse(s.mounting());
        assertEquals(r.y + Sim.BEAM_TOP, s.y, 1e-3f);
    }

    @Test public void briefTouchOfTheTopDoesNotMount() throws Exception {
        Sim t = onRope();
        InputState in = new InputState(); boolean mounted = false;
        for (int i = 0; i < 400 && !mounted; i++) { in.clear(); in.moveY = 1f; t.step(in); if (t.mode == Sim.Mode.ROPE && t.y >= t.course.get(1).y - t.T.handHeight - 0.01f) { in.clear(); in.jumpPressed = true; in.jumpHeld = true; t.step(in); break; } mounted = t.mode == Sim.Mode.BEAM; }
        assertNotEquals("jumping off the instant the top is reached must not mount", Sim.Mode.BEAM, t.mode);
    }

    @Test public void jumpingFromTheBeamLeavesTheGroundAndCanBeSteppedBackDownToTheRope() throws Exception {
        Sim s = onRope(); run(s, 1f, 0f, false, 240);
        assertEquals(Sim.Mode.BEAM, s.mode);
        run(s, 0f, 1f, true, 1);
        assertEquals(Sim.Mode.AIR, s.mode); assertTrue(s.vy > 5f);
        Sim d = onRope(); run(d, 1f, 0f, false, 240);
        run(d, -1f, 0f, false, 2);
        assertEquals(Sim.Mode.ROPE, d.mode);
    }

    @Test public void walkingOffTheEndOfTheBeamDrops() throws Exception {
        Sim s = onRope(); run(s, 1f, 0f, false, 240);
        run(s, 0f, 1f, false, 120);
        assertEquals(Sim.Mode.AIR, s.mode);
    }
}
