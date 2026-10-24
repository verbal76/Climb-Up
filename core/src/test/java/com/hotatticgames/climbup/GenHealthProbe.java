package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;

/**
 * Build 79 dev probe: does generation still extend everywhere CI grows courses (seeds 1-3 and 7 to 5,300 m, framed seeds 5 and 21), and do aimed
 * cannons still appear (AimedCannonTest's compressed worlds, seeds 190-193)?
 *   ./gradlew :core:probe -Pmain=com.hotatticgames.climbup.GenHealthProbe
 */
public class GenHealthProbe {
    public static void main(String[] a) throws Exception {
        Tuning t = TestUtil.tuning();
        for (long seed : new long[]{1, 2, 3, 7}) {
            Course prev = null; int k = 0;
            try { while (prev == null || prev.get(prev.routeSize() - 1).y < 5300f) { prev = CourseGenerator.chunk(seed, k, prev, t); k++; } System.out.println("HEALTH seed " + seed + " ok to " + (int) prev.get(prev.routeSize() - 1).y + " m in " + k + " slices"); }
            catch (Exception e) { System.out.println("HEALTH seed " + seed + " FAILED at slice " + k + ": " + e.getMessage()); }
        }
        for (long seed : new long[]{5, 21}) {
            CourseGenerator.Framed f = null; int k = 0;
            try { for (; k < 45; k++) f = CourseGenerator.chunkFramed(seed, k, f, t); System.out.println("HEALTH framed " + seed + " ok, 45 slices"); }
            catch (Exception e) { System.out.println("HEALTH framed " + seed + " FAILED at slice " + k + ": " + e.getMessage()); }
        }
        Tuning z = TestUtil.tuning(); z.zoneHeight = 50f; int aimed = 0;
        for (long seed = 190; seed < 194; seed++) { Course prev = null; for (int k = 0; k < 11; k++) { prev = CourseGenerator.chunk(seed, k, prev, z); for (Element h : prev.hazards) if (h.type == Element.Type.AIMED) aimed++; } }
        System.out.println("HEALTH aimed cannons in AimedCannonTest's slices: " + aimed);
    }
}
