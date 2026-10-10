package com.hotatticgames.climbup.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Seeded, constraint-driven course generator. Every module is built from authored traversal patterns, then each
 * link is proven (and its tolerance measured) by {@link Autopilot} running the real simulation; links that are
 * not physically solvable with a minimum margin are re-planned easier. The result is an unbroken ascending spiral.
 *
 * Two entry points: {@link #generate} builds one finite tower with a goal (tests and tooling) and {@link #chunk} builds
 * one slice of the endless tower from (seed, index, previous slice), so the climb can be extended forever and resumed
 * from a saved slice without regenerating anything below it.
 */
public final class CourseGenerator {
    static final float RAMP_SLOPE = 0.576f;
    enum Kind { HOP, STAIRS, CRUMBLE, MOVER_H, MOVER_V, PAD, ROPE, CABLE, SWING, GRAB, HAZ, TRAP, SPRING, SEESAW, BRIDGE, RAMP, MOVER_Z, ELEV_FALL, SLIDE_FALL, MORTAR, WET }

    private final Tuning T;
    private final Random rnd;
    private final Course c;
    public int rejected;       // modules re-planned easier (diagnostics)
    private Kind prev, prev2;
    private int rests;
    private int ctx = 1;                    // leading elements that are context from the previous slice
    private boolean endless;
    private float rampH;
    private boolean relaxed;            // last-resort mode (see chunkIn)
    private double base;                    // framed generation: absolute height = base + local y (a multiple of the castle spacing); 0 for the classic absolute-frame slices
    private List<Element> ghostElems = new ArrayList<>();   // earlier-slice elements, only consulted for spiral-layer clearance
    private List<Element> ghostHaz = new ArrayList<>();
    public StringBuilder trace = new StringBuilder();
    public int hazardsPlaced, hazardTried, failLayer, failHaz, failPlan, failWindow;

    private CourseGenerator(long seed, Tuning t, long salt) {
        T = t; this.salt = salt; rnd = new Random(seed * 0x9E3779B97F4A7C15L + 12345 + salt * 0x632BE59BD9B4E019L); c = new Course(seed, t.circumference());
        rampH = t.courseHeight;
    }

    /** One finite tower with a goal flag (used by tests and tooling; the shipped game is endless). */
    public static Course generate(long seed, Tuning t) {
        IllegalStateException last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            CourseGenerator g = new CourseGenerator(seed, t, attempt * 1000003L);
            lastForDebug = g;
            try { g.build(); return g.c; } catch (IllegalStateException e) { last = e; }
        }
        throw last;
    }

    /**
     * Slice {@code k} of the endless tower. Element 0 of the result is the shared start platform (the previous slice's last
     * rest platform, a checkpoint); elements are route-first then decoys, hazards are in {@link Course#hazards}, anchors are local.
     */
    public static Course chunk(long seed, int k, Course prev, Tuning t) { return chunkIn(seed, k, prev, t, 0.0); }

    private static Course chunkIn(long seed, int k, Course prev, Tuning t, double base) {
        IllegalStateException last = null;
        for (int attempt = 0; attempt < 24; attempt++) {      // a dead end is retried with different (but still deterministic) choices
            CourseGenerator g = new CourseGenerator(seed, t, k + 1 + attempt * 1000003L);
            g.endless = true; g.rampH = t.rampHeight; g.base = base;
            g.relaxed = attempt >= 12;       // the previous slice can occasionally leave no room that keeps clear of every older spiral layer: after twelve normal tries, build above it without that clearance rather than ever failing
            lastForDebug = g;
            try { g.buildChunk(prev); return g.extract(); }
            catch (IllegalStateException e) { last = e; }
        }
        throw last;
    }

    /** A slice together with its frame: absolute height = yBase + local y, absolute arc = sBase + local s (yBase is a multiple of the castle spacing, sBase a whole number of laps), so every number the simulation touches stays small at any altitude. */
    public static final class Framed {
        public final Course c; public final double yBase, sBase;
        public Framed(Course c, double yBase, double sBase) { this.c = c; this.yBase = yBase; this.sBase = sBase; }
    }

    /** Slice {@code k} generated in its own small frame from the previous slice (null for slice 0). Deterministic: the same (seed, k, previous slice) always gives the same slice, bit for bit. */
    public static Framed chunkFramed(long seed, int k, Framed prev, Tuning t) {
        if (prev == null) return new Framed(chunkIn(seed, k, null, t, 0.0), 0.0, 0.0);
        Course pc = prev.c; Element last = pc.get(pc.routeSize() - 1);
        double step = t.castleSpacing > 0f ? t.castleSpacing : 500.0;
        double dy = Math.max(0.0, step * Math.floor(last.y / step)), ds = pc.circumference * Math.floor(last.s / pc.circumference);
        double yBase = prev.yBase + dy, sBase = prev.sBase + ds;
        Course shifted = shift(pc, (float) dy, (float) ds);
        return new Framed(chunkIn(seed, k, shifted, t, yBase), yBase, sBase);
    }

    /** A copy of a course translated by (-dy, -ds): used to put the previous slice into the next slice's frame. */
    public static Course shift(Course src, float dy, float ds) {
        Course out = new Course(src.seed, src.circumference);
        for (int i = 0; i < src.size(); i++) { Element e = copyOf(src.get(i), src.get(i).anchor); e.y -= dy; e.s -= ds; out.add(e); }
        for (Element h : src.hazards) { Element e = copyOf(h, h.anchor); e.y -= dy; e.s -= ds; out.hazards.add(e); }
        for (int[] k : src.keyRooms) out.keyRooms.add(k.clone());
        out.routeCount = src.routeCount; out.gemCheckpoints = src.gemCheckpoints; out.indexDecoys();
        return out;
    }

    // ---- difficulty as a function of height (all numbers live in tuning.json)
    private float diff(float y) { return Math.min(1f, Math.max(0f, (float) ((y + base) / rampH))); }
    private int tier(float y) { return Math.min(3, (int) (diff(y) * 4f)); }
    /** Visual theme of the platforms at height y: the four worlds repeat for ever in the endless climb. */
    private int theme(float y) { return endless ? ((int) ((y + base) / T.zoneHeight)) % T.zoneCount : tier(y); }
    /** 0 before the first hazards, ramping to 1 (finite towers ramp over their own height so tests exercise them too). */
    float intensity(float y) {
        float start = endless ? T.hazardStartY : T.courseHeight * 0.18f, ramp = endless ? T.hazardRampY : T.courseHeight * 0.7f;
        return Math.min(1f, Math.max(0f, (float) ((y + base - start) / ramp)));
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
        extend(T.courseHeight - 4f);
        addRest(3);
        // goal
        Element last = c.get(c.size() - 1);
        Element goal = plat(Element.Type.GOAL, last.s + last.w / 2 + 1.6f + 3f, last.y + 0.6f, 6f, 3);
        goal.checkpoint = true;
        if (!commit(listOf(goal), 0.0f)) throw new IllegalStateException("goal link failed");
        addDecoys(3, c.size() - 4);
        finalizeRooms();
    }

    private void buildChunk(Course prev) {
        if (prev == null) {
            Element start = plat(Element.Type.STATIC, 4f, 0f, 7f, 0);
            start.checkpoint = true;
            c.add(start); ctx = 1;
        } else {
            // context: the tail of the previous slice (so the planner sees the real approach) plus everything else as geometry-only ghosts
            int rs = prev.routeSize(), m = Math.min(8, rs), from = rs - m;
            for (int i = from; i < rs; i++) c.add(copyOf(prev.get(i), -1));
            for (Element h : prev.hazards) if (h.anchor >= from - 2 && h.anchor < rs) c.hazards.add(copyOf(h, h.anchor - from));
            if (!relaxed) {
                for (int i = 0; i < rs; i++) if (i < from) ghostElems.add(prev.get(i));
                for (int i = rs; i < prev.size(); i++) ghostElems.add(prev.get(i));
                for (Element h : prev.hazards) if (h.anchor < from - 2) ghostHaz.add(h);
            }
            ctx = m;
            rests = prev.get(rs - 1).zone * 3 + rs;   // keeps the checkpoint cadence varied between slices
        }
        float startY = c.get(ctx - 1).y;
        castleTarget = 0f; castleDone = false; exactCastle = false; keyN = 0; keyY = 0f; gemBest = -1;
        if (T.castleSpacing > 0f) {          // exact heights: the castles at spacing x N, and ONE red-gem checkpoint halfway between every two castles (and between the start and castle 1)
            double sp = T.castleSpacing, half = sp * 0.5, startAbs = startY + base;          // absolute heights in double: the same maths at any altitude
            int mc = (int) Math.floor((startAbs + 80.0) / sp);
            int mg = (int) Math.floor((startAbs + 80.0 - half) / sp);
            if (mc >= 1 && mc * sp > startAbs) { castleTarget = (float) (mc * sp - base); exactCastle = true; }
            else if (mg >= 0 && half + mg * sp > startAbs) { castleTarget = (float) (half + mg * sp - base); exactCastle = false; }
            for (int n = (int) Math.floor(startAbs / sp) + 1; n <= (int) Math.floor((startAbs + 80.0) / sp) + 1; n++) {      // the key of castle n lies somewhere random in the section before it: the slice that contains its height places it
                float hk = (float) (keyHeight(c.seed, n, T.castleSpacing) - base);
                if (hk > startY && hk <= startY + 80f) { keyN = n; keyY = hk; break; }
            }
        }
        extend(keyN > 0 ? Math.max(startY + T.chunkHeight, keyY + 6f) : startY + T.chunkHeight);
        addRest(theme(c.get(c.size() - 1).y));
        c.get(c.size() - 1).checkpoint = true;
        thinCheckpoints();
        checkNoExactStructureSkipped(startY);
        addDecoys(Math.max(3, ctx + 2), c.size() - 11);
        finalizeRooms();
    }

    /**
     * A slice only looks 80 m ahead for the castle or red gem that belongs to it (castleTarget); a slice that grows taller than that (a key placed high in its section, rests, a long module) can climb
     * straight past an exact-height structure, and the next slice, already above it, cannot build it: a whole section would be left without its red gem (a fall would cost 500 m) or its castle.
     * Such a layout is rejected here, so the caller builds this slice again with different choices.
     */
    private void checkNoExactStructureSkipped(float startY) {
        if (T.castleSpacing <= 0f) return;
        double sp = T.castleSpacing, half = sp * 0.5, startAbs = startY + base, endAbs = c.get(c.routeSize() - 1).y + base;
        for (double t = Math.floor(startAbs / half) * half; t <= endAbs; t += half) {        // every castle (multiples of sp) and every red gem (half way between) up to the top of this slice
            if (t <= startAbs + 0.01) continue;
            boolean mine = castleTarget > 0f && Math.abs(t - (castleTarget + base)) < 0.5;
            if (!mine && t < endAbs - 0.01) throw new IllegalStateException("slice climbed past the exact structure at " + t);
        }
    }

    /** Castle attempts that were dropped can leave a second checkpoint right behind another one: keep the red gems evenly spread (the slice's closing one always stays). */
    private void thinCheckpoints() {
        float lastY = c.get(ctx - 1).y;
        for (int i = ctx; i < c.size() - 1; i++) {
            Element e = c.get(i);
            if (!e.checkpoint || e.skin == 3) continue;          // (the halfway gem platform always stays)
            if (e.y - lastY < 0.45f * T.gemSpacing) e.checkpoint = false; else lastY = e.y;
        }
    }

    private static Element copyOf(Element e, int anchor) {
        Element n = new Element(e.type, e.s, e.y, e.w);
        n.zone = e.zone; n.amp = e.amp; n.period = e.period; n.phase = e.phase; n.len = e.len; n.checkpoint = e.checkpoint; n.dir = e.dir; n.color = e.color; n.skin = e.skin; n.anchor = anchor;
        return n;
    }

    /** Result slice: elements ctx-1.. of the working course (route first, then decoys), anchors rebased to the slice. */
    private Course extract() {
        int off = ctx - 1, rs = c.routeSize();
        Course out = new Course(c.seed, c.circumference);
        for (int i = off; i < c.size(); i++) {
            Element e = copyOf(c.get(i), c.get(i).anchor < 0 ? -1 : c.get(i).anchor - off);
            out.add(e);
        }
        int[] newHz = new int[c.hazards.size()];
        for (int i = 0; i < c.hazards.size(); i++) {
            Element h = c.hazards.get(i);
            if (h.anchor >= off) { newHz[i] = out.hazards.size(); out.hazards.add(copyOf(h, h.anchor - off)); } else newHz[i] = -1;
        }
        for (int[] k : c.keyRooms) out.keyRooms.add(new int[]{k[0] - off, k[1] < 0 ? -1 : k[1] - off, k[2], k[3], newHz[k[4]], k[5] < 0 ? -1 : newHz[k[5]]});
        out.routeCount = rs - off;
        out.indexDecoys();
        return out;
    }

    /** Appends modules until the last platform reaches height {@code targetY}. */
    private void extend(float targetY) {
        if (castleTarget > 0f && !castleDone) targetY = Math.max(targetY, castleTarget + (exactCastle ? 8f : 6f));
        int sinceRest = 0;
        float lastRestY = c.get(c.size() - 1).y, nextGap = T.gemSpacing * r(0.85f, 1.15f);      // red-gem checkpoints at even stretches of height, each with a little random give
        while (c.get(c.size() - 1).y < targetY) {
            Element last = c.get(c.size() - 1);
            float d = diff(last.y);
            int zone = tier(last.y), th = theme(last.y);
            boolean castlePending = castleTarget > 0f && !castleDone;
            if (castlePending && exactCastle && last.y >= castleTarget - 6f) { buildExactCastle(th); castleDone = true; lastRestY = c.get(c.size() - 1).y; continue; }
            if (castlePending && !exactCastle && last.y >= castleTarget - GEM_LEAD) { chooseGem(th); castleDone = true; lastRestY = c.get(c.size() - 1).y; continue; }
            if (last.y - lastRestY >= nextGap && targetY - last.y > 0.4f * T.gemSpacing && !(castlePending && last.y > castleTarget - 14f)) {      // (not right before the slice's own closing rest)
                addRest(th); sinceRest = 0; lastRestY = c.get(c.size() - 1).y; nextGap = T.gemSpacing * r(0.85f, 1.15f); maybeCastle(th); continue;
            }
            Kind k = pick(zone, last);
            if (th == 1 && last.type == Element.Type.STATIC && rnd.nextFloat() < 0.2f) k = Kind.WET;          // the Frost world: some platforms are under a rain cloud (slippery)
            if (!tryModuleBelow(k, d, th)) {
                rejected++;
                boolean ok = false;
                for (Kind fb : new Kind[]{Kind.PAD, Kind.ROPE, Kind.MOVER_V, Kind.STAIRS, Kind.HOP}) { if (fb != k && tryModuleBelow(fb, 0.15f, th)) { k = fb; ok = true; break; } }
                if (!ok) throw new IllegalStateException("cannot extend course (layer=" + failLayer + " haz=" + failHaz + " plan=" + failPlan + " window=" + failWindow + ") at " + c.size() + " last=" + last.type + " s=" + last.s + " y=" + last.y + " below=" + below(last));
            }
            prev2 = prev; prev = k; sinceRest++;
        }
    }


    // ------------------------------------------------------------------ castles: a coloured gate across the route, and a key room that is proven reachable (and returnable)

    private final List<int[]> pendingCastles = new ArrayList<>();   // {rest platform index, gate index in c.hazards}
    private int castlesThisCourse;
    public int castleDue, castleBuilt, roomFailed, roomOk, roomFallback; public int[] roomWhy = new int[5], roomWhyD = new int[5];

    private int sliceNo() { return (int) ((salt - 1) % 1000003L); }

    private void maybeCastle(int th) {
        if (endless) return;                    // the endless climb builds its castles at exact heights (buildExactCastle)
        Element last = c.get(c.size() - 1);
        boolean due = endless ? (castlesThisCourse == 0 && c.size() - ctx > 6)
                              : (last.y > 70f && castlesThisCourse < (int) (last.y / 150f) && castlesThisCourse < 3);
        if (due) castleDue++;
        if (!due || !last.checkpoint || last.type != Element.Type.STATIC || last.w < 4f) return;
        if (endless && last.y > c.get(ctx - 1).y + T.chunkHeight - 8f) return;           // keep the whole castle inside this slice
        tryCastle(th);
    }

    private float castleTarget; private boolean castleDone, exactCastle;      // the next exact-height structure of this slice: a castle, or the halfway red-gem platform

    /** A module that must not carry the climb past the castle's approach window (keeps the last platform at least 1.6 m below the castle deck). */
    private boolean tryModuleBelow(Kind k, float d, int zone) {
        if (castleTarget <= 0f || castleDone) return tryModule(k, d, zone);
        int n0 = c.size(), h0 = c.hazards.size();
        if (!tryModule(k, d, zone)) return false;
        if (c.get(c.size() - 1).y > (exactCastle ? castleTarget - 1.6f : castleTarget + 1.0f)) { rollbackTo(n0, h0); return false; }
        return true;
    }

    /** The castle of the endless climb: a rest platform, a staircase of small steps that lands the deck at EXACTLY castleTarget, the deck with its gate (gate.skin = castle number). */
    private void buildExactCastle(int th) {
        addRest(th);
        int rIdx = c.size() - 1; Element u = c.get(rIdx);
        float R = castleTarget - u.y;
        if (R < 0.05f || R > 7.5f || !u.checkpoint || u.w < 4f) throw new IllegalStateException("castle approach out of range R=" + R);
        int number = (int) Math.round((castleTarget + base) / T.castleSpacing);
        float d = diff(u.y);
        for (int attempt = 0; attempt < 10; attempt++) {
            int k = Math.max(1, (int) Math.ceil(R / 1.25f)) + (rnd.nextFloat() < 0.4f ? 1 : 0);
            float[] part = new float[k]; float tot = 0; for (int i = 0; i < k; i++) { part[i] = 0.4f + rnd.nextFloat(); tot += part[i]; }
            boolean fits = true; for (int i = 0; i < k; i++) { part[i] = R * part[i] / tot; if (part[i] > 1.3f) fits = false; }
            if (!fits) { for (int i = 0; i < k; i++) part[i] = R / k; }
            List<Element> l = new ArrayList<>(); Element pv = u; float yy = u.y;
            for (int i = 0; i < k - 1; i++) {
                yy += part[i];
                float w = r(2.2f, 4.0f), gap = reach(part[i]) * r(0.35f, 0.72f) * (1f - 0.03f * attempt);
                Element e = plat(Element.Type.STATIC, pv.s + pv.w / 2f + gap + w / 2f, yy, w, th); l.add(e); pv = e;
            }
            float step = part[k - 1];
            float gapD = reach(step) * r(0.45f, 0.78f) * (1f - 0.03f * attempt);
            Element g = plat(Element.Type.STATIC, pv.s + pv.w / 2f + gapD + 4.5f, castleTarget, 9f, th);
            g.skin = 2; l.add(g);
            Element gate = hz(Element.Type.GATE, g.s, castleTarget, 3.4f, th);
            gate.len = 7.5f; gate.color = castleColor(c.seed, number); gate.skin = number; gate.anchor = c.size() + l.size() - 1;
            if (!commit(l, listOf(gate), T.minLinkMargin)) continue;
            int gateIdx = c.hazards.size() - 1;
            addRest(th);
            pendingCastles.add(new int[]{rIdx, gateIdx});
            castlesThisCourse++; castleBuilt++;
            return;
        }
        throw new IllegalStateException("castle " + number + " could not be placed");
    }

    private int keyN, gemBest; private float keyY;

    /** Deterministic key height for castle n: anywhere in the section before it (near the previous castle ... far above), clear of the exact-height structures (gem, castle). */
    public static float keyHeight(long seed, int n, float spacing) {
        float lo = spacing * (n - 1) + 20f, hi = spacing * n - 40f, mid = spacing * (n - 1) + spacing * 0.5f;
        Random r = new Random(seed * 0x9E3779B97F4A7C15L + n * 0xBF58476D1CE4E5B9L + 17L);
        for (int i = 0; i < 32; i++) { float h = lo + r.nextFloat() * (hi - lo); if (Math.abs(h - mid) >= 16f) return h; }
        return lo;
    }
    /** The key (and gate) colour of castle n is fixed by the run's seed and the castle number, so the key can be placed sections before its gate is built. */
    public static int castleColor(long seed, int n) { return new Random(seed * 0x2545F4914F6CDD1DL + n * 7919L + 5L).nextInt(Element.KEY_COUNT); }

    /** The halfway red gem: among the route platforms of this slice, the suitable one (a plain platform wide enough to stand and wait on) closest to the exact midpoint; if none is within 1.5 m a natural rest platform is added right where the climb crosses it. */
    private void chooseGem(int th) {
        int best = bestGemCandidate();
        // none close enough: a natural rest platform is added right here (the climb is within GEM_LEAD of the midpoint), and again if it came out too narrow to stand and wait on
        for (int tries = 0; tries < 3 && (best < 0 || Math.abs(c.get(best).y - castleTarget) > 1.5f); tries++) {
            addRest(th); int b2 = bestGemCandidate();
            if (b2 >= 0 && (best < 0 || Math.abs(c.get(b2).y - castleTarget) < Math.abs(c.get(best).y - castleTarget))) best = b2;
            if (b2 == c.size() - 1) break;          // the new rest is suitable
        }
        if (best < 0) throw new IllegalStateException("no platform for the gem near " + castleTarget);
        Element g = c.get(best); g.skin = 3; g.checkpoint = true; gemBest = best;
    }
    /** How far below the exact midpoint the climb may still be when the red gem platform is chosen: a rest added then lands within about 4 m of the midpoint instead of up to a whole module above it. */
    private static final float GEM_LEAD = 4f;
    private int bestGemCandidate() {
        int best = -1; float dev = 1e9f;
        for (int i = Math.max(1, ctx); i < c.size(); i++) {          // this slice's own platforms: the context platforms belong to the previous slice, a gem marked on one would be lost
            Element e = c.get(i);
            if (e.type != Element.Type.STATIC || e.skin != 0 || e.wet() || e.w < 3.5f || e.anchor >= 0 || Autopilot.hasMidHazard(c, i)) continue;
            float d = Math.abs(e.y - castleTarget);
            if (d < dev) { dev = d; best = i; }
        }
        return best;
    }

    /** Places the key of castle keyN at a random spot near height keyY: lying on a route platform, or at the end of an optional dead-end branch (a chain of 1-3 platforms, or a pad-boosted perch above), every branch proven both ways. Reachable, never equally convenient. */
    private void placeKey() {
        int rs = c.routeSize();
        List<Integer> cand = new ArrayList<>();
        for (int a = Math.max(1, ctx - 1); a < rs - 4; a++) {
            Element e = c.get(a);
            if (e.type != Element.Type.STATIC || e.skin != 0 || e.wet() || e.w < 2.5f || e.anchor >= 0 || Autopilot.hasMidHazard(c, a)) continue;
            cand.add(a);
        }
        if (cand.isEmpty()) throw new IllegalStateException("no platform for the key");
        final float hk = keyY;
        cand.sort((x, y) -> Float.compare(Math.abs(c.get(x).y - hk), Math.abs(c.get(y).y - hk)));
        int color = castleColor(c.seed, keyN);
        float roll = rnd.nextFloat();
        int[] order = roll < 0.2f ? new int[]{2, 0, 3} : roll < 0.7f ? new int[]{0, 3, 2} : new int[]{3, 0, 2};      // route / chain / pad-up first, the others as fall-backs
        int tries = Math.min(cand.size(), 7);
        for (int mode : order) {
            if (mode == 2) { for (int q = 0; q < Math.min(cand.size(), 3); q++) if (keyOnRoute(cand.get(q), color)) return; continue; }
            for (int q = 0; q < tries; q++) for (int att = 0; att < 6; att++) if (keyBranch(cand.get(q), mode, color)) return;
        }
        for (int q = 0; q < cand.size(); q++) if (keyOnRoute(cand.get(q), color)) return;
        throw new IllegalStateException("key of castle " + keyN + " could not be placed");
    }

    private boolean keyOnRoute(int a, int color) {
        Element A = c.get(a); float room = Math.max(0f, A.halfW() - 0.5f);
        Element key = new Element(Element.Type.KEY, A.s + (rnd.nextFloat() * 2f - 1f) * room, A.y + 0.95f, 0f);
        key.zone = A.zone; key.color = color; key.anchor = a; c.hazards.add(key);
        builtRooms.add(new Element[]{key, null}); roomIdx.add(new int[]{a, -1, 0, 2});
        return true;
    }

    /** mode 0: a chain of 1-3 platforms; mode 3: a pad beside the platform and a perch 3-5 m above it. */
    private boolean keyBranch(int a, int mode, int color) {
        Element A = c.get(a); int side = rnd.nextBoolean() ? 1 : -1;
        List<Element> es = new ArrayList<>(); float edge = A.s + side * A.w / 2f, y = A.y;
        Element key;
        if (mode == 3) {
            float gap = r(0.4f, 1.3f);
            Element P = plat(Element.Type.PAD, edge + side * (gap + 1f), A.y - r(0f, 0.3f), 2f, A.zone);
            float uw = r(2.4f, 4.2f);
            Element U = plat(Element.Type.STATIC, P.s + side * r(0.3f, 3.0f), A.y + r(3.0f, 4.8f), uw, A.zone);
            P.anchor = a; U.anchor = a; es.add(P); es.add(U);
            key = new Element(Element.Type.KEY, U.s + (rnd.nextFloat() * 2f - 1f) * Math.max(0f, U.halfW() - 0.5f), U.y + 0.95f, 0f);
        } else {
            int n = 1 + (rnd.nextFloat() < 0.35f ? 1 : 0) + (rnd.nextFloat() < 0.2f ? 1 : 0);
            for (int i = 0; i < n; i++) {
                float dy = r(-3.2f, 1.3f); if (y + dy > A.y + 4.5f || y + dy < A.y - 6f) dy = -dy * 0.5f;
                float w = r(2.0f, 4.0f), gap = reach(dy) * r(0.30f, 0.78f);
                Element e = plat(Element.Type.STATIC, edge + side * (gap + w / 2f), y + dy, w, A.zone); e.anchor = a; es.add(e);
                edge = e.s + side * w / 2f; y = e.y;
            }
            Element L = es.get(es.size() - 1);
            key = new Element(Element.Type.KEY, L.s + (rnd.nextFloat() * 2f - 1f) * Math.max(0f, L.halfW() - 0.5f), L.y + 0.95f, 0f);
        }
        key.zone = A.zone; key.color = color; key.anchor = a;
        int n0 = c.size(), h0 = c.hazards.size(); boolean ok = true;
        for (Element e : es) { c.add(e); if (!decoyOk(e, a)) { ok = false; break; } }
        if (ok) c.hazards.add(key);
        if (ok) {
            c.indexDecoys(); int first = n0;
            if (mode == 3) ok = pairOk(a, first) && pairOkAir(first, first + 1) && pairOk(first + 1, a);
            else { int prevI = a; for (int i = 0; i < es.size() && ok; i++) { ok = pairOk(prevI, n0 + i) && pairOk(n0 + i, prevI); prevI = n0 + i; } }
        }
        if (ok) {                                                           // the branch must not break any route link nearby
            int rs = c.routeSize();
            for (int q = Math.max(0, a - 7); q <= Math.min(rs - 2, a + 5) && ok; q++) ok = Autopilot.plan(Sim.startOn(c, T, q), q, false).ok;
        }
        if (!ok) { while (c.size() > n0) c.elements.remove(c.size() - 1); while (c.hazards.size() > h0) c.hazards.remove(c.hazards.size() - 1); c.indexDecoys(); return false; }
        builtRooms.add(new Element[]{key, null}); roomIdx.add(new int[]{a, n0, es.size(), mode});
        return true;
    }

    private boolean tryCastle(int th) {
        int n0 = c.size(), h0 = c.hazards.size();
        int rIdx = n0 - 1;
        float d = diff(c.get(rIdx).y);
        int mods = 1 + rnd.nextInt(2);
        for (int i = 0; i < mods; i++) {
            if (!tryModule(rnd.nextBoolean() ? Kind.HOP : Kind.STAIRS, d, th)) { rollbackTo(n0, h0); return false; }
        }
        Element u = c.get(c.size() - 1);
        for (int attempt = 0; attempt < 6; attempt++) {
            float dy = r(0f, 0.5f), gap = reach(dy) * lerp(0.5f, 0.8f, d) * (1f - 0.04f * attempt);
            Element g = plat(Element.Type.STATIC, u.s + u.w / 2f + gap + 4.5f, u.y + dy, 9f, th);
            g.skin = 2;                                                  // castle deck: drawn deep and broad enough to carry the whole tower base
            Element gate = hz(Element.Type.GATE, g.s, g.y, 3.4f, th);
            gate.len = 7.5f; gate.color = rnd.nextInt(Element.KEY_COUNT); gate.anchor = c.size();
            if (!commit(listOf(g), listOf(gate), T.minLinkMargin)) continue;
            int gateIdx = c.hazards.size() - 1;
            addRest(th);
            pendingCastles.add(new int[]{rIdx, gateIdx});
            castlesThisCourse++; castleBuilt++;
            return true;
        }
        rollbackTo(n0, h0);
        return false;
    }

    private void rollbackTo(int n0, int h0) {
        while (c.size() > n0) c.elements.remove(c.size() - 1);
        while (c.hazards.size() > h0) c.hazards.remove(c.hazards.size() - 1);
    }

    private final List<Element[]> builtRooms = new ArrayList<>();     // {key, gate}
    private final List<int[]> roomIdx = new ArrayList<>();            // {anchor, first decoy, nOut, pad}

    /** Builds a key room for every castle (in the decoy phase, after the route is final). A castle whose key cannot be placed provably is removed instead, so a locked door can never be unopenable. */
    private void addKeyRooms() {
        if (keyN > 0) placeKey();          // the endless climb: the key of castle keyN, random place in its section
        List<Element> removeGates = new ArrayList<>();
        for (int[] pc : pendingCastles) {
            Element gate = c.hazards.get(pc[1]);
            if (endless && gate.skin > 0) continue;           // exact castles: the key already lies in the section before (placeKey)
            boolean ok = false;
            int gIdx = gate.anchor;
            for (int anchor = pc[0]; anchor < gIdx && !ok; anchor++) {          // the rest platform first, then any plain platform between it and the gate
                Element pl = c.get(anchor);
                if (pl.type != Element.Type.STATIC || (anchor != pc[0] && pl.w < 3f)) continue;
                for (int attempt = 0; attempt < 14 && !ok; attempt++) ok = tryKeyRoom(anchor, gate, attempt);
            }
            if (!ok) { removeGates.add(gate); roomFailed++; } else roomOk++;
        }
        for (Element g : removeGates) c.hazards.remove(g);
    }

    private boolean tryKeyRoom(int rIdx, Element gate, int attempt) {
        Element R = c.get(rIdx);
        boolean pad = attempt % 2 == 1;
        int side = (attempt / 2) % 3 == 2 ? -1 : 1;     // mostly under the route ahead (it is higher there, so the room rarely collides with it), sometimes behind
        float gap = r(0.4f, 1.3f), edge = side < 0 ? R.s - R.w / 2f : R.s + R.w / 2f;
        List<Element> es = new ArrayList<>();
        Element K, P = null;
        if (!pad) {
            float ky = R.y - r(1.25f, 1.65f);
            K = plat(Element.Type.STATIC, edge + side * (gap + 1.5f), ky, 3f, R.zone);
        } else {
            float ky = R.y - r(3.6f, 5.6f);
            P = plat(Element.Type.PAD, edge + side * 1.6f, ky, 2f, R.zone);
            K = plat(Element.Type.STATIC, P.s + side * 2.8f, ky, 3f, R.zone);
        }
        K.anchor = rIdx; es.add(K);
        if (P != null) { P.anchor = rIdx; es.add(P); }
        Element key = new Element(Element.Type.KEY, K.s + side * 0.8f, K.y + 0.95f, 0f);
        key.zone = R.zone; key.color = gate.color; key.anchor = rIdx;
        int n0 = c.size(), h0 = c.hazards.size();
        boolean ok = true;
        for (Element e : es) { c.add(e); if (!decoyOk(e, rIdx)) { ok = false; roomWhy[0]++; roomWhyD[decoyWhy]++; break; } }
        if (ok) c.hazards.add(key);
        int first = n0, kIdx = n0, pIdx = n0 + 1;
        if (ok) {
            c.indexDecoys();
            boolean a1 = pairOk(rIdx, kIdx), a2 = a1 && (P == null ? pairOk(kIdx, rIdx) : pairOk(kIdx, pIdx)), a3 = a2 && (P == null || pairOkAir(pIdx, rIdx));
            if (!a1) roomWhy[1]++; else if (!a2) roomWhy[2]++; else if (!a3) roomWhy[3]++;
            ok = a3;
        }
        if (ok) {                                                           // the detour must not break any route link nearby
            int rs = c.routeSize();
            for (int q = Math.max(0, rIdx - 7); q <= Math.min(rs - 2, rIdx + 5) && ok; q++) ok = Autopilot.plan(Sim.startOn(c, T, q), q, false).ok;
            if (!ok) roomWhy[4]++;
        }
        if (!ok) { while (c.size() > n0) c.elements.remove(c.size() - 1); while (c.hazards.size() > h0) c.hazards.remove(c.hazards.size() - 1); c.indexDecoys(); return false; }
        builtRooms.add(new Element[]{key, gate});
        roomIdx.add(new int[]{rIdx, first, 1, P == null ? 0 : 1});
        return true;
    }

    /** A detour link must work from several standing spots, not just the platform centre (a human never stands exactly there). */
    private boolean pairOk(int a, int b) {
        float hwd = Math.max(0.3f, c.get(a).halfW() - 0.4f);
        for (float dx : new float[]{-hwd, -0.75f * hwd, -hwd / 2f, -hwd / 4f, 0f, hwd / 4f, hwd / 2f, 0.75f * hwd, hwd}) {
            Sim base = Sim.startOn(c, T, a);
            if (base.mode == Sim.Mode.GROUND) base.s = c.wrap(base.s + dx);
            else if (dx != 0f) continue;
            Autopilot.Result res = Autopilot.planPair(base, a, b, true);
            if (!res.ok || res.margin() < Math.min(T.minLinkMargin, 0.05f)) return false;
        }
        return true;
    }
    private boolean pairOkAir(int a, int b) { return pairOk(a, b); }

    // ------------------------------------------------------------------ decoys: dead ends and lures that mislead without ever blocking the route

    public int decoys, decoyTried, decoyGeoFail, decoyPlanFail;
    public static CourseGenerator lastForDebug;
    private long salt;
    private List<Element> pendingHaz = new ArrayList<>();

    private void finalizeRooms() {
        c.keyRooms.clear();
        for (int i = 0; i < builtRooms.size(); i++) {
            Element[] kg = builtRooms.get(i);
            if (!c.hazards.contains(kg[0]) || (kg[1] != null && !c.hazards.contains(kg[1]))) continue;
            int[] ri = roomIdx.get(i);
            c.keyRooms.add(new int[]{ri[0], ri[1], ri[2], ri[3], c.hazards.indexOf(kg[0]), kg[1] == null ? -1 : c.hazards.indexOf(kg[1])});
        }
    }

    private void addDecoys(int lo, int hi) {
        c.finishRoute();
        Random dr = new Random(c.seed * 0x2545F4914F6CDD1DL + 99 + salt * 7919L);
        int rs = c.routeSize();
        for (int a = lo; a < Math.min(rs - 4, hi + 1); a++) {
            Element p = c.get(a);
            if (p.type != Element.Type.STATIC || p.w < 3f) continue;
            if (dr.nextFloat() > 0.44f + 0.08f * tier(p.y)) continue;          // more dead ends than before (was 0.30)
            for (int again = 0; again < 2; again++) {          // a second, different try when the first does not fit the surrounding geometry
                pendingHaz = new ArrayList<>();
                List<Element> es = buildDecoy(p, a, dr);
                if (tryDecoy(es, pendingHaz, a)) { decoys += es.size(); break; }
            }
        }
        addKeyRooms();          // last, so every key-room link is proven against the final geometry
    }

    /** Dead-end spurs (forward and gently down, or backward and up), crumbling lures, unreachable stepping stones, and trapped ledges guarded by hazards. */
    private List<Element> buildDecoy(Element p, int a, Random dr) {
        List<Element> l = new ArrayList<>();
        int kind = dr.nextInt(12);
        float inten = intensity(p.y);
        boolean longSpur = kind >= 10;           // a longer dead end: four to six steps ending on a wide, plainly safe platform
        if (longSpur) kind = dr.nextInt(7);
        if (kind >= 8 && inten <= 0f) kind = dr.nextInt(8);
        if (kind >= 8) {
            // bait ledge: a wide, tempting platform forward and slightly down, guarded by a trap in the middle or a saw across the approach
            float gap = reach(0f) * (0.62f + 0.12f * dr.nextFloat()), eR = p.s + p.w / 2f;
            if (kind == 8) {
                float w = 4.5f, s = eR + gap + w / 2f, y = p.y - (0.2f + dr.nextFloat() * 0.3f);
                Element d = plat(Element.Type.STATIC, s, y, w, p.zone); d.anchor = a; l.add(d);
                Element trap = new Element(Element.Type.SPIKE_TRAP, s - 0.2f, y, 2.4f); trap.zone = p.zone; trap.anchor = a;
                trap.period = 3.4f + dr.nextFloat(); trap.amp = 0.34f; trap.phase = dr.nextFloat() * 6.28f; pendingHaz.add(trap);
            } else {
                float g2 = 2.6f, s1 = eR + 0.5f + 1f, y1 = p.y - 0.3f;
                Element d1 = plat(Element.Type.STATIC, s1, y1, 2f, p.zone); d1.anchor = a; l.add(d1);
                Element d2 = plat(Element.Type.STATIC, s1 + 1f + g2 + 1.5f, y1 + 0.3f, 3f, p.zone); d2.anchor = a; l.add(d2);
                Element saw = new Element(Element.Type.SAW_V, s1 + 1f + g2 / 2f, y1 + 0.3f, 0f); saw.zone = p.zone; saw.anchor = a;
                saw.amp = 2.0f; saw.period = 2.8f + dr.nextFloat() * 0.8f; saw.phase = dr.nextFloat() * 6.28f; pendingHaz.add(saw);
            }
            return l;
        }
        if (kind < 7) {
            boolean forward = kind < 4;
            float edge = forward ? p.s + p.w / 2f : p.s - p.w / 2f, y = p.y;
            int n = longSpur ? 4 + dr.nextInt(3) : 2 + dr.nextInt(2);
            for (int k = 0; k < n; k++) {
                float dy = forward ? -(longSpur ? 0.15f + dr.nextFloat() * 0.25f : 0.35f + dr.nextFloat() * 0.35f) : (longSpur ? 0.2f + dr.nextFloat() * 0.4f : 0.3f + dr.nextFloat() * 0.8f);
                float gap = reach(Math.max(dy, 0f)) * (0.40f + 0.2f * dr.nextFloat());
                float w = k == n - 1 ? (longSpur ? 3f : 1f) : 1f + dr.nextInt(2);
                float s = forward ? edge + gap + w / 2f : edge - gap - w / 2f; y += dy;
                Element d = plat(Element.Type.STATIC, s, y, w, p.zone); d.anchor = a;       // a dead end costs only the walk back: no trap at the end
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

    /** All earlier elements that matter for spiral-layer clearance: this working course plus the previous slice. */
    private boolean clashesWithOtherLayers(Element e, int selfIdx) {
        for (int i = 0; i < c.size(); i++) { if (i != selfIdx && c.get(i) != e && layersClash(c, e, c.get(i))) return true; }
        for (Element g : ghostElems) if (layersClash(c, e, g)) return true;
        return false;
    }

    public int decoyWhy;      // diagnostics: why decoyOk last said no (1 overlap, 2 other layer, 3 ghost layer, 4 stepping stone)

    private boolean decoyOk(Element d, int a) {
        int rs = c.routeSize();
        for (int i = 0; i < c.size(); i++) {
            Element e = c.get(i);
            if (e == d) continue;
            if (Math.abs(d.s - e.s) < c.circumference * 0.5f) {
                float gap = Math.abs(d.s - e.s) - d.w / 2f - arcHalf(e);
                if (gap < 1.0f && d.y - 0.6f < vHi(e) - 1.6f && d.y + 1.6f > vLo(e) + 0.8f) { decoyWhy = 1; return false; }   // overlapping / touching
            } else if (layersClash(c, d, e)) { decoyWhy = 2; return false; }
        }
        for (Element g : ghostElems) if (layersClash(c, d, g)) { decoyWhy = 3; return false; }
        for (int j = a + 2; j < rs; j++) {                 // never a stepping stone toward later route
            Element e = c.get(j);
            if (!e.isPlatform()) continue;
            float gap = Math.max(0f, Math.abs(c.dsWrap(d.s, e.s)) - d.w / 2f - e.halfW());
            if (gap < 6.5f && e.y - d.y > -7f && e.y - d.y < 3.6f) { decoyWhy = 4; return false; }
        }
        return true;
    }

    private boolean tryDecoy(List<Element> es, List<Element> hz, int a) {
        int n0 = c.size(), h0 = c.hazards.size();
        boolean ok = true; decoyTried++;
        for (Element d : es) { c.add(d); if (!decoyOk(d, a)) { ok = false; decoyGeoFail++; break; } }
        if (ok) for (Element h : hz) { c.hazards.add(h); if (!hazardOk(h, -1)) { ok = false; decoyGeoFail++; break; } }
        if (ok) {
            c.indexDecoys();
            int rs = c.routeSize();
            for (int r = Math.max(0, a - 7); r <= Math.min(rs - 2, a + 5) && ok; r++) {
                Autopilot.Result res = Autopilot.plan(Sim.startOn(c, T, r), r, false);
                if (!res.ok) { ok = false; decoyPlanFail++; }
            }
        }
        if (!ok) { while (c.size() > n0) c.elements.remove(c.size() - 1); while (c.hazards.size() > h0) c.hazards.remove(c.hazards.size() - 1); c.indexDecoys(); }
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
        switch (e.type) { case ROPE: return e.y - e.len; case CABLE: return e.y - 2.4f; case SEESAW: return e.y - 2.8f; case RAMP: return e.y - e.amp * e.halfW() - 3.4f; default: return e.y - 0.8f; }
    }
    private static float vHi(Element e) {
        switch (e.type) {
            case ROPE: return e.y + 0.6f; case CABLE: return e.y + 0.4f; case SWING: return e.y + e.len + 0.4f;
            case MOVE_V: return e.y + e.amp + 2.8f; case RAMP: return e.y + e.amp * e.halfW() + 2.8f; default: return e.y + 2.8f;
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
        for (Element g : ghostElems) if (layersClash(c, e, g)) return false;
        return true;
    }

    // ---- hazard geometry: lethal-region bounding box (centre arc, half extent, low, high)
    private static float hzS(Element h) { return h.type == Element.Type.CANNON ? h.s + h.dir * h.len * 0.5f : h.s; }
    private static float hzHalf(Element h) {
        switch (h.type) {
            case SAW_H: return h.amp + Element.SAW_R;
            case CRAB: return h.amp + 0.6f;
            case BEE: return h.amp + 0.6f;
            case SAW_V: return Element.SAW_R;
            case PENDULUM: return h.len * (float) Math.sin(h.amp) + Element.BALL_R;
            case CANNON: return h.len * 0.5f + Element.SHOT_R;
            case MORTAR: return Element.MORTAR_R;
            default: return h.w * 0.5f;
        }
    }
    private static float hzLo(Element h) {
        switch (h.type) { case SAW_H: case SAW_V: return h.y - Element.SAW_R; case PENDULUM: return h.y - Element.BALL_R; case CANNON: return h.y - Element.SHOT_R; case BEE: return h.y - h.len - 1.4f; default: return h.y; }
    }
    private static float hzHi(Element h) {
        switch (h.type) {
            case SAW_H: return h.y + Element.SAW_R;
            case SAW_V: return h.y + h.amp + Element.SAW_R;
            case PENDULUM: return h.y + h.len * (1f - (float) Math.cos(h.amp)) + Element.BALL_R;
            case CANNON: return h.y + Element.SHOT_R;
            case MORTAR: return h.y + h.amp + Element.MORTAR_R;
            case SPIKE_TRAP: return h.y + 0.7f;
            case SPIKE_DROP: return h.y + h.amp + Element.DROP_H;
            case GATE: return h.y + h.len;
            case CRAB: return h.y + 0.7f;
            case BEE: return h.y + h.len + 0.6f;
            default: return h.y + h.len;
        }
    }

    /** A hazard must not touch standing room on its own spiral layer (except the platform a trap sits in) and must keep clear of other layers. */
    private boolean hazardOk(Element h, int ownerIdx) {
        float hs = hzS(h), hh = hzHalf(h), lo = hzLo(h), hi = hzHi(h);
        for (int pass = 0; pass < 2; pass++) {
            int n = pass == 0 ? c.size() : ghostElems.size();
            for (int i = 0; i < n; i++) {
                Element e = pass == 0 ? c.get(i) : ghostElems.get(i);
                if (pass == 0 && i == ownerIdx) continue;
                if (Math.abs(hs - e.s) < c.circumference * 0.5f) {                 // same revolution
                    float arc = Math.abs(hs - e.s);
                    if (e.isPlatform()) {
                        if (arc < e.halfW() + 0.35f + hh && hi > e.y - 0.3f && lo < e.y + 1.5f) return false;
                    } else if (arc < arcHalf(e) + 0.35f + hh && hi > vLo(e) - 0.3f && lo < vHi(e) + 0.3f) return false;
                } else if (Math.abs(c.dsWrap(hs, e.s)) < hh + arcHalf(e) + 2.0f && lo - 1.5f < vHi(e) && hi + 1.5f > vLo(e)) return false;   // other revolution
            }
        }
        for (Element g : ghostHaz) if (Math.abs(c.dsWrap(hs, hzS(g))) < hh + hzHalf(g) + 1.5f && lo - 1.5f < hzHi(g) && hi + 1.5f > hzLo(g) && Math.abs(hs - hzS(g)) >= c.circumference * 0.5f) return false;
        return true;
    }

    /** The platform a hazard sits on (exempt from the standing-room test), or -1 for hazards that live in gaps. */
    private int ownerOf(Element h) {
        if (h.anchor < 0 || h.anchor >= c.size()) return -1;
        Element o = c.get(h.anchor);
        boolean on = (h.type == Element.Type.SPIKE_TRAP || h.type == Element.Type.SAW_H || h.type == Element.Type.SPIKE_BLOCK || h.type == Element.Type.SPIKE_DROP || h.type == Element.Type.GATE || h.type == Element.Type.CRAB) && o.isPlatform() && Math.abs(hzS(h) - o.s) < o.w * 0.5f;
        return on ? h.anchor : -1;
    }

    private float windowNeeded(float y) { return T.minTimingWindow - 0.06f * intensity(y); }

    private boolean commit(List<Element> es, float minMargin) { return commit(es, new ArrayList<Element>(), minMargin); }

    /** Adds elements (and their hazards) if every link (prefix last -> e1 -> ... -> eN) is solvable with the required margin and timing window. */
    private boolean commit(List<Element> es, List<Element> hz, float minMargin) {
        int n0 = c.size(), h0 = c.hazards.size();
        for (Element e : es) c.add(e);
        for (Element h : hz) c.hazards.add(h);
        boolean ok = true;
        for (int i = n0; i < c.size() && ok; i++) ok = clearOfOtherLayers(i);
        if (!ok) failLayer++;
        for (Element h : hz) { if (!ok) break; if (h.type == Element.Type.CLUB) continue; ok = hazardOk(h, ownerOf(h)); if (!ok) failHaz++; }
        int from = n0 - 1;
        for (Element h : hz) if (h.type == Element.Type.CANNON && h.anchor == n0 - 1 && n0 >= 2) from = n0 - 2;          // a cannon fires across the platform the previous link LANDS on: that link must be proven with the cannon there too
        for (int i = from; i < c.size() - 1 && ok; i++) {
            Sim sim = Sim.startOn(c, T, i);
            Autopilot.Result res = Autopilot.plan(sim, i, true);
            boolean touched = false;
            for (Element h : hz) if (h.anchor == i || h.anchor + 1 == i || h.anchor == i + 1) touched = true;
            if (!res.ok || res.margin() < Math.max(minMargin, 0f)) { ok = false; failPlan++; if (trace.length() < 600) trace.append(String.format("[link %d %s->%s ok=%b %d/%d need %.3f]", i, c.get(i).type, c.get(i + 1).type, res.ok, res.successes, res.trials, minMargin)); }
            else if (touched && res.window < windowNeeded(c.get(i).y)) { ok = false; failWindow++; }
            else if (Autopilot.hasMidHazard(c, i)) {       // a person never stands on the exact spot the planner starts from: the move must work from nearby too
                for (float dx : new float[]{-0.5f, 0.4f}) {
                    Sim b = Sim.startOn(c, T, i); b.s = c.wrap(b.s + dx);
                    if (!Autopilot.plan(b, i, false).ok) { ok = false; failPlan++; break; }
                }
            }
        }
        if (!ok) { while (c.size() > n0) c.elements.remove(c.size() - 1); while (c.hazards.size() > h0) c.hazards.remove(c.hazards.size() - 1); }
        else if (!hz.isEmpty()) { hazardsPlaced += hz.size(); }
        hazardTried += hz.isEmpty() ? 0 : 1;
        return ok;
    }

    private Kind pick(int zone, Element last) {
        float[] w = new float[Kind.values().length];
        switch (zone) {
            case 0: w[Kind.HOP.ordinal()] = 3; w[Kind.STAIRS.ordinal()] = 2; w[Kind.CRUMBLE.ordinal()] = 1; w[Kind.PAD.ordinal()] = 1.5f; w[Kind.ROPE.ordinal()] = 1.5f;
                    w[Kind.MOVER_H.ordinal()] = 1.5f; w[Kind.MOVER_V.ordinal()] = 1.5f; w[Kind.SWING.ordinal()] = 1.5f; w[Kind.MOVER_Z.ordinal()] = 1.5f; break;
            case 1: w[Kind.HOP.ordinal()] = 2; w[Kind.STAIRS.ordinal()] = 1; w[Kind.CRUMBLE.ordinal()] = 2; w[Kind.PAD.ordinal()] = 2; w[Kind.ROPE.ordinal()] = 2;
                    w[Kind.MOVER_H.ordinal()] = 2.5f; w[Kind.MOVER_V.ordinal()] = 1.5f; w[Kind.CABLE.ordinal()] = 1.5f; break;
            case 2: w[Kind.HOP.ordinal()] = 1.5f; w[Kind.CRUMBLE.ordinal()] = 2; w[Kind.PAD.ordinal()] = 2; w[Kind.ROPE.ordinal()] = 2; w[Kind.MOVER_H.ordinal()] = 2;
                    w[Kind.MOVER_V.ordinal()] = 2; w[Kind.CABLE.ordinal()] = 2; w[Kind.SWING.ordinal()] = 3; w[Kind.GRAB.ordinal()] = 2; break;
            default: w[Kind.HOP.ordinal()] = 1; w[Kind.CRUMBLE.ordinal()] = 2; w[Kind.PAD.ordinal()] = 2; w[Kind.ROPE.ordinal()] = 2; w[Kind.MOVER_H.ordinal()] = 2;
                    w[Kind.MOVER_V.ordinal()] = 2; w[Kind.CABLE.ordinal()] = 2; w[Kind.SWING.ordinal()] = 3; w[Kind.GRAB.ordinal()] = 3;
        }
        if (zone >= 1) { w[Kind.MOVER_Z.ordinal()] = 2.5f; w[Kind.ELEV_FALL.ordinal()] = 2f; w[Kind.SLIDE_FALL.ordinal()] = 2f; if (zone == 1) w[Kind.SWING.ordinal()] = 2.5f; }
        float inten = intensity(last.y);
        w[Kind.RAMP.ordinal()] = zone == 0 ? 1.5f : 3f;
        if (zone >= 1 && inten > 0f) w[Kind.MORTAR.ordinal()] = 1f + 3f * inten;          // a mortar row: any stage after the first world
        if (zone >= 1) { w[Kind.SEESAW.ordinal()] = 4f; w[Kind.BRIDGE.ordinal()] = 4f; }
        else w[Kind.BRIDGE.ordinal()] = 2f;
        if (inten > 0f || (!endless && last.y > T.courseHeight * 0.18f)) { w[Kind.HAZ.ordinal()] = 3f + 9f * inten; if (zone >= 1) w[Kind.SPRING.ordinal()] = 2f + 2f * inten; w[Kind.TRAP.ordinal()] = 2f + 5f * inten; }
        // keep the spiral pitch healthy: if climbing lags the arc travelled, prefer vertical modules
        // spiral pitch controller: track y = pitch * (arc travelled / circumference) so successive revolutions stay a fixed distance apart
        float guide = T.spiralPitch * (last.s - 4f) / c.circumference;
        float dev = last.y - guide;
        boolean lagging = dev < -2f, rushing = dev > 7f;
        for (Kind k : Kind.values()) {
            boolean vert = k == Kind.PAD || k == Kind.ROPE || k == Kind.MOVER_V || k == Kind.STAIRS || k == Kind.ELEV_FALL;
            if (lagging && !vert) w[k.ordinal()] *= 0.12f;
            if (rushing && vert) w[k.ordinal()] *= 0.2f;
        }
        if (prev != null) { w[prev.ordinal()] *= 0.35f; if (prev == prev2) w[prev.ordinal()] = 0f; }
        float sum = 0; for (float f : w) sum += f;
        float x = rnd.nextFloat() * sum;
        for (Kind k : Kind.values()) { x -= w[k.ordinal()]; if (x <= 0 && w[k.ordinal()] > 0) return k; }
        return Kind.HOP;
    }

    public static volatile int wetTried, wetOk;          // diagnostics: wet-platform modules attempted / proven

    private boolean tryModule(Kind k, float d, int zone) {
        Element last = c.get(c.size() - 1);
        for (int attempt = 0; attempt < 8; attempt++) {
            float dd = Math.max(0f, d * (1f - attempt * 0.14f));
            List<Element> hz = new ArrayList<>();
            List<Element> es;
            if (k == Kind.HAZ) es = buildHaz(last, Math.max(dd, 0.35f), zone, attempt, hz);
            else if (k == Kind.TRAP) es = buildMid(last, Math.max(dd, 0.35f), zone, attempt, hz);
            else if (k == Kind.WET) es = buildWet(last, dd, zone, attempt);
            else if (k == Kind.MORTAR) es = buildMortar(last, Math.max(dd, 0.35f), zone, attempt, hz);
            else es = build(k, last, dd, zone, attempt);
            if (es == null) continue;
            float margin = k == Kind.GRAB ? Math.min(0.05f, T.minLinkMargin) : T.minLinkMargin;
            boolean ok = commit(es, hz, margin);
            if (k == Kind.WET) { wetTried++; if (ok) wetOk++; }
            if (ok) return true;
        }
        return false;
    }

    private int anchorOf(Element e, List<Element> es) {
        int i = es.indexOf(e);
        return i >= 0 ? c.size() + i : c.size() - 1;      // elements not yet added are appended at the end of the course
    }

    private Element hz(Element.Type t, float s, float y, float w, int zone) {
        Element h = new Element(t, s, y, w); h.zone = zone; return h;
    }

    /** A wide platform under a rain cloud (the hero slides on it): a hop to a platform 4.5-5.5 m wide, marked wet. Proven like any other module; the link OUT of it is proven by the next module with the slippery physics. */
    private List<Element> buildWet(Element last, float d, int z, int attempt) {
        float eR = last.s + last.w / 2f, dy = r(0f, 0.6f);
        float gap = reach(dy) * lerp(0.45f, 0.8f, d) * r(0.9f, 1.05f) * (1f - 0.03f * attempt), w = r(4.5f, 5.5f);
        Element p = plat(Element.Type.STATIC, eR + gap + w / 2f, last.y + dy, w, z);
        p.color = Element.WET;
        List<Element> l = new ArrayList<>(); l.add(p); return l;
    }

    /** Depth of a mortar's muzzle below the path, and how high above the path its ball climbs (about twice the player's height). */
    static final float MORTAR_DEPTH = 3.0f;

    /**
     * A row of five or six level islands with a mortar in the ground under every gap, pointing straight up: the ball rises through the gap to about twice the player's height above the path, falls back
     * into the barrel and fires again. Neighbouring mortars are out of step (a travelling wave), so there is always a moment to cross each gap. Every link is proven by {@link #commit} like any hazard gap.
     */
    private List<Element> buildMortar(Element last, float d, int z, int attempt, List<Element> hzOut) {
        float inten = intensity(last.y);
        int n = 5 + rnd.nextInt(2);
        List<Element> es = new ArrayList<>();
        Element cur = last; int base = c.size();
        float period = r(3.2f, 4.0f) - 0.4f * inten, stagger = r(0.9f, 1.7f), phase0 = r(0f, 6.28f);
        for (int k = 0; k < n; k++) {
            float uR = cur.s + cur.w / 2f;
            float g = Math.min(3.0f, Math.max(2.2f, reach(0f) * lerp(0.55f, 0.72f, d) * r(0.95f, 1f) * (1f - 0.03f * attempt)));
            float w = k == n - 1 ? 3.5f : r(2.4f, 3.2f);
            Element v = plat(Element.Type.STATIC, uR + g + w / 2f, cur.y, w, z);
            es.add(v);
            Element m = hz(Element.Type.MORTAR, uR + g / 2f, cur.y - MORTAR_DEPTH, 0f, z);
            m.amp = MORTAR_DEPTH + 2f * T.height; m.period = period; m.phase = phase0 + k * stagger;
            m.anchor = k == 0 ? base - 1 : base + k - 1;
            hzOut.add(m);
            cur = v;
        }
        return es;
    }

    /** One or two hazard-guarded gaps: a saw blade, a spiked pendulum-free blade, a cannon lane, or a spiked block you must clear. */
    private List<Element> buildHaz(Element last, float d, int z, int attempt, List<Element> hzOut) {
        float inten = intensity(last.y);
        int n = (inten > 0.35f && rnd.nextFloat() < inten * 0.6f) ? 2 : 1;
        List<Element> es = new ArrayList<>();
        Element cur = last;
        int base = c.size();
        for (int j = 0; j < n; j++) {
            float uR = cur.s + cur.w / 2f, dy = r(-0.3f, 0.6f);
            boolean cannonOk = cur.w >= 3f && cur.type == Element.Type.STATIC;
            int roll = rnd.nextInt(cannonOk ? 5 : 4);
            if (roll == 1) dy = Math.min(dy, 0.1f);      // a saw never guards an uphill jump: the blade would sit higher than the take-off
            float g = Math.min(3.05f, Math.max(2.3f, reach(Math.max(0f, dy)) * lerp(0.68f, 0.90f, d) * r(0.95f, 1f) * (1f - 0.03f * attempt)));
            if (roll == 1) g = Math.min(g, 2.6f);      // a saw hangs in the gap: keep the gap well short of a full-length jump so there is room to time the arc over the blade
            float w = j == n - 1 ? 3f + rnd.nextInt(2) : 2.5f;
            Element v = plat(Element.Type.STATIC, uR + g + w / 2f, cur.y + dy, w, z);
            es.add(v);
            int anchor = (j == 0) ? c.size() - 1 : base + j - 1;      // the platform the gap starts at
            float top = Math.max(cur.y, v.y), mid = uR + g / 2f;
            // pick a hazard that fits this gap
            Element h;
            if (roll == 0) {
                h = hz(Element.Type.SAW_V, mid + r(-0.1f, 0.1f), top + 0.35f, 0f, z);
                h.amp = r(1.5f, 2.3f); h.period = r(2.6f, 3.6f) - 0.5f * inten;
            } else if (roll == 1) {
                h = hz(Element.Type.SAW_H, mid, top + 0.62f + r(0f, 0.2f), 0f, z);      // blade top 1.17-1.37 m above the higher ledge: a normal jump arc clears it near its top
                h.amp = Math.max(0.2f, Math.min(0.45f, g / 2f - 0.92f)); h.period = r(2.6f, 3.4f) - 0.4f * inten;
            } else if (roll == 3) {
                h = hz(Element.Type.BEE, mid, top + 1.1f, 0f, z);                  // a bee flies in, buzzes around the gap, dives at you, and leaves
                h.amp = Math.max(0.15f, Math.min(0.8f, g / 2f - 0.95f)); h.len = 0.8f; h.period = r(7.0f, 9.5f) - 1.5f * inten;
            } else if (roll == 2 || (roll == 1)) {
                h = hz(Element.Type.SPIKE_BLOCK, mid, top - 1.1f, r(0.9f, 1.3f), z);      // a spiked stone block in the gap: don't drop into it
                h.len = 1.3f; h.period = 4f;
            } else {
                float muzzle = cur.s - cur.w / 2f + 0.7f;
                h = hz(Element.Type.CANNON, muzzle, cur.y + 2.35f, 0f, z);
                h.len = (uR - muzzle) + g + 1.2f; h.dir = 1; h.period = r(3.0f, 4.0f) - 0.5f * inten;
            }
            h.phase = r(0f, 6.28f); h.anchor = anchor;
            hzOut.add(h);
            cur = v;
        }
        return es;
    }

    /** A wide platform with a hazard in the middle and a safe pocket at each end: land, wait for the right moment, cross. */
    private List<Element> buildMid(Element last, float d, int z, int attempt, List<Element> hzOut) {
        float inten = intensity(last.y);
        float uR = last.s + last.w / 2f, dy = r(0f, 0.6f);
        float gapIn = reach(dy) * lerp(0.5f, 0.85f, d) * (1f - 0.03f * attempt);
        int roll = rnd.nextInt(5);
        float w = roll == 4 ? 9f : 8f;
        Element p = plat(Element.Type.STATIC, uR + gapIn + w / 2f, last.y + dy, w, z);
        List<Element> es = new ArrayList<>(); es.add(p);
        Element h;
        if (roll == 0) {                // pop-up spikes: run through while they are down
            h = hz(Element.Type.SPIKE_TRAP, p.s, p.y, 2.4f, z);
            h.period = r(3.2f, 4.2f); h.amp = lerp(0.30f, 0.40f, inten);
        } else if (roll == 1) {         // a sawblade sliding along the floor: hop it
            h = hz(Element.Type.SAW_H, p.s, p.y + 0.62f, 0f, z);
            h.amp = 1.1f; h.period = r(3.0f, 4.0f) - 0.5f * inten;
        } else if (roll == 2) {         // a spiked stone block in the way: hop it
            h = hz(Element.Type.SPIKE_BLOCK, p.s, p.y - 0.1f, 1.3f, z);
            h.len = 1.0f; h.period = 4f;
        } else if (roll == 3) {         // a spiked stone slab on a chain that slams down on the middle of the platform: slip under it while it is raised
            h = hz(Element.Type.SPIKE_DROP, p.s, p.y, 1.8f, z);
            h.amp = 3.3f; h.period = r(3.4f, 4.4f) - 0.4f * inten;
        } else {                        // a crab patrols the middle and shoves you around; a spiked club floats on the platform before it, if you want to clear it
            h = hz(Element.Type.CRAB, p.s, p.y, 0f, z);
            h.amp = 1.4f; h.period = r(3.0f, 4.2f) - 0.4f * inten;
            if (last.w >= 3f && last.isPlatform() && rnd.nextInt(3) > 0) {
                Element club = hz(Element.Type.CLUB, last.s, last.y + 1.0f, 0f, z); club.anchor = c.size() - 1; club.phase = r(0f, 6.28f);
                hzOut.add(club);
            }
        }
        h.phase = r(0f, 6.28f); h.anchor = c.size();      // the platform being added (first appended element)
        hzOut.add(h);
        return es;
    }

    // ------------------------------------------------------------------ modules

    private List<Element> build(Kind k, Element last, float d, int z, int attempt) {
        List<Element> l = new ArrayList<>();
        float eR = last.s + last.w / 2f, y0 = last.y;
        Element.Type S = Element.Type.STATIC;
        switch (k) {
            case HOP: {
                float dy = tier(last.y) == 0 ? r(0f, 0.8f) : r(0f, 1.4f);
                float frac = lerp(0.45f, 0.92f, d) * r(0.9f, 1.1f);
                float w = 2 + rnd.nextInt(3);
                if (tier(last.y) == 3 && rnd.nextBoolean()) w = 1 + rnd.nextInt(2);
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
            case MOVER_Z: {          // slides toward and away from the camera: step on while it is in your plane
                float amp = 3.0f + 0.8f * d, wm = 3f, gapIn = lerp(1.0f, 1.8f, d), gapOut = lerp(1.0f, 1.8f, d);
                Element m = plat(Element.Type.MOVE_Z, eR + gapIn + wm / 2, y0 + 0.3f, wm, z);
                m.amp = amp; m.period = r(4.6f, 6.0f); m.phase = r(0f, 6.28f);
                l.add(m);
                l.add(plat(S, m.s + wm / 2 + gapOut + 1.5f, m.y + r(0.3f, 1.0f), 3f, z));
                break;
            }
            case SLIDE_FALL: {       // a slider that falls apart a moment after you step on it
                float amp = 1.2f + 0.8f * d, wm = 2.5f, gapIn = lerp(1.0f, 1.6f, d), gapOut = lerp(1.0f, 1.6f, d);
                Element m = plat(Element.Type.MOVE_H, eR + gapIn + amp + wm / 2, y0 + 0.3f, wm, z);
                m.skin = 1; m.amp = amp; m.period = r(3.0f, 4.0f); m.phase = r(0f, 6.28f);
                l.add(m);
                l.add(plat(S, m.s + amp + wm / 2 + gapOut + 1.5f, m.y + r(0.3f, 0.8f), 3f, z));
                break;
            }
            case ELEV_FALL: {        // an elevator that falls apart while you ride it: be quick
                float rise = 2.2f + 0.8f * d;
                Element m = plat(Element.Type.MOVE_V, eR + 1.0f + 1.5f, y0, 3f, z);
                m.skin = 1; m.amp = rise; m.period = r(3.0f, 3.8f); m.phase = r(0f, 6.28f);
                l.add(m);
                l.add(plat(S, m.s + 1.5f + 1.0f + 1.5f, y0 + rise, 3f, z));
                break;
            }
            case PAD: {
                int pads = (tier(last.y) >= 1 && d > 0.3f && rnd.nextBoolean()) ? 2 : 1;
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
            case SPRING: {
                float ang = r(0.32f, 0.62f) * (1f - 0.04f * attempt);
                float v = T.padBounce, vy0 = v * (float) Math.cos(ang), vx0 = v * (float) Math.sin(ang);
                float gap = lerp(1.0f, 1.8f, d) * r(0.9f, 1.1f);
                float sS = eR + gap + 1f;
                float dy = r(-0.8f, 2.8f);
                float disc = vy0 * vy0 - 2 * T.gravity * dy;
                if (disc < 1f) dy = 0f;
                float tf = (vy0 + (float) Math.sqrt(Math.max(1f, vy0 * vy0 - 2 * T.gravity * dy))) / T.gravity;
                Element sp = plat(Element.Type.SPRING, sS, y0, 2f, z); sp.amp = ang;
                l.add(sp);
                l.add(plat(S, sS + vx0 * tf * r(0.86f, 0.97f) + 1.2f, y0 + dy, 3.5f, z));
                break;
            }
            case SEESAW: {
                // a wooden plank balanced on a pivot: it tips toward you, so run across; the far side is a short hop to the next block
                float w = 7f + rnd.nextInt(2), dyIn = r(-0.4f, 0.3f);
                float gapIn = reach(dyIn) * lerp(0.5f, 0.85f, d) * r(0.92f, 1f) * (1f - 0.03f * attempt);
                Element sw = plat(Element.Type.SEESAW, eR + gapIn + w / 2f, y0 + dyIn, w, z); sw.skin = 1;
                l.add(sw);
                float gapOut = lerp(0.5f, 1.3f, d) * r(0.9f, 1.1f) * (1f - 0.05f * attempt);
                l.add(plat(S, sw.s + w / 2f + gapOut + 1.5f, sw.y + r(-0.5f, 0.6f), 3f, z));
                break;
            }
            case BRIDGE: {
                // a floating wooden bridge; sometimes a rope hangs above its middle to climb off to a higher block
                float w = 6f + rnd.nextInt(3), dy = r(0f, 0.9f);
                float gapIn = reach(dy) * lerp(0.45f, 0.85f, d) * r(0.9f, 1f) * (1f - 0.03f * attempt);
                Element br = plat(S, eR + gapIn + w / 2f, y0 + dy, w, z); br.skin = 1;
                l.add(br);
                if (z >= 1 && rnd.nextInt(5) < 3) {
                    float L = r(4.5f, 6f);
                    Element rope = plat(Element.Type.ROPE, br.s + (rnd.nextBoolean() ? 1f : -1f) * r(0.7f, 1.5f), br.y + 0.7f + L, 0f, z);
                    rope.len = L;
                    l.add(rope);
                    l.add(plat(S, rope.s + lerp(1.0f, 2.2f, d) + 1.5f, rope.y - T.handHeight + 0.4f, 3f, z));
                } else {
                    l.add(plat(S, br.s + w / 2f + reach(0.6f) * lerp(0.4f, 0.8f, d) * (1f - 0.04f * attempt) + 1.5f, br.y + 0.6f, 3f, z));
                }
                break;
            }
            case RAMP: {
                // a walkway up to a block that is taller than a jump; from the second world it may crumble, shake you about, or sink away
                float sc = r(1.3f, 1.75f), L = 2.2f * sc, slope = RAMP_SLOPE, rise = slope * L;
                int skin = 0;
                if (z >= 1) { int roll = rnd.nextInt(8); skin = roll < 2 ? 0 : roll < 4 ? 1 : roll < 6 ? 2 : 3; }
                float gapIn = r(0.05f, 0.45f);
                Element rp = plat(Element.Type.RAMP, eR + gapIn + L / 2f, y0 + rise / 2f, L, z); rp.amp = slope; rp.skin = skin; rp.len = sc;
                l.add(rp);
                float wB = 3f + rnd.nextInt(2);
                if (z >= 1 && rnd.nextInt(10) < 4) {         // ski jump: leap off the top of the ramp across a gap to a block up there
                    float dyJ = r(0f, 0.7f), gapJ = reach(dyJ) * lerp(0.5f, 0.85f, d) * r(0.92f, 1f) * (1f - 0.04f * attempt);
                    l.add(plat(S, rp.s + L / 2f + gapJ + wB / 2f, y0 + rise + dyJ, wB, z));
                    break;
                }
                Element blk = plat(S, rp.s + L / 2f + wB / 2f + r(0f, 0.25f), y0 + rise + r(0f, 0.1f), wB, z);
                l.add(blk);
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
