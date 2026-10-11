package com.hotatticgames.climbup.render;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * The rope-climb and cable-shimmy cycle must advance by how far the hero actually travels, by the same
 * amount whether the screen runs at 60, 144 or 300 fps. The old code measured the delta against the
 * player's position at the last 60 Hz sim step and added it every render frame, so on a high-refresh
 * display the inchworm cycle re-added the same movement several times per sim step and ran
 * ~refresh/60 times too fast (the "5x twitch" the owner saw on a 144 Hz laptop). HeroRig.frameAdvance
 * now measures travel per render frame instead; this pins that it is frame-rate independent.
 */
public class ClimbPhaseTest {

    /** Reproduces HeroRig.update's accumulation: each frame, add the clamped travel since the previous frame. */
    private static float phaseOverFrames(int frames, float totalTravel) {
        float phase = 0f, ref = 0f;
        for (int i = 1; i <= frames; i++) {
            float pos = totalTravel * i / frames;
            phase += HeroRig.frameAdvance(pos - ref) * 2.2f;
            ref = pos;
        }
        return phase;
    }

    @Test public void theClimbCycleAdvancesByTravelNotByFrameCount() {
        float at60 = phaseOverFrames(60, 3.0f);     // climb 3 m, sampled 60 times/s
        float at144 = phaseOverFrames(144, 3.0f);   // the same 3 m on a 144 Hz display
        float at300 = phaseOverFrames(300, 3.0f);   // the same 3 m uncapped on a fast GPU
        assertTrue("the cycle actually advances while climbing", at60 > 0.1f);
        assertEquals("60 vs 144 fps: identical", at60, at144, 1e-4f);
        assertEquals("60 vs 300 fps: identical (no high-refresh speed-up)", at60, at300, 1e-4f);
    }

    @Test public void travelIsCountedRegardlessOfDirection() {
        assertEquals("climbing up is counted", 0.05f, HeroRig.frameAdvance(0.05f), 1e-6f);
        assertEquals("sliding down is counted the same", 0.05f, HeroRig.frameAdvance(-0.05f), 1e-6f);
    }

    @Test public void aFloatingOriginRebaseOrTeleportNeverLurchesTheCycle() {
        assertEquals("a huge jump (origin rebase) adds nothing", 0f, HeroRig.frameAdvance(742.0f), 0f);
        assertEquals("a huge negative jump adds nothing", 0f, HeroRig.frameAdvance(-512.0f), 0f);
        assertEquals("a fast but real climb frame is still counted", 0.9f, HeroRig.frameAdvance(0.9f), 1e-6f);
    }
}
