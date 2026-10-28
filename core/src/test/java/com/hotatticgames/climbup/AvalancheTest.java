package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/**
 * Spike-block avalanche physics (owner 2026-10-27): one deterministic implementation for every mode. The blocks wait out a warning, drop from above,
 * bounce off the decks and each other, tumble back down past the trigger deck and are cleaned up; a spot fires once, and only after the cooldown.
 */
public class AvalancheTest {
    /** A staircase up the tower: the trigger deck 0, then four decks rising ahead of it. */
    private static Course stairs(Tuning t, int blocks, float cooldown, float seedPhase) {
        Course c = new Course(0, t.circumference());
        for (int k = 0; k < 5; k++) { Element d = new Element(Element.Type.STATIC, 10f + 4.5f * k, 1.2f * k, 3.4f); if (k == 0) d.checkpoint = true; c.add(d); }
        Element a = new Element(Element.Type.AVALANCHE, 10f, 0f, 3.4f);
        a.anchor = 0; a.len = blocks; a.period = cooldown; a.phase = seedPhase;
        c.hazards.add(a);
        return c;
    }

    /** {impacts, lowest block height reached, time it ended, first time a block could hit, then every block's state every 0.25 s}. */
    private static float[] run(Tuning t, int blocks, float phase) {
        Course c = stairs(t, blocks, 45f, phase);
        Sim s = Sim.startOn(c, t, 0); s.invuln = 1e9f;          // watch the blocks, not the knock-backs
        InputState in = new InputState();
        float low = 1e9f, end = -1f, firstLive = -1f;
        java.util.List<Float> trace = new java.util.ArrayList<>();
        for (int i = 0; i < (int) (16f / Sim.DT); i++) {
            in.clear(); s.step(in);
            for (int b = 0; b < Element.AVL_MAX; b++) {
                float[] k = s.avalancheBlock(0, c.hazards.get(0), b);
                if (k == null) continue;
                if (k[3] == 1f) { low = Math.min(low, k[1]); if (firstLive < 0f) firstLive = s.time; }
                if (i % 15 == 0) { trace.add(k[0]); trace.add(k[1]); trace.add(k[2]); }
            }
            if (end < 0f && s.avalancheState(0) == 2) end = s.time;
        }
        float[] out = new float[4 + trace.size()];
        out[0] = s.avlImpacts; out[1] = low; out[2] = end; out[3] = firstLive;
        for (int i = 0; i < trace.size(); i++) out[4 + i] = trace.get(i);
        return out;
    }

    @Test public void blocksWarnFallBounceTumblePastAndAreCleanedUpTheSameWayEveryTime() throws Exception {
        Tuning t = TestUtil.tuning();
        float[] a = run(t, 5, 0.37f), b = run(t, 5, 0.37f);
        assertArrayEquals("same seed, same inputs: the same avalanche", a, b, 0f);
        assertTrue("the blocks hit the decks (impacts " + a[0] + ")", a[0] >= 3f);
        assertTrue("no block is live before the warning has run (" + a[3] + ")", a[3] >= Element.AVL_WARN - 1e-3f);
        assertTrue("they tumble down past the trigger deck (lowest " + a[1] + ")", a[1] < -2f);
        assertTrue("and are all cleaned up (ended at " + a[2] + ")", a[2] > 0f && a[2] < Element.AVL_LIFE + 1f);
        float[] other = run(t, 5, 0.81f);
        assertFalse("another seed gives other paths", java.util.Arrays.equals(a, other));
    }

    @Test public void twoBlocksForNormalUseTheSamePhysics() throws Exception {
        Tuning t = TestUtil.tuning();
        float[] two = run(t, 2, 0.37f), five = run(t, 5, 0.37f);
        // the first two blocks start exactly alike in both; nothing about a block's own motion depends on the mode
        assertEquals("first sample: block 0 arc", five[4], two[4], 0f);
        assertEquals("first sample: block 0 height", five[5], two[5], 0f);
        assertTrue(two[0] >= 1f);
    }

