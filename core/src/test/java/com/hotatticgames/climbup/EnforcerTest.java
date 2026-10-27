package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.util.*;
import org.junit.Test;

/**
 * PUNISHER Enforcer robots (owner directive 2026-10-27): PUNISHER only, at most two encounters per castle interval, 3-5 decks each, yellow early,
 * orange from the middle levels, red late; no crab, bee, skull or blob deck crowds an encounter; every encounter is proven passable with the robot
 * active; the robot chases, kicks, and leaves on its own; its behaviour is deterministic.
 */
public class EnforcerTest {
    private static final long[] SEEDS = {1, 2};
    private static final int SLICES = 14;          // about 900 m: castle intervals 1 and 2

    /** The plan per section: one or two encounters, inside the section, 3-5 decks, tiers by level, fixed by the seed. */
    @Test public void thePlanKeepsItsLimitsAndTiersRiseWithTheClimb() throws Exception {
        Tuning t = TestUtil.tuning(); float sp = t.castleSpacing, shift = DifficultyProfile.PUNISHER.curveShift;
        int[][] tiers = new int[11][3];
        for (long seed = 1; seed <= 300; seed++) for (int n = 1; n <= 10; n++) {
            float[][] p = CourseGenerator.enforcerPlan(seed, n, sp, shift);
            assertArrayEquals("deterministic", p, CourseGenerator.enforcerPlan(seed, n, sp, shift));
            assertTrue("one or two per castle interval (" + p.length + ")", p.length >= 1 && p.length <= 2);
            float lv = (sp * (n - 0.5f) + shift) / (10f * sp);
            for (float[] e : p) {
                assertTrue("inside section " + n + ": " + e[0], e[0] > sp * (n - 1) && e[0] < sp * n);
                assertTrue("3-5 decks: " + e[2], e[2] >= 3 && e[2] <= 5);
                int tier = (int) e[1];
                if (lv <= 0.5f) assertEquals("yellow only early (section " + n + ")", 0, tier);
                if (lv <= 0.85f) assertTrue("no red before the late levels (section " + n + ")", tier < 2);
                tiers[n][tier]++;
            }
        }
        assertTrue("orange appears in the middle", tiers[4][1] > 0);
        assertTrue("red appears late", tiers[9][2] + tiers[10][2] > 0);
        assertTrue("red is commonest at the top", tiers[10][2] > tiers[8][2]);
    }

    @Test public void normalNeverHasOne() throws Exception {
        Tuning t = TestUtil.tuning();
        for (long seed : SEEDS) {
            CourseGenerator.Framed f = null;
            for (int k = 0; k < 6; k++) {
                f = CourseGenerator.chunkFramed(seed, k, f, t);
                for (Element h : f.c.hazards) assertNotEquals("NORMAL seed " + seed + " slice " + k, Element.Type.ENFORCER, h.type);
            }
        }
    }

