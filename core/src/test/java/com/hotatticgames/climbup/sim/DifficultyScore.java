package com.hotatticgames.climbup.sim;

import java.util.*;

/**
 * Owner directive 2026-10-25 (Priority 1): a diagnostic, deterministic score of what generated courses actually ask of the climber. It never feeds the
 * generator. Three layers are kept apart: A weighted difficulty (signed: threats plus protective clubs), B distinct hazard families, C repetition
 * (diminishing returns for identical threats) and meaningful interactions (different enemy families that can reach the same route link).
 * Eligibility uses the generator's own reach rules ({@link EncounterModel#reaches}): an enemy that reaches no route platform is reported, not scored.
 * Windows are 25 m of height; an enemy is counted once, in the window of its home platform, and exposes every window its reach touches.
 */
public final class DifficultyScore {
    // ---- the one scoring table (owner-approved initial weights)
    public static float points(Element.Type k) {
        switch (k) {
            case CRAB: return 1f; case BLOB: return 2f; case SKULL: return 3f; case BEE: return 2f;
            case CLUB: return -1f;                                   // protective: reduces the consequence of other hazards
            default: return 0f;                                      // ropes, cables, plain jumps, pads and springs used to travel: 0
        }
    }
    public static boolean enemy(Element.Type k) { return k == Element.Type.CRAB || k == Element.Type.BLOB || k == Element.Type.SKULL || k == Element.Type.BEE; }
    /** Hazards that can set the climber back but have no approved weight yet: counted as families, never given points. */
    public static boolean unscoredHazard(Element.Type k) {
        switch (k) { case SAW_H: case SAW_V: case PENDULUM: case CANNON: case AIMED: case MORTAR: case SPIKE_TRAP: case SPIKE_BLOCK: case SPIKE_DROP: return true; default: return false; }
    }
    public static final float WINDOW = 25f, CASTLE_SPAN = 500f;
    /** Repetition: the first FULL identical threats in a window count fully, each further one TAIL of its weight. */
    public static final int FULL = 3; public static final float TAIL = 0.25f;
    /** Interaction: per route link reached by two or more different enemy families, capped per window. */
    public static final float INTERACTION = 0.5f, INTERACTION_CAP = 2f;
    /** A window whose adjusted net difficulty is below this is low-challenge. */
    public static final float LOW = 1f;

    /** Adjusted contribution of n identical threats of weight w (diminishing after FULL). */
    public static float repeated(int n, float w) { return w * (Math.min(n, FULL) + TAIL * Math.max(0, n - FULL)); }

    public static final class Window {
        public final int index; public float pos, prot, adjusted, interaction; public int exposure, unreachable, inaccessibleClubs;
        public final EnumMap<Element.Type, Integer> counts = new EnumMap<>(Element.Type.class), unscored = new EnumMap<>(Element.Type.class);
        Window(int index) { this.index = index; }
        public float net() { return pos + prot; }
        public float adjustedNet() { return adjusted + prot + interaction; }
        public int enemyFamilies() { int n = 0; for (Element.Type k : counts.keySet()) if (enemy(k)) n++; return n; }
        public int hazardFamilies() { return enemyFamilies() + unscored.size(); }
        public boolean low() { return adjustedNet() < LOW; }
        public boolean free() { return pos == 0f && unscored.isEmpty(); }
        public float y0() { return index * WINDOW; }
    }

    final TreeMap<Integer, Window> windows = new TreeMap<>();
    private final Set<String> seen = new HashSet<>();
    private final Tuning t;
    public DifficultyScore(Tuning t) { this.t = t; }
    public Collection<Window> windows() { return windows.values(); }
    private Window win(float y) { int i = (int) Math.floor(y / WINDOW); return windows.computeIfAbsent(i, Window::new); }
    private static boolean route(Course c, int i) { return i >= 0 && i < c.routeSize() && c.get(i).anchor < 0; }

    /** Adds one generated slice. Elements a slice shares with the previous one (its context) are counted once. */
    public void add(Course c) {
        int rs = c.routeSize();
        for (int hi = 0; hi < c.hazards.size(); hi++) {
            Element h = c.hazards.get(hi); Element.Type k = h.type;
            if (!enemy(k) && k != Element.Type.CLUB && !unscoredHazard(k)) continue;
            if (h.anchor < 0 || h.anchor >= c.size()) continue;
            float s = h.s % c.circumference; if (s < 0) s += c.circumference;
            if (!seen.add(k + ":" + Math.round(s * 100f) + ":" + Math.round(h.y * 100f))) continue;
            Element home = c.get(h.anchor); Window w = win(home.y);
            if (enemy(k)) {
                boolean any = false; Set<Integer> exposed = new TreeSet<>();
                for (int p = Math.max(0, h.anchor - 4); p <= Math.min(rs - 1, h.anchor + 4); p++)
                    if (route(c, p) && EncounterModel.reaches(c, t, h, p)) { any = true; exposed.add((int) Math.floor(c.get(p).y / WINDOW)); }
                if (!any) { w.unreachable++; continue; }
                w.counts.merge(k, 1, Integer::sum); w.pos += points(k);
                for (int wi : exposed) windows.computeIfAbsent(wi, Window::new).exposure++;
            } else if (k == Element.Type.CLUB) {
                if (route(c, h.anchor)) { w.counts.merge(k, 1, Integer::sum); w.prot += points(k); } else w.inaccessibleClubs++;
            } else w.unscored.merge(k, 1, Integer::sum);
        }
        // Layer C interactions: route links of this slice reached by two or more different enemy families
        for (int i = 0; i < rs - 1; i++) {
            if (!route(c, i)) continue;
            int j = c.nextRoute(i); if (j < 0 || j >= rs) continue;
            EnumSet<Element.Type> fam = EnumSet.noneOf(Element.Type.class);
            for (Element h : c.hazards) if (enemy(h.type) && h.anchor >= 0 && Math.abs(h.anchor - i) <= 4 && (EncounterModel.reaches(c, t, h, i) || EncounterModel.reaches(c, t, h, j))) fam.add(h.type);
            if (fam.size() < 2) continue;
            String key = "L:" + Math.round(c.get(i).y * 100f) + ":" + Math.round((c.get(i).s % c.circumference) * 100f);
            if (!seen.add(key)) continue;
            Window w = win(c.get(i).y); w.interaction = Math.min(INTERACTION_CAP, w.interaction + INTERACTION);
        }
        for (Window w : windows.values()) { float a = 0f; for (Map.Entry<Element.Type, Integer> e : w.counts.entrySet()) if (enemy(e.getKey())) a += repeated(e.getValue(), points(e.getKey())); w.adjusted = a; }
    }

    /** Mean, median, low-challenge share and longest low stretch (windows) over [y0, y1). */
    public float[] summary(float y0, float y1) {
        List<Float> v = new ArrayList<>(); int low = 0, run = 0, longest = 0;
        for (int i = (int) Math.floor(y0 / WINDOW); i < (int) Math.ceil(y1 / WINDOW); i++) {
            Window w = windows.get(i); float a = w == null ? 0f : w.adjustedNet(); v.add(a);
            boolean l = w == null || w.low(); if (l) { low++; run++; longest = Math.max(longest, run); } else run = 0;
        }
        if (v.isEmpty()) return new float[]{0, 0, 0, 0};
        float sum = 0; for (float x : v) sum += x; List<Float> s = new ArrayList<>(v); Collections.sort(s);
        return new float[]{sum / v.size(), s.get(s.size() / 2), 100f * low / v.size(), longest};
    }
}
