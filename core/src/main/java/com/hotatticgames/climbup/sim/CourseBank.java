package com.hotatticgames.climbup.sim;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Build tool: generates validated towers into assets/courses (run via `gradle :desktop:genCourses`). */
public final class CourseBank {
    public static final long[] SEEDS = {101, 202, 303, 404, 505, 606};

    public static void main(String[] args) throws Exception {
        File assets = new File(args.length > 0 ? args[0] : "assets");
        Tuning t = Tuning.parse(new String(Files.readAllBytes(new File(assets, "data/tuning.json").toPath()), StandardCharsets.UTF_8));
        File out = new File(assets, "courses"); out.mkdirs();
        for (int i = 0; i < SEEDS.length; i++) {
            long t0 = System.currentTimeMillis();
            Course c = CourseGenerator.generate(SEEDS[i], t);
            Autopilot.Report r = Autopilot.run(c, t, 9000f);
            if (!r.completed) throw new IllegalStateException("seed " + SEEDS[i] + " not completable: link " + r.failedLink);
            Files.write(new File(out, String.format("tower%02d.json", i + 1)).toPath(), CourseIO.toJson(c).getBytes(StandardCharsets.UTF_8));
            System.out.printf("tower%02d seed=%d elements=%d height=%.1f autopilot=%.0fs (%dms)%n", i + 1, SEEDS[i], c.size(), c.get(c.goalIndex()).y, r.simTime, System.currentTimeMillis() - t0);
        }
    }
}
