package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/**
 * Jumping while running up a ramp: the slope's surface rises past the feet faster than a late-arc jump rises, and the landing test used to measure the surface where the body had been at the
 * start of the step, so the hero ended up inside the ramp and fell straight through it (found by replaying flailing play on generated towers: every body that sank into a platform from
 * above was on a ramp). A body must stay on or above the visible slope; a body that comes from below (the one-way pass-through) is untouched.
 */
public class RampSlopeTest {
    private static final float AMP = 0.576f, W = 3.3f, MID = 1f;

    private static Sim ramp(int skin) throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = new Course(0, t.circumference());
        Element start = new Element(Element.Type.STATIC, 4f, 0f, 2f); start.checkpoint = true; c.add(start);
        Element r = new Element(Element.Type.RAMP, 10f, MID, W); r.amp = AMP; r.skin = skin; r.len = 1f; c.add(r);
        Sim s = Sim.startOn(c, t, 1);
        s.mode = Sim.Mode.GROUND; s.onElem = 1;
        return s;
    }

    private static float surface(Sim s, int skin) {
        Element r = s.course.get(1); float hw = r.halfW();
        float x = Math.max(-hw, Math.min(hw, s.course.dsWrap(s.s, s.es1[1])));
        return r.y + r.amp * x - (skin == 3 ? s.tilt[1] : 0f);
    }

    @Test public void runningUphillAndJumpingAtAnyMomentNeverSinksIntoTheSlope() throws Exception {
        InputState in = new InputState();
        for (int skin = 0; skin <= 3; skin++) {
            for (float start : new float[]{-1.5f, -1.0f, -0.4f}) {
                for (int jumpAt = 0; jumpAt < 45; jumpAt += 2) {
                    for (float stick : new float[]{1f, 0.6f}) for (int hold : new int[]{1, 4, 14}) {
                        Sim s = ramp(skin);
                        s.s = 10f + start; s.y = MID + AMP * start;
                        String why = "skin " + skin + " start " + start + " jump at " + jumpAt + " stick " + stick + " hold " + hold;
                        for (int i = 0; i < 150; i++) {
                            in.clear(); in.moveX = stick; in.jumpHeld = i >= jumpAt && i < jumpAt + hold; in.jumpPressed = i == jumpAt;
                            s.step(in);
                            float dx = Math.abs(s.course.dsWrap(s.s, s.es1[1]));
                            if (s.gone[1] || dx > s.course.get(1).halfW() + 0.5f) break;               // ran off the far end: the ramp is behind him
                            if (dx <= s.course.get(1).halfW() && s.mode == Sim.Mode.AIR)
                                assertTrue(why + " step " + i + ": feet " + (s.y - surface(s, skin)) + " below the slope (a running body trails the slope by up to 5 cm; a real sink is deeper and keeps going)", s.y >= surface(s, skin) - 0.08f);
                        }
                    }
                }
            }
        }
    }

    @Test public void fallingOntoARampStillLandsOnItsSurface() throws Exception {
        InputState in = new InputState();
        for (float x = -1.5f; x <= 1.5f; x += 0.25f) {
            Sim s = ramp(0);
            s.mode = Sim.Mode.AIR; s.onElem = -1; s.s = 10f + x; s.y = MID + AMP * x + 2.5f; s.vy = 0f; s.vx = 0f;
            int landed = -1;
            for (int i = 0; i < 120 && landed < 0; i++) { in.clear(); s.step(in); if (s.mode == Sim.Mode.GROUND) landed = i; }
            assertTrue("landed at x=" + x, landed >= 0);
            assertEquals("on the slope at x=" + x, surface(s, 0), s.y, 0.01f);
        }
    }

    @Test public void aBodyRisingThroughTheRampFromBelowStillPassesUpThroughIt() throws Exception {
        InputState in = new InputState();
        Sim s = ramp(0);
        s.mode = Sim.Mode.AIR; s.onElem = -1; s.s = 10f; s.y = MID - 3f; s.vy = 22f; s.vx = 0f;
        boolean grounded = false; float topY = -1e9f;
        for (int i = 0; i < 40; i++) { in.clear(); s.step(in); if (s.mode == Sim.Mode.GROUND && s.onElem == 1 && s.y < MID - 0.5f) grounded = true; topY = Math.max(topY, s.y); }
        assertFalse("never lands on the underside", grounded);
        assertTrue("rose well past the slope", topY > MID + 0.5f);
    }
}
