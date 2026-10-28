package com.hotatticgames.climbup.sim;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.*;

/**
 * Gameplay variety, measured apart from difficulty (owner amendment 2026-10-27). Observe-only: nothing here feeds generation or the difficulty score.
 *
 * <p>Unit: the main-route link (one move from a platform to the next), in climbing order, as LaneReport records it. Each link gets
 * <ul>
 *   <li>a <b>terrain mechanic</b>: how the move is made, from the game's own element registry. Family (the gameplay mechanic) and variant
 *       (a form of it that plays differently: e.g. ramp / spinning ramp, seesaw / balance conveyor). Priority: what you leave from (rope, cable,
 *       trampoline, spring, swing), then a moving end, then crumbling or holed footing, then a tilting surface, then a plain jump
 *       (long jump when the gap is beyond 85% of the reach at that rise, LaneReport's own T-LONG line);</li>
 *   <li>its <b>hazard families</b> (on either platform of the link): saw (h/v), pendulum, projectile (side/aimed cannon, mortar), spike mat,
 *       spiked block, crusher (chained spike drop), avalanche;</li>
 *   <li>its <b>enemy families</b> (reaching either platform): crab, bee, skull, blob, enforcer; enforcer variants by tier (yellow, orange, red),
 *       which count as one family with three behaviours;</li>
 *   <li>its <b>signature</b>: terrain family | hazard families | enemy families: the arrangement a player meets.</li>
 * </ul>
 * Scales: 25 m windows (by the link's take-off height), rolling 100 m (four windows), castle intervals (500 m) and the whole run.
 * <p><b>Variety score</b> of a rolling 100 m block, 0..1, the mean of three parts, each 0..1:
 * <ol>
 *   <li>evenness: Shannon entropy of the block's signatures divided by log2(links) (1 = every link different);</li>
 *   <li>novelty: the share of the block's links whose signature did not occur in the previous 100 m;</li>
 *   <li>run break-up: 1 - (longest run of one signature - 1) / (links - 1).</li>
 * </ol>
 * A block with fewer than 2 links scores NaN (reported, not counted). No target values are set: the first runs are the baseline.
 * Counting more objects does not raise it: two saws on one link are one family; a longer run of one arrangement lowers it.
 */
public final class VarietyReport {
    private VarietyReport() {}

    static final String[] TERRAIN = {"rope", "cable", "trampoline", "spring", "swing", "moving", "crumbling", "seesaw", "ramp", "long_jump", "jump"};
    static final String[] HAZARDS = {"saw", "pendulum", "projectile", "spike_mat", "spiked_block", "crusher", "avalanche"};
    static final String[] ENEMIES = {"crab", "bee", "skull", "blob", "enforcer"};

    /** One link's classification. */
    static final class L {
        double y0, y1; int castle, window; String terrain, tVariant; TreeSet<String> haz = new TreeSet<>(), hazV = new TreeSet<>(), en = new TreeSet<>(), enV = new TreeSet<>();
        boolean challenge; String sig;
        String sig() { return terrain + "|" + String.join("+", haz) + "|" + String.join("+", en); }
    }

    static String hazardFamily(Element.Type k) {
        switch (k) {
            case SAW_H: case SAW_V: return "saw"; case PENDULUM: return "pendulum"; case CANNON: case AIMED: case MORTAR: return "projectile";
            case SPIKE_TRAP: return "spike_mat"; case SPIKE_BLOCK: return "spiked_block"; case SPIKE_DROP: return "crusher"; case AVALANCHE: return "avalanche";
            default: return null;
        }
    }
    static String enemyFamily(Element.Type k) {
        switch (k) { case CRAB: return "crab"; case BEE: return "bee"; case SKULL: return "skull"; case BLOB: return "blob"; case ENFORCER: return "enforcer"; default: return null; }
    }
    static final String[] TIER = {"enforcer_yellow", "enforcer_orange", "enforcer_red"};

