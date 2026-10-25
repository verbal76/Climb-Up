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
            if (roamReach(h) >= 0) {
                if (reaches(c, t, h, i) || reaches(c, t, h, j)) { pursueRaw += weight(h.type); n++; }
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
     * True if roaming creature {@code h} can reach route platform {@code p} under the simulation's own movement rules: a crab follows the climber forward
     * up to {@link Sim#CRAB_FOLLOW} platforms ({@link #crabReaches}), a skull or green blob reaches the platforms either side of its own
     * ({@link #neighbourReaches}), and a bee drifts within its envelope ({@link #beeReaches}). Anything else does not roam.
     */
    static boolean reaches(Course c, Tuning t, Element h, int p) {
        if (h.anchor < 0 || p < 0 || p >= c.routeSize()) return false;
        Element e = c.get(p);
        switch (h.type) {
            case CRAB: return crabReaches(c, h.anchor, p);
            case SKULL: case BLOB: return neighbourReaches(c, h.anchor, p);
            case BEE: return beeReaches(c, t, h, p);
            default: return false;
        }
    }

    // Owner decision A (2026-10-25): a creature threatens only the platforms the simulation lets it move onto (Sim.roamDeck, Sim.canHop, Element.ROAM_DY),
    // read from the course as generated (fixed decks: the only kind a creature may move onto, so their generated place is their live place).

    /** Sim.roamDeck on the generated course: a fixed, flat route platform (no mover, ramp or seesaw); skulls and blobs also not a crumbling one. */
    static boolean roamDeck(Course c, int j, boolean crab) {
        if (j < 0 || j >= c.routeSize()) return false;
        Element d = c.get(j);
        if (!d.isPlatform() || d.isMoving() || d.type == Element.Type.RAMP || d.type == Element.Type.SEESAW || d.anchor >= 0) return false;
        return crab || !d.crumbles();
    }
    /** Sim.canHop: a crab hops from deck j to deck k across at most CRAB_HOP_GAP and up or down at most CRAB_HOP_DY. */
    static boolean crabHop(Course c, int j, int k) {
        if (!roamDeck(c, k, true) || j < 0 || j >= c.size()) return false;
        Element a = c.get(j), b = c.get(k);
        return Math.abs(c.dsWrap(b.s, a.s)) - a.halfW() - b.halfW() <= Sim.CRAB_HOP_GAP && Math.abs(b.y - a.y) <= Sim.CRAB_HOP_DY;
    }
    /** A crab from deck {@code home} follows you forward along the route, one hop at a time, up to CRAB_FOLLOW decks past its own (Sim.stepClubCrab: never below home). */
    static boolean crabReaches(Course c, int home, int p) {
        if (p == home) return true;
        if (p < home || p > home + Sim.CRAB_FOLLOW) return false;
        for (int j = home; j < p; j++) if (!crabHop(c, j, j + 1)) return false;
        return true;
    }
    /** A skull or blob moves to the platform before or after its own when it is a roam deck within ROAM_DY of the deck it leaves (Sim.stepSkull, Sim.stepBlob). */
    static boolean neighbourReaches(Course c, int home, int p) {
        if (p == home) return true;
        if (Math.abs(p - home) != 1 || !roamDeck(c, p, false)) return false;
        if (Math.abs(c.get(p).y - c.get(home).y) <= Element.ROAM_DY) return true;
        int q = 2 * home - p;           // or from the other neighbour, once it is there
        return roamDeck(c, q, false) && Math.abs(c.get(q).y - c.get(home).y) <= Element.ROAM_DY && Math.abs(c.get(p).y - c.get(q).y) <= Element.ROAM_DY;
    }

    /**
     * Owner decision B (2026-10-25): bee {@code h}'s pursuit envelope, as offsets from its post that never moves: {lo, hi} along the tower and {yLo, yHi}
     * for its height, written to {@code out}. At least the Build 71 drift (BEE_REACH along, 1.2 m up or down), widened to cover the platforms either side
     * of it: a bee over a gap, the two platforms the gap joins; a bee over a platform, that platform and the one before and after it. Only fixed platforms
     * count, never further than BEE_SEEK (it notices nobody beyond). Computed from the course alone, the same for the simulation and the evaluator.
     */
    public static void beeEnvelope(Course c, Element h, float[] out) {
        float lo = -Element.BEE_REACH, hi = Element.BEE_REACH, yLo = -1.2f, yHi = 1.2f;
        int a = h.anchor;
        if (a >= 0 && a < c.routeSize()) {
            Element A = c.get(a);
            boolean overDeck = A.isPlatform() && Math.abs(c.dsWrap(h.s, A.s)) <= A.halfW();
            for (int n = overDeck ? a - 1 : a; n <= a + 1; n++) {
                if (n < 0 || n >= c.routeSize()) continue;
                Element d = c.get(n);
                if (!d.isPlatform() || d.isMoving() || d.anchor >= 0) continue;          // route platforms only (a streaming world keeps branch platforms among the route indices)
                float off = c.dsWrap(d.s, h.s);
                lo = Math.min(lo, off - d.halfW()); hi = Math.max(hi, off + d.halfW());
                yLo = Math.min(yLo, d.y + 0.9f - h.y); yHi = Math.max(yHi, d.y + 0.9f - h.y);
            }
        }
        out[0] = Math.max(lo, -Element.BEE_SEEK); out[1] = Math.min(hi, Element.BEE_SEEK); out[2] = yLo; out[3] = yHi;
    }
    /** Bee {@code h} can reach route platform p: p is noticed (within BEE_SEEK of the post, 4 m below to 3 m above it) and lies within the envelope, as far as the bee drifts in one visit, plus its dive and body. */
    static boolean beeReaches(Course c, Tuning t, Element h, int p) {
        if (h.anchor == p) return true;
        Element e = c.get(p);
        float off = c.dsWrap(e.s, h.s), dy = e.y - h.y;
        if (Math.abs(off) - e.halfW() > Element.BEE_SEEK || dy <= -4f || dy >= 3f) return false;
        float[] env = new float[4]; beeEnvelope(c, h, env);
        float drift = Element.BEE_DRIFT * t.react() * Element.BEE_VISIT * h.period, extra = Element.BEE_SWOOP * Element.BEE_REACH + Element.BEE_R;
        float d1 = Math.max(env[0], -drift) - extra, d2 = Math.min(env[1], drift) + extra;
        return off + e.halfW() >= d1 && off - e.halfW() <= d2;
    }

    /** Weighted pursuit that can reach route platform {@code p}, with the same overlap growth as {@link #link}. */
    public static float threatAt(Course c, Tuning t, int p) {
        float raw = 0f; int n = 0;
        for (Element h : c.hazards) if (roamReach(h) >= 0 && reaches(c, t, h, p)) { raw += weight(h.type); n++; }
        return n == 0 ? 0f : raw * (1f + OVERLAP * (n - 1));
    }

    /** Weighted pursuit that can reach the take-off or the landing of the link out of route element i (each creature counted once). */
    public static float threat(Course c, Tuning t, int i) {
        int j = c.nextRoute(i);
        float raw = 0f; int n = 0;
        for (Element h : c.hazards) if (roamReach(h) >= 0 && (reaches(c, t, h, i) || reaches(c, t, h, j))) { raw += weight(h.type); n++; }
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
        switch (h.type) { case CRAB: return Sim.CRAB_FOLLOW; case SKULL: case BLOB: case BEE: return 1; default: return -1; }
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
