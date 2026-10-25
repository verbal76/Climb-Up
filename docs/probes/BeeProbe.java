package com.hotatticgames.climbup.sim;
import java.nio.file.*;
/** Dev probe: BeeProbe seed slices. For every bee in the first slices of a NORMAL climb: climber standing still on each platform from anchor-1 to anchor+2,
 *  one bee period stepped; reports whether the bee shoves the climber, its largest per-step drift change (must stay <= BEE_DRIFT*react*DT), its furthest drift and whether its post moved. */
public class BeeProbe {
    public static void main(String[] a) throws Exception {
        long seed = Long.parseLong(a[0]); int slices = Integer.parseInt(a[1]);
        Tuning t = Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json"))));
        float stepMax = Element.BEE_DRIFT * t.react() * Sim.DT + 1e-5f; int bees = 0, badStep = 0, postMoved = 0;
        int[] shoved = new int[8], cases = new int[8]; float[] farSum = new float[8];
        Course prev = null;
        for (int k = 0; k < slices; k++) {
            Course c = CourseGenerator.chunk(seed, k, prev, t); prev = c;
            for (int hi = 0; hi < c.hazards.size(); hi++) {
                Element h = c.hazards.get(hi); if (h.type != Element.Type.BEE || h.anchor < 0) continue; bees++;
                float post = h.s;
                for (int cs = 0; cs < 8; cs++) {
                    int d = cs / 2 - 1; boolean farEnd = cs % 2 == 1;
                    int p = h.anchor + d; if (p < 0 || p >= c.routeSize() || !c.get(p).isPlatform() || c.get(p).anchor >= 0) continue;
                    cases[cs]++;
                    Sim s = Sim.startOn(c, t, p); float last = 0f, far = 0f; boolean hit = false;
                    if (farEnd) { Element P = c.get(p); float side = c.dsWrap(P.s, post) >= 0f ? 1f : -1f; s.s = c.wrap(P.s + side * Math.max(0f, P.halfW() - 0.4f)); }
                    for (int q = 0; q < (int) (h.period / Sim.DT) + 1; q++) {
                        InputState in = new InputState(); s.step(in);
                        float m = hi < s.mobS.length ? s.mobS[hi] : 0f;
                        if (h.beePresent(s.time) && Math.abs(m - last) > stepMax) badStep++;
                        last = h.beePresent(s.time) ? m : 0f; far = Math.max(far, Math.abs(m));
                        if ((s.events & Sim.EV_SHOVE) != 0) hit = true; s.events = 0;
                        if (s.mode != Sim.Mode.GROUND) break;
                    }
                    if (h.s != post) postMoved++;
                    if (hit) shoved[cs]++; farSum[cs] += far;
                }
            }
        }
        String[] nm = {"anchor-1 centre", "anchor-1 far end", "anchor centre", "anchor far end", "anchor+1 centre", "anchor+1 far end", "anchor+2 centre", "anchor+2 far end"};
        System.out.printf("bees %d | drift steps over BEE_DRIFT: %d | posts moved: %d%n", bees, badStep, postMoved);
        for (int q = 0; q < 8; q++) if (cases[q] > 0) System.out.printf("  climber on %-17s shoved %d/%d, mean furthest drift %.2f m%n", nm[q], shoved[q], cases[q], farSum[q] / cases[q]);
    }
}
