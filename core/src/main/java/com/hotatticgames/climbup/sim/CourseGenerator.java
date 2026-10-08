package com.hotatticgames.climbup.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Seeded, constraint-driven course generator. Every module is built from authored traversal patterns, then each
 * link is proven (and its tolerance measured) by {@link Autopilot} running the real simulation; links that are
 * not physically solvable with a minimum margin are re-planned easier. The result is an unbroken ascending spiral.
 */
public final class CourseGenerator {
    enum Kind { HOP, STAIRS, CRUMBLE, MOVER_H, MOVER_V, PAD, ROPE, CABLE, SWING, GRAB }

    private final Tuning T;
    private final Random rnd;
    private final Course c;
    public int rejected;       // modules re-planned easier (diagnostics)
    private Kind prev, prev2;
    private int rests;

    private CourseGenerator(long seed, Tuning t) {
        T = t; rnd = new Random(seed * 0x9E3779B97F4A7C15L + 12345); c = new Course(seed, t.circumference());
    }

    public static Course generate(long seed, Tuning t) {
        CourseGenerator g = new CourseGenerator(seed, t);
        g.build();
        lastForDebug = g;
        return g.c;
    }

    // ------------------------------------------------------------------ physics helpers

    /** Conservative horizontal reach (edge to edge) of a plain jump to a platform dy higher, without coyote/overhang bonuses. */
    float reach(float dy) {
        float v = T.jumpVel, g = T.gravity;
        float disc = v * v - 2 * g * dy;
        if (disc < 0) return 0;
        float t = (v + (float) Math.sqrt(disc)) / g;
        return T.runSpeed * 0.92f * t - 0.1f;
    }

    private float r(float lo, float hi) { return lo + (hi - lo) * rnd.nextFloat(); }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private Element plat(Element.Type type, float s, float y, float w, int zone) {
        Element e = new Element(type, s, y, w); e.zone = zone; return e;
    }

    // ------------------------------------------------------------------ build loop

    private void build() {
        Element start = plat(Element.Type.STATIC, 4f, 0f, 7f, 0);
        start.checkpoint = true;
        c.add(start);
        int sinceRest = 0;
        while (c.get(c.size() - 1).y < T.courseHeight - 4f) {
            Element last = c.get(c.size() - 1);
            float progress = Math.min(1f, last.y / T.courseHeight);
            int zone = Math.min(3, (int) (progress * 4f));
            float d = Math.min(1f, Math.max(0f, progress));
            if (sinceRest >= T.restEvery) { addRest(zone); sinceRest = 0; continue; }
            Kind k = pick(zone, last);
            if (!tryModule(k, d, zone)) {
                rejected++;
                boolean ok = false;
                for (Kind fb : new Kind[]{Kind.PAD, Kind.ROPE, Kind.MOVER_V, Kind.STAIRS, Kind.HOP}) { if (fb != k && tryModule(fb, 0.15f, zone)) { k = fb; ok = true; break; } }
                if (!ok) throw new IllegalStateException("cannot extend course at " + c.size() + " last=" + last.type + " s=" + last.s + " y=" + last.y + " below=" + below(last));
            }
            prev2 = prev; prev = k; sinceRest++;
        }
        addRest(3);
        // goal
        Element last = c.get(c.size() - 1);
        Element goal = plat(Element.Type.GOAL, last.s + last.w / 2 + 1.6f + 3f, last.y + 0.6f, 6f, 3);
        goal.checkpoint = true;
        if (!commit(listOf(goal), 0.0f)) throw new IllegalStateException("goal link failed");
        addDecoys();
    }

    // ------------------------------------------------------------------ decoys: dead ends and lures that mislead without ever blocking the route

    public int decoys, decoyTried, decoyGeoFail, decoyPlanFail;
    public static CourseGenerator lastForDebug;

    private void addDecoys() {
        c.finishRoute();
        Random dr = new Random(c.seed * 0x2545F4914F6CDD1DL + 99);
        int rs = c.routeSize();
        for (int a = 3; a < rs - 4; a++) {
            Element p = c.get(a);
            if (p.type != Element.Type.STATIC || p.w < 3f) continue;
            if (dr.nextFloat() > 0.30f + 0.08f * p.zone) continue;
            List<Element> es = buildDecoy(p, a, dr);
            if (tryDecoy(es, a)) decoys += es.size();
        }
    }

