package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.Element;
import org.junit.Test;

/** The drawn cannonball is the lethal ball while it is lethal, and then simply keeps flying (monotonically, same speed) instead of vanishing mid-screen; the hit ball itself is unchanged. */
public class CannonBallPathTest {
    @Test public void theVisualBallIsTheLethalBallDuringTheFlightAndKeepsFlyingAfterIt() {
        for (int dir : new int[]{1, -1}) {
            Element c = new Element(Element.Type.CANNON, 20f, 5f, 1f); c.dir = dir; c.len = 9f; c.period = 3.2f; c.phase = 0.4f;
            float prev = Float.NaN; int lethalSteps = 0, flyingOn = 0;
            for (float t = 0f; t < c.period; t += 1f / 60f) {
                float vis = c.cannonBallS(t, 0);
                if (c.lethalAt(t)) { assertEquals("same ball while lethal, t=" + t, c.sAt(t), vis, 1e-3f); lethalSteps++; }
                else { flyingOn++; }
                if (!Float.isNaN(prev) && c.cyc(t) > 0.01f) assertTrue("keeps moving away, t=" + t, dir * (vis - prev) > 0f);
                prev = vis;
            }
            assertTrue(lethalSteps > 0 && flyingOn > 0);
            // an earlier launch continues the same path at the same speed
            float t = 1.0f, step = 0.1f;
            float v0 = (c.cannonBallS(t + step, 1) - c.cannonBallS(t, 1)) / step, v1 = (c.cannonBallS(t + step, 0) - c.cannonBallS(t, 0)) / step;
            assertEquals(v1, v0, 1e-3f);
            assertTrue("earlier launch is further along", dir * (c.cannonBallS(t, 1) - c.cannonBallS(t, 0)) > 0f);
        }
    }
}
