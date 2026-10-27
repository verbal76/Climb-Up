package com.hotatticgames.climbup.sim;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/**
 * Three-lane difficulty measurement (owner reporting directive 2026-10-26 and its classification amendment). Diagnostic only: it generates and solves
 * courses exactly as the game and the existing solver do (observe-only {@link GenTrace} hooks) and never feeds anything back.
 *
 * Scoring model {@value #SCORING}: TERRAIN from link geometry (rules T-*), HAZARDS from the nine environmental hazards on route platforms (H-*),
 * ENEMIES from the four enemy families that can reach the route (E-*), with clubs as separate MITIGATION (M-CLUB) and cross-system challenges in a
 * separate INTERACTION ledger (X-*). Every contribution is a ledger row: source id, measured property, raw measurement, rule, weight, window
 * fraction, repetition factor, final value. Window totals are sums of their rows (pinned by LaneReportTest). The earlier single-score model
 * (DifficultyScore, "v2") is still computed alongside for reference; the two are different models and are never mixed.
 *
 *   LaneReport <tuning.json> <top metres> <mode 0|1|2> <seed> <out dir>      env: SOLVE=0 skips the solver, RUN_BUDGET=seconds stops early (partial)
 */
public final class LaneReport {
    public static final String SCORING = "3lane-1";
    public static final float WINDOW = 25f, CASTLE = 500f;
    static final String[] MODE = {"NORMAL", "PUNISHER", "NGH"};
    static final float X_BONUS = 0.5f, X_CAP = 2f, TERRAIN_DEMANDING = 1f;

    // ------------------------------------------------------------------ records
    public static final class El {
        String id, type, lane, module = "", moduleKind = "UNATTRIBUTED", structure = "ordinary"; int slice, idx, window; boolean route; Element e; double sAbs, yAbs;
    }
    public static final class Row {
        int window; String lane, rule, source, property, note = ""; double raw, weight, fraction, repetition = 1, value;
    }
    public static final class Link {
        int slice, from, to; String fromId, toId, fromType, toType; double y0, y1, gap = Double.NaN, dy, reach = Double.NaN, ratio = Double.NaN, landW = Double.NaN, terrain;
        String status = "NOT_SOLVED"; double solveTime = Double.NaN, solveWall = Double.NaN; int setbacks = -1, planFails, visits; float emJump, emPosition, emWait, emCommit, emRecover, emPursue, emScore;
        double[] fractions; int[] windows; Set<Element.Type> hazards = EnumSet.noneOf(Element.Type.class), enemies = EnumSet.noneOf(Element.Type.class);
    }
    public static final class Win {
        int index; double[] raw = new double[3], adj = new double[3]; double mitigation, interaction; boolean geometry;
        EnumMap<Element.Type, Double> counts = new EnumMap<>(Element.Type.class); TreeMap<String, Integer> modules = new TreeMap<>();
        int linksTotal, linksTraversed, linksFailed, linksUnreached, setbacks, planFails, rejected, fallbacks, demandingLinks, offRouteHazards, unreachableEnemies, decoys;
        double solveTime, solveWall, maxRatio = Double.NaN, minLandW = Double.NaN; TreeSet<String> flags = new TreeSet<>(); float v2 = Float.NaN;
        TreeMap<String, Integer> mech = new TreeMap<>(); int recovery;
        double total() { return adj[0] + adj[1] + adj[2] + interaction - mitigation; }
    }

    public final Tuning t; public final int mode; public final long seed; public final float top;
    public final List<El> elements = new ArrayList<>();
    public final List<Element> hazardEls = new ArrayList<>(); public final List<El> hazards = new ArrayList<>();
    public final List<Link> links = new ArrayList<>();
    public final List<Row> ledger = new ArrayList<>();
    public final List<String[]> modules = new ArrayList<>();          // every module attempt
    public final List<String[]> solverSlices = new ArrayList<>(), solverFailures = new ArrayList<>(), sliceFailures = new ArrayList<>();
    public final TreeMap<Integer, Win> windows = new TreeMap<>();
    public final EnumMap<Element.Type, int[]> inventory = new EnumMap<>(Element.Type.class);   // {placed (in course), on route / reaching route (active), scored}
    public final TreeMap<String, int[]> kindStats = new TreeMap<>();   // kind -> {tried, accepted (final course), refused, fallback picks}
    public final List<String[]> ruleChecks = new ArrayList<>();
    /** Rules 13: the progression state the last slice handed on (completed castles with the factors they were built with). */
    float[] lastProg;
    /** Rules 13: every fallback and selection-guard outcome the generator tallies, per slice (the refused modules' own reasons are in modules.csv). */
    static final String[] FALLBACK_KEYS = {"challenge_fallback_ok", "challenge_fallback_failed", "threat_fallback_ok", "threat_fallback_failed", "fallback_at_demand_ok", "fallback_at_demand_failed",
        "rope_fallback_after_recent", "adaptive_rope_fallback_demoted", "continuation_refused", "continuation_backtrack", "backtrack", "progression_hot_window", "adaptive_picks", "adaptive_shift", "adaptive_rope_avoided"};
    final List<String[]> fallbackRows = new ArrayList<>();
    public final List<String[]> chunks = new ArrayList<>(), hazardRows = new ArrayList<>(), forkRows = new ArrayList<>(), forkCountRows = new ArrayList<>(), platformRows = new ArrayList<>();
    /** Traversal variety (owner 2026-10-26, release requirement): route links in climbing order {castle, y0, y1, arc m, rope rise m, challenge 0/1, rope link 0/1}, and the accepted modules {castle, kind, role}. */
    final List<double[]> travLinks = new ArrayList<>(); final List<String[]> travMods = new ArrayList<>();
    static final String[] FORK_COUNTS = {"fork_try", "fork_ok", "padfork_try", "padfork_ok", "subfork_eligible", "subfork_try", "subfork_ok", "subfork_refused"};

    static boolean enemy(Element h) { return h.type == Element.Type.CRAB || h.type == Element.Type.BEE || h.type == Element.Type.SKULL || h.type == Element.Type.BLOB || h.type == Element.Type.ENFORCER; }          // (ENFORCER: PUNISHER only)
    static boolean terrainDemand(Element e) { return e.crumbles() || e.isMoving() || e.type == Element.Type.RAMP || e.type == Element.Type.SEESAW || e.type == Element.Type.SWING || e.wet(); }

    /**
     * Observe-only safety telemetry (owner difficulty clarification 2026-10-26): every main-route platform of the slice, classed as SAFE (no hazard on
     * it, no enemy that reaches it, plain footing), TERRAIN (demanding footing only: crumbling, moving, ramp, seesaw, swing, wet), HAZARD (a static hazard
     * anchored on it) or CONTESTED (an enemy reaches it; may also carry hazards), with its distinct threat families and whether it is a checkpoint.
     * Chain lengths and recovery opportunities are derived from consecutive rows (tools/ci).
     */
    void safetyTelemetry(int k, CourseGenerator.Framed f, Tuning t) {
        Course c = f.c; int rs = c.routeSize();
        for (int i = 0; i < rs; i++) {
            Element p = c.get(i); if (!p.isPlatform() || p.anchor >= 0 || p.type == Element.Type.GOAL) continue;
            int haz = 0, en = 0; java.util.TreeSet<String> fam = new java.util.TreeSet<>();
            for (Element h : c.hazards) {
                if (h.isHazard() && h.anchor == i) { haz++; fam.add(h.type.name()); }
                else if (enemy(h) && h.anchor >= 0 && Math.abs(h.anchor - i) <= 4 && EncounterModel.reaches(c, t, h, i)) { en++; fam.add(h.type.name()); }
            }
            String cls = en > 0 ? "CONTESTED" : haz > 0 ? "HAZARD" : terrainDemand(p) ? "TERRAIN" : "SAFE";
            double y = f.yBase + p.y;
            platformRows.add(new String[]{"" + k, "" + i, f3(y), "" + castleOf(y), p.type.name(), "" + p.checkpoint, "" + haz, "" + en, "" + fam.size(), q(String.join(" ", fam)), cls});
        }
    }

