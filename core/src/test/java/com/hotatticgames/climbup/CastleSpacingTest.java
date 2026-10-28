package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/** The castles of the endless climb stand at exactly castleSpacing apart for every runner, and walking through castle 10's door ends the timed run. */
public class CastleSpacingTest {
    @Test public void castlesStandAtExactMultiplesOfTheSpacing() throws Exception {
        Tuning t = TestUtil.tuning();
        assertEquals(500f, t.castleSpacing, 0f); assertEquals(10, t.finishCastle);
        for (long seed : new long[]{7L, 41L}) {
            Course prev = null; float y = 0; int found = 0; int k = 0;
            while (y < 1600f) {
                Course c = CourseGenerator.chunk(seed, k++, prev, t); prev = c; y = c.get(c.routeSize() - 1).y;
                for (Element h : c.hazards) if (h.type == Element.Type.GATE) {
                    found++;
                    assertEquals("castle " + found + " height (seed " + seed + ")", found * 500f, h.y, 1e-3f);
                    assertEquals(found, h.skin);
                }
            }
            assertEquals("three castles below 1600 m", 3, found);
        }
    }

    @Test public void walkingThroughTheFinishCastlesOpenDoorEndsTheRunOnce() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = TestUtil.flat(t);
        Element gate = new Element(Element.Type.GATE, 10f, 0f, 3.4f); gate.len = 7.5f; gate.color = 1; gate.skin = t.finishCastle; gate.anchor = 0; c.hazards.add(gate);
        Sim s = Sim.startOn(c, t, 0); s.keys = 1 << 1; s.s = 6.5f;
        InputState in = new InputState(); int finishes = 0, doors = 0;
        for (int i = 0; i < 400; i++) { in.clear(); in.moveX = 1f; s.step(in); int ev = s.consumeEvents(); if ((ev & Sim.EV_FINISH) != 0) finishes++; if ((ev & Sim.EV_DOOR) != 0) doors++; }
        assertEquals(1, doors); assertEquals("exactly one finish", 1, finishes); assertTrue(s.finishedRun);
        Element other = new Element(Element.Type.GATE, 10f, 0f, 3.4f); other.len = 7.5f; other.color = 2; other.skin = 3; other.anchor = 0;
        Course c2 = TestUtil.flat(t); c2.hazards.add(other);
        Sim s2 = Sim.startOn(c2, t, 0); s2.keys = 1 << 2; s2.s = 6.5f;
        for (int i = 0; i < 400; i++) { in.clear(); in.moveX = 1f; s2.step(in); s2.consumeEvents(); }
        assertFalse("castle 3 is not the finish", s2.finishedRun);
    }
}
