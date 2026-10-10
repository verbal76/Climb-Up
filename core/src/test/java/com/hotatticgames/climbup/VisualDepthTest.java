package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.render.WorldRenderer;
import org.junit.Test;

/**
 * Surfaces that lie on one another must keep a safe depth margin, or they fight for the depth buffer and flicker (phones commonly give a 16-bit depth buffer: about 4 mm of resolution at the
 * 11 m the camera sits from the play plane with near 0.5 / far 75, so a margin under ~1.5 cm can flicker at a glancing angle).
 */
public class VisualDepthTest {
    private static final float MIN_MARGIN = 0.015f;

    @Test public void theRetractedSpikeMatClearsThePlatformTopAndThePlayersBlobShadow() {
        float platformTop = 0f, shadowTop = WorldRenderer.SHADOW_LIFT + WorldRenderer.SHADOW_THICK * 0.5f;
        assertTrue("mat top over platform top", WorldRenderer.SPIKE_MAT_TOP - platformTop >= MIN_MARGIN);
        assertTrue("mat top over the blob shadow", WorldRenderer.SPIKE_MAT_TOP - shadowTop >= MIN_MARGIN);
        assertTrue("the mat is buried in the slab at the bottom", WorldRenderer.SPIKE_MAT_BOTTOM < 0f);
        assertTrue("the mat stays a thin mat (hero feet barely sink into it)", WorldRenderer.SPIKE_MAT_TOP <= 0.08f);
    }
}