    /** How the move is made (family, variant), from the take-off a and landing b. */
    static String[] terrain(Element a, Element b, double ratio) {
        switch (a.type) {
            case ROPE: return new String[]{"rope", "rope"};
            case CABLE: return new String[]{"cable", "cable"};
            case PAD: return new String[]{"trampoline", "trampoline"};
            case SPRING: return new String[]{"spring", "spring"};
            case SWING: return new String[]{"swing", a.crumbles() ? "swing_crumbling" : "swing"};
            default: break;
        }
        for (Element e : new Element[]{b, a}) {
            if (e.type == Element.Type.MOVE_H || e.type == Element.Type.MOVE_V || e.type == Element.Type.MOVE_Z || e.type == Element.Type.SWING)
                return new String[]{"moving", e.type.name().toLowerCase(Locale.ROOT) + (e.crumbles() ? "_crumbling" : "")};
        }
        for (Element e : new Element[]{a, b}) if (e.type == Element.Type.CRUMBLE || e.holed()) return new String[]{"crumbling", e.holed() ? "holed_bridge" : "crumble"};
        for (Element e : new Element[]{a, b}) {
            if (e.type == Element.Type.SEESAW) return new String[]{"seesaw", e.tiltBelt() ? "balance_conveyor" : "seesaw"};
            if (e.type == Element.Type.RAMP) return new String[]{"ramp", new String[]{"ramp", "ramp_crumbling", "ramp_shaking", "ramp_spinning", "ramp_conveyor"}[Math.max(0, Math.min(4, e.skin))]};
        }
        String v = a.belt() || b.belt() ? "plank_conveyor" : a.wet() || b.wet() ? "wet" : "plain";
        return ratio > 0.85 ? new String[]{"long_jump", "long_jump_" + v} : new String[]{"jump", "jump_" + v};
    }

    /** The run's links, classified, in climbing order. */
    static List<L> classify(LaneReport r) {
        Map<String, Element> byId = new HashMap<>();
        for (LaneReport.El e : r.elements) byId.put(e.id, e.e);
        Map<String, int[]> tierAt = new HashMap<>();          // "slice:platform" -> enforcer tiers reaching it
        for (LaneReport.El h : r.hazards) {
            if (h.e.type != Element.Type.ENFORCER || h.e.anchor < 0) continue;
            for (int p = h.e.anchor - 1; p < h.e.anchor + Math.round(h.e.len); p++) tierAt.computeIfAbsent(h.slice + ":" + p, x -> new int[3])[Math.max(0, Math.min(2, h.e.skin))]++;
        }
        List<L> out = new ArrayList<>();
        for (LaneReport.Link k : r.links) {
            Element a = byId.get(k.fromId), b = byId.get(k.toId);
            if (a == null || b == null) continue;
            L l = new L(); l.y0 = k.y0; l.y1 = k.y1; l.castle = LaneReport.castleOf(k.y0); l.window = LaneReport.wi(k.y0);
            String[] tv = terrain(a, b, k.ratio); l.terrain = tv[0]; l.tVariant = tv[1];
            for (Element.Type h : k.hazards) { String f = hazardFamily(h); if (f != null) { l.haz.add(f); l.hazV.add(h.name().toLowerCase(Locale.ROOT)); } }
            for (Element.Type e : k.enemies) { String f = enemyFamily(e); if (f != null) { l.en.add(f); if (e != Element.Type.ENFORCER) l.enV.add(f); } }
            for (String key : new String[]{k.slice + ":" + k.from, k.slice + ":" + k.to}) { int[] t = tierAt.get(key); if (t != null) for (int q = 0; q < 3; q++) if (t[q] > 0) l.enV.add(TIER[q]); }
            l.challenge = !l.haz.isEmpty() || !l.en.isEmpty() || k.terrain >= LaneReport.TERRAIN_DEMANDING;
            l.sig = l.sig();
            out.add(l);
        }
        return out;
    }

