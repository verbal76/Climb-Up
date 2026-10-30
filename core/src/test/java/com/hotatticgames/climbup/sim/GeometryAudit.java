package com.hotatticgames.climbup.sim;

import java.io.PrintWriter;
import java.nio.file.*;
import java.util.*;

/**
 * Owner directive 2026-10-30 (master vertical geometry, active corridor and buildable dead-zone audit): measures the generated tower between
 * successive spiral revolutions with the generator's OWN geometry (CourseGenerator.arcHalf / vLo / vHi / reachAt / layersClash / hzLo / hzHi and
 * GeometryIntegrity's hazard-box test, mirrored exactly). Observe-only: it reads a finished course and never touches generation.
 *
 * <p>Every sample is a point s on the route's arc (1 m apart); the revolution above it is the arc s + C (C = circumference) and the one below s - C.
 * Per sample: the nominal pitch (route height at s + C minus at s), the active layer's occupancy top (vHi: deck + standing room), its measured
 * player reach top (deck + reachAt over the decks within 12 m, ordinary and launcher reach separately), its hazard influence top (hzHi), and the
 * revolution above's lowest occupied boundary (vLo) and lowest deck. Gaps and the dead zone follow. Then, per mechanic, whether that mechanic
 * could stand on the route's deck at s under the current cross-layer rules, only under the measured-reach rule (C1 without the blanket 9.5 m), or
 * under neither (the same rule code, hypothetical element).
 *
 * <p>Enabled in LaneReport by -Dclimb.geomAudit=true; writes geometry_samples.csv, geometry_mechanics.csv and geometry_summary.json, and prints a
 * summary to the job log.
 */
final class GeometryAudit {
    static final boolean ON = Boolean.getBoolean("climb.geomAudit");
    static final float STEP = 1.0f, REACH_WIN = 12f, NEAR_WIN = 16f;
    /** Mechanics tested at every sample (a hypothetical element of that kind on the route's deck there). */
    static final String[] MECH = {"PLAIN", "PAD", "SPRING", "MOVE_V", "SEESAW", "RAMP", "SWING", "MOVE_H", "MORTAR"};

    final Tuning t; final float C; final String run;
    final Course world;                        // every element and hazard of the course at its absolute arc and height
    final List<Boolean> isRoute = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();

    GeometryAudit(Tuning t, String run) { this.t = t; this.C = t.circumference(); this.run = run; this.world = new Course(0L, C); }

    /** Adds one slice at its frame (the slice's first elements repeat the previous slice's last platform: added once). */
    void add(CourseGenerator.Framed f) {
        Course c = f.c;
        for (int i = 0; i < c.size(); i++) {
            Element e = c.get(i), x = copy(e, (float) (f.sBase + e.s), (float) (f.yBase + e.y));
            if (!seen.add(key(x))) continue;
            world.elements.add(x); isRoute.add(i < c.routeSize());
        }
        for (Element h : c.hazards) { Element x = copy(h, (float) (f.sBase + h.s), (float) (f.yBase + h.y)); if (seen.add("h" + key(x))) world.hazards.add(x); }
    }
    private static String key(Element e) { return e.type + ":" + Math.round(e.s * 100) + ":" + Math.round(e.y * 100) + ":" + Math.round(e.w * 100); }
    private static Element copy(Element e, float s, float y) {
        Element x = new Element(e.type, s, y, e.w); x.amp = e.amp; x.period = e.period; x.phase = e.phase; x.len = e.len; x.dir = e.dir; x.skin = e.skin;
        x.color = e.color; x.zone = e.zone; x.anchor = e.anchor; return x;
    }
    private static boolean physical(Element h) { return h.type != Element.Type.KEY && h.type != Element.Type.GATE && h.type != Element.Type.CLUB; }

