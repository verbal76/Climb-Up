package com.hotatticgames.climbup;

import com.hotatticgames.climbup.sim.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * The demo's course tool (docs/DEMO.md). Courses are made by the real generator through the real Tower, exactly as a climb on that seed makes them.
 *
 *   DemoBake scan   <tuning.json> <difficulty> <seed> [seed...]     one line per seed: the section's contents and its score (env SOLVE=0 skips the solver)
 *   DemoBake bake   <tuning.json> <recipe.json> <assets dir> [stage,...]   writes the stages' course files (all, or the listed 0-based ones) and their recipe sha256 lines
 *   DemoBake verify <tuning.json> <recipe.json> <assets dir>       makes every course again and fails unless each is byte-identical to the file
 *
 * The section a stage plays is castle fromCastle's deck to castle toCastle's door; its score (scan) rewards variety - distinct traversal kinds and hazard
 * kinds, the difficulty's signature threats - and penalises long runs of one kind, generator backtracks, unsolved slices and anything below the
 * stage floor. The score only ranks candidates; the chosen seeds and why are recorded in docs/DEMO.md.
 */
public final class DemoBake {
    static final int FROM = 2, TO = 3;

    public static void main(String[] a) throws Exception {
        Tuning base = Tuning.parse(new String(Files.readAllBytes(new File(a[1]).toPath()), StandardCharsets.UTF_8));
        switch (a[0]) {
            case "scan": {
                int d = Integer.parseInt(a[2]);
                for (int i = 3; i < a.length; i++) { long seed = Long.parseLong(a[i]); System.out.println(scan(base, d, seed)); System.out.flush(); }
                break;
            }
            case "bake": case "verify": {
                File recipeFile = new File(a[2]), assets = new File(a[3]);
                String recipeText = new String(Files.readAllBytes(recipeFile.toPath()), StandardCharsets.UTF_8);
                DemoRecipe r = parseLoose(recipeText);
                boolean ok = true; String out = recipeText;
                java.util.Set<Integer> only = new java.util.HashSet<>();
                if (a.length > 4) for (String x : a[4].split(",")) only.add(Integer.parseInt(x.trim()));
                for (DemoRecipe.Stage st : r.stages) {
                    if (!only.isEmpty() && !only.contains(st.difficulty)) continue;
                    long t0 = System.nanoTime();
                    Tuning t = gen(base, st.difficulty);
                    Built b = build(t, st.seed, r.toCastle);
                    byte[] file = DemoCourse.encode(st.seed, st.difficulty, CourseGenerator.RULES_VERSION, DemoCourse.tuningStamp(t), r.fromCastle, r.toCastle, b.blobs);
                    String sha = DemoCourse.contentSha256(file);
                    File f = new File(assets, st.course);
                    if (a[0].equals("bake")) {
                        f.getParentFile().mkdirs(); Files.write(f.toPath(), file);
                        out = out.replace(st.sha256, sha);
                        Files.write(recipeFile.toPath(), out.getBytes(StandardCharsets.UTF_8));          // after every stage: a long bake keeps what it finished
                        System.out.println("BAKED stage " + (st.difficulty + 1) + " seed " + st.seed + " slices " + b.blobs.size() + " top " + (int) b.top + " m sha256 " + sha + " (" + (System.nanoTime() - t0) / 1_000_000_000L + " s)");
                    } else {
                        String have = f.isFile() ? DemoCourse.contentSha256(Files.readAllBytes(f.toPath())) : "(missing)";
                        boolean same = sha.equals(have) && sha.equals(st.sha256);
                        ok &= same;
                        System.out.println((same ? "VERIFIED" : "MISMATCH") + " stage " + (st.difficulty + 1) + " seed " + st.seed + " generated " + sha + " file " + have + " recipe " + st.sha256 + " (" + (System.nanoTime() - t0) / 1_000_000_000L + " s)");
                    }
                }
                if (a[0].equals("bake")) Files.write(recipeFile.toPath(), out.getBytes(StandardCharsets.UTF_8));
                if (!ok) System.exit(1);
                break;
            }
            default: throw new IllegalArgumentException(a[0]);
        }
    }

    /** The recipe as written before its first bake (placeholder sha256 values are allowed here, nowhere else). */
    static DemoRecipe parseLoose(String text) {
        try { return DemoRecipe.parse(text); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("recipe: " + e.getMessage() + " (bake needs a 64-character placeholder per stage)", e); }
    }

    /** The tuning difficulty {@code d} generates with: exactly ClimbGame.tuningFor(d) for the bundled numbers. */
    static Tuning gen(Tuning base, int d) { return d == 0 ? base : base.hardCopy(d); }

    static final class Built { List<byte[]> blobs = new ArrayList<>(); double top; int backtracks; }