    static double entropy(Collection<String> xs) {
        Map<String, Integer> n = new HashMap<>(); for (String x : xs) n.merge(x, 1, Integer::sum);
        double h = 0, tot = xs.size(); for (int c : n.values()) { double p = c / tot; h -= p * Math.log(p) / Math.log(2); }
        return h;
    }
    static int longestRun(List<String> xs) { int best = 0, run = 0; String prev = null; for (String x : xs) { run = x.equals(prev) ? run + 1 : 1; best = Math.max(best, run); prev = x; } return best; }
    /** {evenness, novelty, run break-up, score} of a block of links, given the signatures of the block before it. */
    static double[] score(List<String> sigs, Set<String> before) {
        int n = sigs.size();
        if (n < 2) return new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        double even = entropy(sigs) / (Math.log(n) / Math.log(2));
        int fresh = 0; for (String s : sigs) if (!before.contains(s)) fresh++;
        double novel = before.isEmpty() ? Double.NaN : fresh / (double) n;
        double runs = 1.0 - (longestRun(sigs) - 1) / (double) (n - 1);
        double sc = Double.isNaN(novel) ? (even + runs) / 2 : (even + novel + runs) / 3;
        return new double[]{even, novel, runs, sc};
    }
    /** Three-link signature sequences that occur more than once in the block (how often an exact sequence repeats). */
    static int repeatedTrigrams(List<String> sigs) {
        Map<String, Integer> n = new HashMap<>();
        for (int i = 0; i + 2 < sigs.size(); i++) n.merge(sigs.get(i) + " > " + sigs.get(i + 1) + " > " + sigs.get(i + 2), 1, Integer::sum);
        int rep = 0; for (int c : n.values()) if (c > 1) rep += c - 1;
        return rep;
    }

