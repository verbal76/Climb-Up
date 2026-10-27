package com.hotatticgames.climbup.sim;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;

/**
 * Castle-to-castle difficulty feedback (owner directive 2026-10-27, rules 13): the generator measures every finished slice with the lane report's own
 * rules and carries the results up the tower inside the slices themselves ({@link Course#prog}, stored by {@link SliceCodec}), so a slice is still a
 * pure function of (seed, k, slice k-1) and streaming, resume and every device make the same decisions. No wall clock, no solver run, no player data.
 * <p>
 * Measurement (an estimate of LaneReport's model, in its units): TERRAIN from link geometry (T-GAP, T-LONG, T-LAND, moving/tilting/crumbling/wet
 * footing), HAZARDS from static hazards on route platforms, ENEMIES from enemies that reach a route platform (EncounterModel reach rules), both with the
 * report's repetition rule per 25 m window, INTERACTION from links that combine demanding terrain, hazards and enemies (capped per window), clubs as
 * MITIGATION. Differences from the report: windows are not merged across a slice boundary (the repetition and interaction caps apply per slice part of
 * a window), enemy reach is credited to the enemy's home window, and the solver is not run. LaneReport writes both side by side (castle_control.csv).
 * <p>
 * Control: each castle has an intended total and category split ({@link DifficultyProfile}); the next castle's selection factors come from the
 * relative error of the castle before it, blended with the castle in progress once a third of it is built, through a fixed gain and fixed bounds. The
 * factors depend on the latest measurements only (no accumulated integral), so they cannot run away, and a castle that came out too hard makes the
 * next one ease off instead of escalating.
 */
public final class Progression {
    /** Categories. */
    public static final int T = 0, H = 1, E = 2, X = 3, M = 4;
    /** Per-castle accumulator: T, H, E, X, M, route links, challenge links, recovery platforms, highest absolute height measured. */
    static final int ACC = 9, A_LINKS = 5, A_CHALL = 6, A_REC = 7, A_TOP = 8;
    /** Completed castles kept (bounded state): castle number, the accumulator, the four factors in force. */
    static final int KEEP = 12, REC = 1 + ACC + 4;
    static final int FORMAT = 1;

    /** The lane report's weights ({@code DifficultyScore.WEIGHTS}, pinned equal by ProgressionTest). */
    static final EnumMap<Element.Type, Float> POINTS = new EnumMap<>(Element.Type.class);
    static {
        Object[][] d = {{Element.Type.CRAB, 1f}, {Element.Type.BLOB, 2f}, {Element.Type.SKULL, 3f}, {Element.Type.BEE, 2f}, {Element.Type.CLUB, -1f},
            {Element.Type.SAW_H, 2f}, {Element.Type.SAW_V, 2f}, {Element.Type.PENDULUM, 2f}, {Element.Type.CANNON, 1f}, {Element.Type.AIMED, 2f},
            {Element.Type.MORTAR, 2f}, {Element.Type.SPIKE_TRAP, 1f}, {Element.Type.SPIKE_BLOCK, 1f}, {Element.Type.SPIKE_DROP, 2f},
            {Element.Type.ENFORCER, 3f}, {Element.Type.AVALANCHE, 2f}};
        for (Object[] e : d) POINTS.put((Element.Type) e[0], (Float) e[1]);
    }
    static float points(Element.Type k) { Float v = POINTS.get(k); return v == null ? 0f : v; }
    static boolean enemy(Element.Type k) { return k == Element.Type.CRAB || k == Element.Type.BLOB || k == Element.Type.SKULL || k == Element.Type.BEE || k == Element.Type.ENFORCER; }
    static boolean hazard(Element.Type k) {
        switch (k) { case SAW_H: case SAW_V: case PENDULUM: case CANNON: case AIMED: case MORTAR: case SPIKE_TRAP: case SPIKE_BLOCK: case SPIKE_DROP: case AVALANCHE: return true; default: return false; }
    }
    static boolean demandingFooting(Element e) { return e.crumbles() || e.isMoving() || e.type == Element.Type.RAMP || e.type == Element.Type.SEESAW || e.type == Element.Type.SWING || e.wet(); }
    public static final float WINDOW = 25f, X_BONUS = 0.5f, X_CAP = 2f, TERRAIN_DEMANDING = 1f, REP_TAIL = 0.25f; static final int REP_FULL = 3;

