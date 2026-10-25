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
    enum Kind { HOP, STAIRS, CRUMBLE, MOVER_H, MOVER_V, PAD, ROPE, CABLE, SWING, GRAB, HAZ, TRAP, SPRING, SEESAW, BRIDGE, RAMP, MOVER_Z, ELEV_FALL, SLIDE_FALL, MORTAR, WET,
        /** A long walkable corridor (owner request): room to walk between scenery and stand fully in front of the back wall. */
        LONG,
        /** Build 71: a creature's deck - a skull on a long fixed platform or bridge (from the second world), or a green blob on a long platform or a tipping bridge (from the third). */
        CREATURE,
        /** Build 71: a row of platforms, each under its own chained spike drop (own speed, own top height, none low): like a mortar row, cross each when its slab is up. */
        DROPS,
        /**
         * Owner directive 2026-10-24 (sections 15-16): a split. The route part of it reserves the room a second way needs: an entry platform, a wide gap
         * to drop through, a gentle climb with full headroom beneath it, and a wide gap before the rejoin platform. The branch below it is built in the
         * decoy phase ({@link #tryReservedFork}) and proven like every other link; if it cannot be proven the route stays as a plain climb.
         */
        FORK }

    private final Tuning T;
    private final DifficultyProfile P;          // what this difficulty changes (owner 2026-10-25: one shared generator, difficulty as configuration)
    private final Random rnd;
    private final Course c;
    public int rejected;       // modules re-planned easier (diagnostics)
    private Kind prev, prev2;
    private int rests;
    private int ctx = 1;                    // leading elements that are context from the previous slice
    private boolean endless;
    private float rampH;
    private int clubBoost;               // Build 71: modules left that should carry a crab or a bee for the club just placed
    private boolean wantCreature;        // the module being built should be a crab (TRAP) or a bee (HAZ)
    private boolean relaxed;            // last-resort mode (see chunkIn)
    private double base;                    // framed generation: absolute height = base + local y (a multiple of the castle spacing); 0 for the classic absolute-frame slices
    private List<Element> ghostElems = new ArrayList<>();   // earlier-slice elements, only consulted for spiral-layer clearance
    private List<Element> ghostHaz = new ArrayList<>();
    public StringBuilder trace = new StringBuilder();
    public int hazardsPlaced, hazardTried, failLayer, failHaz, failPlan, failWindow;

    /**
     * The generation rules version (Build 64): every slice is a pure function of the seed, the slice index, the previous slice, the tuning and this code, so two
     * players on the same seed climb the same course only while this number matches. Bump it whenever generation, its proofs (the Sim the planner runs),
     * the hazards' initial configuration or the bundled tuning change what a seed produces; UI and other changes leave it alone. Version 1 is the Build 63
     * generator (the crab rules of Build 63 changed what a seed produced, so runs from earlier builds carry version 0, "old rules", and are not compared).
     * {@code GeneratorFingerprintTest} fails when generation changes without a bump.
     */
    public static final int RULES_VERSION = 8;      // 8 = Build 81 (owner decisions 2026-10-25): keys lie on a main-route platform (no rooms, branches or perches); 7 = Build 80 (owner directive 2026-10-24): no level gates, PUNISHER on its own curve, encounter-conflict rule, splits with reconnecting branches instead of dead ends; 6 = Build 79: skulls, blobs and crabs reach the platforms either side (crabs follow up to three, fall with crumbling decks), faster skull, no blob wobble, two to four crabs a deck, bee pairs, creatures on spike-drop rows, bridges that drop planks, flat-turning aimed cannons, an always-running balance conveyor, decoys kept off ropes, a slightly harder first world; 5 = Build 77: faster skull with loop+swoop, key detours proved with the skull at either end, one balance conveyor per section from Castle 6 to 10; 4 = Build 76: faster plank conveyor bridges; 3 = Build 72: flush ramps, faster crumbling, a wilder shaking ramp, the spinning ramp; 2 = Build 71: the bee homes in, skulls and green blobs, mortars out of step at random
    /** The rules version a climb started on a given build used (0 = unknown or before the stamps: not comparable). */
    public static int rulesOfBuild(int build) { return build >= 81 ? 8 : build >= 80 ? 7 : build >= 79 ? 6 : build >= 77 ? 5 : build >= 76 ? 4 : build >= 72 ? 3 : build >= 71 ? 2 : build >= 63 ? 1 : 0; }

    private CourseGenerator(long seed, Tuning t, long salt) {
        T = t; P = DifficultyProfile.of(t); this.salt = salt; rnd = new Random(seed * 0x9E3779B97F4A7C15L + 12345 + salt * 0x632BE59BD9B4E019L); c = new Course(seed, t.circumference());
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
        for (int[] f : src.forks) out.forks.add(f.clone());
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
        return Math.min(1f, Math.max(0f, (float) ((y + base + modeShift() - start) / ramp)));
    }

    /**
     * Owner directive 2026-10-24 (two 10-level curves): PUNISHER reads the whole difficulty curve this far further up the tower, so its Level 1 plays like
     * NORMAL's Levels 3-4 and its later levels go past anything NORMAL reaches. Only the curve moves: the worlds, castles and gems stay at their heights.
     */
    private float modeShift() { return endless ? P.curveShift : 0f; }
    /**
     * Where height y sits in the 10-level timed climb, as a difficulty from 0 (NORMAL Level 1) to 1 (NORMAL's top castle), and beyond 1 for PUNISHER's
     * last levels. Every mechanic's chance of appearing grows with it smoothly; none is locked behind a level (directive section 6).
     */
    float level(float y) {
        if (!endless) return diff(y);
        return Math.max(0f, (float) ((y + base + modeShift()) / (10.0 * T.castleSpacing)));
    }

    /**
     * Build 71 (owner: traps and creatures should keep getting harder, chaining more together): 0 until the ordinary ramp is complete (intensity reaches 1 at
     * about 1,170 m), then rising to 1 over the next LATE_RAMP metres. It lengthens hazard chains and mortar and spike-drop rows and makes creature decks and
     * creature-then-trap pairs more common; every link is still proven.
     */
    static final float LATE_RAMP = 3800f;
    float late(float y) {
        float done = endless ? T.hazardStartY + T.hazardRampY : T.courseHeight;
        return Math.min(endless ? P.lateCap : 1f, Math.max(0f, (float) ((y + base + modeShift() - done) / LATE_RAMP)));          // PUNISHER keeps climbing past NORMAL's top (its head start would otherwise flatten it from Level 8)
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
        castleTarget = 0f; castleDone = false; exactCastle = false; keyN = 0; keyY = 0f; gemBest = -1; beltSawY = 0f; beltSawDone = false; beltSawNow = false;
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
            if (endless) for (int n = Math.max(BELT_SAW_FIRST, (int) Math.floor(startAbs / sp) + 1); n <= Math.min(BELT_SAW_LAST, (int) Math.floor((startAbs + 80.0) / sp) + 1); n++) {
                float hb = (float) (beltSawHeight(c.seed, n, T.castleSpacing) - base);
                if (hb > startY && hb <= startY + 80f) { beltSawY = hb; break; }
            }
        }
        float top = keyN > 0 ? Math.max(startY + T.chunkHeight, keyY + 6f) : startY + T.chunkHeight;
        extend(beltSawY > 0f ? Math.max(top, beltSawY + 12f) : top);
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
        for (int[] f : c.forks) if (f[0] >= off) out.forks.add(new int[]{f[0] - off, f[1] - off, f[2], f[3] - off});
        out.routeCount = rs - off;
        out.indexDecoys();
        return out;
    }

    /** Appends modules until the last platform reaches height {@code targetY}. */
    private void extend(float targetY) {
        if (castleTarget > 0f && !castleDone) targetY = Math.max(targetY, castleTarget + (exactCastle ? 8f : 6f));
        int sinceRest = 0;
        float lastRestY = c.get(c.size() - 1).y, nextGap = T.gemSpacing * r(0.85f, 1.15f);      // red-gem checkpoints at even stretches of height, each with a little random give
        while (c.get(c.size() - 1).y < targetY || (beltSawY > 0f && !beltSawDone && c.get(c.size() - 1).y < beltSawY + 40f)) {      // (a balance conveyor that could not be proven yet gets up to 40 m more tries)
            Element last = c.get(c.size() - 1);
            float d = diff(last.y);
            int zone = tier(last.y), th = theme(last.y);
            boolean castlePending = castleTarget > 0f && !castleDone;
            if (castlePending && exactCastle && last.y >= castleTarget - 6f) { buildExactCastle(th); castleDone = true; lastRestY = c.get(c.size() - 1).y; continue; }
            if (castlePending && !exactCastle && last.y >= castleTarget - GEM_LEAD) { chooseGem(th); castleDone = true; lastRestY = c.get(c.size() - 1).y; continue; }
            if (last.y - lastRestY >= nextGap && targetY - last.y > 0.4f * T.gemSpacing && !(castlePending && last.y > castleTarget - 14f)) {      // (not right before the slice's own closing rest)
                addRest(th); sinceRest = 0; lastRestY = c.get(c.size() - 1).y; nextGap = T.gemSpacing * r(0.85f, 1.15f); maybeCastle(th); continue;
            }
            float forkEvery = P.forkEvery;          // PUNISHER splits about twice as often, and its splits are longer
            if (Float.isNaN(lastForkY)) lastForkY = last.y - forkEvery * 0.5f;
            if (last.y - lastForkY >= forkEvery && last.isPlatform() && !last.isMoving() && !last.wet() && !(castlePending && last.y > castleTarget - 24f)) {
                int e0 = c.size(), n = P.forkRisers;
                forkRisers = n;
                if (tryModuleBelow(Kind.FORK, d, th)) { forkEntries.add(new int[]{e0, n}); lastForkY = last.y; prev2 = prev; prev = Kind.FORK; sinceRest++; continue; }
                lastForkY = last.y - forkEvery + 8f;          // no room here: try again a little higher
            }
            Kind k = pick(zone, last);
            boolean clubTarget = false;
            if (clubBoost > 0) { clubBoost--; k = rnd.nextBoolean() ? Kind.TRAP : Kind.HAZ; clubTarget = true; }      // Build 71 (owner): a club was just placed - the next few modules give it crabs and bees to hit
            wantCreature = clubTarget;
            if (th == 1 && last.type == Element.Type.STATIC && rnd.nextFloat() < 0.2f) k = Kind.WET;          // the Frost world: some platforms are under a rain cloud (slippery)
            float lv = Math.min(1f, level(last.y));
            if (k != Kind.WET && prev != Kind.CREATURE && !last.wet() && rnd.nextFloat() < 0.025f + 0.045f * lv + 0.05f * late(last.y)) k = Kind.CREATURE;      // owner directive 2026-10-24: creature decks from Level 1 on, more as the levels rise (was world 2 on)
            else if (prev == Kind.CREATURE && !last.wet() && rnd.nextFloat() < 0.35f * late(last.y)) k = rnd.nextBoolean() ? Kind.HAZ : Kind.TRAP;      // Build 71: higher up, a creature deck runs straight into a trap      // Build 71: a skull's deck (world 2 on), or a green blob's (world 3 on)
            boolean secondHalf = endless && th == 0 && ((last.y + base) % T.zoneHeight) >= 0.5f * T.zoneHeight - 15f;      // castle 1 to castle 2: the Meadow's second, harder half (owner, Build 71)
            if (k != Kind.WET && k != Kind.CREATURE && !last.wet() && prev != Kind.DROPS && (secondHalf ? rnd.nextFloat() < 0.10f : rnd.nextFloat() < 0.015f + 0.025f * lv)) k = Kind.DROPS;      // (spike-drop rows anywhere, more higher up; was world 2 on)
            else if (secondHalf && (k == Kind.HOP || k == Kind.STAIRS) && rnd.nextFloat() < 0.3f) k = Kind.HAZ;          // a little rougher than the first half
            if (last.wet() && (k == Kind.HAZ || k == Kind.TRAP || k == Kind.MORTAR || k == Kind.CREATURE || k == Kind.DROPS)) k = Kind.STAIRS;          // after a slippery platform give a walk-up (not a hazard you must time, nor a jump you must push off a slippery surface for): its timing windows assume a normal run-up (SawFairnessTest) and the plain climb stays walkable
            if (beltSawY > 0f && !beltSawDone && last.y >= beltSawY && !last.wet() && !(castlePending && last.y > castleTarget - 14f)) {      // Build 77: this section's balance conveyor (tried again on the next module if it cannot be proven here)
                beltSawNow = true; boolean ok = tryModuleBelow(Kind.SEESAW, d, th); beltSawNow = false;
                if (ok) { beltSawDone = true; prev2 = prev; prev = Kind.SEESAW; sinceRest++; continue; }
            }
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
    public int castleDue, castleBuilt, roomFailed, roomOk, roomFallback; public int[] roomWhy = new int[5], roomWhyD = new int[8];

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
    private float beltSawY; private boolean beltSawDone, beltSawNow;      // this slice's balance conveyor (Build 77): the height it starts from; built yet; the SEESAW being built now is one

    /** Deterministic key height for castle n: anywhere in the section before it (near the previous castle ... far above), clear of the exact-height structures (gem, castle). */
    public static float keyHeight(long seed, int n, float spacing) {
        float lo = spacing * (n - 1) + 20f, hi = spacing * n - 40f, mid = spacing * (n - 1) + spacing * 0.5f;
        Random r = new Random(seed * 0x9E3779B97F4A7C15L + n * 0xBF58476D1CE4E5B9L + 17L);
        for (int i = 0; i < 32; i++) { float h = lo + r.nextFloat() * (hi - lo); if (Math.abs(h - mid) >= 16f) return h; }
        return lo;
    }
    /** Build 77 (owner): sections 7-10 (Castle 6 to Castle 10) each get ONE balance conveyor, at a height fixed by the seed and the section, like the keys. */
    public static final int BELT_SAW_FIRST = 7, BELT_SAW_LAST = 10;
    public static float beltSawHeight(long seed, int n, float spacing) {
        float lo = spacing * (n - 1) + 40f, hi = spacing * n - 60f, mid = spacing * (n - 1) + spacing * 0.5f;
        Random r = new Random(seed * 0x94D049BB133111EBL + n * 0x9E3779B97F4A7C15L + 29L);
        for (int i = 0; i < 32; i++) { float h = lo + r.nextFloat() * (hi - lo); if (Math.abs(h - mid) >= 25f) return h; }
        return lo;
    }
    /** The key (and gate) colour of castle n is fixed by the run's seed and the castle number, so the key can be placed sections before its gate is built. */
    public static int castleColor(long seed, int n) { return new Random(seed * 0x2545F4914F6CDD1DL + n * 7919L + 5L).nextInt(Element.KEY_COUNT); }

    /** The halfway red gem: among the route platforms of this slice, the suitable one (a plain platform wide enough to stand and wait on) closest to the exact midpoint; if none is within 1.5 m a natural rest platform is added right where the climb crosses it. */
    private void chooseGem(int th) {
        int best = bestGemCandidate();
        // none close enough: a natural rest platform is added right here (the climb is within GEM_LEAD of the midpoint), and again if it came out too narrow to stand and wait on
        for (int tries = 0; tries < 3 && (best < 0 || Math.abs(c.get(best).y - castleTarget) > 1.5f); tries++) {
            addRest(th, true); int b2 = bestGemCandidate();
            if (b2 >= 0 && (best < 0 || Math.abs(c.get(b2).y - castleTarget) < Math.abs(c.get(best).y - castleTarget))) best = b2;
            if (b2 == c.size() - 1) break;          // the new rest is suitable
        }
        if (best < 0 || Math.abs(c.get(best).y - castleTarget) > GEM_MAX_DEV) { int w = widenForGem(); if (w >= 0) best = w; }
        if (best < 0) throw new IllegalStateException("no platform for the gem near " + castleTarget);
        Element g = c.get(best); g.skin = 3; g.checkpoint = true; gemBest = best;
    }
    /** The official rule: the halfway red gem stands within this height of the exact midpoint. */
    private static final float GEM_MAX_DEV = 4.5f;
    /**
     * Build 76: when no rest could be added near the midpoint (every spot blocked by the spiral layer a revolution above or below: seed 8, gem 9, 7.2 m high
     * once the faster plank conveyors reshaped that section) and the climb carried past it, a route platform near the midpoint that is only too narrow
     * to stand and wait on is widened to the gem's minimum width, if it then still clears the other layers and both links through it are proven again.
     * Returns its index, or -1 (the slice is unchanged).
     */
    private int widenForGem() {
        int best = -1; float bestDev = GEM_MAX_DEV;
        for (int i = Math.max(1, ctx); i < c.routeSize() - 1; i++) {
            Element e = c.get(i);
            if (e.type != Element.Type.STATIC || e.skin != 0 || e.wet() || e.w >= GEM_MIN_W || e.anchor >= 0 || Autopilot.hasMidHazard(c, i)) continue;
            float d = Math.abs(e.y - castleTarget);
            if (d > bestDev) continue;
            float old = e.w; e.w = GEM_MIN_W;
            boolean ok = clearOfOtherLayers(i);
            for (int k = i - 1; k <= i + 1 && ok; k += 2) { Element n = c.get(k); ok = Math.abs(n.y - e.y) > 1.0f || Math.abs(c.dsWrap(e.s, n.s)) - e.halfW() - n.halfW() > 0.2f; }      // never into a neighbour
            for (int j = i - 1; j <= i && ok; j++) { Sim sim = Sim.startOn(c, T, j); quietStart(sim, j); Autopilot.Result res = Autopilot.plan(sim, j, true); ok = res.ok && res.margin() >= 0.12f; }
            e.w = old;
            if (ok) { best = i; bestDev = d; }
        }
        if (best >= 0) c.get(best).w = GEM_MIN_W;
        return best;
    }
    private static final float GEM_MIN_W = 3.5f;
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

    /**
     * Owner directive 2026-10-25 (keys): the key of castle keyN is a seeded collectible lying on a main-route platform near height keyY, in the section
     * before its castle. No room, branch, pad perch or chain: the route links onto and off the platform are already proven, and the key is a walk across it.
     * Preferred: a plain platform near keyY that no roaming creature reaches and with nothing low overhead; otherwise the nearest plain route platform.
     */
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
        List<Integer> quiet = new ArrayList<>();
        for (int q = 0; q < cand.size() && quiet.size() < KEY_CHOICES; q++) { int a = cand.get(q); if (keyClear(a) && EncounterModel.threatAt(c, T, a) == 0f) quiet.add(a); }
        if (!quiet.isEmpty()) { keyOnRoute(quiet.get(rnd.nextInt(quiet.size())), color); return; }
        for (int a : cand) if (keyClear(a)) { keyOnRoute(a, color); return; }
        keyOnRoute(cand.get(0), color);
    }
    /** How many of the quiet route platforms nearest keyY the key's platform is drawn from. */
    private static final int KEY_CHOICES = 3;
    /** Nothing hangs low over route platform a (another layer's platform within head height above it), so the key there is in plain view and easy to take. */
    private boolean keyClear(int a) {
        Element A = c.get(a);
        for (int i = 0; i < c.size(); i++) {
            if (i == a) continue;
            Element o = c.get(i); float dy = o.y - A.y;
            if (o.isPlatform() && dy > 0.2f && dy < 2.6f && Math.abs(c.dsWrap(o.s, A.s)) < A.halfW() + o.halfW()) return false;
        }
        return true;
    }

    private boolean keyOnRoute(int a, int color) {
        Element A = c.get(a); float room = Math.max(0f, A.halfW() - 0.5f);
        Element key = new Element(Element.Type.KEY, A.s + (rnd.nextFloat() * 2f - 1f) * room, A.y + 0.95f, 0f);
        key.zone = A.zone; key.color = color; key.anchor = a; c.hazards.add(key);
        builtRooms.add(new Element[]{key, null}); roomIdx.add(new int[]{a, -1, 0, 2});
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
            // NORMAL (Build 80 diagnostics, seed 6 at 834 m: a skull from the next platform hit the climber setting off for the key): platforms no roaming
            // creature can reach are tried first; a platform within reach of one is used only when no quiet one takes a room, so no castle loses its key.
            for (int pass = P.quietKeyRooms ? 0 : 1; pass < 2 && !ok; pass++) {
                for (int anchor = pc[0]; anchor < gIdx && !ok; anchor++) {          // the rest platform first, then any plain platform between it and the gate
                    Element pl = c.get(anchor);
                    if (pl.type != Element.Type.STATIC || (anchor != pc[0] && pl.w < 3f)) continue;
                    if (pass == 0 && EncounterModel.threatAt(c, T, anchor) > 0f) continue;
                    for (int attempt = 0; attempt < 14 && !ok; attempt++) ok = tryKeyRoom(anchor, gate, attempt);
                }
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
            if (c.get(a).overHole(dx, -0.3f)) continue;          // Build 79: nobody waits on the planks that drop out
            Sim base = Sim.startOn(c, T, a);
            if (base.mode == Sim.Mode.GROUND) base.s = c.wrap(base.s + dx);
            else if (dx != 0f) continue;
            Autopilot.Result res = Autopilot.planPair(base, a, b, true);
            if (!res.ok || res.margin() < Math.min(T.minLinkMargin, 0.05f)) return false;
        }
        if (Sim.startOn(c, T, a).hasSkull()) {       // a skull may be waiting at either end of its deck: prove the link from the centre with it parked at the other end too (Build 77, rules 5)
            Sim base = Sim.startOn(c, T, a); base.parkSkullsAtFarEnd();
            Autopilot.Result res = Autopilot.planPair(base, a, b, true);
            if (!res.ok || res.margin() < Math.min(T.minLinkMargin, 0.05f)) return false;
        }
        return true;
    }
    private boolean pairOkAir(int a, int b) { return pairOk(a, b); }

    // ------------------------------------------------------------------ decoys: dead ends and lures that mislead without ever blocking the route

    private float wideHopY = -1e9f;
    private float lastForkY = Float.NaN;          // height of the last split laid (NaN: none yet in this slice)
    private int forkRisers;                       // risers in the split being built
    private final List<int[]> forkEntries = new ArrayList<>();          // {entry platform index, risers} of every split laid          // height of the last wide hop laid for a fork
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
        // Owner directive 2026-10-24 (sections 15-16): no dead ends. Now and then the route forks: the route itself is the quick way, and a longer branch drops
        // below it, runs on under it and bounces back up onto a later route platform. Both go forward and meet again. NORMAL forks now and then, one branch
        // at a time; PUNISHER forks more often, with longer branches, and sometimes a second branch off the first.
        for (int[] fe : forkEntries) {          // the splits the route reserved room for: their branches first
            if (fe[0] < lo || fe[0] + fe[1] + 1 > Math.min(rs - 2, hi + 6)) continue;
            if (tryReservedFork(fe[0], fe[1], dr) < 0) tryPadFork(fe[0], dr);
        }
        float chance = P.forkChance;          // of the take-offs a branch can drop from (open air past the edge: few of them)
        int spacing = P.forkSpacing, last = -100;
        for (int a = lo; a < Math.min(rs - 5, hi + 1); a++) {
            if (a - last < spacing) continue;
            Element p = c.get(a);
            if (!p.isPlatform() || p.isMoving() || p.crumbles() || p.w < 2f || p.anchor >= 0 || !forkable(a)) continue;
            if (dr.nextFloat() > chance) continue;
            int built = tryFork(a, a, dr, P.forkReach);
            if (built < 0) built = tryPadFork(a, dr);          // no room for a branch under the route here: the short fork, a bounce pad down in the gap
            if (built < 0) continue;
            last = a;
            if (P.nestedForks && c.forks.get(c.forks.size() - 1)[2] > 2 && dr.nextFloat() < 0.35f) {          // a second branch off a long one, rejoining the route further on
                int[] f = c.forks.get(c.forks.size() - 1);
                int from = f[1] + Math.max(0, f[2] / 2 - 1);
                tryFork(a, from, dr, 10);
            }
        }
        addKeyRooms();          // last, so every key-room link is proven against the final geometry
    }

    /**
     * One fork off element {@code from} (a route platform, or a platform of an earlier branch) for route platform {@code a}'s section: a branch below the
     * route that rejoins it at a route platform up to {@code maxAhead} ahead. Every link along it is proven like a key-room detour, every route link
     * nearby is proven again with it there, and it is laid out like any decoy (no overlap, no ceiling over the route). Returns the rejoined route index, or -1.
     */
    public static final int[] FORKWHY = new int[14];      // diagnostics: why fork attempts were refused
    /**
     * The branch of a split the route reserved ({@link Kind#FORK}): drop through the wide gap past entry platform {@code e} onto a landing, hop along
     * under the risers, and bounce off a pad in the wide gap before the rejoin platform up onto it. PUNISHER's longer branch has more, shorter hops and
     * a narrow foothold in the middle (a crumbling one cannot be proven by a stand-and-land link proof). Every link is proven, and the route around it is proven again with it there. Returns the rejoin index, or -1 (the route stays plain).
     */
    private int tryReservedFork(int e, int n, Random dr) {
        int rs = c.routeSize(), j = e + n + 1;
        if (j >= rs - 1) return -1;
        Element E = c.get(e), U = c.get(e + n), J = c.get(j);
        float x0 = E.s + E.halfW(), y0 = E.y;
        float padC = (U.s + U.halfW() + J.s - J.halfW()) / 2f;
        float b0 = x0 + 1.7f, bl = padC - 3.6f;
        int m = Math.max(1, Math.round((bl - b0) / P.branchHop));          // PUNISHER: more, shorter hops
        float[] wd = new float[m + 1]; float sumW = 0f;
        for (int k = 0; k <= m; k++) { wd[k] = k == 0 ? 3f : P.narrowFoothold && m >= 2 && k == m / 2 ? 1.4f : 2.4f; if (k > 0) sumW += wd[k]; }          // (PUNISHER's middle foothold is a narrow one)
        float g = (bl + wd[m] / 2f - (b0 + 1.5f) - sumW) / m;          // even gaps edge to edge, the last landing centred at bl
        if (g < 1.1f) { FORKWHY[1]++; return -1; }
        List<Element> es = new ArrayList<>();
        float py = y0, edge = b0 - 1.5f;
        for (int k = 0; k <= m; k++) {
            float x = k == 0 ? b0 : edge + g + wd[k] / 2f;
            float want = y0 - 3.2f + (J.y - 5.4f - (y0 - 3.2f)) * k / m;
            float y = Math.min(Math.min(want, ceilAt(x, y0, J.y) - 1.7f), py + 1.2f);
            Element b = plat(Element.Type.STATIC, x, y, wd[k], E.zone); b.anchor = e; es.add(b); py = y;
            edge = x + wd[k] / 2f;
        }
        float padY = Math.min(ceilAt(padC, y0, J.y) - 0.2f, Math.max(J.y - 5.6f, py - 1.0f));
        if (padY > py + 1.5f || J.y - padY > 7.0f) { FORKWHY[1]++; return -1; }
        Element pad = plat(Element.Type.PAD, padC, padY, 2f, J.zone); pad.anchor = e; es.add(pad);
        int n0 = c.size(), h0 = c.hazards.size();
        boolean ok = true;
        for (Element b : es) { c.add(b); if (!decoyOk(b, e, true)) { ok = false; FORKWHY[2 + decoyWhy]++; break; } }
        if (ok) {
            c.indexDecoys();
            ok = pairOk(e, n0); if (!ok) FORKWHY[10]++;
            for (int k = n0; ok && k < c.size() - 1; k++) { ok = pairOk(k, k + 1); if (!ok) FORKWHY[11]++; }
            if (ok) { ok = pairOkAir(c.size() - 1, j); if (!ok) FORKWHY[12]++; }
            for (int q = Math.max(0, e - 3); ok && q <= Math.min(rs - 2, j + 2); q++) { ok = Autopilot.plan(Sim.startOn(c, T, q), q, false).ok; if (!ok) FORKWHY[9]++; }
        }
        if (!ok) { rollbackTo(n0, h0); c.indexDecoys(); return -1; }
        c.forks.add(new int[]{e, n0, es.size(), j});
        decoys += es.size();
        return j;
    }

    /**
     * The short fork: a bounce pad down in the open gap past route platform {@code a}. Drop onto it and its bounce carries you up onto a route
     * platform ahead (over the next one where it reaches), or take the route's own hops. Both ways are proven, and every route link nearby is proven again with the pad
     * there. Returns the rejoined route index, or -1.
     */
    private int tryPadFork(int a, Random dr) {
        Element start = c.get(a); int rs = c.routeSize();
        float dir = c.get(a + 1).s >= start.s ? 1f : -1f, room = forkRoom(a, dir);
        if (room == Float.MAX_VALUE || room < 2.2f) return -1;
        float x = start.s + dir * (start.halfW() + room / 2f);
        float y = Math.min(ceilAt(x, start.y, start.y + 8f) - 0.2f, start.y - 2.6f - dr.nextFloat() * 0.6f);
        for (int t = Math.min(rs - 2, a + 3); t >= a + 1; t--) {          // the furthest platform the bounce can carry you to (it drifts about 4.5 m)
            Element tg = c.get(t);
            if (!tg.isPlatform() || tg.isMoving() || tg.w < 2f || tg.y - y > 7.6f || Math.abs(tg.s - x) - tg.halfW() > 4.5f) { FORKWHY[0]++; continue; }
            Element pad = plat(Element.Type.PAD, x, y, 2f, start.zone); pad.anchor = a;
            int n0 = c.size(), h0 = c.hazards.size();
            c.add(pad);
            boolean ok = decoyOk(pad, a, true);
            if (!ok) FORKWHY[2 + decoyWhy]++;
            if (ok) {
                c.indexDecoys();
                ok = pairOk(a, n0); if (!ok) FORKWHY[10]++;
                if (ok) { ok = pairOkAir(n0, t); if (!ok) FORKWHY[12]++; }
                for (int q = Math.max(0, a - 3); ok && q <= Math.min(rs - 2, t + 2); q++) { ok = Autopilot.plan(Sim.startOn(c, T, q), q, false).ok; if (!ok) FORKWHY[9]++; }
            }
            if (!ok) { rollbackTo(n0, h0); c.indexDecoys(); continue; }
            c.forks.add(new int[]{a, n0, 1, t});
            decoys++;
            return t;
        }
        return -1;
    }

    /** Open air to drop into past the end of {@code from} going {@code dir}: {@code Float.MAX_VALUE} when the next element is well above (the drop passes under it), else the gap. */
    private float forkRoom(int from, float dir) {
        Element start = c.get(from), nx = c.get(from + 1);
        if (nx.isPlatform() && nx.y - start.y > 2.1f) return Float.MAX_VALUE;          // its top beyond the hands' reach: nothing to catch on the way down
        return dir * (nx.s - start.s) - start.halfW() - nx.halfW();
    }
    private boolean forkable(int a) { float dir = c.get(a + 1).s >= c.get(a).s ? 1f : -1f; return !hazOn(a) && forkRoom(a, dir) >= 2.2f; }          // off a quiet platform
    /** The highest a 2 m branch platform centred at {@code x} may sit: below every element over it by the overlap clearance. */
    private float ceilAt(float x, float y0, float y1) {
        float ceil = Float.MAX_VALUE;
        for (int q = 0; q < c.size(); q++) { Element e = c.get(q); if (Math.abs(e.s - x) < arcHalf(e) + 1f + 1.2f && vHi(e) > y0 - 12f && e.y < y1 + 3f) ceil = Math.min(ceil, vLo(e) - 0.9f); }
        return ceil;
    }
    private boolean hazOn(int i) { for (Element h : c.hazards) if (h.anchor == i) return true; return false; }
    private int tryFork(int a, int from, Random dr, int maxAhead) {
        Element start = c.get(from);
        int rs = c.routeSize();
        int lo = a + 2, n = Math.max(1, maxAhead - 1), off = dr.nextInt(n);
        for (int attempt = 0; attempt < n; attempt++) {
            int t = lo + (off + attempt) % n;          // every candidate rejoin platform once, from a seeded start
            if (t >= rs - 1) continue;
            Element tg = c.get(t);
            if (!tg.isPlatform() || tg.isMoving() || tg.w < 2f || tg.y < start.y + 1f) { FORKWHY[0]++; continue; }
            float dir = tg.s >= start.s ? 1f : -1f;          // the route runs either way round; the branch runs with it (arc positions are unwrapped: no wrap)
            // the pad sits low enough to read as below the route, and close enough under the rejoin platform for its bounce (about 6.5 m) to reach it
            float padY = Math.max(tg.y - 5.4f, Math.min(tg.y - 3.8f, start.y - 3.2f - dr.nextFloat() * 0.4f));
            // the pad bounces up through open air next to the rejoin platform: under the gap before it (at least 1.2 m) or past a platform well below it
            Element pv = c.get(t - 1);
            float gapP = dir * (tg.s - pv.s) - tg.halfW() - pv.halfW();
            float padS;
            if (tg.y - pv.y > 2.4f) padS = tg.s - dir * (tg.halfW() + 1.9f);
            else if (gapP >= 1.2f) padS = tg.s - dir * (tg.halfW() + gapP / 2f);
            else { FORKWHY[12]++; continue; }          // (the player is 0.56 m wide: a 1.2 m gap lets a straight bounce through)
            float padCeil = ceilAt(padS, start.y, tg.y) - 0.2f;
            if (tg.y - padCeil > 7.6f) { FORKWHY[12]++; continue; }          // even the highest pad that fits there is out of the bounce's reach
            // the drop off the take-off needs open air: either the next element along is well above (the branch passes under it) or there is a gap to drop into
            float room = forkRoom(from, dir), edge = start.s + dir * start.halfW();
            float s0;
            if (room == Float.MAX_VALUE) s0 = edge + dir * (1.9f + 1f + dr.nextFloat() * 0.6f);
            else if (room >= 2.2f) s0 = edge + dir * room / 2f;          // under the gap, wide enough to drop through it without catching the next ledge (reach 0.4 m)
            else { FORKWHY[0]++; continue; }
            float span = dir * (padS - s0) - 4.0f;          // the last branch platform leaves a 2 m gap before the pad
            if (span < 0f) { FORKWHY[1]++; continue; }
            int steps = Math.max(1, (int) (span / 3.6f) + 1);          // gaps of about 1.6 m: hops that fit under the route
            List<Element> es = new ArrayList<>();
            float py = start.y;
            boolean shaped = true;
            for (int k = 0; k < steps && shaped; k++) {
                float x = steps == 1 ? s0 : s0 + dir * span * k / (steps - 1f);
                // under the route, never touching it: below every route element over this spot by the overlap clearance
                float ceil = ceilAt(x, start.y, tg.y) - 1.7f;          // headroom to hop under the route: 0.9 m clearance + 1.7 m = the body (1.25 m) and a low hop
                float goal = tg.y - 6.6f;          // the branch climbs toward where the pad can bounce onto the rejoin platform
                float want = k == 0 ? start.y - 3.2f : start.y - 3.2f + (goal - start.y + 3.2f) * k / (steps - 1f);
                float y = Math.min(Math.min(want, ceil - dr.nextFloat() * 0.3f), py + 1.5f);          // a step up the branch stays an easy one (a jump rises about 1.9 m)
                Element b = plat(Element.Type.STATIC, x, y, 2f, start.zone); b.anchor = a; es.add(b); py = y;
            }
            padY = Math.min(padCeil, Math.max(tg.y - 7.4f, py - 1.0f));          // as low as the bounce allows (held, it rises about 8.5 m): an easy hop down or across onto it
            if (!shaped || padY > py + 1.5f || tg.y - padY > 7.6f) { FORKWHY[1]++; continue; }
            Element pad = plat(Element.Type.PAD, padS, padY, 2f, tg.zone); pad.anchor = a; es.add(pad);
            int n0 = c.size(), h0 = c.hazards.size();
            boolean ok = true;
            for (Element e : es) { c.add(e); if (!decoyOk(e, a, true)) { ok = false; FORKWHY[2 + decoyWhy]++; break; } }
            if (ok) {
                c.indexDecoys();
                ok = pairOk(from, n0); if (!ok) { FORKWHY[10]++; }
                for (int k = n0; ok && k < c.size() - 1; k++) { ok = pairOk(k, k + 1); if (!ok) { FORKWHY[11]++; } }
                if (ok) { ok = pairOkAir(c.size() - 1, t); if (!ok) FORKWHY[12]++; }
                for (int q = Math.max(0, a - 3); ok && q <= Math.min(rs - 2, t + 2); q++) { ok = Autopilot.plan(Sim.startOn(c, T, q), q, false).ok; if (!ok) { FORKWHY[9]++; } }
            }
            if (!ok) { rollbackTo(n0, h0); c.indexDecoys(); continue; }
            c.forks.add(new int[]{from, n0, es.size(), t});
            decoys += es.size();
            return t;
        }
        return -1;
    }

    private boolean lastDip;      // Build 79: the last hop module was a dip
    /** The highest route platform built so far. */
    private float routeTop() { float m = -1e9f; for (int i = 0; i < c.size(); i++) { Element e = c.get(i); if (e.anchor < 0 && e.isPlatform()) m = Math.max(m, e.y); } return m; }
    private boolean forceSpur;      // the left-spur pass: always a short spur
    private int leftBuilt;      // left spurs built in this slice
    private boolean spurLeft, spurBuilt;      // Build 79: the side the next dead-end spur branches to (flips with every spur placed); the last decoy built was a spur

    /** Two tries at a decoy off route platform a: a spur goes first to the side whose turn it is, then to the other; a placed spur hands the turn over. */
    private boolean placeDecoy(Element p, int a, Random dr, boolean forceLong) {
        boolean side = spurLeft;
        for (int again = 0; again < 2; again++) {
            pendingHaz = new ArrayList<>(); spurBuilt = false;
            spurLeft = again == 0 ? side : !side;
            List<Element> es = buildDecoy(p, a, dr, forceLong);
            if (tryDecoy(es, pendingHaz, a)) { decoys += es.size(); if (spurBuilt && spurLeft) leftBuilt++; spurLeft = spurBuilt ? !spurLeft : side; return true; }
        }
        spurLeft = side;
        return false;
    }

    /** Dead-end spurs (to the left or the right, stepping up and down), crumbling lures, unreachable stepping stones, and trapped ledges guarded by hazards. */
    /** {@code forceLong}: always build a long spur (used by the separate-RNG long-spur pass so the main decoy RNG stays byte-identical). */
    private List<Element> buildDecoy(Element p, int a, Random dr, boolean forceLong) {
        List<Element> l = new ArrayList<>();
        int kind = dr.nextInt(12);
        float inten = intensity(p.y);
        // a longer dead end: four to six steps ending on a wide, plainly safe platform.
        boolean longSpur = kind >= 10 || forceLong;
        if (longSpur || forceSpur) kind = dr.nextInt(7);
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
            // Build 79 (owner): spurs branch off to the left and to the right in turn (spurLeft flips with every spur built), and each step may go up
            // or down - up, down, up, up... - just to throw the climber off. Every gap is sized for the step taken upward, so the way back is always a
            // normal jump too: a dead end only ever costs the walk back.
            boolean forward = !spurLeft; spurBuilt = true;
            float edge = forward ? p.s + p.w / 2f : p.s - p.w / 2f, y = p.y;
            boolean leftDown = false;
            int n = longSpur ? 4 + dr.nextInt(3) : 2 + dr.nextInt(2);
            for (int k = 0; k < n; k++) {
                boolean up = dr.nextFloat() < (forward ? 0.35f : 0.7f);      // both ways on either side, but a left spur leans up, clear of the lower block the route came from, a right one down, under the block it goes on to
                float dy = up ? 0.2f + dr.nextFloat() * 0.6f : -(0.3f + dr.nextFloat() * 0.7f);
                if (!forward && k == 0) leftDown = dr.nextFloat() < 0.5f;
                if (!forward && k < 2) dy = leftDown ? -(1.0f + dr.nextFloat() * 0.4f) : 1.4f + dr.nextFloat() * 0.3f;   // a left spur branches back over (or under) the route it came up: it climbs or drops steeply first, so it never hangs low over a route platform (no ceiling)
                float gap = reach(Math.abs(dy)) * (0.40f + 0.2f * dr.nextFloat());
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

    private boolean decoyOk(Element d, int a) { return decoyOk(d, a, false); }
    /** {@code fork}: a fork's branch, which rejoins the route anyway, so the stepping-stone rule (no shortcut toward the route) does not apply to it. */
    private boolean decoyOk(Element d, int a, boolean fork) {
        int rs = c.routeSize();
        for (int i = 0; i < c.size(); i++) {
            Element e = c.get(i);
            if (e == d) continue;
            if (Math.abs(d.s - e.s) < c.circumference * 0.5f) {
                float gap = Math.abs(d.s - e.s) - d.w / 2f - arcHalf(e);
                if (gap < 1.0f && d.y - 0.6f < vHi(e) - 1.6f && d.y + 1.6f > vLo(e) + 0.8f) { decoyWhy = 1; return false; }   // overlapping / touching
                if (e.type == Element.Type.ROPE && gap < 1.2f && d.y > e.y - e.len - 0.5f && d.y < e.y + 2.2f) { decoyWhy = 1; return false; }   // Build 79 (owner): never across a rope or on its top beam
                if (i < rs && e.isPlatform() && Math.abs(d.s - e.s) < d.w / 2f + e.halfW() + 0.3f && d.y > e.y + 0.5f && d.y < e.y + 3.9f) { decoyWhy = 1; return false; }   // Build 79: never a ceiling over a route platform (a left-hand spur hung 2.6 m over the route and blocked its jump: EndlessTest seed 224)
            } else if (fork ? forkLayersClash(d, e) : layersClash(c, d, e)) { decoyWhy = 2; return false; }
        }
        for (Element g : ghostElems) if (layersClash(c, d, g)) { decoyWhy = 3; return false; }
        if (!fork) for (int j = a + 2; j < rs; j++) {                 // never a stepping stone toward later route
            Element e = c.get(j);
            if (!e.isPlatform()) continue;
            float gap = Math.max(0f, Math.abs(c.dsWrap(d.s, e.s)) - d.w / 2f - e.halfW());
            if (gap < 6.5f && e.y - d.y > -7f && e.y - d.y < 3.6f) { decoyWhy = 4; return false; }
        }
        return true;
    }

    /** A dead end never lies in the flight corridor of a pad or spring launch near its anchor (the launched hero would be carried onto it). Only for dead ends: key rooms are proven separately. */
    private boolean corridorOk(Element d, int a) {
        int rs = c.routeSize();
        for (int j = Math.max(0, a - 4); j <= Math.min(rs - 1, a + 6); j++) {
            Element e = c.get(j);
            if ((e.type == Element.Type.PAD || e.type == Element.Type.SPRING) && Math.abs(c.dsWrap(d.s, e.s)) < 10f + d.w / 2f && d.y > e.y - 1.5f && d.y < e.y + 9f) { decoyWhy = 5; return false; }
        }
        return true;
    }

    private boolean tryDecoy(List<Element> es, List<Element> hz, int a) {
        int n0 = c.size(), h0 = c.hazards.size();
        boolean ok = true; decoyTried++;
        for (Element d : es) { c.add(d); if (!decoyOk(d, a) || !corridorOk(d, a)) { ok = false; decoyGeoFail++; break; } }
        if (ok) for (Element h : hz) { c.hazards.add(h); if (!hazardOk(h, -1)) { ok = false; decoyGeoFail++; break; } }
        if (ok) {
            c.indexDecoys();
            int rs = c.routeSize();
            for (int r = Math.max(0, a - 7); r <= Math.min(rs - 2, a + 5) && ok; r++) {
                // Build 79: a dead end next to a link may not take away its tolerance - the link keeps the margin every route link is proven with (0.05
                // for a ledge grab, otherwise more), not merely one move that works (left-hand spurs over the route left links at 0.017: CourseTest)
                boolean near = false;
                for (Element d : es) for (int q = r; q <= r + 1 && !near; q++) { Element e = c.get(q); if (Math.abs(c.dsWrap(d.s, e.s)) < 12f && d.y - e.y > -3f && d.y - e.y < 6f) near = true; }
                Autopilot.Result res = Autopilot.plan(Sim.startOn(c, T, r), r, near);
                if (!res.ok || (near && res.margin() < 0.05f)) { ok = false; decoyPlanFail++; }
            }
        }
        if (!ok) { while (c.size() > n0) c.elements.remove(c.size() - 1); while (c.hazards.size() > h0) c.hazards.remove(c.hazards.size() - 1); c.indexDecoys(); }
        return ok;
    }

    private List<Element> listOf(Element... es) { List<Element> l = new ArrayList<>(); for (Element e : es) l.add(e); return l; }

    private void addRest(int zone) { addRest(zone, false); }

    /** gem: the halfway red gem's rest - if there is no room for it a little higher, try level or a little lower before climbing (a climb first
     *  can carry the gem a whole module past its height: 5.4 m on seed 21, where the official rule allows 4.5 m). */
    private void addRest(int zone, boolean gem) {
        for (int round = 0; round < 4; round++) {
            Element last = c.get(c.size() - 1);
            for (int attempt = 0; attempt < (gem && round == 0 ? 14 : 8); attempt++) {
                float dy = attempt >= 8 ? r(-0.8f, 0.2f) : r(0.3f, 1.0f) * (1f - attempt * 0.08f);
                float gap = reach(Math.max(0f, dy)) * (0.5f - Math.min(attempt, 7) * 0.03f);
                float w = attempt < 4 ? 5 + rnd.nextInt(2) : (attempt < 6 ? 4 : attempt < 8 ? 3 : 4);
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
            case SWING: return e.w / 2f + e.len * (float) StrictMath.sin(e.amp);
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

    /**
     * {@link #layersClash} for a fork's branch, which runs below the route and so nearer the revolution beneath: the bodies must keep clear as usual, and
     * no layer may reach the other: platforms out of jump reach (4 m; a jump rises about 1.9 m) unless the lower one bounces, which keeps the 9.5 m rule.
     */
    private boolean forkLayersClash(Element d, Element f) {
        if (Math.abs(d.s - f.s) < c.circumference * 0.5f) return false;
        if (Math.abs(c.dsWrap(d.s, f.s)) > arcHalf(d) + arcHalf(f) + 2.5f) return false;
        if (vLo(d) - 1.5f < vHi(f) && vHi(d) + 1.5f > vLo(f)) return true;
        if (!d.isPlatform() || !f.isPlatform()) return false;
        Element lower = d.y < f.y ? d : f;
        boolean bounces = lower.type == Element.Type.PAD || lower.type == Element.Type.SPRING;
        return Math.abs(d.y - f.y) < (bounces ? 9.5f : 4f);
    }

    private boolean clearOfOtherLayers(int idx) {
        Element e = c.get(idx);
        for (int i = 0; i < idx - 3; i++) if (layersClash(c, e, c.get(i))) { lastClash = c.get(i); lastClashGhost = false; return false; }
        for (Element g : ghostElems) if (layersClash(c, e, g)) { lastClash = g; lastClashGhost = true; return false; }
        return true;
    }
    private Element lastClash; private boolean lastClashGhost;      // diagnostics: what the last refused element ran into
    /** Diagnostics: what crab decks refused for a layer clash ran into {route body, route 9.5 m rule, branch body, branch 9.5 m rule} x {this slice, earlier slice}. */
    public static final int[] CRABCLASH = new int[8];

    // ---- hazard geometry: lethal-region bounding box (centre arc, half extent, low, high)
    private static float hzS(Element h) { return h.type == Element.Type.CANNON ? h.s + h.dir * h.len * 0.5f : h.s; }
    private static float hzHalf(Element h) {
        switch (h.type) {
            case SAW_H: return h.amp + Element.SAW_R;
            case CRAB: return h.amp + 0.6f;
            case BEE: return h.amp + 0.6f;
            case SAW_V: return Element.SAW_R;
            case PENDULUM: return h.len * (float) StrictMath.sin(h.amp) + Element.BALL_R;
            case CANNON: return h.len * 0.5f + Element.SHOT_R;
            case MORTAR: return Element.MORTAR_R;
            case AIMED: return 0.6f;
            default: return h.w * 0.5f;
        }
    }
    private static float hzLo(Element h) {
        switch (h.type) { case SAW_H: case SAW_V: return h.y - Element.SAW_R; case PENDULUM: return h.y - Element.BALL_R; case CANNON: return h.y - Element.SHOT_R; case AIMED: return h.y - 0.4f; case BEE: return h.y - h.len - 1.4f; default: return h.y; }
    }
    private static float hzHi(Element h) {
        switch (h.type) {
            case SAW_H: return h.y + Element.SAW_R;
            case SAW_V: return h.y + h.amp + Element.SAW_R;
            case PENDULUM: return h.y + h.len * (1f - (float) StrictMath.cos(h.amp)) + Element.BALL_R;
            case CANNON: return h.y + Element.SHOT_R;
            case MORTAR: return h.y + h.amp + Element.MORTAR_R;
            case AIMED: return h.y + 0.6f;
            case SPIKE_TRAP: return h.y + 0.7f;
            case SPIKE_DROP: return h.y + h.amp + Element.DROP_H;
            case GATE: return h.y + h.len;
            case CRAB: return h.y + 0.7f;
            case SKULL: case BLOB: return h.y + 2.3f;      // the skull's hover; the blob's leap goes off the top of the screen and is not standing-room clutter
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
        boolean on = (h.type == Element.Type.SPIKE_TRAP || h.type == Element.Type.SAW_H || h.type == Element.Type.SPIKE_BLOCK || h.type == Element.Type.SPIKE_DROP || h.type == Element.Type.GATE || h.type == Element.Type.CRAB || h.type == Element.Type.SKULL || h.type == Element.Type.BLOB) && o.isPlatform() && Math.abs(hzS(h) - o.s) < o.w * 0.5f;
        return on ? h.anchor : -1;
    }

    private float windowNeeded(float y) { return T.minTimingWindow - 0.06f * intensity(y); }

    /** How many decks either side of its own a creature can reach (skulls, blobs and, since rules 8, bees a neighbour; crabs follow up to CRAB_FOLLOW); -1 for anything else. One definition, EncounterModel.roamReach. */
    private static int roamReach(Element h) { return EncounterModel.roamReach(h); }
    /** True if a creature already on the course can roam onto the link from deck a (onto a or a + 1). */
    private boolean creatureReaches(int a) {
        for (Element o : c.hazards) { int r = roamReach(o); if (r >= 0 && a >= o.anchor - r - 1 && a <= o.anchor + r) return true; }
        return false;
    }
    /**
     * Build 79 (owner: skulls, blobs and crabs reach the platforms either side): a creature that roams onto a saw's link cut its timing window from a whole
     * cycle to 0.36 (SawFairnessTest), so, as the homing bee since Build 71, a horizontal saw is never placed within a roaming creature's reach.
     */
    private boolean sawsClearOfCreatures(List<Element> hz) {
        for (Element n : hz) for (Element o : c.hazards) {
            if (n == o) continue;
            Element saw = n.type == Element.Type.SAW_H ? n : o.type == Element.Type.SAW_H ? o : null, mob = saw == n ? o : n;
            int r = saw == null ? -1 : roamReach(mob);
            if (r >= 0 && saw.anchor >= mob.anchor - r - 1 && saw.anchor <= mob.anchor + r) return false;
            if (saw != null && (mob.type == Element.Type.CANNON || mob.type == Element.Type.AIMED) && Math.abs(saw.anchor - mob.anchor) <= 1) return false;   // nor a cannon lane on the deck before, its own or the one it lands on, whichever module placed it (seed 3: a cannon from the next module cut a saw to 0.36)
        }
        return true;
    }

    private boolean commit(List<Element> es, float minMargin) { return commit(es, new ArrayList<Element>(), minMargin); }

    public int failConflict;      // diagnostics: modules refused for incompatible demands (EncounterModel)
    public int failThreat;        // diagnostics: NORMAL modules refused for putting more pursuit within reach of a link than its level allows
    /** The margin each committed link was proven with (route index of its start). */
    private final ArrayList<Float> linkNeeds = new ArrayList<>();
    private float linkNeed(int i) { return i < linkNeeds.size() && linkNeeds.get(i) != null ? linkNeeds.get(i) : Math.min(0.05f, T.minLinkMargin); }
    private void setLinkNeed(int i, float m) { while (linkNeeds.size() <= i) linkNeeds.add(null); linkNeeds.set(i, m); }

    /** Adds elements (and their hazards) if every link (prefix last -> e1 -> ... -> eN) is solvable with the required margin and timing window. */
    /** Diagnostics: modules carrying crabs {tried, refused for layer, hazard placement, plan, window, conflict, threat, accepted}. */
    public static final int[] CRABWHY = new int[8];
    private boolean commit(List<Element> es, List<Element> hz, float minMargin) {
        int n0 = c.size(), h0 = c.hazards.size();
        final int[] w0 = {failLayer, failHaz, failPlan, failWindow, failConflict, failThreat};
        int from = n0 - 1;
        // Every link a new hazard can touch is proven with it there: a hazard on the platform the previous link lands on touches that link too (a cannon
        // fires across it; a saw on it sweeps the landing: seed 3 slice 16, a saw from the next module cut a saw link to 0.36), and a creature also the
        // links into the decks it roams onto (framed seed 5 slice 17: a skull from the next module jumped the pad link before its deck).
        for (Element h : hz) if (h.anchor >= 0) from = Math.min(from, h.anchor - 1 - Math.max(0, roamReach(h)));
        if (n0 >= 2 && !es.isEmpty()) from = Math.min(from, n0 - 2);       // and the link onto the platform the module starts from: what comes next can crowd its landing (tower 3: a slider just past it cut a ramp link from 0.25 to 0.017)
        from = Math.max(0, from);
        // An earlier link is held against the module only if it held before it: some earlier links are proven from a start a plain re-check does not
        // reproduce (the landing on a running belt, a skull parked at the far end), and re-checking those unconditionally rejected every module after
        // them, so generation ran out of modules (framed seed 21 slice 49).
        // NORMAL threat budget (Build 80 diagnostics): the pursuit each link can meet before this module, so the module is judged only on what it adds.
        // A bee seeks a climber within 7 m, so the links it can reach start up to two platforms before its own.
        int thFrom = from;
        if (P.threatBudget) for (Element h : hz) if (h.type == Element.Type.BEE && h.anchor >= 0) thFrom = Math.min(thFrom, h.anchor - 3);
        thFrom = Math.max(0, thFrom);
        float[] threatBefore = new float[Math.max(0, n0 - 1 - thFrom)];
        if (P.threatBudget) for (int i = thFrom; i < n0 - 1; i++) threatBefore[i - thFrom] = EncounterModel.threat(c, T, i);
        boolean[] heldBefore = new boolean[Math.max(0, n0 - 1 - from)];
        for (int i = from; i < n0 - 1; i++) {
            boolean cannonOnLanding = false; for (Element h : hz) if (h.type == Element.Type.CANNON && h.anchor == i + 1) cannonOnLanding = true;
            if (cannonOnLanding) { heldBefore[i - from] = true; continue; }       // as before Build 79: a cannon's lane across a landing is always proven
            Sim b = Sim.startOn(c, T, i); quietStart(b, i);
            Autopilot.Result r0 = Autopilot.plan(b, i, true);
            heldBefore[i - from] = r0.ok && r0.margin() >= linkNeed(i);
        }
        for (Element e : es) c.add(e);
        for (Element h : hz) c.hazards.add(h);
        boolean ok = true;
        int clashAt = -1;
        for (int i = n0; i < c.size() && ok; i++) { ok = clearOfOtherLayers(i); if (!ok) clashAt = i; }
        if (!ok) failLayer++;
        if (!ok) { boolean cr = false; for (Element h : hz) if (h.type == Element.Type.CRAB) cr = true;
            if (cr) { Element e = c.get(clashAt), f = lastClash; boolean body = vLo(e) - 1.5f < vHi(f) && vHi(e) + 1.5f > vLo(f);
                CRABCLASH[(f.anchor >= 0 ? 2 : 0) + (body ? 0 : 1) + (lastClashGhost ? 4 : 0)]++; } }
        for (Element h : hz) { if (!ok) break; if (h.type == Element.Type.CLUB) continue; ok = hazardOk(h, ownerOf(h)); if (!ok) failHaz++; }
        if (ok && !sawsClearOfCreatures(hz)) { ok = false; failHaz++; }
        Sim prevStart = null; Autopilot.Result prevRes = null;
        for (int i = from; i < c.size() - 1 && ok; i++) {
            if (i < n0 - 1 && !heldBefore[i - from]) { prevStart = null; prevRes = null; continue; }
            Sim sim = Sim.startOn(c, T, i); quietStart(sim, i);
            if (c.get(i).tiltBelt() && prevRes != null && prevRes.factory != null) {
                // Build 79 (owner): the balance conveyor always runs, so nobody stands still on it: a climber crosses it straight from the landing. Its link is
                // proven from where and how the proven jump onto it lands (a standing start at its back end is carried off it before anyone could move).
                Sim land = Autopilot.trialEnd(prevStart, i - 1, prevRes.factory, 10.5f);
                if (land != null && land.onElem == i) sim = land;
            }
            Autopilot.Result res = Autopilot.plan(sim, i, true);
            prevStart = sim; prevRes = res;
            boolean touched = false;
            for (Element h : hz) if (h.anchor == i || h.anchor + 1 == i || h.anchor == i + 1) touched = true;
            float need = i >= n0 - 1 ? minMargin : linkNeed(i);       // a link proven again keeps the margin it was proven with (a ledge grab 0.05), not this module's
            boolean sawLink = false; for (Element h : c.hazards) if (h.type == Element.Type.SAW_H && h.anchor == i) sawLink = true;
            if (!res.ok || res.margin() < Math.max(need, 0f)) { ok = false; failPlan++; if (trace.length() < 600) trace.append(String.format("[link %d %s->%s ok=%b %d/%d need %.3f]", i, c.get(i).type, c.get(i + 1).type, res.ok, res.successes, res.trials, minMargin)); }
            else if ((touched || sawLink) && res.window < (sawLink ? Math.max(0.5f, windowNeeded(c.get(i).y)) : windowNeeded(c.get(i).y))) { ok = false; failWindow++; }      // a saw leaves at least half its cycle to time the jump (SawFairnessTest), whatever else shares the link
            else if (Autopilot.hasMidHazard(c, i)) {       // a person never stands on the exact spot the planner starts from: the move must work from nearby too
                for (float dx : new float[]{-0.5f, 0.4f}) {
                    Sim b = Sim.startOn(c, T, i); b.s = c.wrap(b.s + dx); quietStart(b, i);
                    if (!Autopilot.plan(b, i, false).ok) { ok = false; failPlan++; break; }
                }
            }
        }
        // Owner directive 2026-10-24 (sections 10-14): no link may ask for incompatible things at once: several creatures chasing the climber where the
        // jump also has to wait for a cycle from a cramped take-off. Such a module is not dropped from the game; this arrangement of it is refused and the
        // generator tries another (different spacing, placement or module), so the mechanics stay and meet elsewhere.
        for (int i = Math.max(0, from); ok && i < c.size() - 1; i++) if (EncounterModel.link(c, T, i).conflict) { ok = false; failConflict++; }
        // NORMAL (Build 80 diagnostics, seed 34 at 512 m: four crabs and two bees could all reach a spiked take-off two platforms on): the creatures that
        // can really reach a link's take-off or landing - crabs that follow, skulls and blobs from the next platform, bees within their seeking range - stay
        // within a budget that grows with the level, smaller from a cramped take-off. A module is refused only where it pushes a link over the budget;
        // the generator then tries another arrangement, so the creatures still come, just not all at once this early.
        if (P.threatBudget) for (int i = thFrom; ok && i < c.size() - 1; i++) {
            if (i >= c.routeSize() - 1) break;
            float th = EncounterModel.threat(c, T, i);
            if (th <= P.threatBudget(Math.min(1f, level(c.get(i).y)), EncounterModel.cramped(c, i))) continue;
            if (i < n0 - 1 && th <= threatBefore[i - thFrom] + 1e-4f) continue;          // over before this module, and no worse with it
            ok = false; failThreat++;
        }
        if (!ok) { while (c.size() > n0) c.elements.remove(c.size() - 1); while (c.hazards.size() > h0) c.hazards.remove(c.hazards.size() - 1); }
        else { for (int i = Math.max(0, n0 - 1); i < c.size() - 1; i++) setLinkNeed(i, minMargin); if (!hz.isEmpty()) hazardsPlaced += hz.size(); }
        hazardTried += hz.isEmpty() ? 0 : 1;
        boolean crabs = false; for (Element h : hz) if (h.type == Element.Type.CRAB) crabs = true;
        if (crabs) {          // diagnostics only: why modules carrying crabs are refused
            CRABWHY[0]++;
            CRABWHY[ok ? 7 : failLayer != w0[0] ? 1 : failHaz != w0[1] ? 2 : failPlan != w0[2] ? 3 : failWindow != w0[3] ? 4 : failConflict != w0[4] ? 5 : failThreat != w0[5] ? 6 : 0]++;
        }
        return ok;
    }

    /**
     * A standing start at a moment when standing there is safe. Proofs start at time 0; on a platform inside a cannon's reach (its balls fly 14 m past
     * the lane, Build 63) a ball can be passing right there at time 0, so every trial was hit before it could move and a fair platform became a dead end
     * (FramedGenerationTest, seed 21). A climber arrives at some moment and can wait for the gap, so the proof starts at the first quiet moment instead.
     * Untouched when time 0 is already safe, or when the start platform itself moves.
     */
    private void quietStart(Sim sim, int i) { quietStart(c, sim, i); }
    private static void quietStart(Course c, Sim sim, int i) {
        if (sim.mode != Sim.Mode.GROUND || c.get(i).isMoving() || c.get(i).crumbles()) return;
        float t0 = sim.time;
        for (int k = 0; k < 60 && !quiet(sim); k++) { sim.time = t0 + 0.15f * (k + 1); sim.syncElements(); }
        if (!quiet(sim)) { sim.time = t0; sim.syncElements(); }
    }
    /**
     * The start a route link out of element i is proven from (commit): a standing start at the first quiet moment, and on the always-running balance
     * conveyor the landing the proven jump onto it makes. Tests that measure a link's tolerance measure it from here, as the generator does.
     */
    public static Sim provenStart(Course c, Tuning t, int i) {
        Sim sim = Sim.startOn(c, t, i); quietStart(c, sim, i);
        if (i > 0 && c.get(i).tiltBelt()) {
            Sim prev = Sim.startOn(c, t, i - 1); quietStart(c, prev, i - 1);
            Autopilot.Result pr = Autopilot.plan(prev, i - 1, true);
            if (pr.factory != null) { Sim land = Autopilot.trialEnd(prev, i - 1, pr.factory, 10.5f); if (land != null && land.onElem == i) sim = land; }
        }
        return sim;
    }
    private static boolean quiet(Sim sim) {
        Sim d = sim.copy(); InputState in = new InputState();
        for (int q = 0; q < 24; q++) { d.step(in); if ((d.consumeEvents() & (Sim.EV_HIT | Sim.EV_SHOVE)) != 0 || d.mode != Sim.Mode.GROUND) return false; }
        return true;
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
        w[Kind.LONG.ordinal()] = 0f;            // long walkable corridors deferred: at any non-zero weight they lengthen the local climb enough to push the streaming-vs-whole float determinism past its 2 mm tolerance (TowerStreamingTest). Re-enable with a test-stable design (shorter corridors / local-origin friendly) once reviewed. The Kind + piece case stay ready.
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
        // (PUNISHER no longer multiplies these weights: it reads the curve DifficultyProfile.curveShift metres higher; owner directive 2026-10-24)
        if (prev != null) { w[prev.ordinal()] *= 0.35f; if (prev == prev2) w[prev.ordinal()] = 0f; }
        float sum = 0; for (float f : w) sum += f;
        float x = rnd.nextFloat() * sum;
        for (Kind k : Kind.values()) { x -= w[k.ordinal()]; if (x <= 0 && w[k.ordinal()] > 0) return k; }
        return Kind.HOP;
    }

    public static volatile int wetTried, wetOk;          // diagnostics: wet-platform modules attempted / proven

    private boolean tryModule(Kind k, float d, int zone) {
        Element last = c.get(c.size() - 1);
        int attempts = k == Kind.DROPS ? 4 : 8;           // a chain of timed drops is the costliest module to prove; after four misses another module is cheaper
        for (int attempt = 0; attempt < attempts; attempt++) {
            float dd = Math.max(0f, d * (1f - attempt * 0.14f));
            List<Element> hz = new ArrayList<>();
            List<Element> es;
            if (k == Kind.HAZ) es = buildHaz(last, Math.max(dd, 0.35f), zone, attempt, hz);
            else if (k == Kind.TRAP) es = buildMid(last, Math.max(dd, 0.35f), zone, attempt, hz);
            else if (k == Kind.WET) es = buildWet(last, dd, zone, attempt);
            else if (k == Kind.MORTAR) es = buildMortar(last, Math.max(dd, 0.35f), zone, attempt, hz);
            else if (k == Kind.CREATURE) es = buildCreature(last, Math.max(dd, 0.3f), zone, attempt, hz);
            else if (k == Kind.DROPS) es = buildDrops(last, Math.max(dd, 0.3f), zone, attempt, hz);
            else es = build(k, last, dd, zone, attempt);
            if (es == null) continue;
            if (T.hard > 0) for (Element h : hz) if (h.type == Element.Type.CANNON || h.type == Element.Type.AIMED || h.type == Element.Type.MORTAR || h.type == Element.Type.BEE) h.period /= T.react();   // Build 79 PUNISHER / NEVER GONNA HAPPEN: they come round faster
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
        float gap = reach(dy) * lerp(0.45f, 0.8f, d) * r(0.9f, 1.05f) * (1f - 0.03f * attempt), w = rnd.nextFloat() < 0.35f ? r(2.4f, 3.0f) : r(4.5f, 5.5f);      // Build 71 (owner): some icy platforms are tiny ones
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
        int n = 5 + rnd.nextInt(2) + (rnd.nextFloat() < late(last.y) ? 2 : 0);          // Build 71: longer mortar rows higher up
        List<Element> es = new ArrayList<>();
        Element cur = last; int base = c.size();
        float period = r(3.2f, 4.0f) - 0.4f * inten;
        for (int k = 0; k < n; k++) {
            float uR = cur.s + cur.w / 2f;
            float g = Math.min(3.0f, Math.max(2.2f, reach(0f) * lerp(0.55f, 0.72f, d) * r(0.95f, 1f) * (1f - 0.03f * attempt)));
            float w = k == n - 1 ? 3.5f : r(2.4f, 3.2f);
            Element v = plat(Element.Type.STATIC, uR + g + w / 2f, cur.y, w, z);
            es.add(v);
            Element m = hz(Element.Type.MORTAR, uR + g / 2f, cur.y - MORTAR_DEPTH, 0f, z);
            m.amp = MORTAR_DEPTH + 2f * T.height; m.period = period * r(0.9f, 1.1f); m.phase = r(0f, 6.28f);      // Build 71 (owner): each mortar keeps its own time - no wait-jump-wait wave; a few gaps may line up for two or three jumps in a row
            m.anchor = k == 0 ? base - 1 : base + k - 1;
            hzOut.add(m);
            cur = v;
        }
        return es;
    }

    /**
     * Build 71 (owner): a creature's deck. World 2 on: a skull over a long fixed platform (half the time a fixed wooden bridge) - jump its dash. World 3 on, also: a green
     * blob on a long platform, or on a tipping bridge with a hop to a block beyond - keep moving so it does not land on you. The creature sits at the far end. Proven by
     * {@link #commit} like every module (the planner hops the skull's dash and steps out of the blob's shadow; see Autopilot.Reflex).
     */
    private List<Element> buildCreature(Element last, float d, int z, int attempt, List<Element> hzOut) {
        float uR = last.s + last.w / 2f;
        int roll = rnd.nextFloat() < 0.3f + 0.7f * Math.min(1f, level(last.y)) ? (rnd.nextBoolean() ? 0 : 1 + rnd.nextInt(2)) : 0;      // (blobs from Level 1 on, more often higher up; were world 3 on)          // 0 skull (half), 1 blob on a platform, 2 blob on a tipping bridge
        List<Element> es = new ArrayList<>();
        if (roll == 2) {
            float w = 7f + rnd.nextInt(2), dyIn = r(-0.4f, 0.3f);
            float gapIn = reach(dyIn) * lerp(0.5f, 0.85f, d) * r(0.92f, 1f) * (1f - 0.03f * attempt);
            Element sw = plat(Element.Type.SEESAW, uR + gapIn + w / 2f, last.y + dyIn, w, z); sw.skin = 1;
            es.add(sw);
            float gapOut = lerp(0.5f, 1.3f, d) * r(0.9f, 1.1f) * (1f - 0.05f * attempt);
            es.add(plat(Element.Type.STATIC, sw.s + w / 2f + gapOut + 1.5f, sw.y + r(-0.5f, 0.6f), 3f, z));
            Element b = hz(Element.Type.BLOB, sw.s + (w / 2f - Element.BLOB_END), sw.y, w, z); b.anchor = c.size(); b.phase = r(0f, 6.28f);
            hzOut.add(b);
            return es;
        }
        float dy = r(0f, 0.6f), gapIn = reach(dy) * lerp(0.5f, 0.85f, d) * (1f - 0.03f * attempt);
        float w = roll == 0 ? 8f + rnd.nextInt(2) : 7f + rnd.nextInt(2);
        Element p = plat(Element.Type.STATIC, uR + gapIn + w / 2f, last.y + dy, w, z);
        if (roll == 0 && rnd.nextBoolean()) { p.skin = 1; if (rnd.nextFloat() < 0.35f) holes(p); }          // a fixed wooden bridge (owner: long platforms and bridges that do not move); Build 79: some drop planks
        es.add(p);
        Element h = hz(roll == 0 ? Element.Type.SKULL : Element.Type.BLOB, roll == 0 ? p.s : p.s + (w / 2f - Element.BLOB_END), p.y, w, z);
        h.anchor = c.size(); h.phase = r(0f, 6.28f);
        hzOut.add(h);
        if (roll != 0) return es;          // a blob waits at the front end of its deck, so a short hop to a landing ran straight into it and no blob module could be proven (0 blobs on 3 seeds); the next module starts from the deck as before
        float we = r(3.0f, 3.6f);          // a clear landing past the creature's deck: a breather, and the next module does not have to prove its jump with the creature at its back
        es.add(plat(Element.Type.STATIC, p.s + w / 2f + r(1.0f, 1.5f) * (1f - 0.03f * attempt) + we / 2f, p.y + r(-0.1f, 0.4f), we, z));
        return es;
    }

    /**
     * Build 79 (owner: "some of these bridges should have sections of planks fall out of them when you land to leave holes to jump over"): marks one or two plank
     * sections of a fixed wooden bridge to drop out (Element.holed), never the first two (you land there) or the last (you take off there), and never two side by side.
     */
    private void holes(Element br) {
        int n = (int) (br.w / Element.HOLE_SEC), mask = 0;
        if (n < 4) return;
        int k = 2 + rnd.nextInt(Math.max(1, n - 3));
        if (k > n - 2) k = n - 2;
        mask |= 1 << k;
        if (n >= 6 && rnd.nextBoolean()) { int k2 = k + 2 <= n - 2 ? k + 2 : k - 2; if (k2 >= 2) mask |= 1 << k2; }
        br.color = mask;
    }

    /** Spike drops in a row never hang lower than this above the walkway when raised (owner: "dont make any too low"): head room with a margin. */
    static final float DROP_MIN_TOP = 2.5f, DROP_MAX_TOP = 3.6f;
    /**
     * Build 71 (owner): chained spike drops, introduced between castle 1 and castle 2. Three to five platforms with short hops between them, each with a spiked slab on a chain
     * hanging forward of its middle; every slab has its own speed (period) and its own top height (DROP_MIN_TOP..DROP_MAX_TOP), all out of step, so the row has no rhythm to learn by heart.
     * Each platform keeps a safe pocket at both ends; every link is proven by {@link #commit}.
     */
    private List<Element> buildDrops(Element last, float d, int z, int attempt, List<Element> hzOut) {
        float inten = intensity(last.y);
        int n = 3 + (rnd.nextFloat() < 0.35f + 0.4f * inten ? 1 : 0) + (rnd.nextFloat() < late(last.y) ? 1 : 0);
        List<Element> es = new ArrayList<>();
        Element cur = last; int base0 = c.size();
        for (int k = 0; k < n; k++) {
            float uR = cur.s + cur.w / 2f;
            float g = k == 0 ? reach(0.2f) * lerp(0.45f, 0.75f, d) * (1f - 0.03f * attempt) : r(1.1f, 1.7f) * (1f - 0.03f * attempt);
            float w = r(5.0f, 5.8f);
            Element p = plat(Element.Type.STATIC, uR + g + w / 2f, cur.y + (k == 0 ? r(0f, 0.4f) : r(-0.2f, 0.3f)), w, z);
            es.add(p);
            // the slab hangs forward of the middle: you land on clear deck at the back, time your run under it, and take off from the lip in front
            // (centred, it hung right where the hop lands, and a climber who stopped there had no safe way on - EndlessTest, seed 4 slice 9)
            Element h = hz(Element.Type.SPIKE_DROP, p.s + 0.2f * w, p.y, 1.6f, z);
            h.amp = r(DROP_MIN_TOP, DROP_MAX_TOP); h.period = r(2.7f, 4.3f) - 0.3f * inten; h.phase = r(0f, 6.28f);
            h.anchor = base0 + k;
            hzOut.add(h);
            if (k > 0 && rnd.nextFloat() < 0.2f + 0.3f * inten + 0.3f * T.hard) {             // Build 79 (owner: waiting out a drop is too easy): sometimes a crab on the deck or a bee over it as well
                if (rnd.nextBoolean()) {                                    // crabs: never fewer than two on a deck (owner), one more per difficulty level
                    int nc = 2 + T.hard; float span = w - 1.6f, step = span / (nc - 1);
                    for (int q = 0; q < nc; q++) {
                        Element m = hz(Element.Type.CRAB, p.s - span / 2f + q * step, p.y, 0f, z);
                        m.amp = Math.max(0.3f, Math.min(0.9f, step * 0.5f - 0.3f)); m.period = r(3.0f, 4.0f) - 0.4f * inten; m.phase = r(0f, 6.28f); m.anchor = base0 + k;
                        hzOut.add(m);
                    }
                } else {
                    Element m = hz(Element.Type.BEE, p.s, p.y + 2.2f, 0f, z); m.amp = 0.6f; m.len = 0.8f; m.period = r(7.0f, 9.5f) - 1.5f * inten; m.dir = rnd.nextBoolean() ? 1 : -1;
                    m.phase = r(0f, 6.28f); m.anchor = base0 + k;
                    hzOut.add(m);
                }
            }
            cur = p;
        }
        // a clear landing after the chain: a breather, and the next module starts from safe footing instead of under a drop (proving the jump
        // after a drop platform cost the generator many failed tries, ~40 s a slice in the castle 1-2 band)
        float w = r(3.0f, 3.8f);
        es.add(plat(Element.Type.STATIC, cur.s + cur.w / 2f + r(1.1f, 1.6f) * (1f - 0.03f * attempt) + w / 2f, cur.y + r(-0.1f, 0.3f), w, z));
        return es;
    }

    /** One or two hazard-guarded gaps: a saw blade, a spiked pendulum-free blade, a cannon lane, or a spiked block you must clear. */
    private List<Element> buildHaz(Element last, float d, int z, int attempt, List<Element> hzOut) {
        float inten = intensity(last.y);
        int n = (inten > 0.35f && rnd.nextFloat() < inten * 0.6f) ? 2 : 1;
        float lt = late(last.y);
        if (n == 2 && lt > 0.2f && rnd.nextFloat() < lt * 0.55f) n = 3;          // Build 71: higher up, three guarded gaps in a row
        List<Element> es = new ArrayList<>();
        Element cur = last;
        int base = c.size(), prevRoll = -1;
        for (int j = 0; j < n; j++) {
            float uR = cur.s + cur.w / 2f, dy = r(-0.3f, 0.6f);
            boolean cannonOk = cur.w >= 3f && cur.type == Element.Type.STATIC;
            int roll = wantCreature && j == 0 ? 3 : rnd.nextInt(cannonOk ? 5 : 4);
            if ((roll == 1 && prevRoll == 3) || (roll == 3 && prevRoll == 1)) roll = 2;      // Build 71: the bee now homes in and bumps you, so it never sits next to a saw you must time (it cut the saw's window to 0.2 s; SawFairnessTest)
            if ((roll == 1 && prevRoll == 4) || (roll == 4 && prevRoll == 1)) roll = 2;      // Build 79: nor a cannon lane right before or after it (a cannon across the landing cut a saw's window from its whole cycle to 0.36; SawFairnessTest, seed 3)
            prevRoll = roll;
            if (roll == 1 && creatureReaches(j == 0 ? c.size() - 1 : base + j - 1)) roll = 2;      // Build 79: nor next to a skull, blob or crab that can roam onto its decks (a skull there cut the window to 0.36; SawFairnessTest)
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
                if (rnd.nextFloat() < 0.15f + 0.5f * Math.min(1f, level(cur.y))) {          // owner directive 2026-10-24: aimed cannons anywhere, more often higher up (were the night world on)          // late game (the night world on): an aimed cannon instead of the side cannon: it turns to the player, locks, flashes, fires along the locked line
                    h = hz(Element.Type.AIMED, muzzle, cur.y + 2.0f, 0f, z);
                    h.period = r(4.2f, 5.2f) - 0.4f * inten;
                }
            }
            h.phase = r(0f, 6.28f); h.anchor = anchor;
            hzOut.add(h);
            if (roll == 3 && rnd.nextFloat() < 0.3f + 0.4f * inten + 0.3f * T.hard) {        // Build 79 (owner): sometimes a second bee at once, from the other side (never the same side)
                Element b2 = hz(Element.Type.BEE, mid, top + 1.3f, 0f, z);
                b2.amp = h.amp; b2.len = h.len; b2.period = h.period; b2.dir = -1; b2.phase = h.phase + r(0.15f, 0.45f); b2.anchor = anchor;
                hzOut.add(b2);
            }
            cur = v;
        }
        return es;
    }

    /** A wide platform with a hazard in the middle and a safe pocket at each end: land, wait for the right moment, cross. */
    private List<Element> buildMid(Element last, float d, int z, int attempt, List<Element> hzOut) {
        float inten = intensity(last.y);
        float uR = last.s + last.w / 2f, dy = r(0f, 0.6f);
        float gapIn = reach(dy) * lerp(0.5f, 0.85f, d) * (1f - 0.03f * attempt);
        int roll = wantCreature ? 4 : rnd.nextInt(5);
        if (roll == 1 && creatureReaches(c.size())) roll = 2;      // Build 79: no floor saw where a skull, blob or crab can roam onto it (see sawsClearOfCreatures)
        int crabs = roll == 4 ? 2 + (rnd.nextFloat() < inten * 0.7f ? 1 : 0) + (rnd.nextFloat() < late(last.y) * 0.6f ? 1 : 0) + T.hard : 0;      // (PUNISHER one more, NEVER GONNA HAPPEN two)      // Build 79 (owner): never fewer than two crabs, more higher up
        float w = roll == 4 ? 9f + 2f * (crabs - 2) : 8f;
        Element p = plat(Element.Type.STATIC, uR + gapIn + w / 2f, last.y + dy, w, z);
        // bug #14: the spike-follow fix (Sim.carryS/carryY + the renderer) rides a spike with its platform whenever that platform moves.
        // Generating NEW sliding spike platforms here is deferred: a moving spike platform grows the local climb and tips the streaming-vs-whole
        // float determinism past 2 mm (TowerStreamingTest). The follow logic stays, so any spike that sits on a mover already rides it correctly.
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
        } else {                        // crabs patrol the deck and shove you around; a spiked club floats on the platform before it, if you want to clear them
            if (last.w >= 3f && last.isPlatform() && rnd.nextInt(3) > 0) {
                Element club = hz(Element.Type.CLUB, last.s, last.y + 1.0f, 0f, z); club.anchor = c.size() - 1; club.phase = r(0f, 6.28f);
                hzOut.add(club);
                if (!wantCreature) clubBoost = 3;          // three more crab or bee modules follow, so the 30 seconds are worth having
            }
            float span = w - 3f, step = span / (crabs - 1), amp = Math.max(0.5f, Math.min(1.4f, step * 0.5f - 0.35f));
            for (int k = 0; k < crabs; k++) {           // Build 79: each on its own stretch of the deck, out of step with the others
                Element cr = hz(Element.Type.CRAB, p.s - span / 2f + k * step, p.y, 0f, z);
                cr.amp = amp; cr.period = r(3.0f, 4.2f) - 0.4f * inten; cr.phase = r(0f, 6.28f); cr.anchor = c.size();
                hzOut.add(cr);
            }
            return es;
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
                if (rnd.nextFloat() < 0.2f && !lastDip && y0 >= routeTop() - 0.5f && y0 > c.get(0).y + 3f) {          // (one dip at a time, and only from the route's high point: never more than two steps below it)          // Build 79 (owner): now and then the route steps down one or two blocks before it climbs on, just to throw you off
                    int n = 1 + rnd.nextInt(2); float s = eR, y = y0;
                    for (int i = 0; i < n; i++) {
                        float dy = -r(0.4f, 1.1f), w = 2 + rnd.nextInt(2);
                        s += reach(0f) * lerp(0.45f, 0.85f, d) * r(0.9f, 1.05f) + w / 2; y += dy; l.add(plat(S, s, y, w, z)); s += w / 2;
                    }
                    lastDip = true;
                    break;
                }
                lastDip = false;
                float dy = tier(last.y) == 0 ? r(0f, 0.8f) : r(0f, 1.4f);
                float frac = lerp(0.45f, 0.92f, d) * r(0.9f, 1.1f);
                if (y0 - wideHopY > P.wideHopEvery) { frac = Math.max(frac, r(0.66f, 0.74f)); wideHopY = y0; }          // now and then a wide hop (about 2.5 m): the open air a fork drops through (owner directive 2026-10-24, sections 15-16)
                float w = 2 + rnd.nextInt(3);
                if (tier(last.y) == 3 && rnd.nextBoolean()) w = 1 + rnd.nextInt(2);
                l.add(plat(S, eR + reach(dy) * frac + w / 2, y0 + dy, w, z));
                break;
            }
            case FORK: {          // entry, risers over the branch's room, rejoin (see Kind.FORK); every gap and rise is an ordinary hop
                float dy = r(0f, 0.4f), w = 4f;
                Element E = plat(S, eR + reach(dy) * r(0.4f, 0.55f) + w / 2f, y0 + dy, w, z); l.add(E);
                float s = E.s + w / 2f, ye = E.y; int n = forkRisers;
                for (int i = 0; i < n; i++) {
                    float gap = i == 0 ? r(2.5f, 2.8f) : r(1.5f, 1.8f), rw = 2.5f;
                    s += gap + rw / 2f; l.add(plat(S, s, ye + 0.4f + 1.8f * i / Math.max(1, n - 1), rw, z)); s += rw / 2f;
                }
                float jw = 4f; s += r(2.5f, 2.8f) + jw / 2f;
                l.add(plat(S, s, ye + 2.8f, jw, z));
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
                float len = 5f, th = 0.5f, ext = len * (float) StrictMath.sin(th), wm = 2f;
                float gapIn = lerp(1.0f, 1.8f, d), gapOut = lerp(1.0f, 1.8f, d);
                Element sw = plat(Element.Type.SWING, eR + gapIn + ext + wm / 2, y0 - 0.35f, wm, z);
                sw.len = len; sw.amp = th; sw.period = r(3.6f, 4.4f); sw.phase = r(0f, 6.28f);
                l.add(sw);
                l.add(plat(S, sw.s + ext + wm / 2 + gapOut + 1.5f, y0 + r(0.5f, 1.0f), 3f, z));
                break;
            }
            case SPRING: {
                float ang = r(0.32f, 0.62f) * (1f - 0.04f * attempt);
                float v = T.padBounce, vy0 = v * (float) StrictMath.cos(ang), vx0 = v * (float) StrictMath.sin(ang);
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
                if (beltSawNow) { sw.skin = 4; sw.phase = lerp(2.2f, 3.0f, d) * r(0.95f, 1.05f); }      // Build 77 (owner): the balance conveyor - its belt runs toward the low end, up to this fast
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
                if (w >= 7f && d > 0.12f && rnd.nextInt(10) < 4) { br.skin = 4; br.phase = lerp(1.6f, 3.4f, d) * r(0.95f, 1.05f); }      // Build 72 (owner): some long bridges are wooden plank conveyor belts running to the left; Build 76 (owner: "I can just walk right over"): 1.0-2.6 -> 1.6-3.4 m/s
                l.add(br);
                if (z >= 1 && rnd.nextInt(5) < 3) {
                    float L = r(4.5f, 6f);
                    Element rope = plat(Element.Type.ROPE, br.s + (rnd.nextBoolean() ? 1f : -1f) * r(0.7f, 1.5f), br.y + 0.7f + L, 0f, z);
                    rope.len = L;
                    l.add(rope);
                    l.add(plat(S, rope.s + lerp(1.0f, 2.2f, d) + 1.5f, rope.y - T.handHeight + 0.4f, 3f, z));
                } else {
                    if (br.skin == 1 && d > 0.1f && rnd.nextFloat() < 0.45f) holes(br);          // Build 79 (owner): some bridges drop plank sections when you land
                    l.add(plat(S, br.s + w / 2f + reach(0.6f) * lerp(0.4f, 0.8f, d) * (1f - 0.04f * attempt) + 1.5f, br.y + 0.6f, 3f, z));
                }
                break;
            }
            case RAMP: {
                // a walkway up to a block that is taller than a jump; from the second world it may crumble, shake you about, or spin like a record (Build 72)
                float sc = r(1.3f, 1.75f), L = 2.2f * sc, slope = RAMP_SLOPE, rise = slope * L;
                int skin = 0;
                if (z >= 1) { int roll = rnd.nextInt(10); skin = roll < 2 ? 0 : roll < 4 ? 1 : roll < 6 ? 2 : roll < 8 ? 3 : 4; }
                Element rp = plat(Element.Type.RAMP, eR + L / 2f, y0 + rise / 2f, L, z); rp.amp = slope; rp.skin = skin; rp.len = sc;      // Build 72 (owner): its foot meets the platform it starts from
                if (skin == 3) { rp.period = r(2.2f, 3.0f); rp.phase = r(0f, 6.28f); }       // one turn every 2.2-3 s
                if (skin == 4) rp.phase = lerp(1.3f, 3.4f, d) * r(0.95f, 1.05f);                // Build 72 (owner): a conveyor belt running downhill, faster the harder the climb (run speed is 5.6 m/s)
                l.add(rp);
                float wB = 3f + rnd.nextInt(2);
                if (z >= 1 && skin != 4 && rnd.nextInt(10) < 4) {          // (no ski jump off a belt: it would steal the run-up)         // ski jump: leap off the top of the ramp across a gap to a block up there
                    float dyJ = r(0f, 0.7f), gapJ = reach(dyJ) * lerp(0.5f, 0.85f, d) * r(0.92f, 1f) * (1f - 0.04f * attempt);
                    l.add(plat(S, rp.s + L / 2f + gapJ + wB / 2f, y0 + rise + dyJ, wB, z));
                    break;
                }
                Element blk = plat(S, rp.s + L / 2f + wB / 2f, y0 + rise, wB, z);         // Build 72 (owner): flush with the top of the ramp and touching it, so you walk straight on
                l.add(blk);
                break;
            }
            case GRAB: {
                float dy = r(0.7f, 1.3f);
                float frac = 1.28f - 0.06f * attempt;
                l.add(plat(S, eR + reach(dy) * frac + 1.5f, y0 + dy, 3f, z));
                break;
            }
            case LONG: {
                // a long, near-flat corridor you can walk along and stand fully in front of (owner request); trivially completable, it just takes more arc, so the spiral-pitch controller climbs after it
                float dy = r(-0.3f, 0.5f);
                float w = r(10f, 16f);
                float gapIn = reach(Math.max(0f, dy)) * lerp(0.45f, 0.8f, d) * r(0.9f, 1.05f);
                l.add(plat(S, eR + gapIn + w / 2f, y0 + dy, w, z));
                break;
            }
        }
        return l;
    }
}