    public static void write(LaneReport r, Path out) throws IOException {
        List<L> ls = classify(r);
        String run = LaneReport.MODE[r.mode] + "-s" + r.seed;
        TreeMap<Integer, List<L>> byW = new TreeMap<>(); for (L l : ls) byW.computeIfAbsent(l.window, x -> new ArrayList<>()).add(l);
        int maxW = byW.isEmpty() ? -1 : byW.lastKey();
        // ---- 25 m windows
        try (PrintWriter w = LaneReport.pw(out, "variety_windows.csv")) {
            w.println("run,window,y0,castle,links,challenge_links,unchallenged_links,challenge_density,terrain_families,hazard_families,enemy_families,terrain_variants,hazard_variants,enemy_variants,combined_links,repeat_of_recent,distinct_signatures,longest_signature_run,difficulty,terrain_set,hazard_set,enemy_set");
            List<String> recent = new ArrayList<>();
            for (int wi = 0; wi <= maxW; wi++) {
                List<L> win = byW.getOrDefault(wi, Collections.emptyList());
                TreeSet<String> tf = new TreeSet<>(), hf = new TreeSet<>(), ef = new TreeSet<>(), tv = new TreeSet<>(), hv = new TreeSet<>(), ev = new TreeSet<>(); Set<String> sigs = new HashSet<>(); List<String> order = new ArrayList<>();
                int ch = 0, comb = 0, rep = 0;
                for (L l : win) {
                    tf.add(l.terrain); hf.addAll(l.haz); ef.addAll(l.en); tv.add(l.tVariant); hv.addAll(l.hazV); ev.addAll(l.enV); sigs.add(l.sig); order.add(l.sig);
                    if (l.challenge) ch++;
                    int lanes = (l.haz.isEmpty() ? 0 : 1) + (l.en.isEmpty() ? 0 : 1) + (l.challenge && l.haz.isEmpty() && l.en.isEmpty() ? 1 : 0);
                    if (lanes >= 2 || l.haz.size() + l.en.size() >= 2) comb++;
                    if (recent.contains(l.sig)) rep++;
                    recent.add(l.sig); if (recent.size() > 3) recent.remove(0);
                }
                LaneReport.Win dw = r.windows.get(wi);
                w.println(String.join(",", run, "" + wi, LaneReport.f3(wi * LaneReport.WINDOW), "" + LaneReport.castleOf(wi * LaneReport.WINDOW), "" + win.size(), "" + ch, "" + (win.size() - ch),
                    LaneReport.f3(ch / (double) LaneReport.WINDOW), "" + tf.size(), "" + hf.size(), "" + ef.size(), "" + tv.size(), "" + hv.size(), "" + ev.size(), "" + comb, "" + rep, "" + sigs.size(), "" + longestRun(order),
                    dw == null ? "" : LaneReport.f3(dw.total()), LaneReport.q(String.join(" ", tf)), LaneReport.q(String.join(" ", hf)), LaneReport.q(String.join(" ", ev))));
            }
        }
        // ---- rolling 100 m
        List<double[]> rolling = new ArrayList<>();          // {window, score}
        try (PrintWriter w = LaneReport.pw(out, "variety_rolling.csv")) {
            w.println("run,window,y0,y1,castle,links,distinct_signatures,distinct_mechanics,evenness,novelty,run_breakup,variety_score,longest_signature_run,longest_terrain_run,repeated_trigrams,similar_to_previous_block,difficulty");
            for (int wi = 0; wi + 3 <= maxW; wi++) {
                List<String> sigs = new ArrayList<>(), terr = new ArrayList<>(); Set<String> before = new HashSet<>(), mech = new TreeSet<>(), prevMech = new TreeSet<>();
                double diff = 0;
                for (int q = wi; q < wi + 4; q++) {
                    for (L l : byW.getOrDefault(q, Collections.emptyList())) { sigs.add(l.sig); terr.add(l.terrain); mech.add("T:" + l.terrain); for (String h : l.haz) mech.add("H:" + h); for (String e : l.enV) mech.add("E:" + e); }
                    LaneReport.Win dw = r.windows.get(q); if (dw != null) diff += dw.total();
                }
                for (int q = wi - 4; q < wi; q++) for (L l : byW.getOrDefault(q, Collections.emptyList())) { before.add(l.sig); prevMech.add("T:" + l.terrain); for (String h : l.haz) prevMech.add("H:" + h); for (String e : l.enV) prevMech.add("E:" + e); }
                double[] s = score(sigs, before);
                Set<String> inter = new HashSet<>(mech); inter.retainAll(prevMech); Set<String> uni = new HashSet<>(mech); uni.addAll(prevMech);
                double jac = wi < 4 || uni.isEmpty() ? Double.NaN : inter.size() / (double) uni.size();
                rolling.add(new double[]{wi, s[3]});
                w.println(String.join(",", run, "" + wi, LaneReport.f3(wi * LaneReport.WINDOW), LaneReport.f3((wi + 4) * LaneReport.WINDOW), "" + LaneReport.castleOf(wi * LaneReport.WINDOW), "" + sigs.size(), "" + new HashSet<>(sigs).size(), "" + mech.size(),
                    LaneReport.f3(s[0]), LaneReport.f3(s[1]), LaneReport.f3(s[2]), LaneReport.f3(s[3]), "" + longestRun(sigs), "" + longestRun(terr), "" + repeatedTrigrams(sigs), LaneReport.f3(jac), LaneReport.f3(diff)));
            }
        }
        // ---- castle intervals
        TreeMap<Integer, List<L>> byC = new TreeMap<>(); for (L l : ls) byC.computeIfAbsent(l.castle, x -> new ArrayList<>()).add(l);
        StringBuilder castlesJson = new StringBuilder("[");
        try (PrintWriter w = LaneReport.pw(out, "variety_castles.csv")) {
            w.println("run,castle,links,challenge_share,unique_mechanics,terrain_families,hazard_families,enemy_families,terrain_variants,terrain_entropy,hazard_entropy,enemy_entropy,signature_entropy,mean_variety_score,difficulty,longest_signature_run,longest_terrain_run,longest_terrain_run_family,longest_unchallenged_m,repeated_trigrams,jaccard_vs_previous_castle,top_signatures,dominant_hazard_combination,enemy_distribution,terrain_distribution,hazard_distribution");
            Set<String> prevMech = null;
            for (Map.Entry<Integer, List<L>> e : byC.entrySet()) {
                int cs = e.getKey(); List<L> c = e.getValue();
                List<String> sigs = new ArrayList<>(), terr = new ArrayList<>(), haz = new ArrayList<>(), en = new ArrayList<>(); Set<String> mech = new TreeSet<>(), tf = new TreeSet<>(), hf = new TreeSet<>(), ef = new TreeSet<>(), tvs = new TreeSet<>();
                Map<String, Integer> sigN = new HashMap<>(), combo = new HashMap<>(), enN = new TreeMap<>(), tN = new TreeMap<>(), hN = new TreeMap<>();
                int ch = 0, bestRun = 0, run_ = 0; String bestFam = "", prevT = null; double unFrom = Double.NaN, longestUn = 0;
                for (L l : c) {
                    sigs.add(l.sig); terr.add(l.terrain); sigN.merge(l.sig, 1, Integer::sum); tf.add(l.terrain); tvs.add(l.tVariant); tN.merge(l.terrain, 1, Integer::sum);
                    mech.add("T:" + l.terrain); for (String h : l.haz) { mech.add("H:" + h); hf.add(h); haz.add(h); hN.merge(h, 1, Integer::sum); } for (String x : l.enV) { mech.add("E:" + x); en.add(x); enN.merge(x, 1, Integer::sum); } ef.addAll(l.en);
                    if (!l.haz.isEmpty()) combo.merge(String.join("+", l.haz), 1, Integer::sum);
                    run_ = l.terrain.equals(prevT) ? run_ + 1 : 1; prevT = l.terrain; if (run_ > bestRun) { bestRun = run_; bestFam = l.terrain; }
                    if (l.challenge) { ch++; unFrom = Double.NaN; } else { if (Double.isNaN(unFrom)) unFrom = l.y0; longestUn = Math.max(longestUn, l.y1 - unFrom); }
                }
                double vs = 0; int vn = 0; for (double[] q : rolling) if (LaneReport.castleOf(q[0] * LaneReport.WINDOW) == cs && !Double.isNaN(q[1])) { vs += q[1]; vn++; }
                double diff = 0; for (LaneReport.Win dw : r.windows.values()) if (LaneReport.castleOf(dw.index * LaneReport.WINDOW) == cs) diff += dw.total();
                List<Map.Entry<String, Integer>> top = new ArrayList<>(sigN.entrySet()); top.sort((x, y) -> y.getValue() != x.getValue().intValue() ? y.getValue() - x.getValue() : x.getKey().compareTo(y.getKey()));
                StringBuilder tops = new StringBuilder(); for (int i = 0; i < Math.min(3, top.size()); i++) tops.append(i > 0 ? "; " : "").append(top.get(i).getKey()).append(" x").append(top.get(i).getValue());
                String dom = ""; int domN = 0; for (Map.Entry<String, Integer> x : combo.entrySet()) if (x.getValue() > domN || (x.getValue() == domN && x.getKey().compareTo(dom) < 0)) { dom = x.getKey(); domN = x.getValue(); }
                double jac = Double.NaN;
                if (prevMech != null) { Set<String> i = new HashSet<>(mech); i.retainAll(prevMech); Set<String> u = new HashSet<>(mech); u.addAll(prevMech); jac = u.isEmpty() ? Double.NaN : i.size() / (double) u.size(); }
                prevMech = mech;
                w.println(String.join(",", run, "" + cs, "" + c.size(), LaneReport.f3(c.isEmpty() ? 0 : ch / (double) c.size()), "" + mech.size(), "" + tf.size(), "" + hf.size(), "" + ef.size(), "" + tvs.size(),
                    LaneReport.f3(entropy(terr)), LaneReport.f3(entropy(haz)), LaneReport.f3(entropy(en)), LaneReport.f3(entropy(sigs)), vn == 0 ? "" : LaneReport.f3(vs / vn), LaneReport.f3(diff),
                    "" + longestRun(sigs), "" + bestRun, bestFam, LaneReport.f3(longestUn), "" + repeatedTrigrams(sigs), LaneReport.f3(jac), LaneReport.q(tops.toString()), LaneReport.q(dom + (domN > 0 ? " x" + domN : "")),
                    LaneReport.q(enN.toString()), LaneReport.q(tN.toString()), LaneReport.q(hN.toString())));
                castlesJson.append(castlesJson.length() > 1 ? "," : "").append("{\"castle\":").append(cs).append(",\"variety\":").append(vn == 0 ? "null" : LaneReport.f3(vs / vn)).append(",\"difficulty\":").append(LaneReport.f3(diff)).append(",\"unique_mechanics\":").append(mech.size()).append('}');
            }
        }
        castlesJson.append(']');
        // ---- whole run
        Map<String, Integer> tN = new TreeMap<>(), hN = new TreeMap<>(), eN = new TreeMap<>(), tri = new HashMap<>();
        for (L l : ls) { tN.merge(l.terrain, 1, Integer::sum); for (String h : l.haz) hN.merge(h, 1, Integer::sum); for (String e : l.enV) eN.merge(e, 1, Integer::sum); }
        for (int i = 0; i + 2 < ls.size(); i++) tri.merge(ls.get(i).sig + " > " + ls.get(i + 1).sig + " > " + ls.get(i + 2).sig, 1, Integer::sum);
        List<Map.Entry<String, Integer>> tr = new ArrayList<>(tri.entrySet()); tr.removeIf(x -> x.getValue() < 2); tr.sort((x, y) -> y.getValue() != x.getValue().intValue() ? y.getValue() - x.getValue() : x.getKey().compareTo(y.getKey()));
        List<String> allSigs = new ArrayList<>(); for (L l : ls) allSigs.add(l.sig);
        StringBuilder j = new StringBuilder("{");
        j.append("\"run\":").append(LaneReport.js(run)).append(",\"links\":").append(ls.size());
        j.append(",\"signature_entropy\":").append(LaneReport.f3(entropy(allSigs))).append(",\"distinct_signatures\":").append(new HashSet<>(allSigs).size());
        j.append(",\"terrain\":").append(dist(tN, TERRAIN, ls.size())).append(",\"hazards\":").append(dist(hN, HAZARDS, ls.size())).append(",\"enemies\":").append(dist(eN, null, ls.size()));
        j.append(",\"repeated_sequences\":["); for (int i = 0; i < Math.min(10, tr.size()); i++) j.append(i > 0 ? "," : "").append("{\"sequence\":").append(LaneReport.js(tr.get(i).getKey())).append(",\"count\":").append(tr.get(i).getValue()).append('}');
        j.append("],\"castles\":").append(castlesJson).append(",\"ropes\":").append(ropes(r, ls)).append('}');
        try (PrintWriter w = LaneReport.pw(out, "variety_run.json")) { w.print(j); }
    }

