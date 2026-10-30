package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;
import java.util.*;
import org.junit.Test;

/**
 * Scratch probe (owner follow-up 2026-10-30 on PR #16; not a fixture): the two OfficialWorldRulesTest discrepancies, measured exactly as that
 * test measures them (TestUtil.slices: CourseGenerator.chunk, NORMAL tuning). (1) Every red gem up to CLIMB_TOP: its height, deviation from the
 * section midpoint, the slice it is in, the route elements either side and whether it is a rest the generator added. (2) The test's
 * "obstacle load" per seed over the first 1,100 m (hazard list entries + moving route elements), broken down by type, and the hi/lo ratio.
 *
 * <p>Env: CLIMB_PROBE, CLIMB_SEEDS (default 1-8), CLIMB_TOP (default 1100).
 */
public class GemSpreadProbe {
    @Test public void gemsAndSpread() throws Exception {
        org.junit.Assume.assumeTrue(System.getenv("CLIMB_PROBE") != null);
        String seedsEnv = System.getenv().getOrDefault("CLIMB_SEEDS", "1,2,3,4,5,6,7,8");
        float top = Float.parseFloat(System.getenv().getOrDefault("CLIMB_TOP", "1100"));
        StringBuilder sb = new StringBuilder("== config: tarzanOff=" + Boolean.getBoolean("climb.tarzanOff") + " turboOff=" + Boolean.getBoolean("climb.turboOff") + " hitStrict=" + Boolean.getBoolean("climb.hitStrict") + "\n");
        List<Integer> loads = new ArrayList<>();
        for (String ss : seedsEnv.split(",")) {
            long seed = Long.parseLong(ss.trim());
            List<Course> cs = TestUtil.slices(seed, top);
            int gemNo = 0;
            for (int k = 0; k < cs.size(); k++) {
                Course c = cs.get(k);
                for (int i = 1; i < c.routeSize(); i++) {
                    Element e = c.get(i);
                    if (e.type != Element.Type.STATIC || e.skin != 3) continue;
                    gemNo++;
                    float mid = 250f + 500f * (gemNo - 1), dev = e.y - mid;
                    Element a = c.get(i - 1), b = i + 1 < c.routeSize() ? c.get(i + 1) : null;
                    sb.append(String.format("seed %d gem %d: y %.2f mid %.0f dev %+.2f%s | slice %d idx %d w %.2f | before %s y %.2f (%+.2f) | after %s%n", seed, gemNo, e.y, mid, dev, Math.abs(dev) > 4.5f ? "  OVER 4.5" : "",
                            k, i, e.w, a.type, a.y, e.y - a.y, b == null ? "-" : b.type + String.format(" y %.2f (%+.2f)", b.y, b.y - e.y)));
                }
            }
            // the test's load: first 1,100 m only
            int hazards = 0, movers = 0; TreeMap<String, Integer> by = new TreeMap<>();
            for (Course c : TestUtil.slices(seed, 1100f)) {
                for (int i = 0; i < c.routeSize(); i++) if (c.get(i).isMoving()) { movers++; by.merge("mover_" + c.get(i).type, 1, Integer::sum); }
                for (Element h : c.hazards) { hazards++; by.merge(h.type.name(), 1, Integer::sum); }
            }
            loads.add(hazards + movers);
            sb.append(String.format("seed %d load %d (hazard list %d + movers %d): %s%n", seed, hazards + movers, hazards, movers, by));
        }
        int lo = Collections.min(loads), hi = Collections.max(loads);
        sb.append(String.format("loads %s  hi/lo %.3f (test needs >= 1.100)%n", loads, hi / (double) lo));
        System.out.print(sb);
    }
}
