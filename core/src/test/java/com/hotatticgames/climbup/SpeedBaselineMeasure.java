package com.hotatticgames.climbup;

import static org.junit.Assume.assumeTrue;

import com.hotatticgames.climbup.sim.*;
import org.junit.Test;

/**
 * Measurement only (skipped unless SPEED_BASELINE=seeds is set): the autopilot plays the official 0 to 5,000 m climb on real
 * seeds with no falls and no hits allowed (any setback fails the slice), never waiting longer than a moving platform needs,
 * and the slice times are added up to castle 10. Run: ./gradlew :core:test --tests '*SpeedBaselineMeasure*' (with SPEED_BASELINE=1,2,3 in the environment)
 */
public class SpeedBaselineMeasure {
    @Test public void flawlessAutopilotTimeToCastle10() throws Exception {
        String seeds = System.getenv().getOrDefault("SPEED_BASELINE", "");
        assumeTrue("measurement only", !seeds.isEmpty());
        Tuning t = TestUtil.tuning();
        for (String sv : seeds.split(",")) {
            long seed = Long.parseLong(sv.trim());
            Course prev = null; float y = 0, total = 0; int k = 0;
            while (y < 5000f) {
                Course c = CourseGenerator.chunk(seed, k++, prev, t); prev = c;
                float y0 = c.get(0).y, y1 = c.get(c.routeSize() - 1).y;
                Autopilot.Report r = Autopilot.run(c, t, 4000f);
                if (!r.completed) { System.out.printf("SPEED seed=%d slice=%d FAILED (%s)%n", seed, k - 1, r.failInfo); total = Float.NaN; break; }
                float part = y1 > 5000f && y1 > y0 ? (5000f - y0) / (y1 - y0) : 1f;      // the slice that holds castle 10: count only the share below it
                total += r.simTime * part; y = y1;
                System.out.printf("SPEED seed=%d slice=%d %.0f-%.0f m %.1f s%n", seed, k - 1, y0, y1, r.simTime);
            }
            System.out.printf("SPEED_RESULT seed=%d castle10=%.1f s (%.1f min)%n", seed, total, total / 60f);
        }
    }
}
