package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.Course;
import com.hotatticgames.climbup.sim.Element;
import com.hotatticgames.climbup.sim.InputState;
import com.hotatticgames.climbup.sim.Sim;
import com.hotatticgames.climbup.sim.Tuning;
import org.junit.Test;

/** Build 63 (owner): a crab that sees you runs at you, never leaves its own platform, and walks back onto its sweep when you are gone. */
public class CrabChaseTest {
    private static Element crab() { Element e = new Element(Element.Type.CRAB, 10f, 0f, 0f); e.amp = 1.4f; e.period = 3.6f; e.phase = 0f; e.anchor = 0; return e; }
    private static Course world(Tuning t, Element crab) { Course c = TestUtil.flat(t); c.hazards.add(crab); return c; }   // the start platform spans 6..14

    @Test public void itRunsAtYouWhenItSeesYouAndStopsAtItsEdge() throws Exception {
        Tuning t = TestUtil.tuning(); Element e = crab(); Course c = world(t, e);
        Sim s = Sim.startOn(c, t, 0); s.keysFree = false; s.invuln = 1e9f; s.shoveCd = 1e9f;     // no shoves: just watch it move
        s.s = 13.3f;                                                                           // standing near the right end, 3.3 m from the sweep's centre
        InputState in = new InputState();
        float d0 = Math.abs(c.dsWrap(s.crabAt(0, e), s.s));
        for (int i = 0; i < 90; i++) { in.clear(); s.step(in); }                             // 1.5 s
        float d1 = Math.abs(c.dsWrap(s.crabAt(0, e), s.s));
        assertTrue("it is chasing", s.crabChasing(0));
        assertTrue("it closed in (" + d0 + " -> " + d1 + ")", d1 < d0 - 1.0f);
        for (int i = 0; i < 600; i++) { in.clear(); s.step(in); }
        float edge = c.get(0).s + c.get(0).halfW() - Sim.CRAB_EDGE;
        assertTrue("never past its platform's edge: " + s.crabAt(0, e) + " vs " + edge, s.crabAt(0, e) <= edge + 1e-3f);
        assertTrue("and not through you either", s.crabAt(0, e) <= s.s + 1e-3f);
    }

    @Test public void itIgnoresYouFarAboveAndWalksBackToItsSweepWhenYouAreGone() throws Exception {
        Tuning t = TestUtil.tuning(); Element e = crab(); Course c = world(t, e);
        Sim s = Sim.startOn(c, t, 0); s.keysFree = false; s.invuln = 1e9f; s.shoveCd = 1e9f;
        InputState in = new InputState();
        s.s = 13.3f;
        for (int i = 0; i < 60; i++) { in.clear(); s.step(in); s.y = 6f; s.vy = 0f; s.mode = Sim.Mode.AIR; s.onElem = -1; }   // held far above its deck: out of its sight band
        assertFalse("not seen from up there", s.crabChasing(0));
        assertEquals("so it is on its sweep", e.sAt(s.time), s.crabAt(0, e), 1e-4f);
        s.y = 0f; s.vy = 0f; s.mode = Sim.Mode.GROUND; s.onElem = 0;                               // now on its platform: seen
        for (int i = 0; i < 60; i++) { in.clear(); s.step(in); }
        assertTrue("seen on the deck", s.crabChasing(0));
        boolean back = false;                                                                      // and gone (held far above, out of its sight band): it walks back onto the sweep
        for (int i = 0; i < 600 && !back; i++) { in.clear(); s.step(in); s.y = 30f; s.vy = 0f; s.mode = Sim.Mode.AIR; s.onElem = -1; back = !s.crabChasing(0) && Math.abs(c.dsWrap(s.crabAt(0, e), e.sAt(s.time))) < 1e-3f; }
        assertTrue("back on its sweep within ten seconds", back);
    }

    @Test public void heldAtItsEdgeWithYouBeyondItGivesUpAndDoesNotCamp() throws Exception {
        Tuning t = TestUtil.tuning(); Element e = crab(); Course c = world(t, e);
        Sim s = Sim.startOn(c, t, 0); s.keysFree = false; s.invuln = 1e9f; s.shoveCd = 1e9f;
        InputState in = new InputState();
        float edge = c.get(0).s + c.get(0).halfW() - Sim.CRAB_EDGE, farthest = -1e9f;
        boolean chased = false, gaveUp = false; int gaveUpAt = -1;
        for (int i = 0; i < 60 * 4; i++) {
            in.clear(); s.step(in);
            s.s = 14.4f; s.y = 0f; s.vy = 0f; s.mode = Sim.Mode.AIR; s.onElem = -1;          // hovering at the platform's lip (it ends at 14): in its sight but out of its reach
            farthest = Math.max(farthest, s.crabAt(0, e));
            if (s.crabChasing(0)) chased = true;
            else if (chased && !gaveUp) { gaveUp = true; gaveUpAt = i; }
        }
        assertTrue("it ran at you", chased);
        assertTrue("never past its edge: " + farthest + " vs " + edge, farthest <= edge + 1e-3f);
        assertTrue("it gave up within two seconds of reaching the edge (step " + gaveUpAt + ")", gaveUp && gaveUpAt < 60 * 2);
        assertFalse("and it is not back at the edge again after four seconds", s.crabChasing(0) && Math.abs(s.crabAt(0, e) - edge) < 0.05f);
    }

