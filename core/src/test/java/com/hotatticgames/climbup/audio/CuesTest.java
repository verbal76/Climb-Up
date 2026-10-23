package com.hotatticgames.climbup.audio;

import static org.junit.Assert.*;
import com.hotatticgames.climbup.sim.Element;
import org.junit.Test;

public class CuesTest {
    private static Element e(Element.Type t, int skin, int zone) { Element x = new Element(t, 0, 0, 6f); x.skin = skin; x.zone = zone; return x; }

    @Test public void conveyorsAreBeltsOnly() {
        assertTrue(Cues.isConveyor(e(Element.Type.STATIC, 4, 0)));
        assertTrue(Cues.isConveyor(e(Element.Type.RAMP, 4, 0)));
        assertFalse(Cues.isConveyor(e(Element.Type.RAMP, 3, 0)));
        assertFalse(Cues.isConveyor(e(Element.Type.STATIC, 0, 0)));
        assertFalse(Cues.isConveyor(null));
    }
    @Test public void surfacesFollowSkinAndWorld() {
        assertEquals(Cues.Surface.GRASS, Cues.surface(e(Element.Type.STATIC, 0, 0)));
        assertEquals(Cues.Surface.STONE, Cues.surface(e(Element.Type.STATIC, 0, 1)));
        assertEquals(Cues.Surface.STONE, Cues.surface(e(Element.Type.STATIC, 0, 4)));
        assertEquals(Cues.Surface.WOOD, Cues.surface(e(Element.Type.STATIC, 1, 0)));
        assertEquals(Cues.Surface.WOOD, Cues.surface(e(Element.Type.SEESAW, 0, 2)));
    }
    @Test public void humFadesWithDistance() {
        assertEquals(1f, Cues.humLevel(0f, true), 0f);
        assertTrue(Cues.humLevel(2f, false) > Cues.humLevel(6f, false));
        assertEquals(0f, Cues.humLevel(Cues.HUM_RANGE + 1f, false), 0f);
    }
    @Test public void stepRateFollowsSpeed() {
        assertTrue(Cues.stepInterval(8f) <= Cues.stepInterval(3f));
        assertTrue(Cues.stepInterval(0f) <= 0.4f && Cues.stepInterval(100f) >= 0.2f);
    }
}
