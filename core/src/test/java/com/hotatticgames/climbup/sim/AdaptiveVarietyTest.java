package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.nio.file.*;
import org.junit.Test;

/**
 * History-aware variety (owner 2026-10-27; PUNISHER): the memory penalises recent, frequent mechanics and forgets them with distance; it holds only
 * accepted placements (undoing modules forgets them); it never forbids anything; it changes PUNISHER picks, NORMAL's with a lighter strength (rules 13), and leaves NOT GONNA HAPPEN alone;
 * generation stays deterministic.
 */
public class AdaptiveVarietyTest {
    static Tuning tuning() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }

    @Test public void recentAndFrequentMechanicsArePenalisedAndThePenaltyFadesWithDistance() {
        VarietyMemory m = new VarietyMemory();
        assertEquals("an empty memory changes nothing", 1f, m.penalty(0, 50f), 0f);
        m.add(0, 100f, 10);
        float near = m.penalty(0, 102f), mid = m.penalty(0, 140f), far = m.penalty(0, 100f + VarietyMemory.SPAN + 1f);
        assertTrue("just placed: clearly less likely (" + near + ")", near < 0.6f);
        assertTrue("it fades with the height climbed since (" + near + " < " + mid + ")", near < mid && mid < 1f);
        assertEquals("beyond the span it is back to normal", 1f, far, 0f);
        assertEquals("other families are untouched", 1f, m.penalty(1, 102f), 0f);
        m.add(0, 101f, 11);
        assertTrue("more often: less likely still", m.penalty(0, 102f) < near);
        for (int i = 0; i < 20; i++) m.add(0, 101.5f, 12);
        assertEquals("never below the floor: nothing is forbidden", VarietyMemory.FLOOR, m.penalty(0, 102f), 1e-6f);
        assertEquals("a placement above the height asked about does not count", 1f, m.penalty(0, 99f), 0f);
    }

    @Test public void onlyAcceptedPlacementsCountAndUndoForgetsThem() {
        VarietyMemory m = new VarietyMemory();
        m.add(3, 10f, -1);           // carried from the previous slice
        m.add(3, 20f, 5);            // a module placed when the course had 5 elements
        m.add(3, 25f, 9);            // another, at 9
        assertEquals(3, m.size());
        m.forget(9);                 // undo back to 9 elements: the second module goes, its memory with it
        assertEquals(2, m.size());
        m.forget(0);                 // undo everything of this slice: the carried entry stays
        assertEquals(1, m.size());
        m.prune(10f + VarietyMemory.SPAN + 1f);
        assertEquals("and it leaves the memory once it is out of reach", 0, m.size());
    }

    /** {adaptive picks, shifted picks, ropes avoided} over the first slices of a mode, and the generated slices for a determinism check. */
    static int[] tally(Tuning t, long seed, int slices, StringBuilder dump) {
        int[] n = new int[3]; CourseGenerator.Framed f = null;
        for (int k = 0; k < slices; k++) {
            GenTrace tr = new GenTrace(); GenTrace.GEN.set(tr);
            try { f = CourseGenerator.chunkFramed(seed, k, f, t); } finally { GenTrace.GEN.remove(); }
            n[0] += tr.counts.getOrDefault("adaptive_picks", 0); n[1] += tr.counts.getOrDefault("adaptive_shift", 0); n[2] += tr.counts.getOrDefault("adaptive_rope_avoided", 0);
            if (dump != null) dump.append(CourseIO.toJson(f.c));
        }
        return n;
    }

    @Test public void punisherPicksChangeAndStayDeterministicNormalUsesALighterTouchAndNghIsUntouched() throws Exception {
        Tuning base = tuning();
        StringBuilder a = new StringBuilder(), b = new StringBuilder();
        int[] p = tally(base.hardCopy(1), 3L, 5, a);
        tally(base.hardCopy(1), 3L, 5, b);
        assertEquals("PUNISHER with the memory: the same seed gives the same slices", a.toString(), b.toString());
        assertTrue("the memory takes part in PUNISHER's picks (" + p[0] + ")", p[0] > 50);
        assertTrue("and changes some of them (" + p[1] + " of " + p[0] + ")", p[1] > 0);
        assertTrue("but not most of them: no forced alternation (" + p[1] + " of " + p[0] + ")", p[1] < p[0] / 2);
        int[] n = tally(base, 3L, 3, null), g = tally(base.hardCopy(2), 3L, 3, null);
        assertTrue("rules 13 (owner directive 2026-10-27): NORMAL consults it too, at a lighter strength (" + n[0] + ")", n[0] > 0 && DifficultyProfile.NORMAL.varietyStrength() < DifficultyProfile.PUNISHER.varietyStrength());
        assertEquals("NOT GONNA HAPPEN never consults it", 0, g[0]);
        System.out.println("adaptive: PUNISHER seed 3, 5 slices: " + p[0] + " picks, " + p[1] + " changed, " + p[2] + " ropes avoided");
    }
}
