package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/**
 * Rules 18 avalanche overhaul (owner Build 83 directive, 2026-10-29): PUNISHER and NOT GONNA HAPPEN avalanches of rigid spiked cubes with no warning,
 * and a trigger that leads a moving climber. A standing climber sets it off on the trigger deck; the faster they come at it, the further short of it
 * it fires (a launch: before the deck before it); it fires once whatever they do next; a running or launched climber cannot outrun it; the blocks
 * rebound, tumble and knock into each other; everything is deterministic and cleaned up.
 */
public class AvalancheOverhaulTest {
    /** A flat run of nine decks along +s; the avalanche's trigger deck is deck 3. */
    static Course course(Tuning t, int blocks, float phase, int skin, float rise) {
        Course c = new Course(0, t.circumference());
        for (int k = 0; k < 9; k++) { Element d = new Element(Element.Type.STATIC, 10f + 3.6f * k, rise * Math.max(0, k - 3), 3.4f); if (k == 0) d.checkpoint = true; c.add(d); }
        Element a = new Element(Element.Type.AVALANCHE, c.get(3).s, c.get(3).y, 3.4f);
        a.anchor = 3; a.len = blocks; a.period = 45f; a.phase = phase; a.skin = skin;
        c.hazards.add(a);
        return c;
    }

    /** How the climber comes at it: STAND on the trigger deck; STILL on the deck before it, not moving; ACCEL from rest 1.2 m into the deck before it,
     *  running; RUN at full speed from deck 0; LAUNCH through the air at 11 m/s from deck 0. */
    enum Approach { STAND, STILL, ACCEL, RUN, LAUNCH }

    static final class Run {
        Sim s; Course c; float arriveT = -1f, passT = -1f; int fires; String trace = "";
    }

    static Run run(Tuning t, Approach ap, int blocks, float phase, int skin, float seconds, boolean invuln) {
        Run r = new Run();
        Course c = course(t, blocks, phase, skin, 0f); r.c = c;
        int start = ap == Approach.STAND ? 3 : (ap == Approach.STILL || ap == Approach.ACCEL ? 2 : 0);
        Sim s = Sim.startOn(c, t, start); if (invuln) s.invuln = 1e9f; r.s = s;
        if (ap == Approach.ACCEL) s.s = c.get(2).s - 1.2f;
        if (ap == Approach.RUN) s.vx = t.runSpeed;
        if (ap == Approach.LAUNCH) { s.mode = Sim.Mode.AIR; s.onElem = -1; s.vx = 11f; s.vy = 5f; s.y += 0.5f; }
        InputState in = new InputState();
        float edge = c.get(3).s - c.get(3).halfW(), far = c.get(4).s + c.get(4).halfW();
        StringBuilder tr = new StringBuilder();
        for (int i = 0; i < (int) (seconds / Sim.DT); i++) {
            in.clear(); if (ap == Approach.ACCEL || ap == Approach.RUN || ap == Approach.LAUNCH) in.moveX = 1f;
            s.events = 0; s.step(in);
            if ((s.events & Sim.EV_AVL_WARN) != 0) r.fires++;
            if (r.arriveT < 0f && s.s >= edge) r.arriveT = s.time;
            if (r.passT < 0f && s.s >= far) r.passT = s.time;
            if (i % 12 == 0) for (int b = 0; b < Element.AVL_MAX; b++) { float[] k = s.avalancheBlock(0, c.hazards.get(0), b); if (k != null) tr.append(k[0]).append(',').append(k[1]).append(',').append(k[2]).append(';'); }
        }
        r.trace = tr.toString();
        return r;
    }

    @Test public void standingOnTheTriggerDeckSetsItOffAndStandingStillShortOfItNever() throws Exception {
        Tuning t = TestUtil.tuning();
        for (int skin : new int[]{0, 2, 1}) {
            Run on = run(t, Approach.STAND, skin == 0 ? 2 : 10, 0.37f, skin, 1f, true);
            assertEquals("skin " + skin + ": standing on its deck fires it", 1, on.fires);
            assertEquals("at once", 0f, on.s.avlTrigV, 0f);
            Run still = run(t, Approach.STILL, skin == 0 ? 2 : 10, 0.37f, skin, 6f, true);
            assertEquals("skin " + skin + ": standing still on the deck before never fires it", 0, still.fires);
        }
    }

