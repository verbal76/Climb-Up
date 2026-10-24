package com.hotatticgames.climbup.sim;

import java.nio.file.*;

/**
 * Build 79 dev probe: is the always-running balance conveyor crossable, and does the planner find the way? A take-off, a balance conveyor laid out as
 * CourseGenerator lays it (SEESAW skin 4, 7-8 m, belt 2.2-3.0 m/s) and the block after it; for each layout it prints the planner's verdict on both links
 * (as CourseGenerator.commit proves them: the take-off from Sim.startOn, the crossing from where that proven jump lands) and a brute-force count of plain human inputs that cross the whole thing from the take-off:
 * the stick held right throughout, one jump onto the belt and one jump off it, each at any step (2-step grid), with the 100 ms stick hold.
 *   ./gradlew :core:probe -Pmain=com.hotatticgames.climbup.sim.BeltProbe -Pargs="<layouts>"
 */
public class BeltProbe {
    public static void main(String[] a) throws Exception {
        Tuning t = Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); int layouts = a.length > 0 ? Integer.parseInt(a[0]) : 12;
        java.util.Random rnd = new java.util.Random(7);
        for (int L = 0; L < layouts; L++) {
            float d = L / (float) Math.max(1, layouts - 1), w = 7f + rnd.nextInt(2), dyIn = -0.4f + rnd.nextFloat() * 0.7f;
            float gapIn = 2.6f * (0.5f + 0.35f * d), gapOut = (0.5f + 0.8f * d) * (0.9f + 0.2f * rnd.nextFloat()), dyOut = -0.5f + 1.1f * rnd.nextFloat();
            Course c = new Course(0, t.circumference());
            Element A = new Element(Element.Type.STATIC, 10f, 0f, 3f); A.checkpoint = true; c.add(A);
            Element B = new Element(Element.Type.SEESAW, 11.5f + gapIn + w / 2f, dyIn, w); B.skin = 4; B.phase = (2.2f + 0.8f * d) * (0.95f + 0.1f * rnd.nextFloat()); c.add(B);
            Element N = new Element(Element.Type.STATIC, B.s + w / 2f + gapOut + 1.5f, B.y + dyOut, 3f); N.checkpoint = true; c.add(N);
            Element M = new Element(Element.Type.STATIC, N.s + 1.5f + 2.0f + 1.5f, N.y + 0.5f, 3f); c.add(M);
            Autopilot.Result in = Autopilot.plan(Sim.startOn(c, t, 0), 0, true), out;
            { Sim land = in.factory == null ? null : Autopilot.trialEnd(Sim.startOn(c, t, 0), 0, in.factory, 10.5f); out = Autopilot.plan(land != null && land.onElem == 1 ? land : Sim.startOn(c, t, 1), 1, true); }
            int tried = 0, crossed = 0;
            for (int j1 = 0; j1 < 90; j1 += 2) for (int j2 = j1 + 10; j2 < j1 + 200; j2 += 2) {
                tried++;
                Sim s = Sim.startOn(c, t, 0); s.humanHold = 6; InputState inp = new InputState(); boolean onBelt = false;
                for (int k = 0; k < j1 + 320; k++) {
                    inp.clear(); inp.moveX = 1f;
                    if (k == j1 || k == j2) { inp.jumpPressed = true; }
                    if ((k >= j1 && k < j1 + 14) || (k >= j2 && k < j2 + 14)) inp.jumpHeld = true;
                    s.step(inp); s.consumeEvents();
                    if (s.mode == Sim.Mode.GROUND && s.onElem == 1) onBelt = true;
                    if (s.mode == Sim.Mode.GROUND && s.onElem >= 2) break;
                    if (s.y < Math.min(A.y, B.y) - 3f) break;
                }
                if (onBelt && s.mode == Sim.Mode.GROUND && s.onElem >= 2) crossed++;
            }
            System.out.printf("BELT layout %d d=%.2f w=%.0f belt=%.2f m/s gapIn=%.2f gapOut=%.2f dyOut=%.2f | plan in ok=%b win=%.2f margin=%.3f | plan out ok=%b win=%.2f margin=%.3f | brute force %d/%d%n",
                    L, d, w, B.phase, gapIn, gapOut, dyOut, in.ok, in.window, in.margin(), out.ok, out.window, out.margin(), crossed, tried);
        }
    }
}
