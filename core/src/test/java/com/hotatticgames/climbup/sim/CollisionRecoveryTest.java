package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.nio.file.*;
import org.junit.After;
import org.junit.Test;

/**
 * Rules 19 owner correction (2026-10-29): a hit is not a failure. The planner follows a knock to its outcome: a move that takes a cannonball from
 * behind and is carried onto the next deck is a working move, and so is one where a timed rebound off that ball does it; a knock that ends in a fall,
 * on a dead end or nowhere is not. A clean move is always preferred, and a collision move needs at least Autopilot.MIN_COLLISION_SUCCESSES working
 * variants. Deterministic fixtures on the real Sim and planner (the gap widths come from CollisionProbe's map).
 */
public class CollisionRecoveryTest {
    static Tuning T() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }
    @After public void restore() { Autopilot.HIT_STRICT = false; }

    /** Deck 0 at s 0 (4 m), deck 1 across a {@code gap}; with {@code cannon}, a cannon 8 m behind deck 0 fires along the jump at 2 m up. */
    static Course course(Tuning t, float gap, boolean cannon) {
        Course c = new Course(1, t.circumference());
        c.add(new Element(Element.Type.STATIC, 0f, 0f, 4f));
        c.add(new Element(Element.Type.STATIC, 2f + gap + 2f, 0f, 4f));
        if (cannon) { Element cn = new Element(Element.Type.CANNON, -8f, 2.0f, 1f); cn.dir = 1; cn.len = 30f; cn.period = 1.6f; cn.anchor = 0; c.hazards.add(cn); }
        return c;
    }
    /** Plays the planned move to its end on a copy and 1 s beyond (standing still): the state then. */
    static Sim play(Sim base, Autopilot.Result r) {
        Sim s = base.copy(); s.humanHold = Autopilot.HUMAN_HOLD; s.deferRespawn = false;
        Autopilot.Policy p = Autopilot.make(r.factory); InputState in = new InputState();
        for (int k = 0; k < 900 && !(s.mode == Sim.Mode.GROUND && s.onElem == 1); k++) { in.clear(); p.act(s, in); s.step(in); }
        for (int k = 0; k < 60; k++) { in.clear(); s.step(in); }
        return s;
    }

    @Test public void aCleanRouteStaysCleanAndIsPreferred() throws Exception {
        Tuning t = T(); Sim m = Sim.startOn(course(t, 2.5f, false), t, 0);
        Autopilot.Result r = Autopilot.plan(m, 0, true, false);
        assertTrue(r.ok); assertFalse(r.viaHit); assertFalse(r.viaRebound);
        assertEquals(0, r.hitSuccesses); assertEquals("no rebound search when a clean move exists", 0, r.reboundTrials);
        Sim e = play(m, r); assertEquals(Sim.Mode.GROUND, e.mode); assertEquals(1, e.onElem); assertEquals(0, e.hits);
    }

    @Test public void anOrdinaryKnockThatCarriesYouAcrossIsAWorkingMove() throws Exception {
        Tuning t = T(); Sim m = Sim.startOn(course(t, 5f, true), t, 0);
        Autopilot.Result r = Autopilot.plan(m, 0, true, false);
        assertTrue("the knock's flight lands on deck 1", r.ok);
        assertTrue(r.viaHit); assertFalse("no press needed", r.viaRebound);
        assertEquals("no clean move across this gap", 0, r.successes);
        assertTrue("not a one-off (" + r.hitSuccesses + " working moves)", r.hitSuccesses >= Autopilot.MIN_COLLISION_SUCCESSES);
        Sim e = play(m, r);
        assertEquals("standing on deck 1 a second later", Sim.Mode.GROUND, e.mode); assertEquals(1, e.onElem);
        assertTrue("it really was hit", e.hits > 0); assertEquals("and never fell", 0, e.falls);
    }

    @Test public void theOldRuleRejectedThatMove() throws Exception {
        Autopilot.HIT_STRICT = true;
        Tuning t = T(); Sim m = Sim.startOn(course(t, 5f, true), t, 0);
        assertFalse("hits counted as setbacks: no move", Autopilot.plan(m, 0, true, false).ok);
    }

    @Test public void aTimedReboundCarriesYouFurtherWhereAPlainKnockFallsShort() throws Exception {
        Tuning t = T(); Sim m = Sim.startOn(course(t, 8f, true), t, 0);
        Autopilot.Result r = Autopilot.plan(m, 0, true, false);
        assertTrue(r.ok); assertTrue(r.viaRebound);
        assertEquals("no plain knock gets across", 0, r.hitSuccesses);
        assertTrue("not a one-off (" + r.reboundSuccesses + "/" + r.reboundTrials + ")", r.reboundSuccesses >= Autopilot.MIN_COLLISION_SUCCESSES);
        assertTrue("bounded search", r.reboundTrials <= Autopilot.REBOUND_CANDS * Autopilot.REBOUND_LEADS.length);
        Sim e = play(m, r);
        assertEquals(Sim.Mode.GROUND, e.mode); assertEquals(1, e.onElem);
        assertTrue("a real rebound", e.rebounds > 0); assertEquals(0, e.falls);
    }

    @Test public void theReboundNeedsTheFreshPress() throws Exception {
        Tuning t = T(); Sim m = Sim.startOn(course(t, 8f, true), t, 0);
        Autopilot.Result r = Autopilot.plan(m, 0, true, false);
        assertTrue(r.factory.create() instanceof Autopilot.ReboundPress);
        // the same move with Jump simply held through the flight (no fresh press): no rebound, and it does not arrive
        Autopilot.Result hr = new Autopilot.Result();
        hr.factory = () -> { Autopilot.ReboundPress rp = (Autopilot.ReboundPress) r.factory.create();
            return (s, in) -> { rp.inner.act(s, in); if (s.mode == Sim.Mode.AIR) in.jumpHeld = true; }; };
        Sim e = play(m, hr);
        assertEquals(0, e.rebounds);
        assertFalse("held, not pressed: no rebound and no arrival", e.mode == Sim.Mode.GROUND && e.onElem == 1);
    }

    @Test public void aKnockThatCannotBeRecoveredIsStillAFailure() throws Exception {
        Tuning t = T(); Sim m = Sim.startOn(course(t, 11f, true), t, 0);
        Autopilot.Result r = Autopilot.plan(m, 0, true, false);
        assertFalse("too far even with a knock or a rebound", r.ok);
    }

    @Test public void aKnockOntoADeadEndIsNoArrival() throws Exception {
        Tuning t = T();
        Course c = new Course(1, t.circumference());
        c.add(new Element(Element.Type.STATIC, 0f, 0f, 4f));
        c.add(new Element(Element.Type.STATIC, 30f, 0f, 4f));                       // the route's next deck: far out of reach
        Element dead = new Element(Element.Type.STATIC, 2f + 5f + 2f, 0f, 4f); dead.anchor = 0; c.add(dead);          // where the knock lands: a side deck off the route
        c.routeCount = 2; c.indexDecoys();
        Element cn = new Element(Element.Type.CANNON, -8f, 2.0f, 1f); cn.dir = 1; cn.len = 30f; cn.period = 1.6f; cn.anchor = 0; c.hazards.add(cn);
        Sim m = Sim.startOn(c, t, 0);
        Autopilot.Result r = Autopilot.plan(m, 0, true, false);
        assertFalse("carried onto a dead end is not progress", r.ok);
    }

    @Test public void collisionMovesAreDeterministic() throws Exception {
        Tuning t = T();
        Sim a = play(Sim.startOn(course(t, 8f, true), t, 0), Autopilot.plan(Sim.startOn(course(t, 8f, true), t, 0), 0, true, false));
        Sim b = play(Sim.startOn(course(t, 8f, true), t, 0), Autopilot.plan(Sim.startOn(course(t, 8f, true), t, 0), 0, true, false));
        assertEquals(a.s, b.s, 0f); assertEquals(a.y, b.y, 0f); assertEquals(a.hits, b.hits); assertEquals(a.rebounds, b.rebounds); assertEquals(a.time, b.time, 0f);
    }
}
