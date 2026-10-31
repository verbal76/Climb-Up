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
        assertEquals("rules 21: one weight table (the shipped properties file overrides nothing)", Progression.POINTS, DifficultyScore.WEIGHTS);
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
     * Rules 21 closed loop on a model generator: castle n's category k comes out at bias_k x target x f_k^beta_k x (1 + noise), beta = the
     * category's response to its factor (terrain responds weakly). Returns the base-factor history [castle][k] and the measured totals.
     */
    static float[][][] loop(DifficultyProfile p, float[] bias, float noise, long seed, int castles, float[] switchBias, int switchAt) {
        float[] beta = {0.4f, 0.9f, 0.8f, 0.6f}; java.util.Random r = new java.util.Random(seed);
        float[][] base = new float[castles + 1][], meas = new float[castles + 1][]; float[] prev = null;
        for (int n = 1; n <= castles; n++) {
            float[] pl = Progression.plan(n, prev, p, null);
            float[] rec = new float[Progression.REC]; rec[0] = n; float[] b = n >= switchAt && switchBias != null ? switchBias : bias;
            for (int k = 0; k < 4; k++) rec[1 + k] = (float) (p.target(n, k) * b[k] * Math.pow(pl[k], beta[k]) * (1 + noise * (2 * r.nextFloat() - 1)));
            System.arraycopy(pl, 0, rec, Progression.R_BASE, 4); rec[Progression.R_PLAN] = pl[4]; System.arraycopy(pl, 5, rec, Progression.R_REQ, 4);
            base[n] = java.util.Arrays.copyOf(pl, 4); meas[n] = new float[]{Progression.total(rec, 1)}; prev = rec;
        }
        return new float[][][]{base, meas};
    }

    /** Rules 21: a persistent bias the factors can absorb is learned and held (no reset to 1), with no castle-to-castle alternation. */
    @Test public void aPersistentBiasIsLearnedAndHeldWithoutAlternation() {
        for (DifficultyProfile p : new DifficultyProfile[]{N, P, G}) for (float bv : new float[]{0.85f, 1.15f, 1.25f}) {
            float[][][] h = loop(p, new float[]{1f, bv, bv, 1f}, 0f, 1, 10, null, 0);
            for (int k = 1; k <= 2; k++) {
                int flips = 0; double last = 0;
                for (int n = 2; n <= 10; n++) { double d = h[0][n][k] - h[0][n - 1][k]; if (Math.abs(d) > 0.005) { if (last != 0 && Math.signum(d) != Math.signum(last)) flips++; last = d; } }
                assertTrue(p + " bias " + bv + " category " + k + ": the base factor does not alternate (" + flips + " reversals)", flips <= 1);
                double need = Math.pow(1.0 / bv, 1.0 / (k == 1 ? 0.9 : 0.8));
                float hi = k == Progression.H && p.capacityControl() ? Progression.CAP_BOUND : p.factorHi(k);
                if (need >= p.factorLo(k) && need <= hi) assertTrue(p + " bias " + bv + ": the needed factor " + need + " is kept, not reset to 1 (" + h[0][10][k] + ")",
                    Math.abs(h[0][10][k] - need) < Math.abs(1.0 - need) * 0.6 + 0.02);
            }
        }
    }

    /** Noisy castles (±6 %): the factors follow the trend instead of swinging with every castle (the Rule 20 two-castle oscillation). */
    @Test public void noiseDoesNotProduceATwoCastleOscillation() {
        for (DifficultyProfile p : new DifficultyProfile[]{N, P, G}) for (long seed = 1; seed <= 20; seed++) {
            float[][][] h = loop(p, new float[]{1.05f, 1.3f, 1.1f, 1.2f}, 0.06f, seed, 10, null, 0);
            int alt = 0;
            for (int n = 3; n <= 10; n++) {
                double d1 = h[0][n][1] - h[0][n - 1][1], d0 = h[0][n - 1][1] - h[0][n - 2][1];
                if (Math.abs(d1) > 0.03 && Math.abs(d0) > 0.03 && Math.signum(d1) != Math.signum(d0)) alt++;
            }
            assertTrue(p + " seed " + seed + ": hazard factor large reversals " + alt, alt <= 3);
        }
    }

    /** Anti-windup: a factor held at its bound leaves it in the very next castle once the generator changes. */
    @Test public void aBoundFactorLeavesTheBoundAsSoonAsItIsNoLongerNeeded() {
        for (DifficultyProfile p : new DifficultyProfile[]{N, P}) {
            float[][][] h = loop(p, new float[]{1f, 0.5f, 1f, 1f}, 0f, 1, 10, new float[]{1f, 1.6f, 1f, 1f}, 6);
            assertEquals(p + ": the hazard factor sat on its bound", p.factorHi(Progression.H), h[0][6][Progression.H], 1e-4f);
            assertTrue(p + ": and eases in the castle after the change (" + h[0][7][Progression.H] + ")", h[0][7][Progression.H] < p.factorHi(Progression.H) - 0.05f);
        }
    }

    /** A castle that overshoots is followed by a smaller rise, never a drop below it (the planned glide), and its factors ease. */
    @Test public void anOverHotCastleIsFollowedByASmallerRiseNotADrop() {
        for (DifficultyProfile p : new DifficultyProfile[]{N, P, G}) for (int n = 2; n <= 10; n++) {
            float[] prev = new float[Progression.REC]; prev[0] = n - 1;
            for (int k = 0; k < 4; k++) { prev[1 + k] = p.target(n - 1, k) * 1.12f; prev[Progression.R_BASE + k] = 1f; }
            float[] pl = Progression.plan(n, prev, p, null);
            assertTrue(p + " castle " + n + ": planned at least 1.5 % above the over-hot castle", pl[4] >= Progression.total(prev, 1) * 1.015f - 1e-2f);
            for (int k = 0; k < 4; k++) assertTrue("its factors ease (" + k + ")", pl[k] < 1f);
            for (int k = 0; k < 4; k++) prev[1 + k] = p.target(n - 1, k) * 0.85f;
            float[] up = Progression.plan(n, prev, p, null);
            assertTrue(p + " castle " + n + ": a short castle is caught up by at most 6 %", up[4] <= Progression.total(prev, 1) * 1.06f + 1e-2f);
        }
    }

    @Test public void theIntendedCurvesRiseAndPunisherEndsAboveNormal() {
        for (DifficultyProfile p : new DifficultyProfile[]{N, P, G}) {
            for (int n = 2; n <= 10; n++) assertTrue(p + " rises at castle " + n, p.targetTotal(n) > p.targetTotal(n - 1));
            for (int n = 1; n <= 10; n++) { float sum = 0; for (int k = 0; k < 4; k++) sum += p.target(n, k); assertEquals("categories add up to the total", p.targetTotal(n), sum, 0.01f * p.targetTotal(n)); }
            assertEquals("castles past 10 keep castle 10's target", p.targetTotal(10), p.targetTotal(14), 0f);
        }
        // Rules 21 (owner Rule 21 directive 2026-10-31): 3.3 % compounded a castle (+33.9 % castle 1 -> 10), PUNISHER 15 % above NORMAL and NGH
        // 15 % above PUNISHER at every castle - one linked system, never three independent curves.
        assertTrue("NOT GONNA HAPPEN is controlled", G.progression());
        for (DifficultyProfile p : new DifficultyProfile[]{N, P, G}) {
            for (int n = 2; n <= 10; n++) assertEquals(p + " grows 3.3 % at castle " + n, 1.033f, p.targetTotal(n) / p.targetTotal(n - 1), 1e-4f);
            assertEquals(p + " rises 33.9 % over the ten castles", Math.pow(1.033, 9), p.targetTotal(10) / p.targetTotal(1), 1e-4);
        }
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
        assertEquals("NORMAL/PUNISHER state layout (rules 21)", 3 + Progression.ACC + 4 + 9 + Progression.REC, n.toArray().length);
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
