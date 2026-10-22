package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.Test;

/** Build 72 validation sweep (not part of the suite): the whole-course solver on every framed slice of several seeds, under the one-speed stick. */
public class SolverSweepProbe {
    @Test public void sweep() throws Exception {
        if (System.getenv("SWEEP") == null) return;
        Tuning t = TestUtil.tuning();
        long[] seeds = {1, 7, 21, 99, 130, 178};
        int slices = Integer.parseInt(System.getenv().getOrDefault("SWEEP_SLICES", "78"));
        ExecutorService ex = Executors.newFixedThreadPool(Math.min(seeds.length, Runtime.getRuntime().availableProcessors()));
        List<Future<String>> out = new ArrayList<>();
        for (long seed : seeds) out.add(ex.submit(() -> {
            StringBuilder sb = new StringBuilder(); int ok = 0, fail = 0; long t0 = System.nanoTime();
            CourseGenerator.Framed f = null;
            for (int k = 0; k < slices; k++) {
                f = CourseGenerator.chunkFramed(seed, k, f, t);
                Autopilot.Report r = Autopilot.run(f.c, t, 4000f);
                if (r.completed) ok++; else { fail++; sb.append(String.format("  seed %d slice %d link %d: %s%n", seed, k, r.failedLink, r.failInfo)); }
            }
            return String.format("SWEEP seed %d: %d/%d slices completed (%.0f s)%n", seed, ok, ok + fail, (System.nanoTime() - t0) / 1e9) + sb;
        }));
        int total = 0, bad = 0;
        for (Future<String> fu : out) { String s = fu.get(); System.out.print(s); }
        ex.shutdown();
    }
}