    /** Dead-end spurs (forward and gently down, or backward and up), crumbling lures, and unreachable "stepping stones" above earlier ground. */
    private List<Element> buildDecoy(Element p, int a, Random dr) {
        List<Element> l = new ArrayList<>();
        int kind = dr.nextInt(10);
        if (kind < 7) {
            boolean forward = kind < 4;
            float edge = forward ? p.s + p.w / 2f : p.s - p.w / 2f, y = p.y;
            int n = 2 + dr.nextInt(2);
            for (int k = 0; k < n; k++) {
                float dy = forward ? -(0.35f + dr.nextFloat() * 0.35f) : 0.3f + dr.nextFloat() * 0.8f;
                float gap = reach(Math.max(dy, 0f)) * (0.40f + 0.2f * dr.nextFloat());
                float w = k == n - 1 ? 1f : 1f + dr.nextInt(2);
                float s = forward ? edge + gap + w / 2f : edge - gap - w / 2f; y += dy;
                boolean trap = k == n - 1 && dr.nextInt(10) < 3;
                Element d = plat(trap ? Element.Type.CRUMBLE : Element.Type.STATIC, s, y, w, p.zone); d.anchor = a;
                l.add(d); edge = forward ? s + w / 2f : s - w / 2f;
            }
        } else {                                         // lures: scattered blocks that look like stepping stones but lead nowhere
            int n = 2 + dr.nextInt(2); float s = p.s - p.w / 2f - 3f;
            for (int k = 0; k < n; k++) {
                float w = 1f + dr.nextInt(2), yy = p.y + 3.2f + dr.nextFloat() * 2.4f;
                Element d = plat(Element.Type.STATIC, s - w / 2f, yy, w, p.zone); d.anchor = a;
                l.add(d); s -= w + 2.4f + dr.nextFloat() * 2.6f;
            }
        }
        return l;
    }

    private boolean decoyOk(Element d, int a) {
        int rs = c.routeSize();
        for (int i = 0; i < c.size(); i++) {
            Element e = c.get(i);
            if (e == d) continue;
            if (Math.abs(d.s - e.s) < c.circumference * 0.5f) {
                float gap = Math.abs(d.s - e.s) - d.w / 2f - arcHalf(e);
                if (gap < 1.0f && d.y - 0.6f < vHi(e) - 1.6f && d.y + 1.6f > vLo(e) + 0.8f) return false;   // overlapping / touching
            } else if (layersClash(c, d, e)) return false;
        }
        for (int j = a + 2; j < rs; j++) {                 // never a stepping stone toward later route
            Element e = c.get(j);
            if (!e.isPlatform()) continue;
            float gap = Math.max(0f, Math.abs(c.dsWrap(d.s, e.s)) - d.w / 2f - e.halfW());
            if (gap < 6.5f && e.y - d.y > -7f && e.y - d.y < 3.6f) return false;
        }
        return true;
    }

    private boolean tryDecoy(List<Element> es, int a) {
        int n0 = c.size();
        boolean ok = true; decoyTried++;
        for (Element d : es) { c.add(d); if (!decoyOk(d, a)) { ok = false; decoyGeoFail++; break; } }
        if (ok) {
            c.indexDecoys();
            int rs = c.routeSize();
            for (int r = Math.max(0, a - 7); r <= Math.min(rs - 2, a + 5) && ok; r++) {
                Autopilot.Result res = Autopilot.plan(Sim.startOn(c, T, r), r, false);
                if (!res.ok) { ok = false; decoyPlanFail++; }
            }
        }
        if (!ok) { while (c.size() > n0) c.elements.remove(c.size() - 1); c.indexDecoys(); }
        return ok;
    }

    private List<Element> listOf(Element... es) { List<Element> l = new ArrayList<>(); for (Element e : es) l.add(e); return l; }