    /** Observe-only fork telemetry for one slice: every fork with its parent, depth, take-off and rejoin, mechanics and (information only) lengths. */
    void forkTelemetry(int k, CourseGenerator.Framed f, GenTrace tr) {
        Course c = f.c; int rs = c.routeSize();
        for (int n = 0; n < c.forks.size(); n++) {
            int[] fk = c.forks.get(n);
            int parent = -1; for (int m = 0; m < c.forks.size(); m++) { int[] g = c.forks.get(m); if (m != n && fk[0] >= g[1] && fk[0] < g[1] + g[2]) parent = m; }
            int depth = 1; for (int p = parent, guard = 0; p >= 0 && guard < 16; guard++) { depth++; int[] g = c.forks.get(p); int pp = -1; for (int m = 0; m < c.forks.size(); m++) { int[] h = c.forks.get(m); if (m != p && g[0] >= h[1] && g[0] < h[1] + h[2]) pp = m; } p = pp; }
            String rejoin = fk[3] < rs ? "main_route" : "branch";
            java.util.TreeMap<String, Integer> types = new java.util.TreeMap<>(); for (int i = fk[1]; i < fk[1] + fk[2]; i++) types.merge(c.get(i).type.name(), 1, Integer::sum);
            StringBuilder ty = new StringBuilder(); for (Map.Entry<String, Integer> e : types.entrySet()) ty.append(ty.length() > 0 ? " " : "").append(e.getKey()).append('x').append(e.getValue());
            double branch = 0, route = 0; Element p = c.get(fk[0]);
            for (int i = fk[1]; i < fk[1] + fk[2]; i++) { branch += Math.hypot(c.dsWrap(p.s, c.get(i).s), c.get(i).y - p.y); p = c.get(i); }
            branch += Math.hypot(c.dsWrap(p.s, c.get(fk[3]).s), c.get(fk[3]).y - p.y);
            if (fk[0] < rs && fk[3] < rs) for (int i = fk[0]; i < fk[3]; i++) { Element u = c.get(i), v = c.get(c.nextRoute(i)); route += Math.hypot(c.dsWrap(u.s, v.s), v.y - u.y); }
            double y = f.yBase + c.get(fk[0]).y;
            forkRows.add(new String[]{"" + k, "" + n, "" + parent, "" + depth, fk[0] < rs ? "main_route" : "branch", "" + fk[0], rejoin, "" + fk[3], "" + fk[2], q(ty.toString()), f3(y), "" + castleOf(y), f3(branch), fk[0] < rs && fk[3] < rs ? f3(route) : ""});
        }
        String[] row = new String[FORK_COUNTS.length + 2]; row[0] = "" + k; row[1] = "" + castleOf(f.yBase + c.get(0).y);
        for (int i = 0; i < FORK_COUNTS.length; i++) row[i + 2] = "" + tr.counts.getOrDefault(FORK_COUNTS[i], 0);
        StringBuilder why = new StringBuilder(); for (Map.Entry<String, Integer> e : tr.counts.entrySet()) if (e.getKey().contains("_why_")) why.append(why.length() > 0 ? " " : "").append(e.getKey()).append('=').append(e.getValue());
        String[] r2 = java.util.Arrays.copyOf(row, row.length + 1); r2[row.length] = q(why.toString()); forkCountRows.add(r2);
    }
    /** PUNISHER Enforcers and spike-block avalanches per slice (owner 2026-10-27): placed (by tier / block count), refused and dropped by the generator,
     *  and what happened when the solver climbed the slice (Sim's report-only counters). Columns: see encounters.csv's header. */
    final List<String[]> encounterRows = new ArrayList<>();
    static final String ENCOUNTER_HEADER = "run,slice,y0,castle,enforcers_yellow,enforcers_orange,enforcers_red,enforcer_decks,enforcer_unfair,enforcer_dropped,avalanches,avalanche_blocks,avalanche_unfair,avalanche_dropped,"
        + "solved,enf_kick_tries,enf_kick_hits,enf_swat_tries,enf_swat_hits,enf_retreats,enf_falls,avl_fired,avl_skipped_cooldown,avl_blocks_released,avl_blocks_near_player,avl_blocks_in_traversal_area,avl_block_knocks,avl_hits,setbacks,enforcers_planned,avalanches_planned,avl_ricochets,avl_peak_active,avl_blocks_5,avl_blocks_6,avl_blocks_7,hot_window_guard,"
        + "enf_robots_alerted,enf_swats_considered,enf_swats_declined,enf_swats_risky,enf_land_own_deck,enf_land_neighbour_deck,enf_hops,enf_fall_edge,enf_fall_chase,enf_fall_no_deck";
    /**
     * NGH capacity-aware hazard control per castle (owner amendment 2026-10-27): hazard-module attempts and acceptance, the hazard points the accepted
     * modules added, acceptance per kind ("accepted/attempted"), the factor the controller requested and the one applied, how much was withheld and why,
     * and how far the castle's measured hazards fell short of its target. Empty for modes without the capacity control.
     */
    static String capacityColumns(Progression pr, int n, boolean partial, double short_) {
        float[] counts = null, hc = null;
        if (partial) { if (pr.hasCapacity()) { counts = pr.capCurrent(); hc = pr.hazardControlNow(); } }
        else for (int i = 0; i < pr.capRecordCount(); i++) { float[] r = pr.capRecord(i); if ((int) r[0] == n) { counts = java.util.Arrays.copyOfRange(r, 1, 1 + Progression.CAP_W); hc = java.util.Arrays.copyOfRange(r, 1 + Progression.CAP_W, 4 + Progression.CAP_W); } }
        if (counts == null) return ",,,,,,,,,,,,,";
        float at = 0, ok = 0, pts = 0; String[] kind = new String[Progression.CAP_K];
        for (int q = 0; q < Progression.CAP_K; q++) { at += counts[3 * q]; ok += counts[3 * q + 1]; pts += counts[3 * q + 2]; kind[q] = (int) counts[3 * q + 1] + "/" + (int) counts[3 * q]; }
        return String.join(",", "" + (int) at, "" + (int) ok, f3(at > 0 ? ok / at : Double.NaN), f3(pts), f3(at > 0 ? pts / at : Double.NaN), kind[0], kind[1], kind[2], kind[3],
            f3(hc[0]), f3(hc[1]), f3(hc[0] - hc[1]), Progression.HC_NAMES[Math.max(0, Math.min(3, (int) hc[2]))], f3(short_));
    }

    void encounterTelemetry(int k, CourseGenerator.Framed f, GenTrace tr, Autopilot.Report rep) {
        int[] tiers = new int[3]; int decks = 0, avl = 0, blocks = 0;
        for (Element h : f.c.hazards) {
            if (h.type == Element.Type.ENFORCER) { tiers[Math.max(0, Math.min(2, h.skin))]++; decks += Math.round(h.len); }
            if (h.type == Element.Type.AVALANCHE) { avl++; blocks += Math.round(h.len); }
        }
        Sim s = rep != null ? Autopilot.lastReal : null;
        double y0 = f.yBase + f.c.get(0).y;
        String[] o = s == null ? new String[15] : new String[]{"" + rep.completed, "" + s.enfKickTries, "" + s.enfKickHits, "" + s.enfSwatTries, "" + s.enfSwatHits, "" + s.enfRetreats, "" + s.enfFalls,
            "" + s.avlFired, "" + s.avlSkipped, "" + s.avlReleased, "" + s.avlNear, "" + s.avlArea, "" + s.avlKnocks, "" + s.avlHits, "" + s.setbacks()};
        if (s == null) java.util.Arrays.fill(o, "");
        int[] bn = new int[8]; for (Element h : f.c.hazards) if (h.type == Element.Type.AVALANCHE) bn[Math.max(0, Math.min(7, Math.round(h.len)))]++;
        // rules 14: planned vs placed (the slice's planned encounters are the placed ones plus those refused for good), ricochets, the most blocks in the air at once
        String[] o2 = {"" + (tiers[0] + tiers[1] + tiers[2] + tr.counts.getOrDefault("enforcer_dropped", 0)), "" + (avl + tr.counts.getOrDefault("avalanche_dropped", 0)),
            s == null ? "" : "" + s.avlRicochets, s == null ? "" : "" + s.avlPeak, "" + bn[5], "" + bn[6], "" + bn[7], "" + tr.counts.getOrDefault("progression_hot_window", 0),
            // rules 14 Enforcer judgment: robots that chased, swats sized up / declined (no landing it accepts) / taken although risky, where swats came down, hops, falls by cause
            s == null ? "" : "" + s.enfAlerts, s == null ? "" : "" + s.enfSwatConsidered, s == null ? "" : "" + s.enfSwatRejected, s == null ? "" : "" + s.enfSwatRisky,
            s == null ? "" : "" + s.enfLandOwn, s == null ? "" : "" + s.enfLandNear, s == null ? "" : "" + s.enfHops, s == null ? "" : "" + s.enfFallEdge, s == null ? "" : "" + s.enfFallChase, s == null ? "" : "" + s.enfFallNoDeck};
        String[] row = {MODE[mode] + "-s" + seed, "" + k, f3(y0), "" + castleOf(y0), "" + tiers[0], "" + tiers[1], "" + tiers[2], "" + decks,
            "" + tr.counts.getOrDefault("enforcer_unfair", 0), "" + tr.counts.getOrDefault("enforcer_dropped", 0), "" + avl, "" + blocks,
            "" + tr.counts.getOrDefault("avalanche_unfair", 0), "" + tr.counts.getOrDefault("avalanche_dropped", 0)};
        String[] all = java.util.Arrays.copyOf(row, row.length + o.length + o2.length); System.arraycopy(o, 0, all, row.length, o.length); System.arraycopy(o2, 0, all, row.length + o.length, o2.length);
        encounterRows.add(all);
    }
    public float reached; public int slices; public String stopped; public double genSeconds, solveSeconds; public boolean solve;
    public int failedSlice = -1;
    /** Independent geometry check of every generated slice in the absolute frame (GeometryIntegrity, against the previous slice too): {slice, violation}. */
    public final List<String[]> integrityViolations = new ArrayList<>();
    void integrity(int k, CourseGenerator.Framed pf, CourseGenerator.Framed f) {
        Course frame = new Course(f.c.seed, f.c.circumference);
        for (int i = 1; i < f.c.size(); i++) frame.add(abs(f.c.get(i), f));          // (element 0 is the previous slice's closing rest, checked with it)
        for (Element h : f.c.hazards) frame.hazards.add(abs(h, f));
        List<Element> pe = new ArrayList<>(), ph = new ArrayList<>();
        if (pf != null) { for (int i = 0; i < pf.c.size(); i++) pe.add(abs(pf.c.get(i), pf)); for (Element h : pf.c.hazards) ph.add(abs(h, pf)); }
        for (String v : GeometryIntegrity.check(frame, 0, pe, ph, 20)) integrityViolations.add(new String[]{"" + k, q(v)});
    }
    static Element abs(Element e, CourseGenerator.Framed f) {
        Element a = new Element(e.type, (float) (f.sBase + e.s), (float) (f.yBase + e.y), e.w);
        a.len = e.len; a.amp = e.amp; a.period = e.period; a.phase = e.phase; a.skin = e.skin; a.color = e.color; a.anchor = -1; a.dir = e.dir; a.zone = e.zone;
        return a;
    }
    /** The validation gate's verdicts, each on its own: report execution, generation, solver, geometric integrity (performance is reported, not gated). */
    public Map<String, String> verdicts() {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("report_execution", "COMPLETED");
        v.put("generation", stopped == null && reached >= top ? "COMPLETE" : "INCOMPLETE: reached " + Math.round(reached) + " of " + Math.round(top) + " m" + (failedSlice >= 0 ? " (slice " + failedSlice + ")" : "") + (stopped == null ? "" : " - " + stopped));
        int failed = 0, unreached = 0, notDone = 0; for (String[] s : solverSlices) if (!"true".equals(s[3])) notDone++;
        for (Win w : windows.values()) { failed += w.linksFailed; unreached += w.linksUnreached; }
        v.put("solver", !solve ? "NOT_RUN" : notDone == 0 && failed == 0 && unreached == 0 ? "COMPLETE (" + solverSlices.size() + " slices)" : "FAILED: " + notDone + " slices not completed, " + failed + " links failed, " + unreached + " unreached");
        v.put("geometric_integrity", integrityViolations.isEmpty() ? "CLEAN (" + slices + " slices)" : "VIOLATIONS: " + integrityViolations.size());
        v.put("performance", String.format(Locale.ROOT, "generation %.0f s (%.2f s per slice), solver %.0f s", genSeconds, genSeconds / Math.max(1, slices), solveSeconds));
        return v;
    }
    /** Whether the course passes the validation gate (an incomplete course fails it, whatever its partial results look like). */
    public boolean passes() {
        Map<String, String> v = verdicts();
        return v.get("generation").equals("COMPLETE") && !v.get("solver").startsWith("FAILED") && v.get("geometric_integrity").startsWith("CLEAN");
    }
    final DifficultyScore v2;
    private final Set<String> seenHaz = new HashSet<>();

    public LaneReport(Tuning t, int mode, long seed, float top) {
        this.t = t; this.mode = mode; this.seed = seed; this.top = top; v2 = new DifficultyScore(t);
        for (Element.Type k : Element.Type.values()) { Lanes.lane(k); inventory.put(k, new int[3]); }
        for (String[] k : Lanes.KINDS) kindStats.put(k[0], new int[4]);
        for (String[] k : Lanes.STRUCTURES) kindStats.put(k[0], new int[4]);
    }