    @Test public void theFasterTheClimberComesTheEarlierItFires() throws Exception {
        Tuning t = TestUtil.tuning();
        float fall = (float) Math.sqrt(2f * Element.AVL_DROP / Element.AVL_G);
        for (int skin : new int[]{2, 1, 0}) {
            Run acc = run(t, Approach.ACCEL, 10, 0.37f, skin, 4f, true), runr = run(t, Approach.RUN, 10, 0.37f, skin, 4f, true), lau = run(t, Approach.LAUNCH, 10, 0.37f, skin, 4f, true);
            for (Run r : new Run[]{acc, runr, lau}) assertEquals("fires exactly once", 1, r.fires);
            assertTrue("skin " + skin + ": the lead distance grows with speed (" + acc.s.avlTrigDist + " < " + runr.s.avlTrigDist + " < " + lau.s.avlTrigDist + ")",
                    acc.s.avlTrigDist < runr.s.avlTrigDist && runr.s.avlTrigDist < lau.s.avlTrigDist);
            assertTrue("running: substantially earlier, several metres short (" + runr.s.avlTrigDist + ")", runr.s.avlTrigDist > 4f);
            assertTrue("a launch sets it off before the deck before it (" + lau.s.avlTrigDist + " m short; that deck is " + runr.c.get(2).w + " wide)", lau.s.avlTrigDist > runr.c.get(2).w + 0.2f);
            // the lead is the blocks' fall time: they are down as the climber gets there, never long before
            for (Run r : new Run[]{runr, lau}) {
                float lead = r.arriveT - r.s.avlTrigT;
                assertTrue("skin " + skin + ": lead " + lead + " s against a fall of " + fall + " s", lead >= fall - 0.1f && lead <= fall + Element.AVL_LEAD0 + Element.AVL_LEAD_PAD + 2f * Sim.DT);
            }
        }
    }

    @Test public void aRunningOrLaunchedClimberCannotOutrunIt() throws Exception {
        Tuning t = TestUtil.tuning(); int seeds = 0;
        for (int skin : new int[]{2, 1}) for (Approach ap : new Approach[]{Approach.RUN, Approach.LAUNCH}) for (float ph = 0.05f; ph < 1f; ph += 0.15f) {
            Run r = run(t, ap, skin == 2 ? 10 : 12, ph, skin, 6f, true); seeds++;
            assertTrue(skin + "/" + ap + "/" + ph + ": blocks strike the decks (first at " + r.s.avlFirstImpactT + " s)", r.s.avlFirstImpactT > 0f);
            float firstImpactAbs = r.s.avlTrigT + r.s.avlFirstImpactT;
            assertTrue(skin + "/" + ap + "/" + ph + ": the first strike comes before the climber is past the deck after the trigger (" + firstImpactAbs + " vs " + r.passT + ")",
                    r.passT < 0f || firstImpactAbs < r.passT);
            assertTrue(skin + "/" + ap + "/" + ph + ": blocks come within 2.5 m of the climber (first at " + r.s.avlFirstNearT + " s)", r.s.avlFirstNearT >= 0f);
        }
        assertEquals(28, seeds);
    }

    @Test public void turningBackAndComingAgainNeverFiresItTwice() throws Exception {
        Tuning t = TestUtil.tuning();
        Course c = course(t, 10, 0.37f, 2, 0f);
        Sim s = Sim.startOn(c, t, 0); s.invuln = 1e9f; s.vx = t.runSpeed;
        InputState in = new InputState(); int fires = 0;
        for (int i = 0; i < (int) (8f / Sim.DT); i++) {
            in.clear(); float tt = i * Sim.DT; in.moveX = tt < 0.5f ? 1f : (tt < 0.9f ? -1f : 1f);          // toward it, away (staying on the first deck), toward it again and on across
            s.events = 0; s.step(in); if ((s.events & Sim.EV_AVL_WARN) != 0) fires++;
        }
        assertEquals("no fall (a respawn rightly resets encounters ahead of the checkpoint)", 0, s.falls);
        assertEquals("one activation", 1, fires);
        assertEquals(1, s.avlFired);
    }

