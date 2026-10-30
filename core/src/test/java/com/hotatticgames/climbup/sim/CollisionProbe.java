package com.hotatticgames.climbup.sim;

import java.nio.file.*;
import org.junit.Test;

/** Scratch probe (not a fixture): maps which gap widths a cannon firing from behind turns into knock-assisted or rebound-assisted links. */
public class CollisionProbe {
    @Test public void map() throws Exception {
        org.junit.Assume.assumeTrue(System.getenv("CLIMB_PROBE") != null);
        Tuning t = Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json"))));
        for (float gap : new float[]{5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f}) for (float cy : new float[]{0.6f, 1.2f, 2.0f}) {
            Course c = new Course(1, t.circumference());
            c.add(new Element(Element.Type.STATIC, 0f, 0f, 4f));
            c.add(new Element(Element.Type.STATIC, 2f + gap + 2f, 0f, 4f));
            Element cn = new Element(Element.Type.CANNON, -8f, cy, 1f); cn.dir = 1; cn.len = 30f; cn.period = 1.6f; cn.anchor = 0; c.hazards.add(cn);
            Sim m = Sim.startOn(c, t, 0);
            long t0 = System.nanoTime();
            Autopilot.Result r = Autopilot.plan(m, 0, true, false);
            System.out.printf("gap=%.0f cy=%.1f ok=%b viaHit=%b viaRebound=%b clean=%d hitOk=%d rbTrials=%d rbOk=%d trials=%d %.0f ms%n",
                gap, cy, r.ok, r.viaHit, r.viaRebound, r.successes, r.hitSuccesses, r.reboundTrials, r.reboundSuccesses, r.trials, (System.nanoTime() - t0) / 1e6);
        }
    }
}