    static Win win(TreeMap<Integer, Win> m, int i) { return m.computeIfAbsent(i, j -> { Win w = new Win(); w.index = j; return w; }); }
    Win win(int i) { return win(windows, i); }
    static int wi(double y) { return Math.max(0, (int) Math.floor(y / WINDOW)); }      // (anything below the start platform, e.g. a mortar in the ground, belongs to window 0)

    // ------------------------------------------------------------------ run
    public void run(boolean solve, double budget) {
        this.solve = solve; long t0 = System.nanoTime();
        GenTrace.clock = System::nanoTime;          // the reports measure wall time; the sim package itself never reads a clock
        CourseGenerator.Framed f = null; int k = 0;
        try {
            while (f == null || f.yBase + f.c.get(f.c.routeSize() - 1).y < top) {
                GenTrace tr = new GenTrace(); GenTrace.GEN.set(tr); long g0 = System.nanoTime(), ga = alloc();
                CourseGenerator.Framed pf = f;
                try { f = CourseGenerator.chunkFramed(seed, k, f, t); }
                catch (RuntimeException e) { for (String s : tr.sliceFailures) sliceFailures.add(new String[]{"" + k, q(s)}); genSeconds += (System.nanoTime() - g0) / 1e9; failedSlice = k; throw e; }          // the slice that stopped generation keeps its attempts' reasons
                finally { GenTrace.GEN.remove(); }
                integrity(k, pf, f);
                double gs = (System.nanoTime() - g0) / 1e9; long gal = ga < 0 ? -1 : alloc() - ga; genSeconds += gs;
                GenTrace.Solve st = null; Autopilot.Report rep = null; double secs = Double.NaN; long sal = -1;
                if (solve) {
                    st = new GenTrace.Solve(); GenTrace.SOLVE.set(st); long s0 = System.nanoTime(), sa = alloc();
                    try { rep = Autopilot.run(f.c, t, 4000f); } catch (RuntimeException e) { rep = new Autopilot.Report(); rep.failInfo = "solver threw " + e + " at " + frames(e); } finally { GenTrace.SOLVE.remove(); }
                    secs = (System.nanoTime() - s0) / 1e9; solveSeconds += secs; sal = sa < 0 ? -1 : alloc() - sa;
                    if (!rep.completed && (rep.failInfo == null || rep.failInfo.isEmpty())) rep.failInfo = derivedFailInfo(rep, st);
                    solverSlices.add(new String[]{"" + k, f3(f.yBase + f.c.get(0).y), f3(f.yBase + f.c.get(f.c.routeSize() - 1).y), "" + rep.completed, "" + rep.links, "" + rep.failedLink, f3(rep.simTime), f3(rep.minMargin), "" + rep.worstLink, f3(secs), q(rep.failInfo), failClass(rep.completed, rep.failInfo)});
                }
                int nf = solverFailures.size();
                measure(k, f, tr, rep, st);
                if (rep != null && !rep.completed && solverFailures.size() == nf)          // every unsuccessful slice has a failure row, also when no route link was marked FAILED (a solver exception, an invalid link)
                    solverFailures.add(new String[]{"" + k, "" + rep.failedLink, "-", "-", "-", f3(f.yBase + f.c.get(0).y), f3(f.yBase + f.c.get(f.c.routeSize() - 1).y), "" + castleOf(f.yBase + f.c.get(0).y), q(rep.failInfo)});
                { long okN = 0, badN = 0; int bad = 0; for (long[] a : tr.attemptNanos) { if (a[2] == 1) okN += a[1]; else { badN += a[1]; bad++; } }
                  long acc = 0, rej = 0, fb = 0; int na = 0, nr = 0; for (GenTrace.Module m : tr.modules) { if (m.ok && m.attempt == tr.finalAttempt) { acc += m.nanos; na++; } else { rej += m.nanos; nr++; } if ("FALLBACK".equals(m.role)) fb += m.nanos; }
                  Runtime rt = Runtime.getRuntime();
                  chunks.add(new String[]{"" + k, f3(f.yBase + f.c.get(0).y), f3(f.yBase + f.c.get(f.c.routeSize() - 1).y), "" + castleOf(f.yBase + f.c.get(0).y), f3(gs), "" + tr.attemptNanos.size(), "" + bad,
                    f3(okN / 1e9), f3(badN / 1e9), "" + na, f3(acc / 1e9), "" + nr, f3(rej / 1e9), f3(fb / 1e9), "" + tr.relaxed, gal < 0 ? "" : "" + gal, f3(secs), sal < 0 ? "" : "" + sal, "" + (rt.totalMemory() - rt.freeMemory()),
                    f3(tr.phaseNanos.getOrDefault("extend", 0L) / 1e9), f3(tr.phaseNanos.getOrDefault("closing_rest", 0L) / 1e9), f3(tr.phaseNanos.getOrDefault("decoys_forks", 0L) / 1e9), f3(tr.phaseNanos.getOrDefault("rooms", 0L) / 1e9),
                    f3(tr.phaseNanos.getOrDefault("forks_reserved", 0L) / 1e9), f3(tr.phaseNanos.getOrDefault("forks_open", 0L) / 1e9), f3(tr.phaseNanos.getOrDefault("key_rooms", 0L) / 1e9)}); }
                encounterTelemetry(k, f, tr, rep);
                { String[] r = new String[FALLBACK_KEYS.length + 3]; r[0] = "" + k; r[1] = f3(f.yBase + f.c.get(0).y); r[2] = "" + castleOf(f.yBase + f.c.get(0).y);
                  for (int i = 0; i < FALLBACK_KEYS.length; i++) r[i + 3] = "" + tr.counts.getOrDefault(FALLBACK_KEYS[i], 0); fallbackRows.add(r); }
                forkTelemetry(k, f, tr);
                safetyTelemetry(k, f, t);
                v2.add(f.c, (float) f.yBase);
                if (f.c.prog != null) lastProg = f.c.prog;
                reached = (float) (f.yBase + f.c.get(f.c.routeSize() - 1).y); k++;
                if ((System.nanoTime() - t0) / 1e9 > budget) { stopped = "time budget of " + (int) budget + " s reached (partial results)"; break; }
            }
        } catch (RuntimeException e) { stopped = "GENERATION STOPPED: " + e; }
        slices = k;
        finish();
    }

