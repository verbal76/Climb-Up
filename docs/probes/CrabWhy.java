package com.hotatticgames.climbup.sim;
import java.nio.file.*;
/** Dev probe: CrabWhy hard top seed... : generation only; why crab modules are refused, and crabs/clubs per level. */
public class CrabWhy {
    public static void main(String[] a) throws Exception {
        int hard = Integer.parseInt(a[0]); float top = Float.parseFloat(a[1]);
        Tuning t = Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); if (hard > 0) t = t.hardCopy(hard);
        for (int i = 2; i < a.length; i++) {
            long seed = Long.parseLong(a[i]); java.util.Arrays.fill(CourseGenerator.CRABWHY, 0); java.util.Arrays.fill(CourseGenerator.CRABCLASH, 0);
            int[] crabs = new int[11], clubs = new int[11], decks = new int[11]; Course prev = null; int k = 0; long t0 = System.nanoTime();
            while (prev == null || prev.get(prev.routeSize() - 1).y < top) {
                Course c = CourseGenerator.chunk(seed, k++, prev, t); prev = c;
                java.util.Set<Integer> dk = new java.util.HashSet<>();
                for (Element h : c.hazards) if (h.anchor >= 0 && h.anchor < c.size()) { int L = Math.min(10, (int) (c.get(h.anchor).y / 500f) + 1);
                    if (h.type == Element.Type.CRAB) { crabs[L]++; if (dk.add(h.anchor)) decks[L]++; } if (h.type == Element.Type.CLUB) clubs[L]++; }
            }
            int[] w = CourseGenerator.CRABWHY;
            System.out.printf("SEED %d hard %d to %.0f m (%.0f s): crab modules tried %d: layer %d haz %d plan %d window %d conflict %d threat %d other %d accepted %d%n", seed, hard, top, (System.nanoTime() - t0) / 1e9, w[0], w[1], w[2], w[3], w[4], w[5], w[6], w[0] - w[1] - w[2] - w[3] - w[4] - w[5] - w[6] - w[7], w[7]);
            StringBuilder sb = new StringBuilder("  per level crabs/decks/clubs:"); for (int L = 1; L <= 10; L++) if (crabs[L] + clubs[L] > 0) sb.append(String.format(" L%d %d/%d/%d", L, crabs[L], decks[L], clubs[L]));
            System.out.println(sb);
            int[] q = CourseGenerator.CRABCLASH;
            System.out.printf("  layer clashes: this slice route body %d, route 9.5 m %d, branch body %d, branch 9.5 m %d | earlier slice route body %d, route 9.5 m %d, branch body %d, branch 9.5 m %d%n", q[0], q[1], q[2], q[3], q[4], q[5], q[6], q[7]);
        }
    }
}