    /** Family shares; over-represented: more than twice an even share of the families seen; under-represented: less than a quarter of it, or a
     *  registry family never seen (listed separately, as "absent"). */
    static String dist(Map<String, Integer> n, String[] registry, int links) {
        int tot = 0; for (int v : n.values()) tot += v;
        double even = n.isEmpty() ? 0 : 1.0 / n.size();
        StringBuilder b = new StringBuilder("{\"counts\":{"); int i = 0;
        for (Map.Entry<String, Integer> e : n.entrySet()) b.append(i++ > 0 ? "," : "").append(LaneReport.js(e.getKey())).append(':').append(e.getValue());
        b.append("},\"over\":["); i = 0; for (Map.Entry<String, Integer> e : n.entrySet()) if (tot > 0 && e.getValue() / (double) tot > 2 * even) b.append(i++ > 0 ? "," : "").append(LaneReport.js(e.getKey()));
        b.append("],\"under\":["); i = 0; for (Map.Entry<String, Integer> e : n.entrySet()) if (tot > 0 && e.getValue() / (double) tot < 0.25 * even) b.append(i++ > 0 ? "," : "").append(LaneReport.js(e.getKey()));
        b.append("],\"absent\":["); i = 0; if (registry != null) for (String f : registry) if (!n.containsKey(f)) b.append(i++ > 0 ? "," : "").append(LaneReport.js(f));
        return b.append("]}").toString();
    }

