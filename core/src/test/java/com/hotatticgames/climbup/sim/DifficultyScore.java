package com.hotatticgames.climbup.sim;

import java.util.*;

/**
 * Owner directive 2026-10-25 (Priority 1): a diagnostic, deterministic score of what generated courses actually ask of the climber. It never feeds the
 * generator. Three layers are kept apart: A weighted difficulty (signed: threats plus protective clubs), B distinct hazard families, C repetition
 * (diminishing returns for identical threats) and meaningful interactions (different enemy families that can reach the same route link).
 * Eligibility uses the generator's own reach rules ({@link EncounterModel#reaches}): an enemy that reaches no route platform is reported, not scored.
 * Windows are 25 m of height; an enemy is counted once, in the window of its home platform, and exposes every window its reach touches.
 * Static hazards (saws, pendulums, cannons, mortars, spikes) score only when anchored on a route platform, the same eligibility as
 * {@link EncounterModel#link} (a hazard threatens a link only from either of its two platforms); off-route ones are counted, not scored.
 * Weights are provisional and configurable: {@code difficulty_weights.properties} on the test classpath overrides the defaults below.
 */
public final class DifficultyScore {
    // ---- the one scoring table. Owner-approved: crab +1, blob +2, skull +3, bee +2, club -1; ropes, cables, plain jumps, pads and springs used to
    // travel 0. Provisional (2026-10-25 correction), from each hazard's behaviour in Sim and its EncounterModel wait/recover cost: a moving blade
    // (saws, pendulum) or a projectile you must time (aimed cannon, mortar) or a falling spike knocks you off the route: +2; a fixed-line cannon,
    // a timed spike mat and a static spiked block are predictable once read: +1.
    static final EnumMap<Element.Type, Float> WEIGHTS = new EnumMap<>(Element.Type.class);
    static {
        Object[][] d = {{Element.Type.CRAB, 1f}, {Element.Type.BLOB, 2f}, {Element.Type.SKULL, 3f}, {Element.Type.BEE, 2f}, {Element.Type.CLUB, -1f},
            {Element.Type.SAW_H, 2f}, {Element.Type.SAW_V, 2f}, {Element.Type.PENDULUM, 2f}, {Element.Type.CANNON, 1f}, {Element.Type.AIMED, 2f},
            {Element.Type.MORTAR, 2f}, {Element.Type.SPIKE_TRAP, 1f}, {Element.Type.SPIKE_BLOCK, 1f}, {Element.Type.SPIKE_DROP, 2f}};
        for (Object[] e : d) WEIGHTS.put((Element.Type) e[0], (Float) e[1]);
        try (java.io.InputStream in = DifficultyScore.class.getResourceAsStream("/difficulty_weights.properties")) {
            if (in != null) { Properties p = new Properties(); p.load(in); for (String k : p.stringPropertyNames()) WEIGHTS.put(Element.Type.valueOf(k.trim()), Float.parseFloat(p.getProperty(k).trim())); }
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    public static float points(Element.Type k) { Float v = WEIGHTS.get(k); return v == null ? 0f : v; }
    public static boolean enemy(Element.Type k) { return k == Element.Type.CRAB || k == Element.Type.BLOB || k == Element.Type.SKULL || k == Element.Type.BEE; }
    /** The nine static hazards: scored when they sit on a route platform. */
    public static boolean hazard(Element.Type k) {
        switch (k) { case SAW_H: case SAW_V: case PENDULUM: case CANNON: case AIMED: case MORTAR: case SPIKE_TRAP: case SPIKE_BLOCK: case SPIKE_DROP: return true; default: return false; }
    }
    public static boolean threat(Element.Type k) { return enemy(k) || hazard(k); }
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
        public final int index; public float pos, prot, adjusted, interaction; public int exposure, unreachable, inaccessibleClubs, offRouteHazards;
        /** Raw counts of scored threats and clubs (adjusted, repetition-capped values are kept apart in {@link #adjusted}). */
        public final EnumMap<Element.Type, Integer> counts = new EnumMap<>(Element.Type.class);
        Window(int index) { this.index = index; }
        public float net() { return pos + prot; }
        public float adjustedNet() { return adjusted + prot + interaction; }
        public int enemyFamilies() { int n = 0; for (Element.Type k : counts.keySet()) if (enemy(k)) n++; return n; }
        public int staticFamilies() { int n = 0; for (Element.Type k : counts.keySet()) if (hazard(k)) n++; return n; }
        public int hazardFamilies() { return enemyFamilies() + staticFamilies(); }
        public boolean low() { return adjustedNet() < LOW; }
        public boolean free() { return pos == 0f; }
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
    public void add(Course c) { add(c, 0f); }
    /** Adds a slice generated in its own frame ({@link CourseGenerator#chunkFramed}): absolute height = yOff + local y. */
    public void add(Course c, float yOff) {
        int rs = c.routeSize();
        for (int hi = 0; hi < c.hazards.size(); hi++) {
            Element h = c.hazards.get(hi); Element.Type k = h.type;
            if (!threat(k) && k != Element.Type.CLUB) continue;
            if (h.anchor < 0 || h.anchor >= c.size()) continue;
            float s = h.s % c.circumference; if (s < 0) s += c.circumference;
            if (!seen.add(k + ":" + Math.round(s * 100f) + ":" + Math.round((h.y + yOff) * 100f))) continue;
            Element home = c.get(h.anchor); Window w = win(home.y + yOff);
            if (enemy(k)) {
                boolean any = false; Set<Integer> exposed = new TreeSet<>();
                for (int p = Math.max(0, h.anchor - 4); p <= Math.min(rs - 1, h.anchor + 4); p++)
                    if (route(c, p) && EncounterModel.reaches(c, t, h, p)) { any = true; exposed.add((int) Math.floor((c.get(p).y + yOff) / WINDOW)); }
                if (!any) { w.unreachable++; continue; }
                w.counts.merge(k, 1, Integer::sum); w.pos += points(k);
                for (int wi : exposed) windows.computeIfAbsent(wi, Window::new).exposure++;
            } else if (k == Element.Type.CLUB) {
                if (route(c, h.anchor)) { w.counts.merge(k, 1, Integer::sum); w.prot += points(k); } else w.inaccessibleClubs++;
            } else if (route(c, h.anchor)) { w.counts.merge(k, 1, Integer::sum); w.pos += points(k); } else w.offRouteHazards++;
        }
        // Layer C interactions: route links of this slice threatened by two or more different families (enemies that reach either platform,
        // static hazards sitting on either platform)
        for (int i = 0; i < rs - 1; i++) {
            if (!route(c, i)) continue;
            int j = c.nextRoute(i); if (j < 0 || j >= rs) continue;
            EnumSet<Element.Type> fam = EnumSet.noneOf(Element.Type.class);
            for (Element h : c.hazards) {
                if (h.anchor < 0) continue;
                if (enemy(h.type) ? Math.abs(h.anchor - i) <= 4 && (EncounterModel.reaches(c, t, h, i) || EncounterModel.reaches(c, t, h, j)) : hazard(h.type) && (h.anchor == i || h.anchor == j)) fam.add(h.type);
            }
            if (fam.size() < 2) continue;
            String key = "L:" + Math.round((c.get(i).y + yOff) * 100f) + ":" + Math.round((c.get(i).s % c.circumference) * 100f);
            if (!seen.add(key)) continue;
            Window w = win(c.get(i).y + yOff); w.interaction = Math.min(INTERACTION_CAP, w.interaction + INTERACTION);
        }
        for (Window w : windows.values()) { float a = 0f; for (Map.Entry<Element.Type, Integer> e : w.counts.entrySet()) if (threat(e.getKey())) a += repeated(e.getValue(), points(e.getKey())); w.adjusted = a; }
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
