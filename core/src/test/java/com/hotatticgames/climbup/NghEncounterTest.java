package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/**
 * NOT GONNA HAPPEN encounters (rules 14, owner directive 2026-10-27): RED Enforcers only, two or three a castle interval from castle 1; avalanches
 * (rules 18, owner 2026-10-29) of 9-12 rigid spiked cubes, each with its own seeded size, bounce, friction, launch and spin, breaking loose at once and
 * AVL_GAP0-AVL_GAP1 s apart, colliding with the decks, each other and the body, never passing through a deck, always cleaned up, and the same every time.
 */
public class NghEncounterTest {
    private static Course stairs(Tuning t, int blocks, float phase) {
        Course c = new Course(0, t.circumference());
        for (int k = 0; k < 5; k++) { Element d = new Element(Element.Type.STATIC, 10f + 4.5f * k, 1.2f * k, 3.4f); if (k == 0) d.checkpoint = true; c.add(d); }
        Element a = new Element(Element.Type.AVALANCHE, 10f, 0f, 3.4f);
        a.anchor = 0; a.len = blocks; a.period = 30f; a.phase = phase; a.skin = 1;
        c.hazards.add(a);
        return c;
    }

    /** Runs one avalanche 16 s: {release time per block (-1 never), ...}, plus the Sim for its counters; checks no block passes through a deck. */
    private static Object[] run(Tuning t, int blocks, float phase, boolean invuln) {
        Course c = stairs(t, blocks, phase);
        Sim s = Sim.startOn(c, t, 0); if (invuln) s.invuln = 1e9f;
        InputState in = new InputState();
        float[] rel = new float[Element.AVL_MAX]; java.util.Arrays.fill(rel, -1f);
        float[][] prev = new float[Element.AVL_MAX][];
        StringBuilder trace = new StringBuilder();
        Element h = c.hazards.get(0);
        for (int i = 0; i < (int) (16f / Sim.DT); i++) {
            in.clear(); s.step(in);
            for (int b = 0; b < Element.AVL_MAX; b++) {
                float[] k = s.avalancheBlock(0, h, b);
                if (k == null || k[3] != 1f) { prev[b] = null; continue; }
                if (rel[b] < 0f) rel[b] = s.time;
                if (prev[b] != null) for (int j = 0; j < c.size(); j++) {          // no tunnelling: a block above a deck top cannot be below its slab one step later
                    Element d = c.get(j); float hw = d.w / 2f - 0.05f;
                    boolean over = Math.abs(prev[b][0] - d.s) < hw && Math.abs(k[0] - d.s) < hw;
                    if (over && prev[b][1] >= d.y) assertTrue("block " + b + " passed through deck " + j + " (" + prev[b][1] + " -> " + k[1] + ")", k[1] > d.y - Element.AVL_SLAB);
                }
                prev[b] = k;
                if (i % 10 == 0) trace.append(k[0]).append(',').append(k[1]).append(',').append(k[2]).append(';');
            }
        }
        return new Object[]{rel, s, trace.toString()};
    }

    @Test public void nineToTwelveBlocksAllBreakLooseAtOnceSpacedWithinTheirWindowAndAreCleanedUp() throws Exception {
        Tuning t = TestUtil.tuning();
        for (int n = 9; n <= 12; n++) for (float ph = 0.07f; ph < 1f; ph += 0.13f) {
            Object[] r = run(t, n, ph, true); float[] rel = (float[]) r[0]; Sim s = (Sim) r[1];
            for (int b = 0; b < Element.AVL_MAX; b++) assertEquals("block " + b + " of " + n + " released", b < n, rel[b] >= 0f);
            assertTrue("no warning wait: the first breaks loose at once (" + rel[0] + ")", rel[0] <= Element.AVL_LEAD0 + 2f * Sim.DT + 1e-4f);
            for (int b = 1; b < n; b++) {
                float gap = rel[b] - rel[b - 1];
                assertTrue(n + " blocks, gap " + b + " = " + gap, gap >= Element.AVL_GAP0 - Sim.DT - 1e-4f && gap <= Element.AVL_GAP1 + Sim.DT + 1e-4f);
            }
            assertEquals("all " + n + " counted", n, s.avlReleased);
            assertEquals("and all cleaned up", 2, s.avalancheState(0));
        }
    }

