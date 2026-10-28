package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.util.*;
import org.junit.Test;

/**
 * Side structures (owner, binding 2026-10-26). The Build 79 dead-end spurs were replaced in Build 80 by a connected route network: the main route plus
 * forks and nested sub-forks that all lead back into it. Alternate routes are traversal choices: they may be longer or shorter, faster or slower than the
 * main route, and travel in any direction; players find the best route. So this test checks connectivity, not length or time: forks exist, every branch
 * leaves from the route network and leads back into it further on (a sub-fork may rejoin its parent branch, another branch or the main route), and every
 * other side platform belongs to a key room (a collectible detour proven both ways); nothing is a disconnected dead end. Branch traversal is proven by the
 * generator (fork proofs) and the solver suites. Route-length differences are printed for information only.
 */
public class DeadEndTest {
    /** The fork whose branch holds element {@code i}, or null when {@code i} is not on a branch. */
    static int[] forkOf(Course c, int i) { for (int[] f : c.forks) if (i >= f[1] && i < f[1] + f[2]) return f; return null; }

    /** Where a branch finally comes back onto the main route, following rejoins through parent branches (-1: never). */
    static int routeRejoin(Course c, int[] f) {
        for (int depth = 0; depth < 16 && f != null; depth++) { if (f[3] < c.routeSize()) return f[3]; f = forkOf(c, f[3]); }
        return -1;
    }

    /** The main-route platform a fork network leaves from, following take-offs back through parent branches (-1: none). */
    static int routeTakeOff(Course c, int[] f) {
        for (int depth = 0; depth < 16 && f != null; depth++) { if (f[0] < c.routeSize()) return f[0]; f = forkOf(c, f[0]); }
        return -1;
    }

    @Test public void forksFormAConnectedRouteNetworkWithNoDeadEnds() throws Exception {
        Tuning t = TestUtil.tuning();
        int slices = 0, forks = 0, sub = 0, upAndDown = 0, routeDown = 0, longer = 0, shorter = 0;
        for (long seed = 160; seed < 172; seed++) {
            Course prev = null;
            for (int k = 0; k < 5; k++) {
                Course c = CourseGenerator.chunk(seed, k, prev, t); prev = c; slices++;
                for (int[] f : c.forks) {
                    forks++; if (f[0] >= c.routeSize()) sub++;
                    assertTrue("a fork's branch lies off the route", f[1] >= c.routeSize() && f[2] >= 1);
                    int from = routeTakeOff(c, f), to = routeRejoin(c, f);
                    assertTrue("a fork leaves from the route network (take-off " + f[0] + ")", from >= 0);
                    assertTrue("a fork leads back into the route network further on (take-off " + f[0] + " -> route " + from + ", rejoin " + f[3] + " -> route " + to + ")", to > from);
                    boolean up = false, down = false; float py = c.get(f[0]).y;
                    for (int i = f[1]; i < f[1] + f[2]; i++) { Element d = c.get(i); if (d.y > py + 0.05f) up = true; if (d.y < py - 0.05f) down = true; py = d.y; }
                    if (up && down) upAndDown++;
                    if (f[0] < c.routeSize() && f[3] < c.routeSize()) {          // information only: alternate routes may be longer or shorter
                        double branch = 0, route = 0; Element p = c.get(f[0]);
                        for (int i = f[1]; i < f[1] + f[2]; i++) { branch += Math.hypot(c.dsWrap(p.s, c.get(i).s), c.get(i).y - p.y); p = c.get(i); }
                        branch += Math.hypot(c.dsWrap(p.s, c.get(f[3]).s), c.get(f[3]).y - p.y);
                        for (int i = f[0]; i < f[3]; i++) { Element u = c.get(i), v = c.get(c.nextRoute(i)); route += Math.hypot(c.dsWrap(u.s, v.s), v.y - u.y); }
                        if (branch > route) longer++; else shorter++;
                    }
                }
                for (int i = c.routeSize(); i < c.size(); i++) {
                    Element d = c.get(i); assertTrue("side platforms hang off a route element", d.anchor >= 0 && d.anchor < c.routeSize());
                    if (CourseGenerator.onForkBranch(c, i)) continue;
                    boolean keyRoom = false; for (Element h : c.hazards) if (h.type == Element.Type.KEY && h.anchor == d.anchor) keyRoom = true;
                    assertTrue("a side platform off every fork and key room is a disconnected dead end (#" + i + " " + d.type + ")", keyRoom);
                }
                for (int i = 1; i < c.routeSize(); i++) if (c.get(i).type == Element.Type.STATIC && c.get(i - 1).type == Element.Type.STATIC && c.get(i).y < c.get(i - 1).y - 0.35f) routeDown++;
            }
        }
        System.out.println("route network: " + forks + " forks (" + sub + " sub-forks) in " + slices + " slices; branch longer than the route " + longer + ", shorter or equal " + shorter + " (information only)");
        assertTrue("forks appear (" + forks + " in " + slices + " slices)", forks >= 10);
        assertTrue("fork branches step both up and down (" + upAndDown + ")", upAndDown >= 10);
        assertTrue("the route sometimes steps down before it climbs on (" + routeDown + ")", routeDown >= 5);
    }
}
