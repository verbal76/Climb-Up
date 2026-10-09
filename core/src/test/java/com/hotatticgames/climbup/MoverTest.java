package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

public class MoverTest {
    private static Sim stand(Element.Type type, float phase) throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = new Course(0, t.circumference());
        Element a = new Element(Element.Type.STATIC, 10f, 0f, 4f); a.checkpoint = true; c.add(a);
        Element m = new Element(type, 20f, 0f, 3f); m.amp = type == Element.Type.SWING ? 0.5f : 3f; m.len = type == Element.Type.SWING ? 5f : 0f; m.period = 4f; m.phase = phase; c.add(m);
        return Sim.startOn(c, t, 0);
    }

    @Test public void aDepthMoverCannotBeLandedOnWhileItIsOutOfYourPlane() throws Exception {
        Sim away = stand(Element.Type.MOVE_Z, (float) Math.PI / 2f);        // depth = +amp at t=0
        away.mode = Sim.Mode.AIR; away.onElem = -1; away.s = away.es1[1]; away.y = 1.5f; away.vy = -1f; away.vx = 0f;
        InputState in = new InputState();
        for (int i = 0; i < 20; i++) { in.clear(); away.step(in); }
        assertNotEquals("fell through the far platform", 1, away.onElem);
        Sim near = stand(Element.Type.MOVE_Z, 0f);                         // depth 0 at t=0
        near.mode = Sim.Mode.AIR; near.onElem = -1; near.s = near.es1[1]; near.y = 0.4f; near.vy = -1f; near.vx = 0f;
        for (int i = 0; i < 20; i++) { in.clear(); near.step(in); }
        assertEquals(1, near.onElem); assertEquals(Sim.Mode.GROUND, near.mode);
    }

    @Test public void aFallingMoverDropsAfterTheDelayAndAComeBackLater() throws Exception {
        Sim s = stand(Element.Type.MOVE_H, 0f); s.course.get(1).skin = 1; s.course.get(1).amp = 0.1f;
        s.mode = Sim.Mode.AIR; s.onElem = -1; s.s = s.es1[1]; s.y = 0.4f; s.vy = -1f; s.vx = 0f;
        InputState in = new InputState();
        int gone = -1;
        for (int i = 0; i < 400 && gone < 0; i++) { in.clear(); s.step(in); if (s.gone[1]) gone = i; }
        assertTrue("crumbled after standing on it", gone > 60 * 1.2 && gone < 60 * 2.6);
    }

    @Test public void jumpingAtTheTopOfASwingsArcCatapultsYou() throws Exception {
        float best = 0, low = 0;
        for (int pass = 0; pass < 2; pass++) {
            Sim s = stand(Element.Type.SWING, 0f);
            Element e = s.course.get(1);
            float tTop = pass == 0 ? e.period * 0.25f : 0f;                // quarter period = top of the arc, 0 = bottom (moving fastest)
            s.time = tTop - Sim.DT; InputState in = new InputState(); in.clear(); s.step(in);
            s.mode = Sim.Mode.GROUND; s.onElem = 1; s.s = s.es1[1]; s.y = s.ey1[1]; s.vx = s.vy = 0;
            in.clear(); in.jumpPressed = true; in.jumpHeld = true; s.step(in);
            if (pass == 0) best = s.vy; else low = s.vy;
        }
        assertTrue("top of arc " + best + " vs bottom " + low, best > low + 3f);
    }
}
