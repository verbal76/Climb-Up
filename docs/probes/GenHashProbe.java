package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;

/** Dev probe (temporary): SHA-256 of a mode's first slices for one seed (byte-identity check of refactors). GenHashProbe <hard> <seed> <slices> */
public class GenHashProbe {
    public static void main(String[] a) throws Exception {
        int hard = Integer.parseInt(a[0]); long seed = Long.parseLong(a[1]); int n = Integer.parseInt(a[2]);
        Tuning t = hard == 0 ? TestUtil.tuning() : TestUtil.tuning().hardCopy(hard);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        Course prev = null;
        for (int k = 0; k < n; k++) { Course c = CourseGenerator.chunk(seed, k, prev, t); prev = c; md.update(CourseIO.toJson(c).getBytes("UTF-8")); }
        StringBuilder sb = new StringBuilder(); for (byte b : md.digest()) sb.append(String.format("%02x", b));
        System.out.println("HASH hard " + hard + " seed " + seed + " slices " + n + " " + sb);
    }
}
