package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;

/** Dev probe (temporary): fingerprint of PUNISHER seed 2 slices 0-2, to show the NORMAL-only change leaves PUNISHER generation as it was. */
public class PunisherHashProbe {
    public static void main(String[] a) throws Exception {
        Tuning t = TestUtil.tuning().hardCopy(1);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        Course prev = null;
        for (int k = 0; k < 3; k++) { Course c = CourseGenerator.chunk(2, k, prev, t); prev = c; md.update(CourseIO.toJson(c).getBytes("UTF-8")); }
        StringBuilder sb = new StringBuilder(); for (byte b : md.digest()) sb.append(String.format("%02x", b));
        System.out.println("PUNISHER seed 2 slices 0-2 sha256 " + sb);
    }
}
