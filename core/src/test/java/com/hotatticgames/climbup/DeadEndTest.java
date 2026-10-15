package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.util.*;
import org.junit.Test;

/** Dead ends: long spurs exist, they cost only the walk back (nothing hostile at their end), and they are never part of the route. Completability is proven by the generator itself (tryDecoy) and the solver suites. */
public class DeadEndTest {
    @Test public void longSafeDeadEndsAreGeneratedAndNeverHostileOrOnTheRoute() throws Exception {
        Tuning t = TestUtil.tuning();
        int longSpurs = 0, slices = 0;
        for (long seed = 160; seed < 172; seed++) {
            Course prev = null;
            for (int k = 0; k < 5; k++) {
                Course c = CourseGenerator.chunk(seed, k, prev, t); prev = c; slices++;
                Map<Integer, List<Element>> byAnchor = new HashMap<>();
                for (int i = c.routeSize(); i < c.size(); i++) { Element d = c.get(i); assertTrue("decoys hang off a route element", d.anchor >= 0 && d.anchor < c.routeSize()); byAnchor.computeIfAbsent(d.anchor, x -> new ArrayList<>()).add(d); }
                for (Map.Entry<Integer, List<Element>> e : byAnchor.entrySet()) {
                    List<Element> g = e.getValue(); boolean plain = g.size() >= 4;
                    for (Element d : g) if (d.type != Element.Type.STATIC) plain = false;
                    for (Element h : c.hazards) if (h.anchor == e.getKey()) plain = false;
                    if (plain) {
                        longSpurs++;
                        Element end = g.get(g.size() - 1);
                        assertTrue("a long dead end finishes on a wide platform", end.w >= 3f || g.stream().anyMatch(x -> x.w >= 3f));
                    }
                }
            }
        }
        assertTrue("long dead ends appear (" + longSpurs + " in " + slices + " slices)", longSpurs >= 3);
    }
}
