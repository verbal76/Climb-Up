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
    private int ctxHaz = 0;                 // leading hazards that are the previous slice's (copied with the context elements)
    private boolean endless;
    private float rampH;
    private int clubBoost;               // Build 71: modules left that should carry a crab or a bee for the club just placed
    private boolean wantCreature;        // the module being built should be a crab (TRAP) or a bee (HAZ)
    private boolean relaxed;            // last-resort mode (see chunkIn)
    private double base;                    // framed generation: absolute height = base + local y (a multiple of the castle spacing); 0 for the classic absolute-frame slices
    private List<Element> ghostElems = new ArrayList<>();   // earlier-slice elements, only consulted for spiral-layer clearance
    private List<Element> ghostHaz = new ArrayList<>();
    /** DC5: where each ghost pad or spring really lands (resolved on the previous slice, with its forks and structures), parallel to ghostElems. */
    private List<Element> ghostLand = new ArrayList<>();
    /** DC5: a fork being built ({from, first, count, rejoin}), so its pads' landings are known to every check before it is registered. */
    private int[] pendingFork;
    /** DC5: encounters proven by playing them through (Autopilot.chainClean): {from, to, ngh avalanche 1/0}. Re-played when a later module's hazards reach into them. */
    private final List<int[]> encounters = new ArrayList<>();
    public StringBuilder trace = new StringBuilder();
    public int hazardsPlaced, hazardTried, failLayer, failHaz, failPlan, failWindow;

    /**
     * The generation rules version (Build 64): every slice is a pure function of the seed, the slice index, the previous slice, the tuning and this code, so two
     * players on the same seed climb the same course only while this number matches. Bump it whenever generation, its proofs (the Sim the planner runs),
     * the hazards' initial configuration or the bundled tuning change what a seed produces; UI and other changes leave it alone. Version 1 is the Build 63
     * generator (the crab rules of Build 63 changed what a seed produced, so runs from earlier builds carry version 0, "old rules", and are not compared).
     * {@code GeneratorFingerprintTest} fails when generation changes without a bump.
     */
    public static final int RULES_VERSION = 16;     // 16 = (unreleased) owner repair authorisations 2026-10-28: saw fairness enforced at slice acceptance from the real arrival (SafeStart), solver recovery repositionings rehearsed with every hazard (the whole-slice witness), retries reset only encounters ahead of the checkpoint, a jump on the frame a deck vanishes is a plain jump; 15 = (unreleased) owner correction directive 2026-10-28 (consolidated audit DC1-DC10): physics corrections (pad bounce wins, no stale coyote or buffered leap across grabs/shoves, knock-off locks grabs and pushes from the contact, pull-ups let go of vanished or out-of-plane decks), proven, hit-free whole-slice witness with explicit verdicts, ledge catches proven through the pull-up, re-proofs from hazards' real reach and of encounters, insertion checks, route-aware creature decks; every mode's generation changes; 14 = (unreleased) owner directive 2026-10-27, NOT GONNA HAPPEN modernisation: NGH joins the progression controller with its own curve (850 to 1,210), history-aware variety (0.35), a looser 25 m guard (4x), RED Enforcers from castle 1 (2-3 a castle), avalanches of 5-7 blocks each with its own seeded size and physics (3-4 a castle, 15-45 s cooldown); NORMAL and PUNISHER generate exactly as in rules 13 (same fingerprint: a metadata-only version change for them); 13 = (unreleased) owner directive 2026-10-27: castle-to-castle progression controller (Progression: measured castles steer the next castle's terrain, hazard, enemy and interaction selection within fixed bounds; a 25 m spike guard), continuations at the castle's own demand first, NORMAL shares the history-aware variety at a lighter strength, PUNISHER's head start 1,250 m (castle 1 at NORMAL castles 3-4); NGH unchanged; 12 = (unreleased) owner amendments 2026-10-27: PUNISHER Enforcer robots (crabs, bees, skulls and blobs kept clear of them), spike-block avalanches (PUNISHER 2-3 a castle; NORMAL castles 9-10, two blocks), PUNISHER history-aware variety, NORMAL narrow rope-fallback correction; 11 = Build 82 (owner directive 2026-10-27, NORMAL seed 2 stopped at 881 m): while the spiral lags its pitch a refused pick falls back only to challenges that climb, and a slice ending must leave the next slice two ordinary steps (rises a hop or stair makes), climbing out first when it does not; 10 = (unreleased) Build 82 draft (owner release requirements 2026-10-26): spiral pitch measured above the revolution below, slice endings that leave the next slice room, relaxed attempts keep body clearance, geometry integrity enforced on every slice, challenges before ropes in the fallbacks; 9 = (unreleased) Build 82 draft (owner correction directive 2026-10-26): terrain difficulty rises over the whole climb with each mode's head start, NGH has its own profile, no enemies on spike-drop platforms (rare ones on intermediate safe platforms instead), the solver hops a skull at its real dash speed; 8 = Build 81 (owner decisions 2026-10-25): keys lie on a main-route platform (no rooms, branches or perches); 7 = Build 80 (owner directive 2026-10-24): no level gates, PUNISHER on its own curve, encounter-conflict rule, splits with reconnecting branches instead of dead ends; 6 = Build 79: skulls, blobs and crabs reach the platforms either side (crabs follow up to three, fall with crumbling decks), faster skull, no blob wobble, two to four crabs a deck, bee pairs, creatures on spike-drop rows, bridges that drop planks, flat-turning aimed cannons, an always-running balance conveyor, decoys kept off ropes, a slightly harder first world; 5 = Build 77: faster skull with loop+swoop, key detours proved with the skull at either end, one balance conveyor per section from Castle 6 to 10; 4 = Build 76: faster plank conveyor bridges; 3 = Build 72: flush ramps, faster crumbling, a wilder shaking ramp, the spinning ramp; 2 = Build 71: the bee homes in, skulls and green blobs, mortars out of step at random
    /** The rules version a climb started on a given build used (0 = unknown or before the stamps: not comparable). */
    public static int rulesOfBuild(int build) { return build >= 82 ? 9 : build >= 81 ? 8 : build >= 80 ? 7 : build >= 79 ? 6 : build >= 77 ? 5 : build >= 76 ? 4 : build >= 72 ? 3 : build >= 71 ? 2 : build >= 63 ? 1 : 0; }

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

    private static Course chunkIn(long seed, int k, Course prev, Tuning t, double base) { return chunkIn(seed, k, prev, t, base, 0, null); }

    /** Attempts per slice (each one a different, deterministic random stream; from attempt 12 on the shortcut spacing to the previous slice is waived). */
    public static final int ATTEMPTS = 24;

    /**
     * Test-only fault hook (generation recovery R1-R6, 2026-10-28): when set, an attempt it names is refused before it is built, as a real dead end
     * would be. Never set by the game (null: no effect, no cost). It draws no random numbers, so an unrefused attempt builds exactly as it would.
     */
    public interface FaultPlan { boolean refuse(long seed, int k, int attempt, Course prev); }
    public static volatile FaultPlan FAULT;

    /** Slice {@code k} from attempt {@code from} on (R2: a backtrack continues a slice's own attempt sequence); the accepted attempt goes to {@code acceptedOut[0]}. */
    private static Course chunkIn(long seed, int k, Course prev, Tuning t, double base, int from, int[] acceptedOut) {
        IllegalStateException last = null;
        for (int attempt = from; attempt < ATTEMPTS; attempt++) {      // a dead end is retried with different (but still deterministic) choices
            FaultPlan fp = FAULT;
            if (fp != null && fp.refuse(seed, k, attempt, prev)) { last = new IllegalStateException("forced refusal (test fault plan) at attempt " + attempt); continue; }
            CourseGenerator g = new CourseGenerator(seed, t, k + 1 + attempt * 1000003L);
            g.endless = true; g.rampH = t.rampHeight; g.base = base;
            g.relaxed = attempt >= 12;       // the previous slice can occasionally leave no room that keeps clear of every older spiral layer: after twelve normal tries, build above it without the shortcut spacing to it (never without body clearance: ghostClash) rather than ever failing
            lastForDebug = g;
            GenTrace tr = GenTrace.GEN.get(); long a0 = tr != null ? GenTrace.now() : 0L; if (tr != null) { tr.attempt = attempt; tr.relaxed = g.relaxed; }      // observe-only (reports)
            try { g.buildChunk(prev); g.checkIntegrity(); g.checkLaunchCorridors(); Course out = g.extract(); sawsFair(out, t); continuous(out, t); g.progFinish(out); if (tr != null) { tr.finalAttempt = attempt; tr.attemptNanos.add(new long[]{attempt, GenTrace.now() - a0, 1}); } if (acceptedOut != null) acceptedOut[0] = attempt; return out; }
            catch (IllegalStateException e) { last = e; SLICE_FAILURES.incrementAndGet(); GenTrace.tally("attempt_fail_" + failureClass(e.getMessage())); if (tr != null) { tr.sliceFailures.add("attempt " + attempt + " [" + failureClass(e.getMessage()) + "]: " + e.getMessage()); tr.attemptNanos.add(new long[]{attempt, GenTrace.now() - a0, 0}); } }
        }
        throw last != null ? last : new IllegalStateException("slice " + k + ": no attempts left from " + from);
    }

    /**
     * Rules 14 (owner directive 2026-10-27, whole-slice solvability and arrival-state fairness): every link of a slice is proven on its own from a
     * standing start at time 0, which does not show that the links can be climbed one after another - the landing, speed and hazard phases one move
     * leaves are where the next starts (PUNISHER seed 5 slice 64 failed this way on rules 13, seeds 1 and 2 on rules 14). A slice is accepted only once
     * the whole-course solver (Autopilot.run: the real simulation, every hazard live, moves planned from wherever the previous one ended, waiting,
     * repositioning and recovering like a person) has climbed it from its start to its closing rest; otherwise the attempt fails like any dead end and
     * the slice is built again with different deterministic choices. Bounded by the solver's own limits; no random numbers drawn.
     */
    /**
     * Saw fairness (owner decision 2026-10-27, SawFairnessTest; repair 2026-10-28): every saw guarding a route link leaves at least half of its cycle
     * to time the move, measured as the test measures it (SafeStart: from the climber's real arrival by the previous link's proven move, walking to the
     * proven spot and waiting with every hazard active). The link proofs measure the window from the proven start at time 0, which is the same thing
     * only when nothing else on the link moves: NORMAL seed 1 slice 1, a floor saw before a swing, measured 0.60 there and 0.40 from the real arrival.
     * A slice whose saw falls short is refused like any other failed check and built again with the next deterministic attempt.
     */
    private static void sawsFair(Course out, Tuning t) {
        for (Element h : out.hazards) {
            if (h.type != Element.Type.SAW_H || h.anchor < 0 || h.anchor >= out.routeSize() - 1) continue;
            SafeStart.Result ss = SafeStart.evaluate(out, t, h.anchor);
            if (ss.ok && ss.window >= SAW_MIN_WINDOW) continue;
            GenTrace.tally("saw_window_refused");
            throw new IllegalStateException("saw fairness: saw on link " + h.anchor + " leaves too short a window from the real arrival (" + ss + ")");
        }
    }
    /** The share of a saw's cycle that must stay open to time the move past it (SawFairnessTest). */
    static final float SAW_MIN_WINDOW = 0.5f;
    private static void continuous(Course out, Tuning t) {
        Autopilot.Report r = Autopilot.run(out, t, CONTINUITY_BUDGET);
        if (r.verdict == Autopilot.Verdict.PROVEN_SOLVABLE) return;          // DC1: only a proven, hit-free witness passes; UNKNOWN never does
        CONTINUITY_REFUSED.incrementAndGet(); GenTrace.tally("continuity_refused"); GenTrace.tally("continuity_" + r.verdict + "_" + r.cause);
        throw new IllegalStateException("whole-slice traversal " + r.verdict + " (" + r.cause + ") at link " + r.failedLink + ": " + r.failInfo);
    }
    /**
     * DC1 diagnostics: what kind of failure a refused slice attempt was. FAILED_VALIDATION: a constraint check of the assembled slice was violated
     * (geometry integrity, launch corridor); UNKNOWN / PROVEN_UNSOLVABLE: the whole-slice witness did not establish a route; GENERATION: the generator
     * could not place what it wanted (no module fits, no rest, castle approach) and retried. Read from the refusal's message, which every path sets.
     */
    public static String failureClass(String msg) {
        if (msg == null) return "GENERATION";
        if (msg.startsWith("geometry integrity") || msg.startsWith("launch corridor") || msg.startsWith("saw fairness")) return "FAILED_VALIDATION";
        if (msg.startsWith("whole-slice traversal PROVEN_UNSOLVABLE")) return "PROVEN_UNSOLVABLE";
        if (msg.startsWith("whole-slice traversal")) return "UNKNOWN";
        return "GENERATION";
    }
    /** Simulated seconds the whole-slice traversal may take (the validation gate's solver uses the same). */
    static final float CONTINUITY_BUDGET = 4000f;
    /** Diagnostics: slice attempts refused because the continuous traversal failed. */
    public static final java.util.concurrent.atomic.AtomicInteger CONTINUITY_REFUSED = new java.util.concurrent.atomic.AtomicInteger();

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

    /**
     * R2 (generation recovery): {@link #chunkFramed} from attempt {@code from} on; the accepted attempt is written to {@code acceptedOut[0]}. With
     * {@code from} 0 it is exactly chunkFramed.
     */
    public static Framed chunkFramedFrom(long seed, int k, Framed prev, Tuning t, int from, int[] acceptedOut) {
        if (prev == null) return new Framed(chunkIn(seed, k, null, t, 0.0, from, acceptedOut), 0.0, 0.0);
        Course pc = prev.c; Element last = pc.get(pc.routeSize() - 1);
        double step = t.castleSpacing > 0f ? t.castleSpacing : 500.0;
        double dy = Math.max(0.0, step * Math.floor(last.y / step)), ds = pc.circumference * Math.floor(last.s / pc.circumference);
        return new Framed(chunkIn(seed, k, shift(pc, (float) dy, (float) ds), t, prev.yBase + dy, from, acceptedOut), prev.yBase + dy, prev.sBase + ds);
    }

    /** A copy of a course translated by (-dy, -ds): used to put the previous slice into the next slice's frame. */
    public static Course shift(Course src, float dy, float ds) {
        Course out = new Course(src.seed, src.circumference);
        for (int i = 0; i < src.size(); i++) { Element e = copyOf(src.get(i), src.get(i).anchor); e.y -= dy; e.s -= ds; out.add(e); }
        for (Element h : src.hazards) { Element e = copyOf(h, h.anchor); e.y -= dy; e.s -= ds; out.hazards.add(e); }
        for (int[] k : src.keyRooms) out.keyRooms.add(k.clone());
        for (int[] f : src.forks) out.forks.add(f.clone());
        out.routeCount = src.routeCount; out.gemCheckpoints = src.gemCheckpoints; out.prog = src.prog == null ? null : src.prog.clone(); out.indexDecoys();
        return out;
    }

    // ---- difficulty as a function of height (all numbers live in tuning.json)
    private float diff(float y) {
        if (!endless) return Math.min(1f, Math.max(0f, (float) ((y + base) / rampH)));
        float f = DifficultyProfile.TERRAIN_FLOOR;          // endless: the whole 10-castle climb, read with this difficulty's head start (see DifficultyProfile.terrainCap)
        // each mode starts where NORMAL is at its head start (PUNISHER ~castle 5, NGH ~castle 8) and climbs to its own cap over the whole ten-castle
        // climb (owner 2026-10-26: read with the head start alone, PUNISHER reached its cap at castle 7 and NGH at castle 1, so their terrain stayed flat).
        // NORMAL (no head start, cap 1) is unchanged: f + (1 - f) * y / ramp.
        float start = f + (1f - f) * Math.min(1f, modeShift() / DifficultyProfile.TERRAIN_RAMP_Y);
        return Math.min(P.terrainCap, Math.max(0f, start + (P.terrainCap - start) * (float) ((y + base) / DifficultyProfile.TERRAIN_RAMP_Y)));
    }
    /**
     * The module mix (which kinds pick() may choose, and zone-dependent variants) as before rules 9: it opens over the first {@code rampHeight} metres.
     * Rules 9 read it off the 5,000 m terrain curve, which held back mortars, springs, seesaws, bridges, cables and the elevator / slide modules until
     * about 833 m in NORMAL (castle 2) and swings and grabs until 2,222 m: a mechanic unlock (owner directive 2026-10-24: none), the castle 2 hazard
     * spike, and MortarTest / EdgeContactTest finding none of them in their first slices. Terrain demand still follows {@link #diff}.
     */
    private int tier(float y) { return Math.min(3, (int) (Math.min(1f, Math.max(0f, (float) ((y + base) / rampH))) * 4f)); }
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
            c.add(start); ctx = 1; varHist.clear(); lastEnfY = Float.NEGATIVE_INFINITY;
        } else {
            // context: the tail of the previous slice (so the planner sees the real approach) plus everything else as geometry-only ghosts
            // DC5: two more decks than the hazards reach back to, so no copied hazard keeps a negative anchor (its deck absent); a hazard anchored on
            // the previous slice's side structures is kept as a ghost (it used to be dropped altogether)
            int rs = prev.routeSize(), m = Math.min(10, rs), from = rs - m;
            for (int i = from; i < rs; i++) c.add(copyOf(prev.get(i), -1));
            for (Element h : prev.hazards) if (h.anchor >= from && h.anchor < rs) c.hazards.add(copyOf(h, h.anchor - from));
            // (2026-10-26 audit: the relaxed attempts left the previous slice out altogether, so nothing kept their platforms and hazards from
            // overlapping its earlier route, branches and hazards one revolution down; now they only waive the shortcut spacing to it, see ghostClash)
            for (int i = 0; i < rs; i++) if (i < from) { ghostElems.add(prev.get(i)); ghostLand.add(landingOf(prev, i)); }
            for (int i = rs; i < prev.size(); i++) { ghostElems.add(prev.get(i)); ghostLand.add(landingOf(prev, i)); }
            for (Element h : prev.hazards) if (h.anchor < from || h.anchor >= rs) ghostHaz.add(h);
            ctx = m; ctxHaz = c.hazards.size();
            rests = prev.get(rs - 1).zone * 3 + rs;   // keeps the checkpoint cadence varied between slices
            varSeed(prev);
            lastEnfY = Float.NEGATIVE_INFINITY; for (Element h : prev.hazards) if (h.type == Element.Type.ENFORCER) lastEnfY = Math.max(lastEnfY, h.y);          // (prev is already in this frame)
        }
        float startY = c.get(ctx - 1).y;
        progStart(prev);
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
        GenTrace tr = GenTrace.GEN.get(); long p0 = GenTrace.now();      // (observe-only phase timing for reports)
        float extendTo = beltSawY > 0f ? Math.max(top, beltSawY + 12f) : top;
        // PUNISHER Enforcers are claimed like the keys: the slice whose first 80 m hold an encounter's height builds it, and climbs at least 25 m past it
        // (claiming only up to this slice's own top let a height between one slice's top and the next one's start fall through: seed 2, section 1).
        // Encounters not built by the end of the climb are dropped, never carried into the next slice's context.
        // A claim's extra climb never reaches an exact-height structure this slice does not own (PUNISHER seed 1, 1667 m: an avalanche claimed at
        // ~1745 m climbed past the red gem at 1750 m on every attempt); heights too close below it are left to the next slice.
        float cap = nextForeignStructure(startY) - EXACT_ROOM;
        planEnforcers(startY, Math.min(startY + 80f, cap - 25f));
        if (!enfPending.isEmpty()) extendTo = Math.max(extendTo, enfPending.get(enfPending.size() - 1)[0] + 25f);
        planAvalanches(startY, Math.min(startY + 80f, cap - 35f));          // claimed the same way; the climb goes on far enough to give the last one its decks ahead
        if (!avlPending.isEmpty()) extendTo = Math.max(extendTo, avlPending.get(avlPending.size() - 1)[0] + 35f);
        extend(extendTo);
        for (int k = 0; k < avlPending.size(); k++) { AVALANCHES_DROPPED.incrementAndGet(); GenTrace.tally("avalanche_dropped"); }
        avlPending.clear(); avlCand = -1;
        for (int k = 0; k < enfPending.size(); k++) { ENFORCERS_DROPPED.incrementAndGet(); GenTrace.tally("enforcer_dropped"); }
        long p1 = GenTrace.now();
        closeSlice(extendTo);
        c.get(c.size() - 1).checkpoint = true;
        thinCheckpoints();
        checkNoExactStructureSkipped(startY);
        long p2 = GenTrace.now();
        addDecoys(Math.max(3, ctx + 2), c.size() - 11);
        long p3 = GenTrace.now();
        finalizeRooms();
        if (tr != null) { tr.phase("extend", p1 - p0); tr.phase("closing_rest", p2 - p1); tr.phase("decoys_forks", p3 - p2); tr.phase("rooms", GenTrace.now() - p3); }
    }

    /**
     * The slice's closing rest, which the next slice builds on. 2026-10-26 (owner directive; PUNISHER seed 2 slice 49 failed twelve attempts at its first
     * module, every one refused by the spiral-layer rule against this slice, and went on in relaxed mode): a closing rest that leaves the next slice no
     * clear first step is not accepted. The next slice checks a new element against everything here except the last three route elements before it, with
     * the same layer rule, so that is checked now on plain probe platforms (the shape of its fallback steps). If none fits, the rest is placed again and,
     * failing that, the last plain module is undone and the climb rebuilt, a few times, before the slice attempt fails (and is retried here, where the
     * cause is, rather than the next slice failing over it). Closing rests that leave room are kept exactly as before (the check draws no random numbers).
     */
    private void closeSlice(float targetY) {
        for (int tries = 0; ; tries++) {
            int n0 = c.size(), h0 = c.hazards.size(), r0 = rests;
            // rules 11 recovery (NORMAL seed 2 slice 13): an end that is boxed in by the revolution below is climbed out of first - one vertical
            // module (the rest's own climb-first list), then the rest - before modules are undone; bounded like everything here
            if (tries >= 2) { int th = theme(c.get(c.size() - 1).y); for (Kind k : new Kind[]{Kind.PAD, Kind.MOVER_V, Kind.STAIRS, Kind.ROPE}) if (tryModuleBelow(k, 0.1f, th)) { GenTrace.tally("continuation_climb_" + k); break; } }
            addRest(theme(c.get(c.size() - 1).y));
            List<Element> probe = continuationProbe();
            if (probe != null) { contReserve = probe; return; }
            CONTINUATION_REFUSED.incrementAndGet(); GenTrace.tally("continuation_refused");
            rollbackTo(n0, h0); rests = r0;
            if (tries >= 5) throw new IllegalStateException("slice end leaves the next slice no clear first steps at " + c.size());
            if (tries >= 3 && !undo.isEmpty()) { int[] u = undo.pop(); rollbackTo(u[0], u[1]); prev = null; prev2 = null; GenTrace.tally("continuation_backtrack"); extend(targetY); }
        }
    }

    /**
     * Owner release requirement (2026-10-26): no accepted slice holds unintended overlapping geometry. Every element and hazard this slice added is
     * checked against everything in it, the previous slice's tail it builds on, and the previous slice itself (ghosts), with GeometryIntegrity; a
     * violation fails this attempt like any other dead end, so the slice is built again - never accepted with it.
     */
    private void checkIntegrity() {
        List<String> v = GeometryIntegrity.check(c, ctx, ghostElems, ghostHaz, 1);
        if (!v.isEmpty()) { INTEGRITY_REFUSED.incrementAndGet(); GenTrace.tally("integrity_refused"); throw new IllegalStateException("geometry integrity: " + v.get(0)); }
    }
    /** Diagnostics: slice attempts refused by the geometry integrity check. */
    public static final java.util.concurrent.atomic.AtomicInteger INTEGRITY_REFUSED = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Plain steps the next slice can take from the closing rest: {gap, rise}. Rules 11: only rises its ordinary modules make without launching or hanging
     * anything below them (a hop rises at most 1.4 m, a stair 0.9-1.3 m, a fork's entry 0.4 m). Rules 10 also accepted a 2.4 m rise, which no such module
     * makes: NORMAL seed 2 slice 13 closed 3.7 m above a deck of the revolution below and 2 cm clear of it, the 2.4 m probe passed, and every first
     * module of slice 14 (hops, stairs and hazard rows 0.3-1.3 m up, pads and elevators needing 9.5 m, ropes hanging into it) was refused.
     */
    private static final float[][] CONT_STEPS = {{2.0f, 0f}, {3.0f, 0f}, {2.0f, 0.6f}, {3.0f, 0.6f}, {2.0f, 1.2f}, {3.0f, 1.2f}};
    /**
     * Two plain steps after the closing rest (each of {@link #CONT_STEPS}) that keep clear of every other spiral layer here as the next slice will check
     * them, or null: the next slice must be able to climb away from the boundary with ordinary geometry, not merely set one foot down (a first step with
     * nowhere to go from it boxed in the next slice the same way). Draws no random numbers.
     */
    private List<Element> continuationProbe() { return continuationSteps(c, Math.max(0, ctx - 1), c.size(), c.size()); }
    /**
     * The two steps of {@link #continuationProbe} for a slice whose route is {@code c[from, routeEnd)} (closing rest last) and whose side structures are
     * {@code c[sideFrom, size)}, or null: each step against this slice as the next one receives it (extract: from ctx-1 on; the older ghosts it never
     * sees), with the layer rule the next slice applies (all route elements but the three before it). Public for the slice-boundary tests.
     */
    public static List<Element> continuationSteps(Course c, int from, int routeEnd, int sideFrom) {
        Element r = c.get(routeEnd - 1);
        for (float[] a : CONT_STEPS) {
            Element p = new Element(Element.Type.STATIC, r.s + r.w / 2f + a[0] + 1.5f, r.y + a[1], 3f);
            if (!stepClear(c, p, from, routeEnd - 3, sideFrom)) continue;
            for (float[] b : CONT_STEPS) {
                Element q = new Element(Element.Type.STATIC, p.s + p.w / 2f + b[0] + 1.5f, p.y + b[1], 3f);
                if (stepClear(c, q, from, routeEnd - 2, sideFrom)) return List.of(p, q);
            }
        }
        return null;
    }
    private static boolean stepClear(Course c, Element p, int from, int to, int sideFrom) {
        for (int i = from; i < to; i++) if (layersClash(c, p, c.get(i))) return false;
        for (int i = sideFrom; i < c.size(); i++) if (layersClash(c, p, c.get(i))) return false;
        return true;
    }
    /** The first steps closeSlice found clear: the side structures placed after them (dead ends, forks' key rooms) keep them clear (decoyOk). */
    private List<Element> contReserve;
    /** Diagnostics: closing rests refused because the next slice would have had no clear first step. */
    public static final java.util.concurrent.atomic.AtomicInteger CONTINUATION_REFUSED = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * A slice only looks 80 m ahead for the castle or red gem that belongs to it (castleTarget); a slice that grows taller than that (a key placed high in its section, rests, a long module) can climb
     * straight past an exact-height structure, and the next slice, already above it, cannot build it: a whole section would be left without its red gem (a fall would cost 500 m) or its castle.
     * Such a layout is rejected here, so the caller builds this slice again with different choices.
     */
    /** Room kept below an exact-height structure that is not this slice's own, for the closing rest above a claimed encounter's climb. */
    static final float EXACT_ROOM = 10f;
    /** Local height of the first castle or red gem above {@code startY} that is not this slice's castleTarget (no limit without castles). */
    private float nextForeignStructure(float startY) {
        if (T.castleSpacing <= 0f) return Float.MAX_VALUE;
        double half = T.castleSpacing * 0.5, startAbs = startY + base;
        for (double t = Math.floor(startAbs / half) * half; ; t += half) {
            if (t <= startAbs + 0.01) continue;
            if (castleTarget > 0f && Math.abs(t - (castleTarget + base)) < 0.5) continue;
            return (float) (t - base);
        }
    }
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
        GenTrace tr = GenTrace.GEN.get(); if (tr != null) { tr.offset = off; tr.hazardMap = newHz; }      // observe-only (reports)
        return out;
    }

    /** Appends modules until the last platform reaches height {@code targetY}. */
    /**
     * How high {@code e} is above the route one revolution back at the same arc (this slice's route and the previous slice's, which ghostElems holds),
     * or +infinity where nothing lies a revolution back (the first revolution of the climb). Positions are unwrapped arcs, so a revolution back is
     * {@code s - circumference}; the route element nearest that arc (within 8 m) stands for it.
     */
    private float layerGap(Element e) {
        float want = e.s - c.circumference, best = 8f, y = Float.NaN;
        for (int i = 0; i < c.size(); i++) { Element f = c.get(i); if (f.anchor >= 0) continue; float d = Math.abs(f.s - want); if (d < best) { best = d; y = f.y; } }
        for (Element f : ghostElems) { if (f.anchor >= 0) continue; float d = Math.abs(f.s - want); if (d < best) { best = d; y = f.y; } }
        return Float.isNaN(y) ? Float.POSITIVE_INFINITY : e.y - y;
    }

    /** Plain modules placed since the last structure, as {elements, hazards} before each (see extend; closeSlice can also undo one). */
    private final java.util.ArrayDeque<int[]> undo = new java.util.ArrayDeque<>();

    private void extend(float targetY) {
        if (castleTarget > 0f && !castleDone) targetY = Math.max(targetY, castleTarget + (exactCastle ? 8f : 6f));
        int sinceRest = 0, backtracks = 0;
        // local backtracking (2026-10-26): plain modules placed since the last structure (rest, castle, fork, balance conveyor), as {elements, hazards}
        // before each; a dead end undoes up to two of them and builds on with fresh choices instead of discarding the whole section
        int undoneHere = 0;
        float lastRestY = c.get(c.size() - 1).y, nextGap = T.gemSpacing * r(0.85f, 1.15f);      // red-gem checkpoints at even stretches of height, each with a little random give
        while (c.get(c.size() - 1).y < targetY || (beltSawY > 0f && !beltSawDone && c.get(c.size() - 1).y < beltSawY + 40f)) {      // (a balance conveyor that could not be proven yet gets up to 40 m more tries)
            Element last = c.get(c.size() - 1);
            float[] pf = fac(last.y); hotNow = hotWindow(last.y);          // rules 13: this castle's selection factors and the 25 m spike guard
            float d = adjD(diff(last.y), pf[Progression.T]);
            int zone = tier(last.y), th = theme(last.y);
            boolean castlePending = castleTarget > 0f && !castleDone;
            if (castlePending && exactCastle && last.y >= castleTarget - 6f) { int n0 = c.size(), h0 = c.hazards.size(); long s0 = GenTrace.now(); buildExactCastle(th); undo.clear(); traceStructure("CASTLE", n0, h0, last.y, s0); castleDone = true; lastRestY = c.get(c.size() - 1).y; continue; }
            if (castlePending && !exactCastle && last.y >= castleTarget - GEM_LEAD) { int n0 = c.size(), h0 = c.hazards.size(); long s0 = GenTrace.now(); chooseGem(th); undo.clear(); traceStructure("GEM", n0, h0, last.y, s0); castleDone = true; lastRestY = c.get(c.size() - 1).y; continue; }
            if (last.y - lastRestY >= nextGap && targetY - last.y > 0.4f * T.gemSpacing && !(castlePending && last.y > castleTarget - 14f)) {      // (not right before the slice's own closing rest)
                { int n0 = c.size(), h0 = c.hazards.size(); long s0 = GenTrace.now(); addRest(th); undo.clear(); traceStructure("REST", n0, h0, last.y, s0); } sinceRest = 0; lastRestY = c.get(c.size() - 1).y; nextGap = T.gemSpacing * r(0.85f, 1.15f); maybeCastle(th); continue;
            }
            float forkEvery = P.forkEvery;          // PUNISHER splits about twice as often, and its splits are longer
            if (Float.isNaN(lastForkY)) lastForkY = last.y - forkEvery * 0.5f;
            if (last.y - lastForkY >= forkEvery && last.isPlatform() && !last.isMoving() && !last.wet() && !(castlePending && last.y > castleTarget - 24f)) {
                int e0 = c.size(), n = P.forkRisers;
                forkRisers = n;
                traceRole = "FORK"; boolean forked = tryModuleBelow(Kind.FORK, d, th); traceRole = "PICK";
                if (forked) { undo.clear(); forkEntries.add(new int[]{e0, n}); lastForkY = last.y; prev2 = prev; prev = Kind.FORK; sinceRest++; continue; }
                lastForkY = last.y - forkEvery + 8f;          // no room here: try again a little higher
            }
            if (!enfPending.isEmpty() && last.y >= enfPending.get(0)[0] && last.isPlatform() && !last.isMoving() && !last.wet() && last.type != Element.Type.PAD && last.type != Element.Type.SPRING
                    && !(castlePending && last.y > castleTarget - 30f)) {          // PUNISHER: this slice's Enforcer encounter, as soon as the climb reaches its height
                float[] pl = enfPending.get(0);
                int n0 = c.size(), h0 = c.hazards.size(); long s0 = GenTrace.now();
                if (enforcerModule((int) pl[1], (int) pl[2], th)) { enfPending.remove(0); varRecord(n0, h0); lastEnfY = c.get(n0).y; undo.clear(); traceStructure("ENFORCER", n0, h0, last.y, s0); enfQuiet = 2; prev2 = prev; prev = null; sinceRest++; ENFORCERS_PLACED.incrementAndGet(); GenTrace.tally("enforcer_placed_t" + (int) pl[1]); continue; }
                // no fair encounter from this deck: tried again from the next suitable decks (a few modules on), then dropped
                if (++pl[3] >= ENF_TRIES) { enfPending.remove(0); ENFORCERS_DROPPED.incrementAndGet(); GenTrace.tally("enforcer_dropped"); }
                else pl[0] = last.y + 6f;
            }
            Kind k = pick(zone, last);
            // the spiral pitch controller (pick): while the route lags the climb a revolution needs, vertical modules come first; a lagging route brings the
            // next revolution down onto this one, after which every module fails the layer rule (NGH 2d4388a: "cannot extend course"). The safety-pressure
            // rules below (deferred Frost hazards, plain-pick pressure, threat fallback) therefore wait while it lags.
            boolean lagging = layerGap(last) < T.layerGapMin;
            if (deferredHaz != null && !last.wet() && !lagging) { k = deferredHaz; deferredHaz = null; }          // a hazard the Frost world's rain displaced comes on the next dry platform instead of being lost
            boolean clubTarget = false;
            if (clubBoost > 0 && !enfCalm()) { clubBoost--; k = rnd.nextBoolean() ? Kind.TRAP : Kind.HAZ; clubTarget = true; }      // Build 71 (owner): a club was just placed - the next few modules give it crabs and bees to hit
            wantCreature = clubTarget;
            if (th == 1 && last.type == Element.Type.STATIC && rnd.nextFloat() < 0.2f) { GenTrace.tally("wet_replaced_" + k); if (hazardKind(k)) deferredHaz = k; k = Kind.WET; }          // the Frost world: some platforms are under a rain cloud (slippery)
            float lv = Math.min(1f, level(last.y));
            boolean hazOk = !endless || intensity(last.y) > 0f;          // the first hazardStartY metres stay free of hazards and creatures (EndlessTest: a spike-drop row at 7.5 m, seed 3), as for every other hazard module
            if (hazOk && k != Kind.WET && prev != Kind.CREATURE && !last.wet() && !enfCalm() && rnd.nextFloat() < (0.025f + 0.045f * lv + 0.05f * late(last.y)) * P.creatureScale * (hotNow ? 0f : pf[Progression.E])) k = Kind.CREATURE;      // owner directive 2026-10-24: creature decks from Level 1 on, more as the levels rise (was world 2 on)
            else if (prev == Kind.CREATURE && !last.wet() && rnd.nextFloat() < 0.35f * late(last.y) * (hotNow ? 0f : pf[Progression.X])) k = rnd.nextBoolean() ? Kind.HAZ : Kind.TRAP;      // Build 71: higher up, a creature deck runs straight into a trap      // Build 71: a skull's deck (world 2 on), or a green blob's (world 3 on)
            boolean secondHalf = endless && th == 0 && ((last.y + base) % T.zoneHeight) >= 0.5f * T.zoneHeight - 15f;      // castle 1 to castle 2: the Meadow's second, harder half (owner, Build 71)
            if (hazOk && k != Kind.WET && k != Kind.CREATURE && !last.wet() && prev != Kind.DROPS && (secondHalf ? rnd.nextFloat() < 0.10f * (hotNow ? 0f : pf[Progression.H]) : rnd.nextFloat() < (0.015f + 0.025f * lv) * (hotNow ? 0f : pf[Progression.H]) * capDrops(last.y))) k = Kind.DROPS;      // (spike-drop rows anywhere, more higher up; was world 2 on)
            // a little rougher than the first half, and (Phase 1, 2026-10-26) from then on: it applied to castle 2 alone, so castle 2 stood out as a hazard
            // spike and castle 3 fell back to the first half's mix (the chained spike drops above stay castle 2's own)
            else if (hazOk && (k == Kind.HOP || (k == Kind.STAIRS && !lagging)) && rnd.nextFloat() < Math.max((secondHalf || (endless && last.y + base >= T.zoneHeight)) ? 0.3f : 0f, endless ? P.pressure(level(last.y)) : 0f) * (hotNow ? 0f : Math.min(1.25f, pf[Progression.H])))
                k = rnd.nextFloat() < 0.6f ? Kind.HAZ : Kind.TRAP;          // owner 2026-10-26: plain platforms grow rarer with the climb and the mode (DifficultyProfile.pressure)
            if (last.wet() && (k == Kind.HAZ || k == Kind.TRAP || k == Kind.MORTAR || k == Kind.CREATURE || k == Kind.DROPS)) { GenTrace.tally("wet_stairs_" + k); deferredHaz = k; k = Kind.STAIRS; }          // after a slippery platform give a walk-up (not a hazard you must time, nor a jump you must push off a slippery surface for): its timing windows assume a normal run-up (SawFairnessTest) and the plain climb stays walkable
            if (beltSawY > 0f && !beltSawDone && last.y >= beltSawY && !last.wet() && !(castlePending && last.y > castleTarget - 14f)) {      // Build 77: this section's balance conveyor (tried again on the next module if it cannot be proven here)
                beltSawNow = true; traceRole = "BELT"; boolean ok = tryModuleBelow(Kind.SEESAW, d, th); traceRole = "PICK"; beltSawNow = false;
                if (ok) { undo.clear(); beltSawDone = true; prev2 = prev; prev = Kind.SEESAW; sinceRest++; continue; }
            }
            int u0 = c.size(), uh0 = c.hazards.size();
            if (!tryModuleBelow(k, d, th)) {
                rejected++;
                boolean ok = false;
                tracePicked = k.name(); traceRole = "FALLBACK";
                if (endless && hazOk && !last.wet() && !lagging && hazardKind(k) && rnd.nextFloat() < Math.min(1f, P.threatFallback(level(last.y)) * pf[Progression.X])) {          // owner 2026-10-26: a refused hazard tries one other hazard before a safe platform
                    Kind fb = k == Kind.HAZ ? Kind.TRAP : Kind.HAZ;          // one alternative only: trying three, each with its full set of placement attempts, made NGH generation 3-4x slower (2d4388a)
                    if (tryModuleBelow(fb, d, th)) { k = fb; ok = true; GenTrace.tally("threat_fallback_ok"); } else GenTrace.tally("threat_fallback_failed");
                }
                // owner 2026-10-26 (release requirement): a refused challenge is not replaced by a rope or plain travel first. Up to two other
                // challenges are drawn from this spot's own mix (the pick weights: tier, height, pitch controller, repetition), at the same
                // difficulty, without ordinary traversal; refused geometry costs little (commit's geometry check comes before the proofs).
                // While the spiral lags its pitch only challenges that climb are drawn (rules 11): in rules 10 a refused pad or elevator was
                // replaced by a level hazard row from the same weights (the lagging controller only scales them down, and a draw renormalises),
                // so the route could wind on below its pitch for a whole slice (NORMAL seed 2 slice 13: 8.4 m a revolution, layer gaps of 3-10 m)
                // and leave the next slice no room above it (slice 14 failed all 24 attempts at its first module, 881 m).
                if (!ok) {
                    float[] alt = weights(zone, last); alt[k.ordinal()] = 0f;
                    for (Kind o : Kind.values()) if (ordinary(o) || (last.wet() && hazardKind(o)) || (!hazOk && hazardKind(o)) || (lagging && !climbs(o))) alt[o.ordinal()] = 0f;
                    for (int tries = 0; tries < 4 && !ok; tries++) {
                        Kind a2 = draw(alt); if (a2 == null) break; alt[a2.ordinal()] = 0f;
                        if (tryModuleBelow(a2, d, th)) { k = a2; ok = true; GenTrace.tally("challenge_fallback_ok"); } else GenTrace.tally("challenge_fallback_failed");
                    }
                }
                // then the continuation the climb needs, easiest proofs first: vertical moves with timing before stairs, and ropes and plain hops
                // last (rope first when the spiral lags its pitch: a hop flattens it), so a section always goes on
                Kind[] cont = continuation(zone, lagging, ordinaryUsed);
                if (P.ropeFallbackSpacing()) cont = ropeAfterRecent(cont, last.y, lagging); else if (P.adaptiveVariety()) cont = varOrder(cont, last.y);
                // rules 13 (NORMAL, PUNISHER): every continuation used to be built at the fixed easy demand 0.15 whatever the castle (25-43% of accepted
                // modules in rules 12); it is tried at this castle's own demand first, then at 0.15 as before, so the climb still always goes on
                float dFb = endless && P.progression() ? Math.max(0.15f, FALLBACK_DEMAND * d) : 0.15f;
                if (!ok) for (Kind fb : cont) {
                    if (fb == k) continue;
                    if (dFb > 0.15f && tryModuleBelow(fb, dFb, th)) { GenTrace.tally("fallback_at_demand_ok"); }
                    else if (tryModuleBelow(fb, 0.15f, th)) { if (dFb > 0.15f) GenTrace.tally("fallback_at_demand_failed"); }
                    else continue;
                    k = fb; ok = true; if (ordinary(fb)) ordinaryUsed[fb.ordinal()] = ++ordinaryClock; break;
                }
                traceRole = "PICK"; tracePicked = null;
                if (!ok && !undo.isEmpty() && undoneHere < 2 && backtracks < 6) {
                    int[] u = undo.pop(); rollbackTo(u[0], u[1]); backtracks++; undoneHere++; BACKTRACKS.incrementAndGet(); prev = null; prev2 = null; GenTrace.tally("backtrack");
                    continue;
                }
                if (!ok) throw new IllegalStateException("cannot extend course (layer=" + failLayer + " haz=" + failHaz + " plan=" + failPlan + " window=" + failWindow + ") at " + c.size() + " last=" + last.type + " s=" + last.s + " y=" + last.y + " below=" + below(last));
            }
            undo.push(new int[]{u0, uh0}); undoneHere = 0;
            varRecord(u0, uh0);
            if (enfQuiet > 0) enfQuiet--;
            tryAvalanche();
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

    // ---- observe-only generation trace (reports, 2026-10-26): reads sizes and refusal counters, never the random stream or any decision
    private String traceRole = "PICK", tracePicked;
    private boolean tryModuleBelow(Kind k, float d, int zone) {
        if (capOn() && capKind(k) >= 0) { int h0 = c.hazards.size(); float y = c.get(c.size() - 1).y; boolean ok = tryModuleBelowT(k, d, zone); capCount(k, y, h0, ok); return ok; }
        return tryModuleBelowT(k, d, zone);
    }
    private boolean tryModuleBelowT(Kind k, float d, int zone) {
        GenTrace tr = GenTrace.GEN.get();
        if (tr == null) return tryModuleBelow0(k, d, zone);
        GenTrace.Module m = new GenTrace.Module();
        m.attempt = tr.attempt; m.kind = k.name(); m.role = traceRole; m.picked = tracePicked; m.d = d; m.y = (float) (base + c.get(c.size() - 1).y);
        m.e0 = c.size(); m.h0 = c.hazards.size();
        int[] f0 = {failLayer, failHaz, failPlan, failWindow, failConflict, failThreat};
        long t0 = GenTrace.now();
        boolean ok = tryModuleBelow0(k, d, zone);
        m.nanos = GenTrace.now() - t0;
        int[] f1 = {failLayer, failHaz, failPlan, failWindow, failConflict, failThreat};
        for (int i = 0; i < 6; i++) m.fails[i] = f1[i] - f0[i];
        m.ok = ok; m.e1 = c.size(); m.h1 = c.hazards.size(); tr.modules.add(m);
        return ok;
    }
    private void traceStructure(String kind, int n0, int h0, float y, long s0) {
        GenTrace tr = GenTrace.GEN.get(); if (tr == null) return;
        GenTrace.Module m = new GenTrace.Module(); m.nanos = GenTrace.now() - s0;
        m.attempt = tr.attempt; m.kind = kind; m.role = "STRUCTURE"; m.y = (float) (base + y); m.ok = c.size() > n0 || c.hazards.size() > h0;
        m.e0 = n0; m.e1 = c.size(); m.h0 = h0; m.h1 = c.hazards.size(); tr.modules.add(m);
    }

    /** A module that must not carry the climb past the castle's approach window (keeps the last platform at least 1.6 m below the castle deck). */
    private boolean tryModuleBelow0(Kind k, float d, int zone) {
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
    // ------------------------------------------------------------------ PUNISHER Enforcer encounters (owner 2026-10-27)

    /**
     * The Enforcer encounters planned for section n (castle n-1 to castle n), fixed by the seed and the section like the keys: one, or two with a chance
     * growing with the curve level (never more), each at a height spread through the section, with its tier (0 yellow, 1 orange, 2 red: yellow early,
     * orange from the middle levels, red late and most often at the top) and its length in decks (3-5). Returns {height, tier, decks} per encounter.
     */
    public static float[][] enforcerPlan(long seed, int n, float spacing, float curveShift) {
        Random r = new Random(seed * 0xD6E8FEB86659FD93L + n * 0x9E3779B97F4A7C15L + 53L);
        float lo = spacing * (n - 1) + 45f, hi = spacing * n - 70f, mid = spacing * (n - 1) + spacing * 0.5f;
        float lv = (float) ((spacing * (n - 0.5) + curveShift) / (10.0 * spacing));
        int count = r.nextFloat() < Math.max(0.15f, Math.min(0.75f, 0.15f + 0.6f * (lv - 0.4f))) ? 2 : 1;
        float[][] out = new float[count][];
        for (int k = 0; k < count; k++) {
            float a = lo + (hi - lo) * k / count, b = lo + (hi - lo) * (k + 1) / count, h = a;
            for (int i = 0; i < 16; i++) { h = a + r.nextFloat() * (b - a); if (Math.abs(h - mid) >= 25f) break; }
            float red = Math.max(0f, Math.min(0.7f, (lv - 0.85f) / 0.5f)), orange = Math.max(0f, Math.min(1f, (lv - 0.5f) / 0.35f)) * (1f - red), u = r.nextFloat();
            int tier = u < red ? 2 : u < red + orange ? 1 : 0;
            out[k] = new float[]{h, tier, 3 + r.nextInt(3)};
        }
        return out;
    }

    /**
     * NOT GONNA HAPPEN's Enforcer encounters for section n (rules 14, owner 2026-10-27): RED Enforcers only (tier 2, the existing robot AI), possible
     * from castle 1, two a castle interval and sometimes three (more often higher up), never more, 3-5 decks each. Its own random stream (a different
     * salt), so PUNISHER's plan is untouched. Guidance, not a quota: an encounter that cannot be proven clean is dropped. Returns {height, 2, decks}.
     */
    public static float[][] nghEnforcerPlan(long seed, int n, float spacing) {
        Random r = new Random(seed * 0xA0761D6478BD642FL + n * 0xE7037ED1A0B428DBL + 59L);
        float lo = spacing * (n - 1) + 45f, hi = spacing * n - 70f, mid = spacing * (n - 1) + spacing * 0.5f;
        int count = 2 + (r.nextFloat() < Math.min(0.5f, 0.2f + 0.035f * (n - 1)) ? 1 : 0);
        float[][] out = new float[count][];
        for (int k = 0; k < count; k++) {
            float a = lo + (hi - lo) * k / count, b = lo + (hi - lo) * (k + 1) / count, h = a;
            for (int i = 0; i < 16; i++) { h = a + r.nextFloat() * (b - a); if (Math.abs(h - mid) >= 25f) break; }
            out[k] = new float[]{h, 2, 3 + r.nextInt(3)};
        }
        return out;
    }
    /** The plan the current mode uses (NGH red-only, PUNISHER tiered). */
    private float[][] enforcerPlanFor(int n) { return P == DifficultyProfile.NGH ? nghEnforcerPlan(c.seed, n, T.castleSpacing) : enforcerPlan(c.seed, n, T.castleSpacing, P.curveShift); }

    /** Diagnostics (never read by generation): encounters placed, and planned encounters dropped for want of a fair window in their slice. */
    public static final java.util.concurrent.atomic.AtomicInteger ENFORCERS_PLACED = new java.util.concurrent.atomic.AtomicInteger(), ENFORCERS_DROPPED = new java.util.concurrent.atomic.AtomicInteger();

    /** Spots an encounter is tried from (each a few modules higher) before it is dropped. */
    private static final int ENF_TRIES = 4;
    /** This slice's Enforcer encounters still to build ({height above this slice's frame, tier, decks, failed tries}), lowest first (PUNISHER only). */
    private final List<float[]> enfPending = new ArrayList<>();
    /** Modules left after an Enforcer encounter in which no crab, bee, skull or blob deck is drawn (the robot's encounter keeps its room). */
    private int enfQuiet;

    /**
     * PUNISHER (owner directive 2026-10-27): crabs, bees, skulls and blobs do not clutter an Enforcer encounter, so none are drawn in the two modules
     * after one or within ENF_CALM m of it (NOT GONNA HAPPEN too since rules 14). Always false in NORMAL (no Enforcers), so its draws are untouched.
     */
    private boolean enfCalm() {
        float y = c.get(c.size() - 1).y;
        return enfQuiet > 0 || (!enfPending.isEmpty() && y >= enfPending.get(0)[0] - ENF_CALM) || y - lastEnfY < ENF_CALM;
    }
    /** (2026-10-27, measured: within 40 m of an encounter ordinary enemies were denser than elsewhere, 13.1 vs 10.2 per 100 m on PUNISHER seed 1)
     *  the robot's stretch is kept clear of crab, bee, skull and blob decks from ENF_CALM m below a planned encounter to ENF_CALM m above a placed one. */
    static final float ENF_CALM = 25f;
    /** Height of the last Enforcer encounter (this slice's frame; carried from the previous slice), -inf before any. */
    private float lastEnfY = Float.NEGATIVE_INFINITY;

    /** The planned encounters whose heights this slice claims (above its start, up to {@code topY}). */
    private void planEnforcers(float startY, float topY) {
        enfPending.clear(); enfQuiet = 0;
        if (!endless || !P.enforcers() || T.castleSpacing <= 0f) return;
        double sp = T.castleSpacing, startAbs = startY + base, topAbs = topY + base;
        for (int n = Math.max(1, (int) Math.floor(startAbs / sp) + 1); n <= (int) Math.floor(topAbs / sp) + 1; n++)
            for (float[] pl : enforcerPlanFor(n))
                if (pl[0] > startAbs && pl[0] <= topAbs) enfPending.add(new float[]{(float) (pl[0] - base), pl[1], pl[2], 0f});
        enfPending.sort((x, y) -> Float.compare(x[0], y[0]));
    }

    /**
     * An Enforcer encounter as its own module (owner 2026-10-27: its own encounter logic, with room to do its job): {@code len} clean, solid, flat decks it
     * can hop between (gaps 1.3-2.6 m, at most ENF_HOP_GAP; small rises) and an exit deck, the robot waiting at the far end of the first one. The decks are
     * proven like any module's (commit); the encounter is kept only if a climber gets from the deck before it to the exit deck clean with the robot active
     * (Autopilot.chainClean). Up to four geometries are tried.
     */
    private boolean enforcerModule(int tier, int len, int zone) {
        for (int attempt = 0; attempt < 4; attempt++) {
            int n0 = c.size(), h0 = c.hazards.size();
            Element last = c.get(n0 - 1);
            List<Element> l = new ArrayList<>();
            float s = last.s + last.w / 2f, y = last.y;
            for (int k = 0; k <= len; k++) {          // the robot's decks, then the exit deck
                float w = k == len ? r(3f, 4.5f) : r(3.2f, 5.2f), gap = r(1.3f, 2.6f) * (1f - 0.08f * attempt), dy = r(-0.2f, 0.8f);
                s += gap + w / 2f; y += dy; l.add(plat(Element.Type.STATIC, s, y, w, zone)); s += w / 2f;
            }
            if (!commit(l, 0.12f)) { GenTrace.tally("enforcer_geometry"); continue; }
            Element d = c.get(n0), r = new Element(Element.Type.ENFORCER, d.s, d.y, d.w);
            r.anchor = n0; r.len = len; r.skin = tier; r.zone = zone; r.phase = 0.01f + 0.98f * rnd.nextFloat();          // its own seed: where it waits, how soon it notices you, how it leaps away
            c.hazards.add(r);
            if (!insertionClear(r)) { rollbackTo(n0, h0); GenTrace.tally("enforcer_insertion_refused"); continue; }          // (DC5: the checks every hazard gets)
            if (Autopilot.chainClean(c, T, n0 - 1, n0 + len)) { encounters.add(new int[]{n0 - 1, n0 + len, 0}); return true; }
            rollbackTo(n0, h0); GenTrace.tally("enforcer_unfair");
        }
        return false;
    }

    // ------------------------------------------------------------------ spike-block avalanches (owner 2026-10-27)

    /**
     * The avalanches planned for section n (castle n-1 to castle n), fixed by the seed and the section like the keys: {height, blocks, cooldown s, seed}.
     * PUNISHER: two, sometimes three (more often higher up), never more; 4 or 5 blocks (5 more often higher up). NORMAL: none before castle 9; castle 9
     * rarely one, castle 10 a little more often, none past castle 10; always 2 blocks. The cooldown (30-90 s of simulation time) only makes an
     * avalanche eligible; none is ever forced. Guidance, not a quota: a planned avalanche that cannot be proven safe is dropped. NOT GONNA HAPPEN (mode
     * 3, rules 14, owner 2026-10-27): three, sometimes four (more often higher up), never more, from castle 1; 5-7 blocks; cooldown 15-45 s; its own
     * random stream, so the other modes' plans are untouched.
     */
    public static float[][] avalanchePlan(long seed, int n, float spacing, int mode) {
        if (mode == 0 || n < 1) return new float[0][];
        if (mode == 3) {
            Random q = new Random(seed * 0xD1B54A32D192ED03L + n * 0x8CB92BA72F3D8DD7L + 73L);
            int cnt = 3 + (q.nextFloat() < Math.min(0.6f, 0.3f + 0.035f * (n - 1)) ? 1 : 0);
            float lo = spacing * (n - 1) + 40f, hi = spacing * n - 60f;
            float[][] out = new float[cnt][];
            for (int k = 0; k < cnt; k++) {
                float a = lo + (hi - lo) * k / cnt, b = lo + (hi - lo) * (k + 1) / cnt, h = a + q.nextFloat() * (b - a);
                out[k] = new float[]{h, 5 + q.nextInt(3), 15f + 30f * q.nextFloat(), q.nextFloat(), 0f};
            }
            return out;
        }
        Random r = new Random(seed * 0x9FB21C651E98DF25L + n * 0xC13FA9A902A6328FL + 71L);
        int count, blocks;
        if (mode == 2) { count = 2 + (r.nextFloat() < Math.min(0.85f, 0.25f + 0.07f * (n - 1)) ? 1 : 0); blocks = -1; }
        // NORMAL: castles 9 and 10 only (owner), none in the climb on past the finish
        else { if (n < 9 || n > 10) return new float[0][]; float u = r.nextFloat(); count = n == 9 ? (u < 0.3f ? 1 : 0) : (u < 0.45f ? 1 : 0) + (u < 0.08f ? 1 : 0); blocks = 2; }
        float lo = spacing * (n - 1) + 40f, hi = spacing * n - 60f;
        float[][] out = new float[count][];
        for (int k = 0; k < count; k++) {
            float a = lo + (hi - lo) * k / count, b = lo + (hi - lo) * (k + 1) / count, h = a + r.nextFloat() * (b - a);
            int nb = blocks > 0 ? blocks : 4 + (r.nextFloat() < Math.min(0.9f, 0.3f + 0.07f * (n - 1)) ? 1 : 0);
            out[k] = new float[]{h, nb, 30f + 60f * r.nextFloat(), r.nextFloat(), 0f};
        }
        return out;
    }

    /** Diagnostics (never read by generation): avalanches placed, and planned ones dropped for want of a provable spot in their slice. */
    public static final java.util.concurrent.atomic.AtomicInteger AVALANCHES_PLACED = new java.util.concurrent.atomic.AtomicInteger(), AVALANCHES_DROPPED = new java.util.concurrent.atomic.AtomicInteger();
    /** Trigger decks an avalanche is tried from before it is dropped. */
    private static final int AVL_TRIES = 4;
    /** NOT GONNA HAPPEN round 1 correction: the extra arrival delays (s) an avalanche is also proven with (the climber waits on the deck before the trigger). */
    static final float[] AVL_NGH_WAITS = {0.7f, 1.6f, 2.9f};
    /** This slice's avalanches still to place ({height above the frame, blocks, cooldown, seed, failed tries}), lowest first; the deck being tried (-1 none). */
    private final List<float[]> avlPending = new ArrayList<>();
    private int avlCand = -1;
    /** Avalanches placed in this slice ({hazard index, plan}), so an undo of their modules plans them again. */
    private final List<Object[]> avlPlaced = new ArrayList<>();

    private void planAvalanches(float startY, float topY) {
        avlPending.clear(); avlPlaced.clear(); avlCand = -1;
        if (!endless || P.avalanches() == 0 || T.castleSpacing <= 0f) return;
        double sp = T.castleSpacing, startAbs = startY + base, topAbs = topY + base;
        for (int n = Math.max(1, (int) Math.floor(startAbs / sp) + 1); n <= (int) Math.floor(topAbs / sp) + 1; n++)
            for (float[] pl : avalanchePlan(c.seed, n, T.castleSpacing, P.avalanches()))
                if (pl[0] > startAbs && pl[0] <= topAbs) avlPending.add(new float[]{(float) (pl[0] - base), pl[1], pl[2], pl[3], 0f});
        avlPending.sort((x, y) -> Float.compare(x[0], y[0]));
    }

    /** A trigger deck: a plain fixed platform on the route with room to stand, nothing anchored on it, not in the castle's approach. */
    private boolean avlTriggerDeck(int j) {
        Element d = c.get(j);
        if (d.type != Element.Type.STATIC || d.wet() || d.w < 2.5f || d.anchor >= 0 || (castleTarget > 0f && !castleDone && d.y > castleTarget - 30f)) return false;
        for (Element h : c.hazards) if (h.anchor == j) return false;
        return true;
    }

    /**
     * After each accepted module: once the climb reaches the next planned height a trigger deck is chosen, and once four route decks lie beyond it the
     * avalanche is attached there and kept only if a climber gets from the deck before it to the fourth deck ahead clean, with the blocks falling,
     * bouncing and tumbling for real (Autopilot.chainClean, every hazard active). Otherwise the next suitable deck is tried, up to AVL_TRIES, then it
     * is dropped. Nothing about the geometry changes; the generator's random numbers are not touched.
     */
    private void tryAvalanche() {
        if (avlPending.isEmpty()) return;
        int last = c.size() - 1;
        if (avlCand < 0) { if (c.get(last).y >= avlPending.get(0)[0] && avlTriggerDeck(last)) avlCand = last; return; }
        if (last < avlCand + 4) return;
        if (!avlTriggerDeck(avlCand)) { avlCand = -1; GenTrace.tally("avalanche_trigger_taken"); return; }          // (DC5: something was anchored on it since it was chosen: choose again)
        float[] pl = avlPending.get(0);
        Element d = c.get(avlCand), a = new Element(Element.Type.AVALANCHE, d.s, d.y, d.w);
        a.anchor = avlCand; a.len = pl[1]; a.period = pl[2]; a.phase = pl[3]; a.zone = d.zone; if (P == DifficultyProfile.NGH) a.skin = 1;          // NOT GONNA HAPPEN's blocks: each its own size and physics (Sim.stepChaos)
        int hi = c.hazards.size(); c.hazards.add(a);
        long t0 = GenTrace.now();
        boolean proven = insertionClear(a) && Autopilot.chainClean(c, T, avlCand - 1, avlCand + 4);          // (DC5: the checks every hazard gets)
        if (proven && a.skin == 1) for (float w : AVL_NGH_WAITS) if (!Autopilot.chainClean(c, T, avlCand - 1, avlCand + 4, w)) { proven = false; GenTrace.tally("avalanche_arrival_unfair"); break; }      // NGH: every arrival time
        if (proven) {
            avlPending.remove(0); avlPlaced.add(new Object[]{hi, pl}); varAdd(hazFam(a), a.y, c.size()); encounters.add(new int[]{avlCand - 1, avlCand + 4, a.skin == 1 ? 1 : 0}); avlCand = -1;
            AVALANCHES_PLACED.incrementAndGet(); GenTrace.tally("avalanche_placed_b" + Math.round(pl[1]));
            GenTrace.tally("avalanche_proof_ms", (int) ((GenTrace.now() - t0) / 1_000_000L));
            return;
        }
        c.hazards.remove(hi); GenTrace.tally("avalanche_unfair");
        avlCand = -1;
        if (++pl[4] >= AVL_TRIES) { avlPending.remove(0); AVALANCHES_DROPPED.incrementAndGet(); GenTrace.tally("avalanche_dropped"); }
        else pl[0] = c.get(last).y;          // the next suitable deck from here on
    }

    /**
     * NORMAL's narrow rope correction (owner 2026-10-27, authorised after measuring: NORMAL seed 1 to 5,000 m had 72% of its rope modules from this
     * continuation list, and 42% of rope-to-rope spacings under 10 m). When a rope was placed within ROPE_NEAR m below, the continuation tries the rope
     * after stairs (lagging: stairs climb too) or last, so it is used when nothing else fits rather than first. Nothing else in NORMAL's selection
     * changes; the rope stays in the list (a refused climb is never left without it).
     */
    static final float ROPE_NEAR = 12f;
    private Kind[] ropeAfterRecent(Kind[] list, float y, boolean lagging) {
        boolean recent = false;
        for (int i = c.size() - 1; i >= Math.max(0, c.size() - 40) && !recent; i--) { Element e = c.get(i); if (e.type == Element.Type.ROPE && e.y >= y - ROPE_NEAR) recent = true; }
        if (!recent) return list;
        List<Kind> l = new ArrayList<>(List.of(list));
        if (!l.remove(Kind.ROPE)) return list;
        int at = lagging && l.contains(Kind.STAIRS) ? l.indexOf(Kind.STAIRS) + 1 : l.size();
        l.add(at, Kind.ROPE);
        GenTrace.tally("rope_fallback_after_recent");
        return l.toArray(new Kind[0]);
    }

    // ------------------------------------------------------------------ castle-to-castle progression (owner directive 2026-10-27, rules 13)

    /** The measurements carried in from the previous slice, and the selection factors for the castle in progress and the one after it. */
    private Progression progIn = new Progression();
    private static final float[] ONE = {1f, 1f, 1f, 1f};
    private float[] facCur = ONE, facNext = ONE;
    /** True while the 25 m window being built already carries a threat spike (see hotWindow). */
    private boolean hotNow;
    /** Continuations are tried at this share of the castle's terrain demand first (PROVISIONAL). */
    static final float FALLBACK_DEMAND = 0.7f;
    /** How strongly the terrain factor moves the terrain demand d (precision: gaps, landings, mover spans), and its ceiling (PROVISIONAL). */
    static final float DEMAND_GAIN = 0.6f, DEMAND_MAX = 1.1f;
    /**
     * A 25 m window whose route hazards and enemies already weigh this many times the castle's per-window hazard + enemy target is a spike (NORMAL,
     * PUNISHER). NOT GONNA HAPPEN (rules 14) allows HOT_NGH: brutal stretches are its point; the guard only stops a pile-up that no longer reads as a
     * sequence (unfair), and every intervention is counted (progression_hot_window).
     */
    static final float HOT = 3f, HOT_NGH = 4f;
    /** The proof margin a balance conveyor's links need (NORMAL, PUNISHER; PROVISIONAL). */
    static final float BELT_MARGIN = 0.25f;

    private void progStart(Course prev) {
        progIn = prev == null ? new Progression() : Progression.of(prev.prog);
        capAdd.clear(); hcCur = null;
        if (!endless || !P.progression() || T.castleSpacing <= 0f) { facCur = facNext = ONE; return; }
        facCur = progIn.factors(progIn.castle(), P, T.castleSpacing);
        facNext = progIn.factors(progIn.castle() + 1, P, T.castleSpacing);
        if (capOn()) { hcCur = progIn.hazardControlFor(progIn.castle(), P, T.castleSpacing); if (hcCur[2] != Progression.HC_NONE) GenTrace.tally("capacity_constrained_" + Progression.HC_NAMES[(int) hcCur[2]]); }
    }
    /** Measures the finished slice into the state it hands to the next one. */
    private void progFinish(Course out) {
        if (!endless || !P.progression() || T.castleSpacing <= 0f) return;
        Progression p = progIn.copy(); if (capOn()) p.addCapacity(capAdd, hcCur); p.addSlice(out, base, T, facCur); out.prog = p.toArray();
    }

    // ------------------------------------------------------------------ capacity-aware hazard escalation (NOT GONNA HAPPEN; owner amendment 2026-10-27)
    /** This slice's hazard-module attempts by castle ({attempts, accepted, hazard points added} per Progression.CAP_KINDS kind), and its hazard control. */
    private final java.util.TreeMap<Integer, float[]> capAdd = new java.util.TreeMap<>();
    private float[] hcCur;
    private boolean capOn() { return endless && P.capacityControl() && T.castleSpacing > 0f; }
    private static int capKind(Kind k) { switch (k) { case HAZ: return 0; case TRAP: return 1; case MORTAR: return 2; case DROPS: return 3; default: return -1; } }
    /** Records a hazard-module attempt: whether it was placed and the hazard points it added (Progression's weights). */
    private void capCount(Kind k, float y, int h0, boolean ok) {
        int q = capKind(k); if (q < 0) return;
        float[] a = capAdd.computeIfAbsent(Progression.castleOf(y + base, T.castleSpacing), x -> new float[Progression.CAP_W]);
        a[3 * q] += 1f;
        if (!ok) return;
        a[3 * q + 1] += 1f;
        for (int i = h0; i < c.hazards.size(); i++) if (Progression.hazard(c.hazards.get(i).type)) a[3 * q + 2] += Progression.points(c.hazards.get(i).type);
    }
    /** Per-kind multipliers at height y: the measured capacity of the castle before and this one, this slice's attempts included. */
    private float[] capMult(float y) { return progIn.capMultipliers(capAdd.get(Progression.castleOf(y + base, T.castleSpacing))); }
    /** The hazard row, trap and mortar weights re-weighted by their measured yield, the hazard class keeping its total (the arrangement adapts, not the amount). */
    private void capAdapt(float[] w, float y) {
        float[] m = capMult(y); Kind[] ks = {Kind.HAZ, Kind.TRAP, Kind.MORTAR}; float before = 0, after = 0;
        for (Kind k : ks) before += w[k.ordinal()];
        if (before <= 0f) return;
        for (Kind k : ks) { w[k.ordinal()] *= m[capKind(k)]; after += w[k.ordinal()]; }
        if (after > 0f) for (Kind k : ks) w[k.ordinal()] *= before / after;
    }
    /** The spike-drop row chance's capacity multiplier (1 outside NGH). */
    private float capDrops(float y) { return capOn() ? capMult(y)[3] : 1f; }
    /** The selection factors {terrain, hazards, enemies, interaction} at height y. */
    private float[] fac(float y) {
        if (!endless || !P.progression() || T.castleSpacing <= 0f) return ONE;
        return Progression.castleOf(y + base, T.castleSpacing) <= progIn.castle() ? facCur : facNext;
    }
    private float adjD(float d, float fT) { return fT == 1f ? d : Math.max(0.05f, Math.min(Math.max(d, DEMAND_MAX), d * (1f + DEMAND_GAIN * (fT - 1f)))); }
    /** Route hazards and enemies already placed in the 25 m window of height y, in lane-report points. */
    private float windowLoad(float y) {
        double w0 = Math.floor((y + base) / Progression.WINDOW) * Progression.WINDOW - base, w1 = w0 + Progression.WINDOW; float sum = 0f;
        for (Element h : c.hazards) {
            if (h.anchor < 0 || h.anchor >= c.size()) continue;
            Element p = c.get(h.anchor); if (p.anchor >= 0 || p.y < w0 || p.y >= w1) continue;
            if (Progression.hazard(h.type) || Progression.enemy(h.type)) sum += Progression.points(h.type);
        }
        return sum;
    }
    private boolean hotWindow(float y) {
        if (!endless || !P.progression() || T.castleSpacing <= 0f) return false;
        int n = Progression.castleOf(y + base, T.castleSpacing);
        float cap = (P == DifficultyProfile.NGH ? HOT_NGH : HOT) * (P.target(n, Progression.H) + P.target(n, Progression.E)) / (T.castleSpacing / Progression.WINDOW);
        boolean hot = windowLoad(y) > cap; if (hot) GenTrace.tally("progression_hot_window");
        return hot;
    }

    // ------------------------------------------------------------------ history-aware variety (PUNISHER; owner 2026-10-27)

    /**
     * The generator remembers what it has put in the course (accepted placements only: an undone module is forgotten with it) and makes a mechanic
     * that appeared recently and often less likely, fading with the height climbed since (VarietyMemory: nothing is forbidden, nothing is a quota). The
     * weight a penalised module loses goes to the other modules of its own class (ordinary traversal, terrain challenges, hazards), so a repeated
     * challenge gives way to another challenge, not to a plain platform. Families are the variety report's (VarietyReport). The memory carries across
     * slices: each slice starts with the last VarietyMemory.SPAN m of the one before. Deterministic: heights only, StrictMath.
     */
    static final int F_ROPE = 0, F_CABLE = 1, F_TRAMP = 2, F_SPRING = 3, F_SWING = 4, F_MOVING = 5, F_CRUMBLE = 6, F_SEESAW = 7, F_RAMP = 8,
        F_SAW = 9, F_PENDULUM = 10, F_PROJECTILE = 11, F_SPIKE_MAT = 12, F_SPIKED_BLOCK = 13, F_CRUSHER = 14, F_AVALANCHE = 15, F_CRAB = 16, F_BEE = 17, F_SKULL = 18, F_BLOB = 19, F_ENFORCER = 20;
    /** buildHaz's roll 0-4 (vertical saw, horizontal saw, spiked block, bee, cannon) and buildMid's roll 0-4 (spike mat, floor saw, spiked block, crusher, crabs). */
    static final int[] HAZ_ROLL_FAM = {F_SAW, F_SAW, F_SPIKED_BLOCK, F_BEE, F_PROJECTILE}, MID_ROLL_FAM = {F_SPIKE_MAT, F_SAW, F_SPIKED_BLOCK, F_CRUSHER, F_CRAB};
    /** The memory (VarietyMemory): accepted placements only; an entry's owner is the element count when its module was placed. */
    final VarietyMemory varHist = new VarietyMemory();

    static int famOf(Element e) {
        switch (e.type) {
            case ROPE: return F_ROPE; case CABLE: return F_CABLE; case PAD: return F_TRAMP; case SPRING: return F_SPRING; case SWING: return F_SWING;
            case MOVE_H: case MOVE_V: case MOVE_Z: return F_MOVING; case CRUMBLE: return F_CRUMBLE; case SEESAW: return F_SEESAW; case RAMP: return F_RAMP;
            default: return e.holed() ? F_CRUMBLE : -1;          // plain footing: every module lands on it, it is not a mechanic of its own
        }
    }
    static int hazFam(Element h) {
        switch (h.type) {
            case SAW_H: case SAW_V: return F_SAW; case PENDULUM: return F_PENDULUM; case CANNON: case AIMED: case MORTAR: return F_PROJECTILE;
            case SPIKE_TRAP: return F_SPIKE_MAT; case SPIKE_BLOCK: return F_SPIKED_BLOCK; case SPIKE_DROP: return F_CRUSHER; case AVALANCHE: return F_AVALANCHE;
            case CRAB: return F_CRAB; case BEE: return F_BEE; case SKULL: return F_SKULL; case BLOB: return F_BLOB; case ENFORCER: return F_ENFORCER;
            default: return -1;
        }
    }
    /** The family a module kind places (its traversal mechanic); -1 when it has none of its own or its hazards are chosen inside it. */
    static int kindFam(Kind k) {
        switch (k) {
            case ROPE: return F_ROPE; case CABLE: return F_CABLE; case PAD: return F_TRAMP; case SPRING: return F_SPRING; case SWING: return F_SWING;
            case MOVER_H: case MOVER_V: case MOVER_Z: case ELEV_FALL: case SLIDE_FALL: return F_MOVING; case CRUMBLE: return F_CRUMBLE;
            case SEESAW: case BRIDGE: return F_SEESAW; case RAMP: return F_RAMP; case MORTAR: return F_PROJECTILE; case DROPS: return F_CRUSHER;
            default: return -1;
        }
    }
    /** 0 ordinary traversal, 1 terrain challenge, 2 hazard module, -1 outside the redistribution. */
    static int kindClass(Kind k) {
        switch (k) {
            case HOP: case STAIRS: case ROPE: return 0;
            case CRUMBLE: case MOVER_H: case MOVER_V: case PAD: case CABLE: case SWING: case GRAB: case SPRING: case SEESAW: case BRIDGE: case RAMP: case MOVER_Z: case ELEV_FALL: case SLIDE_FALL: return 1;
            case HAZ: case TRAP: case MORTAR: return 2;
            default: return -1;
        }
    }
    private void varAdd(int fam, float y, int idx) { if (P.adaptiveVariety()) varHist.add(fam, y, idx); }
    /** The previous slice's last VAR_SPAN m (already in this slice's frame), so the memory carries over the slice boundary. */
    private void varSeed(Course prev) {
        varHist.clear();
        if (!P.adaptiveVariety()) return;
        float top = prev.get(prev.routeSize() - 1).y;
        for (int i = 0; i < prev.routeSize(); i++) { Element e = prev.get(i); if (e.y >= top - VarietyMemory.SPAN) varAdd(famOf(e), e.y, -1); }
        for (Element h : prev.hazards) if (h.y >= top - VarietyMemory.SPAN) varAdd(hazFam(h), h.y, -1);
    }
    /** An accepted module (elements from n0, hazards from h0) enters the memory; entries more than VAR_SPAN m below its top leave it. */
    private void varRecord(int n0, int h0) {
        if (!P.adaptiveVariety()) return;
        for (int i = n0; i < c.size(); i++) varAdd(famOf(c.get(i)), c.get(i).y, n0);
        for (int i = h0; i < c.hazards.size(); i++) varAdd(hazFam(c.hazards.get(i)), c.hazards.get(i).y, n0);
        varHist.prune(c.get(c.size() - 1).y);
    }
    float varPenalty(int fam, float y) { return P.adaptiveVariety() ? varHist.penalty(fam, y, P.varietyStrength()) : 1f; }
    /** Penalties applied to module weights, each class keeping its total (so repetition control never lowers the challenge mix). */
    private void varAdapt(float[] w, float y) {
        double[] before = new double[3], after = new double[3];
        for (Kind k : Kind.values()) { int cl = kindClass(k); if (cl >= 0) before[cl] += w[k.ordinal()]; }
        for (Kind k : Kind.values()) { float p = varPenalty(kindFam(k), y); w[k.ordinal()] *= p; int cl = kindClass(k); if (cl >= 0) after[cl] += w[k.ordinal()]; }
        for (Kind k : Kind.values()) { int cl = kindClass(k); if (cl >= 0 && after[cl] > 0) w[k.ordinal()] = (float) (w[k.ordinal()] * before[cl] / after[cl]); }
    }
    /** A module's internal choice among {@code n} options of the given families, weighted by the memory (one random number). */
    private int varRoll(int[] fams, int n, float y) {
        float[] p = new float[n]; float sum = 0f;
        for (int i = 0; i < n; i++) { p[i] = varPenalty(fams[i], y); sum += p[i]; }
        float x = rnd.nextFloat() * sum;
        for (int i = 0; i < n; i++) { x -= p[i]; if (x <= 0f) return i; }
        return n - 1;
    }
    /** The continuation list with its ordinary moves reordered by the memory (least penalised first; ties keep the least-recently-used order). */
    private Kind[] varOrder(Kind[] list, float y) {
        List<Kind> ord = new ArrayList<>(), head = new ArrayList<>();
        for (Kind k : list) (ordinary(k) ? ord : head).add(k);
        ord.sort((a, b) -> Float.compare(varPenalty(kindFam(b), y), varPenalty(kindFam(a), y)));
        boolean ropeLast = !ord.isEmpty() && ord.get(ord.size() - 1) == Kind.ROPE && list[list.length - 1] != Kind.ROPE;
        if (ropeLast) GenTrace.tally("adaptive_rope_fallback_demoted");
        head.addAll(ord);
        return head.toArray(new Kind[0]);
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
            for (int j = Math.max(0, i - 2); j <= Math.min(c.routeSize() - 2, i + 1) && ok; j++) { Autopilot.Result res = Autopilot.plan(provenStart(c, T, j), j, true); ok = res.ok && res.margin() >= Math.min(0.12f, linkNeed(j)); }          // (DC5: every link whose take-off or landing the wider deck changes, from its proven start)
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
        for (int i = encounters.size() - 1; i >= 0; i--) if (encounters.get(i)[1] >= n0) encounters.remove(i);          // (their decks went with the undone modules)
        while (c.size() > n0) c.elements.remove(c.size() - 1);
        while (c.hazards.size() > h0) c.hazards.remove(c.hazards.size() - 1);
        // an avalanche whose hazard went with the undone modules is planned again (it is placed again further on, proven again)
        for (int i = avlPlaced.size() - 1; i >= 0; i--) {
            Object[] p = avlPlaced.get(i);
            if ((Integer) p[0] < c.hazards.size()) continue;
            avlPlaced.remove(i); float[] pl = (float[]) p[1]; avlPending.add(0, pl); AVALANCHES_PLACED.decrementAndGet();
        }
        if (avlCand >= c.size()) avlCand = -1;
        varHist.forget(n0);          // the memory holds only what is still in the course
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
            for (int q = Math.max(0, rIdx - 7); q <= Math.min(rs - 2, rIdx + 5) && ok; q++) ok = routeLinkStillOk(q);
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
        GenTrace tr = GenTrace.GEN.get(); long q0 = GenTrace.now();      // (observe-only sub-phase timing for reports)
        // Owner directive 2026-10-24 (sections 15-16): no dead ends. Now and then the route forks: the route itself is the quick way, and a longer branch drops
        // below it, runs on under it and bounces back up onto a later route platform. Both go forward and meet again. NORMAL forks now and then, one branch
        // at a time; PUNISHER forks more often, with longer branches, and sometimes a second branch off the first.
        for (int[] fe : forkEntries) {          // the splits the route reserved room for: their branches first
            if (fe[0] < lo || fe[0] + fe[1] + 1 > Math.min(rs - 2, hi + 6)) continue;
            if (tryReservedFork(fe[0], fe[1], dr) < 0) tryPadFork(fe[0], dr);
        }
        long q1 = GenTrace.now();
        float chance = P.forkChance;          // of the take-offs a branch can drop from (open air past the edge: few of them)
        int spacing = P.forkSpacing, last = -100;
        for (int a = lo; a < Math.min(rs - 5, hi + 1); a++) {
            if (a - last < spacing) continue;
            Element p = c.get(a);
            if (!p.isPlatform() || p.isMoving() || p.crumbles() || p.w < 2f || p.anchor >= 0 || !forkable(a)) continue;
            if (dr.nextFloat() > chance) continue;
            GenTrace.tally("fork_try"); int[] w0 = GenTrace.GEN.get() != null ? FORKWHY.clone() : null;          // (observe-only counts for reports)
            int built = tryFork(a, a, dr, P.forkReach);
            if (built < 0) { GenTrace.tally("padfork_try"); built = tryPadFork(a, dr); if (built >= 0) GenTrace.tally("padfork_ok"); }          // no room for a branch under the route here: the short fork, a bounce pad down in the gap
            else GenTrace.tally("fork_ok");
            if (w0 != null) GenTrace.tallyDiff(built < 0 ? "fork_refused_why_" : "fork_retried_why_", w0, FORKWHY);
            if (built < 0) continue;
            last = a;
            if (P.nestedForks && c.forks.get(c.forks.size() - 1)[2] > 2) {          // a second branch off a long one, rejoining the route further on
                GenTrace.tally("subfork_eligible");
                if (dr.nextFloat() < 0.35f) {
                    int[] f = c.forks.get(c.forks.size() - 1);
                    int from = f[1] + Math.max(0, f[2] / 2 - 1);
                    GenTrace.tally("subfork_try"); int[] s0 = GenTrace.GEN.get() != null ? FORKWHY.clone() : null;
                    boolean subOk = tryFork(a, from, dr, 10) >= 0;
                    GenTrace.tally(subOk ? "subfork_ok" : "subfork_refused");
                    if (s0 != null) GenTrace.tallyDiff(subOk ? "subfork_retried_why_" : "subfork_refused_why_", s0, FORKWHY);
                }
            }
        }
        long q2 = GenTrace.now();
        addKeyRooms();          // last, so every key-room link is proven against the final geometry
        if (tr != null) { tr.phase("forks_reserved", q1 - q0); tr.phase("forks_open", q2 - q1); tr.phase("key_rooms", GenTrace.now() - q2); }
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
        pendingFork = new int[]{e, n0, es.size(), j};
        for (Element b : es) { c.add(b); if (!decoyOk(b, e, true)) { ok = false; FORKWHY[2 + decoyWhy]++; break; } }
        pendingFork = null;
        if (ok) {
            c.indexDecoys();
            ok = pairOkAir(c.size() - 1, j); if (!ok) FORKWHY[12]++;          // (order changes no result; the bounce is refused most)
            if (ok) { ok = pairOk(e, n0); if (!ok) FORKWHY[10]++; }
            for (int k = n0; ok && k < c.size() - 1; k++) { ok = pairOk(k, k + 1); if (!ok) FORKWHY[11]++; }
            for (int q = Math.max(0, e - 3); ok && q <= Math.min(rs - 2, j + 2); q++) { ok = routeLinkStillOk(q); if (!ok) FORKWHY[9]++; }
        }
        if (!ok) { rollbackTo(n0, h0); c.indexDecoys(); return -1; }
        c.forks.add(new int[]{e, n0, es.size(), j});
        if (!forkCorridorsClear(n0, h0)) return -1;
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
            pendingFork = new int[]{a, n0, 1, t};
            boolean ok = decoyOk(pad, a, true);
            pendingFork = null;
            if (!ok) FORKWHY[2 + decoyWhy]++;
            if (ok) {
                c.indexDecoys();
                ok = pairOkAir(n0, t); if (!ok) FORKWHY[12]++;          // (order changes no result; the bounce is refused most)
                if (ok) { ok = pairOk(a, n0); if (!ok) FORKWHY[10]++; }
                for (int q = Math.max(0, a - 3); ok && q <= Math.min(rs - 2, t + 2); q++) { ok = routeLinkStillOk(q); if (!ok) FORKWHY[9]++; }
            }
            if (!ok) { rollbackTo(n0, h0); c.indexDecoys(); continue; }
            c.forks.add(new int[]{a, n0, 1, t});
            if (!forkCorridorsClear(n0, h0)) continue;
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
    /**
     * A fork's pads checked again once the fork is registered: {@link #decoyOk} judged each pad as it was added, before {@code c.forks} knew the
     * branch, so {@link #padLanding} found no landing and only the launch column was checked, never the flight forward to the rejoin deck. The
     * slice audit, which runs with the fork registered, then refused the whole attempt (NORMAL seed 2 slices 13, 18 and 24 on 4478d7d: a branch
     * pad's flight to its rejoin under a spike block or spike trap one revolution up). Refused here, the fork is taken back and the generator tries
     * the next candidate. Returns false after rolling the fork back.
     */
    private boolean forkCorridorsClear(int n0, int h0) {
        boolean ok = true;
        for (int i = n0; i < c.size() && ok; i++) if (!padCorridorClear(i)) { ok = false; GenTrace.tally("pad_corridor_refused"); FORKWHY[13]++; }
        // DC5: an earlier fork's branch near the new one is proven again with it there (its bounce and hops were proven before it existed)
        for (int f = 0; ok && f < c.forks.size() - 1; f++) {
            int[] fk = c.forks.get(f); boolean near = false;
            for (int i = fk[1]; i < fk[1] + fk[2] && !near; i++) for (int q = n0; q < c.size() && !near; q++) near = Math.abs(c.dsWrap(c.get(i).s, c.get(q).s)) < 15f && Math.abs(c.get(i).y - c.get(q).y) < 12f;
            if (!near) continue;
            ok = pairOk(fk[0], fk[1]);
            for (int k = fk[1]; ok && k < fk[1] + fk[2] - 1; k++) ok = pairOk(k, k + 1);
            if (ok) ok = pairOkAir(fk[1] + fk[2] - 1, fk[3]);
            if (!ok) { GenTrace.tally("fork_earlier_branch_refused"); FORKWHY[13]++; }
        }
        if (ok) return true;
        c.forks.remove(c.forks.size() - 1); rollbackTo(n0, h0); c.indexDecoys();
        return false;
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
            pendingFork = new int[]{from, n0, es.size(), t};
            for (Element e : es) { c.add(e); if (!decoyOk(e, a, true)) { ok = false; FORKWHY[2 + decoyWhy]++; break; } }
            pendingFork = null;
            if (ok) {
                c.indexDecoys();
                GenTrace tr = GenTrace.GEN.get(); long q0 = GenTrace.now();      // (observe-only timing for reports)
                // the proofs only read the course, so their order changes no result: the bounce up onto the rejoin platform is refused most, so it goes first
                ok = pairOkAir(c.size() - 1, t); if (!ok) FORKWHY[12]++;
                if (ok) { ok = pairOk(from, n0); if (!ok) { FORKWHY[10]++; } }
                for (int k = n0; ok && k < c.size() - 1; k++) { ok = pairOk(k, k + 1); if (!ok) { FORKWHY[11]++; } }
                long q1 = GenTrace.now();
                for (int q = Math.max(0, a - 3); ok && q <= Math.min(rs - 2, t + 2); q++) { ok = routeLinkStillOk(q); if (!ok) { FORKWHY[9]++; } }
                if (tr != null) { tr.phase("fork_branch_proofs", q1 - q0); tr.phase("fork_route_reproofs", GenTrace.now() - q1); }
            }
            if (!ok) { rollbackTo(n0, h0); c.indexDecoys(); continue; }
            c.forks.add(new int[]{from, n0, es.size(), t});
            if (!forkCorridorsClear(n0, h0)) continue;
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
        for (Element g : ghostElems) if (ghostClash(e, g)) return true;
        return false;
    }

    public int decoyWhy;      // diagnostics: why decoyOk last said no (1 overlap, 2 other layer, 3 ghost layer, 4 stepping stone)

    private boolean decoyOk(Element d, int a) { return decoyOk(d, a, false); }
    /** {@code fork}: a fork's branch, which rejoins the route anyway, so the stepping-stone rule (no shortcut toward the route) does not apply to it. */
    private boolean decoyOk(Element d, int a, boolean fork) {
        int di = c.size() - 1;
        if (di >= 0 && c.get(di) == d && !padCorridorClear(di)) { decoyWhy = 0; GenTrace.tally("pad_corridor_refused"); return false; }
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
        for (Element g : ghostElems) if (ghostClash(d, g)) { decoyWhy = 3; return false; }
        if (!GeometryIntegrity.elementClear(c, d, c.hazards, ghostHaz)) { decoyWhy = 2; return false; }          // (DC5: never inside a placed hazard's box of another revolution)
        if (contReserve != null) for (Element p : contReserve) if (layersClash(c, p, d)) { decoyWhy = 6; return false; }      // the next slice's first step stays clear (closeSlice)
        // never in the flight corridor of any route pad or spring, in this revolution or another: a launch rises up to ~8.5 m and drifts up to
        // ~9 m with the run-up it was reached at, so a branch, dead end or key-room platform there catches the climber (OfficialRunTest seed 7
        // slice 44: a fork branch one revolution up, 7 m over a spring and 8.4 m ahead, outside the 4.5 m layer window; the spring's link was proven
        // before the branch existed)
        // (the top of the corridor: the held launch's apex plus the standing room a body needs under a deck, 2.8 m as in the layer rules; 9 m let a fork
        // branch 10.2 m over a pad take the climber's head on the way up: NORMAL seed 2 slice 77, castle 10, beyond the planner's element window so the
        // proof never saw it)
        float top = T.padBounceHeld * T.padBounceHeld / (2f * T.gravity) + 2.8f;
        for (int j = 0; j < c.size(); j++) {          // (DC5: every pad and spring, the branches' too, not only the route's)
            Element e = c.get(j);
            if (e == d || (pendingFork != null && j >= pendingFork[1])) continue;          // (the structure being built is proven by its own links)
            if ((e.type == Element.Type.PAD || e.type == Element.Type.SPRING) && Math.abs(c.dsWrap(d.s, e.s)) < 10f + d.w / 2f && d.y > e.y - 1.5f && d.y < e.y + top) { decoyWhy = 5; return false; }
        }
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
            for (Kind k : new Kind[]{Kind.PAD, Kind.MOVER_V, Kind.STAIRS, Kind.ROPE}) if (tryModule(k, 0.1f, zone)) { climbed = true; break; }
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
    static float arcHalf(Element e) {
        switch (e.type) {
            case MOVE_H: return e.w / 2f + e.amp;
            case SWING: return e.w / 2f + e.len * (float) StrictMath.sin(e.amp);
            case ROPE: return 0.8f;
            default: return e.w / 2f;
        }
    }
    static float vLo(Element e) {
        switch (e.type) { case ROPE: return e.y - e.len; case CABLE: return e.y - 2.4f; case SEESAW: return e.y - 2.8f; case RAMP: return e.y - e.amp * e.halfW() - 3.4f; default: return e.y - 0.8f; }
    }
    static float vHi(Element e) {
        switch (e.type) {
            case ROPE: return e.y + 0.6f; case CABLE: return e.y + 0.4f; case SWING: return e.y + e.len + 0.4f;
            case MOVE_V: return e.y + e.amp + 2.8f; case RAMP: return e.y + e.amp * e.halfW() + 2.8f; default: return e.y + 2.8f;
        }
    }
    /**
     * True if two elements from different spiral revolutions would intersect or allow a shortcut between layers. Owner decision 2026-10-25 (rules 8):
     * the 9.5 m shortcut spacing is kept wherever either platform can launch the climber well above a jump (pad, spring, seesaw, ramp, swing, anything
     * that moves); two plain fixed platforms need only {@link #PLAIN_LAYER_DY}, as fork branches already did. Physics (tuning.json): a jump lifts the feet
     * jumpVel^2 / 2g = 1.95 m and a ledge is caught up to handHeight + ledgeReachAbove = 1.3 m above them, 3.25 m in all (3.6 m with a skull's
     * launch at the start of a jump), so 4 m leaves a margin. Where their arcs come within 2.5 m the body clearance above (2.8 m standing room + 0.8 m deck
     * + 1.5 m) keeps them 5.1 m apart anyway. Validated with the planner itself (docs/probes/ShortcutProbe): no plain pair of revolutions reachable.
     */
    public static boolean layersClash(Course c, Element e, Element f) {
        if (Math.abs(e.s - f.s) < c.circumference * 0.5f) return false;     // same revolution: handled by route order
        if (Math.abs(c.dsWrap(e.s, f.s)) > arcHalf(e) + arcHalf(f) + 2.5f) return false;
        if (vLo(e) - 1.5f < vHi(f) && vHi(e) + 1.5f > vLo(f)) return true;
        if (!e.isPlatform() || !f.isPlatform()) return false;
        return Math.abs(e.y - f.y) < (launches(e) || launches(f) ? 9.5f : PLAIN_LAYER_DY);   // no trampoline shortcuts between layers
    }
    /**
     * The layer rule against the previous slice ({@link #ghostElems}). The relaxed attempts of a slice (see chunkIn) waive only its shortcut spacing
     * (4 m between plain platforms, 9.5 m where one launches): bodies of different revolutions stay clear of each other, as everywhere.
     */
    private boolean ghostClash(Element e, Element g) { return relaxed ? layersBodiesClash(c, e, g) : layersClash(c, e, g); }
    /** The physical part of {@link #layersClash}: the bodies (with standing room) of two different revolutions overlap. */
    static boolean layersBodiesClash(Course c, Element e, Element f) {
        if (Math.abs(e.s - f.s) < c.circumference * 0.5f) return false;
        if (Math.abs(c.dsWrap(e.s, f.s)) > arcHalf(e) + arcHalf(f) + 2.5f) return false;
        return vLo(e) - 1.5f < vHi(f) && vHi(e) + 1.5f > vLo(f);
    }
    /** Shortcut spacing between plain fixed platforms of different revolutions (see {@link #layersClash}). */
    static final float PLAIN_LAYER_DY = 4f;
    /** A platform that can throw the climber well above a jump, or carry them: these keep the full 9.5 m between revolutions. */
    static boolean launches(Element e) {
        switch (e.type) { case PAD: case SPRING: case SEESAW: case RAMP: case SWING: return true; default: return e.isMoving(); }
    }

    /**
     * {@link #layersClash} for a fork's branch, which runs below the route and so nearer the revolution beneath: the bodies must keep clear as usual, and
     * no layer may reach the other: platforms out of jump reach (4 m; a jump rises about 1.9 m) unless the lower one launches or carries the climber
     * ({@link #launches}: a pad, spring, seesaw, ramp, swing or moving platform), which keeps the 9.5 m rule. (Until rules 9 only a lower pad or spring
     * counted: a rising platform 4-9.5 m under a branch could have carried the climber onto it.) A launcher ABOVE a plain platform is no shortcut: it
     * throws the climber further up its own revolution, and the plain platform below is out of reach. Public for the tests that check generated courses.
     */
    public static boolean forkLayersClash(Course c, Element d, Element f) {
        if (Math.abs(d.s - f.s) < c.circumference * 0.5f) return false;
        if (Math.abs(c.dsWrap(d.s, f.s)) > arcHalf(d) + arcHalf(f) + 2.5f) return false;
        if (vLo(d) - 1.5f < vHi(f) && vHi(d) + 1.5f > vLo(f)) return true;
        if (!d.isPlatform() || !f.isPlatform()) return false;
        Element lower = d.y < f.y ? d : f;
        return Math.abs(d.y - f.y) < (launches(lower) ? 9.5f : 4f);
    }
    private boolean forkLayersClash(Element d, Element f) { return forkLayersClash(c, d, f); }
    /** Whether element {@code i} of {@code c} is on a fork's branch (see {@link Course#forks}: take-off, first branch element, count, rejoin). */
    public static boolean onForkBranch(Course c, int i) { for (int[] f : c.forks) if (i >= f[1] && i < f[1] + f[2]) return true; return false; }

    private boolean clearOfOtherLayers(int idx) {
        Element e = c.get(idx);
        for (int i = 0; i < idx - 3; i++) if (layersClash(c, e, c.get(i))) { lastClash = c.get(i); lastClashGhost = false; return false; }
        for (Element g : ghostElems) if (ghostClash(e, g)) { lastClash = g; lastClashGhost = true; return false; }
        return true;
    }
    private Element lastClash; private boolean lastClashGhost;      // diagnostics: what the last refused element ran into
    /** Diagnostics: what crab decks refused for a layer clash ran into {route body, route 9.5 m rule, branch body, branch 9.5 m rule} x {this slice, earlier slice}. */
    public static final int[] CRABCLASH = new int[8];

    // ---- hazard geometry: lethal-region bounding box (centre arc, half extent, low, high)
    static float hzS(Element h) { return h.type == Element.Type.CANNON ? h.s + h.dir * h.len * 0.5f : h.s; }
    static float hzHalf(Element h) {
        switch (h.type) {
            case SAW_H: return h.amp + Element.SAW_R;
            case CRAB: return h.amp + 0.6f;
            case BEE: return h.amp + 0.6f;
            case SAW_V: return Element.SAW_R;
            case PENDULUM: return h.len * (float) StrictMath.sin(h.amp) + Element.BALL_R;
            case CANNON: return h.len * 0.5f + Element.SHOT_R;
            case MORTAR: return Element.MORTAR_R;
            case AIMED: return 0.6f;
            case ENFORCER: return Element.ENF_HALF + 0.2f;          // its footprint where it waits (it chases along its own revolution's decks)
            default: return h.w * 0.5f;
        }
    }
    static float hzLo(Element h) {
        switch (h.type) { case SAW_H: case SAW_V: return h.y - Element.SAW_R; case PENDULUM: return h.y - Element.BALL_R; case CANNON: return h.y - Element.SHOT_R; case AIMED: return h.y - 0.4f; case BEE: return h.y - h.len - 1.4f; default: return h.y; }
    }
    static float hzHi(Element h) {
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
            case ENFORCER: return h.y + Element.ENF_H;
            case SKULL: case BLOB: return h.y + 2.3f;      // the skull's hover; the blob's leap goes off the top of the screen and is not standing-room clutter
            case BEE: return h.y + h.len + 0.6f;
            default: return h.y + h.len;
        }
    }

    /** A hazard must not touch standing room on its own spiral layer (except the platform a trap sits in) and must keep clear of other layers. */
    static boolean launchPad(Element e) { return e.type == Element.Type.PAD || e.type == Element.Type.SPRING; }
    /** The deck a pad or spring of this slice launches to (its next route element), or null for one off the route (a fork's or dead end's pad: the next element of its structure). */
    private Element padLanding(int i) {
        int[] pf = pendingFork;          // (a fork being built: its landings are known before it is registered)
        if (pf != null && i >= pf[1] && i < pf[1] + pf[2]) { int nx = i + 1 < pf[1] + pf[2] ? i + 1 : pf[3]; return nx < c.size() ? c.get(nx) : null; }
        return landingOf(c, i);
    }
    /**
     * DC5 (owner correction 2026-10-27): the one rule for where a pad or spring at element {@code i} of {@code c} lands, used by every check (placement,
     * slice audit, previous-slice ghosts): a route pad on the next route element; a fork branch's pad on the next branch element, or on the rejoin
     * after the last; any other side structure's (a key room's) on its next element of the same structure, else back on the route deck it hangs from.
     */
    static Element landingOf(Course c, int i) {
        int rs = c.routeSize();
        if (i < rs) { int nx = c.nextRoute(i); return nx > i && nx < c.size() ? c.get(nx) : null; }
        for (int[] fk : c.forks) if (i >= fk[1] && i < fk[1] + fk[2]) { int nx = i + 1 < fk[1] + fk[2] ? i + 1 : fk[3]; return nx < c.size() ? c.get(nx) : null; }
        Element e = c.get(i);
        if (i + 1 < c.size() && c.get(i + 1).anchor == e.anchor && e.anchor >= 0) return c.get(i + 1);
        return e.anchor >= 0 && e.anchor < c.size() ? c.get(e.anchor) : null;
    }
    /**
     * The launch corridor of pad or spring {@code e} (landing on {@code land}, or null) holds hazard {@code h}. 2026-10-26 (campaign 40270745200, NORMAL
     * seed 1 at 79.5 m): a launch climbs ~8.5 m (padBounceHeld^2 / 2g), up into the layer above, so no hazard or creature goes in the column above it.
     * Rules 14 (Campaign A, NORMAL seed 3 slice 53 on cbe6415): the launch drifts forward to the deck it lands on, so the corridor extends over the flight
     * to that deck; a spiked block one revolution up, 3.65 m ahead of a pad and 9 m above it, caught the launch (the column reached only 2.7 m ahead).
     * Keys, gates and clubs are not obstacles. Shared by the placement check and the whole-slice audit, so the order things are placed in does not matter.
     */
    /** Farthest a pad or spring carries the climber along the lane to its landing deck (m); a "landing" farther than this is no landing of that launch. */
    static final float PAD_FLIGHT_MAX = 12f;
    static boolean corridorBlocked(Course c, Tuning t, Element e, Element land, Element h) {
        if (h.type == Element.Type.KEY || h.type == Element.Type.GATE || h.type == Element.Type.CLUB) return false;
        float apex = t.padBounceHeld * t.padBounceHeld / (2f * t.gravity), hs = hzS(h), hh = hzHalf(h), lo = hzLo(h), hi = hzHi(h);
        float side = e.w / 2f + hh + (e.type == Element.Type.SPRING ? 2.5f : 1.2f), ds = c.dsWrap(hs, e.s);
        if (Math.abs(ds) < side && lo < e.y + apex + 1.8f && hi > e.y + 0.3f) return true;
        if (land == null) return false;
        float to = c.dsWrap(land.s, e.s), reach = Math.abs(to) + land.halfW() + hh + 0.5f;
        if (Math.abs(to) > PAD_FLIGHT_MAX) return false;          // not this launch's landing (a list neighbour of another structure): the column alone applies
        return ds * to > 0f && Math.abs(ds) < reach && lo < Math.max(e.y, land.y) + apex + 1.8f && hi > Math.min(e.y, land.y) + 0.3f;
    }
    /**
     * Rules 14 (owner clarification 2026-10-27: corridor protection wherever the traversal needs it, in every mode): the placement check only sees pads
     * that exist when a hazard is placed, but forks' and dead ends' pads are built after the route's hazards above them, and the previous slice's pads
     * were never consulted. Every pad and spring of this slice and of the previous one is audited against every hazard of both before the slice is
     * accepted; one blocked corridor fails the attempt (the slice is built again), like the geometry integrity check.
     */
    private void checkLaunchCorridors() {
        // only pairs with something of this slice in them: a pad and a hazard that both came from the previous slice (its context copy or its
        // ghosts) were judged when that slice was accepted, and nothing this slice builds can change them (NORMAL seed 2 slice 18 on f4df3f3
        // failed all 24 attempts on such a pair: a ghost pad and a spike drop copied with the context)
        for (int i = 0; i < c.size(); i++) {
            Element e = c.get(i);
            if (!launchPad(e)) continue;
            boolean newPad = i >= ctx;
            Element land = padLanding(i);
            for (int k = 0; k < c.hazards.size(); k++) { Element h = c.hazards.get(k); if ((newPad || k >= ctxHaz) && h.anchor != i && otherRevolution(c, e, h) && corridorBlocked(c, T, e, land, h)) corridorFail(e, h, "pad " + i + (i < ctx ? " ctx" : "") + (i < c.routeSize() ? " route" : " side") + " land " + (land == null ? "-" : land.type) + " / haz " + k + (k < ctxHaz ? " ctx" : "") + " anchor " + h.anchor + " hazardOkNow=" + hazardOk(h, h.anchor)); }
            if (newPad) for (Element h : ghostHaz) if (otherRevolution(c, e, h) && corridorBlocked(c, T, e, land, h)) corridorFail(e, h, "pad " + i + " / ghost haz");
        }
        for (int i = 0; i < ghostElems.size(); i++) {
            Element e = ghostElems.get(i);
            if (!launchPad(e)) continue;
            Element land = ghostLand.get(i);
            for (int k = ctxHaz; k < c.hazards.size(); k++) { Element h = c.hazards.get(k); if (otherRevolution(c, e, h) && corridorBlocked(c, T, e, land, h)) corridorFail(e, h, "ghost pad " + i + " / haz " + k + " anchor " + h.anchor); }
        }
    }
    /**
     * The audit's scope: hazards of another spiral revolution (the layer rule's convention, a raw arc difference of half a lap or more). Those are the
     * ones a launch meets without any proof seeing them - the planner sizes a move up among nearby elements, so the hazards on the landing deck itself
     * (a crab, a spike drop, the next challenge) are already in the move's proof and stay legitimate (a first audit over every hazard refused NGH seed 2
     * slice 1 in all 24 attempts on exactly those).
     */
    private static boolean otherRevolution(Course c, Element pad, Element h) { return Math.abs(hzS(h) - pad.s) >= c.circumference * 0.5f; }
    /**
     * The placement-time half of {@link #checkLaunchCorridors}: a pad or spring being placed (a route module's, or a fork's, dead end's or key room's)
     * keeps its corridor clear of the hazards already there, this slice's and the previous slice's, of another revolution. Refusing it here lets the
     * generator place something else at that point; the slice audit alone could only throw the whole attempt away, and when the clash came from
     * structure every attempt rebuilt the same way it failed all of them (Campaign A on 78292a7: NORMAL seed 2 slice 37, PUNISHER seed 2 slice 34).
     */
    private boolean padCorridorClear(int i) {
        Element e = c.get(i);
        if (!launchPad(e)) return true;
        Element land = padLanding(i);
        for (Element h : c.hazards) if (h.anchor != i && otherRevolution(c, e, h) && corridorBlocked(c, T, e, land, h)) return false;
        for (Element h : ghostHaz) if (otherRevolution(c, e, h) && corridorBlocked(c, T, e, land, h)) return false;
        return true;
    }
    private static void corridorFail(Element e, Element h, String where) {
        CORRIDOR_REFUSED.incrementAndGet(); GenTrace.tally("corridor_refused");
        throw new IllegalStateException(String.format(java.util.Locale.ROOT, "launch corridor of the %s at s=%.2f y=%.2f holds a %s at s=%.2f y=%.2f [%s]", e.type, e.s, e.y, h.type, h.s, h.y, where));
    }
    /** Diagnostics: slice attempts refused by the launch-corridor audit. */
    public static final java.util.concurrent.atomic.AtomicInteger CORRIDOR_REFUSED = new java.util.concurrent.atomic.AtomicInteger();
    /**
     * DC5 (owner correction 2026-10-27): the checks every hazard gets at placement, for the hazards placed outside a module (an Enforcer, an avalanche):
     * no launch corridor of another revolution holds it (this slice's pads and the previous slice's), and it keeps clear of every other spiral layer.
     * Its own decks are its encounter's, proven by playing it (chainClean), so the same-layer standing-room rule does not apply. Refused here, the
     * generator tries the next geometry or deck instead of losing the whole slice at the audit.
     */
    private boolean insertionClear(Element h) {
        for (int i = 0; i < c.size(); i++) { Element e = c.get(i); if (launchPad(e) && otherRevolution(c, e, h) && corridorBlocked(c, T, e, padLanding(i), h)) return false; }
        for (int i = 0; i < ghostElems.size(); i++) { Element e = ghostElems.get(i); if (launchPad(e) && otherRevolution(c, e, h) && corridorBlocked(c, T, e, ghostLand.get(i), h)) return false; }
        if (!GeometryIntegrity.hazardClear(c, h, ghostElems, ghostHaz)) return false;          // the slice audit's own I4 rule, so placement and audit agree
        float hs = hzS(h), hh = hzHalf(h), lo = hzLo(h), hi = hzHi(h);
        for (int pass = 0; pass < 2; pass++) {
            int n = pass == 0 ? c.size() : ghostElems.size();
            for (int i = 0; i < n; i++) {
                Element e = pass == 0 ? c.get(i) : ghostElems.get(i);
                if (Math.abs(hs - e.s) >= c.circumference * 0.5f && Math.abs(c.dsWrap(hs, e.s)) < hh + arcHalf(e) + 2.0f && lo - 1.5f < vHi(e) && hi + 1.5f > vLo(e)) return false;
            }
        }
        return true;
    }
    private boolean hazardOk(Element h, int ownerIdx) {
        float hs = hzS(h), hh = hzHalf(h), lo = hzLo(h), hi = hzHi(h);
        if (h.type != Element.Type.KEY && h.type != Element.Type.GATE && h.type != Element.Type.CLUB) {
            // 2026-10-26 (campaign 40270745200, NORMAL seed 1 at 79.5 m): a pad or spring launch climbs ~8.5 m (padBounceHeld^2 / 2g), up into the layer above,
            // and a later module put a spike block right in that column: the pad link had been proven before the block existed and is never re-proven.
            // No hazard or creature goes in the launch column above a pad or spring.
            for (int i = 0; i < c.size(); i++) {
                Element e = c.get(i);
                if (i == ownerIdx || !launchPad(e)) continue;
                if (corridorBlocked(c, T, e, padLanding(i), h)) return false;
            }
            for (int i = 0; i < ghostElems.size(); i++) {          // (rules 14: the previous slice's pads and springs as well)
                Element e = ghostElems.get(i);
                if (launchPad(e) && otherRevolution(c, e, h) && corridorBlocked(c, T, e, ghostLand.get(i), h)) return false;
            }
        }
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
    /**
     * DC5 (owner correction 2026-10-27): the first route link hazard {@code h} can touch, from what it really reaches - an aimed cannon wakes and fires
     * within AIM_RANGE in any direction, a bee seeks within BEE_SEEK and drifts BEE_REACH, a cannon's balls fly its lane and 14 m past it - as well as
     * the decks a creature roams onto. Links before it were proven before the hazard existed and are proven again with it (they used to start at the
     * anchor or one or two decks before it).
     */
    private int reachFrom(Element h) {
        int a = h.anchor; if (a < 0) return Integer.MAX_VALUE;
        int from = a - 1 - Math.max(0, roamReach(h));
        float R = h.type == Element.Type.AIMED ? Element.AIM_RANGE + 1f : h.type == Element.Type.BEE ? Element.BEE_SEEK + Element.BEE_REACH : 0f;
        int top = Math.min(a, c.routeSize()) - 1;
        if (R > 0f) for (int k = top; k >= 0; k--) { Element d = c.get(k); if (Math.abs(c.dsWrap(d.s, hzS(h))) < R + d.halfW() && Math.abs(d.y - h.y) < R) from = Math.min(from, k - 1); }
        if (h.type == Element.Type.CANNON) for (int k = top; k >= 0; k--) {
            Element d = c.get(k); float along = c.dsWrap(d.s, h.s) * (h.dir >= 0 ? 1f : -1f);
            if (along > -d.halfW() - 1f && along < h.len + 14f + d.halfW() && Math.abs(d.y - h.y) < 3f) from = Math.min(from, k - 1);
        }
        return Math.max(0, from);
    }
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

    /** Platforms that carry a dedicated hazard structure (spike drop, mortar, cannon, aimed cannon): protected by the owner's structural rules. */
    private boolean protectedDeck(int p) {
        for (Element h : c.hazards) if (h.anchor == p && (h.type == Element.Type.SPIKE_DROP || h.type == Element.Type.MORTAR || h.type == Element.Type.CANNON || h.type == Element.Type.AIMED)) return true;
        return false;
    }
    /**
     * Owner structural rules (2026-10-26): no enemy sits on a protected hazard platform, and none can pursue onto one (EncounterModel.reaches, the
     * Sim's own reach rules) below this difficulty's {@link DifficultyProfile#pursuitFromY} - never in NORMAL, the top third in PUNISHER, the top two-thirds in
     * NGH. Checked for every protected platform within reach of the newly added elements (enemies and protected decks can arrive in either order).
     */
    private boolean protectedRowsOk(int n0) {
        int from = Math.max(0, n0 - 5);
        for (int p = from; p < c.size(); p++) {
            if (!protectedDeck(p)) continue;
            boolean pursuitAllowed = base + c.get(p).y >= P.pursuitFromY;
            for (Element h : c.hazards) {
                if (h.anchor < 0 || Math.abs(h.anchor - p) > 4) continue;
                if (h.type != Element.Type.CRAB && h.type != Element.Type.BEE && h.type != Element.Type.SKULL && h.type != Element.Type.BLOB) continue;
                if (h.anchor == p) return false;
                if (!pursuitAllowed && EncounterModel.reaches(c, T, h, p)) return false;
            }
        }
        return true;
    }

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
        // Geometry first (2026-10-26, owner directive: PUNISHER seed 2 slice 51 spent most of its 62 s proving the links before modules that the
        // spiral-layer rule then refused). The layer, hazard-placement, saw/creature and protected-row checks read only the geometry, with the module in
        // place exactly as below, and none of them reads or changes what the proofs of the earlier links compute (no random numbers, no course state). So
        // they run before those proofs: a module they refuse is refused exactly as before, with the same counters, without the planner runs. The same
        // checks run again below with the module added (unchanged code path); they pass again there, as nothing between changes the course.
        for (Element e : es) c.add(e);
        for (Element h : hz) c.hazards.add(h);
        if (!geometryOk(n0, hz)) {
            rollbackTo(n0, h0);
            hazardTried += hz.isEmpty() ? 0 : 1;
            crabWhy(hz, false, w0);
            PRECHECK_REFUSED.incrementAndGet();
            return false;
        }
        rollbackTo(n0, h0);
        int from = n0 - 1;
        // Every link a new hazard can touch is proven with it there: a hazard on the platform the previous link lands on touches that link too (a cannon
        // fires across it; a saw on it sweeps the landing: seed 3 slice 16, a saw from the next module cut a saw link to 0.36), and a creature also the
        // links into the decks it roams onto (framed seed 5 slice 17: a skull from the next module jumped the pad link before its deck).
        for (Element h : hz) if (h.anchor >= 0) from = Math.min(from, reachFrom(h));          // (DC5: from the hazard's real reach, not only its roaming decks)
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
            Sim b = provenStart(c, T, i);          // (DC2: the start the link was proven from, the belt's landing included, so belt links are judged too)
            Autopilot.Result r0 = Autopilot.plan(b, i, true);
            heldBefore[i - from] = r0.ok && r0.margin() >= linkNeed(i);
        }
        for (Element e : es) c.add(e);
        for (Element h : hz) c.hazards.add(h);
        boolean ok = geometryOk(n0, hz);
        Sim prevStart = null; Autopilot.Result prevRes = null;
        for (int i = from; i < c.size() - 1 && ok; i++) {
            if (i < n0 - 1 && !heldBefore[i - from]) { prevStart = null; prevRes = null; continue; }
            Sim sim = Sim.startOn(c, T, i); quietStart(sim, i);
            // DC2: the link off a balance conveyor is proven from the landing on it whenever there is no earlier link of this proof to chain from - the
            // same start heldBefore measured it from (NORMAL seed 2024 slice 48: the baseline held from the landing, this loop proved it from a standing
            // start that the belt carries off, and every module after the conveyor was refused)
            if (c.get(i).tiltBelt() && (prevRes == null || prevRes.factory == null) && i > 0) sim = provenStart(c, T, i);
            else if (c.get(i).tiltBelt() && prevRes != null && prevRes.factory != null) {
                // Build 79 (owner): the balance conveyor always runs, so nobody stands still on it: a climber crosses it straight from the landing. Its link is
                // proven from where and how the proven jump onto it lands (a standing start at its back end is carried off it before anyone could move).
                Sim land = Autopilot.trialEnd(prevStart, i - 1, prevRes.factory, 10.5f);
                if (land != null && land.onElem == i) sim = land;
            }
            Autopilot.Result res = Autopilot.plan(sim, i, true);
            if (beltSawNow && endless && P.progression() && c.get(i).tiltBelt() && res.ok) {
                // rules 13 (NORMAL seed 2, 4,415 m): the belt's exit must also hold from a landing a little either side of the proven one - a climber
                // who landed 2 cm off it a moment later was run off the back (the belt keeps moving, so the exact landing is never repeated)
                for (float dx : new float[]{-0.3f, 0.3f}) { Sim p = sim.copy(); p.s = c.wrap(p.s + dx); if (!Autopilot.plan(p, i, false).ok) { res = new Autopilot.Result(); GenTrace.tally("belt_exit_fragile"); break; } }
            }
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
            Element land = c.get(i + 1);
            if (ok && i >= n0 - 2 && (land.type == Element.Type.PAD || land.type == Element.Type.SPRING) && c.nextRoute(i + 1) < c.size()) {
                // a pad or spring launches the climber as they arrive, with the run-up they arrive with: its own link was proven from a standstill on it,
                // which nobody coming off the link before it is in. So the link onto it must have a move that leaves a launch the next link can be made
                // from - the solver's own lookahead (2026-10-26: PUNISHER seed 2 581 m and other "did not reach the next link" failures after pads/springs)
                Sim b = Sim.startOn(c, T, i); quietStart(b, i);
                if (!Autopilot.plan(b, i, false, true).ok) { ok = false; failPlan++; if (trace.length() < 600) trace.append(String.format("[link %d onto %s: no arrival leaves a launch that makes the next link]", i, land.type)); }
            }
        }
        // Owner directive 2026-10-24 (sections 10-14): no link may ask for incompatible things at once: several creatures chasing the climber where the
        // jump also has to wait for a cycle from a cramped take-off. Such a module is not dropped from the game; this arrangement of it is refused and the
        // generator tries another (different spacing, placement or module), so the mechanics stay and meet elsewhere.
        for (int i = Math.max(0, from); ok && i < c.size() - 1; i++) if (EncounterModel.link(c, T, i).conflict) { ok = false; failConflict++; }
        // DC5: an encounter proven by playing it through (Enforcer, avalanche, spike drops with enemies) is played again when this module's hazards
        // reach into it: it was proven before they existed
        if (ok && !hz.isEmpty()) for (int[] en : encounters) {
            if (en[1] < from || en[1] >= n0) continue;
            boolean clean = Autopilot.chainClean(c, T, en[0], en[1]);
            if (clean && en[2] == 1) for (float w : AVL_NGH_WAITS) if (!Autopilot.chainClean(c, T, en[0], en[1], w)) { clean = false; break; }
            if (!clean) { ok = false; failPlan++; GenTrace.tally("encounter_reproof_refused"); break; }
        }
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
        crabWhy(hz, ok, w0);
        return ok;
    }

    /** The hazards already placed before this module's own (its hazards are the last {@code hz.size()} of the course's). */
    private int h0Of(List<Element> hz) { return c.hazards.size() - hz.size(); }
    /** The module's geometry, with it added from element {@code n0}: clear of other spiral layers, hazards placed clear, saws out of creatures' reach, protected rows kept (counts its refusal). */
    private boolean geometryOk(int n0, List<Element> hz) {
        boolean ok = true;
        int clashAt = -1;
        for (int i = n0; i < c.size() && ok; i++) { ok = clearOfOtherLayers(i); if (!ok) clashAt = i; }
        if (!ok) failLayer++;
        if (!ok && GenTrace.GEN.get() != null) { Element e = c.get(clashAt), f = lastClash;          // (observe-only: what a layer refusal ran into, for reports)
            GenTrace.tally("clash_" + (lastClashGhost ? "ghost_" : "slice_") + (layersBodiesClash(c, e, f) ? "body_" : "spacing_") + f.type + (f.anchor >= 0 ? "_side" : "")); }
        if (!ok) { boolean cr = false; for (Element h : hz) if (h.type == Element.Type.CRAB) cr = true;
            if (cr) { Element e = c.get(clashAt), f = lastClash; boolean body = vLo(e) - 1.5f < vHi(f) && vHi(e) + 1.5f > vLo(f);
                CRABCLASH[(f.anchor >= 0 ? 2 : 0) + (body ? 0 : 1) + (lastClashGhost ? 4 : 0)]++; } }
        // DC5: a new deck never lies in the lethal box of a hazard of another revolution placed before it (an avalanche's fall, a cannon's lane):
        // the slice audit's I4 rule, applied here so the generator places something else instead of losing the attempt
        for (int i = n0; i < c.size() && ok; i++) if (!GeometryIntegrity.elementClear(c, c.get(i), c.hazards.subList(0, Math.min(c.hazards.size(), h0Of(hz))), ghostHaz)) { ok = false; failHaz++; GenTrace.tally("deck_in_hazard_refused"); }
        for (Element h : hz) { if (!ok) break; if (h.type == Element.Type.CLUB) continue; ok = hazardOk(h, ownerOf(h)) && GeometryIntegrity.hazardClear(c, h, ghostElems, ghostHaz); if (!ok) failHaz++; }          // (DC5: and the slice audit's own I4 rule, hazards of two revolutions included)
        for (int i = n0; i < c.size() && ok; i++) if (!padCorridorClear(i)) { ok = false; failHaz++; GenTrace.tally("pad_corridor_refused"); }
        if (ok && !sawsClearOfCreatures(hz)) { ok = false; failHaz++; }
        if (ok && !protectedRowsOk(n0)) { ok = false; failHaz++; }
        return ok;
    }

    private void crabWhy(List<Element> hz, boolean ok, int[] w0) {
        boolean crabs = false; for (Element h : hz) if (h.type == Element.Type.CRAB) crabs = true;
        if (crabs) {          // diagnostics only: why modules carrying crabs are refused
            CRABWHY[0]++;
            CRABWHY[ok ? 7 : failLayer != w0[0] ? 1 : failHaz != w0[1] ? 2 : failPlan != w0[2] ? 3 : failWindow != w0[3] ? 4 : failConflict != w0[4] ? 5 : failThreat != w0[5] ? 6 : 0]++;
        }
    }
    /** Diagnostics: modules refused by their geometry before any link was proven (see commit). */
    public static final java.util.concurrent.atomic.AtomicInteger PRECHECK_REFUSED = new java.util.concurrent.atomic.AtomicInteger();

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

    /**
     * Owner correction directive 2026-10-26 (PROVISIONAL): hazard modules keep gaining weight with the late-climb ramp. Before, their weights stopped at
     * intensity 1 (about 1,000 m; PUNISHER and NGH from the start) while other modules kept gaining share, so hazard scores were flat or fell.
     */
    static final float LATE_HAZ = 6f, LATE_TRAP = 3f, LATE_MORTAR = 2f;
    /**
     * Owner correction directive 2026-10-26 (PROVISIONAL): terrain complexity keeps rising with the late-climb ramp - demanding traversal modules gain weight
     * and plain hops lose some - because PUNISHER and NGH reach the top of the terrain curve early (their head start) and their terrain stayed flat
     * (campaign 40270745200: +6 % / +7 % from castle 1 to 10). Vertical modules are untouched: the spiral pitch controller needs them.
     */
    static final float LATE_COMPLEX = 0.8f, LATE_HOP_CUT = 0.35f;
    private Kind pick(int zone, Element last) {
        float[] w = weights(zone, last);
        float sum = 0; for (float f : w) sum += f;
        float u = rnd.nextFloat(), x = u * sum;
        Kind out = Kind.HOP;
        for (Kind k : Kind.values()) { x -= w[k.ordinal()]; if (x <= 0 && w[k.ordinal()] > 0) { out = k; break; } }
        if (P.adaptiveVariety()) {          // telemetry only: what the same draw would have picked without the memory
            float[] wb = weights(zone, last, false); float sb = 0; for (float f : wb) sb += f; float xb = u * sb; Kind would = Kind.HOP;
            for (Kind k : Kind.values()) { xb -= wb[k.ordinal()]; if (xb <= 0 && wb[k.ordinal()] > 0) { would = k; break; } }
            GenTrace.tally("adaptive_picks");
            if (would != out) { GenTrace.tally("adaptive_shift"); if (would == Kind.ROPE) GenTrace.tally("adaptive_rope_avoided"); }
        }
        return out;
    }

    /** One draw from weights {@code w} (nothing left: null); consumes one random number when anything is left. */
    private Kind draw(float[] w) {
        float sum = 0; for (float f : w) sum += f;
        if (sum <= 0f) return null;
        float x = rnd.nextFloat() * sum;
        for (Kind k : Kind.values()) { x -= w[k.ordinal()]; if (x <= 0 && w[k.ordinal()] > 0) return k; }
        for (int i = w.length - 1; i >= 0; i--) if (w[i] > 0f) return Kind.values()[i];
        return null;
    }

    /** When each ordinary continuation was last used (a clock of uses): the one used longest ago is tried first, so none of them takes over. */
    private final int[] ordinaryUsed = new int[Kind.values().length]; private int ordinaryClock;
    /**
     * The guaranteed continuation of a refused pick, in order (see extend): vertical moves that carry timing first, then the ordinary ones (stairs, a
     * hop, a rope), least recently used first; a hop flattens a lagging spiral, so then it comes last.
     */
    private static Kind[] continuation(int zone, boolean lagging, int[] used) {
        List<Kind> l = new ArrayList<>();
        l.add(Kind.MOVER_V); l.add(Kind.PAD); if (zone >= 1) l.add(Kind.ELEV_FALL);
        List<Kind> ord = new ArrayList<>(List.of(Kind.STAIRS, Kind.HOP, Kind.ROPE));
        ord.sort((a, b) -> Integer.compare(used[a.ordinal()], used[b.ordinal()]));       // stable: ties keep stairs, hop, rope
        if (lagging) { ord.remove(Kind.HOP); ord.add(Kind.HOP); }
        l.addAll(ord);
        return l.toArray(new Kind[0]);
    }

    /** Ordinary traversal: moving the climber on, not a challenge in itself (owner 2026-10-26: a refused challenge is not replaced by more of these first). */
    static boolean ordinary(Kind k) { return k == Kind.ROPE || k == Kind.HOP || k == Kind.STAIRS; }
    /** The vertical modules the spiral pitch controller favours while the route lags (see weights). */
    static boolean climbs(Kind k) { return k == Kind.PAD || k == Kind.ROPE || k == Kind.MOVER_V || k == Kind.STAIRS || k == Kind.ELEV_FALL; }

    /** The module weights {@link #pick} draws from: the mix of this tier and height, the spiral pitch controller and the repetition damping. */
    private float[] weights(int zone, Element last) { return weights(zone, last, true); }
    private float[] weights(int zone, Element last, boolean adaptive) {
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
        float lt = late(last.y);
        if (zone >= 1 && inten > 0f) w[Kind.MORTAR.ordinal()] = 1f + 3f * inten + LATE_MORTAR * P.lateHazScale * lt;          // a mortar row: any stage after the first world
        for (Kind k : new Kind[]{Kind.GRAB, Kind.SWING, Kind.CRUMBLE, Kind.MOVER_Z, Kind.SLIDE_FALL}) if (w[k.ordinal()] > 0f) w[k.ordinal()] += LATE_COMPLEX * lt;
        w[Kind.HOP.ordinal()] *= 1f - LATE_HOP_CUT * Math.min(1f, lt);
        if (zone >= 1) { w[Kind.SEESAW.ordinal()] = 4f; w[Kind.BRIDGE.ordinal()] = 4f; }
        else w[Kind.BRIDGE.ordinal()] = 2f;
        if (inten > 0f || (!endless && last.y > T.courseHeight * 0.18f)) { w[Kind.HAZ.ordinal()] = 3f + 9f * inten + LATE_HAZ * P.lateHazScale * lt; if (zone >= 1) w[Kind.SPRING.ordinal()] = 2f + 2f * inten; w[Kind.TRAP.ordinal()] = 2f + 5f * inten + LATE_TRAP * P.lateHazScale * lt; }
        if (endless && P.progression()) {          // rules 13: the castle's category factors - terrain challenges and hazard modules gain or lose weight against plain traversal
            float[] f = fac(last.y);
            for (Kind q : Kind.values()) { int cl = kindClass(q); if (cl == 1) w[q.ordinal()] *= f[Progression.T]; else if (cl == 2) w[q.ordinal()] *= hotNow ? 0.3f * Math.min(1f, f[Progression.H]) : f[Progression.H]; }
        }
        if (capOn()) capAdapt(w, last.y);          // NGH: hazard kinds by what they actually place (owner amendment 2026-10-27)
        if (adaptive && P.adaptiveVariety()) varAdapt(w, last.y);          // PUNISHER and (rules 13, lighter) NORMAL: recent mechanics less likely, within their own class (before the pitch controller)
        // spiral pitch controller: keep successive revolutions a healthy distance apart. Until 2026-10-26 it compared the height with
        // pitch x (arc / circumference) in the slice's own frame, whose arc restarts every slice (whole laps) but whose height only every 500 m:
        // past a slice's first ~60 m of a 500 m frame it always read "rushing" and damped the vertical modules, so the route could wind round
        // without climbing (PUNISHER seed 2 slice 48 closed 1.4 m above its own previous revolution, and slice 49 failed twelve attempts on it).
        // It now measures what it controls: the height above the route one revolution back (layerGap), in any frame.
        float gap = layerGap(last);
        boolean lagging = gap < T.layerGapMin, rushing = gap > T.layerGapMax;
        for (Kind k : Kind.values()) {
            boolean vert = climbs(k);
            if (lagging && !vert) w[k.ordinal()] *= 0.12f;
            if (rushing && vert) w[k.ordinal()] *= 0.2f;
        }
        // (PUNISHER no longer multiplies these weights: it reads the curve DifficultyProfile.curveShift metres higher; owner directive 2026-10-24)
        if (prev != null) { w[prev.ordinal()] *= 0.35f; if (prev == prev2) w[prev.ordinal()] = 0f; }
        return w;
    }

    /**
     * Phase 1 (2026-10-26): the Frost world's rain cloud (a WET pick on 20% of plain platforms) and the walk-up after a wet platform used to discard the
     * hazard, trap, mortar, creature or spike-drop module they displaced: in NORMAL seed 1 castles 3-4 about as many such picks were lost as were
     * placed (the castle 3-4 hazard dip in every mode). The displaced kind is now kept and built on the next module that does not follow a wet
     * platform; the rain and the walk-up stay as they were.
     */
    /**
     * Re-proof of route link {@code q} after a side structure (fork branch, pad fork, key room) was added near it. Phase 0 (2026-10-26): it used to check
     * only that the move still works, so a later structure could narrow the timing window of a saw guarding the link (SawFairnessTest on 71eed30: a gap
     * saw left 18% of its cycle). A link guarded by a gap saw also keeps the window it was built with, measured as the test measures it; any other link
     * is checked exactly as before.
     */
    private boolean routeLinkStillOk(int q) {
        boolean gapSaw = false;
        for (Element h : c.hazards) if (h.type == Element.Type.SAW_H && h.anchor == q) { Element p = c.get(q); if (!(p.w >= 7f && Math.abs(h.y - p.y - 0.62f) < 0.01f)) gapSaw = true; }
        // DC2 (owner correction 2026-10-27): the same proof quality as the link's first proof - its proven start (quiet moment, belt landing) and the
        // margin it was built with - not a bare "one move still works" from a standing start at time 0
        Autopilot.Result r = Autopilot.plan(provenStart(c, T, q), q, true);
        if (!r.ok || r.margin() < Math.max(0f, linkNeed(q))) return false;
        return !gapSaw || r.window >= Math.max(0.5f, windowNeeded(c.get(q).y));
    }

    private Kind deferredHaz;
    /** The narrowest deck a gap saw is timed from (m): the climber needs room to stand back and wait for the blade. */
    static final float SAW_TAKEOFF_W = 2.5f;          // (the hazard row's own decks are 2.5 m; 1.5 still let a 2.0 m take-off through: NORMAL seed 3 slice 3, hit walking back to wait)
    /** True if a hazard already stands on deck j (a trap or spiked block anchored to it), so the climber has no clear spot there to wait for a saw. */
    private boolean deckHasHazard(int j) { for (Element h : c.hazards) if (h.anchor == j) return true; return false; }
    private static boolean hazardKind(Kind k) { return k == Kind.HAZ || k == Kind.TRAP || k == Kind.MORTAR || k == Kind.CREATURE || k == Kind.DROPS; }

    public static volatile int wetTried, wetOk;
    /** Diagnostics only (never read by generation): failed slice attempts and local backtracks since start, for GenBench. */
    public static final java.util.concurrent.atomic.AtomicInteger SLICE_FAILURES = new java.util.concurrent.atomic.AtomicInteger(), BACKTRACKS = new java.util.concurrent.atomic.AtomicInteger();          // diagnostics: wet-platform modules attempted / proven

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
            // rules 13 (NORMAL seed 2, 4,415 m): a balance conveyor whose exit holds only from its exact proven landing (margin 0.083: 8% of trials)
            // fails for a climber who lands 2 cm away a moment later - the belt runs them off the back. Its links need a real margin (PROVISIONAL 0.25).
            if (beltSawNow && endless && P.progression()) margin = Math.max(margin, BELT_MARGIN);
            int cn0 = c.size(), ch0 = c.hazards.size();
            boolean ok = commit(es, hz, margin);
            // rules 13 (PUNISHER seeds 2 and 3, castles 6-7): a spike-drop row with enemies on its safe platforms was proven link by link, but a climber
            // crossing it in one go was shoved back under the drops again and again (111 hits). Such a row is kept only if it is crossed clean with
            // everything active, as the Enforcer encounters are (Autopilot.chainClean); otherwise another arrangement is tried.
            if (ok && k == Kind.DROPS && endless && P.progression()) {
                boolean enemies = false; for (Element h : hz) if (Progression.enemy(h.type)) enemies = true;
                if (enemies && !Autopilot.chainClean(c, T, Math.max(0, cn0 - 1), c.size() - 1)) { rollbackTo(cn0, ch0); ok = false; GenTrace.tally("drops_chain_refused"); }
                else if (enemies) encounters.add(new int[]{Math.max(0, cn0 - 1), c.size() - 1, 0});
            }
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
    /**
     * NOT GONNA HAPPEN round 2 correction (owner directive 2026-10-27, late hazards): a mortar row was 5-8 decks laid dead level, ~40 m of arc without
     * climbing, so on the spiral it ran into the revolution below whenever the route lagged its pitch: NGH round 1 accepted 10-15 of ~140 mortar rows per
     * course (all refusals but a few were the layer rule; NORMAL/PUNISHER rules 13 ~24%). NGH rows rise MORTAR_RISE0-1 m a deck (the gaps sized with
     * reach(rise)); each mortar fires from below the lower deck past the higher one. NORMAL and PUNISHER rows are unchanged.
     */
    static final float MORTAR_RISE0 = 0.2f, MORTAR_RISE1 = 0.45f;
    private List<Element> buildMortar(Element last, float d, int z, int attempt, List<Element> hzOut) {
        float inten = intensity(last.y);
        int n = 5 + rnd.nextInt(2) + (rnd.nextFloat() < late(last.y) ? 2 : 0);          // Build 71: longer mortar rows higher up
        List<Element> es = new ArrayList<>();
        Element cur = last; int base = c.size();
        float period = r(3.2f, 4.0f) - 0.4f * inten;
        boolean climbs = P == DifficultyProfile.NGH;          // NGH round 2 correction: the row climbs with the spiral (see MORTAR_RISE)
        for (int k = 0; k < n; k++) {
            float uR = cur.s + cur.w / 2f, rise = climbs ? r(MORTAR_RISE0, MORTAR_RISE1) : 0f;
            float g = Math.min(3.0f, Math.max(2.2f, reach(rise) * lerp(0.55f, 0.72f, d) * r(0.95f, 1f) * (1f - 0.03f * attempt)));
            float w = k == n - 1 ? 3.5f : r(2.4f, 3.2f);
            Element v = plat(Element.Type.STATIC, uR + g + w / 2f, cur.y + rise, w, z);
            es.add(v);
            Element m = hz(Element.Type.MORTAR, uR + g / 2f, cur.y - MORTAR_DEPTH, 0f, z);
            m.amp = MORTAR_DEPTH + rise + 2f * T.height; m.period = period * r(0.9f, 1.1f); m.phase = r(0f, 6.28f);      // Build 71 (owner): each mortar keeps its own time - no wait-jump-wait wave; a few gaps may line up for two or three jumps in a row
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
        float pSkull = 0.5f;
        if (P.adaptiveVariety()) { float ps = varPenalty(F_SKULL, last.y), pb = varPenalty(F_BLOB, last.y); pSkull = ps / (ps + pb); }
        int roll = rnd.nextFloat() < 0.3f + 0.7f * Math.min(1f, level(last.y)) ? ((P.adaptiveVariety() ? rnd.nextFloat() < pSkull : rnd.nextBoolean()) ? 0 : 1 + rnd.nextInt(2)) : 0;      // (blobs from Level 1 on, more often higher up; were world 3 on)          // 0 skull (half), 1 blob on a platform, 2 blob on a tipping bridge
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
        int castle = 1 + (int) ((base + last.y) / 500.0);
        float mid = castle < P.midEnemyFromCastle ? 0f : P.midEnemyChance + P.midEnemyGrowth * (castle - P.midEnemyFromCastle);
        boolean pursuitOk = base + last.y >= P.pursuitFromY;
        // below the pursuit height a crab may not follow off its platform: a crab hops gaps up to CRAB_HOP_GAP (3.2 m) but only CRAB_HOP_DY (1.8 m) up
        // or down (Sim.canHop), and a 3.45 m gap is past a fair jump (reach about 3.3 m), so the crabs' platform is raised just past the crabs' hop
        // above its neighbours instead (1.86-1.94 m: a jump lifts the feet 1.95 m, so it is a just-made-it hop up, and EncounterModel does not read it
        // as a long jump next to two pursuers and a timed drop, which its conflict rule refuses); drop off it onto the next drop platform
        float lift = 0f;
        for (int k = 0; k < n; k++) {
            float uR = cur.s + cur.w / 2f;
            if (k > 0 && mid > 0f && rnd.nextFloat() < mid && k < n - 1 && !enfCalm()) {          // (the chance is drawn first, as before batch 5: drawing it only when k < n - 1 reshuffled every later row, and NGH seed 1 lost its castle-1 spike-drop rows - ModeProfileTest)
                n--;          // the row keeps its length: one drop platform fewer (a row made longer by it ran into the spiral layer below: batch 4 probe, every such row refused)
                // owner structural rule (2026-10-26): a genuine intermediate safe platform between two drop platforms may host a rare enemy (a bee over it
                // or a pair of crabs); the drop platforms themselves are protected and carry none
                float iw = r(4.0f, 4.4f);          // 4 m or more: not a narrow take-off with crabs on it (EncounterModel conflict rule)
                boolean crabs = !pursuitOk || rnd.nextBoolean();          // below the pursuit height only crabs (a bee's envelope always covers its neighbours)
                float dy = crabs && !pursuitOk ? Sim.CRAB_HOP_DY + r(0.06f, 0.14f) : r(-0.2f, 0.3f);
                Element ip = plat(Element.Type.STATIC, uR + r(1.1f, 1.6f) * (1f - 0.03f * attempt) + iw / 2f, cur.y + dy, iw, z);
                es.add(ip); int ia = base0 + es.size() - 1;
                if (crabs) {
                    float span = iw - 1.6f; if (!pursuitOk) lift = dy;
                    int nc = 2 + T.hard;          // a crab deck carries two crabs plus one per difficulty level (Build 79 owner rule; DifficultyTest)
                    for (int q = 0; q < nc; q++) {
                        Element m = hz(Element.Type.CRAB, ip.s - span / 2f + q * span / (nc - 1), ip.y, 0f, z);
                        m.amp = Math.max(0.3f, Math.min(0.9f, span / (2f * (nc - 1)) - 0.3f)); m.period = r(3.0f, 4.0f) - 0.4f * inten; m.phase = r(0f, 6.28f); m.anchor = ia;
                        hzOut.add(m);
                    }
                } else {
                    Element m = hz(Element.Type.BEE, ip.s, ip.y + 2.2f, 0f, z); m.amp = 0.6f; m.len = 0.8f; m.period = r(7.0f, 9.5f) - 1.5f * inten; m.dir = rnd.nextBoolean() ? 1 : -1;
                    m.phase = r(0f, 6.28f); m.anchor = ia;
                    hzOut.add(m);
                }
                cur = ip; uR = cur.s + cur.w / 2f;
            }
            float g = k == 0 ? reach(0.2f) * lerp(0.45f, 0.75f, d) * (1f - 0.03f * attempt) : r(1.1f, 1.7f) * (1f - 0.03f * attempt);
            float w = r(5.0f, 5.8f);
            float py = lift > 0f ? cur.y - lift + r(-0.04f, 0.04f) : cur.y + (k == 0 ? r(0f, 0.4f) : r(-0.2f, 0.3f));          // off a raised one: back down past the crabs' hop
            lift = 0f;
            Element p = plat(Element.Type.STATIC, uR + g + w / 2f, py, w, z);
            es.add(p);
            // the slab hangs forward of the middle: you land on clear deck at the back, time your run under it, and take off from the lip in front
            // (centred, it hung right where the hop lands, and a climber who stopped there had no safe way on - EndlessTest, seed 4 slice 9)
            Element h = hz(Element.Type.SPIKE_DROP, p.s + 0.2f * w, p.y, 1.6f, z);
            h.amp = r(DROP_MIN_TOP, DROP_MAX_TOP); h.period = r(2.7f, 4.3f) - 0.3f * inten; h.phase = r(0f, 6.28f);
            h.anchor = base0 + es.size() - 1;
            hzOut.add(h);
            // (2026-10-26, owner protected-structure rule: the Build 79 crab/bee on the drop platform itself is gone; with NGH's +0.6 chance and four crabs
            //  it filled nearly every drop deck, and no NGH spike-drop row could be proven - 146 tries, 0 kept. Enemies now come on intermediate platforms.)
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
        float fh = hotNow ? Math.min(1f, fac(last.y)[Progression.H]) : fac(last.y)[Progression.H];          // rules 13: the castle's hazard factor lengthens or shortens the chain (1 for NGH)
        int n = (inten > 0.35f && rnd.nextFloat() < Math.min(0.95f, inten * 0.6f * fh)) ? 2 : 1;
        float lt = late(last.y);
        if (n == 2 && lt > 0.2f && rnd.nextFloat() < Math.min(0.9f, lt * 0.55f * fh)) n = 3;          // Build 71: higher up, three guarded gaps in a row
        List<Element> es = new ArrayList<>();
        Element cur = last;
        int base = c.size(), prevRoll = -1;
        for (int j = 0; j < n; j++) {
            float uR = cur.s + cur.w / 2f, dy = r(-0.3f, 0.6f);
            boolean cannonOk = cur.w >= 3f && cur.type == Element.Type.STATIC;
            int roll = wantCreature && j == 0 ? 3 : P.adaptiveVariety() ? varRoll(HAZ_ROLL_FAM, cannonOk ? 5 : 4, cur.y) : rnd.nextInt(cannonOk ? 5 : 4);
            if ((roll == 1 && prevRoll == 3) || (roll == 3 && prevRoll == 1)) roll = 2;      // Build 71: the bee now homes in and bumps you, so it never sits next to a saw you must time (it cut the saw's window to 0.2 s; SawFairnessTest)
            if ((roll == 1 && prevRoll == 4) || (roll == 4 && prevRoll == 1)) roll = 2;      // Build 79: nor a cannon lane right before or after it (a cannon across the landing cut a saw's window from its whole cycle to 0.36; SawFairnessTest, seed 3)
            if (roll == 3 && enfCalm()) roll = 2;          // PUNISHER: no bee next to an Enforcer encounter (the draw is kept, so NORMAL is unchanged)
            prevRoll = roll;
            if (roll == 1 && creatureReaches(j == 0 ? c.size() - 1 : base + j - 1)) roll = 2;      // Build 79: nor next to a skull, blob or crab that can roam onto its decks (a skull there cut the window to 0.36; SawFairnessTest)
            if (roll == 1 && cur.w < SAW_TAKEOFF_W) roll = 2;      // rules 14: nor from a narrow foothold (NORMAL seed 3 slice 7 under rules 13: a gap saw from a 1.0 m ledge left no spot to wait for it; SawFairnessTest)
            if (roll == 1 && j == 0 && deckHasHazard(c.size() - 1)) roll = 2;      // rules 14: nor from a deck that already carries its own trap (NORMAL seeds 2 and 4 under rules 13: a spike trap / spiked block on the take-off deck cut the saw's window to 0.40-0.46 of its cycle; SawFairnessTest)
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
        int roll = wantCreature ? 4 : P.adaptiveVariety() ? varRoll(MID_ROLL_FAM, 5, last.y) : rnd.nextInt(5);
        if (roll == 4 && enfCalm()) roll = 2;          // PUNISHER: no crab deck next to an Enforcer encounter
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
