package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.render.WorldRenderer;
import org.junit.Test;

/** The cannon is drawn with its barrel axis at the height the ball flies at (the generator puts the launch point 2.35 above the platform), resting on a pedestal that reaches it. */
public class CannonFitTest {
    @Test public void theBarrelRestsOnItsPedestalAtTheBallsHeight() {
        float axis = 2.35f, radius = 0.937f * 0.37f, capTop = WorldRenderer.CANNON_LIFT + 0.16f;
        assertTrue("the cap reaches the barrel's underside", capTop >= axis - radius - 0.01f && capTop <= axis);
        assertTrue("the barrel is not buried", axis - radius > capTop - 0.2f);
    }
}
