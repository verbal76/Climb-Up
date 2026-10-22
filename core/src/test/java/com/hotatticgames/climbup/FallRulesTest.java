package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/** Falling rules: a hazard knocks you off (no teleport); you fall on to whatever is below; only falling past the lowest platform returns you to the last red gem. */
public class FallRulesTest {
    private static Course world(Tuning t, boolean floorBelow) {
        Course c = new Course(0, t.circumference());
        Element top = new Element(Element.Type.STATIC, 10f, 0f, 4f); top.checkpoint = true; c.add(top);                       // 0: a small platform
        Element gem = new Element(Element.Type.STATIC, 40f, 0f, 6f); gem.checkpoint = true; gem.skin = 3; c.add(gem);        // 1: a red gem platform elsewhere
        c.gemCheckpoints = true;
        if (floorBelow) { Element low = new Element(Element.Type.STATIC, 6f, -8f, 20f); c.add(low); }                       // 2: wide ground below the small platform
        Element spike = new Element(Element.Type.SPIKE_BLOCK, 10f, 0f, 1.2f); spike.len = 1f; spike.anchor = 0; c.hazards.add(spike);
        return c;
    }

    private static Sim hitTheSpike(Course c, Tuning t, int[] stepsToHit) {
        Sim s = Sim.startOn(c, t, 0);
        s.setRange(0, c.size() - 1);
        s.keysFree = true; s.s = c.wrap(9.2f); s.invuln = 0f; s.checkpoint = 1;       // the last red gem touched is element 1
        InputState in = new InputState();
        int i = 0; for (; i < 20 && s.hits == 0; i++) { in.clear(); s.step(in); }
        stepsToHit[0] = i;
        return s;
    }

    @Test public void aHazardKnocksYouBackInsteadOfTeleportingYou() throws Exception {
        Tuning t = TestUtil.tuning(); Course c = world(t, true);
        Sim s = Sim.startOn(c, t, 0); s.setRange(0, c.size() - 1); s.s = c.wrap(9.2f); s.invuln = 0f; s.checkpoint = 1;
        float s0 = s.s; InputState in = new InputState();
        for (int i = 0; i < 20 && s.hits == 0; i++) { in.clear(); s.step(in); }
        assertEquals("the hazard was touched", 1, s.hits);
        assertEquals("a hit is not a fall: no respawn", 0, s.falls);
        assertTrue("knocked into the air", s.mode == Sim.Mode.AIR);
        assertTrue("pushed away from the hazard (it is to the right)", s.vx < -1f && s.vy > 1f);
        assertTrue("no teleport: still within a metre or so of where you were", Math.abs(c.dsWrap(s.s, s0)) < 1.5f && Math.abs(s.y) < 1.5f);
        assertTrue("grace period so a hazard cannot chain-hit you", s.invuln > 0f);
        assertTrue("the hit is reported", (s.consumeEvents() & Sim.EV_HIT) != 0);
    }

    @Test public void youKeepFallingAndLandOnWhatIsBelow() throws Exception {
        Tuning t = TestUtil.tuning(); Course c = world(t, true);
        int[] n = new int[1]; Sim s = hitTheSpike(c, t, n);
        InputState in = new InputState();
        for (int i = 0; i < 60 * 6; i++) { in.clear(); s.step(in); }
        assertEquals("landed on the ground below and carried on", 2, s.onElem);
        assertEquals("no respawn at all", 0, s.falls);
        assertEquals("the checkpoint is untouched", 1, s.checkpoint);
    }

    @Test public void fallingBelowTheLowestPlatformReturnsYouToTheLastRedGem() throws Exception {
        Tuning t = TestUtil.tuning(); Course c = world(t, false);
        int[] n = new int[1]; Sim s = hitTheSpike(c, t, n);
        assertEquals("not teleported by the hit itself", 0, s.falls);
        InputState in = new InputState();
        boolean respawned = false;
        for (int i = 0; i < 60 * 8 && !respawned; i++) { in.clear(); s.step(in); respawned = (s.consumeEvents() & Sim.EV_RESPAWN) != 0; }
        assertTrue("fell past the lowest platform of the map", respawned);
        assertEquals(1, s.falls);
        assertEquals("back on the last red gem", 1, s.onElem);
        assertEquals(0f, s.y, 0.01f);
        assertEquals(c.wrap(40f), s.s, 0.05f);
    }

    @Test public void aHazardTouchNeverSendsYouToTheCheckpointByItself() throws Exception {
        Tuning t = TestUtil.tuning(); Course c = world(t, false);
        Sim s = Sim.startOn(c, t, 0); s.setRange(0, c.size() - 1); s.s = c.wrap(9.2f); s.invuln = 0f; s.checkpoint = 1;
        InputState in = new InputState();
        for (int i = 0; i < 60 * 8; i++) {
            float before = s.s, beforeY = s.y; int falls = s.falls;
            in.clear(); s.step(in);
            if (s.falls != falls) break;                                          // the respawn only ever happens below the map
            assertTrue("every step is continuous motion until the map is left (y=" + s.y + ")", Math.abs(c.dsWrap(s.s, before)) < 1.2f && Math.abs(s.y - beforeY) < 1.5f);
            if (s.hits > 0) assertTrue("while falling you stay above the respawn line", s.y > s.floorY - 6.5f);
        }
    }
}
