package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/**
 * Owner rule (Build 71): the checkpoint gem is a reward you walk into, never something to jump for. Its lowest visible point, even at the top of its
 * bob and at any roll, never rises above the standing character's face (the measured head bone of the shortest characters, 0.618 m; desktop
 * GemShot re-measures it in the real renderer). Its pickup volume covers the whole drawn gem and the character's normal path across the platform,
 * so walking or running from either side collects it at any bob phase, without a jump and without lining up.
 */
public class CheckpointGemTest {

    @Test public void theGemsBottomNeverRisesAboveTheFaceAtAnyBobOrRoll() {
        for (float b = -1f; b <= 1f; b += 0.01f) for (float roll = -CheckpointGem.ROLL_DEG; roll <= CheckpointGem.ROLL_DEG; roll += 0.5f) {
            float bottom = CheckpointGem.bottomAt(b, roll);
            assertTrue("bottom " + bottom + " at bob " + b + ", roll " + roll + " rises above the face (" + CheckpointGem.BOTTOM_LIMIT + ")", bottom <= CheckpointGem.BOTTOM_LIMIT);
            assertTrue("and never sinks into the deck (" + bottom + ")", bottom >= 0.25f);
        }
        assertEquals("the rule bites at the very top of the bob, with a little margin", CheckpointGem.BOTTOM_LIMIT, CheckpointGem.bottomAt(1f, CheckpointGem.ROLL_DEG), 0.04f);
    }

    @Test public void thePickupVolumeCoversTheWholeDrawnGemAndAStandingBody() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = course(t);
        Element g = c.get(1);
        for (float b = -1f; b <= 1f; b += 0.25f) {
            float bottom = CheckpointGem.bottomAt(b, 0f), top = bottom + 2f * CheckpointGem.HALF_H;
            assertTrue("the volume reaches the gem's top", top <= CheckpointGem.topMax() + 1e-4f);
            assertTrue("a standing body overlaps the drawn gem at every bob (" + bottom + " .. " + top + ")", bottom < t.height && top > 0f);
        }
        for (float dx = -CheckpointGem.HALF_W; dx <= CheckpointGem.HALF_W; dx += 0.05f)
            assertTrue("a body standing over any part of the gem touches it", CheckpointGem.touches(c, g, g.s, g.y, g.s + dx, g.y, t));
        assertTrue("a body on the deck's very edge touches it", CheckpointGem.touches(c, g, g.s, g.y, g.s + g.halfW(), g.y, t));
        assertFalse("well short of the platform does not", CheckpointGem.touches(c, g, g.s, g.y, g.s - g.halfW() - 1f, g.y, t));
        assertFalse("high above it does not", CheckpointGem.touches(c, g, g.s, g.y, g.s, g.y + CheckpointGem.topMax() + 0.1f, t));
    }

    /** Three flush platforms; the middle one carries the gem. */
    private static Course course(Tuning t) {
        Course c = new Course(1, t.circumference()); c.gemCheckpoints = true;
        Element a = new Element(Element.Type.STATIC, 10f, 0f, 8f); a.checkpoint = true; c.add(a);
        Element g = new Element(Element.Type.STATIC, 18f, 0f, 8f); g.checkpoint = true; g.skin = 3; c.add(g);
        Element b = new Element(Element.Type.STATIC, 26f, 0f, 8f); c.add(b);
        return c;
    }

    @Test public void walkingOrRunningFromEitherSideCollectsItAtEveryBobPhaseWithoutAJump() throws Exception {
        Tuning t = TestUtil.tuning();
        int runs = 0;
        for (int dir : new int[]{1, -1}) for (float stick : new float[]{0.35f, 0.6f, 1f}) for (int wait = 0; wait < 180; wait += 9) {      // 180 frames: a whole 3 s bob cycle of start times
            Course c = course(t);
            Sim s = Sim.startOn(c, t, dir > 0 ? 0 : 2); s.setRange(0, c.size() - 1); s.checkpoint = 0;
            InputState in = new InputState(); boolean got = false;
            for (int f = 0; f < wait + 600 && !got; f++) {
                in.clear(); if (f >= wait) in.moveX = dir * stick;          // never a jump: jumpPressed / jumpHeld stay false
                s.step(in);
                if ((s.consumeEvents() & Sim.EV_CHECKPOINT) != 0) got = true;
                assertEquals("walked across without leaving the ground", Sim.Mode.GROUND, s.mode);
            }
            assertTrue("collected walking/running from " + (dir > 0 ? "the left" : "the right") + " at stick " + stick + " after waiting " + wait + " frames", got);
            assertEquals("the gem platform is the checkpoint", 1, s.checkpoint);
            runs++;
        }
        assertEquals(120, runs);
    }

    @Test public void aLowHopAcrossItAlsoCollectsIt() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = course(t);
        Sim s = Sim.startOn(c, t, 0); s.setRange(0, c.size() - 1); s.checkpoint = 0; s.s = 12.5f;
        InputState in = new InputState(); boolean got = false;
        for (int f = 0; f < 120 && !got; f++) { in.clear(); in.moveX = 1f; in.jumpPressed = f == 0; in.jumpHeld = f < 8; s.step(in); got = (s.consumeEvents() & Sim.EV_CHECKPOINT) != 0; }
        assertTrue("a short hop from the edge onto the gem platform collects it", got);
    }
}
