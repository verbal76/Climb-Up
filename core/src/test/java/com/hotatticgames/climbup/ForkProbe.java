package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;

/** Owner directive 2026-10-24: forks per mode on DeadEndTest's slices (seeds 160-171, 5 slices each), branch lengths, second branches, and that every branch reconnects. */
public class ForkProbe {
    public static void main(String[] a) throws Exception {
        int mode = Integer.parseInt(a[0]);
        Tuning t0 = TestUtil.tuning(), t = mode == 0 ? t0 : t0.hardCopy(mode);
        int slices = 0, forks = 0, nested = 0, broken = 0, len = 0, maxLen = 0;
        for (long seed = 160; seed < (a.length > 1 ? 160 + Integer.parseInt(a[1]) : 172); seed++) {
            Course prev = null;
            for (int k = 0; k < 5; k++) {
                Course c = CourseGenerator.chunk(seed, k, prev, t); prev = c; slices++;
                for (int[] f : c.forks) {
                    forks++; if (f[0] >= c.routeSize()) nested++; len += f[2]; maxLen = Math.max(maxLen, f[2]);
                    boolean ok = Autopilot.linkExists(c, t, f[0], f[1]);
                    for (int q = f[1]; ok && q < f[1] + f[2] - 1; q++) ok = Autopilot.linkExists(c, t, q, q + 1);
                    ok = ok && Autopilot.linkExists(c, t, f[1] + f[2] - 1, f[3]);
                    if (!ok) { broken++; System.out.println("BROKEN seed " + seed + " slice " + k + " fork " + java.util.Arrays.toString(f)); }
                }
            }
        }
        System.out.println("ROOMHIST gap0..6,above | dy<-1,-1..0,0..1,1..2,>2 " + java.util.Arrays.toString(CourseGenerator.ROOMHIST));
        System.out.println("FORKWHY [target, span, decoyWhy0..6, corridor, pairIn, pairStep, padUp] " + java.util.Arrays.toString(CourseGenerator.FORKWHY));
        System.out.printf("FORKS %s: %d forks in %d slices (%d second branches), branch length mean %.1f max %d, broken %d%n", Tuning.HARD_NAMES[mode], forks, slices, nested, forks > 0 ? (float) len / forks : 0f, maxLen, broken);
    }
}
