package com.hotatticgames.climbup.sim;

import java.nio.file.*;
import java.util.*;

/**
 * T5 (owner master directive 2026-10-29, solver and physics validation). Diagnostics only: nothing here is used by the game or the generator.
 *
 * <ul>
 * <li>{@code reach TYPE...}: how high above a lower platform of each kind a climber really gets onto a platform of the NEXT spiral revolution (same
 * arc, offset along it), measured on the real 60 Hz Sim by an independent bounded input search (direction-only stick held at least 100 ms, run-up,
 * jump timing and hold, air steering, waits over a mover's period), against the solver ({@link Autopilot#linkExists}) and against the generator's
 * layer rule ({@link CourseGenerator#layersClash}). One CSV row per (kind, offset, height).</li>
 * <li>{@code ceiling}: whether a platform of the revolution above stops a jump from below (head or feet).</li>
 * <li>{@code parity MODE SEED SLICES}: on generated slices, every route link's planned move is replayed with the same inputs in the full world (every
 * element and hazard) and compared step by step with the windowed proof; the next link is planned again from the state the move really arrives in
 * (natural arrival) and from a clean start; and each link's move-set margin and timing window are recorded (execution tolerance).</li>
 * </ul>
 */
public class T5Probe {
    static Tuning T;
    static final StringBuilder OUT = new StringBuilder();
    static void row(String s) { OUT.append(s).append('\n'); }

    public static void main(String[] a) throws Exception {
        T = Tuning.parse(new String(Files.readAllBytes(Paths.get("../assets/data/tuning.json"))));
        Path out = Paths.get(a[a.length - 1]); Files.createDirectories(out);
        String part = a[0];
        long t0 = System.nanoTime();
        if (part.equals("reach")) { row("kind,offset,dy,generator_clash,search_reached,search_trials,solver_reached"); for (int i = 1; i < a.length - 1; i++) reach(a[i]); }
        else if (part.equals("ceiling")) ceiling();
        else if (part.equals("audit")) audit(Integer.parseInt(a[1]), Long.parseLong(a[2]), Integer.parseInt(a[3]));
        else if (part.equals("ctrl")) ctrl(Integer.parseInt(a[1]), Long.parseLong(a[2]), Float.parseFloat(a[3]));
        else if (part.equals("cf")) counterfactual(Integer.parseInt(a[1]), Long.parseLong(a[2]), Integer.parseInt(a[3]));
        else if (part.equals("parity")) parity(Integer.parseInt(a[1]), Long.parseLong(a[2]), Integer.parseInt(a[3]));
        String name = part + (a.length > 2 ? "-" + String.join("_", Arrays.copyOfRange(a, 1, a.length - 1)) : "");
        Files.write(out.resolve("t5-" + name + ".csv"), OUT.toString().getBytes());
        System.out.printf(Locale.ROOT, "T5 %s done in %.0f s%n", name, (System.nanoTime() - t0) / 1e9);
    }

    // ------------------------------------------------------------------ reach

    /** The lower platform of a kind, as the generator builds it (CourseGenerator.terrain), at arc 0, height 0. */
    static Element lower(String kind) {
        Element e;
        switch (kind) {
            case "STATIC": e = new Element(Element.Type.STATIC, 0f, 0f, 3f); break;
            case "PAD": e = new Element(Element.Type.PAD, 0f, 0f, 2f); break;
            case "SPRING": e = new Element(Element.Type.SPRING, 0f, 0f, 2f); e.amp = 0.47f; break;
            case "MOVE_V35": e = new Element(Element.Type.MOVE_V, 0f, 0f, 3f); e.amp = 3.5f; e.period = 5.75f; break;
            case "MOVE_V55": e = new Element(Element.Type.MOVE_V, 0f, 0f, 3f); e.amp = 5.5f; e.period = 5.75f; break;
            case "ELEV_FALL": e = new Element(Element.Type.MOVE_V, 0f, 0f, 3f); e.amp = 3.0f; e.period = 3.4f; e.skin = 1; break;
            case "MOVE_H": e = new Element(Element.Type.MOVE_H, 0f, 0f, 2f); e.amp = 2.4f; e.period = 3.8f; break;
            case "MOVE_Z": e = new Element(Element.Type.MOVE_Z, 0f, 0f, 3f); e.amp = 3.4f; e.period = 5.3f; break;
            case "SWING": e = new Element(Element.Type.SWING, 0f, 0f, 2f); e.len = 5f; e.amp = 0.5f; e.period = 4f; break;
            case "SEESAW": e = new Element(Element.Type.SEESAW, 0f, 0f, 7f); e.skin = 1; break;
            case "RAMP": case "RAMP_SHAKE": case "RAMP_SPIN": {
                float sc = 1.5f, L = 2.2f * sc; e = new Element(Element.Type.RAMP, 0f, 0f, L); e.amp = CourseGenerator.RAMP_SLOPE; e.len = sc;
                e.skin = kind.equals("RAMP") ? 0 : kind.equals("RAMP_SHAKE") ? 2 : 3; if (e.skin == 3) { e.period = 2.6f; } break; }
            case "SKULL": case "SPIKE_DROP": e = new Element(Element.Type.STATIC, 0f, 0f, 5f); break;
            default: throw new IllegalArgumentException(kind);
        }
        return e;
    }
    static boolean moving(Element e) { return e.isMoving() || e.type == Element.Type.RAMP && e.skin >= 2; }

