package com.hotatticgames.climbup.sim;

import static org.junit.Assert.*;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Castle-to-castle progression (owner directive 2026-10-27, rules 13): the estimator uses the lane report's own weights and classes; the feedback
 * factors stay inside their bounds, cannot escalate on their own, ease off after a castle that came out too hard, and treat each category on its own;
 * the intended curves rise and PUNISHER ends above NORMAL, NOT GONNA HAPPEN (rules 14) above PUNISHER; the state travels inside the slices, so a slice made
 * from a stored (decoded) slice is the same as one made in memory, and generation stays deterministic.
 */
public class ProgressionTest {
    static Tuning tuning() throws Exception { return Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); }
    static final DifficultyProfile N = DifficultyProfile.NORMAL, P = DifficultyProfile.PUNISHER, G = DifficultyProfile.NGH;
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
        for (DifficultyProfile p : new DifficultyProfile[]{N, P, G}) for (int n = 2; n <= 11; n++) for (float s : new float[]{0f, 0.01f, 0.5f, 1f, 2f, 100f}) {
            float[] f = after(p, n, all(s)).factors(n, p, SP);
            for (int k = 0; k < 4; k++) {
                assertFalse(Float.isNaN(f[k]));
                float hi = k == Progression.H && p.capacityControl() ? Progression.CAP_BOUND : p.factorHi(k);          // NGH hazards: no fixed ceiling, only the computational bound (owner amendment)
                assertTrue(p + " castle " + n + " x" + s + " factor " + k + " = " + f[k], f[k] >= p.factorLo(k) - 1e-6f && f[k] <= hi + 1e-6f);
            }
        }
        assertTrue("enemies may be raised only a little (no enemy spam)", N.factorHi(Progression.E) <= 1.1f + 1e-6f && P.factorHi(Progression.E) <= 1.1f + 1e-6f);
    }

    @Test public void aHardCastleEasesTheNextAndAnEasyOneRaisesIt() {
        for (DifficultyProfile p : new DifficultyProfile[]{N, P, G}) for (int n = 2; n <= 10; n++) {
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
        for (DifficultyProfile p : new DifficultyProfile[]{N, P, G}) for (int trial = 0; trial < 50; trial++) {
            Progression g = new Progression();
            for (int n = 1; n <= 30; n++) {
                float[] f = g.factors(n, p, SP);
                float bias = 0.7f + 0.8f * r.nextFloat();
                float[] rec = new float[Progression.REC]; rec[0] = n;
                for (int k = 0; k < 4; k++) rec[1 + k] = p.target(n, k) * bias * (0.7f + 0.3f * f[k]);
                g.castle = n + 1; if (g.doneN == Progression.KEEP) { System.arraycopy(g.done, 1, g.done, 0, Progression.KEEP - 1); g.doneN--; } g.done[g.doneN++] = rec;
                float[] next = g.factors(n + 1, p, SP);
                for (int k = 0; k < 4; k++) {
                    assertTrue(next[k] >= p.factorLo(k) - 1e-6f && next[k] <= (k == Progression.H && p.capacityControl() ? Progression.CAP_BOUND : p.factorHi(k)) + 1e-6f);
                    if (rec[1 + k] > 1.05f * p.target(n, k)) assertTrue("after a castle that came out hard the next factor is below 1", next[k] < 1f);
                }
            }
        }
    }

    @Test public void theIntendedCurvesRiseAndPunisherEndsAboveNormal() {
        for (DifficultyProfile p : new DifficultyProfile[]{N, P, G}) {
            for (int n = 2; n <= 10; n++) assertTrue(p + " rises at castle " + n, p.targetTotal(n) > p.targetTotal(n - 1));
            for (int n = 1; n <= 10; n++) { float sum = 0; for (int k = 0; k < 4; k++) sum += p.target(n, k); assertEquals("categories add up to the total", p.targetTotal(n), sum, 0.01f * p.targetTotal(n)); }
            assertEquals("castles past 10 keep castle 10's target", p.targetTotal(10), p.targetTotal(14), 0f);
        }
        // The 30/15 Split (owner design principle, 2026-10-31; rules 20): every mode rises about 30 % from castle 1 to castle 10, and PUNISHER sits
        // 15 % above NORMAL and NGH 15 % above PUNISHER at every castle, compounding - one linked system, never three independent curves.
        assertTrue("NOT GONNA HAPPEN is controlled", G.progression());
        for (DifficultyProfile p : new DifficultyProfile[]{N, P, G}) assertEquals(p + " rises 30 % over the ten castles", 1.30f, p.targetTotal(10) / p.targetTotal(1), 1e-4f);
        for (int n = 1; n <= 10; n++) {
            assertEquals("PUNISHER 15 % above NORMAL at castle " + n, 1.15f, P.targetTotal(n) / N.targetTotal(n), 1e-4f);
            assertEquals("NGH 15 % above PUNISHER at castle " + n, 1.15f, G.targetTotal(n) / P.targetTotal(n), 1e-4f);
        }
        assertEquals("NORMAL's castle 1 is the anchor the harder modes derive from", DifficultyProfile.NORMAL_CASTLE1, N.targetTotal(1), 1e-3f);
    }

    /** A state whose castle n-1 measured hazards at {@code hScale} of target, was built with hazard factor {@code fPrev}, and placed hazards at {@code acc}. */
    static Progression capState(int n, float hScale, float fPrev, float acc) {
        Progression g = after(G, n, new float[]{1f, hScale, 1f, 1f});
        g.done[0][1 + Progression.ACC + Progression.H] = fPrev;
        g.cap = true; for (int q = 0; q < Progression.CAP_K; q++) { g.capPrev[3 * q] = 100; g.capPrev[3 * q + 1] = 100 * acc; g.capPrev[3 * q + 2] = 150 * acc; }
        return g;
    }

    /** Owner amendment 2026-10-27: NGH's hazard factor has no fixed ceiling; it escalates one bounded step a castle while hazards are being placed. */
    @Test public void nghHazardsEscalatePastTheOldCeilingWhileTheyArePlaced() {
        float f = capState(8, 0.5f, 1.35f, 0.7f).factors(8, G, SP)[Progression.H];
        assertEquals("one step above the previous castle's 1.35", 1.35f + Progression.CAP_STEP, f, 1e-5f);
        float[] hc = capState(8, 0.5f, 1.35f, 0.7f).hazardControlFor(8, G, SP);
        assertEquals("constrained by the escalation rate", Progression.HC_RATE, (int) hc[2]);
        assertTrue("and the request is recorded above what was applied (" + hc[0] + ")", hc[0] > hc[1]);
        float[] big = capState(9, 0.5f, 2.9f, 0.7f).hazardControl(9, G, 5f);
        assertEquals("never past the computational bound", Progression.CAP_BOUND, big[1], 1e-5f);
        assertEquals(Progression.HC_BOUND, (int) big[2]);
        for (DifficultyProfile p : new DifficultyProfile[]{N, P}) {
            Progression q = after(p, 8, new float[]{1f, 0.5f, 1f, 1f}); q.done[0][1 + Progression.ACC + Progression.H] = 1.35f;
            assertEquals(p + " keeps its 1.35 ceiling", 1.35f, q.factors(8, p, SP)[Progression.H], 1e-6f);
        }
    }

    @Test public void nghDoesNotEscalateIntoPlacementResistance() {
        Progression g = capState(8, 0.5f, 1.6f, 0.3f);
        assertEquals("held at the previous castle's factor while acceptance is low", 1.6f, g.factors(8, G, SP)[Progression.H], 1e-5f);
        assertEquals(Progression.HC_ACCEPTANCE, (int) g.hazardControlFor(8, G, SP)[2]);
        Progression ok = capState(8, 1.0f, 1.6f, 0.3f);
        assertEquals("on target: no correction requested, none withheld", Progression.HC_NONE, (int) ok.hazardControlFor(8, G, SP)[2]);
    }

    @Test public void kindsThatPlaceAreDrawnMoreAndKindsThatAreRefusedLess() {
        Progression g = new Progression(); g.cap = true;
        g.capPrev[0] = 100; g.capPrev[1] = 80; g.capPrev[2] = 160;          // hazard rows: placed, 1.6 points an attempt
        g.capPrev[6] = 100; g.capPrev[7] = 8; g.capPrev[8] = 12;            // mortar rows: mostly refused
        float[] m = g.capMultipliers(null);
        assertTrue("rows that place are drawn more (" + m[0] + ")", m[0] > 1f);
        assertTrue("refused mortar rows less (" + m[2] + ")", m[2] < 1f && m[2] >= Progression.CAP_MULT_LO);
        for (float v : m) assertTrue(v >= Progression.CAP_MULT_LO && v <= Progression.CAP_MULT_HI);
    }

    @Test public void capacityStateTravelsInTheSliceOnlyForNgh() {
        Progression g = capState(5, 0.6f, 1.5f, 0.6f); g.hcNow[0] = 1.9f; g.hcNow[1] = 1.75f; g.hcNow[2] = Progression.HC_RATE;
        Progression back = Progression.of(g.toArray());
        assertArrayEquals(g.toArray(), back.toArray(), 0f);
        assertTrue(back.hasCapacity());
        Progression n = after(N, 5, all(1f));
        assertEquals("NORMAL/PUNISHER state keeps its rules-13 layout", 3 + Progression.ACC + 4 + Progression.REC, n.toArray().length);
        assertFalse(Progression.of(n.toArray()).hasCapacity());
    }

    @Test public void theStateTravelsInTheSliceAndAStoredSliceGivesTheSameNextSlice() throws Exception {
        Tuning base = tuning();
        for (Tuning t : new Tuning[]{base, base.hardCopy(1), base.hardCopy(2)}) {
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
        assertNotNull("NOT GONNA HAPPEN slices carry the controller state (rules 14)", ngh.c.prog);
        assertTrue("and decode with it", SliceCodec.same(ngh.c, SliceCodec.decode(SliceCodec.encode(ngh)).c));
    }
}
