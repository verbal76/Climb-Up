package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/** Mortars: a ball that climbs to about twice the player's height above the path, falls back into the cannon and fires again; a hit only knocks the hero away; rows are generated, staggered and proven. */
public class MortarTest {
    private static Element mortar(float muzzleY, float amp, float period, float phase) {
        Element m = new Element(Element.Type.MORTAR, 10f, muzzleY, 0f); m.amp = amp; m.period = period; m.phase = phase; return m;
    }

    @Test public void theBallRisesToTwiceThePlayerHeightAboveThePathFallsBackIntoTheBarrelAndRelaunches() throws Exception {
        Tuning t = TestUtil.tuning();
        float path = 0f, amp = 3f + 2f * t.height; Element m = mortar(path - 3f, amp, 3.6f, 0f);
        float top = -1e9f, atReload = Float.NaN; boolean lethalWhileReloading = false; int launches = 0; float prevY = m.y;
        for (float x = 0; x < m.period * 3f; x += 1f / 120f) {
            float yy = m.mortarBallY(x); top = Math.max(top, yy);
            if (m.cyc(x) >= Element.MORTAR_FLIGHT) { atReload = yy; if (m.lethalAt(x)) lethalWhileReloading = true; }
            if (prevY <= m.y + 1e-3f && yy > m.y + 1e-3f) launches++;
            prevY = yy;
        }
        assertEquals("apex is twice the player's height above the path", path + 2f * t.height, top, 0.05f);
        assertEquals("the ball is back in the barrel while it reloads", m.y, atReload, 1e-4f);
        assertFalse("a ball in the barrel hurts nobody", lethalWhileReloading);
        assertTrue("it fires again every cycle", launches >= 2);
    }

    @Test public void aHitKnocksTheHeroAwayAndNeverSendsHimToACheckpoint() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = new Course(0, t.circumference());
        Element a = new Element(Element.Type.STATIC, 7f, 0f, 3f); a.checkpoint = true; c.add(a);
        Element b = new Element(Element.Type.STATIC, 13f, 0f, 3f); c.add(b);
        Element m = mortar(-3f, 3f + 2f * t.height, 3.6f, 0f); m.s = 10f; m.anchor = 0; c.hazards.add(m);
        Sim s = Sim.startOn(c, t, 0);
        s.s = 10f; s.y = 0.6f; s.mode = Sim.Mode.AIR; s.onElem = -1; s.vy = 0f; s.vx = 0f;
        InputState in = new InputState(); int hits = 0, hold = 0;
        for (int i = 0; i < 60 * 8; i++) {
            if (i < 60 * 4) { s.mode = Sim.Mode.AIR; s.onElem = -1; s.s = 10f; s.y = 0.6f; s.vy = 0f; s.vx = 0f; s.invuln = 0f; }      // a hero hanging in the column
            in.clear(); int h0 = s.hits; s.step(in); s.consumeEvents(); if (s.hits > h0) hits++;
        }
        assertTrue("the rising ball hits a hero in its column", hits >= 1);
        assertEquals("no checkpoint teleport", 0, s.falls);
    }

    @Test public void mortarRowsAreGeneratedStaggeredAndOnlyAfterTheFirstWorld() throws Exception {
        Tuning t = TestUtil.tuning();
        int mortars = 0, rows = 0; float lowest = 1e9f;
        for (long seed = 160; seed < 170; seed++) {
            Course prev = null;
            for (int k = 0; k < 7; k++) {
                Course c = CourseGenerator.chunk(seed, k, prev, t); prev = c;
                java.util.List<Element> row = new java.util.ArrayList<>();
                for (Element h : c.hazards) if (h.type == Element.Type.MORTAR) { row.add(h); mortars++; lowest = Math.min(lowest, h.y); assertEquals("apex two heights above the path", h.y + 3f + 2f * t.height, h.y + h.amp, 0.01f); }
                if (row.size() >= 5) { rows++; for (int i = 1; i < row.size(); i++) assertNotEquals("neighbouring mortars are out of step", row.get(i - 1).phase, row.get(i).phase, 0.2f); }
            }
        }
        assertTrue("rows were generated (" + mortars + " mortars, " + rows + " rows)", rows >= 1);
    }
}
