package com.hotatticgames.climbup.sim;

import java.nio.file.*;
import org.junit.Test;

/**
 * Scratch probe (not a fixture; NGH generation cost investigation 2026-10-30): grows NGH seed 7322 to {@code CLIMB_TOP} m (default 600) under the
 * old hit rule and the new one and prints where generation time goes: plans by outcome (count, seconds) and the collision search (found / none).
 */
public class NghCostProbe {
    @Test public void cost() throws Exception {
        org.junit.Assume.assumeTrue(System.getenv("CLIMB_PROBE") != null);
        float topM = System.getenv("CLIMB_TOP") != null ? Float.parseFloat(System.getenv("CLIMB_TOP")) : 600f;
        GenTrace.clock = System::nanoTime;
        Tuning t = Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))).hardCopy(2);
        for (boolean strict : new boolean[]{true, false}) {
            Autopilot.HIT_STRICT = strict;
            GameGrowth g = new GameGrowth(7322, t);
            java.util.TreeMap<String, Long> ph = new java.util.TreeMap<>(); java.util.TreeMap<String, Integer> n = new java.util.TreeMap<>();
            double top = 0; int slices = 0; long w0 = System.nanoTime();
            for (int k = 0; k < 60 && top < topM; k++) {
                CourseGenerator.Framed f = g.next(); slices++;
                top = f.yBase + f.c.get(f.c.size() - 1).y;
                g.trace.phaseNanos.forEach((key, v) -> { if (key.startsWith("r19")) ph.merge(key, v, Long::sum); });
                g.trace.counts.forEach((key, v) -> { if (key.startsWith("r19plan_n_") || key.equals("r19_why_rebound_trials")) n.merge(key, v, Integer::sum); });
            }
            double wall = (System.nanoTime() - w0) / 1e9;
            StringBuilder sb = new StringBuilder(String.format("== NGH hitStrict=%b: %d slices to %.0f m, wall %.0f s (%.2f s per 10 m)%n", strict, slices, top, wall, wall / (top / 10)));
            ph.forEach((key, v) -> sb.append(String.format("   %-22s %8.1f s%n", key, v / 1e9)));
            n.forEach((key, v) -> sb.append(String.format("   %-22s %8d%n", key, v)));
            System.out.print(sb);
        }
        Autopilot.HIT_STRICT = false;
    }
}