    void measure(int k, CourseGenerator.Framed f, GenTrace tr, Autopilot.Report rep, GenTrace.Solve st) {
        Course c = f.c; double yb = f.yBase, sb = f.sBase; int rs = c.routeSize();
        for (String s : tr.sliceFailures) sliceFailures.add(new String[]{"" + k, q(s)});
        // modules: every attempt is a diagnostic row; the final attempt's accepted ones own elements (a later module wins an index re-used after a rollback)
        String[] owner = new String[c.size()], ownerKind = new String[c.size()]; int mi = 0;
        for (GenTrace.Module m : tr.modules) {
            String id = k + ":m" + (mi++); boolean fin = m.attempt == tr.finalAttempt;
            int[] ks = kindStats.computeIfAbsent(m.kind, x -> new int[4]); ks[0]++; if (!m.ok) ks[2]++; if ("FALLBACK".equals(m.role) && m.ok) ks[3]++;
            int e0 = m.e0 - tr.offset, e1 = m.e1 - tr.offset;
            if (fin && m.ok) { ks[1]++; travMods.add(new String[]{"" + castleOf(m.y), m.kind, m.role}); for (int i = Math.max(0, e0); i < Math.min(c.size(), e1); i++) { owner[i] = id; ownerKind[i] = m.kind; } }
            modules.add(new String[]{id, "" + k, "" + m.attempt, "" + fin, m.kind, m.role, m.picked == null ? "" : m.picked, f3(m.d), f3(m.y), "" + m.ok, "" + e0, "" + e1, "" + (m.h1 - m.h0),
                "" + m.fails[0], "" + m.fails[1], "" + m.fails[2], "" + m.fails[3], "" + m.fails[4], "" + m.fails[5], f3(m.nanos / 1e6)});
            if (!m.ok) { Win w = win(wi(m.y)); w.rejected++; }
            if ("FALLBACK".equals(m.role) && m.ok && fin) win(wi(m.y)).fallbacks++;
        }
        // elements (index 0 of a later slice is the previous slice's last platform: counted once)
        Map<Integer, El> byIdx = new HashMap<>();
        for (int i = (k == 0 ? 0 : 1); i < c.size(); i++) {
            Element e = c.get(i); El r = new El(); r.e = e; r.slice = k; r.idx = i; r.id = k + ":" + i; r.type = e.type.name(); r.lane = Lanes.lane(e.type).name();
            r.route = i < rs && e.anchor < 0; r.sAbs = sb + e.s; r.yAbs = yb + e.y; r.window = wi(r.yAbs);
            if (owner[i] != null) { r.module = owner[i]; r.moduleKind = ownerKind[i]; } else if (!r.route) r.moduleKind = "DECOY"; else if (k == 0 && i == 0) { r.moduleKind = "START"; r.structure = "start platform"; }
            if (!r.route) win(r.window).decoys++;
            elements.add(r); byIdx.put(i, r); inventory.get(e.type)[0]++; if (r.route) inventory.get(e.type)[1]++;
        }
        for (int i = 0; i < c.size(); i++) if (owner[i] == null && byIdx.containsKey(i) && byIdx.get(i).route && !(k == 0 && i == 0)) win(byIdx.get(i).window).flags.add("MODULE_UNATTRIBUTED");
        // structural classification (derived from module kind and what sits on each platform; the cannon pillar is render-only)
        Map<Integer, Set<Element.Type>> on = new HashMap<>();
        for (Element h : c.hazards) if (h.anchor >= 0) on.computeIfAbsent(h.anchor, x -> EnumSet.noneOf(Element.Type.class)).add(h.type);
        for (El r : byIdx.values()) {
            Set<Element.Type> s = on.getOrDefault(r.idx, Collections.emptySet());
            boolean dedicated = s.contains(Element.Type.SPIKE_DROP) || s.contains(Element.Type.MORTAR) || s.contains(Element.Type.CANNON) || s.contains(Element.Type.AIMED);
            if ("DROPS".equals(r.moduleKind)) r.structure = s.contains(Element.Type.SPIKE_DROP) ? "spike-drop row platform" : "spike-drop row intermediate platform";
            else if ("MORTAR".equals(r.moduleKind)) r.structure = s.contains(Element.Type.MORTAR) ? "mortar-row firing platform" : "mortar-row island/intermediate platform";
            else if (s.contains(Element.Type.SPIKE_DROP)) r.structure = "individual spike-drop platform";
            else if (s.contains(Element.Type.CANNON) || s.contains(Element.Type.AIMED)) r.structure = "cannon platform (pillar render-only)";
            else if ("START".equals(r.moduleKind)) r.structure = "start platform";
            else if ("REST".equals(r.moduleKind) || "CASTLE".equals(r.moduleKind) || "GEM".equals(r.moduleKind)) r.structure = r.moduleKind.toLowerCase(Locale.ROOT) + " structure";
            else if (!r.route) r.structure = "decoy / branch";
            else if (dedicated) r.structure = "hazard-carrying platform";
        }
        // solver per link: time between the solver's arrivals at successive route links, setbacks and refused plans in between
        Map<Integer, double[]> solved = new HashMap<>();
        if (st != null) {
            for (int a = 0; a < st.arrivals.size(); a++) {
                float[] x = st.arrivals.get(a), nx = a + 1 < st.arrivals.size() ? st.arrivals.get(a + 1) : null;
                double dt = nx != null ? nx[1] - x[1] : rep.simTime - x[1]; int sbk = nx != null ? (int) (nx[2] - x[2]) : 0;
                double dw = a + 1 < st.wall.size() ? (st.wall.get(a + 1) - st.wall.get(a)) / 1e9 : Double.NaN;
                double[] prev = solved.get((int) x[0]);
                solved.put((int) x[0], new double[]{(prev == null ? 0 : prev[0]) + dt, (prev == null ? 0 : prev[1]) + sbk, (prev == null ? 0 : prev[2]) + (Double.isNaN(dw) ? 0 : dw), (prev == null ? 0 : prev[3]) + 1});
            }
        }
        Map<Integer, Integer> planFails = new HashMap<>(); if (st != null) for (int[] p : st.planFails) planFails.merge(p[0], p[1], Integer::sum);
        // hazards, enemies, clubs and utility
        Course bare = new Course(c.seed, c.circumference); for (int i = 0; i < c.size(); i++) bare.add(c.get(i)); bare.routeCount = c.routeCount; bare.indexDecoys();
        Map<Integer, Set<Element.Type>> hazOn = new HashMap<>(), enemyAt = new HashMap<>();
        for (int hi = 0; hi < c.hazards.size(); hi++) {
            Element h = c.hazards.get(hi); Lanes.Lane ln = Lanes.lane(h.type);
            double sa = ((sb + h.s) % c.circumference + c.circumference) % c.circumference;
            if (!seenHaz.add(h.type + ":" + Math.round(sa * 100) + ":" + Math.round((yb + h.y) * 100))) continue;
            El r = new El(); r.e = h; r.slice = k; r.idx = hi; r.id = k + ":h" + hi; r.type = h.type.name(); r.lane = ln.name(); r.sAbs = sb + h.s; r.yAbs = yb + h.y;
            El home = byIdx.get(h.anchor); if (home == null && h.anchor == 0 && k > 0) home = homeOfContext(yb + c.get(0).y);
            r.window = home != null ? home.window : wi(r.yAbs); r.module = home != null ? home.module : ""; r.moduleKind = home != null ? home.moduleKind : "";
            r.structure = home != null ? home.structure : "";
            boolean onRoute = h.anchor >= 0 && h.anchor < rs && c.get(h.anchor).anchor < 0; r.route = onRoute;
            hazards.add(r); inventory.get(h.type)[0]++;
            {   // observed hazard/enemy detail: parameters the Sim reads, route exposure, threats sharing the platform, enemy reach
                int exposed = 0; for (int i = 0; i < rs - 1; i++) if (c.get(i).anchor < 0 && (i == h.anchor || c.nextRoute(i) == h.anchor)) exposed++;
                Set<Element.Type> share = EnumSet.noneOf(Element.Type.class); for (Element o : c.hazards) if (o != h && o.anchor == h.anchor && Lanes.lane(o.type) != Lanes.Lane.UTILITY) share.add(o.type);
                int reachN = 0; TreeSet<Integer> reachW = new TreeSet<>();
                if (ln == Lanes.Lane.ENEMIES) for (int p = Math.max(0, h.anchor - 4); p <= Math.min(rs - 1, h.anchor + 4); p++) if (c.get(p).anchor < 0 && EncounterModel.reaches(c, t, h, p)) { reachN++; reachW.add(wi(yb + c.get(p).y)); }
                String status = ln == Lanes.Lane.HAZARDS ? (onRoute ? "SCORED" : "OFF_ROUTE") : ln == Lanes.Lane.ENEMIES ? (reachN > 0 ? "SCORED" : "UNREACHABLE") : h.type == Element.Type.CLUB ? (onRoute ? "MITIGATION" : "OFF_ROUTE") : "UTILITY";
                hazardRows.add(new String[]{r.id, r.type, r.lane, status, "" + onRoute, home == null ? "" : home.id, q(r.structure), r.moduleKind, "" + r.window, "" + castleOf(r.yAbs), f3(r.sAbs), f3(r.yAbs),
                    f3(h.w), f3(h.amp), f3(h.period), f3(h.phase), f3(h.len), "" + h.dir, f3(radius(h.type)), "" + exposed, q(share.toString()), ln == Lanes.Lane.ENEMIES ? "" + reachN : "", ln == Lanes.Lane.ENEMIES ? q(reachW.toString()) : ""});
            }
            if (ln == Lanes.Lane.HAZARDS) {
                if (!onRoute) { win(r.window).offRouteHazards++; continue; }
                inventory.get(h.type)[1]++; inventory.get(h.type)[2]++;
                hazOn.computeIfAbsent(h.anchor, x -> EnumSet.noneOf(Element.Type.class)).add(h.type);
                add(r.window, "HAZARDS", "H-" + h.type, r.id, "hazard on route platform " + (home == null ? h.anchor : home.id), 1, DifficultyScore.points(h.type), 1, h.type);
            } else if (ln == Lanes.Lane.ENEMIES) {
                List<Integer> reach = new ArrayList<>();
                for (int p = Math.max(0, h.anchor - 4); p <= Math.min(rs - 1, h.anchor + 4); p++) if (c.get(p).anchor < 0 && EncounterModel.reaches(c, t, h, p)) reach.add(p);
                if (reach.isEmpty()) { win(r.window).unreachableEnemies++; continue; }
                inventory.get(h.type)[1]++; inventory.get(h.type)[2]++;
                for (int p : reach) enemyAt.computeIfAbsent(p, x -> EnumSet.noneOf(Element.Type.class)).add(h.type);
                Map<Integer, Integer> per = new TreeMap<>(); for (int p : reach) per.merge(wi(yb + c.get(p).y), 1, Integer::sum);
                for (Map.Entry<Integer, Integer> e : per.entrySet())
                    add(e.getKey(), "ENEMIES", "E-" + h.type, r.id, "reaches " + reach.size() + " route platform(s), " + e.getValue() + " in this window", reach.size(), DifficultyScore.points(h.type), e.getValue() / (double) reach.size(), h.type);
                // owner future-rule checks (report only)
                boolean homeDedicated = home != null && (home.structure.startsWith("spike-drop") || home.structure.startsWith("mortar") || home.structure.startsWith("cannon") || home.structure.startsWith("individual spike"));
                boolean pursuitIntoHazard = false; for (int p : reach) { El pr = byIdx.get(p); if (p != h.anchor && pr != null && (pr.structure.startsWith("spike-drop row platform") || pr.structure.startsWith("mortar-row firing") || pr.structure.startsWith("individual spike") || pr.structure.startsWith("cannon"))) pursuitIntoHazard = true; }
                if (home != null && (home.structure.contains("intermediate") || home.structure.contains("island"))) ruleChecks.add(new String[]{"R1-intermediate-enemy", r.id, h.type.name(), f3(r.yAbs), "" + castleOf(r.yAbs), "enemy on an intermediate platform of a hazard row"});
                if (homeDedicated && !home.structure.contains("intermediate") && !home.structure.contains("island")) ruleChecks.add(new String[]{"R3-protected-structure", r.id, h.type.name(), f3(r.yAbs), "" + castleOf(r.yAbs), "enemy directly on a dedicated hazard structure: " + home.structure});
                if (pursuitIntoHazard) ruleChecks.add(new String[]{"R2-pursuit-into-hazard", r.id, h.type.name(), f3(r.yAbs), "" + castleOf(r.yAbs), "reach includes a dedicated hazard platform"});
            } else if (h.type == Element.Type.CLUB) {
                if (onRoute) { inventory.get(h.type)[1]++; add(r.window, "MITIGATION", "M-CLUB", r.id, "club on route platform", 1, 1, 1, h.type); }
            } else add(r.window, "UTILITY", "U-" + h.type, r.id, "progression element (no difficulty points)", 1, 0, 1, h.type);
        }
        // route links: terrain (game's own EncounterModel rules plus v3 precision rules), interactions, solver outcome
        for (int i = 0; i < rs - 1; i++) {
            if (c.get(i).anchor >= 0) continue;
            int j = c.nextRoute(i); if (j < 0 || j >= rs) continue;
            Element a = c.get(i), b = c.get(j); Link L = new Link();
            L.slice = k; L.from = i; L.to = j; L.fromId = k + ":" + i; L.toId = k + ":" + j; L.fromType = a.type.name(); L.toType = b.type.name();
            L.y0 = yb + a.y; L.y1 = yb + b.y; L.dy = b.y - a.y;
            apportion(L);
            EncounterModel.Link em = EncounterModel.link(c, t, i), eb = EncounterModel.link(bare, t, i);
            L.emJump = eb.jump; L.emPosition = eb.position; L.emWait = eb.wait; L.emCommit = eb.commit; L.emRecover = em.recover; L.emPursue = em.pursue; L.emScore = em.score;
            List<Object[]> T = new ArrayList<>();       // {rule, property, raw, weight}
            if (a.isPlatform() && b.isPlatform()) {
                L.gap = Math.max(0f, Math.abs(c.dsWrap(b.s, a.s)) - a.halfW() - b.halfW()); L.reach = EncounterModel.reach(t, b.y - a.y);
                L.ratio = L.reach > 0 ? Math.min(1.2, L.gap / L.reach) : 1.2; L.landW = b.w;
                T.add(new Object[]{"T-GAP", "gap / longest jump at this rise", L.ratio, 2.0 * clamp((L.ratio - 0.4) / 0.6)});
                if (L.ratio > 0.85) T.add(new Object[]{"T-LONG", "long jump / grab beyond 85% of reach (EncounterModel)", L.ratio, 3.0 * (L.ratio - 0.85)});
                T.add(new Object[]{"T-LAND", "usable landing width (m)", (double) b.w, 1.5 * clamp((3.0 - b.w) / 2.0)});
            } else T.add(new Object[]{"T-TRAVERSE", "rope/cable link (owner-approved 0)", 0.0, 0.0});
            if (a.type == Element.Type.PAD || a.type == Element.Type.SPRING) T.add(new Object[]{"T-LAUNCH", "pad/spring used to travel (owner-approved 0)", 0.0, 0.0});
            if (a.isMoving()) T.add(new Object[]{"T-MOVE-TAKEOFF", "moving take-off (EncounterModel)", (double) a.amp, 0.5});
            if (a.type == Element.Type.SEESAW || a.type == Element.Type.RAMP) T.add(new Object[]{"T-TILT-TAKEOFF", a.type + " take-off (EncounterModel)", (double) a.skin, 0.6});
            if (a.type == Element.Type.RAMP && a.skin >= 2) T.add(new Object[]{"T-RAMP-VARIANT", "ramp skin " + a.skin + " (shaking/spinning/conveyor)", (double) a.skin, 0.4});
            if (b.isMoving() || b.type == Element.Type.SWING) T.add(new Object[]{"T-MOVE-TARGET", "moving/swinging target, period " + b.period + " s (EncounterModel)", (double) b.period, 0.8});
            if (a.crumbles() || a.holed()) T.add(new Object[]{"T-CRUMBLE", "crumbling or holed footing (EncounterModel)", 1.0, 0.7});
            if (a.wet()) T.add(new Object[]{"T-WET", "slippery footing (EncounterModel)", 1.0, 0.4});
            double tsum = 0; for (Object[] x : T) tsum += (double) x[3]; L.terrain = tsum;
            for (Object[] x : T) for (int q = 0; q < L.windows.length; q++)
                addRow(L.windows[q], "TERRAIN", (String) x[0], L.fromId + ">" + L.toId, (String) x[1], (double) x[2], (double) x[3], L.fractions[q], 1, null);
            L.hazards.addAll(hazOn.getOrDefault(i, Collections.emptySet())); L.hazards.addAll(hazOn.getOrDefault(j, Collections.emptySet()));
            L.enemies.addAll(enemyAt.getOrDefault(i, Collections.emptySet())); L.enemies.addAll(enemyAt.getOrDefault(j, Collections.emptySet()));
            List<String> X = new ArrayList<>();
            if (L.terrain >= TERRAIN_DEMANDING && !L.hazards.isEmpty()) X.add("X-TERRAIN-HAZARD");
            if (L.terrain >= TERRAIN_DEMANDING && !L.enemies.isEmpty()) X.add("X-TERRAIN-ENEMY");
            if (!L.hazards.isEmpty() && !L.enemies.isEmpty()) X.add("X-HAZARD-ENEMY");
            if (L.hazards.size() >= 2) X.add("X-HAZARD-HAZARD");
            if (L.enemies.size() >= 2) X.add("X-ENEMY-ENEMY");
            {   // a meaningful challenge: demanding terrain, a hazard or an enemy on the link (a rope climb or a plain hop is ordinary traversal)
                boolean challenge = L.terrain >= TERRAIN_DEMANDING || !L.hazards.isEmpty() || !L.enemies.isEmpty();
                travLinks.add(new double[]{castleOf(L.y0), L.y0, L.y1, Math.abs(c.dsWrap(b.s, a.s)), a.type == Element.Type.ROPE ? Math.max(0, a.y - (a.y - a.len)) : 0, challenge ? 1 : 0, a.type == Element.Type.ROPE || b.type == Element.Type.ROPE ? 1 : 0});
            }
            for (String x : X) for (int q = 0; q < L.windows.length; q++)
                addRow(L.windows[q], "INTERACTION", x, L.fromId + ">" + L.toId, "hazards " + L.hazards + " enemies " + L.enemies + " terrain " + f3(L.terrain), 1, X_BONUS, L.fractions[q], 1, null);
            for (int q = 0; q < L.windows.length; q++) {
                Win w = win(L.windows[q]); w.geometry = true; w.linksTotal++; if (L.terrain >= TERRAIN_DEMANDING) w.demandingLinks++;
                if (!Double.isNaN(L.ratio)) w.maxRatio = Double.isNaN(w.maxRatio) ? L.ratio : Math.max(w.maxRatio, L.ratio);
                if (!Double.isNaN(L.landW)) w.minLandW = Double.isNaN(w.minLandW) ? L.landW : Math.min(w.minLandW, L.landW);
            }
            if (rep != null) {
                double[] sv = solved.get(i); int pf = planFails.getOrDefault(i, 0); L.planFails = pf;
                if (rep.failedLink == i) L.status = "FAILED"; else if (sv != null) L.status = "TRAVERSED"; else if (!rep.completed && (rep.failedLink < 0 || i > rep.failedLink)) L.status = "UNREACHED"; else L.status = "TRAVERSED_IN_PASSING";
                if (sv != null) { L.solveTime = sv[0]; L.setbacks = (int) sv[1]; L.solveWall = sv[2]; L.visits = (int) sv[3]; }
                for (int q = 0; q < L.windows.length; q++) {
                    Win w = win(L.windows[q]);
                    if (L.status.startsWith("TRAVERSED")) w.linksTraversed++; else if ("FAILED".equals(L.status)) w.linksFailed++; else w.linksUnreached++;
                    if (!Double.isNaN(L.solveTime)) w.solveTime += L.solveTime * L.fractions[q];
                    if (!Double.isNaN(L.solveWall)) w.solveWall += L.solveWall * L.fractions[q];
                    if (L.setbacks > 0 && q == 0) w.setbacks += L.setbacks; if (q == 0) w.planFails += pf;
                }
                if ("FAILED".equals(L.status)) solverFailures.add(new String[]{"" + k, L.fromId, L.toId, L.fromType, L.toType, f3(L.y0), f3(L.y1), "" + castleOf(L.y0), q(rep.failInfo)});
            }
            links.add(L);
        }
        for (El r : byIdx.values()) {
            Win w = win(r.window); w.modules.merge(r.moduleKind, 1, Integer::sum);
            if (!r.route) continue;
            Element e = r.e; String m = e.type.name() + (e.type == Element.Type.RAMP ? "/skin" + e.skin : e.type == Element.Type.STATIC && e.skin == 1 ? (e.holed() ? "/holed-bridge" : "/bridge") : e.wet() ? "/wet" : e.crumbles() && e.type != Element.Type.CRUMBLE ? "/crumbling" : "");
            w.mech.merge(m, 1, Integer::sum);
            if (e.checkpoint || r.structure.startsWith("rest") || r.structure.startsWith("castle") || r.structure.startsWith("gem")) w.recovery++;
        }
    }