    // ------------------------------------------------------------------ state
    /** Castle being measured (1-based) and its running totals. */
    int castle = 1; final float[] cur = new float[ACC];
    /** Completed castles, oldest first ({@link #REC} floats each), at most {@link #KEEP}. */
    final float[][] done = new float[KEEP][]; int doneN;
    /** The factors the latest slice was generated with (diagnostics; also stored with each completed castle). */
    final float[] lastF = {1f, 1f, 1f, 1f};

    public Progression copy() {
        Progression p = new Progression(); p.castle = castle; System.arraycopy(cur, 0, p.cur, 0, ACC);
        for (int i = 0; i < doneN; i++) p.done[i] = done[i].clone(); p.doneN = doneN; System.arraycopy(lastF, 0, p.lastF, 0, 4); return p;
    }
    public float[] toArray() {
        float[] a = new float[3 + ACC + 4 + doneN * REC]; int k = 0;
        a[k++] = FORMAT; a[k++] = castle; a[k++] = doneN;
        for (float v : cur) a[k++] = v; for (float v : lastF) a[k++] = v;
        for (int i = 0; i < doneN; i++) for (float v : done[i]) a[k++] = v;
        return a;
    }
    /** The state stored in a slice (null or unknown: a fresh climb, no feedback yet). */
    public static Progression of(float[] a) {
        Progression p = new Progression();
        if (a == null || a.length < 3 + ACC + 4 || (int) a[0] != FORMAT) return p;
        int k = 1; p.castle = (int) a[k++]; int n = (int) a[k++];
        for (int i = 0; i < ACC; i++) p.cur[i] = a[k++]; for (int i = 0; i < 4; i++) p.lastF[i] = a[k++];
        for (int i = 0; i < n && k + REC <= a.length; i++) { p.done[i] = new float[REC]; System.arraycopy(a, k, p.done[i], 0, REC); k += REC; p.doneN++; }
        return p;
    }
    /** The completed record of castle n (null if not kept). */
    float[] record(int n) { for (int i = 0; i < doneN; i++) if ((int) done[i][0] == n) return done[i]; return null; }
    public int completedCount() { return doneN; }
    public float[] completed(int i) { return done[i]; }
    public int castle() { return castle; }
    public float[] current() { return cur.clone(); }
    public float[] lastFactors() { return lastF.clone(); }
    public static float total(float[] acc, int off) { return acc[off + T] + acc[off + H] + acc[off + E] + acc[off + X] - acc[off + M]; }

    private void complete() {
        float[] r = new float[REC]; r[0] = castle; System.arraycopy(cur, 0, r, 1, ACC); System.arraycopy(lastF, 0, r, 1 + ACC, 4);
        if (doneN == KEEP) { System.arraycopy(done, 1, done, 0, KEEP - 1); doneN--; }
        done[doneN++] = r; castle++; java.util.Arrays.fill(cur, 0f);
    }

    // ------------------------------------------------------------------ measurement
    static int castleOf(double yAbs, float spacing) { return Math.max(1, 1 + (int) Math.floor(yAbs / spacing)); }

