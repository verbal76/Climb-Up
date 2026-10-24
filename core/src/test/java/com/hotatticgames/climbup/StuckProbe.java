package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;

/**
 * Build 79 dev probe: where the solver stops on a generated slice, with the route elements and decoys around the failed link.
 *   ./gradlew :core:probe -Pmain=com.hotatticgames.climbup.StuckProbe -Pargs="<seed> <slice>"
 */
public class StuckProbe {
    public static void main(String[] a) throws Exception {
        Tuning t = TestUtil.tuning(); long seed = Long.parseLong(a[0]); int slice = Integer.parseInt(a[1]);
        Course c = null;
        if (a.length > 2 && a[2].equals("framed")) { CourseGenerator.Framed f = null; for (int k = 0; k <= slice; k++) f = CourseGenerator.chunkFramed(seed, k, f, t); c = f.c; }
        else for (int k = 0; k <= slice; k++) c = CourseGenerator.chunk(seed, k, c, t);
        Autopilot.Report r = Autopilot.run(c, t, 4000f);
        System.out.println("STUCK seed " + seed + " slice " + slice + ": completed=" + r.completed + " link " + r.failedLink + " " + r.failInfo);
        if (r.completed) return;
        int f = r.failedLink;
        for (int i = Math.max(0, f - 4); i <= Math.min(c.routeSize() - 1, f + 2); i++) { Element e = c.get(i); System.out.printf("  route %d %s skin %d s=%.2f y=%.2f w=%.1f%n", i, e.type, e.skin, e.s, e.y, e.w); }
        for (int i = c.routeSize(); i < c.size(); i++) { Element e = c.get(i); if (Math.abs(c.dsWrap(e.s, c.get(f).s)) < 12f && Math.abs(e.y - c.get(f).y) < 8f) System.out.printf("  decoy of %d %s s=%.2f y=%.2f w=%.1f%n", e.anchor, e.type, e.s, e.y, e.w); }
        for (Element h : c.hazards) if (Math.abs(h.anchor - f) <= 2) System.out.printf("  hazard %s on %d s=%.2f y=%.2f%n", h.type, h.anchor, h.s, h.y);
    }
}
