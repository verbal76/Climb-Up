package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;

/**
 * Build 79 dev probe for CourseTest: route platforms that break the overall ascent (more than 3 m under the highest so far) in towers 1-6, and the
 * tower 3 links under the 0.04 m tolerance with the decoys near them.
 *   ./gradlew :core:probe -Pmain=com.hotatticgames.climbup.TowerShapeProbe
 */
public class TowerShapeProbe {
    public static void main(String[] a) throws Exception {
        Tuning t = TestUtil.tuning();
        for (int i = 1; i <= 6; i++) {
            Course c = TestUtil.tower(i); float prevTop = -1, top = -1;
            for (int k = 0; k < c.routeSize(); k++) {
                Element e = c.get(k);
                if (!e.isPlatform()) continue;
                if (!(e.y > prevTop - 0.01f || e.type == Element.Type.CRUMBLE)) {
                    System.out.printf("ASCENT tower %d route %d %s y=%.2f highest %.2f%n", i, k, e.type, e.y, top);
                    for (int q = Math.max(0, k - 5); q <= k; q++) System.out.printf("   %d %s y=%.2f%n", q, c.get(q).type, c.get(q).y);
                }
                prevTop = Math.max(prevTop, e.y - 3f); top = Math.max(top, e.y);
            }
        }
        Course c = TestUtil.tower(3);
        for (int i = 0; i < c.routeSize() - 1; i += 3) {
            Autopilot.Result r = Autopilot.plan(CourseGenerator.provenStart(c, t, i), i, true);
            if (r.ok && r.margin() >= 0.04f) continue;
            System.out.printf("MARGIN link %d %s->%s ok=%b margin %.3f%n", i, c.get(i).type, c.get(i + 1).type, r.ok, r.margin());
            for (int q = c.routeSize(); q < c.size(); q++) { Element d = c.get(q); if (Math.abs(c.dsWrap(d.s, c.get(i).s)) < 12f && Math.abs(d.y - c.get(i).y) < 6f) System.out.printf("   decoy of %d %s s=%.2f y=%.2f w=%.1f (link from s=%.2f y=%.2f)%n", d.anchor, d.type, d.s, d.y, d.w, c.get(i).s, c.get(i).y); }
            for (Element h : c.hazards) if (Math.abs(h.anchor - i) <= 1) System.out.printf("   hazard %s on %d%n", h.type, h.anchor);
            Course bare = new Course(c.seed, c.circumference); for (int q = 0; q < c.routeSize(); q++) bare.add(c.get(q)); bare.finishRoute();
            System.out.printf("   route only: margin %.3f%n", Autopilot.plan(Sim.startOn(bare, t, i), i, true).margin());
            for (int cut = i + 2; cut <= i + 5; cut++) { Course tr = new Course(c.seed, c.circumference); for (int q = 0; q < cut; q++) tr.add(c.get(q));
                System.out.printf("   route 0..%d, unfinished: margin %.3f%n", cut - 1, Autopilot.plan(Sim.startOn(tr, t, i), i, true).margin()); }
            Course nh = new Course(c.seed, c.circumference); for (int q = 0; q < c.size(); q++) nh.add(c.get(q)); nh.finishRoute();
            System.out.printf("   route + decoys, no hazards: margin %.3f%n", Autopilot.plan(Sim.startOn(nh, t, i), i, true).margin());
            Course rh = new Course(c.seed, c.circumference); for (int q = 0; q < c.routeSize(); q++) rh.add(c.get(q)); rh.finishRoute(); rh.hazards.addAll(c.hazards);
            System.out.printf("   route + hazards: margin %.3f%n", Autopilot.plan(Sim.startOn(rh, t, i), i, true).margin());
            for (Element.Type drop : new Element.Type[]{Element.Type.BEE, Element.Type.CRAB, Element.Type.SPIKE_BLOCK}) {
                Course x = new Course(c.seed, c.circumference); for (int q = 0; q < c.size(); q++) x.add(c.get(q)); x.finishRoute(); for (Element h : c.hazards) if (h.type != drop) x.hazards.add(h);
                System.out.printf("   without %s: margin %.3f%n", drop, Autopilot.plan(Sim.startOn(x, t, i), i, true).margin());
            }
            for (int q = i + 1; q <= i + 3; q++) { Element e = c.get(q); System.out.printf("   next %d %s skin %d s=%.2f y=%.2f w=%.1f amp %.2f len %.2f%n", q, e.type, e.skin, e.s, e.y, e.w, e.amp, e.len); }
            Element e0 = c.get(i), e1 = c.get(i + 1); System.out.printf("   %s skin %d s=%.2f y=%.2f w=%.1f amp %.2f period %.2f -> s=%.2f y=%.2f w=%.1f%n", e0.type, e0.skin, e0.s, e0.y, e0.w, e0.amp, e0.period, e1.s, e1.y, e1.w);
        }
    }
}