    /**
     * Adds a finished slice (as extracted: element 0 is the previous slice's last route platform, whose own hazards that slice counted) and closes every
     * castle the slice climbed past. {@code f} are the factors the slice was generated with.
     */
    public void addSlice(Course c, double yBase, Tuning t, float[] f) {
        System.arraycopy(f, 0, lastF, 0, 4);
        float sp = t.castleSpacing > 0f ? t.castleSpacing : 500f;
        int rs = c.routeSize();
        // per (castle, window): hazard and enemy counts by type, interaction sum
        Map<Long, EnumMap<Element.Type, Integer>> haz = new TreeMap<>(); Map<Long, Float> xs = new TreeMap<>();          // (ordered maps: the float sums are made in one fixed order)
        Map<Integer, Set<Element.Type>> hazOn = new TreeMap<>(), enemyAt = new TreeMap<>();
        double[][] add = new double[64][]; int base = castle;
        for (int hi = 0; hi < c.hazards.size(); hi++) {
            Element h = c.hazards.get(hi);
            if (h.anchor <= 0 || h.anchor >= c.size()) continue;          // anchored on element 0: the previous slice counted it
            if (hazard(h.type)) {
                if (h.anchor >= rs || c.get(h.anchor).anchor >= 0) continue;
                hazOn.computeIfAbsent(h.anchor, x -> EnumSet.noneOf(Element.Type.class)).add(h.type);
                haz.computeIfAbsent(key(c.get(h.anchor).y + yBase, sp), x -> new EnumMap<>(Element.Type.class)).merge(h.type, 1, Integer::sum);
            } else if (enemy(h.type)) {
                boolean any = false;
                for (int p = Math.max(0, h.anchor - 4); p <= Math.min(rs - 1, h.anchor + 4); p++)
                    if (c.get(p).anchor < 0 && EncounterModel.reaches(c, t, h, p)) { any = true; enemyAt.computeIfAbsent(p, x -> EnumSet.noneOf(Element.Type.class)).add(h.type); }
                if (any) haz.computeIfAbsent(key(c.get(h.anchor).y + yBase, sp), x -> new EnumMap<>(Element.Type.class)).merge(h.type, 1, Integer::sum);
            } else if (h.type == Element.Type.CLUB && h.anchor < rs && c.get(h.anchor).anchor < 0) {
                acc(add, base, castleOf(c.get(h.anchor).y + yBase, sp))[M] += 1;
            }
        }
        for (Map.Entry<Long, EnumMap<Element.Type, Integer>> e : haz.entrySet()) {
            int cs = (int) (e.getKey() >> 32);
            for (Map.Entry<Element.Type, Integer> n : e.getValue().entrySet()) {
                double v = points(n.getKey()) * (Math.min(n.getValue(), REP_FULL) + REP_TAIL * Math.max(0, n.getValue() - REP_FULL));
                acc(add, base, cs)[enemy(n.getKey()) ? E : H] += v;
            }
        }
        for (int i = 0; i < rs - 1; i++) {
            if (c.get(i).anchor >= 0) continue;
            int j = c.nextRoute(i); if (j < 0 || j >= rs) continue;
            Element a = c.get(i), b = c.get(j);
            double y0 = a.y + yBase; int cs = castleOf(y0, sp); double[] q = acc(add, base, cs);
            double terr = 0;
            if (a.isPlatform() && b.isPlatform()) {
                double gap = Math.max(0f, Math.abs(c.dsWrap(b.s, a.s)) - a.halfW() - b.halfW()), reach = EncounterModel.reach(t, b.y - a.y);
                double ratio = reach > 0 ? Math.min(1.2, gap / reach) : 1.2;
                terr += 2.0 * clamp((ratio - 0.4) / 0.6); if (ratio > 0.85) terr += 3.0 * (ratio - 0.85); terr += 1.5 * clamp((3.0 - b.w) / 2.0);
            }
            if (a.isMoving()) terr += 0.5;
            if (a.type == Element.Type.SEESAW || a.type == Element.Type.RAMP) terr += 0.6;
            if (a.type == Element.Type.RAMP && a.skin >= 2) terr += 0.4;
            if (b.isMoving() || b.type == Element.Type.SWING) terr += 0.8;
            if (a.crumbles() || a.holed()) terr += 0.7;
            if (a.wet()) terr += 0.4;
            q[T] += terr; q[A_LINKS] += 1;
            Set<Element.Type> hz = EnumSet.noneOf(Element.Type.class), en = EnumSet.noneOf(Element.Type.class);
            if (hazOn.containsKey(i)) hz.addAll(hazOn.get(i)); if (hazOn.containsKey(j)) hz.addAll(hazOn.get(j));
            if (enemyAt.containsKey(i)) en.addAll(enemyAt.get(i)); if (enemyAt.containsKey(j)) en.addAll(enemyAt.get(j));
            int nx = 0;
            if (terr >= TERRAIN_DEMANDING && !hz.isEmpty()) nx++;
            if (terr >= TERRAIN_DEMANDING && !en.isEmpty()) nx++;
            if (!hz.isEmpty() && !en.isEmpty()) nx++;
            if (hz.size() >= 2) nx++;
            if (en.size() >= 2) nx++;
            if (nx > 0) xs.merge(key(y0, sp), nx * X_BONUS, Float::sum);
            if (terr >= TERRAIN_DEMANDING || !hz.isEmpty() || !en.isEmpty()) q[A_CHALL] += 1;
            if (b.checkpoint) q[A_REC] += 1;
            q[A_TOP] = Math.max(q[A_TOP], b.y + yBase);
        }
        for (Map.Entry<Long, Float> e : xs.entrySet()) acc(add, base, (int) (e.getKey() >> 32))[X] += Math.min(X_CAP, e.getValue());
        // fold into the state in climbing order, closing castles the slice has passed
        for (int k = 0; k < add.length; k++) {
            if (add[k] == null) continue;
            int cs = base + k;
            while (castle < cs) complete();
            if (cs < castle) continue;          // (cannot happen: a slice never reaches below the castle being measured)
            for (int i = 0; i < ACC; i++) cur[i] = i == A_TOP ? (float) Math.max(cur[i], add[k][i]) : cur[i] + (float) add[k][i];
        }
    }
    private static double[] acc(double[][] add, int base, int cs) { int k = Math.max(0, Math.min(add.length - 1, cs - base)); if (add[k] == null) add[k] = new double[ACC]; return add[k]; }
    private static long key(double yAbs, float sp) { long w = (long) Math.floor(yAbs / WINDOW); return ((long) castleOf(yAbs, sp) << 32) | (w & 0xffffffffL); }
    static double clamp(double x) { return Math.max(0, Math.min(1, x)); }

