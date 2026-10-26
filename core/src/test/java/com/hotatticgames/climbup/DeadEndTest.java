package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.util.*;
import org.junit.Test;

/**
 * Side structures. Until Build 79 these were long dead-end spurs; the owner directive of 2026-10-24 (Build 80) replaced dead ends with forks whose branch
 * reconnects to the route (and later "no stranded platforms or dead ends"), so this test now holds the current rule: forks exist, every branch rejoins the
 * route further on, nothing off the route is a long dead end, branches step up and down to throw the climber off, and the route itself sometimes steps
 * down. (The Build 79 left/right count no longer applies: a branch runs ahead to its rejoin by construction.) Completability is proven by the generator
 * (fork proofs) and the solver suites.
 */
public class DeadEndTest {
    @Test public void forksReplaceDeadEndsAndEveryBranchRejoinsTheRoute() throws Exception {
        Tuning t = TestUtil.tuning();
        int longSpurs = 0, slices = 0, forks = 0, upAndDown = 0, routeDown = 0;
        for (long seed = 160; seed < 172; seed++) {
            Course prev = null;
            for (int k = 0; k < 5; k++) {
                Course c = CourseGenerator.chunk(seed, k, prev, t); prev = c; slices++;
                for (int[] f : c.forks) {
                    forks++;
                    assertTrue("a fork's branch rejoins the route further on (take-off " + f[0] + ", rejoin " + f[3] + ")", f[3] > f[0] && f[3] < c.routeSize());
                    assertTrue("a fork's branch lies off the route", f[1] >= c.routeSize() && f[2] >= 1);
                    boolean up = false, down = false; float py = c.get(f[0]).y;
                    for (int i = f[1]; i < f[1] + f[2]; i++) { Element d = c.get(i); if (d.y > py + 0.05f) up = true; if (d.y < py - 0.05f) down = true; py = d.y; }
                    if (up && down) upAndDown++;
                }
                Map<Integer, List<Element>> byAnchor = new HashMap<>();
                for (int i = c.routeSize(); i < c.size(); i++) {
                    Element d = c.get(i); assertTrue("side platforms hang off a route element", d.anchor >= 0 && d.anchor < c.routeSize());
                    if (!CourseGenerator.onForkBranch(c, i)) byAnchor.computeIfAbsent(d.anchor, x -> new ArrayList<>()).add(d);
                }
                for (int i = 1; i < c.routeSize(); i++) if (c.get(i).type == Element.Type.STATIC && c.get(i - 1).type == Element.Type.STATIC && c.get(i).y < c.get(i - 1).y - 0.35f) routeDown++;
                for (List<Element> g : byAnchor.values()) if (g.size() >= 4) longSpurs++;
            }
        }
        assertTrue("forks appear (" + forks + " in " + slices + " slices)", forks >= 10);
        assertEquals("no long dead end off the route (forks replace them)", 0, longSpurs);
        assertTrue("fork branches step both up and down (" + upAndDown + ")", upAndDown >= 10);
        assertTrue("the route sometimes steps down before it climbs on (" + routeDown + ")", routeDown >= 5);
    }
}