    private El homeOfContext(double y) { for (int i = elements.size() - 1; i >= 0; i--) if (Math.abs(elements.get(i).yAbs - y) < 1e-3 && elements.get(i).route) return elements.get(i); return null; }

    void apportion(Link L) {
        double lo = Math.min(L.y0, L.y1), hi = Math.max(L.y0, L.y1);
        if (hi - lo < 1e-3 || wi(lo) == wi(hi)) { L.windows = new int[]{wi(lo)}; L.fractions = new double[]{1}; return; }
        int a = wi(lo), b = wi(hi); L.windows = new int[b - a + 1]; L.fractions = new double[b - a + 1]; double span = hi - lo, sum = 0;
        for (int w = a; w <= b; w++) { double o = Math.min(hi, (w + 1) * WINDOW) - Math.max(lo, w * WINDOW); L.windows[w - a] = w; L.fractions[w - a] = Math.max(0, o) / span; sum += L.fractions[w - a]; }
        for (int q = 0; q < L.fractions.length; q++) L.fractions[q] /= sum;
    }

    void add(int window, String lane, String rule, String source, String property, double raw, double weight, double fraction, Element.Type countType) {
        addRow(window, lane, rule, source, property, raw, weight, fraction, 1, countType);
    }
    void addRow(int window, String lane, String rule, String source, String property, double raw, double weight, double fraction, double rep, Element.Type countType) {
        Row r = new Row(); r.window = window; r.lane = lane; r.rule = rule; r.source = source; r.property = property; r.raw = raw; r.weight = weight; r.fraction = fraction; r.repetition = rep;
        ledger.add(r); if (countType != null) win(window).counts.merge(countType, fraction, Double::sum);
    }

    /** Repetition (HAZARDS and ENEMIES: first 3 identical per window full, then a quarter), interaction cap, window totals from their rows. */
    void finish() {
        float limit = Math.min(top, reached);
        for (int i = 0; i < Math.ceil(limit / WINDOW); i++) win(i);
        windows.keySet().removeIf(i -> i * WINDOW >= limit);
        ledger.removeIf(r -> r.window * WINDOW >= limit);
        Map<String, Double> n = new HashMap<>();
        for (Row r : ledger) if (r.lane.equals("HAZARDS") || r.lane.equals("ENEMIES")) n.merge(r.window + ":" + r.rule, r.fraction, Double::sum);
        Map<Integer, Double> xs = new HashMap<>();
        for (Row r : ledger) if (r.lane.equals("INTERACTION")) xs.merge(r.window, r.weight * r.fraction, Double::sum);
        for (Row r : ledger) {
            if (r.lane.equals("HAZARDS") || r.lane.equals("ENEMIES")) { double c = n.get(r.window + ":" + r.rule); r.repetition = c > 0 ? (Math.min(c, DifficultyScore.FULL) + DifficultyScore.TAIL * Math.max(0, c - DifficultyScore.FULL)) / c : 1; }
            if (r.lane.equals("INTERACTION")) { double s = xs.get(r.window); r.repetition = s > X_CAP ? X_CAP / s : 1; r.note = s > X_CAP ? "window interaction cap " + X_CAP : ""; }
            r.value = r.weight * r.fraction * r.repetition;
            Win w = windows.get(r.window); if (w == null) continue;
            int li = r.lane.equals("TERRAIN") ? 0 : r.lane.equals("HAZARDS") ? 1 : r.lane.equals("ENEMIES") ? 2 : -1;
            if (li >= 0) { w.raw[li] += r.weight * r.fraction; w.adj[li] += r.value; }
            else if (r.lane.equals("MITIGATION")) w.mitigation += r.value; else if (r.lane.equals("INTERACTION")) w.interaction += r.value;
        }
        for (DifficultyScore.Window v : v2.windows()) { Win w = windows.get(v.index); if (w != null) w.v2 = v.adjustedNet(); }
        for (Win w : windows.values()) {
            if (!w.geometry) w.flags.add("NO_ROUTE_GEOMETRY");
            if (!solve) w.flags.add("SOLVER_OFF"); else if (w.linksFailed > 0) w.flags.add("SOLVER_FAILED"); else if (w.linksUnreached > 0) w.flags.add("SOLVER_UNREACHED");
        }
        if (stopped != null && !windows.isEmpty()) windows.lastEntry().getValue().flags.add("RUN_STOPPED_HERE");
    }