    /** Lower platform (0), the target one revolution up (1), and for a pad or spring a plain deck to run onto it from (2, where the climb starts). */
    static Course fixture(String kind, float off, float dy) {
        Course c = new Course(1, T.circumference());
        Element lo = lower(kind); c.add(lo);
        c.add(new Element(Element.Type.STATIC, lo.s + T.circumference() + off, lo.y + dy, 3f));
        if (lo.type == Element.Type.PAD || lo.type == Element.Type.SPRING) c.add(new Element(Element.Type.STATIC, -3.2f, 0.1f, 4f));
        if (kind.equals("SKULL")) { Element h = new Element(Element.Type.SKULL, 0f, 0f, 5f); h.anchor = 0; h.phase = 1f; c.hazards.add(h); }
        if (kind.equals("SPIKE_DROP")) { Element h = new Element(Element.Type.SPIKE_DROP, 1f, 0f, 1.6f); h.anchor = 0; h.amp = 3.3f; h.period = 3.9f; c.hazards.add(h); }
        return c;
    }
    static int startIdx(Course c) { return c.size() > 2 ? 2 : 0; }

    static final float[] RUN = {0f, 0.1f, 0.2f, 0.35f, 0.5f, 0.8f}, HOLD = {0.1f, 0.25f, 0.6f};
    static int trials;

    /** The independent search: true if some input sequence in the family puts the climber standing on element 1 with no fall (hits allowed: a skull's launch is the point). */
    static boolean searchReaches(Course c) { return searchReaches(c, startIdx(c), 0, 1); }
    static boolean searchReaches(Course c, int start, int lowIdx, int target) {
        Element lo = c.get(lowIdx);
        float period = moving(lo) ? Math.max(lo.period, 2f) : 0f;
        int waits = period > 0f ? (int) Math.ceil(period / 0.4f) : 1;
        boolean launcher = lo.type == Element.Type.PAD || lo.type == Element.Type.SPRING || lo.type == Element.Type.SEESAW;
        Sim base = Sim.startOn(c, T, start); base.humanHold = Autopilot.HUMAN_HOLD; tgt = target; low = lowIdx; startI = start;
        for (int w = 0; w < waits; w++) for (int d1 = -1; d1 <= 1; d1++) for (float run : RUN) for (float hold : HOLD) for (int d2 = -1; d2 <= 1; d2++) for (int j0 = 0; j0 < (launcher ? 2 : 1); j0++) {
            if (d1 == 0 && run > 0f) continue;
            trials++;
            if (trial(base, w * 0.4f, d1, run, hold, d2, j0 == 1)) return true;
        }
        return false;
    }
    static boolean trial(Sim base, float wait, int d1, float run, float hold, int d2, boolean holdAll) {
        Sim m = base.copy(); int falls = m.falls;
        InputState in = new InputState();
        float tj = wait + run;
        int n = (int) ((tj + 4.5f) / Sim.DT);
        for (int i = 0; i < n; i++) {
            float tt = i * Sim.DT;
            in.clear(); in.moveY = 1f;
            if (tt >= wait && tt < tj) in.moveX = d1;
            else if (tt >= tj) in.moveX = d2;
            boolean held = holdAll || (tt >= tj && tt < tj + hold);
            in.jumpHeld = held; in.jumpPressed = held && (holdAll ? i == 0 || (tt >= tj && tt < tj + Sim.DT) : tt < tj + Sim.DT);
            m.step(in);
            if (m.falls != falls) return false;
            if (m.onElem == tgt && m.mode == Sim.Mode.GROUND || m.lastPad == tgt) return true;          // (a pad or spring target bounces: touching it counts)
            if (m.mode == Sim.Mode.GROUND && m.onElem >= 0 && m.onElem != tgt && m.onElem != low && m.onElem != startI) return false;          // a direct move only
        }
        return false;
    }
    static int tgt = 1, low = 0, startI = 0;

