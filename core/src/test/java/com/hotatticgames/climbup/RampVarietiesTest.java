package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/**
 * Build 72 (owner's ramp notes): a ramp meets its platforms smoothly (no trip-hop), crumbling ramps and crumble platforms give way sooner, the shaking ramp
 * throws you about, and the spinning ramp (which replaced the sinking one) turns like a record: its slope flattens and flips and it pushes you sideways.
 */
public class RampVarietiesTest {
    private static final float SC = 1.5f, L = 2.2f * SC, SLOPE = 0.576f, RISE = SLOPE * L;

    /** A deck, a ramp whose foot meets it, and a block flush with the ramp's top: what the generator now builds. */
    private static Course ramp(Tuning t, int skin) {
        Course c = new Course(1, t.circumference());
        Element a = new Element(Element.Type.STATIC, 10f, 0f, 4f); a.checkpoint = true; c.add(a);
        Element rp = new Element(Element.Type.RAMP, 12f + L / 2f, RISE / 2f, L); rp.amp = SLOPE; rp.len = SC; rp.skin = skin; c.add(rp);
        if (skin == 3) { rp.period = 2.6f; rp.phase = 0f; }
        Element b = new Element(Element.Type.STATIC, rp.s + L / 2f + 2f, RISE, 4f); c.add(b);
        return c;
    }
    private static Sim on(Course c, Tuning t, float s) throws Exception { Sim sim = Sim.startOn(c, t, 0); sim.setRange(0, c.size() - 1); sim.s = s; return sim; }

    @Test public void walkingUpARampAndOntoTheBlockNeverLeavesTheGround() throws Exception {
        Tuning t = TestUtil.tuning();
        for (float stick : new float[]{1f, 0.5f}) {
            Course c = ramp(t, 0);
            Sim s = on(c, t, 9f); InputState in = new InputState(); boolean reachedBlock = false;
            for (int f = 0; f < 600 && s.s < 18.5f; f++) {
                in.clear(); in.moveX = stick; s.step(in);
                assertEquals("walked on at stick " + stick + " (s=" + s.s + ")", Sim.Mode.GROUND, s.mode);
                if (s.onElem == 2) reachedBlock = true;
            }
            assertTrue(reachedBlock);
            assertEquals("standing on the block at the ramp's top height", RISE, s.y, 1e-3f);
            for (int f = 0; f < 600 && s.s > 9.5f; f++) {           // and back down onto the deck
                in.clear(); in.moveX = -stick; s.step(in);
                assertEquals("walked down at stick " + stick + " (s=" + s.s + ")", Sim.Mode.GROUND, s.mode);
            }
            assertEquals(0, s.onElem);
        }
    }

    @Test public void theGeneratorMakesRampsFlushWithTheirPlatforms() throws Exception {
        Tuning t = TestUtil.tuning(); int ramps = 0, flushTops = 0;
        for (long seed : new long[]{3, 11}) for (Course c : TestUtil.slices(seed, 1100f)) {
            for (int i = 1; i + 1 < c.routeSize(); i++) {
                Element r = c.get(i); if (r.type != Element.Type.RAMP) continue;
                Element before = c.get(i - 1), after = c.get(i + 1);
                ramps++;
                assertEquals("the ramp's foot starts where the platform before it ends", before.s + before.halfW(), r.s - r.halfW(), 1e-3f);
                assertEquals("and at its height", before.y, r.y - r.amp * r.halfW(), 1e-3f);
                float top = r.y + r.amp * r.halfW();
                if (Math.abs(after.s - after.halfW() - (r.s + r.halfW())) < 1e-3f) { flushTops++; assertEquals("a block that touches the top is level with it", top, after.y, 1e-3f); }
            }
        }
        assertTrue("ramps were generated (" + ramps + ")", ramps >= 5);
        assertTrue("most ramps lead flush onto their block; the rest are ski jumps (" + flushTops + "/" + ramps + ")", flushTops >= ramps / 3);
    }

