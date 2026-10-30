package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/**
 * Rules 19 (owner decision 2026-10-29): planks that fall out of a wooden bridge are gone for the rest of the climb: not back after a wait, after a
 * respawn, after the slice is unloaded and reloaded, or after a save and load (Tower done keys). Planks that have not fallen yet are not affected.
 */
public class PermanentBridgeDamageTest {
    static Element el(Element.Type ty, float s, float y, float w) { return new Element(ty, s, y, w); }

    static Course bridgeCourse(Tuning t) {
        Course c = new Course(1, t.circumference()); c.add(el(Element.Type.STATIC, 0, 0, 4));
        Element b = el(Element.Type.STATIC, 4.65f, 0.1f, 6.5f); b.skin = 1; b.color = 1 << 2; c.add(b);
        c.add(el(Element.Type.STATIC, 10.5f, 0.3f, 3f));
        return c;
    }

    @Test public void fallenPlanksNeverComeBackAfterAWait() throws Exception {
        Tuning t = TestUtil.tuning();
        Sim m = Sim.startOn(bridgeCourse(t), t, 0);
        m.crumbleT[1] = 0f;          // landed on the bridge
        InputState in = new InputState();
        for (int k = 0; k < 60 * 60; k++) { m.step(in); if (k > 60) assertTrue("open " + k, m.holesOpen(1)); }
        assertEquals(Element.HOLE_DONE, m.crumbleT[1], 0f);
    }

    @Test public void aRespawnKeepsFallenPlanksAndResetsPlanksStillInPlace() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = bridgeCourse(t);
        Element b2 = el(Element.Type.STATIC, 16f, 0.5f, 6.5f); b2.skin = 1; b2.color = 1 << 3; c.add(b2);
        Sim m = Sim.startOn(c, t, 0);
        m.crumbleT[1] = Element.HOLE_DONE;          // its planks have fallen
        m.crumbleT[3] = 0.2f;                        // landed on, planks not fallen yet
        m.completeRespawn(0);
        assertTrue("fallen planks stay gone", m.holesOpen(1));
        assertEquals("a bridge whose planks had not fallen is whole again", -1f, m.crumbleT[3], 0f);
    }

    /** A holed bridge in a real tower: damaged, unloaded (the window moved far away), reloaded: still damaged; and the damage survives a save (done keys). */
    @Test public void damageSurvivesUnloadingReloadingAndASave() throws Exception {
        Tuning t = TestUtil.tuning();
        for (long seed = 1; seed <= 40; seed++) {
            Tower tw = new Tower(seed, t); while (tw.topAbsY() < 700) tw.extend();
            int bi = -1; for (int i = 0; i < tw.world.size(); i++) if (tw.world.get(i).holed()) { bi = i; break; }
            if (bi < 0) continue;
            Sim s = Sim.startOn(tw.world, t, 0); s.keysFree = true; s.deferRespawn = true; s.floorOverride = tw.floorLocal(); s.setRange(0, tw.world.size() - 1);
            double abs = tw.absY(tw.world.get(bi).y); long key = tw.elemKeyAt(bi);
            s.crumbleT[bi] = Element.HOLE_DONE;
            long[] saved = tw.doneKeys(s).stream().mapToLong(Long::longValue).toArray();
            tw.windowAround(s, abs + 400.0); tw.windowAround(s, abs + 460.0);
            assertTrue("unloaded", tw.indexOfElemKey(key) < 0);
            tw.windowAround(s, abs);
            int b2 = tw.indexOfElemKey(key);
            assertTrue("resident again", b2 >= 0);
            assertTrue("still damaged after unloading and reloading", s.holesOpen(b2));
            // a save and load: a fresh tower of the same seed gets the done keys
            Tower tl = new Tower(seed, t); while (tl.topAbsY() < 700) tl.extend();
            tl.addDone(saved);
            Sim sl = Sim.startOn(tl.world, t, 0); sl.keysFree = true; sl.deferRespawn = true; sl.floorOverride = tl.floorLocal(); sl.setRange(0, tl.world.size() - 1);
            tl.windowAround(sl, abs + 400.0); tl.windowAround(sl, abs);
            int b3 = tl.indexOfElemKey(key);
            assertTrue(b3 >= 0);
            assertTrue("still damaged after a save and load", sl.holesOpen(b3));
            return;
        }
        fail("no holed bridge in the first 40 seeds below 700 m");
    }
}
