package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;

/** Build 79 dev probe: how far framed seed 21 generates before "cannot extend course". */
public class FramedExtendProbe {
    public static void main(String[] a) throws Exception {
        Tuning t = TestUtil.tuning(); CourseGenerator.Framed f = null; int k = 0; long seed = a.length > 0 ? Long.parseLong(a[0]) : 21;
        try { for (; k < 45; k++) f = CourseGenerator.chunkFramed(seed, k, f, t); System.out.println("EXTEND framed " + seed + " ok 45 slices"); }
        catch (Exception e) { System.out.println("EXTEND framed " + seed + " failed at slice " + k + ": " + e.getMessage().substring(0, Math.min(90, e.getMessage().length()))); }
    }
}
