package com.hotatticgames.climbup.sim;

import java.nio.file.*;
import java.util.Random;
import org.junit.Test;

/** Scratch probe (not a fixture): the Tarzan module's own geometry, many draws; which half the planner fails - the catch or the ride across. */
public class TarzanModuleProbe {
    static float r(Random q, float a, float b) { return a + q.nextFloat() * (b - a); }
    static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    @Test public void map() throws Exception {
        org.junit.Assume.assumeTrue(System.getenv("CLIMB_PROBE") != null);
        Tuning T = Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json"))));
        for (float d : new float[]{0.2f, 0.5f, 0.8f}) {
            int n = 40, catchOk = 0, rideOk = 0, both = 0, lo1 = 0, lo2 = 0; float m1 = 0, m2 = 0; StringBuilder fails = new StringBuilder();
            Random q = new Random(42);
            for (int k = 0; k < n; k++) {
                float eR = 1.5f, y0 = 0f;
                float amp = r(q, 0.55f, 0.75f), len = r(q, 4.5f, 6f), period = r(q, 2.4f, 3.0f);
                float sc = eR + lerp(1.2f, 2.0f, d), yc = y0 + T.handHeight + r(q, 0.6f, 1.0f);
                float sp = sc + len * (float) StrictMath.sin(amp), yp = yc + len * (float) StrictMath.cos(amp);
                Element tz = new Element(Element.Type.TARZAN, sp, yp, 1f); tz.len = len; tz.amp = amp; tz.period = period; tz.phase = r(q, 0f, 6.28f);
                float vx = len * amp * 2f * (float) Math.PI / period, vy = T.jumpVel * Sim.TARZAN_RELEASE_UP;
                float yb = yp - len - T.handHeight, yl = y0 + r(q, -0.5f, 1.6f);
                float tf = (vy + (float) Math.sqrt(Math.max(0f, vy * vy + 2f * T.gravity * (yb - yl)))) / T.gravity;
                float clear = sp + CourseGenerator.arcHalf(tz) + 0.4f, wB = 3f;
                float land = Math.max(clear + wB / 2f, sp + vx * tf * lerp(0.6f, 0.85f, d) + 0.5f);
                Course c = new Course(1, T.circumference());
                c.add(new Element(Element.Type.STATIC, 0f, y0, 3f)); c.add(tz); c.add(new Element(Element.Type.STATIC, land, yl, wB));
                Sim m = Sim.startOn(c, T, 0);
                Autopilot.Result r1 = Autopilot.plan(m, 0, true, false);
                boolean ok1 = r1.ok, ok2 = false;
                if (ok1) {
                    Sim s = m.copy(); s.humanHold = Autopilot.HUMAN_HOLD; Autopilot.Policy p = Autopilot.make(r1.factory); InputState in = new InputState();
                    for (int i = 0; i < 1200 && s.mode != Sim.Mode.TARZAN; i++) { in.clear(); p.act(s, in); s.step(in); }
                    if (s.mode == Sim.Mode.TARZAN) { Autopilot.Result r2 = Autopilot.plan(s, 1, true, false); ok2 = r2.ok; m2 += r2.margin(); if (r2.margin() < 0.07f) lo2++; }
                } else {          // the ride alone, from a catch placed on the rope
                    Sim s = Sim.startOn(c, T, 0); s.attachTarzan(1, len * 0.75f);
                    ok2 = Autopilot.plan(s, 1, false, false).ok;
                }
                if (ok1) { catchOk++; m1 += r1.margin(); if (r1.margin() < 0.07f) lo1++; } if (ok2) rideOk++; if (ok1 && ok2) both++;
                if (!(ok1 && ok2) && fails.length() < 900) fails.append(String.format("[%s%s amp=%.2f len=%.1f per=%.1f gapC=%.1f land=%.1f dy=%.1f] ", ok1 ? "" : "CATCH ", ok2 ? "" : "RIDE", amp, len, period, sc - eR, land - sp, yl));
            }
            System.out.printf("d=%.1f: catch %d/%d (mean margin %.3f, %d under 0.07), ride %d/%d (mean margin %.3f, %d under 0.07)%n", d, catchOk, n, m1 / Math.max(1, catchOk), lo1, rideOk, n, m2 / Math.max(1, rideOk), lo2);
        }
    }
}
