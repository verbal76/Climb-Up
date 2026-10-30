package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.nio.file.*;
import org.junit.Test;

/**
 * Rules 19 turbo ramp (owner directive 2026-10-29): running off the top of a turbo ramp (RAMP skin 5) fires the climber out fast and flat; a fresh
 * Jump press in the powered flight cancels it (forward speed cut, gravity and steering back); holding or buffering Jump never cancels; any
 * contact ends the turbo so knocks behave as ever. Deterministic fixtures on the real Sim and planner.
 */
public class TurboRampTest {
    static Tuning T() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }
    static final float ANGLE = 0.3f, SPEED = 13f, L = 3.3f;

    /** Deck 0, the turbo ramp (1) rising from its right edge, and (with {@code landAt} > 0) a landing deck {@code landAt} m past the ramp's top. */
    static Course course(Tuning t, float landAt, float landDy) {
        Course c = new Course(1, t.circumference());
        c.add(new Element(Element.Type.STATIC, 0f, 0f, 4f));
        float slope = CourseGenerator.RAMP_SLOPE, rise = slope * L;
        Element rp = new Element(Element.Type.RAMP, 2f + L / 2f, rise / 2f, L); rp.amp = slope; rp.len = 1.5f; rp.skin = 5; rp.period = ANGLE; rp.phase = SPEED; c.add(rp);
        if (landAt > 0f) c.add(new Element(Element.Type.STATIC, 2f + L + landAt, rise + landDy, 3f));
        return c;
    }
    static InputState run() { InputState i = new InputState(); i.moveX = 1f; return i; }
    static InputState press() { InputState i = new InputState(); i.jumpPressed = true; i.jumpHeld = true; return i; }
    /** Runs right from deck 0 until the booster fires (or 5 s pass); the climber is then in the air. */
    static Sim launched(Course c, Tuning t) {
        Sim m = Sim.startOn(c, t, 0);
        for (int k = 0; k < 300 && m.turboLaunches == 0; k++) m.step(run());
        return m;
    }

    @Test public void runningOffTheTopFiresTheBooster() throws Exception {
        Tuning t = T(); Sim m = launched(course(t, 0f, 0f), t);
        assertEquals(1, m.turboLaunches);
        assertEquals(Sim.Mode.AIR, m.mode);
        assertTrue("powered", m.turboT > 0f);
        assertTrue("fast and forward (" + m.vx + ")", m.vx > SPEED * (float) Math.cos(ANGLE) - 1f);
        assertTrue("shallow (" + m.vy + ")", m.vy > 0f && m.vy < SPEED * (float) Math.sin(ANGLE) + 1f);
    }

    @Test public void thePoweredFlightHoldsItsSpeedAndFallsSlowly() throws Exception {
        Tuning t = T(); Sim m = launched(course(t, 0f, 0f), t);
        float vx0 = m.vx, vy0 = m.vy; int n = (int) (0.3f / Sim.DT);
        for (int k = 0; k < n; k++) m.step(new InputState());          // no input: a normal flight would drag
        assertEquals("no drag while powered", vx0, m.vx, 1e-4f);
        assertEquals("gravity at " + Sim.TURBO_GRAVITY, vy0 - t.gravity * Sim.TURBO_GRAVITY * n * Sim.DT, m.vy, 0.05f);
    }

    @Test public void aFreshPressCancelsTheTurbo() throws Exception {
        Tuning t = T(); Sim m = launched(course(t, 0f, 0f), t);
        for (int k = 0; k < 6; k++) m.step(new InputState());
        float vx = m.vx, y = m.y;
        m.step(press());
        assertEquals(1, m.turboCancels); assertEquals(0f, m.turboT, 0f);
        assertEquals("forward speed cut", vx * Sim.TURBO_CANCEL_KEEP, m.vx, 0.3f);
        assertEquals("no teleport, no snap down", y, m.y, 0.3f);
        assertEquals("the cancel press is not also a rebound press", 0f, m.reboundBuf, 0f);
        assertEquals(Sim.Mode.AIR, m.mode);
    }

    @Test public void holdingJumpThroughTheLaunchNeverCancels() throws Exception {
        Tuning t = T(); Sim m = launched(course(t, 0f, 0f), t);
        InputState held = new InputState(); held.jumpHeld = true;
        for (int k = 0; k < (int) (Sim.TURBO_POWER / Sim.DT) - 2; k++) { m.step(held); assertEquals(0, m.turboCancels); }
    }

    @Test public void aJumpPressedOnTheRampIsAnOrdinaryJumpNotALaunchAndCancel() throws Exception {
        Tuning t = T(); Sim m = Sim.startOn(course(t, 0f, 0f), t, 0);
        for (int k = 0; k < 300 && !(m.mode == Sim.Mode.GROUND && m.onElem == 1); k++) m.step(run());
        assertEquals(1, m.onElem);
        InputState j = run(); j.jumpPressed = true; j.jumpHeld = true;
        m.step(j);
        assertEquals("a normal jump off the ramp", Sim.Mode.AIR, m.mode);
        assertEquals(0, m.turboLaunches); assertEquals(0, m.turboCancels);
    }

    @Test public void aKnockEndsTheTurbo() throws Exception {
        Tuning t = T(); Course c = course(t, 0f, 0f);
        float rise = CourseGenerator.RAMP_SLOPE * L;
        Element saw = new Element(Element.Type.SAW_H, 2f + L + 2.5f, rise + 0.9f, 1f); saw.amp = 0f; saw.period = 4f; saw.anchor = 0; c.hazards.add(saw);
        Sim m = launched(c, t);
        for (int k = 0; k < 60 && m.hits == 0; k++) m.step(new InputState());
        assertTrue("met the saw", m.hits > 0);
        assertEquals("the turbo is over", 0f, m.turboT, 0f);
        assertTrue("thrown back away from it, as any knock (" + m.vx + ")", m.vx < 0f);
    }

    @Test public void landingEndsTheTurbo() throws Exception {
        Tuning t = T(); Sim m = launched(course(t, 4f, 0.2f), t);
        for (int k = 0; k < 120 && m.mode == Sim.Mode.AIR; k++) m.step(new InputState());
        assertEquals(Sim.Mode.GROUND, m.mode); assertEquals(0f, m.turboT, 0f);
    }

    /** Where the full, uncancelled flight comes back down to the ramp-top height. */
    static float fullFlight(Tuning t) {
        Sim m = launched(course(t, 0f, 0f), t);
        float x0 = 2f + L, top = CourseGenerator.RAMP_SLOPE * L;
        InputState hold = run();
        for (int k = 0; k < 600 && !(m.vy < 0f && m.y <= top); k++) m.step(hold);
        return m.s - x0;
    }

    @Test public void thePlannerCancelsToLandShortOfTheFullFlight() throws Exception {
        Tuning t = T(); float full = fullFlight(t);
        Course c = course(t, full * 0.62f, 0f);
        Sim m = Sim.startOn(c, t, 0);
        for (int k = 0; k < 300 && !(m.mode == Sim.Mode.GROUND && m.onElem == 1); k++) m.step(run());
        Autopilot.Result r = Autopilot.plan(m, 1, true, false);
        assertTrue("a cancel lands it (full flight " + full + " m, deck at 62%)", r.ok);
        Autopilot.Policy p = r.factory.create();
        assertTrue(p instanceof Autopilot.TurboPolicy);
        assertTrue("it cancels", ((Autopilot.TurboPolicy) p).cancelAfter >= 0f);
        Sim run = m.copy(); Autopilot.Policy pp = Autopilot.make(r.factory); InputState in = new InputState();
        for (int k = 0; k < 600 && !(run.mode == Sim.Mode.GROUND && run.onElem == 2); k++) { in.clear(); pp.act(run, in); run.step(in); }
        assertEquals(2, run.onElem); assertEquals(1, run.turboCancels);
    }

    @Test public void turboFlightsAreDeterministic() throws Exception {
        Tuning t = T();
        Sim a = launched(course(t, 0f, 0f), t), b = launched(course(t, 0f, 0f), t);
        for (int k = 0; k < 40; k++) { InputState i = k == 10 ? press() : new InputState(); a.step(i); b.step(k == 10 ? press() : new InputState()); }
        assertEquals(a.s, b.s, 0f); assertEquals(a.y, b.y, 0f); assertEquals(a.vx, b.vx, 0f); assertEquals(a.turboCancels, b.turboCancels);
        Sim cp = a.copy(); cp.step(new InputState()); a.step(new InputState());
        assertEquals(a.s, cp.s, 0f); assertEquals(a.vx, cp.vx, 0f); assertEquals(a.turboT, cp.turboT, 0f);
    }
}
