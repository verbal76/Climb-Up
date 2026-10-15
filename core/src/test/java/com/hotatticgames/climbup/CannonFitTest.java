package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.render.WorldRenderer;
import org.junit.Test;

/** The cannon is drawn with its barrel axis at the height the ball flies at (the generator puts the launch point 2.35 above the platform), its wheels on a pedestal that reaches it. */
public class CannonFitTest {
    @Test public void theBarrelRestsOnItsPedestalAtTheBallsHeight() {
        // Build 63: the model's axle sits on the 0.16 cap (wheels sunk 0.04 into it) and the barrel's axis runs 1.02 model units above the axle (PK = 0.37)
        float axis = 2.35f, pk = 0.37f, wheel = 0.94f * pk, axisUp = 1.02f * pk, barrelR = 1.0f * pk, capTop = WorldRenderer.CANNON_LIFT + 0.16f;
        float axle = capTop - 0.04f + wheel;
        assertEquals("the barrel's axis is at the height the ball flies at", axis, axle + axisUp, 0.02f);
        assertTrue("the barrel clears the cap", axis - barrelR > capTop - 0.05f);
        assertEquals("the turret's axis sits 2.0 above its slab the same way", 2.0f, WorldRenderer.AIM_LIFT + 0.16f - 0.04f + wheel + axisUp, 0.02f);
    }
}