    @Test public void crumblingRampsAndPlatformsGiveWayAfterHalfASecond() throws Exception {
        Tuning t = TestUtil.tuning();
        assertEquals(0.5f, t.crumbleDelay, 1e-6f);
        Course c = ramp(t, 1);
        Sim s = on(c, t, 13.5f); s.mode = Sim.Mode.GROUND; s.onElem = 1;
        InputState in = new InputState(); int f = 0;
        while (!s.gone[1] && f < 120) { in.clear(); s.step(in); f++; }
        assertTrue("it went", s.gone[1]);
        assertTrue("within half a second of standing on it (" + f + " steps)", f <= 31);
    }

    @Test public void theShakingRampKicksYouUpHighAndSidewaysAlmostAtOnce() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = ramp(t, 2);
        Sim s = on(c, t, 13.5f); s.mode = Sim.Mode.GROUND; s.onElem = 1;
        InputState in = new InputState(); int f = 0, ev = 0;
        while (s.mode == Sim.Mode.GROUND && f < 60) { in.clear(); s.step(in); ev |= s.consumeEvents(); f++; }
        assertEquals("kicked into the air", Sim.Mode.AIR, s.mode);
        assertTrue("within a quarter second (" + f + " steps)", f <= 16);
        assertTrue("a bounce: the flip and the BOING", (ev & Sim.EV_BOUNCE) != 0);
        assertTrue("hard upward (" + s.vy + ")", s.vy >= 9.5f);
        assertTrue("and sideways (" + s.vx + ")", Math.abs(s.vx) >= 3f);
    }

    @Test public void theSpinningRampTurnsItsSlopeAndPushesYouSidewaysBothWays() throws Exception {
        Tuning t = TestUtil.tuning();
        Element rp = ramp(t, 3).get(1);
        assertTrue(rp.spins());
        assertEquals("uphill to the right at the start", SLOPE, rp.rampSlope(0f), 1e-4f);
        assertEquals("flat side-on", 0f, rp.rampSlope(rp.period / 4f), 1e-4f);
        assertEquals("uphill to the left half a turn later", -SLOPE, rp.rampSlope(rp.period / 2f), 1e-4f);

        Course c = ramp(t, 3);
        Sim s = on(c, t, 12f + L / 2f); s.mode = Sim.Mode.GROUND; s.onElem = 1;
        InputState in = new InputState(); float s0 = s.s; boolean right = false, left = false;
        for (int f = 0; f < 600 && s.mode == Sim.Mode.GROUND && s.onElem == 1; f++) {
            float before = s.s; in.clear(); s.step(in);
            if (s.s > before + 1e-4f) right = true; if (s.s < before - 1e-4f) left = true;
        }
        assertTrue("standing still, it carries you sideways (moved " + (s.s - s0) + ")", right || left);
        // the push carries into a jump, one way or the other depending on when you go: the same moment on two spinners half a turn apart
        float[] launch = new float[2];
        for (int k = 0; k < 2; k++) {
            Course jc = ramp(t, 3); jc.get(1).phase = k == 0 ? 0f : (float) Math.PI;
            Sim j = on(jc, t, 12f + L / 2f); j.mode = Sim.Mode.GROUND; j.onElem = 1;
            for (int f = 0; f < (int) (rp.period / 4f * 60f) && j.mode == Sim.Mode.GROUND; f++) { in.clear(); j.step(in); }
            assertEquals("still riding it after a quarter turn", 1, j.onElem);
            in.clear(); in.jumpPressed = true; in.jumpHeld = true; j.step(in);
            launch[k] = j.vx;
        }
        assertTrue("one jump flies right, the other left (" + launch[0] + " / " + launch[1] + ")", launch[0] > 1f && launch[1] < -1f);
    }

    @Test public void theSpinningRampIsAlwaysProvenPassable() throws Exception {
        Tuning t = TestUtil.tuning(); int spinners = 0;
        for (Course c : TestUtil.slices(3, 2600f)) {            // they start in the second world (from 1,000 m)
            int here = 0; for (int i = 0; i < c.routeSize(); i++) if (c.get(i).spins()) here++;
            if (here == 0) continue;
            spinners += here;
            Autopilot.Report r = Autopilot.run(c, t, 4000f);
            assertTrue("a slice with " + here + " spinning ramps is solvable end to end: " + r.failInfo, r.completed);
        }
        assertTrue("spinning ramps appear (" + spinners + ")", spinners >= 1);
    }
}
