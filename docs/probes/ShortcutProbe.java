package com.hotatticgames.climbup.sim;
import java.nio.file.*;
/**
 * Dev probe: ShortcutProbe top seed... : on generated NORMAL slices, every pair of platforms on different revolutions (route or branch) with the upper one
 * 0-10 m higher and their arcs within 6 m: does the planner (real physics, run-ups, ledge grabs, pads) get from the lower one onto the upper one?
 * Also every fork branch: each link along it and the rejoin. Prints counts and every shortcut found.
 */
public class ShortcutProbe {
    static boolean launches(Element e) { switch (e.type) { case PAD: case SPRING: case SEESAW: case RAMP: case SWING: return true; default: return e.isMoving(); } }
    public static void main(String[] a) throws Exception {
        float top = Float.parseFloat(a[0]);
        Tuning t = Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json"))));
        for (int si = 1; si < a.length; si++) {
            long seed = Long.parseLong(a[si]); Course prev = null; int k = 0;
            int plainPairs = 0, launchPairs = 0, plainCuts = 0, launchCuts = 0, close = 0, forkLinks = 0, forkBad = 0, forks = 0; float minPlainDy = 99f;
            while (prev == null || prev.get(prev.routeSize() - 1).y < top) {
                Course c = CourseGenerator.chunk(seed, k, prev, t); prev = c;
                for (int i = 0; i < c.size(); i++) {
                    Element lo = c.get(i); if (!lo.isPlatform()) continue;
                    for (int j = 0; j < c.size(); j++) {
                        Element up = c.get(j); if (!up.isPlatform() || j == i) continue;
                        if (Math.abs(lo.s - up.s) < c.circumference * 0.5f) continue;
                        float dy = up.y - lo.y; if (dy <= 0f || dy >= 10f) continue;
                        if (Math.abs(c.dsWrap(lo.s, up.s)) > lo.w * 0.5f + up.w * 0.5f + 6f) continue;
                        boolean launch = launches(lo) || launches(up);
                        if (launch) launchPairs++; else { plainPairs++; minPlainDy = Math.min(minPlainDy, dy); if (dy < 9.5f) close++; }
                        boolean cut = Autopilot.linkExists(c, t, i, j);
                        if (cut) { if (launch) launchCuts++; else plainCuts++;
                            System.out.printf("  SHORTCUT seed %d slice %d: %d %s y=%.1f w=%.1f -> %d %s y=%.1f w=%.1f (dy %.2f, gap %.2f)%n", seed, k, i, lo.type, lo.y, lo.w, j, up.type, up.y, up.w, dy, Math.abs(c.dsWrap(lo.s, up.s)) - lo.w * 0.5f - up.w * 0.5f); }
                    }
                }
                for (int[] f : c.forks) {
                    forks++; int prevI = f[0];
                    for (int q = 0; q < f[2]; q++) { forkLinks++; if (!Autopilot.linkExists(c, t, prevI, f[1] + q)) { forkBad++; System.out.printf("  FORK LINK FAILS seed %d slice %d: %d -> %d%n", seed, k, prevI, f[1] + q); } prevI = f[1] + q; }
                    forkLinks++; if (!Autopilot.linkExists(c, t, prevI, f[3])) { forkBad++; System.out.printf("  FORK REJOIN FAILS seed %d slice %d: %d -> %d%n", seed, k, prevI, f[3]); }
                }
                k++;
            }
            System.out.printf("SEED %d to %.0f m: cross-revolution pairs within 10 m: plain %d (of them closer than 9.5 m: %d, closest %.2f m) -> shortcuts %d; with a launcher %d -> shortcuts %d | forks %d, branch links %d, failing %d%n",
                seed, top, plainPairs, close, minPlainDy, plainCuts, launchPairs, launchCuts, forks, forkLinks, forkBad);
        }
    }
}