    @Test public void blocksReboundTumbleAndKnockIntoEachOtherFarMoreThanNormalsGentleOnes() throws Exception {
        Tuning t = TestUtil.tuning();
        int reb = 0, tum = 0, kn = 0, ric = 0, peak = 0, nReb = 0;
        for (float ph = 0.05f; ph < 1f; ph += 0.1f) {
            for (int skin : new int[]{2, 1}) { Sim s = run(t, Approach.STAND, skin == 2 ? 10 : 12, ph, skin, 12f, true).s; reb += s.avlRebounds; tum += s.avlTumbles; kn += s.avlKnocks; ric += s.avlRicochets; peak = Math.max(peak, s.avlPeak); }
            nReb += run(t, Approach.STAND, 2, ph, 0, 12f, true).s.avlRebounds;
        }
        assertTrue("strong rebounds (" + reb + " over 20 avalanches)", reb >= 20 * 20);
        assertTrue("corner landings and glancing knocks set them tumbling (" + tum + ")", tum >= 20 * 10);
        assertTrue("they collide with each other (" + kn + " knocks)", kn >= 20 * 3);
        assertTrue("hard rebounds (" + ric + ")", ric >= 20 * 10);
        assertTrue("PUNISHER 10 / NGH 12 in the air at once (peak " + peak + ")", peak >= 10);
        assertTrue("NORMAL keeps its gentle blocks (" + nReb + " rebounds)", nReb < reb / 10);
    }

    @Test public void someBlocksRicochetBackDownTheRouteTowardTheClimber() throws Exception {
        Tuning t = TestUtil.tuning(); int back = 0;
        for (float ph = 0.05f; ph < 1f; ph += 0.1f) {
            Run r = run(t, Approach.RUN, 10, ph, 2, 3f, true);
            for (int b = 0; b < Element.AVL_MAX; b++) { float[] k = r.s.avalancheBlock(0, r.c.hazards.get(0), b); if (k != null && k[3] == 1f && k[0] < r.c.get(3).s) back++; }
        }
        assertTrue("blocks end up back down the route, toward where the climber came from (" + back + ")", back > 10);
    }

    @Test public void theSameInputsGiveTheSameAvalancheAndACopyCarriesOnExactly() throws Exception {
        Tuning t = TestUtil.tuning();
        for (int skin : new int[]{2, 1}) {
            Run a = run(t, Approach.RUN, 10, 0.37f, skin, 8f, true), b = run(t, Approach.RUN, 10, 0.37f, skin, 8f, true);
            assertEquals("skin " + skin + ": deterministic", a.trace, b.trace);
            assertNotEquals("another seed: other paths", a.trace, run(t, Approach.RUN, 10, 0.61f, skin, 8f, true).trace);
        }
        Course c = course(t, 12, 0.37f, 1, 0f);
        Sim s = Sim.startOn(c, t, 3); s.invuln = 1e9f; InputState in = new InputState();
        for (int i = 0; i < 90; i++) { in.clear(); s.step(in); }
        Sim k = s.copy();
        for (int i = 0; i < 240; i++) { in.clear(); s.step(in); k.step(in); }
        assertArrayEquals("a copy mid-avalanche carries on exactly", s.avl, k.avl, 0f);
    }