    // ------------------------------------------------------------------ control
    /** Fraction of castle {@link #castle} measured so far. */
    float progress(float spacing) { return cur[A_TOP] <= 0f ? 0f : (float) Math.max(0.0, Math.min(1.0, (cur[A_TOP] - (castle - 1) * (double) spacing) / spacing)); }

    /** Gain and the share of the castle in progress that must be built before its own error counts. */
    static final float GAIN = 1.5f, PARTIAL_FROM = 0.35f, PARTIAL_WEIGHT = 0.5f;

    /**
     * Selection factors {T, H, E, X} for castle {@code n} (n is the castle being measured or the one after it): 1 means "as the static profile
     * builds it". Each is 1 + GAIN * relative error (target - measured) / target, from the previous castle and, once enough of it is built, the
     * castle in progress, clamped to the profile's bounds. No previous castle and too little of this one: no correction.
     */
    public float[] factors(int n, DifficultyProfile P, float spacing) {
        float[] f = {1f, 1f, 1f, 1f};
        if (!P.progression()) return f;
        float[] prevAcc = null; int prevN = n - 1;
        float[] partial = null; float frac = 0f;
        if (n == castle) {
            float[] r = record(prevN); if (r != null) { prevAcc = new float[ACC]; System.arraycopy(r, 1, prevAcc, 0, ACC); }
            frac = progress(spacing); if (frac >= PARTIAL_FROM) partial = cur;
        } else if (n == castle + 1) {          // a slice climbing into the next castle: the castle in progress stands for its predecessor
            if (progress(spacing) >= 0.5f) prevAcc = cur;
        }
        if (prevAcc == null && partial == null) return n == 1 ? P.initialFactors() : f;          // castle 1 before a third of it is built: the profile's starting point
        for (int k = 0; k < 4; k++) {
            double e = 0, wsum = 0;
            if (prevAcc != null) { double tg = P.target(prevN, k); if (tg > 0) { e += (1 - PARTIAL_WEIGHT * (partial != null ? 1 : 0)) * (tg - prevAcc[k]) / tg; wsum += 1 - PARTIAL_WEIGHT * (partial != null ? 1 : 0); } }
            if (partial != null) { double tg = P.target(n, k) * frac; if (tg > 0) { double w = prevAcc != null ? PARTIAL_WEIGHT : 1; e += w * (tg - partial[k]) / tg; wsum += w; } }
            if (wsum <= 0) continue;
            e /= wsum;
            f[k] = (float) Math.max(P.factorLo(k), Math.min(P.factorHi(k), 1 + GAIN * e));
        }
        return f;
    }
}