    /** The solver leaves failInfo empty on two paths (a planned move that never reaches its target within 20 s, and its sim-time limit): the report
     *  fills the reason from the solver's own end state (Autopilot.lastReal, read after the run) and marks it as report-derived. */
    static String derivedFailInfo(Autopilot.Report rep, GenTrace.Solve st) {
        Sim r = Autopilot.lastReal;
        String state = r == null ? "end state unavailable" : String.format(Locale.ROOT, "mode=%s on=%d s=%.2f y=%.2f vx=%.2f vy=%.2f t=%.2f hits=%d falls=%d", r.mode, r.onElem, r.s, r.y, r.vx, r.vy, r.time, r.hits, r.falls)
            + (r != null && r.hitBy != null ? " lastHazard=" + r.hitBy.type : "");
        int last = st == null || st.arrivals.isEmpty() ? -1 : (int) st.arrivals.get(st.arrivals.size() - 1)[0];
        if (rep.failedLink >= 0) return "[report-derived] planned move did not reach the next link within 20 s (solver gives no text on this path): " + state;
        return "[report-derived] solver stopped without completing (sim-time limit or loop end) after link " + last + ": " + state;
    }
    static double radius(Element.Type k) {
        switch (k) { case SAW_H: case SAW_V: return Element.SAW_R; case PENDULUM: return Element.BALL_R; case CANNON: return Element.SHOT_R; case MORTAR: return Element.MORTAR_R; default: return Double.NaN; }
    }
    static long alloc() {
        try { java.lang.management.ThreadMXBean b = java.lang.management.ManagementFactory.getThreadMXBean(); return b instanceof com.sun.management.ThreadMXBean ? ((com.sun.management.ThreadMXBean) b).getCurrentThreadAllocatedBytes() : -1; }
        catch (Throwable e) { return -1; }
    }
    /** Per castle: see traversal.csv's header. Runs (consecutive ropes, ordinary links, unchallenged climb) are counted in climbing order and credited to the castle where they end. */
    List<String[]> traversalRows() {
        int n = 11; int[] links = new int[n], chall = new int[n], mods = new int[n], fbs = new int[n], ropes = new int[n], ropePick = new int[n], ropeFb = new int[n], maxRope = new int[n], maxOrd = new int[n];
        double[] longest = new double[n], ropeM = new double[n], horiz = new double[n];
        int runRope = 0;
        for (String[] m : travMods) {
            int cs = Integer.parseInt(m[0]); mods[cs]++; boolean fb = "FALLBACK".equals(m[2]); if (fb) fbs[cs]++;
            if ("ROPE".equals(m[1])) { ropes[cs]++; if (fb) ropeFb[cs]++; else ropePick[cs]++; runRope++; maxRope[cs] = Math.max(maxRope[cs], runRope); } else runRope = 0;
        }
        int runOrd = 0; double runFrom = Double.NaN;
        for (double[] l : travLinks) {
            int cs = (int) l[0]; links[cs]++; ropeM[cs] += l[4];
            if (l[5] > 0) { chall[cs]++; runOrd = 0; runFrom = Double.NaN; continue; }
            if (l[6] == 0) horiz[cs] += l[3];
            if (runOrd == 0) runFrom = l[1];
            runOrd++; maxOrd[cs] = Math.max(maxOrd[cs], runOrd); longest[cs] = Math.max(longest[cs], l[2] - runFrom);
        }
        List<String[]> out = new ArrayList<>();
        for (int cs = 1; cs < n; cs++) {
            if (links[cs] == 0 && mods[cs] == 0) continue;
            out.add(new String[]{"" + cs, "" + links[cs], "" + chall[cs], "" + (links[cs] - chall[cs]), f3(links[cs] == 0 ? 0 : chall[cs] / (double) links[cs]), "" + mods[cs], "" + fbs[cs], "" + ropes[cs], "" + ropePick[cs], "" + ropeFb[cs],
                f3(fbs[cs] == 0 ? 0 : ropeFb[cs] / (double) fbs[cs]), "" + maxRope[cs], "" + maxOrd[cs], f3(longest[cs]), f3(ropeM[cs]), f3(horiz[cs])});
        }
        return out;
    }