    @Test public void everyBlockIsCleanedUpAndNonePassesThroughADeck() throws Exception {
        Tuning t = TestUtil.tuning();
        for (int skin : new int[]{2, 1}) for (float ph = 0.05f; ph < 1f; ph += 0.15f) {
            Course c = course(t, 12, ph, skin, 1.2f);
            Sim s = Sim.startOn(c, t, 3); s.invuln = 1e9f; InputState in = new InputState();
            float[][] prev = new float[Element.AVL_MAX][]; float end = -1f;
            for (int i = 0; i < (int) ((Element.AVL_LIFE + 2f) / Sim.DT); i++) {
                in.clear(); s.step(in);
                for (int b = 0; b < Element.AVL_MAX; b++) {
                    float[] k = s.avalancheBlock(0, c.hazards.get(0), b);
                    if (k == null || k[3] != 1f) { prev[b] = null; continue; }
                    if (prev[b] != null) for (int j = 0; j < c.size(); j++) {
                        Element d = c.get(j); float hw = d.w / 2f - 0.05f;
                        if (Math.abs(prev[b][0] - d.s) < hw && Math.abs(k[0] - d.s) < hw && prev[b][1] >= d.y)
                            assertTrue("skin " + skin + " block " + b + " passed through deck " + j + " (" + prev[b][1] + " -> " + k[1] + ")", k[1] > d.y - Element.AVL_SLAB);
                    }
                    prev[b] = k;
                }
                if (end < 0f && s.avalancheState(0) == 2) end = s.time;
            }
            assertTrue("skin " + skin + " ph " + ph + ": all cleaned up (ended " + end + ")", end > 0f && end <= Element.AVL_LIFE + 0.5f);
            for (int b = 0; b < Element.AVL_MAX; b++) assertNull(s.avalancheBlock(0, c.hazards.get(0), b));
        }
    }

    @Test public void twelveBlocksStayCheapToSimulate() throws Exception {
        Tuning t = TestUtil.tuning();
        run(t, Approach.STAND, 12, 0.37f, 1, 4f, true);          // (warm up)
        long t0 = System.nanoTime(); int steps = (int) (8f / Sim.DT) * 5;
        for (float ph : new float[]{0.1f, 0.3f, 0.5f, 0.7f, 0.9f}) run(t, Approach.STAND, 12, ph, 1, 8f, true);
        float us = (System.nanoTime() - t0) / 1000f / steps;
        System.out.println("AVALANCHE PERF: " + us + " us per simulation step with a 12-block avalanche (whole Sim.step)");
        assertTrue("a 12-block avalanche step costs " + us + " us (budget 1,000 us on a desktop)", us < 1000f);
    }

    @Test public void aSaveFromRules14To17KeepsItsAvalanches() throws Exception {
        int w17 = 2 + 7 * Sim.AVL_B;
        float[] old = new float[2 * w17];
        for (int i = 0; i < old.length; i++) old[i] = i + 1;
        float[] w = RunSnapshot.widenAvl(old, 2, w17);
        assertEquals(2 * Sim.AVL_W, w.length);
        for (int i = 0; i < w17; i++) { assertEquals(old[i], w[i], 0f); assertEquals(old[w17 + i], w[Sim.AVL_W + i], 0f); }
        for (int i = w17; i < Sim.AVL_W; i++) assertEquals("the new block slots are empty", 0f, w[i], 0f);
    }

    @Test public void thePlanDoublesTheBlocksNotTheAvalanches() throws Exception {
        Tuning t = TestUtil.tuning(); float sp = t.castleSpacing;
        int pCount = 0, pSlices = 0; java.util.Map<Integer, Integer> pb = new java.util.TreeMap<>(), nb = new java.util.TreeMap<>();
        for (long seed = 1; seed <= 300; seed++) for (int n = 1; n <= 10; n++) {
            float[][] pun = CourseGenerator.avalanchePlan(seed, n, sp, DifficultyProfile.PUNISHER.avalanches());
            pCount += pun.length; pSlices++;
            for (float[] a : pun) pb.merge(Math.round(a[1]), 1, Integer::sum);
            for (float[] a : CourseGenerator.avalanchePlan(seed, n, sp, DifficultyProfile.NGH.avalanches())) nb.merge(Math.round(a[1]), 1, Integer::sum);
            for (float[] a : CourseGenerator.avalanchePlan(seed, n, sp, DifficultyProfile.NORMAL.avalanches())) assertEquals("NORMAL: two blocks", 2f, a[1], 0f);
        }
        assertEquals("PUNISHER 8-10", java.util.Set.of(8, 9, 10), pb.keySet());
        assertEquals("NGH 9-12", java.util.Set.of(9, 10, 11, 12), nb.keySet());
        float perCastle = pCount / (float) pSlices;
        assertTrue("PUNISHER still two or three avalanches a castle (" + perCastle + ")", perCastle >= 2f && perCastle <= 3f);
    }
}
