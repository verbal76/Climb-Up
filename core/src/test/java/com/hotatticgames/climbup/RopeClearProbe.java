package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;

/** Build 79 probe: platforms that sit across a rope's line or its top beam (owner screenshot, seed 22222-243N-ZJHT near 1016 m). */
public class RopeClearProbe {
    public static void main(String[] a) throws Exception {
        Tuning t = TestUtil.tuning();
        long seed = SeedCode.decode(a.length > 0 ? a[0] : "22222-243N-ZJHT");
        int slices = a.length > 1 ? Integer.parseInt(a[1]) : 30;
        Course prev = null;
        for (int k = 0; k < slices; k++) {
            Course c = CourseGenerator.chunk(seed, k, prev, t); prev = c;
            for (int i = 0; i < c.size(); i++) {
                Element r = c.get(i);
                if (r.type != Element.Type.ROPE) continue;
                for (int j = 0; j < c.size(); j++) {
                    Element p = c.get(j);
                    if (j == i || !p.isPlatform()) continue;
                    float dx = Math.abs(c.dsWrap(p.s, r.s));
                    if (dx < p.halfW() + 0.3f && p.y > r.y - r.len + 0.3f && p.y < r.y + 1.2f)
                        System.out.printf("slice %d rope %d (s=%.2f top=%.2f len=%.2f) crosses %s %d (s=%.2f y=%.2f w=%.2f) route=%d decoy=%b%n", k, i, r.s, r.y, r.len, p.type, j, p.s, p.y, p.w, c.routeSize(), j >= c.routeSize());
                }
            }
        }
    }
}