    /** Rope links: count, spacing in height between successive rope links, longest run of consecutive rope links, rope modules picked vs fallbacks. */
    static String ropes(LaneReport r, List<L> ls) {
        List<Double> gaps = new ArrayList<>(); double last = Double.NaN; int n = 0, run = 0, best = 0;
        for (L l : ls) {
            if (l.terrain.equals("rope")) { n++; run++; best = Math.max(best, run); if (!Double.isNaN(last)) gaps.add(l.y0 - last); last = l.y1; } else run = 0;
        }
        int pick = 0, fb = 0; for (String[] m : r.travMods) if ("ROPE".equals(m[1])) { if ("FALLBACK".equals(m[2])) fb++; else pick++; }
        double mean = 0, min = Double.NaN; for (double g : gaps) { mean += g; min = Double.isNaN(min) ? g : Math.min(min, g); } mean = gaps.isEmpty() ? Double.NaN : mean / gaps.size();
        int close = 0; for (double g : gaps) if (g < 10) close++;
        return "{\"rope_links\":" + n + ",\"rope_links_share\":" + LaneReport.f3(ls.isEmpty() ? 0 : n / (double) ls.size()) + ",\"mean_spacing_m\":" + LaneReport.num(LaneReport.f3(mean)) + ",\"min_spacing_m\":" + LaneReport.num(LaneReport.f3(min))
            + ",\"spacings_under_10m\":" + close + ",\"longest_consecutive_rope_links\":" + best + ",\"rope_modules_picked\":" + pick + ",\"rope_modules_fallback\":" + fb + "}";
    }
}
