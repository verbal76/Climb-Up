package com.hotatticgames.climbup;

import static org.junit.Assume.assumeTrue;
import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/**
 * Measurement (GEN_TIME=1, run with --rerun): how long each endless slice takes to generate and prove, seed 1 to 5,300 m (GEN_TOP), per 500 m band.
 * The game builds slices on a background thread while you climb (PlayScreen.BUILD_AHEAD). Build 70: 215 s, mean 2.6 s, worst 10 s; Build 71: docs/VERIFICATION.md.
 */
public class GenTimeMeasure {
    @Test public void measure() throws Exception {
        assumeTrue(System.getenv("GEN_TIME") != null);
        Tuning t = TestUtil.tuning();
        Course prev = null; int k = 0; long total = 0, worst = 0; float y = 0f; StringBuilder band = new StringBuilder(); long bandMs = 0; int bandN = 0; int bandTop = 500;
        float top = Float.parseFloat(System.getenv().getOrDefault("GEN_TOP", "5300"));
        while (y < top) {
            long t0 = System.nanoTime();
            Course c = CourseGenerator.chunk(1L, k++, prev, t); prev = c; y = c.get(c.routeSize() - 1).y;
            long ms = (System.nanoTime() - t0) / 1_000_000; total += ms; worst = Math.max(worst, ms); bandMs += ms; bandN++;
            if (y >= bandTop) { band.append(String.format(" <%dm:%dms/slice", bandTop, bandMs / bandN)); bandMs = 0; bandN = 0; bandTop += 500; }
        }
        System.out.printf("GENTIME slices=%d total=%ds mean=%dms worst=%dms%s%n", k, total / 1000, total / k, worst, band);
    }
}