    // ---- sorted indices for window queries
    private Element[] els, haz; private float[] es, hs;
    private void index() {
        els = world.elements.toArray(new Element[0]); Arrays.sort(els, Comparator.comparingDouble(e -> e.s)); es = new float[els.length]; for (int i = 0; i < els.length; i++) es[i] = els[i].s;
        List<Element> hp = new ArrayList<>(); for (Element h : world.hazards) if (physical(h)) hp.add(h);
        haz = hp.toArray(new Element[0]); Arrays.sort(haz, Comparator.comparingDouble(CourseGenerator::hzS)); hs = new float[haz.length]; for (int i = 0; i < haz.length; i++) hs[i] = CourseGenerator.hzS(haz[i]);
    }
    private static int lower(float[] a, float v) { int lo = 0, hi = a.length; while (lo < hi) { int m = (lo + hi) >>> 1; if (a[m] < v) lo = m + 1; else hi = m; } return lo; }

    /** Route height at arc s (linear between route decks), NaN outside the route. */
    private float[] rs, ry;
    private void routeLine() {
        List<float[]> pts = new ArrayList<>();
        for (int i = 0; i < world.elements.size(); i++) { Element e = world.elements.get(i); if (isRoute.get(i) && e.isPlatform()) pts.add(new float[]{e.s, e.y}); }
        pts.sort(Comparator.comparingDouble(p -> p[0]));
        rs = new float[pts.size()]; ry = new float[pts.size()]; for (int i = 0; i < pts.size(); i++) { rs[i] = pts.get(i)[0]; ry[i] = pts.get(i)[1]; }
    }
    float routeY(float s) {
        if (rs.length == 0 || s < rs[0] || s > rs[rs.length - 1]) return Float.NaN;
        int i = Math.max(1, lower(rs, s)); if (i >= rs.length) return ry[rs.length - 1];
        float a = rs[i - 1], b = rs[i]; return b <= a ? ry[i] : ry[i - 1] + (ry[i] - ry[i - 1]) * (s - a) / (b - a);
    }

    static final class Sample { float s, yR, pitch, deck = Float.NaN, occ = Float.NaN, reachPlain = Float.NaN, reachLaunch = Float.NaN, haz = Float.NaN, upDeck = Float.NaN, upLo = Float.NaN, upHazLo = Float.NaN;
        String upTypes = "", actTypes = ""; int castle; int[] mech = new int[MECH.length]; }

    final List<Sample> samples = new ArrayList<>();

    void analyse() {
        index(); routeLine();
        if (rs.length < 2) return;
        for (float s = rs[0]; s + C <= rs[rs.length - 1]; s += STEP) {
            Sample p = new Sample(); p.s = s; p.yR = routeY(s); p.pitch = routeY(s + C) - p.yR; p.castle = Math.min(10, 1 + (int) (p.yR / t.castleSpacing));
            StringBuilder at = new StringBuilder(), ut = new StringBuilder();
            // active layer: elements covering s; reach from platforms within REACH_WIN; hazards covering s
            for (int i = lower(es, s - NEAR_WIN); i < es.length && es[i] <= s + NEAR_WIN; i++) {
                Element e = els[i]; float d = Math.abs(e.s - s), ah = CourseGenerator.arcHalf(e);
                if (d <= ah) { p.occ = max(p.occ, CourseGenerator.vHi(e)); if (e.isPlatform()) p.deck = max(p.deck, e.y); at.append(e.type).append(' '); }
                if (e.isPlatform() && d <= REACH_WIN) {
                    float r = CourseGenerator.reachAt(world, e, Math.max(0f, d - ah));
                    if (r > 0f) { if (CourseGenerator.launches(e)) p.reachLaunch = max(p.reachLaunch, e.y + r); else p.reachPlain = max(p.reachPlain, e.y + r); }
                }
            }
            for (int i = lower(hs, s - NEAR_WIN); i < hs.length && hs[i] <= s + NEAR_WIN; i++) { Element h = haz[i]; if (Math.abs(hs[i] - s) <= CourseGenerator.hzHalf(h)) p.haz = max(p.haz, CourseGenerator.hzHi(h)); }
            // the revolution above at s + C: its lowest occupied boundary and lowest deck
            float su = s + C;
            for (int i = lower(es, su - NEAR_WIN); i < es.length && es[i] <= su + NEAR_WIN; i++) {
                Element e = els[i]; if (Math.abs(e.s - su) <= CourseGenerator.arcHalf(e)) { p.upLo = min(p.upLo, CourseGenerator.vLo(e)); if (e.isPlatform()) p.upDeck = min(p.upDeck, e.y); ut.append(e.type).append(' '); }
            }
            for (int i = lower(hs, su - NEAR_WIN); i < hs.length && hs[i] <= su + NEAR_WIN; i++) { Element h = haz[i]; if (Math.abs(hs[i] - su) <= CourseGenerator.hzHalf(h)) p.upHazLo = min(p.upHazLo, CourseGenerator.hzLo(h)); }
            p.actTypes = at.toString().trim(); p.upTypes = ut.toString().trim();
            if (!Float.isNaN(p.deck)) for (int m = 0; m < MECH.length; m++) p.mech[m] = verdict(MECH[m], s, p.deck);
            samples.add(p);
        }
    }

