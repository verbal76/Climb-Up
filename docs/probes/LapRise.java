package com.hotatticgames.climbup.sim;
import java.nio.file.*;
/** Dev probe: LapRise seed slices : route height gained per tower revolution, and how many wide (>= 8 m) route decks have a route platform of another revolution within 9.5 m above or below on an overlapping arc. Uses only API shared with Build 79. */
public class LapRise {
    public static void main(String[] a) throws Exception {
        long seed = Long.parseLong(a[0]); int n = Integer.parseInt(a[1]);
        Tuning t = Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json"))));
        Course prev = null; double rise = 0, laps = 0; int wide = 0, crowded = 0;
        for (int k = 0; k < n; k++) {
            Course c = CourseGenerator.chunk(seed, k, prev, t); prev = c;
            int rs = c.routeSize(); Element f = c.get(0), l = c.get(rs - 1);
            rise += l.y - f.y; laps += (l.s - f.s) / c.circumference;
            for (int i = 0; i < rs; i++) { Element e = c.get(i); if (!e.isPlatform() || e.w < 8f) continue; wide++;
                for (int j = 0; j < rs; j++) { Element g = c.get(j); if (!g.isPlatform() || Math.abs(e.s - g.s) < c.circumference * 0.5f) continue;
                    if (Math.abs(c.dsWrap(e.s, g.s)) < e.halfW() + g.halfW() + 2.5f && Math.abs(e.y - g.y) < 9.5f) { crowded++; break; } } }
        }
        System.out.printf("seed %d %d slices: %.2f m per revolution (%.1f revolutions), wide decks %d, of them within 9.5 m of another revolution %d%n", seed, n, rise / laps, laps, wide, crowded);
    }
}
