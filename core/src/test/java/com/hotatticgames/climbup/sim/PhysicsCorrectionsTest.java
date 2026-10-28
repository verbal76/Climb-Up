package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.nio.file.*;
import org.junit.Test;

/**
 * Owner correction directive 2026-10-27, DC4 and DC8 (docs/verification/CONSOLIDATED_AUDIT_REPORT.md): the movement defects the audit confirmed by
 * probe, each replayed on the real simulation. The probes are the verifier's own (docs/verification/evidence/verified_A_player_movement.md, P1-P12).
 */
public class PhysicsCorrectionsTest {
    static Tuning T() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }
    static Element el(Element.Type ty, float s, float y, float w) { return new Element(ty, s, y, w); }
    static InputState in(float mx, float my, boolean press, boolean held) { InputState i = new InputState(); i.moveX = mx; i.moveY = my; i.jumpPressed = press; i.jumpHeld = held; return i; }

    /** DC4.1 (P8): walking off a deck onto an adjacent lower pad and tapping jump at once keeps the full bounce. */
    @Test public void aJumpTapRightAfterAPadBounceKeepsTheBounce() throws Exception {
        Tuning t = T();
        float[] apex = new float[2];
        for (int press = 0; press <= 1; press++) {
            Course c = new Course(1, t.circumference()); c.add(el(Element.Type.STATIC, 0, 0.1f, 4)); c.add(el(Element.Type.PAD, 3.2f, 0f, 2));
            Sim m = new Sim(c, t); int k = 0;
            while (k++ < 200) { m.step(in(1, 0, false, false)); if ((m.consumeEvents() & Sim.EV_BOUNCE) != 0) break; }
            assertEquals("bounced", Sim.Mode.AIR, m.mode);
            m.step(in(1, 0, press == 1, false));
            float top = m.y; for (int j = 0; j < 160 && m.vy > 0; j++) { m.step(in(1, 0, false, false)); top = Math.max(top, m.y); }
            apex[press] = top;
        }
        assertTrue("bounce apex without a tap " + apex[0], apex[0] > 6f);
        assertEquals("a tap right after the bounce does not replace it (was 0.44 m)", apex[0], apex[1], 0.05f);
    }

    /** DC4.2 (P1): letting go at the bottom of a rope entered from its top beam gives no mid-air jump. */
    @Test public void noStaleCoyoteJumpAfterBeamToRope() throws Exception {
        Tuning t = T();
        Course c = new Course(1, t.circumference()); c.add(el(Element.Type.STATIC, 0, 0, 4)); Element r = el(Element.Type.ROPE, 20, 10, 0); r.len = 4; c.add(r);
        Sim m = Sim.startOn(c, t, 1);
        int k = 0; while (!(m.mode == Sim.Mode.BEAM && !m.mounting()) && k++ < 600) m.step(in(0, 1, false, false));
        assertEquals(Sim.Mode.BEAM, m.mode);
        m.step(in(0, -1, false, false)); assertEquals(Sim.Mode.ROPE, m.mode);
        k = 0; while (m.mode == Sim.Mode.ROPE && k++ < 600) m.step(in(0, -1, false, false));
        m.step(in(0, 0, false, false));
        m.step(in(0, 0, true, true));
        m.consumeEvents();
        assertTrue("no jump in mid-air after the rope (vy " + m.vy + ")", m.vy < 0f);
    }

    /** DC4.2 (P2): a crab's shove leaves no coyote: a jump pressed the next step is no ground jump. */
    @Test public void aShoveLeavesNoCoyoteJump() throws Exception {
        Tuning t = T();
        Course c = new Course(1, t.circumference()); c.add(el(Element.Type.STATIC, 0, 0, 8));
        Element cr = el(Element.Type.CRAB, 2.5f, 0, 1); cr.anchor = 0; cr.amp = 0; cr.period = 4; c.hazards.add(cr);
        Sim m = new Sim(c, t);
        int k = 0; while (m.mode == Sim.Mode.GROUND && k++ < 600) m.step(in(1, 0, false, false));
        assertEquals("shoved", Sim.Mode.AIR, m.mode);
        m.step(in(1, 0, true, true));
        assertTrue("the shove's own rise, not an 11.5 m/s jump (vy " + m.vy + ")", m.vy < 7f);
    }

    /** DC4.3 (P3): a jump pressed just before catching a rope does not leap off it the next step. */
    @Test public void aBufferedPressDoesNotLeapOffAFreshRopeCatch() throws Exception {
        Tuning t = T();
        Course c = new Course(1, t.circumference()); c.add(el(Element.Type.STATIC, -20, 0, 4)); Element r = el(Element.Type.ROPE, 3, 5, 0); r.len = 4; c.add(r);
        Sim m = new Sim(c, t); m.mode = Sim.Mode.AIR; m.onElem = -1; m.s = 3 - 0.75f; m.y = 1.5f; m.vx = 4f; m.vy = 0.5f; m.lockout = 0;
        m.step(in(1, 0, true, true));
        for (int k = 0; k < 6 && m.mode == Sim.Mode.AIR; k++) m.step(in(1, 0, false, true));
        assertEquals("caught the rope", Sim.Mode.ROPE, m.mode);
        m.step(in(1, 0, false, true));
        assertEquals("still on it a step later", Sim.Mode.ROPE, m.mode);
    }

    /** DC4.4 (P12): a saw beside a rope knocks the climber off for real: no re-grab the next frame, no hit loop while hanging. */
    @Test public void aKnockOffFromARopeIsAKnockOff() throws Exception {
        Tuning t = T();
        for (float off : new float[]{0.5f, 0.75f}) {
            Course c = new Course(1, t.circumference()); c.add(el(Element.Type.STATIC, -20, -30, 4));
            Element r = el(Element.Type.ROPE, 10, 10, 0); r.len = 6; c.add(r);
            Element saw = el(Element.Type.SAW_V, 10 + off, 6.6f, 1); saw.anchor = 1; saw.amp = 0; saw.period = 4; c.hazards.add(saw);
            Sim m = Sim.startOn(c, t, 1); m.y = 6.0f; m.invuln = 0; m.lockout = 0f;
            int regrabs = 0;
            for (int k = 0; k < 300; k++) { m.step(new InputState()); if ((m.consumeEvents() & Sim.EV_ROPE) != 0) regrabs++; }
            assertEquals("offset " + off + ": hit once", 1, m.hits);
            assertEquals("offset " + off + ": never back on the rope", 0, regrabs);
            assertNotEquals(Sim.Mode.ROPE, m.mode);
        }
    }

    /** DC4.5 (P7): knocked away from where the hazard touched you, also when it rides a moving platform. */
    @Test public void theKnockOffPushesAwayFromTheContact() throws Exception {
        Tuning t = T();
        float tHit = (float) (Math.asin(2.0 / 3.0) / (2 * Math.PI / 4));
        for (float ps : new float[]{1.5f, 2.5f}) for (int carried = 0; carried <= 1; carried++) {
            Course c = new Course(1, t.circumference());
            Element mv = el(carried == 1 ? Element.Type.MOVE_H : Element.Type.STATIC, 0, -3, 6); mv.amp = 3; mv.period = 4; c.add(mv);
            Element saw = el(Element.Type.SAW_H, carried == 1 ? 0 : 2, 0.8f, 1); saw.anchor = 0; saw.amp = 0; saw.period = 4; c.hazards.add(saw);
            Sim m = new Sim(c, t); m.time = tHit - Sim.DT; m.syncElements();
            m.mode = Sim.Mode.AIR; m.onElem = -1; m.s = ps; m.y = 0.3f; m.vx = 0; m.vy = 0; m.invuln = 0;
            m.step(in(0, 0, false, false));
            float actual = carried == 1 ? 3f * (float) Math.sin(2 * Math.PI * m.time / 4) : 2f;
            assertEquals("carried " + carried + " player " + ps + ": hit", 1, m.hits);
            assertEquals("carried " + carried + " player " + ps + ": pushed away from the blade at " + actual, Math.signum(ps - actual), Math.signum(m.vx), 0f);
        }
    }

    /** DC4.6: an Enforcer swat does not reach a climber hanging on a rope (a rope is out of every creature's reach); it does reach them in the air. */
    @Test public void anEnforcerSwatDoesNotReachARope() throws Exception {
        Tuning t = T().hardCopy(1);
        int swatHitsAir = 0, swatHitsRope = 0, scenarios = 0;
        for (float ph : new float[]{0.11f, 0.37f, 0.63f, 0.89f}) for (int tier = 1; tier <= 2; tier++) {
            Course c = new Course(0, t.circumference());
            Element d0 = el(Element.Type.STATIC, 10f, 0f, 6f); d0.checkpoint = true; c.add(d0); c.add(el(Element.Type.STATIC, 17f, 0f, 6f));
            Element rope = el(Element.Type.ROPE, 300f, 40f, 0f); rope.len = 8f; c.add(rope);          // far away until the swat starts
            Element r = el(Element.Type.ENFORCER, 10f, 0f, 6f); r.anchor = 0; r.len = 2; r.skin = tier; r.phase = ph; c.hazards.add(r);
            int hi = c.hazards.indexOf(r);
            Sim s = Sim.startOn(c, t, 0); InputState in = new InputState(); s.step(in); s.enforcerChaseAt(hi, 0, 12.0f);
            s.s = c.wrap(13.4f); s.y = 1.2f; s.vx = 5.6f; s.vy = 5f; s.mode = Sim.Mode.AIR; s.onElem = -1;
            Sim onRope = null;
            for (int i = 0; i < 180 && onRope == null; i++) { in.clear(); in.moveX = 1f; s.step(in); if (s.enforcerState(hi) == 4) onRope = s.copy(); }
            if (onRope == null) continue;
            scenarios++;
            rope.s = onRope.s; rope.y = onRope.y + t.handHeight + 2f;      // the same body, hanging on a rope right where it is
            onRope.syncElements(); s.syncElements(); onRope.mode = Sim.Mode.ROPE; onRope.onElem = 2; onRope.vx = onRope.vy = 0;
            // the same body held in the air where the swat aims (each step put back there), against the same body hanging on the rope there
            float hs = onRope.s, hy = onRope.y;
            int h0 = s.enfSwatHits, h1 = onRope.enfSwatHits;
            for (int i = 0; i < 120; i++) { s.s = hs; s.y = hy; s.vx = s.vy = 0; s.mode = Sim.Mode.AIR; s.onElem = -1; s.lockout = 1f; /* (the course is shared: no grabbing the rope from the air) */ in.clear(); s.step(in); in.clear(); onRope.step(in); }
            swatHitsAir += s.enfSwatHits - h0; swatHitsRope += onRope.enfSwatHits - h1;
            rope.s = 300f; rope.y = 40f;
        }
        assertTrue("some swats started", scenarios > 0);
        assertTrue("the swat reaches a climber in the air (sanity): " + swatHitsAir, swatHitsAir > 0);
        assertEquals("never one on a rope", 0, swatHitsRope);
    }

    /** DC8 (P11): a pull-up onto a depth mover that leaves the plane lets go instead of standing on it out of plane. */
    @Test public void aPullUpNeverEndsOnAnOutOfPlaneMover() throws Exception {
        Tuning t = T();
        Course c = new Course(1, t.circumference()); c.add(el(Element.Type.STATIC, -20, -5, 4));
        Element z = el(Element.Type.MOVE_Z, 5, 2, 2); z.amp = 3; z.period = 4; z.phase = 0; c.add(z);
        c.add(el(Element.Type.STATIC, 5, -6, 14));
        Sim m = new Sim(c, t); m.mode = Sim.Mode.AIR; m.onElem = -1; m.s = 3.8f; m.y = 0.5f; m.vx = 1f; m.vy = 0f; m.lockout = 0;
        m.step(in(1, 0, false, false));
        assertTrue("caught it", m.mode == Sim.Mode.LEDGE || m.mode == Sim.Mode.PULLUP);
        for (int k = 0; k < 90; k++) {
            m.step(in(0, 1, false, false));
            if (m.mode == Sim.Mode.GROUND && m.onElem == 1) assertTrue("standing on the mover only in plane (z " + z.zAt(m.time) + ")", Math.abs(z.zAt(m.time)) <= Element.Z_REACH + 0.2f);
        }
    }

    /** DC8: a pull-up onto a deck that crumbles mid-pull lets go (it used to complete onto nothing). */
    @Test public void aPullUpOntoAVanishingDeckLetsGo() throws Exception {
        Tuning t = T();
        Course c = new Course(1, t.circumference()); c.add(el(Element.Type.STATIC, -20, -5, 4));
        c.add(el(Element.Type.CRUMBLE, 5, 2, 2)); c.add(el(Element.Type.STATIC, 5, -6, 14));
        Sim m = new Sim(c, t); m.mode = Sim.Mode.AIR; m.onElem = -1; m.s = 3.8f; m.y = 0.5f; m.vx = 1f; m.vy = 0f; m.lockout = 0;
        m.crumbleT[1] = 0.45f;          // already crumbling (someone stood on it a moment ago)
        m.step(in(1, 0, false, false));
        for (int k = 0; k < 40; k++) { m.step(in(0, 1, false, false)); assertFalse("never standing on the crumbled deck", m.mode == Sim.Mode.GROUND && m.onElem == 1 && m.gone[1]); }
    }

    /** DC8 (P5): walking straight on onto a holed bridge never steps onto an open hole. */
    @Test public void noStepAcrossOntoAnOpenHole() throws Exception {
        Tuning t = T();
        Course c = new Course(1, t.circumference()); c.add(el(Element.Type.STATIC, 0, 0, 4));
        Element b = el(Element.Type.STATIC, 4.65f, 0.1f, 6.5f); b.skin = 1; b.color = 1; c.add(b);
        c.add(el(Element.Type.STATIC, 4.65f, -6f, 12f));
        Sim m = new Sim(c, t); m.crumbleT[1] = 0.5f;
        assertTrue(m.holesOpen(1));
        for (int k = 0; k < 120; k++) {
            m.step(in(1, 0, false, false));
            if (m.mode == Sim.Mode.GROUND && m.onElem == 1) assertFalse("stood over an open hole at " + m.s, b.overHole(c.dsWrap(m.s, m.es1[1]), Sim.HOLE_FOOT));
        }
    }
}
