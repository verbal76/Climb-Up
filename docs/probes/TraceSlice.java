package com.hotatticgames.climbup.sim;
import java.nio.file.*;
/** Dev probe: TraceSlice seed k hard file. Generates (or loads from file) slice k, runs the solver, then prints where the live sim is and a 6 s replay of the pad move. */
public class TraceSlice {
    public static void main(String[] a) throws Exception {
        long seed = Long.parseLong(a[0]); int k = Integer.parseInt(a[1]), hard = Integer.parseInt(a[2]); Path f = Paths.get(a[3]);
        Tuning t = Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json")))); if (hard > 0) t = t.hardCopy(hard);
        Course c;
        if (Files.exists(f)) c = CourseIO.fromJson(new String(Files.readAllBytes(f)));
        else { Course prev = null; c = null; for (int i = 0; i <= k; i++) { c = CourseGenerator.chunk(seed, i, prev, t); prev = c; } Files.write(f, CourseIO.toJson(c).getBytes()); }
        Autopilot.Report r = Autopilot.run(c, t, 4000f);
        Sim s = Autopilot.lastReal;
        System.out.printf("completed=%b failedLink=%d info=%s | end mode=%s on=%d lastPad=%d s=%.2f y=%.2f vx=%.2f vy=%.2f t=%.2f%n", r.completed, r.failedLink, r.failInfo, s.mode, s.onElem, s.lastPad, s.s - c.get(0).s, s.y, s.vx, s.vy, s.time);
    }
}
