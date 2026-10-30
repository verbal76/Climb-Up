package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.nio.file.*;
import org.junit.Test;

/**
 * Build 82 independent code review (2026-10-30), findings 2, 4 and 5: in the live tower each slice's side structures follow the slice's whole route,
 * so near a slice's end "index + 1" names a decoy rather than the next route deck, and an index says nothing about where a side deck lies along the
 * climb. These fixtures lay a course out that way - slice A's route (0, 1), slice A's decoy (2, hanging from deck 1), slice B's route (3, 4) - and
 * check that creatures, checkpoints and the planner count route decks, as DC6 does for everything else.
 */
public class LiveTowerDeckOrderTest {
    static Tuning T() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }

    /** Route 0, 1 | decoy 2 (anchor 1, well below) | route 3, 4: the next route deck after 1 is 3, 1.5 m of gap away at the same height. */
    static Course live(Tuning t) {
        Course c = new Course(1, t.circumference());
        c.add(new Element(Element.Type.STATIC, -6f, 0f, 4f));          // 0
        c.add(new Element(Element.Type.STATIC, 0f, 0f, 4f));           // 1: the encounter's deck, last route deck of slice A
        Element decoy = new Element(Element.Type.STATIC, -1f, -6f, 3f); decoy.anchor = 1; c.add(decoy);          // 2: slice A's decoy
        c.add(new Element(Element.Type.STATIC, 5.5f, 0f, 4f));         // 3: first route deck of slice B
        c.add(new Element(Element.Type.STATIC, 11f, 0f, 4f));          // 4
        return c;
    }

    static void standOn(Sim s, int deck) {
        Element d = s.course.get(deck);
        s.mode = Sim.Mode.GROUND; s.onElem = deck; s.s = d.s; s.y = d.y; s.vx = 0f; s.vy = 0f;
    }

    /** Finding 2 (Enforcer): with you on the next route deck it hops there - over the slice boundary, past the decoy listed between. */
    @Test public void anEnforcerHopsToTheNextRouteDeckPastASlicesDecoy() throws Exception {
        Tuning t = T().hardCopy(1);
        Course c = live(t);
        assertEquals("the layout under test: the next route deck after 1 is 3", 3, c.deck(1, 1));
        Element r = new Element(Element.Type.ENFORCER, 0f, 0f, 4f); r.anchor = 1; r.len = 2; r.skin = 0; c.hazards.add(r);
        Sim s = Sim.startOn(c, t, 3);
        int hi = c.hazards.indexOf(r);
        InputState in = new InputState();
        boolean reached = false;
        for (int i = 0; i < (int) (12f / Sim.DT) && !reached; i++) {
            in.clear(); standOn(s, 3); s.step(in);
            reached = s.mobDeckIdx(hi, r) == 3;
        }
        assertTrue("it hopped onto route deck 3 (deck offset " + s.deckOff[hi] + ", state " + s.enforcerState(hi) + ")", reached);
    }

    /** Finding 2 (crab): chasing you, it follows you onto the next route deck past the decoy, as it does inside a slice. */
    @Test public void aChasingCrabFollowsOntoTheNextRouteDeckPastASlicesDecoy() throws Exception {
        Tuning t = T();
        Course c = live(t);
        Element crab = new Element(Element.Type.CRAB, 0f, 0.05f, 0.6f); crab.anchor = 1; crab.amp = 1.2f; crab.period = 3f; c.hazards.add(crab);
        Sim s = Sim.startOn(c, t, 1);
        int hi = c.hazards.indexOf(crab);
        InputState in = new InputState();
        for (int i = 0; i < (int) (1.5f / Sim.DT); i++) { in.clear(); standOn(s, 1); s.s = 1.6f; s.invuln = 1f; s.step(in); }          // it sees you on its deck and gives chase
        boolean followed = false;
        for (int i = 0; i < (int) (8f / Sim.DT) && !followed; i++) {
            in.clear(); standOn(s, 3); s.invuln = 1f; s.step(in);
            followed = s.mobDeckIdx(hi, crab) == 3;
        }
        assertTrue("it followed you onto route deck 3 (deck offset " + s.deckOff[hi] + ")", followed);
    }

    /** Finding 4: behind the checkpoint means along the route - a side deck counts by the route deck it hangs from, not by its index. */
    @Test public void encountersBehindTheCheckpointAreJudgedByRoutePosition() throws Exception {
        Tuning t = T();
        Course c = live(t);
        Element fork = new Element(Element.Type.STATIC, 6f, 3f, 3f); fork.anchor = 3; c.add(fork);          // 5: a side deck hanging from the checkpoint deck
        Sim s = Sim.startOn(c, t, 0);
        assertTrue("route deck 1 is behind checkpoint 3", s.behindCheckpoint(1, 3));
        assertTrue("the checkpoint's own deck counts as behind (an encounter there goes on)", s.behindCheckpoint(3, 3));
        assertFalse("route deck 4 is ahead", s.behindCheckpoint(4, 3));
        assertTrue("decoy 2 hangs from deck 1: behind checkpoint 3, although its index is 2", s.behindCheckpoint(2, 3));
        assertFalse("decoy 2 hangs from deck 1: not behind checkpoint 1 (it branches off the checkpoint's deck, ahead of it)", s.behindCheckpoint(2, 1));
        assertFalse("side deck 5 hangs from checkpoint deck 3: ahead, although index 5 > 3 says nothing", s.behindCheckpoint(5, 3));
        assertTrue("side deck 5 is behind checkpoint 4", s.behindCheckpoint(5, 4));
    }

    /** Finding 5: the planner's moving-hazard window covers the next two route decks over the slice boundary, and not a decoy listed between. */
    @Test public void thePlannersHazardWindowCountsRouteDecks() throws Exception {
        Tuning t = T();
        Course c = live(t);
        Element saw = new Element(Element.Type.SAW_H, 11f, 0.6f, 0f); saw.anchor = 4; saw.amp = 1f; saw.period = 2f; c.hazards.add(saw);
        assertTrue("a moving hazard on route deck 4 (two route decks after 1) is near the link from 1", saw.isMoving());
        assertTrue(Autopilot.movingNear(c, 1));
        assertEquals(2f, Autopilot.maxPeriod(c, 1), 1e-6f);
        c.hazards.clear();
        Element onDecoy = new Element(Element.Type.SAW_H, -1f, -5.4f, 0f); onDecoy.anchor = 2; onDecoy.amp = 1f; onDecoy.period = 2f; c.hazards.add(onDecoy);
        assertFalse("a hazard on the decoy is not on the link from 1", Autopilot.movingNear(c, 1));
    }
}