    static void reach(String kind) {
        for (float off : new float[]{0f, 1.5f, 3f, 4.5f, 6f, 7.5f, 9f}) {
            float best = -1f, bestSolver = -1f; int sinceLast = 0;
            for (float dy = 1f; dy <= 14.01f; dy += 0.25f) {
                Course c = fixture(kind, off, dy);
                boolean clash = CourseGenerator.layersClash(c, c.get(1), c.get(0));
                trials = 0; boolean r = searchReaches(c); int tr = trials;
                boolean sv = Autopilot.linkExists(c, T, startIdx(c), 1);
                row(String.format(Locale.ROOT, "%s,%.2f,%.2f,%b,%b,%d,%b", kind, off, dy, clash, r, tr, sv));
                if (r) best = dy; if (sv) bestSolver = dy;
                sinceLast = r || sv ? 0 : sinceLast + 1;
                if (sinceLast >= 12 && dy > 5f) break;          // 3 m with nothing reached by either: past the reach
            }
            System.out.printf(Locale.ROOT, "REACH %-10s offset %.1f: search max %.2f m, solver max %.2f m%n", kind, off, best, bestSolver);
        }
    }

    // ------------------------------------------------------------------ audit of generated courses

    /**
     * Every pair of platforms of two different revolutions in generated slices with the upper one 0.5-11 m higher and within 10 m of arc: can a climber on
     * the lower one (for a pad or spring, from the platform before it) get onto the upper one, by the search or the solver? Such a move skips the route
     * in between. The generator's own layer check accepted every pair here (they are in the course).
     */
    static Element cp(Element e) {
        Element n = new Element(e.type, e.s, e.y, e.w);
        n.zone = e.zone; n.amp = e.amp; n.period = e.period; n.phase = e.phase; n.len = e.len; n.dir = e.dir; n.color = e.color; n.skin = e.skin;
        return n;
    }
    static void audit(int mode, long seed, int slices) {
        Tuning t = mode == 0 ? T : T.hardCopy(mode); T = t;
        row("slice,lower,lower_type,upper,upper_type,ds,dy,route_skipped,generator_clash,search_reached,solver_reached");
        CourseGenerator.Framed f = null; int pairs = 0, hit = 0;
        Map<String, int[]> byType = new TreeMap<>();
        for (int k = 0; k < slices; k++) {
            f = CourseGenerator.chunkFramed(seed, k, f, t);
            Course c = f.c;
            for (int i = 0; i < c.size(); i++) {
                Element lo = c.get(i); if (!lo.isPlatform()) continue;
                for (int j = 0; j < c.size(); j++) {
                    Element up = c.get(j); if (j == i || !up.isPlatform()) continue;
                    if (Math.abs(lo.s - up.s) < c.circumference * 0.5f) continue;
                    float dy = up.y - lo.y, ds = c.dsWrap(up.s, lo.s);
                    if (dy < 0.5f || dy > 11f || Math.abs(ds) > 10f) continue;
                    pairs++;
                    // the pair on its own (plus the deck a pad or spring is run onto, and anything anchored on the lower one): a direct move, not the route between
                    boolean launcher = (lo.type == Element.Type.PAD || lo.type == Element.Type.SPRING) && i > 0;
                    Course m = new Course(1, c.circumference); m.add(cp(lo)); m.add(cp(up)); if (launcher) m.add(cp(c.get(i - 1)));
                    for (Element h : c.hazards) if (h.anchor == i) { Element hc = cp(h); hc.anchor = 0; m.hazards.add(hc); }
                    int start = launcher ? 2 : 0;
                    boolean sr = searchReaches(m, start, 0, 1), sv = Autopilot.linkExists(m, t, start, 1);
                    int[] bt = byType.computeIfAbsent(lo.type.toString(), x -> new int[2]); bt[0]++;
                    if (sr || sv) { hit++; bt[1]++; }
                    if (sr || sv) row(String.format(Locale.ROOT, "%d,%d,%s,%d,%s,%.2f,%.2f,%d,%b,%b,%b", k, i, lo.type, j, up.type, ds, dy, Math.max(0, j - i), CourseGenerator.layersClash(c, up, lo), sr, sv));          // (reachable pairs only; the totals are printed)
                }
            }
            System.out.printf(Locale.ROOT, "AUDIT mode %d seed %d slice %d: cross-revolution pairs within 11 m up / 10 m arc %d, reachable %d%n", mode, seed, k, pairs, hit);
        }
        for (Map.Entry<String, int[]> e : byType.entrySet()) System.out.printf(Locale.ROOT, "AUDIT mode %d seed %d lower %-8s pairs %5d reachable %4d%n", mode, seed, e.getKey(), e.getValue()[0], e.getValue()[1]);
    }

