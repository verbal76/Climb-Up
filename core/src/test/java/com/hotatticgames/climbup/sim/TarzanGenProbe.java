package com.hotatticgames.climbup.sim;

import java.nio.file.*;
import org.junit.Test;

/** Scratch probe (not a fixture): grows a climb as the game does and counts the Tarzan swings the generator placed and the rules 19 tallies. */
public class TarzanGenProbe {
    @Test public void count() throws Exception {
        org.junit.Assume.assumeTrue(System.getenv("CLIMB_PROBE") != null);
        Tuning t = Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json"))));
        for (long seed : new long[]{7322}) {
            GameGrowth g = new GameGrowth(seed, t);
            int tz = 0, ramps = 0, slices = 0; java.util.TreeMap<String, Integer> why = new java.util.TreeMap<>(); double secs = 0, top = 0;
            try {
                for (int k = 0; k < 8; k++) {
                    CourseGenerator.Framed f = g.next(); slices++; secs += g.seconds;
                    for (int i = 0; i < f.c.size(); i++) { Element e = f.c.get(i); if (e.type == Element.Type.TARZAN) tz++; if (e.type == Element.Type.RAMP) ramps++; }
                    top = f.yBase + f.c.get(f.c.size() - 1).y;
                    for (java.util.Map.Entry<String, Integer> e : g.trace.counts.entrySet()) if (e.getKey().startsWith("r19_") || e.getKey().equals("module_tarzan")) why.merge(e.getKey(), e.getValue(), Integer::sum);
                }
            } catch (RuntimeException e) { System.out.println("seed " + seed + " stopped at slice " + g.failedSlice + ": " + e.getMessage()); }
            System.out.printf("seed %d: %d slices to %.0f m in %.1f s, %d Tarzan swings, %d ramps, backtracks %d, %s%n", seed, slices, top, secs, tz, ramps, g.backtracks, why);
        }
    }
}
