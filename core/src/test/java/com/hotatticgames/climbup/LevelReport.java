package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;
import java.util.*;

/**
 * Owner directive 2026-10-24: per-level evidence for the 10 timed levels (castle n at n x 500 m). For each level of each mode: links, encounter score
 * (mean, 90th percentile, max), share of hard links (score >= 3), demand conflicts, and how often each mechanic appears.
 *   java -cp ... com.hotatticgames.climbup.LevelReport <mode 0|1> <seed> [<seed>...]
 */
public class LevelReport {
    public static void main(String[] a) throws Exception {
        int mode = Integer.parseInt(a[0]);
        Tuning t0 = TestUtil.tuning(), t = mode == 0 ? t0 : t0.hardCopy(mode);
        List<List<Float>> scores = new ArrayList<>(); List<int[]> conflicts = new ArrayList<>(); List<TreeMap<String, Integer>> mech = new ArrayList<>();
        for (int L = 0; L < 10; L++) { scores.add(new ArrayList<>()); conflicts.add(new int[1]); mech.add(new TreeMap<>()); }
        for (int si = 1; si < a.length; si++) {
            long seed = Long.parseLong(a[si]); Course prev = null; int k = 0;
            while (prev == null || prev.get(prev.routeSize() - 1).y < 5000f) {
                Course c = CourseGenerator.chunk(seed, k++, prev, t); prev = c;
                for (int i = 0; i < c.routeSize() - 1; i++) {
                    Element e = c.get(i); int L = (int) (e.y / 500f); if (L < 0 || L > 9) continue;
                    EncounterModel.Link l = EncounterModel.link(c, t, i);
                    scores.get(L).add(l.score); if (l.conflict) conflicts.get(L)[0]++;
                    mech.get(L).merge("p:" + e.type.name() + (e.type == Element.Type.RAMP || e.type == Element.Type.SEESAW ? e.skin : ""), 1, Integer::sum);
                }
                for (Element h : c.hazards) { if (h.anchor < 0 || h.anchor >= c.size()) continue; int L = (int) (c.get(h.anchor).y / 500f); if (L >= 0 && L <= 9) mech.get(L).merge("h:" + h.type.name(), 1, Integer::sum); }
            }
        }
        System.out.println("LEVELS mode " + Tuning.HARD_NAMES[mode] + " seeds " + String.join(",", Arrays.copyOfRange(a, 1, a.length)));
        for (int L = 0; L < 10; L++) {
            List<Float> s = scores.get(L); Collections.sort(s); if (s.isEmpty()) continue;
            float sum = 0, hard = 0; for (float v : s) { sum += v; if (v >= 3f) hard++; }
            System.out.printf(Locale.ROOT, "L%-2d links %4d mean %.2f p90 %.2f max %.2f hard %.0f%% conflicts %d | %s%n", L + 1, s.size(), sum / s.size(), s.get((int) (s.size() * 0.9f)), s.get(s.size() - 1), 100f * hard / s.size(), conflicts.get(L)[0], mech.get(L));
        }
    }
}
