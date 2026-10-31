package com.hotatticgames.climbup.sim;

import java.nio.file.*;
import java.util.*;
import org.junit.Test;

/**
 * Scratch probe (KPI 7B progression audit; not a fixture). Grows climbs as the game does (GameGrowth) and prints, per completed castle, the
 * controller's measurement against the profile's target (total and T/H/E/X, mitigation M), castle-to-castle and cumulative growth, the factors
 * the castle was built with (applied) beside what the controller asked for at the castle's start (requested, before the bounds), saturated
 * factors, accepted / refused module attempts, and for NGH the hazard control and capacity records. A summary line gives castle 1 -> last
 * growth and the regressions (castles below their predecessor).
 *
 * <p>Env: CLIMB_PROBE, CLIMB_CASES = "mode:seed:top,..." (mode 0 NORMAL, 1 PUNISHER, 2 NGH).
 */
public class CastleAuditProbe {
    @Test public void castles() throws Exception {
        org.junit.Assume.assumeTrue(System.getenv("CLIMB_PROBE") != null);
        String cases = System.getenv().getOrDefault("CLIMB_CASES", "1:7322:1600");
        Tuning base = Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json"))));
        for (String cs : cases.split(",")) {
            String[] p = cs.split(":"); int lvl = Integer.parseInt(p[0]); long seed = Long.parseLong(p[1]); float top = Float.parseFloat(p[2]);
            Tuning t = lvl == 0 ? base : base.hardCopy(lvl);
            DifficultyProfile P = DifficultyProfile.of(t);
            GameGrowth g = new GameGrowth(seed, t);
            StringBuilder sb = new StringBuilder(String.format("== %s seed %d (castle spacing %.0f m, rules %d)%n", new String[]{"NORMAL", "PUNISHER", "NGH"}[lvl], seed, t.castleSpacing, CourseGenerator.RULES_VERSION));
            double y = 0; int printed = 0, capPrinted = 0; long w0 = System.nanoTime();
            Map<Integer, float[]> req = new HashMap<>(); Map<Integer, int[]> mods = new TreeMap<>(); List<Float> totals = new ArrayList<>();
            Map<String, int[]> refusedBy = new TreeMap<>(); Map<String, Integer> whys = new TreeMap<>();          // Phase 3: refusal causes per module kind (all attempts)
            try {
                for (int k = 0; k < 400 && y < top; k++) {
                    CourseGenerator.Framed f = g.next(); y = f.yBase + f.c.get(f.c.routeSize() - 1).y;
                    for (GenTrace.Module m : g.trace.modules) {
                        if (!m.ok && !"STRUCTURE".equals(m.role)) { int[] v = refusedBy.computeIfAbsent(m.kind, x -> new int[8]); v[0]++; if (m.earlyRejected) v[7]++; for (int q = 0; q < 6; q++) if (m.fails[q] > 0) v[1 + q]++; }
                        if ("STRUCTURE".equals(m.role) || m.attempt != g.trace.finalAttempt) continue;
                        int[] v = mods.computeIfAbsent(Progression.castleOf(m.y, t.castleSpacing), x -> new int[3]); if (m.ok) v[0]++; else { v[1]++; if (m.earlyRejected) v[2]++; }
                    }
                    for (Map.Entry<String, Integer> w : g.trace.counts.entrySet()) if (w.getKey().startsWith("why_")) whys.merge(w.getKey(), w.getValue(), Integer::sum);
                    Progression pr = Progression.of(f.c.prog);
                    if (!req.containsKey(pr.castle())) { float[] r = new float[4]; pr.factors(pr.castle(), P, t.castleSpacing, r); req.put(pr.castle(), r); }
                    for (int i = printed; i < pr.completedCount(); i++) {
                        float[] r = pr.completed(i); int n = (int) r[0];
                        float tot = Progression.total(r, 1), tg = P.targetTotal(n), prev = totals.isEmpty() ? Float.NaN : totals.get(totals.size() - 1);
                        totals.add(tot);
                        float[] rq = req.getOrDefault(n, new float[]{Float.NaN, Float.NaN, Float.NaN, Float.NaN});
                        StringBuilder fs = new StringBuilder();
                        for (int q = 0; q < 4; q++) {
                            float fv = r[1 + Progression.ACC + q];
                            boolean lo = fv <= P.factorLo(q) + 1e-4f, hi = fv >= P.factorHi(q) - 1e-4f && !(q == 1 && P.capacityControl());
                            fs.append(String.format(" %s%.2f/%.2f%s", "THEX".charAt(q), rq[q], fv, lo ? "(lo)" : hi ? "(hi)" : ""));
                        }
                        int[] mv = mods.getOrDefault(n, new int[3]);
                        sb.append(String.format("CASTLE %2d total %6.0f / target %6.0f (%+5.1f%%) step %+5.1f%% cum %+5.1f%% | T %4.0f/%4.0f H %4.0f/%4.0f E %4.0f/%4.0f X %3.0f/%3.0f M %2.0f | req/applied%s | modules ok %d refused %d (early %d)%n",
                                n, tot, tg, 100 * (tot - tg) / tg, Float.isNaN(prev) ? 0f : 100 * (tot - prev) / prev, 100 * (tot - totals.get(0)) / totals.get(0),
                                r[1], P.target(n, 0), r[2], P.target(n, 1), r[3], P.target(n, 2), r[4], P.target(n, 3), r[5], fs, mv[0], mv[1], mv[2]));
                    }
                    printed = pr.completedCount();
                    for (int i = capPrinted; i < pr.capRecordCount(); i++) {
                        float[] c = pr.capRecord(i); StringBuilder ks = new StringBuilder();
                        for (int q = 0; q < Progression.CAP_K; q++) ks.append(String.format(" %s %.0f/%.0f (%.0f pts)", Progression.CAP_KINDS[q], c[1 + 3 * q + 1], c[1 + 3 * q], c[1 + 3 * q + 2]));
                        sb.append(String.format("   NGH hazard control castle %.0f: requested %.2f applied %.2f (%s); accepted/attempted%s%n", c[0], c[1 + Progression.CAP_W], c[2 + Progression.CAP_W], Progression.HC_NAMES[(int) c[3 + Progression.CAP_W]], ks));
                    }
                    capPrinted = pr.capRecordCount();
                }
            } catch (RuntimeException e) { sb.append("STOPPED: ").append(e.getMessage()).append('\n'); }
            int regress = 0; for (int i = 1; i < totals.size(); i++) if (totals.get(i) < totals.get(i - 1)) regress++;
            if (totals.size() > 1) sb.append(String.format("SUMMARY %s seed %d: castles %d, castle 1 -> %d growth %+5.1f%%, regressions %d, backtracks %d%n",
                    new String[]{"NORMAL", "PUNISHER", "NGH"}[lvl], seed, totals.size(), totals.size(), 100 * (totals.get(totals.size() - 1) - totals.get(0)) / totals.get(0), regress, g.backtracks));
            sb.append("REFUSED modules by kind (any attempt): refused / raised layer, hazard, plan, timing, conflict, threat / early\n");
            for (Map.Entry<String, int[]> r : refusedBy.entrySet()) { int[] v = r.getValue(); sb.append(String.format("   %-9s %4d / %4d %4d %4d %4d %4d %4d / %d%n", r.getKey(), v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7])); }
            sb.append("WHY tallies (top 40)\n");
            whys.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(40).forEach(w -> sb.append(String.format("   %5d %s%n", w.getValue(), w.getKey())));
            sb.append(String.format("   (%.0f m in %.0f s)%n", y, (System.nanoTime() - w0) / 1e9));
            System.out.print(sb);
        }
    }
}
