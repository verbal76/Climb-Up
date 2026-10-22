package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Castles are checkpoints as well as the red gems (owner): opening a castle's door saves you at its deck; a later fall past the lowest platform brings you back inside the open castle, never to the gem below it. */
public class CastleCheckpointTest {
    private static final int COLOR = 2;

    /** A flat start platform (the red-gem checkpoint, index 0) and a castle deck beside it (index 1) with its gate anchored on the deck. */
    private static Course castleCourse(Tuning t) {
        Element deck = new Element(Element.Type.STATIC, 18.5f, 0f, 9f); deck.skin = 2;          // the castle deck (14..23), flush with the start platform (6..14): not a gem (no skin 3), not flagged
        Course c = TestUtil.flat(t, deck); c.gemCheckpoints = true;
        Element gate = new Element(Element.Type.GATE, 18.5f, 0f, 3.4f); gate.len = 7.5f; gate.color = COLOR; gate.anchor = 1; c.hazards.add(gate);
        return c;
    }

    @Test public void openingTheCastleDoorSavesYouAtItsDeck() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = castleCourse(t);
        Sim s = Sim.startOn(c, t, 0); s.setRange(0, c.size() - 1); s.keysFree = false; s.keys = 1 << COLOR; s.checkpoint = 0; s.invuln = 0f;
        assertFalse("the deck itself is not a gem checkpoint", c.isCheckpoint(c.get(1)));
        InputState in = new InputState();
        int ev = 0;
        for (int i = 0; i < 240 && s.s < 21.5f; i++) { in.clear(); in.moveX = 1f; s.step(in); ev |= s.consumeEvents(); }
        assertTrue("walked through the door", s.s > 21f);
        assertTrue("the door opened", (ev & Sim.EV_DOOR) != 0);
        assertTrue("and that is a checkpoint", (ev & Sim.EV_CHECKPOINT) != 0);
        assertEquals("the checkpoint is the castle deck", 1, s.checkpoint);
        assertEquals("the key was used", 0, s.keys);
        // a fall past the lowest platform: back to the castle, inside the open door
        s.respawn();
        assertEquals(1, s.onElem);
        assertEquals("standing at the deck's centre, where the castle is", 18.5f, s.s, 0.01f);
        assertEquals(0f, s.y, 0.01f);
        assertTrue("the door is still open", s.featDone[0]);
        in.clear(); in.moveX = 1f;
        for (int i = 0; i < 60; i++) s.step(in);
        assertTrue("walks out through the exit door without being blocked", s.s > 21f);
        assertEquals("no block event", 0, s.consumeEvents() & Sim.EV_BLOCKED);
    }

    @Test public void aLockedCastleIsNotACheckpoint() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = castleCourse(t);
        Sim s = Sim.startOn(c, t, 0); s.setRange(0, c.size() - 1); s.keysFree = false; s.keys = 0; s.checkpoint = 0; s.invuln = 0f;
        InputState in = new InputState();
        int ev = 0;
        for (int i = 0; i < 240; i++) { in.clear(); in.moveX = 1f; s.step(in); ev |= s.consumeEvents(); }
        assertTrue("stopped at the locked gate", (ev & Sim.EV_BLOCKED) != 0);
        assertEquals("no checkpoint from a door you could not open", 0, s.checkpoint);
        assertEquals("and no checkpoint event", 0, ev & Sim.EV_CHECKPOINT);
        s.respawn();
        assertEquals("a fall still goes back to the red gem", 0, s.onElem);
    }

    @Test public void aCastleBelowTheCurrentCheckpointDoesNotLowerIt() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = castleCourse(t);
        Sim s = Sim.startOn(c, t, 0); s.setRange(0, c.size() - 1); s.keysFree = true; s.checkpoint = 1;     // already saved at (or above) the deck
        InputState in = new InputState();
        int ev = 0;
        for (int i = 0; i < 240 && s.s < 21.5f; i++) { in.clear(); in.moveX = 1f; s.step(in); ev |= s.consumeEvents(); }
        assertTrue((ev & Sim.EV_DOOR) != 0);
        assertEquals("the checkpoint never moves down", 1, s.checkpoint);
        assertEquals(0, ev & Sim.EV_CHECKPOINT);
    }
}
