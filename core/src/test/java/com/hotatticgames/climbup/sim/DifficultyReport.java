package com.hotatticgames.climbup.sim;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Diagnostic report (Priority 1, run by .github/workflows/difficulty-report.yml): generates seeded climbs per difficulty and scores them with
 * {@link DifficultyScore}. Writes windows.csv (every 25 m window), castles.csv (per castle) and summary.md (readable comparison) to the output folder.
 *   DifficultyReport <tuning.json> <top metres> <modes e.g. 0,1,2> <seeds e.g. 1,2> <out dir>
 */
public final class DifficultyReport {
    static final String[] MODE = {"NORMAL", "PUNISHER", "NGH"};
    static final Element.Type[] ENEMIES = {Element.Type.CRAB, Element.Type.BLOB, Element.Type.SKULL, Element.Type.BEE};

    public static void main(String[] a) throws Exception {
        Tuning base = Tuning.parse(new String(Files.readAllBytes(Paths.get(a[0]))));
        float top = Float.parseFloat(a[1]); Path out = Paths.get(a[4]); Files.createDirectories(out);
        String commit = System.getenv().getOrDefault("GITHUB_SHA", "local");
        try (PrintWriter wcsv = new PrintWriter(Files.newBufferedWriter(out.resolve("windows.csv")));
             PrintWriter ccsv = new PrintWriter(Files.newBufferedWriter(out.resolve("castles.csv")));
             PrintWriter md = new PrintWriter(Files.newBufferedWriter(out.resolve("summary.md")))) {
            wcsv.println("mode,seed,y0,y1,threat_pos,protective,net,adjusted_net,interaction,enemy_families,hazard_families,crab,blob,skull,bee,club,unscored_hazards,exposure,unreachable_enemies,low,challenge_free");
            ccsv.println("mode,seed,castle,mean_adj,median_adj,low_pct,longest_low_windows,threat_pos,protective,crab,blob,skull,bee,club,enemy_families,interaction");
            md.printf("# Difficulty report%n%ncommit `%s`, rules %d, seeds %s, to %.0f m. Windows of %.0f m; adjusted = repetition-capped threat + clubs + interactions (see DifficultyScore).%n%n",
                commit, CourseGenerator.RULES_VERSION, a[3], top, DifficultyScore.WINDOW);
            md.println("| mode | seed | castle | mean adj | median | low % | longest low (m) | threat | clubs | crab/blob/skull/bee | families | interactions |");
            md.println("|---|---|---|---|---|---|---|---|---|---|---|---|");
            for (String ms : a[2].split(",")) for (String ss : a[3].split(",")) {
                int mode = Integer.parseInt(ms.trim()); long seed = Long.parseLong(ss.trim());
                Tuning t = mode == 0 ? base : base.hardCopy(mode);
                DifficultyScore sc = new DifficultyScore(t);
                Course prev = null; int k = 0; float reached = 0f; String fail = null; long t0 = System.nanoTime();
                try {
                    while (prev == null || prev.get(prev.routeSize() - 1).y < top) {
                        Course c = CourseGenerator.chunk(seed, k++, prev, t); sc.add(c); prev = c; reached = c.get(c.routeSize() - 1).y;
                    }
                } catch (RuntimeException e) { fail = e.getClass().getSimpleName() + ": " + e.getMessage(); }
                float secs = (System.nanoTime() - t0) / 1e9f;
                for (DifficultyScore.Window w : sc.windows()) {
                    if (w.y0() >= Math.min(top, reached)) continue;
                    wcsv.printf(Locale.ROOT, "%s,%d,%.0f,%.0f,%.1f,%.1f,%.1f,%.2f,%.1f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%b,%b%n", MODE[mode], seed, w.y0(), w.y0() + DifficultyScore.WINDOW,
                        w.pos, w.prot, w.net(), w.adjustedNet(), w.interaction, w.enemyFamilies(), w.hazardFamilies(), n(w, Element.Type.CRAB), n(w, Element.Type.BLOB), n(w, Element.Type.SKULL),
                        n(w, Element.Type.BEE), n(w, Element.Type.CLUB), w.unscored.values().stream().mapToInt(Integer::intValue).sum(), w.exposure, w.unreachable, w.low(), w.free());
                }
                for (int castle = 1; castle <= 10; castle++) {
                    float y0 = (castle - 1) * DifficultyScore.CASTLE_SPAN, y1 = Math.min(castle * DifficultyScore.CASTLE_SPAN, Math.min(top, reached));
                    if (y1 <= y0) break;
                    float[] s = sc.summary(y0, y1); float pos = 0, prot = 0, inter = 0; int[] cnt = new int[5]; EnumSet<Element.Type> fam = EnumSet.noneOf(Element.Type.class);
                    for (DifficultyScore.Window w : sc.windows()) {
                        if (w.y0() < y0 || w.y0() >= y1) continue;
                        pos += w.pos; prot += w.prot; inter += w.interaction;
                        for (int e = 0; e < 4; e++) { cnt[e] += n(w, ENEMIES[e]); if (n(w, ENEMIES[e]) > 0) fam.add(ENEMIES[e]); }
                        cnt[4] += n(w, Element.Type.CLUB);
                    }
                    ccsv.printf(Locale.ROOT, "%s,%d,%d,%.2f,%.2f,%.1f,%.0f,%.1f,%.1f,%d,%d,%d,%d,%d,%d,%.1f%n", MODE[mode], seed, castle, s[0], s[1], s[2], s[3], pos, prot, cnt[0], cnt[1], cnt[2], cnt[3], cnt[4], fam.size(), inter);
                    md.printf(Locale.ROOT, "| %s | %d | %d | %.2f | %.2f | %.0f | %.0f | %.0f | %.0f | %d/%d/%d/%d | %d | %.1f |%n", MODE[mode], seed, castle, s[0], s[1], s[2], s[3] * DifficultyScore.WINDOW, pos, prot, cnt[0], cnt[1], cnt[2], cnt[3], fam.size(), inter);
                }
                md.printf(Locale.ROOT, "%n%s seed %d: reached %.0f m in %.0f s%s%n%n", MODE[mode], seed, reached, secs, fail == null ? "" : " - GENERATION STOPPED: " + fail);
                md.flush(); wcsv.flush(); ccsv.flush();
                System.out.printf(Locale.ROOT, "%s seed %d: reached %.0f m in %.0f s%s%n", MODE[mode], seed, reached, secs, fail == null ? "" : " FAIL " + fail);
            }
        }
    }
    private static int n(DifficultyScore.Window w, Element.Type k) { Integer v = w.counts.get(k); return v == null ? 0 : v; }
}
