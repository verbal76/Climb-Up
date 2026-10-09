package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

public class BridgeTest {
    private static Course seesawCourse(Tuning t) {
        Course c = new Course(0, t.circumference());
        Element sw = new Element(Element.Type.SEESAW, 10f, 0f, 8f); sw.skin = 1; c.add(sw);
        Element p2 = new Element(Element.Type.STATIC, 17.2f, 0.2f, 3f); c.add(p2);
        return c;
    }

    @Test public void standingStillOnASeesawTipsYouOff() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = seesawCourse(t);
        Sim s = Sim.startOn(c, t, 0);
        InputState in = new InputState();
        float maxTilt = 0;
        for (int i = 0; i < 240; i++) { in.clear(); s.step(in); maxTilt = Math.max(maxTilt, Math.abs(s.tilt[0])); }
        assertTrue("the plank tips under the weight: " + maxTilt, maxTilt > 0.4f);
        assertTrue("and slides the idle player off (respawn counts)", s.falls > 0 || s.mode != Sim.Mode.GROUND || s.onElem != 0);
    }

    @Test public void runningAcrossMakesIt() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = seesawCourse(t);
        Sim s = Sim.startOn(c, t, 0);
        InputState in = new InputState();
        for (int i = 0; i < 400 && !(s.mode == Sim.Mode.GROUND && s.onElem == 1); i++) { in.clear(); in.moveX = 1f; in.jumpPressed = s.mode == Sim.Mode.GROUND && s.onElem == 0 && s.s > 11.5f; in.jumpHeld = true; s.step(in); }
        assertEquals(1, s.onElem);
        assertEquals(0, s.falls);
    }

    @Test public void seesawLevelsOutWhenEmpty() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = seesawCourse(t);
        Sim s = Sim.startOn(c, t, 0);
        InputState in = new InputState();
        for (int i = 0; i < 20; i++) { in.clear(); s.step(in); }
        assertTrue(Math.abs(s.tilt[0]) > 0.1f);
        s.s = c.wrap(s.s - 30f); s.y = 40f; s.vy = 0; s.mode = Sim.Mode.AIR; s.onElem = -1;
        for (int i = 0; i < 120; i++) { in.clear(); s.step(in); }
        assertEquals(0f, s.tilt[0], 1e-3f);
    }

    @Test public void fallingOntoALowerLevelResumesInsteadOfRespawning() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = new Course(0, t.circumference());
        Element hi = new Element(Element.Type.STATIC, 10f, 30f, 6f); hi.checkpoint = true; c.add(hi);
        Element lo = new Element(Element.Type.STATIC, 20f, 0f, 14f); c.add(lo);
        Sim s = Sim.startOn(c, t, 0);
        s.setRange(0, 1);
        InputState in = new InputState();
        for (int i = 0; i < 600; i++) { in.clear(); in.moveX = i < 40 ? 1f : 0f; s.step(in); }
        assertEquals("no respawn while there is something to land on", 0, s.falls);
        assertEquals(1, s.onElem);
        // below the lowest level there is nothing left to land on
        s.s = c.wrap(s.s + 60f); s.y = -3f; s.vy = 0; s.mode = Sim.Mode.AIR; s.onElem = -1;
        for (int i = 0; i < 300 && s.falls == 0; i++) { in.clear(); s.step(in); }
        assertEquals(1, s.falls);
        assertEquals(0, s.onElem);
    }
}