    /** 0 fits under the current rules, 1 fits only without the blanket 9.5 m rule (measured reach C1 + bodies + plain 4 m), 2 fits under neither. */
    private int verdict(String mech, float s, float deck) {
        if ("MORTAR".equals(mech)) return mortarVerdict(s, deck);
        Element x = hypothetical(mech, s, deck);
        boolean cur = true, meas = true;
        for (float ref : new float[]{s + C, s - C}) {
            for (int i = lower(es, ref - NEAR_WIN); i < es.length && es[i] <= ref + NEAR_WIN; i++) {
                Element f = els[i];
                if (CourseGenerator.layersClash(world, x, f)) cur = false;
                if (measuredClash(x, f)) meas = false;
                if (!meas) break;
            }
        }
        return cur ? 0 : meas ? 1 : 2;
    }
    /** layersClash without the blanket launcher spacing: C1 measured reach, bodies, and the plain 4 m between platforms. */
    private boolean measuredClash(Element e, Element f) {
        if (Math.abs(e.s - f.s) < C * 0.5f) return false;
        if (CourseGenerator.reachShortcut(world, e, f)) return true;
        if (Math.abs(world.dsWrap(e.s, f.s)) > CourseGenerator.arcHalf(e) + CourseGenerator.arcHalf(f) + 2.5f) return false;
        if (CourseGenerator.vLo(e) - 1.5f < CourseGenerator.vHi(f) && CourseGenerator.vHi(e) + 1.5f > CourseGenerator.vLo(f)) return true;
        if (!e.isPlatform() || !f.isPlatform()) return false;
        return Math.abs(e.y - f.y) < CourseGenerator.PLAIN_LAYER_DY;
    }
    private Element hypothetical(String mech, float s, float deck) {
        switch (mech) {
            case "PAD": return new Element(Element.Type.PAD, s, deck, 1.6f);
            case "SPRING": return new Element(Element.Type.SPRING, s, deck, 1.2f);
            case "MOVE_V": { Element e = new Element(Element.Type.MOVE_V, s, deck, 2.5f); e.amp = 3f; return e; }
            case "SEESAW": return new Element(Element.Type.SEESAW, s, deck, 5f);
            case "RAMP": { Element e = new Element(Element.Type.RAMP, s, deck, 6f); e.amp = 0.35f; return e; }
            case "SWING": { Element e = new Element(Element.Type.SWING, s, deck, 2.5f); e.len = 3.5f; e.amp = 0.6f; return e; }
            case "MOVE_H": { Element e = new Element(Element.Type.MOVE_H, s, deck, 2.5f); e.amp = 1.5f; return e; }
            default: return new Element(Element.Type.STATIC, s, deck, 3f);
        }
    }
    /** A mortar in the gap at s firing from MORTAR_DEPTH below the deck to about twice the player's height above it (CourseGenerator.buildMortar),
     *  tested as GeometryIntegrity tests a hazard's lethal box against decks of other revolutions; the blanket rule does not apply to hazards. */
    private int mortarVerdict(float s, float deck) {
        Element m = new Element(Element.Type.MORTAR, s, deck - CourseGenerator.MORTAR_DEPTH, 0f); m.amp = CourseGenerator.MORTAR_DEPTH + 0.3f + 2f * t.height;
        for (float ref : new float[]{s + C, s - C}) for (int i = lower(es, ref - NEAR_WIN); i < es.length && es[i] <= ref + NEAR_WIN; i++) {
            Element f = els[i]; float hsx = CourseGenerator.hzS(m);
            if (Math.abs(world.dsWrap(hsx, f.s)) < CourseGenerator.hzHalf(m) + CourseGenerator.arcHalf(f) && CourseGenerator.hzLo(m) < CourseGenerator.vHi(f) && CourseGenerator.hzHi(m) > CourseGenerator.vLo(f)) return 2;
        }
        return 0;
    }
    private static float max(float a, float b) { return Float.isNaN(a) ? b : Float.isNaN(b) ? a : Math.max(a, b); }          // (NaN = nothing there)
    private static float min(float a, float b) { return Float.isNaN(a) ? b : Float.isNaN(b) ? a : Math.min(a, b); }