    // ------------------------------------------------------------------ Group 4: progression controller and placement opportunities

    /**
     * Group 4B/4D (owner testing program 2026-10-29): generates a course to {@code top} and prints, per castle, the controller's measured lanes against
     * the profile's targets and the factors it applied (Progression records), and the placement opportunities the route offered: route platforms,
     * plain decks a creature deck, trap or avalanche can use, platforms with a mechanic, route hazards and enemies, all per 100 m of climb.
     * Run it twice, with and without -Dclimb.r17OffSpacing=true, for the controlled spacing comparison.
     */
    static void ctrl(int mode, long seed, float top) {
        Tuning t = mode == 0 ? T : T.hardCopy(mode); T = t;
        DifficultyProfile P = DifficultyProfile.of(t);
        float sp = t.castleSpacing;
        row("castle,measured_T,measured_H,measured_E,measured_X,target_T,target_H,target_E,target_X,factor_T,factor_H,factor_E,factor_X,route_platforms_per100,plain_decks_per100,mechanic_platforms_per100,hazards_per100,enemies_per100,avg_rise_per_link");
        CourseGenerator.Framed f = null; int k = 0;
        double[][] opp = new double[12][7];          // per castle: route platforms, plain usable decks, mechanic platforms, hazards, enemies, rise sum, links
        double done = -1e9;
        while (f == null || f.yBase + f.c.get(f.c.routeSize() - 1).y < top) {
            f = CourseGenerator.chunkFramed(seed, k++, f, t);
            Course c = f.c; double lastY = done;
            for (int i = 1; i < c.routeSize(); i++) {
                Element e = c.get(i); double y = f.yBase + e.y; if (y <= done) continue;
                int n = Math.min(11, Progression.castleOf(y, sp));
                opp[n][0]++;
                boolean anchored = false; for (Element h : c.hazards) if (h.anchor == i) anchored = true;
                if (e.type == Element.Type.STATIC && !e.wet() && e.w >= 2.5f && !anchored) opp[n][1]++;
                if (Progression.demandingFooting(e) || e.type == Element.Type.PAD || e.type == Element.Type.SPRING || e.type == Element.Type.ROPE || e.type == Element.Type.CABLE) opp[n][2]++;
                for (Element h : c.hazards) if (h.anchor == i) { if (Progression.enemy(h.type)) opp[n][4]++; else if (Progression.hazard(h.type)) opp[n][3]++; }
                Element pr = c.get(i - 1); if (pr.anchor < 0) { opp[n][5] += e.y - pr.y; opp[n][6]++; }
                lastY = Math.max(lastY, y);
            }
            done = lastY;
        }
        Progression pg = Progression.of(f.c.prog);
        for (int i = 0; i < pg.completedCount(); i++) {
            float[] r = pg.completed(i); int n = (int) r[0]; if (n < 1 || n > 10) continue;
            double per = sp / 100.0;
            row(String.format(Locale.ROOT, "%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.3f,%.3f,%.3f,%.3f,%.2f,%.2f,%.2f,%.2f,%.2f,%.3f", n,
                r[1 + Progression.T], r[1 + Progression.H], r[1 + Progression.E], r[1 + Progression.X],
                P.target(n, Progression.T), P.target(n, Progression.H), P.target(n, Progression.E), P.target(n, Progression.X),
                r[1 + Progression.ACC], r[2 + Progression.ACC], r[3 + Progression.ACC], r[4 + Progression.ACC],
                opp[n][0] / per, opp[n][1] / per, opp[n][2] / per, opp[n][3] / per, opp[n][4] / per, opp[n][6] > 0 ? opp[n][5] / opp[n][6] : 0));
        }
        System.out.printf(Locale.ROOT, "CTRL mode %d seed %d: %d castles recorded, spacing off=%b%n", mode, seed, pg.completedCount(), CourseGenerator.R17_OFF_SPACING);
    }

