package com.hotatticgames.climbup.sim;

import java.nio.file.*;

/**
 * Build 79 dev probe for RampVarietiesTest: spinning ramps (RAMP skin 3) laid out as CourseGenerator lays them - a take-off, the ramp from its edge, then the
 * flush block or the ski-jump block - and each link proved as CourseGenerator.commit proves it (Sim.startOn, margin, timing window). Prints how many pass,
 * so a build where spinning ramps never survive generation can be told from one where they simply were not rolled.
 *   ./gradlew :core:probe -Pmain=com.hotatticgames.climbup.sim.RampProbe -Pargs="<layouts>"
 */
public class RampProbe {
    public static void main(String[] a) throws Exception {
        Tuning t = Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json"))));
        int layouts = a.length > 0 ? Integer.parseInt(a[0]) : 24;
        java.util.Random rnd = new java.util.Random(11);
        int pass = 0, inOk = 0, outOk = 0;
        for (int L = 0; L < layouts; L++) {
            boolean ski = L % 2 == 1;
            float sc = 1.3f + rnd.nextFloat() * 0.45f, len = 2.2f * sc, slope = CourseGenerator.RAMP_SLOPE, rise = slope * len, y0 = 600f;
            Course c = new Course(0, t.circumference());
            Element A = new Element(Element.Type.STATIC, 10f, y0, 3f); A.checkpoint = true; A.zone = 1; c.add(A);
            Element R = new Element(Element.Type.RAMP, 11.5f + len / 2f, y0 + rise / 2f, len); R.amp = slope; R.skin = 3; R.len = sc; R.zone = 1;
            R.period = 2.2f + rnd.nextFloat() * 0.8f; R.phase = rnd.nextFloat() * 6.28f; c.add(R);
            float wB = 3f + rnd.nextInt(2);
            Element B = ski ? new Element(Element.Type.STATIC, R.s + len / 2f + 2.0f + wB / 2f, y0 + rise + 0.3f, wB) : new Element(Element.Type.STATIC, R.s + len / 2f + wB / 2f, y0 + rise, wB);
            B.zone = 1; c.add(B);
            Element N = new Element(Element.Type.STATIC, B.s + wB / 2f + 2.0f + 1.5f, B.y + 0.4f, 3f); N.checkpoint = true; N.zone = 1; c.add(N);
            Autopilot.Result r0 = Autopilot.plan(Sim.startOn(c, t, 0), 0, true), r1 = Autopilot.plan(Sim.startOn(c, t, 1), 1, true);
            boolean ok0 = r0.ok && r0.margin() >= 0.07f, ok1 = r1.ok && r1.margin() >= 0.07f && r1.window >= t.minTimingWindow - 0.06f;
            if (ok0) inOk++; if (ok1) outOk++; if (ok0 && ok1) pass++;
            System.out.printf("RAMP %d %s period %.2f | onto ok=%b margin %.3f | off ok=%b margin %.3f window %.2f%n", L, ski ? "ski" : "flush", R.period, r0.ok, r0.margin(), r1.ok, r1.margin(), r1.window);
        }
        System.out.println("RAMPS spinning: onto " + inOk + "/" + layouts + ", off " + outOk + "/" + layouts + ", both " + pass + "/" + layouts);
    }
}
