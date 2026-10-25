package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;
import java.util.*;

/**
 * Dev probe (temporary, never committed): generation variety, threat, retries and solver completion for a few seeds, written only against API that
 * Build 79, Build 80 and the threat repair share (newer fields read by reflection), so the three versions can be compared on the same seeds.
 *   VarietyProbe <hard 0|1> <top metres> <solver 0|1> <seed>...
 */
public class VarietyProbe {
    static int roam(Element h) { switch (h.type) { case CRAB: return Sim.CRAB_FOLLOW; case SKULL: case BLOB: return 1; case BEE: return 0; default: return -1; } }
    static float wt(Element.Type k) { switch (k) { case CRAB: return 1f; case SKULL: return 1.2f; case BLOB: return 1f; case BEE: return 0.8f; default: return 0f; } }
    static boolean reaches(Course c, Element h, int p) {
        if (h.anchor < 0 || p < 0 || p >= c.routeSize()) return false;
        Element e = c.get(p);
        switch (h.type) {
            case CRAB: return Math.abs(h.anchor - p) <= Sim.CRAB_FOLLOW;
            case SKULL: case BLOB: return Math.abs(h.anchor - p) <= 1;
            case BEE: { float d = Math.abs(c.dsWrap(e.s, h.s)) - e.halfW(), dy = e.y - h.y; return h.anchor == p || (d <= 7f && dy > -4f && dy < 3f); }
            default: return false;
        }
    }
    static float threat(Course c, int i) {
        int j = i + 1; float raw = 0f; int n = 0;
        for (Element h : c.hazards) if (roam(h) >= 0 && (reaches(c, h, i) || reaches(c, h, j))) { raw += wt(h.type); n++; }
        return n == 0 ? 0f : raw * (1f + 0.25f * (n - 1));
    }
    static boolean cramped(Course c, int i) {
        Element a = c.get(i);
        if (a.isMoving() || (a.isPlatform() && a.w < 2.6f)) return true;
        for (Element h : c.hazards) if (h.anchor == i && (h.type == Element.Type.SPIKE_BLOCK || h.type == Element.Type.SPIKE_TRAP || h.type == Element.Type.SPIKE_DROP)) return true;
        return false;
    }
    static Object field(Object o, String f) { try { java.lang.reflect.Field x = o.getClass().getField(f); return x.get(o); } catch (Exception e) { return null; } }