    /**
     * Group 4C/4D first-divergence counterfactual (owner testing program 2026-10-29): along the rules 18 course, every slice is also built a second time
     * from the SAME previous slice (same pre-decision state, same random streams: generation is a pure function of seed, slice and previous slice) with
     * only the rules 17 spacing switched off. The first module attempt whose outcome differs is reported with both versions, and the slice's direct
     * consequences (route platforms, mechanics, enemies, hazards, avalanches, accepted creature and trap modules). The chain continues on the rules 18
     * slice, so no downstream random drift is mixed into the per-slice differences.
     */
    static void counterfactual(int mode, long seed, int slices) {
        Tuning t = mode == 0 ? T : T.hardCopy(mode); T = t;
        row("slice,first_div_module,a_kind,a_role,a_ok,a_fails,b_kind,b_role,b_ok,b_fails,a_route,b_route,a_mech,b_mech,a_enemies,b_enemies,a_hazards,b_hazards,a_avl,b_avl,a_creature_ok,b_creature_ok,a_trap_haz_ok,b_trap_haz_ok,a_creature_tried,b_creature_tried,a_attempts,b_attempts");
        CourseGenerator.Framed f = null;
        double[] sum = new double[16];
        for (int k = 0; k < slices; k++) {
            GenTrace ta = new GenTrace(), tb = new GenTrace();
            CourseGenerator.R17_OFF_SPACING = false; GenTrace.GEN.set(ta); CourseGenerator.Framed fa = CourseGenerator.chunkFramed(seed, k, f, t); GenTrace.GEN.remove();
            CourseGenerator.R17_OFF_SPACING = true; GenTrace.GEN.set(tb); CourseGenerator.Framed fb = CourseGenerator.chunkFramed(seed, k, f, t); GenTrace.GEN.remove();
            CourseGenerator.R17_OFF_SPACING = false;
            int div = -1, n = Math.min(ta.modules.size(), tb.modules.size());
            for (int i = 0; i < n && div < 0; i++) if (!sig(ta.modules.get(i)).equals(sig(tb.modules.get(i)))) div = i;
            if (div < 0 && ta.modules.size() != tb.modules.size()) div = n;
            String ma = div >= 0 && div < ta.modules.size() ? desc(ta.modules.get(div)) : ",,,", mb = div >= 0 && div < tb.modules.size() ? desc(tb.modules.get(div)) : ",,,";
            int[] sa = stats(fa.c, ta), sb = stats(fb.c, tb);
            StringBuilder st = new StringBuilder();
            for (int q = 0; q < 8; q++) { st.append(',').append(sa[q]).append(',').append(sb[q]); sum[2 * q] += sa[q]; sum[2 * q + 1] += sb[q]; }
            row(k + "," + div + "," + ma + "," + mb + st + "," + ta.attempt + "," + tb.attempt);
            f = fa;
        }
        String[] nm = {"route", "mechanic platforms", "enemies", "hazards", "avalanches", "creature modules ok", "trap/haz modules ok", "creature modules tried"};
        for (int q = 0; q < 8; q++) System.out.printf(Locale.ROOT, "CF mode %d seed %d %-22s rules18 %6.0f  spacing-off %6.0f  (%+.1f%%)%n", mode, seed, nm[q], sum[2 * q], sum[2 * q + 1], sum[2 * q] > 0 ? 100 * (sum[2 * q + 1] / sum[2 * q] - 1) : 0);
    }
    static String sig(GenTrace.Module m) { return m.attempt + "|" + m.kind + "|" + m.role + "|" + m.ok + "|" + Arrays.toString(m.fails); }
    static String desc(GenTrace.Module m) { return m.kind + "," + m.role + "," + m.ok + "," + Arrays.toString(m.fails).replace(",", " "); }
    /** route platforms, mechanic platforms, route enemies, route hazards, avalanches, accepted creature modules, accepted trap/haz modules, tried creature modules (final attempt). */
    static int[] stats(Course c, GenTrace tr) {
        int[] r = new int[8];
        for (int i = 0; i < c.routeSize(); i++) { Element e = c.get(i); r[0]++; if (Progression.demandingFooting(e) || e.type == Element.Type.PAD || e.type == Element.Type.SPRING || e.type == Element.Type.ROPE || e.type == Element.Type.CABLE) r[1]++; }
        for (Element h : c.hazards) { if (h.anchor < 0 || h.anchor >= c.routeSize()) continue; if (h.type == Element.Type.AVALANCHE) r[4]++; else if (Progression.enemy(h.type)) r[2]++; else if (Progression.hazard(h.type)) r[3]++; }
        for (GenTrace.Module m : tr.modules) { if (m.attempt != tr.finalAttempt) continue;
            if (m.kind.equals("CREATURE")) { r[7]++; if (m.ok) r[5]++; }
            if ((m.kind.equals("TRAP") || m.kind.equals("HAZ")) && m.ok) r[6]++; }
        return r;
    }

