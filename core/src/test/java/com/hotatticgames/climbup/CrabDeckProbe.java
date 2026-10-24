package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;

/**
 * Build 79 dev probe: a crab deck laid out as CourseGenerator.buildMid lays it (a 3 m take-off, a gap, a deck 9 + 2 (n - 2) m long with n crabs each on its own
 * stretch, a 3 m landing after it), then each link proved the way CourseGenerator.commit proves a route link. Prints how many layouts pass.
 *   ./gradlew :core:probe -Pmain=com.hotatticgames.climbup.CrabDeckProbe -Pargs="<hard> <layouts>"
 */
public class CrabDeckProbe {
    public static void main(String[] a) throws Exception {
        int hard = a.length > 0 ? Integer.parseInt(a[0]) : 0, layouts = a.length > 1 ? Integer.parseInt(a[1]) : 20;
        Tuning t = TestUtil.tuning().hardCopy(hard);
        java.util.Random rnd = new java.util.Random(5);
        for (int crabs = 2 + hard; crabs <= 4 + hard; crabs++) {
            int in = 0, out = 0, both = 0;
            for (int L = 0; L < layouts; L++) {
                float w = 9f + 2f * (crabs - 2), gap = 2.0f + rnd.nextFloat() * 0.8f;
                Course c = new Course(0, t.circumference());
                Element A = new Element(Element.Type.STATIC, 10f, 0f, 3f); A.checkpoint = true; c.add(A);
                Element D = new Element(Element.Type.STATIC, 11.5f + gap + w / 2f, 0.3f, w); c.add(D);
                Element N = new Element(Element.Type.STATIC, D.s + w / 2f + 2.0f + 1.5f, 0.5f, 3f); N.checkpoint = true; c.add(N);
                Element M = new Element(Element.Type.STATIC, N.s + 1.5f + 2.0f + 1.5f, 0.6f, 3f); c.add(M);
                float amp = Math.max(0.6f, Math.min(1.2f, (w - 2f * 1.5f - 0.3f * (crabs - 1)) / (2f * crabs)));
                float span = w - 2f * (1.5f + amp), step = span / (crabs - 1);
                for (int k = 0; k < crabs; k++) {
                    Element cr = new Element(Element.Type.CRAB, D.s - span / 2f + k * step, D.y, 0f);
                    cr.amp = amp; cr.period = 3.0f + rnd.nextFloat() * 1.2f - 0.4f; cr.phase = rnd.nextFloat() * 6.28f; cr.anchor = 1; c.hazards.add(cr);
                }
                boolean okIn = pair(c, t, 0, 1), okOut = pair(c, t, 1, 2);
                if (okIn) in++; if (okOut) out++; if (okIn && okOut) both++;
                if (L < 3 && !(okIn && okOut)) System.out.println("  crabs " + crabs + " layout " + L + ": in=" + okIn + " out=" + okOut + " (" + why + ")");
            }
            System.out.println("CRABDECK hard=" + hard + " crabs=" + crabs + " in " + in + "/" + layouts + " out " + out + "/" + layouts + " both " + both + "/" + layouts);
        }
    }
    static String why = "";
    /** As CourseGenerator.commit proves a link: the planner from where a climber stands on element a (Sim.startOn: the safe pocket at the back of a hazard deck),
     *  with a timing window of at least minTimingWindow, and from 0.5 m behind and 0.4 m ahead of that spot as well. */
    static boolean pair(Course c, Tuning t, int a, int b) {
        Autopilot.Result r = Autopilot.plan(Sim.startOn(c, t, a), a, true);
        why = String.format("plan ok=%b window=%.2f", r.ok, r.window);
        if (!r.ok || r.window < t.minTimingWindow - 0.06f) return false;
        for (float dx : new float[]{-0.5f, 0.4f}) { Sim m = Sim.startOn(c, t, a); m.s = c.wrap(m.s + dx); if (!Autopilot.plan(m, a, false).ok) { why += " offset " + dx + " fails"; return false; } }
        return true;
    }
}