    static int castleOf(double y) { return Math.min(10, 1 + (int) (y / CASTLE)); }
    static double clamp(double x) { return Math.max(0, Math.min(1, x)); }
    static String f3(double v) { return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%.3f", v); }
    static String q(String s) { return "\"" + (s == null ? "" : s.replace('"', '\'').replace('\n', ' ')) + "\""; }

    // ------------------------------------------------------------------ castle statistics
    static double[] stats(List<Double> v) {     // n, mean, median, min, max, sd, q1, q3
        if (v.isEmpty()) return new double[]{0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        List<Double> s = new ArrayList<>(v); Collections.sort(s); double sum = 0; for (double x : s) sum += x; double m = sum / s.size(), var = 0; for (double x : s) var += (x - m) * (x - m);
        return new double[]{s.size(), m, pct(s, 0.5), s.get(0), s.get(s.size() - 1), Math.sqrt(var / s.size()), pct(s, 0.25), pct(s, 0.75)};
    }
    static double pct(List<Double> s, double p) { double i = p * (s.size() - 1); int lo = (int) Math.floor(i), hi = (int) Math.ceil(i); return s.get(lo) + (s.get(hi) - s.get(lo)) * (i - lo); }

    // ------------------------------------------------------------------ output
    /** Failure classes for the reports (owner solver directive 2026-10-26): every unsuccessful slice falls in exactly one. */
    static String failClass(boolean completed, String info) {
        if (completed) return "completed";
        String i = info == null ? "" : info.replace("\"", "");
        if (i.startsWith("solver threw")) return "solver_exception";
        if (i.startsWith("setback while executing")) return "route_link_failure";
        if (i.startsWith("plan failed")) return "planning_failure";
        if (i.startsWith("recover") || i.startsWith("detour") || i.startsWith("lost the route") || i.startsWith("stuck on a spike slab")) return "recovery_failure";
        if (i.startsWith("stuck at one link")) return "knockback_loop";
        if (i.contains("within 20 s") || i.contains("sim time")) return "timeout";
        return "unknown";
    }
    static String frames(Throwable e) {
        StringBuilder b = new StringBuilder(); int n = 0;
        for (StackTraceElement f : e.getStackTrace()) if (f.getClassName().startsWith("com.hotatticgames") && n++ < 3) b.append(n > 1 ? " < " : "").append(f.getClassName().replaceAll(".*\\.", "")).append('.').append(f.getMethodName()).append(':').append(f.getLineNumber());
        return b.toString();
    }
    public void write(Path out, Map<String, String> meta) throws IOException {
        Files.createDirectories(out);
        String run = MODE[mode] + "-s" + seed;
        try (PrintWriter w = pw(out, "windows.csv")) {
            w.println("run,mode,seed,castle,window,y0,y1,terrain_raw,terrain_adj,hazard_raw,hazard_adj,enemy_raw,enemy_adj,mitigation,interaction,total_reference,v2_reference_score,"
                + "links,demanding_links,max_gap_ratio,min_landing_width,decoy_elements,off_route_hazards,unreachable_enemies,solver_links_traversed,solver_links_failed,solver_links_unreached,"
                + "solver_time_s,solver_setbacks,solver_plan_failures,gen_rejected_attempts,gen_fallbacks,modules,counts,flags,route_mechanics,recovery_platforms,solver_wall_s");
            for (Win x : windows.values()) w.println(String.join(",", run, MODE[mode], "" + seed, "" + castleOf(x.index * WINDOW), "" + x.index, f3(x.index * WINDOW), f3((x.index + 1) * WINDOW),
                x.geometry ? f3(x.raw[0]) : "", x.geometry ? f3(x.adj[0]) : "", f3(x.raw[1]), f3(x.adj[1]), f3(x.raw[2]), f3(x.adj[2]), f3(x.mitigation), f3(x.interaction), f3(x.total()), f3(x.v2),
                "" + x.linksTotal, "" + x.demandingLinks, f3(x.maxRatio), f3(x.minLandW), "" + x.decoys, "" + x.offRouteHazards, "" + x.unreachableEnemies,
                solve ? "" + x.linksTraversed : "", solve ? "" + x.linksFailed : "", solve ? "" + x.linksUnreached : "", solve ? f3(x.solveTime) : "", solve ? "" + x.setbacks : "", solve ? "" + x.planFails : "",
                "" + x.rejected, "" + x.fallbacks, q(x.modules.toString()), q(counts(x)), q(String.join(";", x.flags)), q(x.mech.toString()), "" + x.recovery, solve ? f3(x.solveWall) : ""));
        }
        try (PrintWriter w = pw(out, "ledger.csv")) {
            w.println("run,window,castle,lane,rule,source,property,raw,weight,fraction,repetition,value,note");
            for (Row r : ledger) w.println(String.join(",", run, "" + r.window, "" + castleOf(r.window * WINDOW), r.lane, r.rule, r.source, q(r.property), f3(r.raw), f3(r.weight), f3(r.fraction), f3(r.repetition), f3(r.value), q(r.note)));
        }
        try (PrintWriter w = pw(out, "elements.csv")) {
            w.println("run,id,slice,index,type,lane,route,s,y,w,amp,period,phase,len,skin,color,dir,anchor,window,castle,module,module_kind,structure");
            for (List<El> l : Arrays.asList(elements, hazards)) for (El r : l) { Element e = r.e;
                w.println(String.join(",", run, r.id, "" + r.slice, "" + r.idx, r.type, r.lane, "" + r.route, f3(r.sAbs), f3(r.yAbs), f3(e.w), f3(e.amp), f3(e.period), f3(e.phase), f3(e.len), "" + e.skin, "" + e.color, "" + e.dir, "" + e.anchor,
                    "" + r.window, "" + castleOf(r.yAbs), r.module, r.moduleKind, q(r.structure))); }
        }
        try (PrintWriter w = pw(out, "links.csv")) {
            w.println("run,slice,from,to,from_type,to_type,y0,y1,dy,gap,reach,gap_ratio,landing_width,terrain,em_jump,em_position,em_wait,em_commit,em_recover,em_pursue,em_score,hazards,enemies,solver_status,solver_time_s,setbacks,plan_failures,windows,solver_wall_s,solver_visits");
            for (Link L : links) w.println(String.join(",", run, "" + L.slice, L.fromId, L.toId, L.fromType, L.toType, f3(L.y0), f3(L.y1), f3(L.dy), f3(L.gap), f3(L.reach), f3(L.ratio), f3(L.landW), f3(L.terrain),
                f3(L.emJump), f3(L.emPosition), f3(L.emWait), f3(L.emCommit), f3(L.emRecover), f3(L.emPursue), f3(L.emScore), q(L.hazards.toString()), q(L.enemies.toString()), L.status, f3(L.solveTime), L.setbacks < 0 ? "" : "" + L.setbacks, "" + L.planFails, q(Arrays.toString(L.windows)), f3(L.solveWall), "" + L.visits));
        }
        try (PrintWriter w = pw(out, "modules.csv")) {
            w.println("run,id,slice,attempt,final_attempt,kind,role,picked_first,difficulty,y,accepted,e0,e1,hazards_added,refused_layer,refused_hazard,refused_plan,refused_window,refused_conflict,refused_threat,wall_ms");
            for (String[] m : modules) w.println(run + "," + String.join(",", m));
        }
        try (PrintWriter w = pw(out, "solver.csv")) {
            w.println("run,slice,y0,y1,completed,plans,failed_link,sim_time_s,min_margin,worst_link,wall_seconds,fail_info,fail_class");
            for (String[] m : solverSlices) w.println(run + "," + String.join(",", m));
        }
        try (PrintWriter w = pw(out, "solver_failures.csv")) {
            w.println("run,slice,from,to,from_type,to_type,y0,y1,castle,fail_info,fail_class");
            for (String[] m : solverFailures) w.println(run + "," + String.join(",", m) + "," + failClass(false, m[8]));
        }
        try (PrintWriter w = pw(out, "generation_failures.csv")) {
            w.println("run,slice,detail"); for (String[] m : sliceFailures) w.println(run + "," + String.join(",", m));
        }
        try (PrintWriter w = pw(out, "integrity.csv")) {
            w.println("run,slice,violation"); for (String[] m : integrityViolations) w.println(run + "," + String.join(",", m));
        }
        try (PrintWriter w = pw(out, "gate.json")) {
            StringBuilder j = new StringBuilder("{\"run\":").append(js(run)).append(",\"passes\":").append(passes());
            for (Map.Entry<String, String> e : verdicts().entrySet()) j.append(',').append(js(e.getKey())).append(':').append(js(e.getValue()));
            w.println(j.append('}'));
        }
        try (PrintWriter w = pw(out, "rule_checks.csv")) {
            w.println("run,mode,rule,source,type,y,castle,detail"); for (String[] m : ruleChecks) w.println(run + "," + MODE[mode] + "," + String.join(",", m));
        }
        try (PrintWriter w = pw(out, "inventory.csv")) {
            w.println("run,mode,kind,element_type,lane,defined,placed,on_or_reaching_route,scored,status,description");
            for (Element.Type k : Element.Type.values()) { int[] v = inventory.get(k);
                String st = v[0] == 0 ? "DEFINED, NOT PLACED (measured zero)" : v[2] > 0 ? "PLACED AND ACTIVE" : v[1] > 0 ? "PLACED ON ROUTE" : "PLACED, NOT ON ROUTE";
                w.println(String.join(",", run, MODE[mode], "element", k.name(), Lanes.lane(k).name(), "true", "" + v[0], "" + v[1], "" + v[2], q(st), q(Lanes.describe(k)))); }
        }
        try (PrintWriter w = pw(out, "module_inventory.csv")) {
            w.println("run,mode,kind,purpose,tried,accepted_in_course,refused,accepted_as_fallback,status");
            List<String[]> all = new ArrayList<>(Arrays.asList(Lanes.KINDS)); all.addAll(Arrays.asList(Lanes.STRUCTURES));
            for (String[] k : all) { int[] v = kindStats.get(k[0]);
                String st = v[0] == 0 ? "NEVER SELECTED (no attempt observed)" : v[1] == 0 ? "SELECTED, NEVER ACCEPTED" : "ACCEPTED";
                w.println(String.join(",", run, MODE[mode], k[0], q(k[1]), "" + v[0], "" + v[1], "" + v[2], "" + v[3], q(st))); }
        }
        try (PrintWriter w = pw(out, "chunks.csv")) {
            w.println("run,slice,y0,y1,castle,gen_wall_s,slice_attempts,failed_attempts,final_attempt_s,failed_attempts_s,modules_accepted,accepted_modules_s,modules_refused_or_discarded,refused_or_discarded_s,fallback_s,relaxed,gen_alloc_bytes,solve_wall_s,solve_alloc_bytes,heap_used_bytes,phase_extend_s,phase_closing_rest_s,phase_decoys_forks_s,phase_rooms_s,phase_forks_reserved_s,phase_forks_open_s,phase_key_rooms_s");
            for (String[] m : chunks) w.println(run + "," + String.join(",", m));
        }
        try (PrintWriter w = pw(out, "platforms.csv")) {          // observe-only safety classes of main-route platforms, in route order
            w.println("run,slice,index,y,castle,type,checkpoint,hazards_on,enemies_reaching,threat_family_count,threat_families,class");
            for (String[] m : platformRows) w.println(run + "," + String.join(",", m));
        }
        try (PrintWriter w = pw(out, "traversal.csv")) {          // owner 2026-10-26: ropes and ordinary traversal per castle (observe-only)
            w.println("run,castle,route_links,challenge_links,ordinary_links,challenge_density,modules_accepted,fallbacks_accepted,rope_modules,rope_picks,rope_fallbacks,rope_share_of_fallbacks,max_consecutive_rope_modules,max_consecutive_ordinary_links,longest_unchallenged_climb_m,rope_climb_m,unchallenged_horizontal_m");
            for (String[] row : traversalRows()) w.println(run + "," + String.join(",", row));
        }
        try (PrintWriter w = pw(out, "forks.csv")) {          // observe-only: route-length columns are information, not a requirement (owner 2026-10-26)
            w.println("run,slice,fork,parent_fork,depth,takeoff_on,takeoff_index,rejoin_on,rejoin_index,branch_platforms,branch_mechanics,y,castle,branch_length_m,bypassed_route_length_m");
            for (String[] m : forkRows) w.println(run + "," + String.join(",", m));
        }
        try (PrintWriter w = pw(out, "fork_attempts.csv")) {          // FORKWHY reasons: 0 rejoin/take-off unsuitable, 1 shape, 2+decoyWhy placement (3 overlap, 4 other layer, 5 ghost layer, 6 stepping stone, 7 launch corridor), 9 route re-proof, 10 drop onto branch, 11 branch hop, 12 bounce onto rejoin
            w.println("run,slice,castle," + String.join(",", FORK_COUNTS) + ",reasons");
            for (String[] m : forkCountRows) w.println(run + "," + String.join(",", m));
        }
        try (PrintWriter w = pw(out, "hazards.csv")) {
            w.println("run,id,type,lane,status,on_route,platform,structure,module_kind,window,castle,s,y,w,amp,period,phase,len,dir,hit_radius,route_links_exposed,same_platform_threats,enemy_reach_platforms,enemy_reach_windows");
            for (String[] m : hazardRows) w.println(run + "," + String.join(",", m));
        }
        List<String[]> castles = castleRows(run);
        try (PrintWriter w = pw(out, "fallbacks.csv")) { w.println("slice,y0,castle," + String.join(",", FALLBACK_KEYS)); for (String[] r : fallbackRows) w.println(String.join(",", r)); }
        try (PrintWriter w = pw(out, "castle_control.csv")) {          // rules 13: what the generator's progression controller saw and did, per castle, beside this report's own lane totals
            w.println("run,castle,status,target_total,target_terrain,target_hazards,target_enemies,target_interaction,est_terrain,est_hazards,est_enemies,est_interaction,est_mitigation,est_total,est_links,est_challenge_links,est_recovery,"
                + "factor_terrain,factor_hazards,factor_enemies,factor_interaction,lane_terrain,lane_hazards,lane_enemies,lane_interaction,lane_mitigation,lane_total,deviation_from_target,"
                + "hazard_attempts,hazard_accepted,hazard_acceptance,hazard_points_placed,hazard_points_per_attempt,haz_rows,traps,mortar_rows,drop_rows,"
                + "hazard_factor_requested,hazard_factor_applied,hazard_factor_lost,hazard_constraint,hazard_short_of_target");
            DifficultyProfile P = DifficultyProfile.of(t); Progression pr = Progression.of(lastProg);
            double[][] lane = new double[12][6];
            for (Win x : windows.values()) { int cs = castleOf(x.index * WINDOW); lane[cs][0] += x.adj[0]; lane[cs][1] += x.adj[1]; lane[cs][2] += x.adj[2]; lane[cs][3] += x.interaction; lane[cs][4] += x.mitigation; lane[cs][5] += x.total(); }
            List<float[]> recs = new ArrayList<>(); for (int i = 0; i < pr.completedCount(); i++) recs.add(pr.completed(i));
            if (lastProg != null) { float[] cur = pr.current(), r = new float[Progression.REC]; r[0] = pr.castle(); System.arraycopy(cur, 0, r, 1, Progression.ACC); System.arraycopy(pr.lastFactors(), 0, r, 1 + Progression.ACC, 4); recs.add(r); }
            for (float[] r : recs) {
                int n = (int) r[0]; if (n < 1 || n > 10) continue; boolean partial = r == recs.get(recs.size() - 1) && lastProg != null;
                double est = Progression.total(r, 1), tgt = P.progression() ? P.targetTotal(n) : Double.NaN;
                w.println(String.join(",", MODE[mode] + "-s" + seed, "" + n, partial ? "in_progress" : "complete", f3(tgt), f3(P.progression() ? P.target(n, 0) : Double.NaN), f3(P.progression() ? P.target(n, 1) : Double.NaN), f3(P.progression() ? P.target(n, 2) : Double.NaN), f3(P.progression() ? P.target(n, 3) : Double.NaN),
                    f3(r[1]), f3(r[2]), f3(r[3]), f3(r[4]), f3(r[5]), f3(est), f3(r[1 + Progression.A_LINKS]), f3(r[1 + Progression.A_CHALL]), f3(r[1 + Progression.A_REC]),
                    f3(r[1 + Progression.ACC]), f3(r[2 + Progression.ACC]), f3(r[3 + Progression.ACC]), f3(r[4 + Progression.ACC]),
                    f3(lane[n][0]), f3(lane[n][1]), f3(lane[n][2]), f3(lane[n][3]), f3(lane[n][4]), f3(lane[n][5]), f3(P.progression() ? (lane[n][5] - tgt) / tgt : Double.NaN),
                    capacityColumns(pr, n, partial, P.progression() ? P.target(n, 1) - lane[n][1] : Double.NaN)));
            }
        }
        try (PrintWriter w = pw(out, "encounters.csv")) { w.println(ENCOUNTER_HEADER); for (String[] r : encounterRows) w.println(String.join(",", r)); }
        try (PrintWriter w = pw(out, "castles.csv")) {
            w.println("run,mode,seed,castle,lane,windows,mean,median,min,max,sd,q1,q3,raw_sum,adj_sum,high_spike_windows,low_gap_windows,solver_links_failed,solver_links_unreached,solver_time_s,flags");
            for (String[] r : castles) w.println(String.join(",", r));
        }
        try (PrintWriter w = pw(out, "run.json")) { w.print(json(run, meta, castles)); }
    }

    List<String[]> castleRows(String run) {
        List<String[]> rows = new ArrayList<>(); String[] lanes = {"TERRAIN", "HAZARDS", "ENEMIES", "INTERACTION", "MITIGATION", "TOTAL_REFERENCE"};
        for (int castle = 1; castle <= 10; castle++) {
            List<Win> ws = new ArrayList<>(); for (Win x : windows.values()) if (castleOf(x.index * WINDOW) == castle) ws.add(x);
            if (ws.isEmpty()) continue;
            int failed = 0, unreached = 0; double st = 0; TreeSet<String> fl = new TreeSet<>(); for (Win x : ws) { failed += x.linksFailed; unreached += x.linksUnreached; st += x.solveTime; fl.addAll(x.flags); }
            for (int li = 0; li < lanes.length; li++) {
                List<Double> v = new ArrayList<>(); double rawSum = 0, adjSum = 0;
                for (Win x : ws) {
                    double a = li < 3 ? x.adj[li] : li == 3 ? x.interaction : li == 4 ? x.mitigation : x.total(); double r = li < 3 ? x.raw[li] : a;
                    if (li == 0 && !x.geometry) continue;
                    v.add(a); rawSum += r; adjSum += a;
                }
                double[] s = stats(v); int hi = 0, lo = 0; for (double a : v) { if (a > s[7] + 1.5 * (s[7] - s[6])) hi++; if (li < 3 && a == 0) lo++; }
                rows.add(new String[]{run, MODE[mode], "" + seed, "" + castle, lanes[li], "" + (int) s[0], f3(s[1]), f3(s[2]), f3(s[3]), f3(s[4]), f3(s[5]), f3(s[6]), f3(s[7]), f3(rawSum), f3(adjSum), "" + hi, "" + lo,
                    solve ? "" + failed : "", solve ? "" + unreached : "", solve ? f3(st) : "", q(String.join(";", fl))});
            }
        }
        return rows;
    }

    String counts(Win x) { StringBuilder b = new StringBuilder(); for (Map.Entry<Element.Type, Double> e : x.counts.entrySet()) b.append(b.length() == 0 ? "" : ";").append(e.getKey()).append('=').append(f3(e.getValue())); return b.toString(); }

    String json(String run, Map<String, String> meta, List<String[]> castles) {
        StringBuilder j = new StringBuilder("{");
        j.append("\"run\":").append(js(run)).append(",\"mode\":").append(js(MODE[mode])).append(",\"seed\":").append(seed).append(",\"derived_seed\":null,\"top_m\":").append(top)
         .append(",\"reached_m\":").append(reached).append(",\"slices\":").append(slices).append(",\"stopped\":").append(stopped == null ? "null" : js(stopped))
         .append(",\"generation_seconds\":").append(f3(genSeconds)).append(",\"solver_seconds\":").append(solve ? f3(solveSeconds) : "null").append(",\"solver\":").append(solve)
         .append(",\"scoring_model\":").append(js(SCORING)).append(",\"reference_model\":\"v2 (DifficultyScore, provisional hazard weights)\",\"generator_rules_version\":").append(CourseGenerator.RULES_VERSION);
        for (Map.Entry<String, String> e : meta.entrySet()) j.append(',').append(js(e.getKey())).append(':').append(js(e.getValue()));
        j.append(",\"weights\":{"); boolean first = true; for (Map.Entry<Element.Type, Float> e : DifficultyScore.WEIGHTS.entrySet()) { j.append(first ? "" : ",").append(js(e.getKey().name())).append(':').append(e.getValue()); first = false; }
        j.append("},\"mode_parameters\":").append(modeParams());
        double[] tot = new double[6]; for (Win x : windows.values()) { tot[0] += x.adj[0]; tot[1] += x.adj[1]; tot[2] += x.adj[2]; tot[3] += x.interaction; tot[4] += x.mitigation; tot[5] += x.total(); }
        j.append(",\"totals\":{\"terrain_adj\":").append(f3(tot[0])).append(",\"hazard_adj\":").append(f3(tot[1])).append(",\"enemy_adj\":").append(f3(tot[2])).append(",\"interaction\":").append(f3(tot[3]))
         .append(",\"mitigation\":").append(f3(tot[4])).append(",\"total_reference\":").append(f3(tot[5])).append(",\"windows\":").append(windows.size()).append(",\"ledger_rows\":").append(ledger.size()).append('}');
        j.append(",\"castles\":["); first = true;
        for (String[] r : castles) { j.append(first ? "" : ",").append("{\"castle\":").append(r[3]).append(",\"lane\":").append(js(r[4])).append(",\"windows\":").append(r[5]).append(",\"mean\":").append(num(r[6])).append(",\"median\":").append(num(r[7]))
            .append(",\"min\":").append(num(r[8])).append(",\"max\":").append(num(r[9])).append(",\"sd\":").append(num(r[10])).append(",\"q1\":").append(num(r[11])).append(",\"q3\":").append(num(r[12])).append(",\"adj_sum\":").append(num(r[14])).append('}'); first = false; }
        j.append("],\"files\":[\"windows.csv\",\"ledger.csv\",\"elements.csv\",\"links.csv\",\"modules.csv\",\"solver.csv\",\"solver_failures.csv\",\"generation_failures.csv\",\"rule_checks.csv\",\"inventory.csv\",\"module_inventory.csv\",\"castles.csv\",\"windows_v2_reference.csv\",\"chunks.csv\",\"hazards.csv\",\"forks.csv\",\"fork_attempts.csv\",\"platforms.csv\",\"traversal.csv\",\"integrity.csv\",\"gate.json\",\"variety_windows.csv\",\"variety_rolling.csv\",\"variety_castles.csv\",\"variety_run.json\"]");
        j.append(",\"measurement_limits\":[\"cannon pillars and mortar emplacements are render-only (no traversal geometry in the simulation); they contribute no terrain\","
            + "\"hazard attack envelopes are reported as the element parameters the Sim interprets (amp, period, len, phase, dir), not swept volumes\","
            + "\"per-type hazard and enemy placement attempts are not observable; module attempts and refusal counters are\","
            + "\"solver time per link is the gap between the solver's arrivals at successive links (supporting evidence, never a score)\","
            + "\"generation time is per module attempt and per slice attempt (wall clock); validation, rollback and solver-proof time inside an attempt are not separated\","
            + "\"allocation is the generating thread's allocated bytes (HotSpot ThreadMXBean); blank when the JVM does not provide it\","
            + "\"aimed-cannon and spike timing envelopes, actual enemy contacts and per-type placement attempts need Sim/generator internals and are not measured\"]}");
        return j.toString();
    }
    String modeParams() {
        Tuning base = t; StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (Field f : DifficultyProfile.class.getFields()) { if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            try { b.append(first ? "" : ",").append(js("profile." + f.getName())).append(':').append(js(String.valueOf(f.get(DifficultyProfile.of(base))))); first = false; } catch (IllegalAccessException e) { } }
        for (Field f : Tuning.class.getFields()) { if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue; String n = f.getName().toLowerCase(Locale.ROOT);
            if (!(n.contains("hard") || n.contains("crab") || n.contains("bee") || n.contains("skull") || n.contains("blob") || n.contains("enforcer") || n.contains("enemy") || n.contains("creature") || n.contains("aggr"))) continue;
            try { Object v = f.get(base); if (v != null && v.getClass().isArray()) v = arr(v); b.append(",").append(js("tuning." + f.getName())).append(':').append(js(String.valueOf(v))); } catch (IllegalAccessException e) { } }
        return b.append('}').toString();
    }
    static String arr(Object a) { StringBuilder b = new StringBuilder("["); int n = java.lang.reflect.Array.getLength(a); for (int i = 0; i < n; i++) b.append(i == 0 ? "" : " ").append(java.lang.reflect.Array.get(a, i)); return b.append(']').toString(); }
    static String num(String s) { return s.isEmpty() ? "null" : s; }
    static String js(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    static PrintWriter pw(Path out, String name) throws IOException { return new PrintWriter(Files.newBufferedWriter(out.resolve(name))); }

    public void writeV2(Path out) throws IOException {
        try (PrintWriter w = pw(out, "windows_v2_reference.csv")) {
            w.println("run,window,y0,v2_threat_pos,v2_protective,v2_adjusted_net,v2_interaction,v2_enemy_families,v2_hazard_families,model");
            for (DifficultyScore.Window v : v2.windows()) if (v.y0() < Math.min(top, reached))
                w.println(String.join(",", MODE[mode] + "-s" + seed, "" + v.index, f3(v.y0()), f3(v.pos), f3(v.prot), f3(v.adjustedNet()), f3(v.interaction), "" + v.enemyFamilies(), "" + v.hazardFamilies(), "v2"));
        }
    }

    public static void main(String[] a) throws Exception {
        Tuning base = Tuning.parse(new String(Files.readAllBytes(Paths.get(a[0]))));
        int mode = Integer.parseInt(a[2]); long seed = Long.parseLong(a[3]); Path out = Paths.get(a[4]);
        Tuning t = mode == 0 ? base : base.hardCopy(mode);
        LaneReport r = new LaneReport(t, mode, seed, Float.parseFloat(a[1]));
        r.run(!"0".equals(System.getenv("SOLVE")), Double.parseDouble(System.getenv().getOrDefault("RUN_BUDGET", "1e9")));
        Map<String, String> meta = new LinkedHashMap<>();
        for (String[] kv : new String[][]{{"commit", "GITHUB_SHA"}, {"branch", "GITHUB_REF_NAME"}, {"actions_run_id", "GITHUB_RUN_ID"}, {"actions_run_attempt", "GITHUB_RUN_ATTEMPT"}, {"workflow", "GITHUB_WORKFLOW"}})
            meta.put(kv[0], System.getenv().getOrDefault(kv[1], "local"));
        meta.put("solver_version", "Autopilot.run at commit " + meta.get("commit"));
        r.write(out, meta); r.writeV2(out); VarietyReport.write(r, out);          // gameplay variety, apart from difficulty (owner 2026-10-27)
        System.out.printf(Locale.ROOT, "%s seed %d: reached %.0f m, %d slices, %d windows, %d ledger rows, gen %.0f s, solve %.0f s%s%n", MODE[mode], seed, r.reached, r.slices, r.windows.size(), r.ledger.size(), r.genSeconds, r.solveSeconds, r.stopped == null ? "" : " - " + r.stopped);
        for (Map.Entry<String, String> e : r.verdicts().entrySet()) System.out.println("  " + e.getKey() + ": " + e.getValue());
        // validation gate (owner directive 2026-10-27): the artifacts above are written first; an incomplete course, a solver failure or a geometry
        // violation then fails the job, so a partial course never shows green
        if (!r.passes()) { System.out.println("VALIDATION GATE FAILED"); System.exit(1); }
    }
}
