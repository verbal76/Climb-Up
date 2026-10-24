package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.util.*;
import org.junit.Test;

/** Dead ends: long spurs exist, they cost only the walk back (nothing hostile at their end), and they are never part of the route. Completability is proven by the generator itself (tryDecoy) and the solver suites. */
public class DeadEndTest {
    @Test public void longSafeDeadEndsAreGeneratedAndNeverHostileOrOnTheRoute() throws Exception {
        Tuning t = TestUtil.tuning();
        int longSpurs = 0, slices = 0, left = 0, right = 0, upAndDown = 0, routeDown = 0;
        for (long seed = 160; seed < 172; seed++) {
            Course prev = null;
            for (int k = 0; k < 5; k++) {
                Course c = CourseGenerator.chunk(seed, k, prev, t); prev = c; slices++;
                Map<Integer, List<Element>> byAnchor = new HashMap<>();
                for (int i = c.routeSize(); i < c.size(); i++) { Element d = c.get(i); assertTrue("decoys hang off a route element", d.anchor >= 0 && d.anchor < c.routeSize()); byAnchor.computeIfAbsent(d.anchor, x -> new ArrayList<>()).add(d); }
                for (int i = 1; i < c.routeSize(); i++) if (c.get(i).type == Element.Type.STATIC && c.get(i - 1).type == Element.Type.STATIC && c.get(i).y < c.get(i - 1).y - 0.35f) routeDown++;
                for (Map.Entry<Integer, List<Element>> e : byAnchor.entrySet()) {
                    List<Element> g = e.getValue(); boolean plain = g.size() >= 4;
                    Element an = c.get(e.getKey());
                    if (g.size() >= 2 && Math.abs(g.get(0).y - an.y) < 3f) {          // a spur (lures float higher): which way it branches, and whether it steps both up and down
                        if (c.dsWrap(g.get(0).s, an.s) > 0) right++; else left++;
                        boolean up = false, down = false; float py = an.y;
                        for (Element d : g) { if (d.y > py + 0.05f) up = true; if (d.y < py - 0.05f) down = true; py = d.y; }
                        if (up && down) upAndDown++;
                    }
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
        // Build 79 (owner): dead ends branch to the left and to the right, step up and down to throw you off, and the route itself sometimes steps down
        assertTrue("dead ends branch both ways (left " + left + ", right " + right + ")", left >= 10 && right >= 10);
        assertTrue("dead ends step both up and down (" + upAndDown + ")", upAndDown >= 10);
        assertTrue("the route sometimes steps down before it climbs on (" + routeDown + ")", routeDown >= 5);
    }
}