    // ------------------------------------------------------------------ ceiling

    static void ceiling() {
        row("deck_above_dy,apex_feet,stopped,landed_on_it");
        float free = apex(null);
        for (float h : new float[]{1.4f, 1.8f, 2.2f, 2.6f, 3.0f, 3.4f, 3.8f}) {
            Course c = new Course(1, T.circumference()); c.add(new Element(Element.Type.STATIC, 0f, 0f, 4f)); c.add(new Element(Element.Type.STATIC, T.circumference(), h, 3f));
            float ap = apex(c); boolean landed = lastLanded;
            row(String.format(Locale.ROOT, "%.2f,%.3f,%b,%b", h, ap, ap < free - 0.05f, landed));
            System.out.printf(Locale.ROOT, "CEILING deck %.1f m above (next revolution): jump apex %.2f m (free %.2f m)%s%n", h, ap, free, landed ? " - landed ON it from below" : "");
        }
    }
    static boolean lastLanded;
    static float apex(Course c) {
        if (c == null) { c = new Course(1, T.circumference()); c.add(new Element(Element.Type.STATIC, 0f, 0f, 4f)); }
        Sim m = Sim.startOn(c, T, 0); InputState in = new InputState(); float top = m.y; lastLanded = false;
        for (int i = 0; i < 90; i++) { in.clear(); in.jumpHeld = true; in.jumpPressed = i == 0; m.step(in); top = Math.max(top, m.y); if (m.onElem == 1 && m.mode == Sim.Mode.GROUND) lastLanded = true; }
        return top;
    }

    // ------------------------------------------------------------------ parity, natural arrival, tolerance