    @Test public void itWatchesItsOwnDeckOnlySoItCannotCampTheEdgeYouLandOn() throws Exception {
        Tuning t = TestUtil.tuning(); Element e = crab(); Course c = world(t, e);
        Sim s = Sim.startOn(c, t, 0); s.keysFree = false; s.invuln = 1e9f; s.shoveCd = 1e9f;
        InputState in = new InputState();
        s.s = 15.6f; s.y = 0.2f; s.vy = 0f; s.mode = Sim.Mode.AIR; s.onElem = -1;                // lining up the jump from beside its platform (the next platform's end, level with its deck)
        for (int i = 0; i < 60 * 3; i++) {
            in.clear(); s.step(in);
            s.s = 15.6f; s.y = 0.2f; s.vy = 0f; s.mode = Sim.Mode.AIR; s.onElem = -1;
            assertFalse("never seen off its deck (step " + i + ")", s.crabChasing(0));
            assertEquals("so it keeps to its sweep", e.sAt(s.time), s.crabAt(0, e), 1e-4f);
        }
        assertTrue("its sight stops just past its edge", Sim.CRAB_SIGHT_OVER < 1.0f && Sim.CRAB_SIGHT_OVER > 0f);
    }

    @Test public void itStopsToTurnRoundWhenYouGetPastIt() throws Exception {
        Tuning t = TestUtil.tuning(); Element e = crab(); Course c = world(t, e);
        Sim s = Sim.startOn(c, t, 0); s.keysFree = false; s.invuln = 1e9f; s.shoveCd = 1e9f;
        InputState in = new InputState();
        s.s = 13.3f;
        for (int i = 0; i < 45; i++) { in.clear(); s.step(in); }                                 // 0.75 s: it is running right, at you
        assertTrue(s.crabChasing(0)); assertEquals(1f, s.crabFacing(0, e), 0f);
        float at = s.crabAt(0, e);
        s.s = c.wrap(at - 2.0f);                                                                 // you hopped over it: now behind it
        in.clear(); s.step(in);
        assertEquals("it faces you at once", -1f, s.crabFacing(0, e), 0f);
        int still = 0;
        for (int i = 0; i < 60; i++) { in.clear(); s.step(in); if (Math.abs(s.crabAt(0, e) - at) < 1e-4f) still++; else break; }
        assertTrue("it stands for about CRAB_TURN seconds first (" + still + " steps)", Math.abs(still - Math.round(Sim.CRAB_TURN / Sim.DT)) <= 2);
        for (int i = 0; i < 30; i++) { in.clear(); s.step(in); }
        assertTrue("then it comes after you", s.crabAt(0, e) < at - 0.5f && s.crabChasing(0));
    }

    @Test public void theChaseIsDeterministicAndTheShoveComesFromWhereItReallyIs() throws Exception {
        Tuning t = TestUtil.tuning(); Element e = crab(); Course c = world(t, e);
        Sim a = Sim.startOn(c, t, 0), b = Sim.startOn(c, t, 0);
        a.keysFree = b.keysFree = false; a.invuln = b.invuln = 0f; a.s = b.s = 13.0f;
        InputState in = new InputState();
        int shoveAt = -1;
        for (int i = 0; i < 300; i++) {
            in.clear(); a.step(in); b.step(in);
            assertEquals("two sims, same crab", a.crabAt(0, e), b.crabAt(0, e), 0f);
            if (shoveAt < 0 && (a.consumeEvents() & Sim.EV_SHOVE) != 0) shoveAt = i;
        }
        assertTrue("standing still in its sight, it reaches you and shoves you", shoveAt >= 0 && shoveAt < 120);
        assertEquals("shoves never hurt", 0, a.hits);
        assertEquals("a copy carries the chase", a.crabAt(0, e), a.copy().crabAt(0, e), 0f);
    }
}
