package com.hotatticgames.climbup.sim;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Generation cost as the game pays it (owner directive 2026-10-26: separate real generation from reporting overhead and solving). Generates a climb
 * slice by slice with NO trace installed (the game's path: GenTrace off, so no module records and a zero clock), timing each slice, and runs the slice
 * solver separately to get how long the climber takes to climb it. A streaming model then estimates waiting: the game builds slices on one background
 * thread, in order, and needs each slice admitted when the highest point climbed reaches its foot minus PlayScreen.ADMIT_AHEAD (100 m). Generation is
 * scaled by assumed device factors (1x = this machine; the slower factors are ASSUMPTIONS standing in for a phone, not measurements of one).
 *
 *   java ... GenBench tuning.json mode seed topMetres out.csv
 */
public final class GenBench {
    public static void main(String[] a) throws Exception {
        Tuning base = Tuning.parse(new String(Files.readAllBytes(Paths.get(a[0]))));
        int mode = Integer.parseInt(a[1]); long seed = Long.parseLong(a[2]); float top = Float.parseFloat(a[3]);
        Tuning t = mode == 0 ? base : base.hardCopy(mode);
        List<double[]> rows = new ArrayList<>();       // {slice, y0, y1, gen_s, climb_s}
        int unsolved = 0, f0 = CourseGenerator.SLICE_FAILURES.get(), b0 = CourseGenerator.BACKTRACKS.get();
        CourseGenerator.Framed f = null; int k = 0; String stopped = "";
        try (PrintWriter w = new PrintWriter(new FileWriter(a[4]))) {
            w.println("mode,seed,slice,y0,y1,gen_s,climb_s,solver_completed");
            while (f == null || f.yBase + f.c.get(f.c.routeSize() - 1).y < top) {
                long g0 = System.nanoTime();
                try { f = CourseGenerator.chunkFramed(seed, k, f, t); } catch (RuntimeException e) { stopped = "generation failed at slice " + k + ": " + e.getMessage(); break; }
                double gs = (System.nanoTime() - g0) / 1e9;
                Autopilot.Report r = Autopilot.run(f.c, t, 4000f);
                double y0 = f.yBase + f.c.get(0).y, y1 = f.yBase + f.c.get(f.c.routeSize() - 1).y;
                rows.add(new double[]{k, y0, y1, gs, r.simTime}); if (!r.completed) unsolved++;
                w.printf(Locale.ROOT, "%d,%d,%d,%.1f,%.1f,%.3f,%.1f,%b%n", mode, seed, k, y0, y1, gs, r.simTime, r.completed); w.flush();
                k++;
            }
        }
        double gen = 0, worst = 0; for (double[] r : rows) { gen += r[3]; worst = Math.max(worst, r[3]); }
        System.out.printf(Locale.ROOT, "GENBENCH mode %d seed %d: %d slices to %.0f m, generation %.0f s (mean %.2f s, worst %.2f s per slice), failed slice attempts %d, backtracks %d, solver-unsolved slices %d %s%n",
            mode, seed, rows.size(), rows.isEmpty() ? 0 : rows.get(rows.size() - 1)[2], gen, rows.isEmpty() ? 0 : gen / rows.size(), worst,
            CourseGenerator.SLICE_FAILURES.get() - f0, CourseGenerator.BACKTRACKS.get() - b0, unsolved, stopped);
        for (double pace : new double[]{1, 0.5}) for (double factor : new double[]{1, 3, 5}) {          // pace 0.5: a climber twice as fast as the solver
            double ready = 0, played = 0, stall = 0, maxStall = 0; int stalls = 0;
            for (int i = 1; i < rows.size(); i++) {          // slice 0 is prepared before the climb starts
                ready += rows.get(i)[3] * factor;
                // the climber reaches (foot of slice i - 100 m) after climbing the slices below that height
                double need = rows.get(i)[1] - 100.0, when = 0;
                for (int j = 0; j < i; j++) { double[] s = rows.get(j); if (s[2] <= need) when += s[4] * pace; else { double part = Math.max(0, (need - s[1]) / Math.max(1e-3, s[2] - s[1])); when += s[4] * pace * part; break; } }
                double wait = ready - (when + stall);
                if (wait > 0) { stall += wait; stalls++; maxStall = Math.max(maxStall, wait); }
                played = when;
            }
            System.out.printf(Locale.ROOT, "  streaming estimate, climb pace x%.1f, generation x%.0f: %d waits, total %.1f s, longest %.1f s (climb time %.0f s)%n", pace, factor, stalls, stall, maxStall, played);
        }
    }
}
