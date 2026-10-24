package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;

/**
 * Build 79 dev probe: drives the autopilot up a streaming tower exactly as StreamingReplayTest records it, and reports where (and why) the driver gives up.
 *   ./gradlew :core:probe -Pmain=com.hotatticgames.climbup.TowerStuckProbe -Pargs="<seed> <steps>"
 */
public class TowerStuckProbe {
    public static void main(String[] a) throws Exception {
        Tuning t = TestUtil.tuning(); long seed = Long.parseLong(a[0]); int steps = Integer.parseInt(a[1]);
        Tower b = new Tower(seed, t);
        Sim s = Sim.startOn(b.world, t, 0); s.keysFree = true; s.deferRespawn = true; s.floorOverride = b.floorLocal(); s.setRange(0, b.world.size() - 1);
        Autopilot.Driver d = new Autopilot.Driver(s); InputState in = new InputState(); int size = b.world.size(); double maxAbs = 0; int seen = b.rebuilds;
        int step = 0, lastOn = -2; java.util.ArrayDeque<String> trail = new java.util.ArrayDeque<>();
        for (; step < steps && !d.failed; step++) {
            if (step % 6 == 0) {
                while (b.topAbsY() < maxAbs + 150) b.extend();
                b.maintain(s);
                if (b.world.size() != size) { s.setRange(0, b.world.size() - 1); size = b.world.size(); }
                if (b.rebuilds != seen) { d.rebase(b.lastRemap); seen = b.rebuilds; }
            }
            d.drive(s, in); s.step(in); s.consumeEvents();
            maxAbs = Math.max(maxAbs, b.absY(s.y));
            if (s.onElem != lastOn) { lastOn = s.onElem; trail.add(String.format("[%d %s on=%d anchor=%d link=%d s=%.2f y=%.2f]", step, s.mode, s.onElem, s.onElem >= 0 ? b.world.get(s.onElem).anchor : -9, d.link(), s.s, b.absY(s.y))); if (trail.size() > 14) trail.removeFirst(); }
        }
        System.out.printf("TOWER seed %d: %d steps, climbed %.1f m, failed=%b link %d mode=%s on=%d s=%.2f y(abs)=%.2f falls=%d hits=%d%n",
                seed, step, maxAbs, d.failed, d.link(), s.mode, s.onElem, s.s, b.absY(s.y), s.falls, s.hits);
        for (String x : trail) System.out.println("  " + x);
        int f = d.link();
        Course c = b.world;
        for (int i = Math.max(0, f - 8); i <= Math.min(c.routeSize() - 1, f + 2); i++) { Element e = c.get(i); System.out.printf("  elem %d %s anchor %d s=%.2f y=%.2f w=%.1f%n", i, e.type, e.anchor, e.s, b.absY(e.y), e.w); }
        for (int i = c.routeSize(); i < c.size(); i++) { Element e = c.get(i); if (Math.abs(e.anchor - f) <= 3) System.out.printf("  decoy of %d %s s=%.2f y=%.2f w=%.1f%n", e.anchor, e.type, e.s, b.absY(e.y), e.w); }
        for (Element h : c.hazards) if (Math.abs(h.anchor - f) <= 2) System.out.printf("  hazard %s on %d s=%.2f%n", h.type, h.anchor, h.s);
    }
}