    // ---- output
    void write(Path out) throws Exception {
        analyse();
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out.resolve("geometry_samples.csv")))) {
            w.print("run,s,castle,route_y,pitch,deck_top,occupancy_top,reach_plain_top,reach_launch_top,hazard_top,upper_deck,upper_low,upper_hazard_low,"
                + "phys_clear,reach_gap,hazard_gap,dead_zone,active_types,upper_types"); for (String m : MECH) w.print(",fit_" + m); w.println();
            for (Sample p : samples) {
                float reach = max(p.reachPlain, p.reachLaunch), top = max(max(p.occ, reach), p.haz);
                w.print(String.join(",", run, f(p.s), "" + p.castle, f(p.yR), f(p.pitch), f(p.deck), f(p.occ), f(p.reachPlain), f(p.reachLaunch), f(p.haz), f(p.upDeck), f(p.upLo), f(p.upHazLo),
                    f(p.upLo - p.occ), f(p.upDeck - reach), f(min(p.upLo, p.upHazLo) - p.haz), f(min(p.upLo, p.upHazLo) - top), p.actTypes, p.upTypes));
                for (int m = 0; m < MECH.length; m++) w.print(Float.isNaN(p.deck) ? "," : "," + p.mech[m]);
                w.println();
            }
        }
        String sum = summary();
        Files.write(out.resolve("geometry_summary.json"), json().getBytes());
        System.out.print(sum);
    }
    private static String f(float v) { return Float.isNaN(v) ? "" : String.format(Locale.ROOT, "%.3f", v); }

    static double[] pct(List<Float> v) {
        if (v.isEmpty()) return new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        List<Float> s = new ArrayList<>(v); Collections.sort(s); double sum = 0, sq = 0; for (float x : s) { sum += x; sq += x * x; } double mean = sum / s.size();
        double[] q = {0.05, 0.25, 0.5, 0.75, 0.95}; double[] r = new double[9];
        r[0] = s.get(0); for (int i = 0; i < 5; i++) r[1 + i] = s.get((int) Math.min(s.size() - 1, Math.round(q[i] * (s.size() - 1)))); r[6] = s.get(s.size() - 1); r[7] = mean; r[8] = Math.sqrt(Math.max(0, sq / s.size() - mean * mean));
        return r;
    }
    private List<Float> col(java.util.function.Function<Sample, Float> g) { List<Float> l = new ArrayList<>(); for (Sample p : samples) { float v = g.apply(p); if (!Float.isNaN(v)) l.add(v); } return l; }
    private static final String[] STAT = {"min", "p5", "p25", "median", "p75", "p95", "max", "mean", "sd"};
    private final LinkedHashMap<String, double[]> stats = new LinkedHashMap<>();
    private final LinkedHashMap<String, Object> extra = new LinkedHashMap<>();

    String summary() {
        stats.put("pitch", pct(col(p -> p.pitch)));
        stats.put("phys_clear", pct(col(p -> p.upLo - p.occ)));
        stats.put("reach_gap", pct(col(p -> p.upDeck - max(p.reachPlain, p.reachLaunch))));
        stats.put("reach_gap_plain", pct(col(p -> p.upDeck - p.reachPlain)));
        stats.put("hazard_gap", pct(col(p -> min(p.upLo, p.upHazLo) - p.haz)));
        stats.put("dead_zone", pct(col(p -> min(p.upLo, p.upHazLo) - max(max(p.occ, max(p.reachPlain, p.reachLaunch)), p.haz))));
        stats.put("deck_to_upper_deck", pct(col(p -> p.upDeck - p.deck)));
        int n = samples.size(), covered = 0, withDeck = 0; for (Sample p : samples) { if (!Float.isNaN(p.upLo)) covered++; if (!Float.isNaN(p.deck)) withDeck++; }
        extra.put("samples", n); extra.put("upper_covered", covered); extra.put("active_deck", withDeck); extra.put("circumference", C); extra.put("radius", t.radius);
        int[][] mc = new int[MECH.length][3]; for (Sample p : samples) if (!Float.isNaN(p.deck)) for (int m = 0; m < MECH.length; m++) mc[m][p.mech[m]]++;
        LinkedHashMap<String, int[]> mech = new LinkedHashMap<>(); for (int m = 0; m < MECH.length; m++) mech.put(MECH[m], mc[m]); extra.put("mechanics", mech);
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "GEOMETRY %s: radius %.2f, circumference %.2f m, %d samples (1 m of route arc), revolution above occupied at %.1f%%, route deck at %.1f%%%n",
            run, t.radius, C, n, 100.0 * covered / Math.max(1, n), 100.0 * withDeck / Math.max(1, n)));
        sb.append("GEOMETRY metric            min     p5    p25 median    p75    p95    max   mean     sd\n");
        for (Map.Entry<String, double[]> e : stats.entrySet()) { double[] r = e.getValue(); sb.append(String.format(Locale.ROOT, "GEOMETRY %-18s", e.getKey())); for (double v : r) sb.append(String.format(Locale.ROOT, " %6.2f", v)); sb.append('\n'); }
        sb.append("GEOMETRY mechanic at the route deck: fits now / only without the blanket 9.5 m rule / neither (share of samples with a route deck)\n");
        for (int m = 0; m < MECH.length; m++) { int tot = Math.max(1, mc[m][0] + mc[m][1] + mc[m][2]); sb.append(String.format(Locale.ROOT, "GEOMETRY   %-7s %5.1f%% %5.1f%% %5.1f%%%n", MECH[m], 100.0 * mc[m][0] / tot, 100.0 * mc[m][1] / tot, 100.0 * mc[m][2] / tot)); }
        return sb.toString();
    }
    String json() {
        StringBuilder j = new StringBuilder("{\"run\":\"" + run + "\"");
        for (Map.Entry<String, Object> e : extra.entrySet()) {
            j.append(",\"").append(e.getKey()).append("\":");
            if (e.getValue() instanceof Map) { j.append('{'); boolean first = true; for (Map.Entry<?, ?> m : ((Map<?, ?>) e.getValue()).entrySet()) { int[] v = (int[]) m.getValue(); j.append(first ? "" : ",").append('"').append(m.getKey()).append("\":[").append(v[0]).append(',').append(v[1]).append(',').append(v[2]).append(']'); first = false; } j.append('}'); }
            else j.append(e.getValue());
        }
        for (Map.Entry<String, double[]> e : stats.entrySet()) { j.append(",\"").append(e.getKey()).append("\":{"); for (int i = 0; i < STAT.length; i++) j.append(i > 0 ? "," : "").append('"').append(STAT[i]).append("\":").append(Double.isNaN(e.getValue()[i]) ? "null" : String.format(Locale.ROOT, "%.3f", e.getValue()[i])); j.append('}'); }
        return j.append("}\n").toString();
    }
}
