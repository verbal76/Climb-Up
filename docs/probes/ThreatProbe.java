package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;
import java.util.Locale;

/** Dev probe (temporary): the NORMAL threat budget on seeds 34 and 6, the two Build 80 diagnostics. */
public class ThreatProbe {
    static float level(float y) { return Math.min(1f, y / 5000f); }
    public static void main(String[] a) throws Exception {
        Tuning t = TestUtil.tuning();
        String which = a.length > 0 ? a[0] : "34";
        if (which.equals("34")) {
            Course prev = null;
            for (int k = 0; k < 10; k++) {
                Course c = CourseGenerator.chunk(34, k, prev, t); prev = c;
                int over = 0; float worst = 0f; String worstAt = "";
                for (int i = 0; i < c.routeSize() - 1; i++) {
                    float th = EncounterModel.threat(c, t, i), b = EncounterModel.threatBudget(level(c.get(i).y), EncounterModel.cramped(c, i));
                    if (th > b) over++;
                    if (th / b > worst) { worst = th / b; worstAt = String.format(Locale.ROOT, "link %d y=%.1f threat %.2f budget %.2f", i, c.get(i).y, th, b); }
                }
                Autopilot.Report r = Autopilot.run(c, t, 4000f);
                System.out.printf(Locale.ROOT, "seed 34 slice %d y %.1f..%.1f solver completed=%b%s | links over budget %d, worst %s | forks %d%n", k, c.get(0).y, c.get(c.routeSize() - 1).y, r.completed,
                    r.completed ? "" : " (link " + r.failedLink + " " + r.failInfo + ")", over, worstAt, c.forks.size());
                if (k == 8) for (int i = 0; i < Math.min(8, c.routeSize()); i++) {
                    StringBuilder hs = new StringBuilder(); for (Element h : c.hazards) if (h.anchor == i) hs.append(h.type).append(' ');
                    System.out.printf(Locale.ROOT, "   route %d %s y=%.1f w=%.1f threat %.2f | %s%n", i, c.get(i).type, c.get(i).y, c.get(i).w, EncounterModel.threat(c, t, i), hs);
                }
            }
        } else {
            java.util.List<Course> sl = TestUtil.slices(6, 1100f);
            for (int k = 0; k < sl.size(); k++) {
                Course c = sl.get(k);
                Autopilot.Report r = Autopilot.run(c, t, 4000f);
                int over = 0; for (int i = 0; i < c.routeSize() - 1; i++) if (EncounterModel.threat(c, t, i) > EncounterModel.threatBudget(level(c.get(i).y), EncounterModel.cramped(c, i))) over++;
                StringBuilder rooms = new StringBuilder();
                for (int[] kr : c.keyRooms) rooms.append(String.format(Locale.ROOT, " keyroom@%d y=%.1f takeoff threat %.2f;", kr[0], c.get(kr[0]).y, EncounterModel.threatAt(c, t, kr[0])));
                System.out.printf(Locale.ROOT, "seed 6 slice %d y %.1f..%.1f solver completed=%b%s | links over budget %d |%s%n", k, c.get(0).y, c.get(c.routeSize() - 1).y, r.completed,
                    r.completed ? "" : " (link " + r.failedLink + " " + r.failInfo + ")", over, rooms);
            }
        }
    }
}
