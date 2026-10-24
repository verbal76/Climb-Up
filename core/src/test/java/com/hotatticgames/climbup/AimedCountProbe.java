package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;

/** Build 79 dev probe: aimed cannons (and side cannons) in AimedCannonTest's slices (compressed worlds, seeds 190-193, 11 slices each). */
public class AimedCountProbe {
    public static void main(String[] a) throws Exception {
        Tuning t = TestUtil.tuning(); t.zoneHeight = 50f; int aimed = 0, side = 0;
        for (long seed = 190; seed < 194; seed++) { Course prev = null; for (int k = 0; k < 11; k++) { prev = CourseGenerator.chunk(seed, k, prev, t); for (Element h : prev.hazards) { if (h.type == Element.Type.AIMED) aimed++; if (h.type == Element.Type.CANNON) side++; } } }
        System.out.println("AIMED " + aimed + " aimed cannons, " + side + " side cannons" + (Boolean.getBoolean("aimedX") ? " (aimed cannon not re-checking the link before)" : ""));
    }
}