    @Test public void everyBlockHasItsOwnSizeAndMotionAndTheWholeThingIsDeterministic() throws Exception {
        Tuning t = TestUtil.tuning();
        Object[] a = run(t, 12, 0.37f, true), b = run(t, 12, 0.37f, true);
        assertEquals("same seed, same inputs: the same avalanche", a[2], b[2]);
        assertNotEquals("another seed: other paths", a[2], run(t, 12, 0.61f, true)[2]);
        Element e = stairs(t, 12, 0.37f).hazards.get(0);
        java.util.Set<Float> radii = new java.util.HashSet<>();
        for (int k = 0; k < 12; k++) { float R = Sim.avlRadius(e, k); radii.add(R); assertTrue(R >= Element.AVL_CR0 && R <= Element.AVL_CR0 + Element.AVL_CR1); }
        assertTrue("sizes differ between blocks (" + radii + ")", radii.size() >= 10);
        int ric = 0, knocks = 0, peak = 0;
        for (float ph = 0.05f; ph < 1f; ph += 0.1f) { Sim s = (Sim) run(t, 12, ph, true)[1]; ric += s.avlRicochets; knocks += s.avlKnocks; peak = Math.max(peak, s.avlPeak); }
        assertTrue("hard hits ricochet (" + ric + ")", ric > 0);
        assertTrue("blocks knock into each other (" + knocks + ")", knocks > 0);
        assertTrue("several are in the air at once (peak " + peak + ")", peak >= 6);
        Element p = new Element(Element.Type.AVALANCHE, 0f, 0f, 3f);
        assertEquals("NORMAL and PUNISHER blocks keep the one radius", Element.AVL_R, Sim.avlRadius(p, 3), 0f);
    }

    @Test public void aBlockKnocksYouAway() throws Exception {
        Tuning t = TestUtil.tuning(); int hits = 0;
        for (float ph = 0.05f; ph < 1f; ph += 0.05f) hits += ((Sim) run(t, 12, ph, false)[1]).avlHits;
        assertTrue("a block that reaches the body knocks it away (" + hits + " hits)", hits > 0);
    }

    @Test public void aSaveFromBeforeRules14KeepsItsAvalanches() throws Exception {
        float[] old = new float[2 * (2 + 5 * Sim.AVL_B)];
        for (int i = 0; i < old.length; i++) old[i] = i + 1;
        float[] w = RunSnapshot.widenAvl(old, 2);
        assertEquals(2 * Sim.AVL_W, w.length);
        for (int i = 0; i < 2 + 5 * Sim.AVL_B; i++) { assertEquals(old[i], w[i], 0f); assertEquals(old[2 + 5 * Sim.AVL_B + i], w[Sim.AVL_W + i], 0f); }
        for (int i = 2 + 5 * Sim.AVL_B; i < Sim.AVL_W; i++) assertEquals("the new block slots are empty", 0f, w[i], 0f);
    }

