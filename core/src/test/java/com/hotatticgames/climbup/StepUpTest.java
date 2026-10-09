package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/** Walking (no jump) off a platform towards a close, slightly higher platform must catch the edge instead of falling through it. */
public class StepUpTest {
    private static Sim walk(float gap, float dy, int steps) throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = new Course(0, t.circumference());
        Element a = new Element(Element.Type.STATIC, 10f, 0f, 4f); a.checkpoint = true; c.add(a);
        Element b = new Element(Element.Type.STATIC, 10f + 2f + gap + 2f, dy, 4f); c.add(b);
        Sim s = Sim.startOn(c, t, 0);
        s.s = c.wrap(s.s - 1.0f);          // start a metre back from the middle
        InputState in = new InputState();
        for (int i = 0; i < steps && s.falls == 0 && !(s.onElem == 1 && s.mode == Sim.Mode.GROUND); i++) { in.clear(); in.moveX = 1f; s.step(in); }
        return s;
    }

    @Test public void walkingOffTowardsACloseHigherPlatformStepsUpOntoIt() throws Exception {
        for (float dy : new float[]{0.2f, 0.5f, 0.9f}) for (float gap : new float[]{0.2f, 0.6f, 1.0f}) {
            Sim s = walk(gap, dy, 240);
            assertEquals("gap " + gap + " dy " + dy, 1, s.onElem);
            assertEquals(Sim.Mode.GROUND, s.mode);
            assertEquals(0, s.falls);
        }
    }

    @Test public void aSameHeightGapOfAMetreIsStillCaught() throws Exception {
        Sim s = walk(0.9f, 0f, 240);
        assertEquals(1, s.onElem); assertEquals(0, s.falls);
    }
}
