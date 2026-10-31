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
    /**
     * Completed castles kept (bounded state). Rules 21 record: castle number, the accumulator, the castle's BASE factors (what its slices were
     * built from before the within-castle trim), the planned total it was steered toward, the factors of its last slice, and the requested
     * (unbounded) base factors.
     */
    static final int KEEP = 12, R_BASE = 1 + ACC, R_PLAN = R_BASE + 4, R_LAST = R_PLAN + 1, R_REQ = R_LAST + 4, REC = R_REQ + 4;
    static final int FORMAT = 3;

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
    /**
     * The terrain terms (rules 21: the ONE definition; LaneReport's T-* ledger rules call these, so the generator's estimate and the certification
     * report cannot drift apart). T-GAP 2 x clamp((ratio - 0.4) / 0.6); T-LONG 3 x (ratio - 0.85) above 0.85; T-LAND 1.5 x clamp((3 - width) / 2);
     * moving take-off 0.5; seesaw/ramp take-off 0.6; ramp variant 0.4; moving or swinging target 0.8; crumbling or holed footing 0.7; wet 0.4.
     */
    public static final double T_MOVE_TAKEOFF = 0.5, T_TILT_TAKEOFF = 0.6, T_RAMP_VARIANT = 0.4, T_MOVE_TARGET = 0.8, T_CRUMBLE = 0.7, T_WET = 0.4;
    public static double tGap(double ratio) { return 2.0 * clamp((ratio - 0.4) / 0.6); }
    public static double tLong(double ratio) { return ratio > 0.85 ? 3.0 * (ratio - 0.85) : 0.0; }
    public static double tLand(double w) { return 1.5 * clamp((3.0 - w) / 2.0); }
    public static final float WINDOW = 25f, X_BONUS = 0.5f, X_CAP = 2f, TERRAIN_DEMANDING = 1f, REP_TAIL = 0.25f; static final int REP_FULL = 3;

    // ------------------------------------------------------------------ state
    /** Castle being measured (1-based) and its running totals. */
    int castle = 1; final float[] cur = new float[ACC];
    /** Completed castles, oldest first ({@link #REC} floats each), at most {@link #KEEP}. */
    final float[][] done = new float[KEEP][]; int doneN;
    /** The factors the latest slice was generated with (diagnostics; also stored with each completed castle). */
    final float[] lastF = {1f, 1f, 1f, 1f};
    /** The plan of the castle in progress: base factors, planned total, requested base factors (planned 0 = not set yet: derived on demand). */
    final float[] curBase = {1f, 1f, 1f, 1f}, curReq = {1f, 1f, 1f, 1f}; float curPlanned;
    /** The profile the latest slice was measured for (transient: set by addSlice, used when a castle closes). */
    private DifficultyProfile prof;

    // ------------------------------------------------------------------ hazard capacity (NOT GONNA HAPPEN only; rules 14, owner amendment 2026-10-27)
    /**
     * NGH has no fixed hazard ceiling: its hazard selection may escalate as far as the generator can place hazards. The capacity it measures, per
     * hazard module kind ({@link #CAP_KINDS}): attempts, accepted modules, and the hazard points the accepted modules actually added (the estimator's
     * weights). Kept for the castle in progress and the castle before it, with a record per completed castle for the reports. Written into the slice
     * only when present (NGH), so NORMAL and PUNISHER slices are byte-for-byte what they were.
     */
    public static final String[] CAP_KINDS = {"HAZ", "TRAP", "MORTAR", "DROPS"};
    public static final int CAP_K = 4, CAP_W = 3 * CAP_K, CAP_REC = 1 + CAP_W + 3;
    /** Hazard control result codes: applied = requested, step-limited, held for low acceptance, computational bound. */
    public static final int HC_NONE = 0, HC_RATE = 1, HC_ACCEPTANCE = 2, HC_BOUND = 3;
    public static final String[] HC_NAMES = {"none", "escalation_rate", "low_acceptance", "computational_bound"};
    boolean cap;
    final float[] capCur = new float[CAP_W], capPrev = new float[CAP_W];
    /** {requested, applied, constraint} of the hazard factor for the castle in progress (as the latest slice was generated). */
    final float[] hcNow = {1f, 1f, HC_NONE};
    final float[][] capRec = new float[KEEP][]; int capRecN;
    private java.util.Map<Integer, float[]> capPending;
    static final int FORMAT_CAP = 4;

    public Progression copy() {
        Progression p = new Progression(); p.castle = castle; System.arraycopy(cur, 0, p.cur, 0, ACC);
        for (int i = 0; i < doneN; i++) p.done[i] = done[i].clone(); p.doneN = doneN; System.arraycopy(lastF, 0, p.lastF, 0, 4);
        System.arraycopy(curBase, 0, p.curBase, 0, 4); System.arraycopy(curReq, 0, p.curReq, 0, 4); p.curPlanned = curPlanned; p.prof = prof;
        p.cap = cap; System.arraycopy(capCur, 0, p.capCur, 0, CAP_W); System.arraycopy(capPrev, 0, p.capPrev, 0, CAP_W); System.arraycopy(hcNow, 0, p.hcNow, 0, 3);
        for (int i = 0; i < capRecN; i++) p.capRec[i] = capRec[i].clone(); p.capRecN = capRecN;
        return p;
    }
    public float[] toArray() {
        int extra = cap ? 2 * CAP_W + 3 + 1 + capRecN * CAP_REC : 0;
        float[] a = new float[3 + ACC + 4 + 9 + doneN * REC + extra]; int k = 0;
        a[k++] = cap ? FORMAT_CAP : FORMAT; a[k++] = castle; a[k++] = doneN;
        for (float v : cur) a[k++] = v; for (float v : lastF) a[k++] = v;
        for (float v : curBase) a[k++] = v; a[k++] = curPlanned; for (float v : curReq) a[k++] = v;
        for (int i = 0; i < doneN; i++) for (float v : done[i]) a[k++] = v;
        if (cap) {
            for (float v : capCur) a[k++] = v; for (float v : capPrev) a[k++] = v; for (float v : hcNow) a[k++] = v;
            a[k++] = capRecN; for (int i = 0; i < capRecN; i++) for (float v : capRec[i]) a[k++] = v;
        }
        return a;
    }
    /** The state stored in a slice (null or unknown: a fresh climb, no feedback yet). */
    public static Progression of(float[] a) {
        Progression p = new Progression();
        if (a == null || a.length < 3 + ACC + 4 + 9 || ((int) a[0] != FORMAT && (int) a[0] != FORMAT_CAP)) return p;          // an older format (rules 20 or before): a fresh climb
        int k = 1; p.castle = (int) a[k++]; int n = (int) a[k++];
        for (int i = 0; i < ACC; i++) p.cur[i] = a[k++]; for (int i = 0; i < 4; i++) p.lastF[i] = a[k++];
        for (int i = 0; i < 4; i++) p.curBase[i] = a[k++]; p.curPlanned = a[k++]; for (int i = 0; i < 4; i++) p.curReq[i] = a[k++];
        for (int i = 0; i < n && k + REC <= a.length; i++) { p.done[i] = new float[REC]; System.arraycopy(a, k, p.done[i], 0, REC); k += REC; p.doneN++; }
        if ((int) a[0] == FORMAT_CAP && k + 2 * CAP_W + 4 <= a.length) {
            p.cap = true;
            for (int i = 0; i < CAP_W; i++) p.capCur[i] = a[k++]; for (int i = 0; i < CAP_W; i++) p.capPrev[i] = a[k++]; for (int i = 0; i < 3; i++) p.hcNow[i] = a[k++];
            int m = (int) a[k++];
            for (int i = 0; i < m && k + CAP_REC <= a.length && i < KEEP; i++) { p.capRec[i] = new float[CAP_REC]; System.arraycopy(a, k, p.capRec[i], 0, CAP_REC); k += CAP_REC; p.capRecN++; }
        }
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
        float[] r = new float[REC]; r[0] = castle; System.arraycopy(cur, 0, r, 1, ACC);
        float[] pl = currentPlan();
        System.arraycopy(pl, 0, r, R_BASE, 4); r[R_PLAN] = pl[4]; System.arraycopy(lastF, 0, r, R_LAST, 4); System.arraycopy(pl, 5, r, R_REQ, 4);
        if (doneN == KEEP) { System.arraycopy(done, 1, done, 0, KEEP - 1); doneN--; }
        done[doneN++] = r;
        if (cap) {
            float[] cr = new float[CAP_REC]; cr[0] = castle; System.arraycopy(capCur, 0, cr, 1, CAP_W); System.arraycopy(hcNow, 0, cr, 1 + CAP_W, 3);
            if (capRecN == KEEP) { System.arraycopy(capRec, 1, capRec, 0, KEEP - 1); capRecN--; }
            capRec[capRecN++] = cr; System.arraycopy(capCur, 0, capPrev, 0, CAP_W); java.util.Arrays.fill(capCur, 0f);
        }
        castle++; java.util.Arrays.fill(cur, 0f);
        if (cap && capPending != null) addCap(capPending.get(castle));
        curPlanned = 0f;
        if (prof != null && prof.progression()) { float[] nx = plan(castle, r, prof, this); System.arraycopy(nx, 0, curBase, 0, 4); curPlanned = nx[4]; System.arraycopy(nx, 5, curReq, 0, 4); }
    }
    private void addCap(float[] v) { if (v != null) for (int i = 0; i < CAP_W; i++) capCur[i] += v[i]; }
    /** The completed capacity records (NGH): {castle, attempts/accepted/points per kind, requested, applied, constraint}. */
    public int capRecordCount() { return capRecN; }
    public float[] capRecord(int i) { return capRec[i]; }
    public float[] capCurrent() { return capCur.clone(); }
    public float[] hazardControlNow() { return hcNow.clone(); }
    public boolean hasCapacity() { return cap; }

    /**
     * NGH: the slice's hazard-module attempts by castle ({@code capByCastle}: castle -> CAP_W counts) and the hazard control it was generated with
     * ({@code hc}), folded in with {@link #addSlice}. Call before addSlice.
     */
    public void addCapacity(java.util.Map<Integer, float[]> capByCastle, float[] hc) {
        cap = true; capPending = capByCastle; if (hc != null) System.arraycopy(hc, 0, hcNow, 0, 3);
        addCap(capByCastle.get(castle));
    }

    // ------------------------------------------------------------------ measurement
    static int castleOf(double yAbs, float spacing) { return Math.max(1, 1 + (int) Math.floor(yAbs / spacing)); }

    /**
     * Adds a finished slice (as extracted: element 0 is the previous slice's last route platform, whose own hazards that slice counted) and closes every
     * castle the slice climbed past. {@code f} are the factors the slice was generated with.
     */
    public void addSlice(Course c, double yBase, Tuning t, float[] f) {
        System.arraycopy(f, 0, lastF, 0, 4); prof = DifficultyProfile.of(t);
        if (curPlanned <= 0f && prof.progression()) { float[] pl = currentPlan(); System.arraycopy(pl, 0, curBase, 0, 4); curPlanned = pl[4]; System.arraycopy(pl, 5, curReq, 0, 4); }
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
                double gap = Math.max(0f, Math.abs(c.dsWrap(b.s, a.s)) - a.halfW() - b.halfW()), ratio = reachRatio(c, t, a, b, gap);
                terr += tGap(ratio) + tLong(ratio) + tLand(b.w);
            }
            if (a.isMoving()) terr += T_MOVE_TAKEOFF;
            if (a.type == Element.Type.SEESAW || a.type == Element.Type.RAMP) terr += T_TILT_TAKEOFF;
            if (a.type == Element.Type.RAMP && a.skin >= 2) terr += T_RAMP_VARIANT;
            if (b.isMoving() || b.type == Element.Type.SWING) terr += T_MOVE_TARGET;
            if (a.crumbles() || a.holed()) terr += T_CRUMBLE;
            if (a.wet()) terr += T_WET;
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
        capPending = null;
    }
    /**
     * How close a link comes to the take-off's physical reach (0 .. 1.2). A jump is measured against the longest plain jump at its rise
     * ({@link EncounterModel#reach}); a launcher take-off - pad, spring, swing or elevator - against that launcher's own measured reach at the
     * edge gap ({@link CourseGenerator#reachAt}, the real-simulation tables of correction C1). Rules 20 (owner 2026-10-31): before, a launcher's
     * rise always exceeded plain-jump height, so every launcher link scored the maximum, as the hardest possible jump; the launch itself
     * still scores nothing extra (owner rule: pad and spring travel 0) - its landing width and a moving target are scored as before.
     */
    static double reachRatio(Course c, Tuning t, Element a, Element b, double gap) {
        if (launcher(a)) { float rv = CourseGenerator.reachAt(c, a, (float) gap); return rv > 0f ? Math.min(1.2, Math.max(0.0, (b.y - a.y) / rv)) : 1.2; }
        double reach = EncounterModel.reach(t, b.y - a.y);
        return reach > 0 ? Math.min(1.2, gap / reach) : 1.2;
    }
    /** Take-offs that launch the climber beyond a plain jump: measured against their own reach (rules 20). */
    static boolean launcher(Element e) { return e.type == Element.Type.PAD || e.type == Element.Type.SPRING || e.type == Element.Type.SWING || e.type == Element.Type.MOVE_V; }
    private static double[] acc(double[][] add, int base, int cs) { int k = Math.max(0, Math.min(add.length - 1, cs - base)); if (add[k] == null) add[k] = new double[ACC]; return add[k]; }
    private static long key(double yAbs, float sp) { long w = (long) Math.floor(yAbs / WINDOW); return ((long) castleOf(yAbs, sp) << 32) | (w & 0xffffffffL); }
    static double clamp(double x) { return Math.max(0, Math.min(1, x)); }

    // ------------------------------------------------------------------ control (rules 21)
    /** Fraction of castle {@link #castle} measured so far. */
    float progress(float spacing) { return cur[A_TOP] <= 0f ? 0f : (float) Math.max(0.0, Math.min(1.0, (cur[A_TOP] - (castle - 1) * (double) spacing) / spacing)); }

    /**
     * Rules 21 control law (Build 83 Rule 20 forensic audit, owner Rule 21 directive 2026-10-31). Rules 13-20 computed every castle's factors afresh as
     * 1 + 1.5 x (the previous castle's relative error), relative to 1 and blind to the factor that castle had been built with: a castle eased to 0.7
     * that landed on target sent the next one back to 1.0, the built-in excess returned, and the factors alternated castle by castle (NORMAL hazards
     * 0.70 / 1.02 / 0.77 / 1.22). Rules 21 keeps a correction STATE instead:
     * <ul>
     * <li><b>Base factors (integral, velocity form).</b> Castle n is built from base(n) = base(n-1) x (wanted(n-1) / measured(n-1))^K, per category,
     * each castle's change limited to x/÷ {@link #STEP_MAX} and the result held inside the profile's bounds. A castle that lands on target keeps the
     * factor that got it there (no reset to 1); a persistent mismatch is learned; the bound clamps the stored state, so nothing winds up past a bound
     * (anti-windup). The gains K ({@link #GAIN_K}) are below 1 for the categories that respond about proportionally (no overcorrection, so no
     * alternation) and 1 for terrain, which responds weakly.</li>
     * <li><b>Planned level (overshoot / undershoot glide).</b> Castle n is steered toward its target, but never below 1.5 % above what castle n-1
     * measured (an over-hot castle is followed by a smaller rise, not a drop) and never above 6 % over it (a short castle is caught up over several
     * castles, not in one SERIOUS jump) ({@link #G_FLOOR}, {@link #G_CEIL}); castles past 10 hold castle 10's target. This replaces the optional
     * slope-aware switch of rules 19-20.</li>
     * <li><b>Within-castle trim.</b> Once {@link #PARTIAL_FROM} of the castle is built, each slice multiplies the base by
     * (castle-so-far wanted / measured)^{@link #K_IN}, bounded to ±{@link #TRIM}: cumulative, so one unusual module moves it little, and it never
     * changes an accepted slice.</li>
     * </ul>
     * Everything is a pure function of the state carried in the slices: same seed, same course, on every device and on resume.
     */
    static final float PARTIAL_FROM = 0.3f, PARTIAL_NEXT = 0.5f, STEP_MAX = 1.25f, TRIM = 0.15f, K_IN = 0.5f;
    static final float[] GAIN_K = {1.0f, 0.6f, 0.6f, 0.5f};
    static final double G_FLOOR = 0.015, G_CEIL = 0.06;
    static final float[] ONE4 = {1f, 1f, 1f, 1f};

    /**
     * The plan of castle {@code n} from the record of castle n-1 ({@code prev}, null for castle 1 or an unknown predecessor): {base T, H, E, X,
     * planned total, requested (unbounded) base T, H, E, X}. {@code st}: the state whose hazard capacity applies (NGH), or null.
     */
    static float[] plan(int n, float[] prev, DifficultyProfile P, Progression st) {
        float[] o = new float[9];
        double tn = P.targetTotal(n);
        if (prev == null) { float[] f0 = n == 1 ? P.initialFactors() : ONE4; System.arraycopy(f0, 0, o, 0, 4); System.arraycopy(f0, 0, o, 5, 4); o[4] = (float) tn; return o; }
        int m = n - 1;
        double meas = total(prev, 1), planPrev = prev[R_PLAN] > 0f ? prev[R_PLAN] : P.targetTotal(m);
        double planned = tn;
        if (n <= 10 && meas > 0) planned = Math.min(Math.max(tn, meas * (1 + G_FLOOR)), meas * (1 + G_CEIL));
        o[4] = (float) planned;
        double scale = planPrev / P.targetTotal(m);
        for (int k = 0; k < 4; k++) {
            double fPrev = prev[R_BASE + k] > 0f ? prev[R_BASE + k] : 1.0, want = P.target(m, k) * scale, got = prev[1 + k];
            double ratio = got > 1e-3 ? want / got : STEP_MAX;
            ratio = Math.max(1.0 / STEP_MAX, Math.min(STEP_MAX, Math.pow(Math.max(1e-6, ratio), GAIN_K[k])));
            float req = (float) (fPrev * ratio);
            o[5 + k] = req;
            if (k == H && P.capacityControl() && st != null) o[k] = st.hazardControl(P, req, (float) fPrev)[1];
            else o[k] = Math.max(P.factorLo(k), Math.min(P.factorHi(k), req));
        }
        return o;
    }
    /** The plan of the castle in progress (stored when the castle opened; derived on demand before the first slice). */
    float[] currentPlan() { return currentPlan(prof); }
    float[] currentPlan(DifficultyProfile P) {
        if (curPlanned > 0f) { float[] o = new float[9]; System.arraycopy(curBase, 0, o, 0, 4); o[4] = curPlanned; System.arraycopy(curReq, 0, o, 5, 4); return o; }
        return P == null ? new float[]{1, 1, 1, 1, 0, 1, 1, 1, 1} : plan(castle, record(castle - 1), P, this);
    }
    /** The castle in progress as if complete (its accumulator scaled to a whole castle), for the plan of the castle after it. */
    private float[] virtualRecord(float frac, DifficultyProfile P) {
        float[] r = new float[REC]; r[0] = castle; float[] pl = currentPlan(P);
        for (int i = 0; i < ACC; i++) r[1 + i] = i < 5 ? cur[i] / Math.max(0.05f, frac) : cur[i];
        System.arraycopy(pl, 0, r, R_BASE, 4); r[R_PLAN] = pl[4]; return r;
    }

    /**
     * Selection factors {T, H, E, X} for castle {@code n} (n is the castle being measured or the one after it): the castle's base factors with the
     * within-castle trim, inside the profile's bounds (NGH hazards: the capacity rule).
     */
    public float[] factors(int n, DifficultyProfile P, float spacing) { return factors(n, P, spacing, null); }
    /** As {@link #factors(int, DifficultyProfile, float)}; also writes the requested (unbounded) factors into {@code requested} when given (reports). */
    public float[] factors(int n, DifficultyProfile P, float spacing, float[] requested) {
        float[] f = {1f, 1f, 1f, 1f};
        if (requested != null) java.util.Arrays.fill(requested, 1f);
        if (!P.progression()) return f;
        float frac = progress(spacing);
        float[] pl, trim = {1f, 1f, 1f, 1f};
        if (n == castle) {
            pl = currentPlan(P);
            if (frac >= PARTIAL_FROM) for (int k = 0; k < 4; k++) {
                double want = P.target(n, k) * (pl[4] / P.targetTotal(n)) * frac, got = cur[k];
                double tr = got > 1e-3 ? Math.pow(want / got, K_IN) : 1 + TRIM;
                trim[k] = (float) Math.max(1 - TRIM, Math.min(1 + TRIM, tr));
            }
        } else if (n == castle + 1) {
            pl = frac >= PARTIAL_NEXT ? plan(n, virtualRecord(frac, P), P, this) : currentPlan(P);          // too little of the castle in progress to judge: carry its base on
        } else pl = n == 1 ? plan(1, null, P, this) : currentPlan(P);
        for (int k = 0; k < 4; k++) {
            float req = pl[5 + k] * trim[k];
            if (requested != null) requested[k] = req;
            if (k == H && P.capacityControl()) { float[] hc = hazardControl(P, req, prevAppliedH(n)); f[k] = hc[1]; hcLast[0] = Math.max(req, hc[0]); hcLast[1] = hc[1]; hcLast[2] = hc[2]; }
            else f[k] = Math.max(P.factorLo(k), Math.min(P.factorHi(k), pl[k] * trim[k]));
        }
        return f;
    }
    /** The hazard factor the castle before {@code n} was built with (its base), or the latest slice's factor. */
    float prevAppliedH(int n) { float[] r = record(n - 1); if (r != null && r[R_BASE + H] > 0f) return r[R_BASE + H]; if (n == castle + 1) return currentPlan()[H]; return lastF[H]; }

    /**
     * Reports only (KPI 7B): the requested (unbounded) base factors of castle {@code n} from the record of castle n-1 ({@code prev}, layout of
     * {@link #completed}; null for castle 1), before capacity control.
     */
    public static float[] startRequest(int n, float[] prev, DifficultyProfile P) {
        if (!P.progression()) return ONE4.clone();
        float[] pl = plan(n, prev, P, null); return new float[]{pl[5], pl[6], pl[7], pl[8]};
    }

    // ------------------------------------------------------------------ capacity-aware hazard escalation (NGH)
    /** Escalation per castle above the previous castle's applied factor, while acceptance holds (PROVISIONAL). */
    public static final float CAP_STEP = 0.25f;
    /** Hazard-module acceptance (accepted / attempted, last castle and this one so far) below which NGH does not escalate further (PROVISIONAL). */
    public static final float CAP_ACCEPT_OK = 0.40f;          // rules 21: was 0.55, above the 0.43-0.47 the generator achieves (Rule 20 campaign P3c), so NGH never escalated
    /**
     * Computational bound, not a balancing value: at 3x the hazard modules' weights outweigh every other module class at castle 10 several times over,
     * after which more weight only means more refused attempts (generation time) without more placed hazards.
     */
    public static final float CAP_BOUND = 3.0f;
    /** Prior attempts (with the pooled yield) that steady the per-kind capacity estimate early in a climb. */
    static final float CAP_PRIOR = 6f;

    /** Pooled hazard-module counts of the castle before and the castle in progress: {attempts, accepted, points} per kind. */
    float[] capPool() { float[] v = new float[CAP_W]; for (int i = 0; i < CAP_W; i++) v[i] = capPrev[i] + capCur[i]; return v; }
    /** Acceptance over all hazard kinds (pooled), with a weak prior of one in two. */
    public float capAcceptance() { float[] v = capPool(); float at = 0, ok = 0; for (int q = 0; q < CAP_K; q++) { at += v[3 * q]; ok += v[3 * q + 1]; } return (ok + 1f) / (at + 2f); }

    /**
     * NGH's hazard factor for castle {@code n} from the controller's request: {requested, applied, constraint}. No fixed ceiling: up to the
     * request, but never more than CAP_STEP above the factor the previous castle was built with, never above it while acceptance is below
     * CAP_ACCEPT_OK (more weight would only mean more refused attempts), and never past CAP_BOUND. The static 1.35 of NORMAL/PUNISHER stays available
     * as the floor of the escalation room, so NGH is never held below what the other modes may ask.
     */
    public float[] hazardControl(int n, DifficultyProfile P, float requested) { return hazardControl(P, requested, prevAppliedH(n)); }
    public float[] hazardControl(DifficultyProfile P, float requested, float prevApplied) {
        float req = Math.max(P.factorLo(H), requested), base = P.factorHi(H);
        boolean accepting = capAcceptance() >= CAP_ACCEPT_OK;
        float room = accepting ? Math.max(base, prevApplied + CAP_STEP) : Math.max(base, prevApplied);
        float applied = Math.min(req, Math.min(room, CAP_BOUND));
        int code = applied >= req - 1e-6f ? HC_NONE : room >= CAP_BOUND ? HC_BOUND : accepting ? HC_RATE : HC_ACCEPTANCE;
        return new float[]{req, applied, code};
    }
    /** {requested, applied, constraint} of the hazard factor for castle {@code n}, exactly as {@link #factors} applies it (reports, GenTrace). */
    public float[] hazardControlFor(int n, DifficultyProfile P, float spacing) {
        float[] f = factors(n, P, spacing, null);
        if (!P.capacityControl()) return new float[]{f[H], f[H], HC_NONE};
        return hcLast.clone();
    }
    /** The hazard control of the latest {@link #factors} call (transient). */
    private final float[] hcLast = {1f, 1f, HC_NONE};

    /**
     * Per-kind selection multipliers (NGH): each hazard module kind's measured yield (hazard points actually placed per attempt) relative to the pooled
     * yield, clamped to [CAP_MULT_LO, CAP_MULT_HI]. A kind the generator keeps refusing is drawn less, one that places reliably is drawn more; the
     * generator rescales the hazard class to its previous total, so this changes the arrangement of hazards, not how many are asked for.
     */
    public static final float CAP_MULT_LO = 0.4f, CAP_MULT_HI = 1.6f;
    public float[] capMultipliers(float[] extra) {
        float[] v = capPool(); if (extra != null) for (int i = 0; i < CAP_W; i++) v[i] += extra[i];
        float at = 0, pts = 0; for (int q = 0; q < CAP_K; q++) { at += v[3 * q]; pts += v[3 * q + 2]; }
        float pooled = at > 0 ? pts / at : 1f, m[] = new float[CAP_K];
        for (int q = 0; q < CAP_K; q++) {
            float y = (v[3 * q + 2] + CAP_PRIOR * pooled) / (v[3 * q] + CAP_PRIOR);
            m[q] = pooled > 0 ? Math.max(CAP_MULT_LO, Math.min(CAP_MULT_HI, y / pooled)) : 1f;
        }
        return m;
    }
}