    /** The tower on {@code seed}, grown slice by slice until it holds castle {@code to} plus the stage's headroom. */
    static Built build(Tuning t, long seed, int to) {
        Tower tw = new Tower(seed, t);
        double need = to * (double) t.castleSpacing + DemoFlow.HEADROOM;
        while (tw.topAbsY() < need) tw.extend();
        Built b = new Built();
        for (int k = 0; k < tw.sliceCount(); k++) b.blobs.add(tw.blob(k));
        b.top = tw.topAbsY(); b.backtracks = tw.backtracks;
        return b;
    }

    static String kind(Element e) {
        switch (e.type) {
            case STATIC: return e.skin == 1 ? (e.holed() ? "BRIDGE_HOLED" : "BRIDGE") : e.skin == 4 ? "BELT_BRIDGE" : e.skin == 2 ? "CASTLE_DECK" : "STATIC";
            case RAMP: return "RAMP_" + e.skin;
            default: return e.type.name();
        }
    }

    static String scan(Tuning base, int d, long seed) {
        long t0 = System.nanoTime();
        Tuning t = gen(base, d);
        Built b;
        try { b = build(t, seed, TO); } catch (RuntimeException e) { return "SCAN d=" + d + " seed=" + seed + " FAILED " + e.getMessage(); }
        double lo = FROM * (double) t.castleSpacing, hi = TO * (double) t.castleSpacing, floor = lo - DemoFlow.FLOOR_BELOW_DECK;
        TreeMap<String, Integer> trav = new TreeMap<>(), haz = new TreeMap<>();
        int maxRun = 0, run = 0, routeN = 0, checkpoints = 0, solved = 0, sliceN = 0; String prev = null; double minY = Double.POSITIVE_INFINITY; boolean solve = !"0".equals(System.getenv("SOLVE"));
        StringBuilder unsolved = new StringBuilder();
        for (int k = 0; k < b.blobs.size(); k++) {
            CourseGenerator.Framed f = SliceCodec.decode(b.blobs.get(k));
            Course c = f.c;
            double sb = f.yBase + c.get(0).y, st = f.yBase + c.get(c.routeSize() - 1).y;
            if (st < lo || sb > hi) continue;
            sliceN++;
            for (int i = 0; i < c.size(); i++) {
                Element e = c.get(i); double y = f.yBase + e.y;
                if (y < lo - 0.5 || y > hi + 8) continue;
                minY = Math.min(minY, y - Math.abs(e.amp));
                if (i >= c.routeSize()) continue;
                routeN++; if (e.checkpoint) checkpoints++;
                String kd = kind(e);
                if (!kd.equals("STATIC") && !kd.equals("CASTLE_DECK")) { trav.merge(kd, 1, Integer::sum); run = kd.equals(prev) ? run + 1 : 1; maxRun = Math.max(maxRun, run); prev = kd; }
            }
            for (Element h : c.hazards) {
                double y = f.yBase + h.y; if (y < lo || y > hi + 8) continue;
                if (h.type == Element.Type.GATE || h.type == Element.Type.KEY || h.type == Element.Type.CLUB) continue;
                haz.merge(h.type.name(), 1, Integer::sum);
            }
            if (solve) {
                Autopilot.Report rep;
                try { rep = Autopilot.run(c, t, 4000f); } catch (RuntimeException e) { rep = new Autopilot.Report(); rep.failInfo = e.toString(); }
                if (rep.completed) solved++; else unsolved.append(k).append(':').append(rep.cause).append(' ');
            }
        }
        boolean sig = d == 0 || haz.containsKey("ENFORCER");
        int score = 3 * trav.size() + 2 * haz.size() + (d >= 1 && haz.containsKey("ENFORCER") ? 3 : 0) + (d >= 1 && haz.containsKey("AVALANCHE") ? 2 : 0)
            - 2 * Math.max(0, maxRun - 2) - b.backtracks - (solve ? 6 * (sliceN - solved) : 0) - (minY < floor ? 20 : 0);
        return "SCAN d=" + d + " seed=" + seed + " score=" + score + " slices=" + b.blobs.size() + " sectionSlices=" + sliceN + (solve ? " solved=" + solved + "/" + sliceN + (unsolved.length() > 0 ? " unsolved=[" + unsolved.toString().trim() + "]" : "") : " solver=off")
            + " backtracks=" + b.backtracks + " route=" + routeN + " checkpoints=" + checkpoints + " maxRun=" + maxRun + " minY=" + String.format(Locale.ROOT, "%.1f", minY) + (minY < floor ? " BELOW_FLOOR" : "")
            + " signature=" + sig + " traversal=" + trav + " hazards=" + haz + " (" + (System.nanoTime() - t0) / 1_000_000_000L + " s)";
    }
}