    private void addRest(int zone) {
        for (int round = 0; round < 4; round++) {
            Element last = c.get(c.size() - 1);
            for (int attempt = 0; attempt < 8; attempt++) {
                float dy = r(0.3f, 1.0f) * (1f - attempt * 0.08f);
                float gap = reach(dy) * (0.5f - attempt * 0.03f);
                float w = attempt < 4 ? 5 + rnd.nextInt(2) : (attempt < 6 ? 4 : 3);
                Element e = plat(Element.Type.STATIC, last.s + last.w / 2 + gap + w / 2, last.y + dy, w, zone);
                rests++;
                e.checkpoint = (rests % Math.max(1, T.checkpointEveryRests)) == 0;
                if (commit(listOf(e), 0.12f)) return;
                rests--;
            }
            // no room at this height: climb first, then try to rest
            boolean climbed = false;
            for (Kind k : new Kind[]{Kind.PAD, Kind.STAIRS, Kind.ROPE, Kind.MOVER_V}) if (tryModule(k, 0.1f, zone)) { climbed = true; break; }
            if (!climbed) break;
        }
        throw new IllegalStateException("cannot place rest at " + c.size());
    }

    private String below(Element e) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.size(); i++) { Element f = c.get(i); if (Math.abs(e.s - f.s) > c.circumference * 0.5f && Math.abs(c.dsWrap(e.s, f.s)) < 14f) sb.append(String.format("[%d %s y=%.1f ds=%.1f] ", i, f.type, f.y, c.dsWrap(f.s, e.s))); }
        return sb.toString();
    }

    // ---- spiral-layer clearance: one revolution above/below must never intersect the current layer
    private static float arcHalf(Element e) {
        switch (e.type) {
            case MOVE_H: return e.w / 2f + e.amp;
            case SWING: return e.w / 2f + e.len * (float) Math.sin(e.amp);
            case ROPE: return 0.8f;
            default: return e.w / 2f;
        }
    }
    private static float vLo(Element e) {
        switch (e.type) { case ROPE: return e.y - e.len; case CABLE: return e.y - 2.4f; default: return e.y - 0.8f; }
    }
    private static float vHi(Element e) {
        switch (e.type) {
            case ROPE: return e.y + 0.6f; case CABLE: return e.y + 0.4f; case SWING: return e.y + e.len + 0.4f;
            case MOVE_V: return e.y + e.amp + 2.8f; default: return e.y + 2.8f;
        }
    }
    /** True if two elements from different spiral revolutions would intersect or allow a shortcut between layers. */
    public static boolean layersClash(Course c, Element e, Element f) {
        if (Math.abs(e.s - f.s) < c.circumference * 0.5f) return false;     // same revolution: handled by route order
        if (Math.abs(c.dsWrap(e.s, f.s)) > arcHalf(e) + arcHalf(f) + 2.5f) return false;
        if (vLo(e) - 1.5f < vHi(f) && vHi(e) + 1.5f > vLo(f)) return true;
        return e.isPlatform() && f.isPlatform() && Math.abs(e.y - f.y) < 9.5f;   // no trampoline shortcuts between layers
    }

    private boolean clearOfOtherLayers(int idx) {
        Element e = c.get(idx);
        for (int i = 0; i < idx - 3; i++) if (layersClash(c, e, c.get(i))) return false;
        return true;
    }

    /** Adds elements if every link (prefix last -> e1 -> ... -> eN) is solvable with the required margin. */
    private boolean commit(List<Element> es, float minMargin) {
        int n0 = c.size();
        for (Element e : es) c.add(e);
        boolean ok = true;
        for (int i = n0; i < c.size() && ok; i++) ok = clearOfOtherLayers(i);
        for (int i = n0 - 1; i < c.size() - 1 && ok; i++) {
            Sim sim = Sim.startOn(c, T, i);
            Autopilot.Result res = Autopilot.plan(sim, i, true);
            if (!res.ok || res.margin() < Math.max(minMargin, 0f)) ok = false;
        }
        if (!ok) { while (c.size() > n0) c.elements.remove(c.size() - 1); }
        return ok;
    }

    private Kind pick(int zone, Element last) {
        float[] w = new float[Kind.values().length];
        switch (zone) {
            case 0: w[Kind.HOP.ordinal()] = 4; w[Kind.STAIRS.ordinal()] = 2; w[Kind.CRUMBLE.ordinal()] = 1; w[Kind.PAD.ordinal()] = 1.5f; w[Kind.ROPE.ordinal()] = 1.5f; break;
            case 1: w[Kind.HOP.ordinal()] = 2; w[Kind.STAIRS.ordinal()] = 1; w[Kind.CRUMBLE.ordinal()] = 2; w[Kind.PAD.ordinal()] = 2; w[Kind.ROPE.ordinal()] = 2;
                    w[Kind.MOVER_H.ordinal()] = 2.5f; w[Kind.MOVER_V.ordinal()] = 1.5f; w[Kind.CABLE.ordinal()] = 1.5f; break;
            case 2: w[Kind.HOP.ordinal()] = 1.5f; w[Kind.CRUMBLE.ordinal()] = 2; w[Kind.PAD.ordinal()] = 2; w[Kind.ROPE.ordinal()] = 2; w[Kind.MOVER_H.ordinal()] = 2;
                    w[Kind.MOVER_V.ordinal()] = 2; w[Kind.CABLE.ordinal()] = 2; w[Kind.SWING.ordinal()] = 3; w[Kind.GRAB.ordinal()] = 2; break;
            default: w[Kind.HOP.ordinal()] = 1; w[Kind.CRUMBLE.ordinal()] = 2; w[Kind.PAD.ordinal()] = 2; w[Kind.ROPE.ordinal()] = 2; w[Kind.MOVER_H.ordinal()] = 2;
                    w[Kind.MOVER_V.ordinal()] = 2; w[Kind.CABLE.ordinal()] = 2; w[Kind.SWING.ordinal()] = 3; w[Kind.GRAB.ordinal()] = 3;
        }
        // keep the spiral pitch healthy: if climbing lags the arc travelled, prefer vertical modules
        // spiral pitch controller: track y = pitch * (arc travelled / circumference) so successive revolutions stay a fixed distance apart
        float guide = T.spiralPitch * (last.s - c.get(0).s) / c.circumference;
        float dev = last.y - guide;
        boolean lagging = dev < -2f, rushing = dev > 7f;
        for (Kind k : Kind.values()) {
            boolean vert = k == Kind.PAD || k == Kind.ROPE || k == Kind.MOVER_V || k == Kind.STAIRS;
            if (lagging && !vert) w[k.ordinal()] *= 0.12f;
            if (rushing && vert) w[k.ordinal()] *= 0.2f;
        }
        if (prev != null) { w[prev.ordinal()] *= 0.35f; if (prev == prev2) w[prev.ordinal()] = 0f; }
        float sum = 0; for (float f : w) sum += f;
        float x = rnd.nextFloat() * sum;
        for (Kind k : Kind.values()) { x -= w[k.ordinal()]; if (x <= 0 && w[k.ordinal()] > 0) return k; }
        return Kind.HOP;
    }

    private boolean tryModule(Kind k, float d, int zone) {
        Element last = c.get(c.size() - 1);
        for (int attempt = 0; attempt < 8; attempt++) {
            float dd = Math.max(0f, d * (1f - attempt * 0.14f));
            List<Element> es = build(k, last, dd, zone, attempt);
            float margin = k == Kind.GRAB ? Math.min(0.05f, T.minLinkMargin) : T.minLinkMargin;
            if (commit(es, margin)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ modules

    private List<Element> build(Kind k, Element last, float d, int z, int attempt) {
        List<Element> l = new ArrayList<>();
        float eR = last.s + last.w / 2f, y0 = last.y;
        Element.Type S = Element.Type.STATIC;
        switch (k) {
            case HOP: {
                float dy = z == 0 ? r(0f, 0.8f) : r(0f, 1.4f);
                float frac = lerp(0.45f, 0.92f, d) * r(0.9f, 1.1f);
                float w = 2 + rnd.nextInt(3);
                if (z == 3 && rnd.nextBoolean()) w = 1 + rnd.nextInt(2);
                l.add(plat(S, eR + reach(dy) * frac + w / 2, y0 + dy, w, z));
                break;
            }
            case STAIRS: {
                int n = 2 + rnd.nextInt(2);
                float s = eR, y = y0;
                for (int i = 0; i < n; i++) {
                    float dy = r(0.9f, 1.3f), w = i == n - 1 ? 2 + rnd.nextInt(2) : 1 + rnd.nextInt(2);
                    float gap = reach(dy) * lerp(0.4f, 0.7f, d);
                    s += gap + w / 2; y += dy; l.add(plat(S, s, y, w, z)); s += w / 2;
                }
                break;
            }
            case CRUMBLE: {
                int n = 2 + (d > 0.5f ? 1 : 0);
                float s = eR, y = y0;
                for (int i = 0; i < n; i++) {
                    float gap = lerp(0.7f, 1.8f, d) * r(0.9f, 1.1f), dy = r(0.2f, 0.5f);
                    s += gap + 1f; y += dy; l.add(plat(Element.Type.CRUMBLE, s, y, 2f, z)); s += 1f;
                }
                l.add(plat(S, s + 1.5f + 1.5f, y + r(0.2f, 0.6f), 3f, z));
                break;
            }
            case MOVER_H: {
                float amp = 1.4f + 1.0f * d, wm = 2f, gapIn = lerp(1.0f, 1.8f, d), gapOut = lerp(1.0f, 1.8f, d);
                Element m = plat(Element.Type.MOVE_H, eR + gapIn + amp + wm / 2, y0 + 0.3f, wm, z);
                m.amp = amp; m.period = r(3.2f, 4.4f); m.phase = r(0f, 6.28f);
                l.add(m);
                l.add(plat(S, m.s + amp + wm / 2 + gapOut + 1.5f, m.y + r(0.3f, 1.0f), 3f, z));
                break;
            }
            case MOVER_V: {
                float rise = 3.5f + 2f * d;
                Element m = plat(Element.Type.MOVE_V, eR + 1.0f + 1.5f, y0, 3f, z);
                m.amp = rise; m.period = r(5f, 6.5f); m.phase = r(0f, 6.28f);
                l.add(m);
                l.add(plat(S, m.s + 1.5f + 1.0f + 1.5f, y0 + rise, 3f, z));
                break;
            }
            case PAD: {
                int pads = (z >= 1 && d > 0.3f && rnd.nextBoolean()) ? 2 : 1;
                float s = eR, y = y0;
                for (int i = 0; i < pads; i++) {
                    float gap = lerp(1.0f, 2.0f, d) * r(0.9f, 1.1f);
                    s += gap + 1f; l.add(plat(Element.Type.PAD, s, y, 2f, z)); s += 1f;
                    y += r(3.6f, 5.0f) * (1f - attempt * 0.03f);
                    if (i < pads - 1) { /* next pad sits at the new height */ s += 0f; }
                }
                float gapOut = 1.0f + 1.2f * d;
                l.add(plat(S, s + gapOut + 1.5f, y, 3f, z));
                break;
            }
            case ROPE: {
                float gapR = lerp(1.4f, 2.6f, d), yBot = y0 + 0.4f, L = r(4f, 6f);
                Element rope = plat(Element.Type.ROPE, eR + gapR, yBot + L, 0f, z);
                rope.len = L;
                l.add(rope);
                float gapOut = lerp(1.0f, 2.4f, d);
                l.add(plat(S, rope.s + gapOut + 1.5f, rope.y - T.handHeight + 0.4f, 3f, z));
                break;
            }
            case CABLE: {
                float cy = y0 + 2.6f, span = lerp(5f, 8.5f, d), sA = eR + r(1.0f, 1.8f);
                Element cab = plat(Element.Type.CABLE, sA + span / 2, cy, span, z);
                l.add(cab);
                l.add(plat(S, sA + span - 0.6f + 1.5f, cy - T.handHeight - 0.25f, 3f, z));
                break;
            }
            case SWING: {
                float len = 5f, th = 0.5f, ext = len * (float) Math.sin(th), wm = 2f;
                float gapIn = lerp(1.0f, 1.8f, d), gapOut = lerp(1.0f, 1.8f, d);
                Element sw = plat(Element.Type.SWING, eR + gapIn + ext + wm / 2, y0 - 0.35f, wm, z);
                sw.len = len; sw.amp = th; sw.period = r(3.6f, 4.4f); sw.phase = r(0f, 6.28f);
                l.add(sw);
                l.add(plat(S, sw.s + ext + wm / 2 + gapOut + 1.5f, y0 + r(0.5f, 1.0f), 3f, z));
                break;
            }
            case GRAB: {
                float dy = r(0.7f, 1.3f);
                float frac = 1.28f - 0.06f * attempt;
                l.add(plat(S, eR + reach(dy) * frac + 1.5f, y0 + dy, 3f, z));
                break;
            }
        }
        return l;
    }
}