    @Test public void punisherEncountersAreBoundedUncluttedAndFair() throws Exception {
        Tuning t = TestUtil.tuning().hardCopy(1);
        int placed = 0;
        for (long seed : SEEDS) {
            Map<Integer, Integer> perSection = new HashMap<>();
            CourseGenerator.Framed f = null;
            for (int k = 0; k < SLICES; k++) {
                f = CourseGenerator.chunkFramed(seed, k, f, t);
                Course c = f.c;
                for (Element h : c.hazards) {
                    if (h.type != Element.Type.ENFORCER) continue;
                    placed++;
                    String at = "seed " + seed + " slice " + k + " robot on " + h.anchor;
                    int len = Math.round(h.len);
                    assertTrue(at + ": 3-5 decks (" + len + ")", len >= 3 && len <= 5);
                    assertTrue(at + ": tier " + h.skin, h.skin >= 0 && h.skin <= 2);
                    assertTrue(at + ": its decks and exit deck are on the route", h.anchor >= 1 && h.anchor + len < c.routeSize());
                    for (int j = h.anchor; j <= h.anchor + len; j++) assertEquals(at + ": plain decks", Element.Type.STATIC, c.get(j).type);
                    int sec = (int) Math.floor((f.yBase + c.get(h.anchor).y) / t.castleSpacing);
                    perSection.merge(sec, 1, Integer::sum);
                    for (Element o : c.hazards) {
                        if (o == h || o.anchor < 0) continue;
                        boolean nuisance = o.type == Element.Type.CRAB || o.type == Element.Type.BEE || o.type == Element.Type.SKULL || o.type == Element.Type.BLOB;
                        assertFalse(at + ": a " + o.type + " on platform " + o.anchor + " crowds the encounter", nuisance && o.anchor >= h.anchor - 1 && o.anchor <= h.anchor + len + 1);
                        assertFalse(at + ": a second robot overlaps it", o.type == Element.Type.ENFORCER && Math.abs(o.anchor - h.anchor) <= len + 1);
                    }
                    assertTrue(at + ": a climber gets through clean with the robot active", Autopilot.chainClean(c, t, h.anchor - 1, h.anchor + len));
                }
            }
            for (Map.Entry<Integer, Integer> e : perSection.entrySet()) assertTrue("seed " + seed + " section " + (e.getKey() + 1) + ": " + e.getValue() + " encounters", e.getValue() <= 2);
        }
        System.out.println("enforcer encounters placed: " + placed);
        assertTrue("encounters actually appear in PUNISHER", placed >= 2);
    }

    /** A robot on a flat run of decks: it notices you, chases, kicks you off your feet, and leaves; the same inputs give the same robot. */
    @Test public void itChasesKicksAndLeavesDeterministically() throws Exception {
        Tuning t = TestUtil.tuning().hardCopy(1);
        float[] a = run(t, 0), b = run(t, 0);
        assertArrayEquals("deterministic", a, b, 0f);
        for (int tier = 0; tier <= 2; tier++) {
            float[] r = run(t, tier);
            assertTrue("tier " + tier + " starts its chase", r[0] > 0f);
            assertTrue("tier " + tier + " kicks you", r[1] > 0f);
            assertTrue("tier " + tier + " leaves within 20 s (state " + r[3] + ")", r[2] > 0f);
        }
        assertTrue("red notices faster than yellow", run(t, 2)[0] < run(t, 0)[0]);
    }

    /** {first alert time, first shove time, gone time, last state, then the robot's arc every 0.5 s}. */
    private static float[] run(Tuning t, int tier) throws Exception {
        Course c = TestUtil.flat(t);
        int a0 = 0;
        Element r = new Element(Element.Type.ENFORCER, c.get(a0).s, c.get(a0).y, c.get(a0).w);
        r.anchor = a0; r.len = 1; r.skin = tier;
        c.hazards.add(r);
        Sim s = Sim.startOn(c, t, a0);
        int hi = c.hazards.indexOf(r);
        InputState in = new InputState();
        float alert = -1f, shove = -1f, gone = -1f;
        List<Float> trace = new ArrayList<>();
        for (int i = 0; i < (int) (20f / Sim.DT); i++) {
            in.clear(); s.step(in);
            int ev = s.events; s.events = 0;
            if (alert < 0f && (ev & Sim.EV_ENF_ALERT) != 0) alert = s.time;
            if (shove < 0f && (ev & Sim.EV_SHOVE) != 0) shove = s.time;
            if (gone < 0f && s.featDone.length > hi && s.featDone[hi]) gone = s.time;
            if (i % 30 == 0) trace.add(s.enforcerS(hi, r));
        }
        float[] out = new float[4 + trace.size()];
        out[0] = alert; out[1] = shove; out[2] = gone; out[3] = s.enforcerState(hi);
        for (int i = 0; i < trace.size(); i++) out[4 + i] = trace.get(i);
        return out;
    }
}
