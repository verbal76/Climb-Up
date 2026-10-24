package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;

/**
 * Build 79 dev probe for SawFairnessTest: every gap saw whose timing window is under 0.5 of its cycle, with what else is on that link, and the window again
 * with only the saw left on the link (so a narrow window can be put down to the saw itself or to a creature sharing the link).
 *   ./gradlew :core:probe -Pmain=com.hotatticgames.climbup.SawWindowProbe -Pargs="<slices>"
 */
public class SawWindowProbe {
    public static void main(String[] a) throws Exception {
        Tuning t = TestUtil.tuning(); int slices = a.length > 0 ? Integer.parseInt(a[0]) : 26;
        for (long seed : new long[]{1, 2, 3, 4, 5}) {
            Course prev = null;
            for (int k = 0; k < slices; k++) {
                Course c = CourseGenerator.chunk(seed, k, prev, t); prev = c;
                for (int hi = 0; hi < c.hazards.size(); hi++) {
                    Element h = c.hazards.get(hi);
                    if (h.type != Element.Type.SAW_H || h.anchor < 0 || h.anchor >= c.routeSize() - 1) continue;
                    Element p = c.get(h.anchor);
                    if (p.w >= 7f && Math.abs(h.y - p.y - 0.62f) < 0.01f) continue;          // a floor saw
                    Autopilot.Result r = Autopilot.plan(Sim.startOn(c, t, h.anchor), h.anchor, true);
                    if (r.window >= 0.5f) continue;
                    StringBuilder near = new StringBuilder();
                    for (Element o : c.hazards) if (o != h && Math.abs(o.anchor - h.anchor) <= 1) near.append(o.type).append('@').append(o.anchor).append(' ');
                    Course solo = new Course(c.seed, c.circumference);
                    for (int i = 0; i < c.routeSize(); i++) solo.add(c.get(i));
                    solo.hazards.add(h);
                    float alone = Autopilot.plan(Sim.startOn(solo, t, h.anchor), h.anchor, true).window;
                    System.out.printf("SAW seed %d slice %d anchor %d y=%.1f window %.3f alone %.3f period %.2f amp %.2f | near: %s%n", seed, k, h.anchor, h.y, r.window, alone, h.period, h.amp, near);
                }
            }
        }
    }
}
