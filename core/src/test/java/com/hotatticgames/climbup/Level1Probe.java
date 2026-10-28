package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;
import java.util.*;

/** Owner directive 2026-10-24: which enemies and hazards Level 1 (the first 500 m) holds, per mode, over a few seeds. Dev probe. */
public class Level1Probe {
    public static void main(String[] a) throws Exception {
        int mode = Integer.parseInt(a[0]);
        Tuning t0 = TestUtil.tuning(), t = mode == 0 ? t0 : t0.hardCopy(mode);
        for (int si = 1; si < a.length; si++) {
            long seed = Long.parseLong(a[si]); Course prev = null; int k = 0, forks = 0;
            TreeMap<String, Integer> m = new TreeMap<>();
            while (prev == null || prev.get(prev.routeSize() - 1).y < 500f) {
                Course c = CourseGenerator.chunk(seed, k++, prev, t); prev = c; forks += c.forks.size();
                for (Element h : c.hazards) if (h.anchor >= 0 && h.anchor < c.size() && c.get(h.anchor).y < 500f) m.merge(h.type.name(), 1, Integer::sum);
            }
            System.out.println("L1 " + Tuning.HARD_NAMES[mode] + " seed " + seed + " forks " + forks + " " + m);
        }
    }
}
