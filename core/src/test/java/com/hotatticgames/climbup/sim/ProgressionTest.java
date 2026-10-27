package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Castle-to-castle progression (owner directive 2026-10-27, rules 13): the estimator uses the lane report's own weights and classes; the feedback
 * factors stay inside their bounds, cannot escalate on their own, ease off after a castle that came out too hard, and treat each category on its own;
 * the intended curves rise and PUNISHER ends above NORMAL; NOT GONNA HAPPEN has no controller; the state travels inside the slices, so a slice made
 * from a stored (decoded) slice is the same as one made in memory, and generation stays deterministic.
 */
public class ProgressionTest {
    static Tuning tuning() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }
    static final DifficultyProfile N = DifficultyProfile.NORMAL, P = DifficultyProfile.PUNISHER;
    static final float SP = 500f;

    /** A state whose castle n-1 measured {T, H, E, X} = scale x its targets (castle n not started). */
    static Progression after(DifficultyProfile p, int n, float[] scale) {
        Progression g = new Progression(); g.castle = n;
        float[] r = new float[Progression.REC]; r[0] = n - 1;
        for (int k = 0; k < 4; k++) r[1 + k] = p.target(n - 1, k) * scale[k];
        g.done[0] = r; g.doneN = 1; return g;
    }
    static float[] all(float s) { return new float[]{s, s, s, s}; }

    @Test public void theEstimatorUsesTheLaneReportsWeightsAndClasses() {
        for (Element.Type k : Element.Type.values()) {
            assertEquals("weight of " + k, DifficultyScore.points(k), Progression.points(k), 0f);
            assertEquals("enemy class of " + k, DifficultyScore.enemy(k), Progression.enemy(k));
            assertEquals("hazard class of " + k, DifficultyScore.hazard(k), Progression.hazard(k));
        }
        assertEquals(LaneReport.X_BONUS, Progression.X_BONUS, 0f); assertEquals(LaneReport.X_CAP, Progression.X_CAP, 0f);
        assertEquals(LaneReport.TERRAIN_DEMANDING, Progression.TERRAIN_DEMANDING, 0f); assertEquals(LaneReport.WINDOW, Progression.WINDOW, 0f);
        assertEquals(DifficultyScore.FULL, Progression.REP_FULL); assertEquals(DifficultyScore.TAIL, Progression.REP_TAIL, 0f);
    }

    @Test public void factorsStayInsideTheirBounds() {
        for (DifficultyProfile p : new DifficultyProfile[]{N, P}) for (int n = 2; n <= 11; n++) for (float s : new float[]{0f, 0.01f, 0.5f, 1f, 2f, 100f}) {
            float[] f = after(p, n, all(s)).factors(n, p, SP);
            for (int k = 0; k < 4; k++) {
                assertFalse(Float.isNaN(f[k]));
                assertTrue(p + " castle " + n + " x" + s + " factor " + k + " = " + f[k], f[k] >= p.factorLo(k) - 1e-6f && f[k] <= p.factorHi(k) + 1e-6f);
            }
        }
        assertTrue("enemies may be raised only a little (no enemy spam)", N.factorHi(Progression.E) <= 1.1f + 1e-6f && P.factorHi(Progression.E) <= 1.1f + 1e-6f);
    }

    @Test public void aHardCastleEasesTheNextAndAnEasyOneRaisesIt() {
        for (DifficultyProfile p : new DifficultyProfile[]{N, P}) for (int n = 2; n <= 10; n++) {
            float[] hard = after(p, n, all(1.3f)).factors(n, p, SP), easy = after(p, n, all(0.75f)).factors(n, p, SP), on = after(p, n, all(1f)).factors(n, p, SP);
            for (int k = 0; k < 4; k++) {
                assertTrue("a castle 30% too hard never makes the next one harder (" + k + ": " + hard[k] + ")", hard[k] < 1f);
                assertTrue("a castle 25% too easy raises the next one's selection (" + k + ": " + easy[k] + ")", easy[k] > 1f);
                assertEquals("on target: no correction", 1f, on[k], 1e-5f);
            }
        }
    }

    @Test public void eachCategoryIsControlledOnItsOwn() {
        for (int c = 0; c < 4; c++) {
            float[] s = all(1f); s[c] = 0.6f;
            float[] f = after(P, 5, s).factors(5, P, SP);
            for (int k = 0; k < 4; k++) if (k == c) assertTrue("the short category is raised", f[k] > 1f); else assertEquals("the others are left alone", 1f, f[k], 1e-5f);
        }
    }

    /**
     * A closed loop with a deliberately biased "generator" (each castle comes out at bias x target x (0.7 + 0.3 x factor), the bias drawn per castle
     * between 0.7 and 1.5): over 30 castles the factors stay inside their bounds and every castle that came out hard is followed by easing.
     */
    @Test public void theFeedbackCannotRunAway() {
        java.util.Random r = new java.util.Random(7);
        for (DifficultyProfile p : new DifficultyProfile[]{N, P}) for (int trial = 0; trial < 50; trial++) {
            Progression g = new Progression();
            for (int n = 1; n <= 30; n++) {
                float[] f = g.factors(n, p, SP);
                float bias = 0.7f + 0.8f * r.nextFloat();
                float[] rec = new float[Progression.REC]; rec[0] = n;
                for (int k = 0; k < 4; k++) rec[1 + k] = p.target(n, k) * bias * (0.7f + 0.3f * f[k]);
                g.castle = n + 1; if (g.doneN == Progression.KEEP) { System.arraycopy(g.done, 1, g.done, 0, Progression.KEEP - 1); g.doneN--; } g.done[g.doneN++] = rec;
                float[] next = g.factors(n + 1, p, SP);
                for (int k = 0; k < 4; k++) {
                    assertTrue(next[k] >= p.factorLo(k) - 1e-6f && next[k] <= p.factorHi(k) + 1e-6f);
                    if (rec[1 + k] > 1.05f * p.target(n, k)) assertTrue("after a castle that came out hard the next factor is below 1", next[k] < 1f);
                }
            }
        }
    }

    @Test public void theIntendedCurvesRiseAndPunisherEndsAboveNormal() {
        for (DifficultyProfile p : new DifficultyProfile[]{N, P}) {
            for (int n = 2; n <= 10; n++) assertTrue(p + " rises at castle " + n, p.targetTotal(n) > p.targetTotal(n - 1));
            for (int n = 1; n <= 10; n++) { float sum = 0; for (int k = 0; k < 4; k++) sum += p.target(n, k); assertEquals("categories add up to the total", p.targetTotal(n), sum, 0.01f * p.targetTotal(n)); }
            assertEquals("castles past 10 keep castle 10's target", p.targetTotal(10), p.targetTotal(14), 0f);
        }
        assertTrue("PUNISHER castle 1 sits at NORMAL castles 3-4 (owner)", P.targetTotal(1) >= N.targetTotal(3) && P.targetTotal(1) <= N.targetTotal(4));
        assertEquals("PUNISHER castle 10 about 20% above NORMAL castle 10 (owner, provisional)", 1.2f, P.targetTotal(10) / N.targetTotal(10), 0.01f);
        assertFalse("NOT GONNA HAPPEN is not balanced (no controller)", DifficultyProfile.NGH.progression());
        float[] f = after(DifficultyProfile.NGH, 5, all(0.5f)).factors(5, DifficultyProfile.NGH, SP);
        for (float v : f) assertEquals(1f, v, 0f);
    }

    @Test public void theStateTravelsInTheSliceAndAStoredSliceGivesTheSameNextSlice() throws Exception {
        Tuning base = tuning();
        for (Tuning t : new Tuning[]{base, base.hardCopy(1)}) {
            CourseGenerator.Framed f = null; List<byte[]> stored = new ArrayList<>();
            for (int k = 0; k < 9; k++) { f = CourseGenerator.chunkFramed(77L, k, f, t); stored.add(SliceCodec.encode(f)); assertNotNull("slice " + k + " carries the state", f.c.prog); }
            Progression g = Progression.of(f.c.prog);
            assertTrue("past the first castle the state has closed it (" + (f.yBase + f.c.get(f.c.routeSize() - 1).y) + " m)", f.yBase + f.c.get(f.c.routeSize() - 1).y < 500 || g.completedCount() >= 1);
            CourseGenerator.Framed mem = CourseGenerator.chunkFramed(77L, 9, f, t);
            CourseGenerator.Framed fromStore = CourseGenerator.chunkFramed(77L, 9, SliceCodec.decode(stored.get(8)), t);
            assertTrue("resume: the next slice from a stored slice is the same, state included", SliceCodec.same(mem.c, fromStore.c));
            CourseGenerator.Framed again = null; for (int k = 0; k < 9; k++) again = CourseGenerator.chunkFramed(77L, k, again, t);
            assertArrayEquals("deterministic", SliceCodec.encode(f), SliceCodec.encode(again));
        }
        CourseGenerator.Framed ngh = CourseGenerator.chunkFramed(77L, 0, null, base.hardCopy(2));
        assertNull("NOT GONNA HAPPEN slices carry no controller state", ngh.c.prog);
        assertTrue("a slice without state decodes without it", SliceCodec.same(ngh.c, SliceCodec.decode(SliceCodec.encode(ngh)).c));
    }
}
