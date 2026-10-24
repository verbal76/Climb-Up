package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;
import java.util.*;

/**
 * Build 79 dev probe for DeadEndTest: on its seeds (160-171, 5 slices each) counts decoy groups by size and why each group of 4+ is not a "long plain dead end"
 * (a non-static piece, or a hazard on the route element it hangs off).
 *   ./gradlew :core:probe -Pmain=com.hotatticgames.climbup.DeadEndProbe
 */
public class DeadEndProbe {
    public static void main(String[] a) throws Exception {
        Tuning t = TestUtil.tuning();
        int longSpurs = 0, big = 0, notStatic = 0, hazarded = 0; Map<Integer, Integer> sizes = new TreeMap<>(); Map<String, Integer> hazTypes = new TreeMap<>();
        for (long seed = 160; seed < 172; seed++) {
            Course prev = null;
            for (int k = 0; k < 5; k++) {
                Course c = CourseGenerator.chunk(seed, k, prev, t); prev = c;
                Map<Integer, List<Element>> by = new HashMap<>();
                for (int i = c.routeSize(); i < c.size(); i++) by.computeIfAbsent(c.get(i).anchor, x -> new ArrayList<>()).add(c.get(i));
                for (Map.Entry<Integer, List<Element>> e : by.entrySet()) {
                    List<Element> g = e.getValue(); sizes.merge(g.size(), 1, Integer::sum);
                    if (g.size() < 4) continue;
                    big++;
                    boolean st = true; for (Element d : g) if (d.type != Element.Type.STATIC) st = false;
                    boolean hz = false; for (Element h : c.hazards) if (h.anchor == e.getKey()) { hz = true; hazTypes.merge(h.type.toString(), 1, Integer::sum); }
                    if (!st) notStatic++; else if (hz) hazarded++; else longSpurs++;
                }
            }
        }
        System.out.println("DEADEND groups by size " + sizes + " | 4+: " + big + " plain " + longSpurs + " notStatic " + notStatic + " hazardOnAnchor " + hazarded + " " + hazTypes);
    }
}