    public static void main(String[] a) throws Exception {
        int hard = Integer.parseInt(a[0]); float top = Float.parseFloat(a[1]); boolean solve = a[2].equals("1");
        Tuning t = hard == 0 ? TestUtil.tuning() : TestUtil.tuning().hardCopy(hard);
        String[] counters = {"rejected", "failConflict", "failThreat", "failPlan", "failLayer", "failHaz", "failWindow", "roomFailed", "roomOk"};
        for (int si = 3; si < a.length; si++) {
            long seed = Long.parseLong(a[si]);
            TreeMap<String, Integer> mech = new TreeMap<>(); int[] bandThreat = new int[4]; // threat buckets: 0, (0,2.5], (2.5,5], >5
            int overBudget = 0, links = 0, forks = 0, slices = 0, solved = 0; long genMs = 0; long[] cnt = new long[counters.length];
            Set<Integer> roomModes = new TreeSet<>(); List<String> fails = new ArrayList<>(); float maxThreat = 0f; String maxAt = "";
            Course prev = null;
            while (prev == null || prev.get(prev.routeSize() - 1).y < top) {
                long t0 = System.nanoTime();
                Course c = CourseGenerator.chunk(seed, slices, prev, t); genMs += (System.nanoTime() - t0) / 1_000_000;
                Object g = field(null, "x"); try { g = CourseGenerator.class.getField("lastForDebug").get(null); } catch (Exception e) { }
                for (int q = 0; q < counters.length; q++) { Object v = g == null ? null : field(g, counters[q]); if (v instanceof Integer) cnt[q] += (Integer) v; }
                Object fk = field(c, "forks"); if (fk instanceof List) forks += ((List<?>) fk).size();
                for (int[] kr : c.keyRooms) roomModes.add(kr[3]);
                for (int i = 0; i < c.routeSize(); i++) {
                    Element e = c.get(i); int L = (int) (e.y / 500f) + 1;
                    String key = "p:" + e.type + (e.type == Element.Type.RAMP || e.type == Element.Type.SEESAW ? "" + e.skin : "") + (e.belt() ? "(belt)" : "");
                    mech.merge("L" + L + " " + key, 1, Integer::sum);
                    if (i < c.routeSize() - 1) {
                        float th = threat(c, i), lv = Math.min(1f, e.y / 5000f), b = (5f + 6f * lv) * (cramped(c, i) ? 0.75f : 1f);
                        links++; if (th > b) overBudget++;
                        bandThreat[th == 0f ? 0 : th <= 2.5f ? 1 : th <= 5f ? 2 : 3]++;
                        if (th > maxThreat) { maxThreat = th; maxAt = String.format(Locale.ROOT, "%.1f m", e.y); }
                    }
                }
                for (Element h : c.hazards) if (h.anchor >= 0 && h.anchor < c.size() && h.type != Element.Type.KEY && h.type != Element.Type.GATE) mech.merge("L" + ((int) (c.get(h.anchor).y / 500f) + 1) + " h:" + h.type, 1, Integer::sum);
                if (solve) {
                    Autopilot.Report r = Autopilot.run(c, t, 4000f);
                    if (r.completed) solved++;
                    else {
                        int fl = Math.max(0, r.failedLink); Element A = c.get(fl), B = c.get(Math.min(c.size() - 1, c.nextRoute(fl)));
                        float gap = Math.abs(c.dsWrap(B.s, A.s)) - A.halfW() - B.halfW();
                        StringBuilder hz = new StringBuilder(); for (Element h : c.hazards) if (h.anchor >= fl - 3 && h.anchor <= fl + 2 && h.type != Element.Type.KEY) hz.append(h.type).append('@').append(h.anchor - fl).append(' ');
                        fails.add(String.format(Locale.ROOT, "slice %d link %d y=%.1f (castle/level %d) %s w%.1f -> %s w%.1f gap %.2f rise %.2f threat %.2f | %s | near: %s", slices, fl, A.y, (int) (A.y / 500f) + 1, A.type, A.w, B.type, B.w, gap, B.y - A.y, threat(c, fl), r.failInfo, hz));
                    }
                }
                prev = c; slices++;
            }
            StringBuilder cs = new StringBuilder(); for (int q = 0; q < counters.length; q++) cs.append(counters[q]).append('=').append(cnt[q]).append(' ');
            System.out.printf(Locale.ROOT, "SEED %d hard %d to %.0f m: slices %d gen %.1f s (%.2f s/slice) | %s| forks %d | key-room modes %s%n", seed, hard, top, slices, genMs / 1000f, genMs / 1000f / slices, cs, forks, roomModes);
            System.out.printf(Locale.ROOT, "  links %d threat 0:%d (0,2.5]:%d (2.5,5]:%d >5:%d, max %.2f at %s, over the NORMAL budget %d%n", links, bandThreat[0], bandThreat[1], bandThreat[2], bandThreat[3], maxThreat, maxAt, overBudget);
            if (solve) System.out.printf(Locale.ROOT, "  solver completed %d of %d slices%n", solved, slices);
            for (String f : fails) System.out.println("  FAIL " + f);
            StringBuilder ms = new StringBuilder(); for (Map.Entry<String, Integer> m : mech.entrySet()) if (!m.getKey().contains("p:STATIC") ) ms.append(m.getKey()).append('=').append(m.getValue()).append(", ");
            System.out.println("  MECH " + ms);
        }
    }
}