    @Test public void nghEnforcersAreRedOnlyTwoOrThreeFromCastleOne() throws Exception {
        Tuning t = TestUtil.tuning(); float sp = t.castleSpacing; int three = 0;
        for (long seed = 1; seed <= 300; seed++) for (int n = 1; n <= 12; n++) {
            float[][] g = CourseGenerator.nghEnforcerPlan(seed, n, sp);
            assertArrayEquals("deterministic", g, CourseGenerator.nghEnforcerPlan(seed, n, sp));
            assertTrue("two or three (" + g.length + ")", g.length >= 2 && g.length <= 3);
            if (g.length == 3) three++;
            for (float[] a : g) {
                assertEquals("RED only", 2f, a[1], 0f);
                assertTrue("3-5 decks", a[2] >= 3f && a[2] <= 5f);
                assertTrue("inside its castle interval", a[0] > sp * (n - 1) && a[0] < sp * n);
            }
        }
        assertTrue("three sometimes, not always (" + three + " of 3600)", three > 300 && three < 2400);
        assertTrue(DifficultyProfile.NGH.enforcers()); assertTrue(DifficultyProfile.PUNISHER.enforcers()); assertFalse("NORMAL stays Enforcer-free", DifficultyProfile.NORMAL.enforcers());
    }

    /**
     * Round 1 finding (NGH seed 1 slice 10 on 27603e4): an avalanche proven for one arrival time failed the whole-slice solver when the climb arrived later.
     * Every NGH avalanche the generator keeps is clean for each of the extra arrival delays, and seed 1 slice 10 solves end to end.
     */
    @Test public void nghAvalanchesHoldForEveryArrivalTimeAndSeedOneSliceTenSolves() throws Exception {
        Tuning t = TestUtil.tuning().hardCopy(2);
        CourseGenerator.Framed f = null, s10 = null; int checked = 0;
        for (int k = 0; k <= 10 || (checked == 0 && k <= 30); k++) {          // (rules 18: avalanches are rarer early on; look on until one is placed)
            f = CourseGenerator.chunkFramed(1L, k, f, t);
            if (k == 10) s10 = f;
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (Element h : f.c.hazards) if (h.type == Element.Type.AVALANCHE && seen.add(h.anchor)) {
                checked++;
                int from = Math.max(0, h.anchor - 2);          // (rules 18: the generator proves it from two decks before the trigger)
                for (float w : new float[]{0f, 0.7f, 1.6f, 2.9f}) assertTrue("slice " + k + " avalanche on deck " + h.anchor + " with the climber " + w + " s later", Autopilot.chainClean(f.c, t, from, h.anchor + 4, w));
            }
        }
        assertTrue("avalanches were checked (" + checked + ")", checked > 0);
        Autopilot.Report r = Autopilot.run(s10.c, t, 4000f);
        assertTrue("NGH seed 1 slice 10 solves (link " + r.failedLink + ": " + r.failInfo + ")", r.completed);
    }

    /** Generated NGH slices: every Enforcer red, every avalanche of 9-12 blocks (rules 18) marked for the per-block physics; and generation is deterministic. */
    @Test public void generatedNghSlicesCarryRedEnforcersAndChaoticAvalanches() throws Exception {
        Tuning t = TestUtil.tuning().hardCopy(2);
        int enf = 0, avl = 0; StringBuilder a = new StringBuilder(), b = new StringBuilder();
        CourseGenerator.Framed f = null, g = null;
        for (int k = 0; k < 9; k++) {
            f = CourseGenerator.chunkFramed(5L, k, f, t); g = CourseGenerator.chunkFramed(5L, k, g, t);
            a.append(java.util.Arrays.hashCode(SliceCodec.encode(f))); b.append(java.util.Arrays.hashCode(SliceCodec.encode(g)));
            for (Element h : f.c.hazards) {
                if (h.type == Element.Type.ENFORCER) { enf++; assertEquals("NGH Enforcers are red", 2, h.skin); }
                if (h.type == Element.Type.AVALANCHE) { avl++; assertEquals("NGH avalanche physics", 1, h.skin); assertTrue(h.len >= 9f && h.len <= 12f); assertTrue(h.period >= 15f && h.period <= 45f); }
            }
        }
        assertEquals("deterministic", a.toString(), b.toString());
        assertTrue("red Enforcers are placed (" + enf + ")", enf > 0);
        assertTrue("avalanches are placed (" + avl + ")", avl > 0);
    }
}
