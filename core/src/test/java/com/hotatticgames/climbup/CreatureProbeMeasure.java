package com.hotatticgames.climbup;

import static org.junit.Assume.assumeTrue;
import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/**
 * Build 71 measurement (not part of the normal suite; CREATURE_PROBE=1 ./gradlew :core:test --tests '*CreatureProbeMeasure'): grows seeds 7, 11 and 23 to
 * 3,200 m and lets the autopilot play every slice holding a Build 71 hazard (skull, blob, chained spike drops, mortar rows, ice) from its bottom to its top,
 * with real movement only. Prints the counts and every slice it could not finish.
 */
public class CreatureProbeMeasure {
    @Test public void probe() throws Exception {
        assumeTrue(System.getenv("CREATURE_PROBE") != null);
        Tuning t = TestUtil.tuning();
        for (long seed : new long[]{7, 11, 23}) {
            Course prev = null; float y = 0; int k = 0, sk = 0, bl = 0, be = 0, mo = 0, dr = 0, wet = 0, ran = 0, fails = 0; long t0 = System.currentTimeMillis();
            while (y < 3200f) {
                Course c = CourseGenerator.chunk(seed, k++, prev, t); prev = c; y = c.get(c.routeSize() - 1).y;
                int s0 = 0, b0 = 0, d0 = 0, w0 = 0;
                for (int i = 0; i < c.size(); i++) if (c.get(i).wet()) { w0++; wet++; }
                for (Element h : c.hazards) { if (h.type == Element.Type.SPIKE_DROP) { dr++; d0++; } if (h.type == Element.Type.MORTAR) d0++; if (h.type == Element.Type.SKULL) { sk++; s0++; } if (h.type == Element.Type.BLOB) { bl++; b0++; } if (h.type == Element.Type.BEE) be++; if (h.type == Element.Type.MORTAR) mo++; }
                if (s0 + b0 + d0 + w0 > 0) { ran++;
                    Autopilot.Report r = Autopilot.run(c, t, 4000f);
                    if (!r.completed) { fails++; System.out.printf("PROBE seed=%d slice=%d y=%.0f skull=%d blob=%d FAIL link %d %s%n", seed, k - 1, y, s0, b0, r.failedLink, r.failInfo); }
                }
            }
            System.out.printf("PROBE seed=%d skulls=%d blobs=%d bees=%d mortars=%d drops=%d wet=%d slicesRun=%d runFails=%d gen+run=%ds%n", seed, sk, bl, be, mo, dr, wet, ran, fails, (System.currentTimeMillis() - t0) / 1000);
        }
    }
}
