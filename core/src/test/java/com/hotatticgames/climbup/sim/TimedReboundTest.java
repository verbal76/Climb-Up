package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.nio.file.*;
import org.junit.Test;

/**
 * Rules 19 timed rebound jump (owner directive 2026-10-29): a fresh Jump press up to {@link Sim#REBOUND_WINDOW} before touching a crab, a bee or a
 * cannon ball (or a saw) adds a jump impulse to that contact's normal outcome. Without the press, or outside the window, the outcome is exactly as
 * before. Deterministic fixtures on the real Sim.
 */
public class TimedReboundTest {
    static Tuning T() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }
    static InputState in(boolean press, boolean held) { InputState i = new InputState(); i.jumpPressed = press; i.jumpHeld = held; return i; }

    /** A crab standing on a deck, and the climber falling onto it from above ({@code ds} along the arc from its middle). */
    static Sim fallOntoCrab(Tuning t, float ds) {
        Course c = new Course(1, t.circumference()); c.add(new Element(Element.Type.STATIC, 0, 0, 8));
        Element cr = new Element(Element.Type.CRAB, 0f, 0, 1); cr.anchor = 0; cr.amp = 0; cr.period = 4; c.hazards.add(cr);
        Sim m = new Sim(c, t); m.mode = Sim.Mode.AIR; m.onElem = -1; m.s = ds; m.y = 2.4f; m.vx = 0f; m.vy = -2f; m.lockout = 0;
        return m;
    }
    /** Steps until the first shove or hit; presses Jump on step {@code pressAt} (-1: never), holding it from {@code holdFrom} (-1: never). Returns the contact step. */
    static int runToContact(Sim m, int pressAt, int holdFrom) {
        for (int k = 0; k < 240; k++) {
            boolean held = holdFrom >= 0 && k >= holdFrom;
            m.step(in(k == pressAt || (holdFrom >= 0 && k == holdFrom), held || k == pressAt));
            if ((m.consumeEvents() & (Sim.EV_SHOVE | Sim.EV_HIT)) != 0) return k;
        }
        return -1;
    }

    @Test public void withoutAPressACrabShovesExactlyAsBefore() throws Exception {
        Tuning t = T();
        Sim a = fallOntoCrab(t, 0.3f); int ka = runToContact(a, -1, -1);
        assertTrue("touched the crab", ka > 0);
        assertEquals("the ordinary shove", 9f, Math.abs(a.vx), 1e-4f);
        assertEquals(6f, a.vy, 1e-4f);
        assertEquals(0, a.rebounds);
    }

    @Test public void aPressJustBeforeTouchingTheCrabRebounds() throws Exception {
        Tuning t = T();
        Sim base = fallOntoCrab(t, 0.3f); int k = runToContact(base, -1, -1);
        Sim r = fallOntoCrab(t, 0.3f); int kr = runToContact(r, k - 3, -1);          // 50 ms before contact
        assertEquals("same contact step", k, kr);
        assertEquals(1, r.rebounds);
        assertTrue("an upward rebound on top of the shove (" + r.vy + ")", r.vy > base.vy + 0.5f * t.jumpVel);
        assertTrue("from above, less of the shove's sideways push", Math.abs(r.vx) < Math.abs(base.vx));
    }

    @Test public void aPressOutsideTheWindowChangesNothing() throws Exception {
        Tuning t = T();
        Sim base = fallOntoCrab(t, 0.3f); int k = runToContact(base, -1, -1);
        Sim late = fallOntoCrab(t, 0.3f); runToContact(late, k - 8, -1);          // 133 ms before contact: outside the 100 ms window
        assertEquals(0, late.rebounds);
        assertEquals(base.vx, late.vx, 1e-6f); assertEquals(base.vy, late.vy, 1e-6f);
    }

    @Test public void holdingJumpDoesNotRebound() throws Exception {
        Tuning t = T();
        Sim base = fallOntoCrab(t, 0.3f); int k = runToContact(base, -1, -1);
        Sim held = fallOntoCrab(t, 0.3f); runToContact(held, -1, Math.max(0, k - 20));          // pressed 333 ms before and held through the contact
        assertEquals(0, held.rebounds);
        assertEquals(base.vy, held.vy, 1e-6f);
    }

    @Test public void aPressInEmptyAirIsNoDoubleJump() throws Exception {
        Tuning t = T();
        Course c = new Course(1, t.circumference()); c.add(new Element(Element.Type.STATIC, 0, -20, 4));
        Sim a = new Sim(c, t), b = new Sim(c, t);
        for (Sim m : new Sim[]{a, b}) { m.mode = Sim.Mode.AIR; m.onElem = -1; m.s = 0; m.y = 5f; m.vx = 2f; m.vy = 3f; m.lockout = 0; }
        for (int k = 0; k < 40; k++) { a.step(in(false, false)); b.step(in(k == 5, k == 5)); }
        assertEquals("no impulse without a contact", a.vy, b.vy, 1e-6f); assertEquals(a.y, b.y, 1e-6f);
        assertEquals(0, b.rebounds);
    }

    @Test public void aCannonBallReboundsAndStillCountsAsAHit() throws Exception {
        Tuning t = T();
        Course c = new Course(1, t.circumference()); c.add(new Element(Element.Type.STATIC, -30, -30, 4));
        Element cn = new Element(Element.Type.CANNON, -6f, 1f, 1f); cn.dir = 1; cn.len = 12f; cn.period = 2f;
        float cyc0 = 4.6f * Element.CANNON_FLIGHT / cn.len; cn.phase = cyc0 * 2f * (float) Math.PI;          // a ball 4.6 m out of the muzzle at t = 0: 1.4 m short of the climber
        c.hazards.add(cn);
        Sim base = new Sim(c, t); base.mode = Sim.Mode.AIR; base.onElem = -1; base.s = 0f; base.y = 0.6f; base.vx = 0f; base.vy = 2f; base.lockout = 0;
        int k0 = runToContact(base, -1, -1);
        assertTrue("the ball reached the climber", k0 > 2);
        Sim r = new Sim(c, t); r.mode = Sim.Mode.AIR; r.onElem = -1; r.s = 0f; r.y = 0.6f; r.vx = 0f; r.vy = 2f; r.lockout = 0;
        int kr = runToContact(r, k0 - 2, -1);
        assertEquals(k0, kr);
        assertEquals("still a hit (pending the owner's decision)", base.hits, r.hits);
        assertEquals(1, r.rebounds);
        assertTrue("rebounded higher than the knock-back (" + r.vy + " vs " + base.vy + ")", r.vy > base.vy + 0.4f * t.jumpVel);
    }

    @Test public void reboundsAreDeterministic() throws Exception {
        Tuning t = T();
        Sim a = fallOntoCrab(t, 0.6f), b = fallOntoCrab(t, 0.6f);
        int k = runToContact(fallOntoCrab(t, 0.6f), -1, -1);
        runToContact(a, k - 4, -1); runToContact(b, k - 4, -1);
        for (int i = 0; i < 90; i++) { a.step(in(false, false)); b.step(in(false, false)); }
        assertEquals(a.s, b.s, 0f); assertEquals(a.y, b.y, 0f); assertEquals(a.vx, b.vx, 0f); assertEquals(a.vy, b.vy, 0f);
    }
}
