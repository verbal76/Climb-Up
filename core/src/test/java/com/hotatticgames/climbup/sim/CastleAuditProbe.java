package com.hotatticgames.climbup.sim;

import java.nio.file.*;
import org.junit.Test;

/**
 * Scratch probe (overnight priority D, 2026-10-30: progression controller audit, measurement only; not a fixture). Grows climbs as the game does and
 * prints, for every completed castle, the controller's own measurement against the profile's target (total and T/H/E/X), the factors that castle
 * was built with and whether each sat on its bound, and for NGH the hazard control (requested, applied, constraint).
 *
 * <p>Env: CLIMB_PROBE, CLIMB_CASES = "mode:seed:top,...".
 */
public class CastleAuditProbe {
    @Test public void castles() throws Exception {
        org.junit.Assume.assumeTrue(System.getenv("CLIMB_PROBE") != null);
        String cases = System.getenv().getOrDefault("CLIMB_CASES", "1:7322:1600");
        Tuning base = Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json"))));
        for (String cs : cases.split(",")) {
            String[] p = cs.split(":"); int lvl = Integer.parseInt(p[0]); long seed = Long.parseLong(p[1]); float top = Float.parseFloat(p[2]);
            Tuning t = lvl == 0 ? base : base.hardCopy(lvl);
            DifficultyProfile P = DifficultyProfile.of(t);
            GameGrowth g = new GameGrowth(seed, t);
            StringBuilder sb = new StringBuilder(String.format("== %s seed %d (castle spacing %.0f m)%n", new String[]{"NORMAL", "PUNISHER", "NGH"}[lvl], seed, t.castleSpacing));
            double y = 0; int printed = 0, capPrinted = 0; long w0 = System.nanoTime();
            try {
                for (int k = 0; k < 400 && y < top; k++) {
                    CourseGenerator.Framed f = g.next(); y = f.yBase + f.c.get(f.c.routeSize() - 1).y;
                    Progression pr = Progression.of(f.c.prog);
                    for (int i = printed; i < pr.completedCount(); i++) {
                        float[] r = pr.completed(i); int n = (int) r[0];
                        float tot = Progression.total(r, 1), tg = P.targetTotal(n);
                        StringBuilder fs = new StringBuilder();
                        for (int q = 0; q < 4; q++) { float fv = r[1 + Progression.ACC + q]; fs.append(String.format(" %s%.2f%s", "THEX".charAt(q), fv, fv <= P.factorLo(q) + 1e-4f ? "(lo)" : fv >= P.factorHi(q) - 1e-4f && !(q == 1 && P.capacityControl()) ? "(hi)" : "")); }
                        sb.append(String.format("CASTLE %2d total %6.0f / target %6.0f (%+5.1f%%) | T %5.0f/%5.0f H %5.0f/%5.0f E %5.0f/%5.0f X %4.0f/%4.0f M %3.0f | links %3.0f chall %3.0f rest %2.0f | factors%s%n",
                                n, tot, tg, 100 * (tot - tg) / tg, r[1], P.target(n, 0), r[2], P.target(n, 1), r[3], P.target(n, 2), r[4], P.target(n, 3), r[5], r[6], r[7], r[8], fs));
                    }
                    printed = pr.completedCount();
                    for (int i = capPrinted; i < pr.capRecordCount(); i++) {
                        float[] c = pr.capRecord(i); StringBuilder ks = new StringBuilder();
                        for (int q = 0; q < Progression.CAP_K; q++) ks.append(String.format(" %s %.0f/%.0f (%.0f pts)", Progression.CAP_KINDS[q], c[1 + 3 * q + 1], c[1 + 3 * q], c[1 + 3 * q + 2]));
                        sb.append(String.format("   NGH hazard control castle %.0f: requested %.2f applied %.2f (%s); accepted/attempted%s%n", c[0], c[1 + Progression.CAP_W], c[2 + Progression.CAP_W], Progression.HC_NAMES[(int) c[3 + Progression.CAP_W]], ks));
                    }
                    capPrinted = pr.capRecordCount();
                }
            } catch (RuntimeException e) { sb.append("STOPPED: ").append(e.getMessage()).append('\n'); }
            sb.append(String.format("   (%.0f m in %.0f s)%n", y, (System.nanoTime() - w0) / 1e9));
            System.out.print(sb);
        }
    }
}
