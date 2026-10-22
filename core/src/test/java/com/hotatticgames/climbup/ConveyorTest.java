package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Build 72 (owner): a conveyor ramp whose belt runs downhill (faster the harder the climb), and long wooden bridges that are plank conveyor belts running to the
 * left. Standing still carries you back; running beats the belt; the generator makes both and proves every course with them passable.
 */
public class ConveyorTest {
    private static final float SC = 1.5f, L = 2.2f * SC, SLOPE = 0.576f, RISE = SLOPE * L;

    private static Course ramp(float belt) throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = new Course(1, t.circumference());
        Element a = new Element(Element.Type.STATIC, 10f, 0f, 4f); a.checkpoint = true; c.add(a);
        Element rp = new Element(Element.Type.RAMP, 12f + L / 2f, RISE / 2f, L); rp.amp = SLOPE; rp.len = SC; rp.skin = 4; rp.phase = belt; c.add(rp);
        c.add(new Element(Element.Type.STATIC, rp.s + L / 2f + 2f, RISE, 4f));
        return c;
    }

    private static Course bridge(float belt) throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = new Course(1, t.circumference());
        Element a = new Element(Element.Type.STATIC, 10f, 0f, 4f); a.checkpoint = true; c.add(a);
        Element br = new Element(Element.Type.STATIC, 12f + 2f + 4f, 0f, 8f); br.skin = 4; br.phase = belt; c.add(br);       // a 2 m gap, then an 8 m plank belt
        c.add(new Element(Element.Type.STATIC, br.s + 4f + 2f + 2f, 0.3f, 4f));
        return c;
    }

    private static Sim on(Course c, int elem, float s) throws Exception {
        Sim sim = Sim.startOn(c, TestUtil.tuning(), 0); sim.setRange(0, c.size() - 1);
        sim.s = s; sim.y = c.get(elem).type == Element.Type.RAMP ? c.get(elem).y + c.get(elem).amp * (s - c.get(elem).s) : c.get(elem).y;
        sim.mode = Sim.Mode.GROUND; sim.onElem = elem; sim.vx = 0; sim.vy = 0;
        return sim;
    }

    @Test public void standingOnTheConveyorRampCarriesYouBackDownToItsFoot() throws Exception {
        Course c = ramp(2.5f);
        Sim s = on(c, 1, c.get(1).s + 1f);
        InputState in = new InputState(); float s0 = s.s;
        for (int f = 0; f < 240 && s.onElem == 1; f++) { in.clear(); s.step(in); }
        assertTrue("carried downhill (" + (s.s - s0) + " m)", s.s < s0 - 1f);
        assertEquals("off the foot and onto the deck below, on the ground", 0, s.onElem);
        assertEquals(Sim.Mode.GROUND, s.mode);
    }

    @Test public void runningBeatsTheBeltAndReachesTheBlockAtTheTop() throws Exception {
        for (float belt : new float[]{1.3f, 3.4f}) {           // the slowest and the fastest belt the generator makes
            Course c = ramp(belt);
            Sim s = on(c, 0, 10f); InputState in = new InputState(); int f = 0;
            for (; f < 600 && s.onElem != 2; f++) { in.clear(); in.moveX = 1f; s.step(in); }
            assertEquals("belt " + belt + ": ran up onto the block", 2, s.onElem);
            assertEquals(Sim.Mode.GROUND, s.mode);
        }
        // and the belt really slows the climb: the fast belt takes longer than the slow one
        float[] took = new float[2]; int k = 0;
        for (float belt : new float[]{1.3f, 3.4f}) {
            Course c = ramp(belt); Sim s = on(c, 0, 10f); InputState in = new InputState(); int f = 0;
            for (; f < 600 && s.onElem != 2; f++) { in.clear(); in.moveX = 1f; s.step(in); }
            took[k++] = f;
        }
        assertTrue("the faster belt is harder (" + took[0] + " vs " + took[1] + " steps)", took[1] > took[0] + 5);
    }

    @Test public void theConveyorBridgeRollsYouLeftOffItsEndIfYouStandStill() throws Exception {
        Course c = bridge(1.8f);
        Sim s = on(c, 1, c.get(1).s); InputState in = new InputState(); float s0 = s.s; boolean fell = false;
        for (int f = 0; f < 600 && !fell; f++) { in.clear(); s.step(in); if (s.mode == Sim.Mode.AIR && s.vy < -1f) fell = true; }
        assertTrue("carried left (" + (s.s - s0) + " m)", s.s < s0 - 3.5f);
        assertTrue("and off the left end into the gap", fell);
    }

    @Test public void runningAcrossTheConveyorBridgeGetsYouToTheFarSide() throws Exception {
        Course c = bridge(2.6f);
        Sim s = on(c, 1, c.get(1).s - 3.5f); InputState in = new InputState(); int f = 0;
        for (; f < 600 && s.onElem != 2 && s.mode != Sim.Mode.AIR; f++) { in.clear(); in.moveX = 1f; s.step(in); }
        assertTrue("reached the far end while running (s=" + s.s + ")", s.s > c.get(1).s + 3.5f);
        assertTrue("jumping off the belt still clears the gap", jumpAcross(c));
    }

    private static boolean jumpAcross(Course c) throws Exception {
        Sim s = on(c, 1, c.get(1).s - 3.5f); InputState in = new InputState(); boolean jumped = false;
        for (int f = 0; f < 600; f++) {
            in.clear(); in.moveX = 1f;
            if (!jumped && s.mode == Sim.Mode.GROUND && s.s > c.get(1).s + 3.6f) { in.jumpPressed = true; jumped = true; }
            in.jumpHeld = jumped && f < 400;
            s.step(in);
            if (s.mode == Sim.Mode.GROUND && s.onElem == 2) return true;
        }
        return false;
    }

    @Test public void theGeneratorMakesBothConveyorsFasterHigherUpAndProvesThemPassable() throws Exception {
        Tuning t = TestUtil.tuning(); int ramps = 0, bridges = 0;
        List<float[]> rampSpeeds = new ArrayList<>();
        for (Course c : TestUtil.slices(3, 2600f)) {
            int here = 0;
            for (int i = 0; i < c.routeSize(); i++) {
                Element e = c.get(i);
                if (!e.belt()) continue;
                here++;
                assertTrue("a belt has a speed", e.phase > 0.5f && e.phase < 4f);
                if (e.type == Element.Type.RAMP) { ramps++; rampSpeeds.add(new float[]{e.y, e.phase}); } else bridges++;
            }
            if (here == 0) continue;
            Autopilot.Report r = Autopilot.run(c, t, 4000f);
            assertTrue("a slice with " + here + " conveyors is solvable end to end: " + r.failInfo, r.completed);
        }
        assertTrue("conveyor ramps appear (" + ramps + ")", ramps >= 1);
        assertTrue("conveyor bridges appear (" + bridges + ")", bridges >= 1);
        if (rampSpeeds.size() >= 2) {
            float[] lo = rampSpeeds.get(0), hi = rampSpeeds.get(rampSpeeds.size() - 1);
            assertTrue("a higher conveyor ramp runs at least as fast (" + lo[1] + " at " + lo[0] + " m, " + hi[1] + " at " + hi[0] + " m)", hi[1] >= lo[1] * 0.9f);
        }
    }
}
