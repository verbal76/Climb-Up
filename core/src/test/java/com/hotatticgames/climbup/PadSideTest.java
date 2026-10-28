package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/** Running off a platform into the side of a bounce pad (same height, a little higher or lower) must bounce, not pass through it. */
public class PadSideTest {
    private static boolean bounced(Element.Type type, float gap, float dy) throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = new Course(0, t.circumference());
        Element a = new Element(Element.Type.STATIC, 10f, 0f, 4f); a.checkpoint = true; c.add(a);
        Element b = new Element(type, 10f + 2f + gap + 1f, dy, 2f); b.amp = 0.4f; c.add(b);
        Sim s = Sim.startOn(c, t, 0);
        s.s = c.wrap(s.s - 1.0f);
        InputState in = new InputState();
        for (int i = 0; i < 240; i++) { in.clear(); in.moveX = 1f; s.step(in); if ((s.consumeEvents() & Sim.EV_BOUNCE) != 0) return true; if (s.falls > 0) return false; }
        return false;
    }
    @Test public void walkingIntoThePadFromTheSideBounces() throws Exception {
        for (float dy : new float[]{-0.5f, 0f, 0.2f, 0.5f, 0.9f}) for (float gap : new float[]{0.2f, 0.6f, 1.0f})
            assertTrue("pad gap " + gap + " dy " + dy, bounced(Element.Type.PAD, gap, dy));
    }
    @Test public void sameForAnAngledSpring() throws Exception {
        for (float dy : new float[]{0f, 0.4f}) assertTrue("spring dy " + dy, bounced(Element.Type.SPRING, 0.4f, dy));
    }
}