    @Test public void aSpotFiresOnceAndOnlyAfterTheCooldown() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = stairs(t, 3, 45f, 0.37f);
        Element second = new Element(Element.Type.AVALANCHE, 19f, 2.4f, 3.4f);
        second.anchor = 2; second.len = 3; second.period = 45f; second.phase = 0.5f;
        c.hazards.add(second);
        Sim s = Sim.startOn(c, t, 0); s.invuln = 1e9f;
        InputState in = new InputState();
        for (int i = 0; i < 30; i++) { in.clear(); s.step(in); }
        assertEquals("landing on its deck fires it", 1, s.avalancheState(0));
        int warns = 0; s.events = 0;          // (the firing itself is already counted)
        for (int i = 0; i < (int) (20f / Sim.DT); i++) { in.clear(); s.step(in); if ((s.events & Sim.EV_AVL_WARN) != 0) warns++; s.events = 0; }
        assertEquals("standing there never fires it again", 0, warns);
        assertEquals(2, s.avalancheState(0));
        Sim q = Sim.startOn(c, t, 0); q.invuln = 1e9f; q.lastAvlT = q.time - 10f;          // the last avalanche was 10 s ago
        for (int i = 0; i < 30; i++) { in.clear(); q.step(in); }
        assertEquals("within the cooldown the spot is spent instead", 2, q.avalancheState(0));
        for (int b = 0; b < Element.AVL_MAX; b++) assertNull(q.avalancheBlock(0, c.hazards.get(0), b));
    }

    @Test public void blocksKnockIntoEachOther() throws Exception {
        Tuning t = TestUtil.tuning(); int knocks = 0;
        for (float ph = 0.05f; ph < 1f; ph += 0.1f) {
            Course c = stairs(t, 5, 45f, ph);
            Sim s = Sim.startOn(c, t, 0); s.invuln = 1e9f;
            InputState in = new InputState();
            for (int i = 0; i < (int) (14f / Sim.DT); i++) { in.clear(); s.step(in); }
            knocks += s.avlKnocks;
        }
        assertTrue("blocks collide with each other (" + knocks + " knocks over ten seeds)", knocks > 0);
    }

    /** The plan per castle interval: NORMAL none before castle 9 and two blocks at most; PUNISHER two or three, of 4-5 blocks; NOT GONNA HAPPEN (rules 14) three or four of 5-7. */
    @Test public void thePlanKeepsEachModesLimits() throws Exception {
        Tuning t = TestUtil.tuning(); float sp = t.castleSpacing;
        int n9 = 0, n10 = 0;
        for (long seed = 1; seed <= 400; seed++) for (int n = 1; n <= 12; n++) {
            float[][] nor = CourseGenerator.avalanchePlan(seed, n, sp, DifficultyProfile.NORMAL.avalanches());
            float[][] pun = CourseGenerator.avalanchePlan(seed, n, sp, DifficultyProfile.PUNISHER.avalanches());
            float[][] ngh = CourseGenerator.avalanchePlan(seed, n, sp, DifficultyProfile.NGH.avalanches());          // rules 14 (owner 2026-10-27)
            assertTrue("NOT GONNA HAPPEN: three or four a castle interval (" + ngh.length + ")", ngh.length >= 3 && ngh.length <= 4);
            for (float[] a : ngh) {
                assertTrue("NGH: 5-7 blocks (" + a[1] + ")", a[1] >= 5f && a[1] <= 7f && a[1] == Math.round(a[1]) && a[1] <= Element.AVL_MAX);
                assertTrue("NGH cooldown 15-45 s", a[2] >= 15f && a[2] <= 45f);
                assertTrue("inside its castle interval", a[0] > sp * (n - 1) && a[0] < sp * n);
            }
            assertArrayEquals("deterministic", ngh, CourseGenerator.avalanchePlan(seed, n, sp, DifficultyProfile.NGH.avalanches()));
            assertArrayEquals("deterministic", pun, CourseGenerator.avalanchePlan(seed, n, sp, DifficultyProfile.PUNISHER.avalanches()));
            if (n <= 8 || n > 10) assertEquals("NORMAL: none outside castles 9-10 (seed " + seed + ", castle " + n + ")", 0, nor.length);
            if (n == 9) n9 += nor.length; if (n == 10) n10 += nor.length;
            for (float[] a : nor) { assertEquals("NORMAL: two blocks", 2f, a[1], 0f); assertTrue(a[2] >= 30f && a[2] <= 90f); }
            assertTrue("PUNISHER: two or three a castle interval (" + pun.length + ")", pun.length >= 2 && pun.length <= 3);
            for (float[] a : pun) {
                assertTrue("PUNISHER: 4-5 blocks", a[1] == 4f || a[1] == 5f);
                assertTrue("cooldown 30-90 s", a[2] >= 30f && a[2] <= 90f);
                assertTrue("inside its castle interval", a[0] > sp * (n - 1) && a[0] < sp * n);
            }
        }
        assertTrue("castle 9 rare (" + n9 + " in 400 seeds)", n9 > 40 && n9 < 200);
        assertTrue("castle 10 a little more often than castle 9 (" + n10 + " vs " + n9 + ")", n10 > n9);
    }

    @Test public void aBlockKnocksYouAway() throws Exception {
        Tuning t = TestUtil.tuning();
        boolean hit = false;
        for (float ph = 0.05f; ph < 1f && !hit; ph += 0.1f) {
            Course c = stairs(t, 5, 45f, ph);
            Sim s = Sim.startOn(c, t, 0);
            InputState in = new InputState();
            for (int i = 0; i < (int) (10f / Sim.DT) && !hit; i++) { in.clear(); s.step(in); hit = (s.events & Sim.EV_HIT) != 0 && s.hitBy == c.hazards.get(0); s.events = 0; }
        }
        assertTrue("a climber standing still under an avalanche is hit by some seed", hit);
    }
}
