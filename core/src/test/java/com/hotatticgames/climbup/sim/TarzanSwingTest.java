package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.nio.file.*;
import org.junit.Test;

/**
 * Rules 19 Tarzan swing (owner directive 2026-10-29): a rope swinging from a pivot. It can only be caught where it is at that moment; the climber
 * rides it; Jump lets go with the rope's own velocity plus a little lift, so the moment of letting go decides where you fly. Deterministic fixtures
 * on the real Sim and the planner.
 */
public class TarzanSwingTest {
    static Tuning T() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }
    static InputState none() { return new InputState(); }
    static InputState jump() { InputState i = new InputState(); i.jumpPressed = true; i.jumpHeld = true; return i; }

    static final float PIVOT_S = 5f, PIVOT_Y = 6f, LEN = 4.5f, AMP = 0.7f, PERIOD = 2.4f;

    /** Start deck, the swing, a landing deck across the gap (route order). */
    static Course course(Tuning t) {
        Course c = new Course(1, t.circumference());
        c.add(new Element(Element.Type.STATIC, 0f, 0f, 4f));
        Element tz = new Element(Element.Type.TARZAN, PIVOT_S, PIVOT_Y, 1f); tz.len = LEN; tz.amp = AMP; tz.period = PERIOD; tz.phase = 0f; c.add(tz);
        c.add(new Element(Element.Type.STATIC, 11.5f, 1f, 3f));
        return c;
    }

    /** The climber in the air with his hands {@code r} down the rope at its angle at time 0 + {@code along} the arc ({@code off} sideways of the rope). */
    static Sim inAirAt(Course c, Tuning t, float th, float r) {
        Sim m = new Sim(c, t); m.mode = Sim.Mode.AIR; m.onElem = -1; m.lockout = 0; m.vx = 0f; m.vy = 0f;
        m.s = PIVOT_S + r * (float) Math.sin(th); m.y = PIVOT_Y - r * (float) Math.cos(th) - t.handHeight;
        return m;
    }

    @Test public void theRopeIsCaughtWhereItIsNow() throws Exception {
        Tuning t = T(); Course c = course(t);
        Element tz = c.get(1);
        float th = tz.tarzanAngle(Sim.DT);          // where the rope is after one step
        Sim m = inAirAt(c, t, th, 3.5f);
        m.step(none());
        assertEquals("caught", Sim.Mode.TARZAN, m.mode);
        assertEquals(1, m.onElem);
        assertEquals(3.5f, m.tarzanR, 0.1f);
    }

    @Test public void whereTheRopeHangsAtRestIsNotEnoughWhileItSwingsAway() throws Exception {
        Tuning t = T(); Course c = course(t);
        Course c2 = course(t); c2.get(1).phase = (float) (Math.PI / 2);          // swung all the way to +s at time 0
        Sim m = inAirAt(c2, t, -AMP, 3.5f);                                          // hands where the rope was a half swing ago
        for (int k = 0; k < 10; k++) { m.step(none()); assertNotEquals("no catch of a rope that is not there", Sim.Mode.TARZAN, m.mode); }
        Sim straight = inAirAt(c2, t, 0f, 3.5f);                                     // straight down (the rest position): the rope is 0.7 rad away
        straight.step(none());
        assertNotEquals(Sim.Mode.TARZAN, straight.mode);
    }

    @Test public void theClimberRidesTheRope() throws Exception {
        Tuning t = T(); Course c = course(t);
        Sim m = inAirAt(c, t, c.get(1).tarzanAngle(Sim.DT), 3.5f); m.step(none());
        assertEquals(Sim.Mode.TARZAN, m.mode);
        float r = m.tarzanR;
        for (int k = 0; k < 150; k++) {
            m.step(none());
            assertEquals(Sim.Mode.TARZAN, m.mode);
            float th = c.get(1).tarzanAngle(m.time);
            assertEquals("on the rope's arc", PIVOT_S + r * (float) Math.sin(th), m.s, 1e-3f);
            assertEquals(PIVOT_Y - r * (float) Math.cos(th) - t.handHeight, m.y, 1e-3f);
        }
    }

    /** Lets go after {@code delay} seconds on the rope; returns the climber 1 s after letting go. */
    static Sim releaseAfter(Course c, Tuning t, float delay) {
        Sim m = inAirAt(c, t, c.get(1).tarzanAngle(Sim.DT), 3.5f); m.step(none());
        assertEquals(Sim.Mode.TARZAN, m.mode);
        int n = Math.round(delay / Sim.DT);
        for (int k = 0; k < n; k++) m.step(none());
        float vx = m.vx, vy = m.vy;
        m.step(jump());
        assertEquals("let go", Sim.Mode.AIR, m.mode);
        assertEquals("keeps the rope's sideways speed", vx, m.vx, Math.abs(vx) * 0.15f + 0.3f);
        assertTrue("and a little lift", m.vy > vy + 0.3f * t.jumpVel);
        return m;
    }

    @Test public void whenYouLetGoDecidesWhereYouFly() throws Exception {
        Tuning t = T(); Course c = course(t);
        // swinging toward +s fastest at the bottom (t = 0 + whole periods), toward -s half a period later
        Sim fwd = releaseAfter(c, t, PERIOD - Sim.DT * 2), back = releaseAfter(c, t, PERIOD * 1.5f - Sim.DT * 2);
        assertTrue("forward release flies forward (" + fwd.vx + ")", fwd.vx > 2f);
        assertTrue("backward release flies back (" + back.vx + ")", back.vx < -2f);
        Sim early = releaseAfter(c, t, PERIOD - 0.4f), late = releaseAfter(c, t, PERIOD + 0.4f);
        assertNotEquals(early.vy, late.vy, 0.5f);          // rising after the bottom, falling before it
        assertTrue("late (rising) release goes up faster", late.vy > early.vy);
    }

    @Test public void downAtTheRopesEndDropsOff() throws Exception {
        Tuning t = T(); Course c = course(t);
        Sim m = inAirAt(c, t, c.get(1).tarzanAngle(Sim.DT), 4.4f); m.step(none());
        assertEquals(Sim.Mode.TARZAN, m.mode);
        InputState dn = new InputState(); dn.moveY = -1f;
        for (int k = 0; k < 30 && m.mode == Sim.Mode.TARZAN; k++) m.step(dn);
        assertEquals(Sim.Mode.AIR, m.mode);
    }

    @Test public void thePlannerFindsAReleaseThatLandsAcrossTheGap() throws Exception {
        Tuning t = T(); Course c = course(t);
        Sim m = inAirAt(c, t, c.get(1).tarzanAngle(Sim.DT), 3.5f); m.step(none());
        assertEquals(Sim.Mode.TARZAN, m.mode);
        Autopilot.Result r = Autopilot.plan(m, 1, true, false);
        assertTrue("a release time works", r.ok);
        assertTrue("but not every one: timing matters (window " + r.window + ")", r.window > 0f && r.window < 0.6f);
        Sim run = m.copy(); Autopilot.Policy p = Autopilot.make(r.factory); InputState in = new InputState();
        for (int k = 0; k < 600 && !(run.mode == Sim.Mode.GROUND && run.onElem == 2); k++) { in.clear(); p.act(run, in); run.step(in); }
        assertEquals("landed on the far deck", Sim.Mode.GROUND, run.mode);
        assertEquals(2, run.onElem);
    }

    @Test public void swingsAreDeterministicAndCopiesContinueExactly() throws Exception {
        Tuning t = T(); Course c = course(t);
        Sim a = inAirAt(c, t, c.get(1).tarzanAngle(Sim.DT), 3.5f), b = inAirAt(c, t, c.get(1).tarzanAngle(Sim.DT), 3.5f);
        for (int k = 0; k < 70; k++) { a.step(none()); b.step(none()); }
        Sim cp = a.copy();
        for (Sim m : new Sim[]{a, b, cp}) { m.step(jump()); for (int k = 0; k < 90; k++) m.step(none()); }
        for (Sim m : new Sim[]{b, cp}) { assertEquals(a.s, m.s, 0f); assertEquals(a.y, m.y, 0f); assertEquals(a.vx, m.vx, 0f); assertEquals(a.vy, m.vy, 0f); assertEquals(a.mode, m.mode); }
    }
}
