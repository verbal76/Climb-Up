package com.hotatticgames.climbup.sim;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Diagnostic report (Priority 1, run by .github/workflows/difficulty-report.yml): generates seeded climbs per difficulty the way the game streams them
 * ({@link CourseGenerator#chunkFramed}), runs the existing whole-slice solver ({@link Autopilot#run}) on every slice and scores them with
 * {@link DifficultyScore}. Writes windows.csv (every 25 m window), castles.csv (per castle), solver.csv (per slice), report.json and summary.md to
 * the output folder. Every row is flushed as soon as it exists, so a mode that fails or times out still leaves its partial results.
 *   DifficultyReport <tuning.json> <top metres> <modes e.g. 0,1,2> <seeds e.g. 1,2> <out dir>     (env SOLVE=0 skips the solver; RUN_BUDGET=seconds stops a run early and still writes it)
 */
public final class DifficultyReport {
    static final String[] MODE = {"NORMAL", "PUNISHER", "NGH"};
    static final Element.Type[] ENEMIES = {Element.Type.CRAB, Element.Type.BLOB, Element.Type.SKULL, Element.Type.BEE};

    public static void main(String[] a) throws Exception {
        Tuning base = Tuning.parse(new String(Files.readAllBytes(Paths.get(a[0]))));
        float top = Float.parseFloat(a[1]); Path out = Paths.get(a[4]); Files.createDirectories(out);
        boolean solve = !"0".equals(System.getenv("SOLVE"));
        double budget = Double.parseDouble(System.getenv().getOrDefault("RUN_BUDGET", "1e9"));
        String commit = System.getenv().getOrDefault("GITHUB_SHA", "local");
        StringBuilder json = new StringBuilder();
        try (PrintWriter wcsv = new PrintWriter(Files.newBufferedWriter(out.resolve("windows.csv")));
             PrintWriter ccsv = new PrintWriter(Files.newBufferedWriter(out.resolve("castles.csv")));
             PrintWriter scsv = new PrintWriter(Files.newBufferedWriter(out.resolve("solver.csv")));
             PrintWriter md = new PrintWriter(Files.newBufferedWriter(out.resolve("summary.md")))) {
            wcsv.println("mode,seed,y0,y1,threat_pos,protective,net,adjusted_net,interaction,enemy_families,hazard_families,crab,blob,skull,bee,club,static_hazards,exposure,unreachable_enemies,inaccessible_clubs,off_route_hazards,low,challenge_free,"
                + "saw_h,saw_v,pendulum,cannon,aimed,mortar,spike_trap,spike_block,spike_drop");
            ccsv.println("mode,seed,castle,mean_adj,median_adj,low_pct,longest_low_windows,threat_pos,protective,crab,blob,skull,bee,club,enemy_families,hazard_families,interaction,gen_seconds,solver_slices,solver_failures");
            scsv.println("mode,seed,slice,y0,y1,gen_seconds,solve_seconds,completed,links,failed_link,min_margin,fail_info");
            md.printf("# Difficulty report%n%ncommit `%s`, rules %d, seeds %s, to %.0f m, solver %s. Windows of %.0f m; adjusted = repetition-capped threat (enemies and route hazards, weights in DifficultyScore.WEIGHTS) + clubs + interactions.%n%n",
                commit, CourseGenerator.RULES_VERSION, a[3], top, solve ? "on" : "off", DifficultyScore.WINDOW);
            md.println("| mode | seed | castle | mean adj | median | low % | longest low (m) | threat | clubs | crab/blob/skull/bee | families | interactions | gen s | solver fails |");
            md.println("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|");
            json.append("{\"commit\":\"").append(commit).append("\",\"rules\":").append(CourseGenerator.RULES_VERSION).append(",\"top\":").append(top).append(",\"runs\":[");
            boolean firstRun = true;
            for (String ms : a[2].split(",")) for (String ss : a[3].split(",")) {
                int mode = Integer.parseInt(ms.trim()); long seed = Long.parseLong(ss.trim());
                Tuning t = mode == 0 ? base : base.hardCopy(mode);
                DifficultyScore sc = new DifficultyScore(t);
                CourseGenerator.Framed f = null; int k = 0; float reached = 0f; String fail = null; long t0 = System.nanoTime();
                float[] genAt = new float[11]; int[] slicesAt = new int[11], failsAt = new int[11]; int solverFails = 0;
                try {
                    while (f == null || f.yBase + f.c.get(f.c.routeSize() - 1).y < top) {
                        long g0 = System.nanoTime();
                        f = CourseGenerator.chunkFramed(seed, k, f, t);
                        float gen = (System.nanoTime() - g0) / 1e9f, yOff = (float) f.yBase;
                        float y0 = yOff + f.c.get(0).y, y1 = yOff + f.c.get(f.c.routeSize() - 1).y;
                        sc.add(f.c, yOff); reached = y1;
                        int castle = Math.min(10, 1 + (int) (y0 / DifficultyScore.CASTLE_SPAN)); genAt[castle] += gen;
                        if (solve) {
                            long s0 = System.nanoTime(); Autopilot.Report r;
                            try { r = Autopilot.run(f.c, t, 4000f); }
                            catch (RuntimeException e) { r = new Autopilot.Report(); r.failInfo = "solver threw " + e; }
                            slicesAt[castle]++; if (!r.completed) { failsAt[castle]++; solverFails++; }
                            scsv.printf(Locale.ROOT, "%s,%d,%d,%.1f,%.1f,%.3f,%.1f,%b,%d,%d,%.3f,\"%s\"%n", MODE[mode], seed, k, y0, y1, gen, (System.nanoTime() - s0) / 1e9f,
                                r.completed, r.links, r.failedLink, r.minMargin, r.failInfo.replace('"', '\''));
                            scsv.flush();
                        }
                        k++;
                        if ((System.nanoTime() - t0) / 1e9 > budget) { fail = "time budget of " + (int) budget + " s reached (partial results)"; break; }
                    }
                } catch (RuntimeException e) { fail = e.getClass().getSimpleName() + ": " + e.getMessage(); }
                float secs = (System.nanoTime() - t0) / 1e9f;
                for (DifficultyScore.Window w : sc.windows()) {
                    if (w.y0() >= Math.min(top, reached)) continue;
                    wcsv.printf(Locale.ROOT, "%s,%d,%.0f,%.0f,%.1f,%.1f,%.1f,%.2f,%.1f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%b,%b,%s%n", MODE[mode], seed, w.y0(), w.y0() + DifficultyScore.WINDOW,
                        w.pos, w.prot, w.net(), w.adjustedNet(), w.interaction, w.enemyFamilies(), w.hazardFamilies(), n(w, Element.Type.CRAB), n(w, Element.Type.BLOB), n(w, Element.Type.SKULL),
                        n(w, Element.Type.BEE), n(w, Element.Type.CLUB), statics(w), w.exposure, w.unreachable, w.inaccessibleClubs, w.offRouteHazards, w.low(), w.free(), hazardCounts(w));
                }
                if (!firstRun) json.append(','); firstRun = false;
                json.append("{\"mode\":\"").append(MODE[mode]).append("\",\"seed\":").append(seed).append(",\"reached\":").append(reached).append(",\"seconds\":").append(secs)
                    .append(",\"slices\":").append(k).append(",\"solver_failures\":").append(solverFails).append(",\"generation_error\":").append(fail == null ? "null" : "\"" + fail.replace('"', '\'') + "\"").append(",\"castles\":[");
                for (int castle = 1; castle <= 10; castle++) {
                    float y0 = (castle - 1) * DifficultyScore.CASTLE_SPAN, y1 = Math.min(castle * DifficultyScore.CASTLE_SPAN, Math.min(top, reached));
                    if (y1 <= y0) break;
                    float[] s = sc.summary(y0, y1); float pos = 0, prot = 0, inter = 0; int[] cnt = new int[5];
                    EnumSet<Element.Type> fam = EnumSet.noneOf(Element.Type.class); Set<Element.Type> haz = new HashSet<>();
                    for (DifficultyScore.Window w : sc.windows()) {
                        if (w.y0() < y0 || w.y0() >= y1) continue;
                        pos += w.pos; prot += w.prot; inter += w.interaction; for (Element.Type h : HAZARDS) if (n(w, h) > 0) haz.add(h);
                        for (int e = 0; e < 4; e++) { cnt[e] += n(w, ENEMIES[e]); if (n(w, ENEMIES[e]) > 0) fam.add(ENEMIES[e]); }
                        cnt[4] += n(w, Element.Type.CLUB);
                    }
                    int hf = fam.size() + haz.size();
                    ccsv.printf(Locale.ROOT, "%s,%d,%d,%.2f,%.2f,%.1f,%.0f,%.1f,%.1f,%d,%d,%d,%d,%d,%d,%d,%.1f,%.2f,%d,%d%n", MODE[mode], seed, castle, s[0], s[1], s[2], s[3], pos, prot,
                        cnt[0], cnt[1], cnt[2], cnt[3], cnt[4], fam.size(), hf, inter, genAt[castle], slicesAt[castle], failsAt[castle]);
                    md.printf(Locale.ROOT, "| %s | %d | %d | %.2f | %.2f | %.0f | %.0f | %.0f | %.0f | %d/%d/%d/%d | %d | %.1f | %.1f | %s |%n", MODE[mode], seed, castle, s[0], s[1], s[2],
                        s[3] * DifficultyScore.WINDOW, pos, prot, cnt[0], cnt[1], cnt[2], cnt[3], hf, inter, genAt[castle], solve ? failsAt[castle] + "/" + slicesAt[castle] : "-");
                    if (castle > 1) json.append(',');
                    json.append(String.format(Locale.ROOT, "{\"castle\":%d,\"mean_adj\":%.3f,\"median_adj\":%.3f,\"low_pct\":%.1f,\"longest_low_m\":%.0f,\"threat\":%.1f,\"protective\":%.1f,"
                        + "\"crab\":%d,\"blob\":%d,\"skull\":%d,\"bee\":%d,\"club\":%d,\"enemy_families\":%d,\"hazard_families\":%d,\"interaction\":%.1f,\"gen_seconds\":%.2f,\"solver_slices\":%d,\"solver_failures\":%d}",
                        castle, s[0], s[1], s[2], s[3] * DifficultyScore.WINDOW, pos, prot, cnt[0], cnt[1], cnt[2], cnt[3], cnt[4], fam.size(), hf, inter, genAt[castle], slicesAt[castle], failsAt[castle]));
                }
                json.append("]}");
                md.printf(Locale.ROOT, "%n%s seed %d: reached %.0f m, %d slices in %.0f s%s%s%n%n", MODE[mode], seed, reached, k, secs,
                    solve ? ", solver failures " + solverFails + "/" + k : "", fail == null ? "" : " - GENERATION STOPPED: " + fail);
                md.flush(); wcsv.flush(); ccsv.flush();
                Files.write(out.resolve("report.json"), (json + "]}").getBytes("UTF-8"));
                System.out.printf(Locale.ROOT, "%s seed %d: reached %.0f m in %.0f s%s%n", MODE[mode], seed, reached, secs, fail == null ? "" : " FAIL " + fail);
            }
        }
    }
    static final Element.Type[] HAZARDS = {Element.Type.SAW_H, Element.Type.SAW_V, Element.Type.PENDULUM, Element.Type.CANNON, Element.Type.AIMED,
        Element.Type.MORTAR, Element.Type.SPIKE_TRAP, Element.Type.SPIKE_BLOCK, Element.Type.SPIKE_DROP};
    private static int statics(DifficultyScore.Window w) { int s = 0; for (Element.Type h : HAZARDS) s += n(w, h); return s; }
    private static String hazardCounts(DifficultyScore.Window w) { StringBuilder b = new StringBuilder(); for (Element.Type h : HAZARDS) b.append(b.length() == 0 ? "" : ",").append(n(w, h)); return b.toString(); }
    private static int n(DifficultyScore.Window w, Element.Type k) { Integer v = w.counts.get(k); return v == null ? 0 : v; }
}
