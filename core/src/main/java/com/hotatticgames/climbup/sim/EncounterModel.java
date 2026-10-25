package com.hotatticgames.climbup.sim;

import java.util.Locale;

/**
 * Owner directive 2026-10-24 (master generator directive, sections 7 and 10-13): what each route link asks of the player, and how hard the whole encounter is.
 * <p>
 * Each link (route element i to the next route element) is described by the demands its mechanics make, not by their names:
 * <ul>
 *   <li><b>pursue</b>: creatures that come after the player here (crab, skull, blob, bee), counted with their roaming reach. Several
 *       pursuers make more than the sum of them (their attacks overlap).</li>
 *   <li><b>wait</b>: the jump has to be synchronised with a cycle: a moving or swinging target, a timed spike, a saw, cannon, aimed
 *       cannon or mortar on the link.</li>
 *   <li><b>position</b>: the take-off needs a particular spot or stance: a narrow or moving deck, a belt, a seesaw or ramp, or a jump
 *       close to the longest one possible.</li>
 *   <li><b>commit</b>: footing that will not wait (crumbling platforms and ramps, plank holes, ice).</li>
 *   <li><b>recover</b>: hits that knock the player back (cannons, aimed cannons, mortars, saws, spikes).</li>
 * </ul>
 * The encounter score adds the demands and the interactions that make them fight each other. The largest is pursuit while having to wait
 * or hold a position: the creatures demand movement, the timing demands staying put. The score is a deterministic, inspectable estimate
 * of difficulty; it is not proof that a person can do it. {@link Link#conflict} marks where demands become incompatible: several
 * pursuers on a link that also needs waiting from a cramped take-off. The generator moves such encounters apart rather than dropping the
 * mechanics.
 * <p>
 * The weights are first estimates, documented in docs/GENERATOR_AUDIT.md, to be calibrated against playtests.
 */
public final class EncounterModel {
    private EncounterModel() {}

    // weights (first estimates; see the class comment)
    static final float W_CRAB = 1.0f, W_SKULL = 1.2f, W_BLOB = 1.0f, W_BEE = 0.8f;
    static final float OVERLAP = 0.25f;                  // each extra pursuer adds this much of the others again
    static final float X_PURSUE_WAIT = 1.5f, X_PURSUE_POSITION = 1.0f, X_WAIT_POSITION = 0.5f, X_PURSUE_COMMIT = 0.5f;

    /** One route link's demands and score, with the reasons, for inspection. */
    public static final class Link {
        public int from;
        public float jump, pursue, wait, position, commit, recover, score;
        public int pursuers;
        public boolean conflict;
        public final StringBuilder why = new StringBuilder();
        @Override public String toString() {
            return String.format(Locale.ROOT, "link %d score %.2f (jump %.2f pursue %.2f wait %.2f position %.2f commit %.2f recover %.2f%s) %s",
                from, score, jump, pursue, wait, position, commit, recover, conflict ? " CONFLICT" : "", why);
        }
    }

    /** The demands of the link out of route element i (to {@code c.nextRoute(i)}). */
    public static Link link(Course c, Tuning t, int i) {
        Link l = new Link(); l.from = i;
        int j = c.nextRoute(i);
        if (j < 0 || j >= c.size()) return l;
        Element a = c.get(i), b = c.get(j);
        // the jump itself: how close the gap comes to the longest jump at that height difference
        if (a.isPlatform() && b.isPlatform()) {
            float gap = Math.max(0f, Math.abs(c.dsWrap(b.s, a.s)) - a.halfW() - b.halfW());
            float reach = reach(t, b.y - a.y);
            l.jump = reach > 0f ? Math.min(1.2f, gap / reach) : 1.2f;
            if (l.jump > 0.85f) { l.position += (l.jump - 0.85f) * 3f; note(l, "long jump"); }
        }
        if (a.isPlatform() && a.w < 2.6f) { l.position += 0.5f; note(l, "narrow take-off"); }
        if (a.isMoving()) { l.position += 0.5f; note(l, "moving take-off"); }
        if (a.type == Element.Type.SEESAW || a.type == Element.Type.RAMP) { l.position += 0.6f; note(l, a.type.name().toLowerCase(Locale.ROOT)); }
        if (b.isMoving() || b.type == Element.Type.SWING) { l.wait += 0.8f; note(l, "moving target"); }
        if (a.crumbles() || a.holed()) { l.commit += 0.7f; note(l, "crumbling footing"); }
        if (a.wet()) { l.commit += 0.4f; note(l, "slippery"); }
        float pursueRaw = 0f; int n = 0;
        for (Element h : c.hazards) {
            if (h.anchor < 0) continue;
            int reach = roamReach(h);
            if (reach >= 0) {
                if (h.anchor >= i - reach && h.anchor <= j + reach) { pursueRaw += weight(h.type); n++; }
                continue;
            }
            if (h.anchor != i && h.anchor != j) continue;
            switch (h.type) {
                case SAW_H: case SAW_V: case PENDULUM: l.wait += 0.6f; l.recover += 0.4f; note(l, h.type.name().toLowerCase(Locale.ROOT)); break;
                case SPIKE_TRAP: case SPIKE_DROP: l.wait += 0.6f; l.recover += 0.3f; note(l, "timed spikes"); break;
                case SPIKE_BLOCK: l.position += 0.3f; note(l, "spiked block"); break;
                case CANNON: l.wait += 0.5f; l.recover += 0.5f; note(l, "cannon"); break;
                case AIMED: l.wait += 0.6f; l.recover += 0.6f; note(l, "aimed cannon"); break;
                case MORTAR: l.wait += 0.5f; l.recover += 0.5f; note(l, "mortar"); break;
                default: break;
            }
        }
        if (n > 0) { l.pursue = pursueRaw * (1f + OVERLAP * (n - 1)); l.pursuers = n; note(l, n + " pursuer" + (n > 1 ? "s" : "")); }
        float base = l.jump + l.pursue + l.wait + l.position + l.commit + l.recover;
        float inter = X_PURSUE_WAIT * l.pursue * l.wait + X_PURSUE_POSITION * l.pursue * l.position + X_WAIT_POSITION * l.wait * l.position + X_PURSUE_COMMIT * l.pursue * l.commit;
        l.score = base + inter;
        l.conflict = l.pursuers >= 2 && l.wait > 0f && (l.position > 0f || (a.isPlatform() && a.w < 4f));
        return l;
    }

