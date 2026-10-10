package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/** Aimed cannon: turns to the player while tracking, locks, flashes, fires along exactly the locked barrel line; fair (a dodge after the lock works), asleep out of range, a hit only knocks away. */
public class AimedCannonTest {
    private static Sim world(float playerS, float playerY, float phase) throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = new Course(0, t.circumference());
        Element a = new Element(Element.Type.STATIC, 10f, 0f, 3f); a.checkpoint = true; c.add(a);
        Element b = new Element(Element.Type.STATIC, 28f, 0f, 30f); c.add(b);
        Element h = new Element(Element.Type.AIMED, 10f, 2.0f, 0f); h.period = 4.4f; h.phase = phase; h.anchor = 0; c.hazards.add(h);
        Sim s = Sim.startOn(c, t, 1); s.s = playerS; s.y = playerY;
        s.mode = Sim.Mode.GROUND; s.onElem = 1;
        return s;
    }

    private static void run(Sim s, float seconds, float holdS) {
        InputState in = new InputState();
        for (int i = 0; i < (int) (seconds * 60); i++) { in.clear(); s.step(in); s.consumeEvents(); }
    }

    @Test public void theBarrelTurnsToThePlayerAndTheBallGoesWhereTheBarrelPoints() throws Exception {
        Sim s = world(20f, 0f, 0f);
        Element h = s.course.hazards.get(0);
        run(s, h.period * Element.AIM_LOCK + 0.05f, 0f);                 // tracking is over: locked
        float want = (float) Math.atan2(0.7f - h.y, 20f - 10f);
        assertEquals("barrel points at the player", want, s.aimAng[0], 0.12f);
        // the ball launches along that line: at the moment it is level with the player's arc it is within reach of the player
        InputState in = new InputState(); boolean fired = false; float[] atPlayer = null;
        for (int i = 0; i < 60 * 4 && atPlayer == null; i++) {
            in.clear(); s.step(in); s.consumeEvents();
            float[] b = s.aimedBall(0, h);
            if (b != null) { fired = true; if (b[0] >= 20f) atPlayer = b; }
        }
        assertTrue("it fired", fired); assertNotNull(atPlayer);
        assertEquals("the ball passes the player's height where he stands", 0.7f, atPlayer[1], 0.6f);
    }

    @Test public void aHeroWhoStaysPutIsKnockedAwayAndNeverSentToACheckpoint() throws Exception {
        Sim s = world(20f, 0f, 0f);
        InputState in = new InputState(); int hits = 0;
        for (int i = 0; i < 60 * 12; i++) { in.clear(); int h0 = s.hits; s.step(in); s.consumeEvents(); if (s.hits > h0) hits++; if (s.mode != Sim.Mode.GROUND) { s.mode = Sim.Mode.GROUND; s.onElem = 1; s.s = 20f; s.y = 0f; s.vx = s.vy = 0f; } }
        assertTrue("standing in the line of fire gets hit (" + hits + ")", hits >= 1);
        assertEquals(0, s.falls);
    }

    @Test public void stepAsideAfterTheLockAndTheBallMisses() throws Exception {
        Sim s = world(20f, 0f, 0f);
        Element h = s.course.hazards.get(0);
        run(s, h.period * Element.AIM_LOCK + 0.05f, 0f);               // locked
        s.s = 20f; s.y = 4.5f; s.mode = Sim.Mode.AIR; s.onElem = -1; s.vy = 0f;      // gone from the line (a jump): the ball flies on the locked line
        InputState in = new InputState(); int hits = 0;
        for (int i = 0; i < 60 * 1.2; i++) { in.clear(); int h0 = s.hits; s.vy = 0f; s.y = 4.5f; s.step(in); s.consumeEvents(); if (s.hits > h0) hits++; }
        assertEquals("dodged", 0, hits);
    }

    @Test public void itSleepsWhenThePlayerIsOutOfRange() throws Exception {
        Sim s = world(10f + Element.AIM_RANGE + 6f, 0f, 0f);
        run(s, 4.4f * 3, 0f);
        assertTrue("never fired", s.aimedBall(0, s.course.hazards.get(0)) == null && s.aimFireT[0] < -1e8f);
        assertEquals(0, s.hits);
    }

    @Test public void lateGameSlicesContainAimedCannonsAndTheyArePlacedOnPlatformsWithClearance() throws Exception {
        Tuning t = TestUtil.tuning(); t.zoneHeight = 50f;                 // compress the worlds so the late game (night world on) is reached within a few slices
        int aimed = 0;
        for (long seed = 190; seed < 194; seed++) { Course prev = null;
        for (int k = 0; k < 11; k++) {
            Course c = CourseGenerator.chunk(seed, k, prev, t); prev = c;
            for (Element h : c.hazards) if (h.type == Element.Type.AIMED) {
                aimed++;
                Element owner = c.get(h.anchor);
                assertTrue("turret stands clear above its platform", h.y >= owner.y + 1.9f);
                assertTrue("a readable cycle", h.period >= 3f);
            }
        }
        }
        assertTrue("late-game slices had aimed cannons (" + aimed + ")", aimed >= 1);
    }
}
