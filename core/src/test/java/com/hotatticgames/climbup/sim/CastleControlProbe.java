package com.hotatticgames.climbup.sim;

import java.nio.file.*;
import org.junit.Test;

/**
 * Scratch probe (not a fixture; KPI audit 2026-10-30, PUNISHER castle-2 dip): grows PUNISHER seed 7322 to about 1000 m under the old hit rule and
 * the new one and prints, per slice, the castle-to-castle controller's state (Progression in the slice): factors in force, each completed castle's
 * measured T/H/E/X against the profile's target.
 */
public class CastleControlProbe {
    @Test public void punisherCastles() throws Exception {
        org.junit.Assume.assumeTrue(System.getenv("CLIMB_PROBE") != null);
        Tuning t = Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))).hardCopy(1);
        DifficultyProfile P = DifficultyProfile.of(t);
        for (boolean strict : new boolean[]{true, false}) {
            Autopilot.HIT_STRICT = strict;
            GameGrowth g = new GameGrowth(7322, t);
            StringBuilder sb = new StringBuilder("== hitStrict=" + strict + "\n");
            double top = 0; int printed = 0;
            for (int k = 0; k < 40 && top < 1050; k++) {
                CourseGenerator.Framed f = g.next();
                top = f.yBase + f.c.get(f.c.size() - 1).y;
                Progression p = Progression.of(f.c.prog);
                float[] lf = p.lastFactors(), cur = p.current();
                sb.append(String.format("slice %2d top %5.0f castle %d factors T%.2f H%.2f E%.2f X%.2f | castle-in-progress T%.0f H%.0f E%.0f X%.0f%n", k, top, p.castle(), lf[0], lf[1], lf[2], lf[3], cur[0], cur[1], cur[2], cur[3]));
                for (int i = printed; i < p.completedCount(); i++) {
                    float[] r = p.completed(i); int n = (int) r[0];
                    sb.append(String.format("   CASTLE %d DONE: T %.0f/%.0f  H %.0f/%.0f  E %.0f/%.0f  X %.0f/%.0f  (measured/target)%n", n, r[1], P.target(n, 0), r[2], P.target(n, 1), r[3], P.target(n, 2), r[4], P.target(n, 3)));
                }
                printed = p.completedCount();
            }
            System.out.print(sb);
        }
        Autopilot.HIT_STRICT = false;
    }
}