    /** The encounter around a link: its own score plus half of each neighbour's (pressure carries over between links). */
    public static float window(Course c, Tuning t, int i) {
        float s = link(c, t, i).score;
        int prev = i - 1;
        while (prev >= 0 && c.get(prev).anchor >= 0) prev--;
        if (prev >= 0) s += 0.5f * link(c, t, prev).score;
        int next = c.nextRoute(i);
        if (next >= 0 && next < c.routeSize() - 1) s += 0.5f * link(c, t, next).score;
        return s;
    }

    // ---- NORMAL threat budget (Build 80 diagnostics, 2026-10-25): what can really reach a take-off, not only what is placed on the link

    /** NORMAL's budget for the weighted pursuit that may reach one link (see {@link DifficultyProfile#threatBudget}): base, growth over the 10 levels, share from a cramped take-off. */
    static final float THREAT_BASE = 5.0f, THREAT_PER_LEVEL = 6.0f, THREAT_CRAMPED = 0.75f;

    /**
     * True if roaming creature {@code h} can reach route platform {@code p}: a crab follows the climber up to {@link Sim#CRAB_FOLLOW} platforms, a
     * skull or green blob reaches the platforms either side of its own, and a bee seeks a climber within {@link Element#BEE_SEEK} of its post (4 m below
     * to 3 m above it). Anything else does not roam.
     */
    static boolean reaches(Course c, Element h, int p) {
        if (h.anchor < 0 || p < 0 || p >= c.routeSize()) return false;
        Element e = c.get(p);
        switch (h.type) {
            case CRAB: return Math.abs(h.anchor - p) <= Sim.CRAB_FOLLOW;
            case SKULL: case BLOB: return Math.abs(h.anchor - p) <= 1;
            case BEE: { float d = Math.abs(c.dsWrap(e.s, h.s)) - e.halfW(), dy = e.y - h.y; return h.anchor == p || (d <= Element.BEE_SEEK && dy > -4f && dy < 3f); }
            default: return false;
        }
    }

    /** Weighted pursuit that can reach route platform {@code p}, with the same overlap growth as {@link #link}. */
    public static float threatAt(Course c, Tuning t, int p) {
        float raw = 0f; int n = 0;
        for (Element h : c.hazards) if (roamReach(h) >= 0 && reaches(c, h, p)) { raw += weight(h.type); n++; }
        return n == 0 ? 0f : raw * (1f + OVERLAP * (n - 1));
    }

    /** Weighted pursuit that can reach the take-off or the landing of the link out of route element i (each creature counted once). */
    public static float threat(Course c, Tuning t, int i) {
        int j = c.nextRoute(i);
        float raw = 0f; int n = 0;
        for (Element h : c.hazards) if (roamReach(h) >= 0 && (reaches(c, h, i) || reaches(c, h, j))) { raw += weight(h.type); n++; }
        return n == 0 ? 0f : raw * (1f + OVERLAP * (n - 1));
    }

    /** The take-off asks for footing: a narrow or moving platform, or one carrying a spiked block or timed spikes. */
    public static boolean cramped(Course c, int i) {
        Element a = c.get(i);
        if (a.isMoving() || (a.isPlatform() && a.w < 2.6f)) return true;
        for (Element h : c.hazards) if (h.anchor == i && (h.type == Element.Type.SPIKE_BLOCK || h.type == Element.Type.SPIKE_TRAP || h.type == Element.Type.SPIKE_DROP)) return true;
        return false;
    }

    static int roamReach(Element h) {
        switch (h.type) { case CRAB: return Sim.CRAB_FOLLOW; case SKULL: case BLOB: return 1; case BEE: return 0; default: return -1; }
    }
    static float weight(Element.Type k) {
        switch (k) { case CRAB: return W_CRAB; case SKULL: return W_SKULL; case BLOB: return W_BLOB; case BEE: return W_BEE; default: return 0f; }
    }
    static float reach(Tuning t, float dy) {
        float v = t.jumpVel, g = t.gravity, disc = v * v - 2 * g * dy;
        if (disc < 0) return 0f;
        return t.runSpeed * (v + (float) Math.sqrt(disc)) / g;
    }
    private static void note(Link l, String s) { if (l.why.length() > 0) l.why.append(", "); l.why.append(s); }
}
