package com.hotatticgames.climbup.sim;

import java.nio.file.*;
import java.util.*;
import org.junit.Test;

/**
 * Scratch probe (overnight priorities B and C, 2026-10-30; not a fixture). Grows climbs as the game does (GameGrowth) with the observe-only plan audit
 * on and reports (C) module attempts by outcome and the first refusal counter they raised - count, wall time, plans and plan time - so refusals
 * decided by plan-independent checks after the proofs show what an early rejection could save; and (B) encounter proofs (chainClean) by kind
 * (avalanche or other), arrival (at once or after a wait) and outcome: calls, time, plan share, links walked, knocks taken, steps, exact repeats.
 *
 * <p>Env: CLIMB_PROBE, CLIMB_CASES = "mode:seed:top,...".
 */
public class ModuleCostProbe {
    static final String[] WHY = {"layer", "hazard_placement", "plan", "timing_window", "conflict", "threat"};

    @Test public void cost() throws Exception {
        org.junit.Assume.assumeTrue(System.getenv("CLIMB_PROBE") != null);
        String cases = System.getenv().getOrDefault("CLIMB_CASES", "2:7322:150");
        GenTrace.clock = System::nanoTime; Autopilot.PLAN_AUDIT = true;
        Tuning base = Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json"))));
        for (String cs : cases.split(",")) {
            String[] p = cs.split(":"); int lvl = Integer.parseInt(p[0]); long seed = Long.parseLong(p[1]); float top = Float.parseFloat(p[2]);
            Tuning t = lvl == 0 ? base : base.hardCopy(lvl);
            GameGrowth g = new GameGrowth(seed, t);
            TreeMap<String, long[]> cat = new TreeMap<>();          // {modules, nanos, plans, planNanos}
            TreeMap<String, Long> ph = new TreeMap<>(); TreeMap<String, Integer> n = new TreeMap<>();
            long w0 = System.nanoTime(); double y = 0; int k = 0;
            try {
                for (; k < 200 && y < top; k++) {
                    CourseGenerator.Framed f = g.next(); y = f.yBase + f.c.get(f.c.routeSize() - 1).y;
                    for (GenTrace.Module m : g.trace.modules) {
                        if (m.role != null && m.role.equals("STRUCTURE")) continue;
                        String c = m.ok ? "accepted" : m.castleRollback ? "refused_castle_window_after_proofs" : null;
                        if (c == null) { for (int i = 0; i < 6 && c == null; i++) if (m.fails[i] > 0) c = "refused_" + WHY[i] + (i >= 4 && m.fails[2] == 0 && m.fails[3] == 0 ? "_only" : ""); }
                        if (c == null) c = "refused_other";
                        long[] v = cat.computeIfAbsent(c, x -> new long[4]); v[0]++; v[1] += m.nanos; v[2] += m.plans; v[3] += m.planNanos;
                        long[] all = cat.computeIfAbsent("~all", x -> new long[4]); all[0]++; all[1] += m.nanos; all[2] += m.plans; all[3] += m.planNanos;
                    }
                    g.trace.phaseNanos.forEach((key, v) -> { if (key.startsWith("chain")) ph.merge(key, v, Long::sum); });
                    g.trace.counts.forEach((key, v) -> { if (key.startsWith("chain") || key.startsWith("encounter_") || key.startsWith("avalanche_")) n.merge(key, v, Integer::sum); });
                }
            } catch (RuntimeException e) { System.out.println("STOPPED: " + e.getMessage()); }
            double wall = (System.nanoTime() - w0) / 1e9;
            StringBuilder sb = new StringBuilder(String.format("== %s seed %d: %d slices to %.0f m, wall %.0f s%n", new String[]{"NORMAL", "PUNISHER", "NGH"}[lvl], seed, k, y, wall));
            sb.append("  C. module attempts (final slice attempts' trace)   modules    wall s   plans   plan s%n".replace("%n", "\n"));
            cat.forEach((key, v) -> sb.append(String.format("    %-46s %7d %9.1f %7d %8.1f%n", key, v[0], v[1] / 1e9, v[2], v[3] / 1e9)));
            sb.append("  B. encounter proofs\n");
            ph.forEach((key, v) -> sb.append(String.format("    T %-58s %8.1f s%n", key, v / 1e9)));
            n.forEach((key, v) -> sb.append(String.format("    N %-58s %8d%n", key, v)));
            System.out.print(sb);
        }
        Autopilot.PLAN_AUDIT = false;
    }
}
