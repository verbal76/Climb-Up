package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.Course;
import com.hotatticgames.climbup.sim.Element;
import com.hotatticgames.climbup.sim.InputState;
import com.hotatticgames.climbup.sim.Sim;
import com.hotatticgames.climbup.sim.Tuning;
import org.junit.Test;

/** Build 63 (owner): every cannon ball you can see hits, including the earlier launches flying on past the lane, and the hit disc is the drawn ball. */
public class CannonTrailTest {
    @Test public void aBallFlyingOnPastTheLaneStillHits() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = TestUtil.flat(t);
        Element cn = new Element(Element.Type.CANNON, 6.5f, 2.35f, 0f); cn.len = 5f; cn.dir = 1; cn.period = 3.2f; cn.phase = 0f; cn.anchor = 0; c.hazards.add(cn);
        Sim s = Sim.startOn(c, t, 0); s.keysFree = false; s.invuln = 0f;
        InputState in = new InputState();
        for (int i = 0; i < 60 * 7; i++) { in.clear(); s.step(in); }                   // standing on the deck under the lane: the balls fly over
        assertEquals("the balls fly over a standing player", 0, s.hits);
        float bs = cn.cannonBallS(s.time + Sim.DT, 2);                                    // the ball launched two cycles ago, well past the lane
        assertTrue("it is past the lane", c.dsWrap(bs, cn.s) > cn.len + 1f);
        s.s = c.wrap(bs); s.y = cn.y - 0.5f; s.vy = 0f; s.mode = Sim.Mode.AIR; s.onElem = -1;   // hover in its path
        in.clear(); s.step(in);
        assertEquals("it hits", 1, s.hits);
        assertTrue((s.consumeEvents() & Sim.EV_HIT) != 0);
    }

    @Test public void aBallFizzlesOutPastItsReachAndIsHarmlessThere() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = TestUtil.flat(t);
        Element cn = new Element(Element.Type.CANNON, 6.5f, 2.35f, 0f); cn.len = 5f; cn.dir = 1; cn.period = 3.2f; cn.phase = 0f; cn.anchor = 0; c.hazards.add(cn);
        float reach = cn.cannonReach();
        assertEquals(cn.len + Element.CANNON_BEYOND, reach, 1e-6f);
        // find an earlier launch and a time at which that ball is just past its reach: it must be neither drawn nor lethal
        float t3 = -1f; int back3 = -1;
        for (int back = 1; back <= Element.CANNON_TRAIL && t3 < 0f; back++)
            for (float tt = 0f; tt < 30f && t3 < 0f; tt += Sim.DT) if (cn.cannonFlown(tt, back) > reach + 0.5f && cn.cannonFlown(tt, back) < reach + 1.5f) { t3 = tt; back3 = back; }
        assertTrue(t3 >= 0f);
        assertFalse("not in the air any more", cn.cannonBallAlive(t3, back3));
        Sim s = Sim.startOn(c, t, 0); s.keysFree = false; s.invuln = 0f;
        InputState in = new InputState();
        while (s.time < t3 - Sim.DT * 1.5f) { in.clear(); s.step(in); }
        s.s = c.wrap(cn.cannonBallS(s.time + Sim.DT, back3)); s.y = cn.y - 0.5f; s.vy = 0f; s.mode = Sim.Mode.AIR; s.onElem = -1;
        int hits0 = s.hits; in.clear(); s.step(in);
        assertEquals("a fizzled ball cannot hit", hits0, s.hits);
    }

    @Test public void theHitDiscsMatchTheDrawnBalls() {
        assertEquals(0.42f, Element.SHOT_R, 1e-6f); assertEquals(0.42f, Element.MORTAR_R, 1e-6f); assertEquals(0.40f, Element.AIM_R, 1e-6f);
        float body = 0.60f * 0.55f, tips = 0.83f * 0.55f;                                  // the spiky ball model at the side cannon's drawn scale
        assertTrue("between the ball's body and its spike tips", Element.SHOT_R >= body && Element.SHOT_R <= tips);
        assertEquals("the ball leaves the muzzle, 2.26 model units from the axle at 0.37", 2.26f * 0.37f, Element.AIM_MUZZLE, 0.005f);
    }
}