    static void parity(int mode, long seed, int slices) {
        Tuning t = mode == 0 ? T : T.hardCopy(mode); T = t;
        row("slice,link,from_type,to_type,hazards_near,planned,margin,window,steps,first_div_step,first_div_field,max_pos_dev,windowed_end,full_end,outcome_differs,clean_next_ok,natural_next_ok");
        CourseGenerator.Framed f = null;
        int links = 0, diverged = 0, outcome = 0, natOnly = 0, cleanOnly = 0, planned = 0;
        for (int k = 0; k < slices; k++) {
            f = CourseGenerator.chunkFramed(seed, k, f, t);
            Course c = f.c;
            for (int a = 0; a < c.routeSize() - 2; a++) {
                int b = c.nextRoute(a); if (b <= a || b >= c.routeSize()) continue;
                Sim base = Sim.startOn(c, t, a);
                Autopilot.Result r = Autopilot.plan(base, a, true);
                links++;
                String head = String.format(Locale.ROOT, "%d,%d,%s,%s,%d,%b,%.3f,%.3f", k, a, c.get(a).type, c.get(b).type, c.hazardsFor(a - 3, a + 3).length, r.ok, r.margin(), r.window);
                if (!r.ok || r.factory == null) { row(head + ",,,,,,,,"); continue; }
                planned++;
                Sim w = base.copy(); w.humanHold = Autopilot.HUMAN_HOLD; w.deferRespawn = false; w.setWindow(a - 1, a + 6);
                Sim fw = base.copy(); fw.humanHold = Autopilot.HUMAN_HOLD; fw.deferRespawn = false; fw.act = null; fw.hz = null; fw.winLo = 0; fw.winHi = Integer.MAX_VALUE;
                Autopilot.Policy p = r.factory.create();
                List<InputState> ins = new ArrayList<>(); List<float[]> snaps = new ArrayList<>();
                int arrived = -1;
                for (int n = 0; n < 1200; n++) {
                    InputState in = new InputState(); p.act(w, in); w.step(in);
                    InputState cp = new InputState(); cp.moveX = in.moveX; cp.moveY = in.moveY; cp.jumpHeld = in.jumpHeld; cp.jumpPressed = in.jumpPressed; cp.swingPressed = in.swingPressed; ins.add(cp);
                    snaps.add(new float[]{w.s, w.y, w.vx, w.vy, w.mode.ordinal(), w.onElem, w.hits, w.falls, w.lastPad});
                    if (w.mode == Sim.Mode.LEDGE || w.mode == Sim.Mode.PULLUP) p = PULL;
                    if (w.mode == Sim.Mode.GROUND && w.onElem > a && w.onElem < c.routeSize()) { arrived = w.onElem; break; }
                    if (w.falls != base.falls || w.hits != base.hits) break;
                }
                int div = -1; String field = ""; float maxDev = 0f;
                for (int i = 0; i < ins.size(); i++) {
                    fw.step(ins.get(i));
                    float[] sn = snaps.get(i);
                    String fd = diff(fw, sn);
                    if (fd != null && div < 0) { div = i; field = fd; }
                    maxDev = Math.max(maxDev, Math.abs(c.dsWrap(fw.s, sn[0])) + Math.abs(fw.y - sn[1]));
                }
                String we = end(w), fe = end(fw);
                boolean od = !we.equals(fe);
                if (div >= 0) diverged++; if (od) outcome++;
                // natural arrival: the next link planned from where this move really arrives (hazards in their phase), and from a clean start there
                String cn = "", nn = "";
                if (arrived >= 0 && c.nextRoute(arrived) > arrived && c.nextRoute(arrived) < c.routeSize()) {
                    boolean clean = Autopilot.plan(Sim.startOn(c, t, arrived), arrived, false).ok;
                    Sim nat = w.copy();
                    boolean natural = Autopilot.plan(nat, arrived, false).ok;
                    cn = "" + clean; nn = "" + natural;
                    if (clean && !natural) cleanOnly++; if (natural && !clean) natOnly++;
                }
                row(head + String.format(Locale.ROOT, ",%d,%d,%s,%.4f,%s,%s,%b,%s,%s", ins.size(), div, field, maxDev, we, fe, od, cn, nn));
            }
            System.out.printf(Locale.ROOT, "PARITY mode %d seed %d slice %d: %d links, %d planned, %d diverged (any field), %d outcome differs; next link clean-only %d, natural-only %d%n", mode, seed, k, links, planned, diverged, outcome, cleanOnly, natOnly);
        }
    }
    static String diff(Sim f, float[] w) {
        if (f.mode.ordinal() != (int) w[4]) return "mode";
        if (f.onElem != (int) w[5]) return "onElem";
        if (f.hits != (int) w[6]) return "hits";
        if (f.falls != (int) w[7]) return "falls";
        if (f.lastPad != (int) w[8]) return "lastPad";
        if (f.s != w[0] || f.y != w[1] || f.vx != w[2] || f.vy != w[3]) return "motion";
        return null;
    }
    static String end(Sim s) { return s.mode + ":" + s.onElem + ":h" + s.hits + ":f" + s.falls; }
    /** After a ledge catch: up held, as the proofs do (Autopilot.finishArrival). */
    static final Autopilot.Policy PULL = (sim, in) -> in.moveY = 1f;
}
