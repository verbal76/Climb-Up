package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;

/** Build 79 dev probe: how long one slice takes to generate, cold and warm (the game prepares the next slice while you climb). */
public class GenTimeProbe {
    public static void main(String[] a) throws Exception {
        Tuning t = TestUtil.tuning();
        for (long seed : new long[]{1, 2, 3}) {
            Course prev = null; long worst = 0, total = 0; int n = 12;
            for (int k = 0; k < n; k++) { long t0 = System.nanoTime(); prev = CourseGenerator.chunk(seed, k, prev, t); long d = System.nanoTime() - t0; worst = Math.max(worst, d); total += d; }
            System.out.printf("GEN seed %d: %d slices, mean %.0f ms, worst %.0f ms%n", seed, n, total / 1e6 / n, worst / 1e6);
        }
    }
}
