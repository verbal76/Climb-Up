package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/** A fall only ends on something to land on, or below the lowest platform of the whole map (not just of the simulated slice). */
public class FullFallTest {
    @Test public void floorIsTheLowestPlatformOfTheWholeMap() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = new Course(0, t.circumference());
        Element a = new Element(Element.Type.STATIC, 10f, 0f, 4f); a.checkpoint = true; c.add(a);
        Element b = new Element(Element.Type.STATIC, 10f, 300f, 4f); b.checkpoint = true; c.add(b);
        Sim s = Sim.startOn(c, t, 1);
        s.setRange(1, 1);
        assertTrue("floor must be below the bottom platform, not the slice", s.floorY < 0f);
        s.mode = Sim.Mode.AIR; s.onElem = -1; s.s = c.wrap(40f); s.y = 305f; s.vy = 0;
        InputState in = new InputState();
        for (int i = 0; i < 60 * 3 && s.falls == 0; i++) { in.clear(); s.step(in); }
        assertEquals("still falling after 3 s, no early respawn", 0, s.falls);
    }
}
