package com.hotatticgames.climbup.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Look-ahead planner that plays the real {@link Sim}. Used (1) while generating, to prove every link has a physical
 * solution with a measurable tolerance, and (2) in tests as the scripted full-course run.
 */
public final class Autopilot {
    public interface Policy { void act(Sim sim, InputState in); }
    public interface PolicyFactory { Policy create(); }

    public static final class Result {
        public boolean ok; public PolicyFactory factory; public int trials, successes;
        public float window = 1f;     // best fraction of wait-phases a single move style succeeds at (timing tolerance; 1 = timing irrelevant)
        /** With lookahead: a working move that does not leave a state the next link can be made from (ok stays false; see {@link #planRun}). */
        public PolicyFactory fallback;
        public float margin() { return trials == 0 ? 0 : successes / (float) trials; }
    }

    /**
     * Owner correction directive 2026-10-27 (DC1): what a whole-course run establishes. PROVEN_SOLVABLE: a continuous, hit-free traversal witness
     * on the real simulation reached the goal. PROVEN_UNSOLVABLE: a sound bound shows a required move is out of physical reach (see
     * {@link #outOfReach}). UNKNOWN: the solver could not establish a result (budget, no move found, a hit on its line, lost, stuck): never a pass.
     * FAILED_VALIDATION is reserved for the generator's own constraint checks (integrity, launch corridors), which never reach the solver.
     */
    public enum Verdict { PROVEN_SOLVABLE, PROVEN_UNSOLVABLE, UNKNOWN, FAILED_VALIDATION }

    public static final class Report {
        /** True only for {@link Verdict#PROVEN_SOLVABLE}. */
        public boolean completed; public int failedLink = -1; public float simTime; public int links; public float minMargin = 1f;
        public int worstLink = -1;
        public String failInfo = "";
        public Verdict verdict = Verdict.UNKNOWN;
        /** Short cause of a verdict other than PROVEN_SOLVABLE: budget, setback, no_move, lost_route, stuck, branch, detour, recover, key_missed, stalled, out_of_reach. */
        public String cause = "";
        /** How the witness got through (audit counters): moves taken without a lookahead-good follow-up, resyncs onto another route element, waits on the spot, moves executed while a look-ahead still saw a hazard on them. */
        public int fallbackMoves, resyncs, waits, unclearMoves;
    }
    private static Report stop(Report rep, Sim real, int a, String cause, String info) {
        rep.failedLink = a; rep.simTime = real.time; rep.cause = cause; rep.failInfo = info;
        rep.verdict = cause.equals("out_of_reach") ? Verdict.PROVEN_UNSOLVABLE : Verdict.UNKNOWN; rep.completed = false;
        return rep;
    }

    static final float[] OFFSETS = {0f, 0.45f, 0.95f, 1.5f};
    /** Move-style id ranges for the timing-window metric (DC7): ground 32 (offset x hold x steer), walk-off 4, hole hop 6, mid hop 8, rope 12, cable 8. */
    static final int G_GROUND = 0, G_WALKOFF = 32, G_HOLEHOP = 36, G_MIDHOP = 42, G_ROPE = 50, G_CABLE = 62, G_COUNT = 70;
    /** Waits tried on a rope or a cable beside something that moves (DC7), spread over its period. */
    static final int ROPE_WAITS = 6;
    static final float[] HOLDS = {0.9f, 0.22f};

    // ------------------------------------------------------------------ policies

    /** Build 72 (one movement speed): a person steers a direction-only stick by holding toward the target and letting go once over it. */
    static float steer(Sim s, int b, int mode) {
        float d = s.course.dsWrap(s.es1[b], s.s);
        Element el = s.course.get(b);
        if (mode == 1) return Math.signum(d);
        if (mode == 2) { // aim at the near edge of the target platform
            float near = d - Math.signum(d) * (el.isPlatform() ? Math.max(0f, el.halfW() - 0.3f) : 0f);
            return Math.abs(near) < 0.2f ? 0f : Math.signum(near);
        }
        return Math.abs(d) < 0.25f ? 0f : Math.signum(d);
    }
    /** How many steps a stand-in for a person holds a stick direction before changing it (100 ms): see {@link Sim#humanHold}. */
    public static final int HUMAN_HOLD = 6;
    /** A slow walk on a direction-only stick: a person feathers it, pushing and letting go in turns (each at least {@link #HUMAN_HOLD} steps). */
    static float feather(float t, float speed) {
        if (speed >= 0.99f) return 1f;
        int on = HUMAN_HOLD, off = Math.max(HUMAN_HOLD, Math.round(HUMAN_HOLD * (1f - speed) / Math.max(0.1f, speed)));
        int k = (int) (t / Sim.DT + 0.5f) % (on + off);
        return k < on ? 1f : 0f;
    }
    /** Walking to a spot on a direction-only stick: push toward it and let go when close; the brake stops you within a few centimetres of a quarter metre. */
    static float walkTo(float d) { return Math.abs(d) < 0.3f ? 0f : Math.signum(d); }

    static final class GroundPolicy implements Policy {
        final int a, b, dir, steerMode; final float wait, offset, hold; float speed = 1f;
        float t, t2, holdT; int phase;
        /** Shared-wait probe (see {@link WaitPrefix}): stops, touching nothing, at the first step whose action depends on more than the wait. */
        boolean probe, diverged;
        GroundPolicy(int a, int b, int dir, float wait, float offset, float hold, int steerMode) {
            this.a = a; this.b = b; this.dir = dir; this.wait = wait; this.offset = offset; this.hold = hold; this.steerMode = steerMode;
        }
        public void act(Sim s, InputState in) {
            if (s.mode == Sim.Mode.LEDGE || s.mode == Sim.Mode.PULLUP) { in.moveY = 1f; return; }
            if (probe && !(phase == 0 && s.mode == Sim.Mode.GROUND && t < wait)) { diverged = true; return; }
            if (phase == 0) {
                if (s.mode != Sim.Mode.GROUND) { phase = 2; }
                else if (t < wait) { t += Sim.DT; return; }
                else {
                    Element el = s.course.get(a);
                    float edge = s.es1[a] + dir * (el.halfW() - Math.min(offset, Math.max(0f, el.w - 0.5f)));
                    float ds = s.course.dsWrap(edge, s.s);
                    float ahead = ds * dir;
                    if (ahead > 0.06f) { in.moveX = dir * feather(t2 += Sim.DT, speed); return; }
                    if (ahead < -0.3f) { in.moveX = -dir; return; }
                    in.moveX = dir * (speed >= 0.99f ? 1f : feather(t2, speed)); in.jumpPressed = true; in.jumpHeld = true; phase = 2; holdT = 0; return;
                }
            }
            if (phase == 2) {
                holdT += Sim.DT;
                in.jumpHeld = holdT < hold;
                in.moveX = steerMode == 3 ? dir : steer(s, b, steerMode);
            }
        }
    }

    static final class AirPolicy implements Policy {
        final int b, steerMode; final boolean held;
        AirPolicy(int b, int steerMode, boolean held) { this.b = b; this.steerMode = steerMode; this.held = held; }
        public void act(Sim s, InputState in) {
            if (s.mode == Sim.Mode.LEDGE || s.mode == Sim.Mode.PULLUP) { in.moveY = 1f; return; }
            in.jumpHeld = held; in.moveX = steer(s, b, steerMode);
        }
    }

    static final class RopePolicy implements Policy {
        final int a, b, dir; final float delta, hold; int phase; float holdT;
        RopePolicy(int a, int b, int dir, float delta, float hold) { this.a = a; this.b = b; this.dir = dir; this.delta = delta; this.hold = hold; }
        public void act(Sim s, InputState in) {
            if (s.mode == Sim.Mode.LEDGE || s.mode == Sim.Mode.PULLUP) { in.moveY = 1f; return; }
            if (phase == 0) {
                if (s.mode != Sim.Mode.ROPE) { phase = 2; }
                else {
                    Element r = s.course.get(a);
                    float hand = s.y + s.T.handHeight;
                    if (hand < r.y - delta - 0.05f) { in.moveY = 1f; return; }
                    in.moveX = dir; in.jumpPressed = true; in.jumpHeld = true; phase = 2; holdT = 0; return;
                }
            }
            holdT += Sim.DT;
            in.jumpHeld = holdT < hold;
            in.moveX = Math.abs(s.course.dsWrap(s.es1[b], s.s)) > 0.5f ? dir : steer(s, b, 0);
        }
    }

    static final class CablePolicy implements Policy {
        final int a, b, dir; final float k; final boolean drop; int phase; float holdT;
        CablePolicy(int a, int b, int dir, float k, boolean drop) { this.a = a; this.b = b; this.dir = dir; this.k = k; this.drop = drop; }
        public void act(Sim s, InputState in) {
            if (s.mode == Sim.Mode.LEDGE || s.mode == Sim.Mode.PULLUP) { in.moveY = 1f; return; }
            if (phase == 0) {
                if (s.mode != Sim.Mode.CABLE) { phase = 2; }
                else {
                    Element c = s.course.get(a);
                    float end = s.es1[a] + dir * c.w * 0.5f;
                    float ahead = s.course.dsWrap(end, s.s) * dir;
                    if (ahead > k) { in.moveX = dir; return; }
                    in.moveX = dir;
                    if (drop) in.moveY = -1f; else { in.jumpPressed = true; in.jumpHeld = true; }
                    phase = 2; holdT = 0; return;
                }
            }
            holdT += Sim.DT; in.jumpHeld = holdT < 0.3f; in.moveX = steer(s, b, 0);
        }
    }

    /** DC7: hangs on (no input) for {@code wait} seconds, then plays {@code inner}: waiting for a gap on a rope or a cable, as a person does. */
    static final class WaitFirst implements Policy {
        final Policy inner; final float wait; float t;
        WaitFirst(Policy inner, float wait) { this.inner = inner; this.wait = wait; }
        public void act(Sim s, InputState in) { if (t < wait && (s.mode == Sim.Mode.ROPE || s.mode == Sim.Mode.CABLE)) { t += Sim.DT; return; } inner.act(s, in); }
    }

    /** Waits, then simply walks off the edge (no jump) and steers toward the target: the way down to a lower platform. */
    static final class WalkOffPolicy implements Policy {
        final int b, dir, steerMode; final float wait; float t;
        WalkOffPolicy(int b, int dir, float wait, int steerMode) { this.b = b; this.dir = dir; this.wait = wait; this.steerMode = steerMode; }
        public void act(Sim s, InputState in) {
            if (s.mode == Sim.Mode.LEDGE || s.mode == Sim.Mode.PULLUP) { in.moveY = 1f; return; }
            if (s.mode == Sim.Mode.GROUND) { if (t < wait) { t += Sim.DT; return; } in.moveX = dir; return; }
            in.moveX = steerMode == 3 ? dir : steer(s, b, steerMode);
        }
    }

    /** Waits, runs across the platform, hops over the hazard in the middle ({@code hopDist} before it), then leaves like {@link GroundPolicy}. */
    static final class MidHopPolicy implements Policy {
        final int a, b, dir; final float wait, hopDist, hx; final GroundPolicy rest; float t; int phase;
        /** Hazard index of a crab to hop (-1: a fixed hazard at hx): a crab runs at you (Build 63), so the hop is timed off where it IS, not where its sweep was. */
        final int crab;
        MidHopPolicy(int a, int b, int dir, float wait, float hopDist, float hx, float offset, int steerMode) { this(a, b, dir, wait, hopDist, hx, offset, steerMode, 1f, -1); }
        MidHopPolicy(int a, int b, int dir, float wait, float hopDist, float hx, float offset, int steerMode, int crab) { this(a, b, dir, wait, hopDist, hx, offset, steerMode, 1f, crab); }
        /** {@code restSpeed} below 1 is the walk to the take-off edge after the hazard is behind: a person eases off before a spring or pad, which launches with whatever speed is carried in. */
        MidHopPolicy(int a, int b, int dir, float wait, float hopDist, float hx, float offset, int steerMode, float restSpeed) { this(a, b, dir, wait, hopDist, hx, offset, steerMode, restSpeed, -1); }
        MidHopPolicy(int a, int b, int dir, float wait, float hopDist, float hx, float offset, int steerMode, float restSpeed, int crab) {
            this.a = a; this.b = b; this.dir = dir; this.wait = wait; this.hopDist = hopDist; this.hx = hx; this.crab = crab;
            rest = new GroundPolicy(a, b, dir, 0f, offset, 0.9f, steerMode); rest.speed = restSpeed;
        }
        public void act(Sim s, InputState in) {
            if (s.mode == Sim.Mode.LEDGE || s.mode == Sim.Mode.PULLUP) { in.moveY = 1f; return; }
            if (phase == 0) {
                if (s.mode != Sim.Mode.GROUND) { phase = 3; }
                else if (t < wait) { t += Sim.DT; return; }
                else phase = 1;
            }
            if (phase == 1) {
                float ahead = crab >= 0 ? crabAhead(s) : s.course.dsWrap(hx, s.s) * dir;
                in.moveX = dir;
                if (ahead <= hopDist) { in.jumpPressed = true; in.jumpHeld = true; phase = 2; }
                return;
            }
            if (phase == 2) {
                in.moveX = dir; in.jumpHeld = s.vy > 0f && s.mode == Sim.Mode.AIR;
                if (s.mode != Sim.Mode.GROUND) return;
                phase = crab >= 0 && crabAhead(s) < 1e8f ? 1 : 3;          // Build 79: several crabs share a deck: hop each in turn
                if (phase == 1) return;
            }
            rest.act(s, in);
        }
        /** Distance ahead (along dir) of the nearest live crab on this deck that is still in front of you (1e9 when none). */
        private float crabAhead(Sim s) {
            Course c = s.course; Element ref = crab < c.hazards.size() ? c.hazards.get(crab) : null;
            float best = 1e9f;
            for (int k = 0, n = s.hz == null ? c.hazards.size() : s.hz.length; k < n; k++) {
                int hi = s.hz == null ? k : s.hz[k];
                if (hi >= c.hazards.size()) continue;
                Element h = c.hazards.get(hi);
                if (h.type != Element.Type.CRAB || (hi < s.featDone.length && s.featDone[hi]) || s.crabFalling(hi) || ref == null || s.mobDeckIdx(hi, h) != s.mobDeckIdx(crab, ref)) continue;
                float ahead = c.dsWrap(s.crabAt(hi, h), s.s) * dir;
                if (ahead > -0.2f && ahead < best) best = ahead;
            }
            return best;
        }
    }

    /** Build 79: runs across a bridge whose planks drop out (Element.holed), hopping each hole {@code hopDist} before its near edge, then leaves like {@link GroundPolicy}. */
    static final class HoleHopPolicy implements Policy {
        final int a, dir; final float hopDist; final GroundPolicy rest; boolean inHop;
        HoleHopPolicy(int a, int b, int dir, float hopDist, int steerMode) { this.a = a; this.dir = dir; this.hopDist = hopDist; rest = new GroundPolicy(a, b, dir, 0f, 0f, 0.9f, steerMode); }
        public void act(Sim s, InputState in) {
            if (s.mode == Sim.Mode.LEDGE || s.mode == Sim.Mode.PULLUP) { in.moveY = 1f; return; }
            if (inHop) {
                if (s.mode == Sim.Mode.AIR) { in.moveX = dir; in.jumpHeld = s.vy > 0f; return; }
                inHop = false;
            }
            if (s.mode == Sim.Mode.GROUND && s.onElem == a) {
                float near = nextHole(s);
                if (near < 1e8f) {
                    in.moveX = dir;
                    if (near <= hopDist) { in.jumpPressed = true; in.jumpHeld = true; inHop = true; }
                    return;
                }
            }
            rest.act(s, in);
        }
        /** Distance (along dir) from you to the near edge of the next hole section ahead on deck a (negative when already over it), else 1e9. */
        private float nextHole(Sim s) {
            Element e = s.course.get(a);
            if (!e.holed()) return 1e9f;
            float x = s.course.dsWrap(s.s, s.es1[a]); int n = e.holeSections(); float sec = e.w / n, best = 1e9f;
            for (int k = 0; k < n; k++) {
                if ((e.color & (1 << k)) == 0) continue;
                float lo = -e.w * 0.5f + k * sec, hi = lo + sec, near = dir > 0 ? lo : hi, far = dir > 0 ? hi : lo;
                if ((far - x) * dir <= Sim.HOLE_FOOT) continue;          // behind you
                best = Math.min(best, (near - x) * dir);
            }
            return best;
        }
    }

    /** Every planned move is played with a person's reflexes on top (Build 71): see {@link Reflex}. */
    static Policy make(PolicyFactory f) { return f == null ? null : new Reflex(f); }

    /**
     * Build 71 creatures need reflexes rather than a plan made in advance: a skull dashes at you wherever you are, a green blob lands where you were
     * (Build 79: and a crab that followed you onto another platform runs at you there: hop it as it closes in; crabs on their own deck are the plan's job).
     * On foot, this hops a dashing skull just before it reaches you (its dash is low, so a hop clears it) and steps out of a blob's shadow (away from its
     * centre, or the other way when the deck's edge is too close). The planned move is then started again from wherever that left you. Without either
     * creature nearby it is exactly the planned move.
     */
    static final class Reflex implements Policy {
        final PolicyFactory f; Policy inner; int busy; int blob = -1; float dodge;
        Reflex(PolicyFactory f) { this.f = f; inner = f.create(); }
        public void act(Sim s, InputState in) {
            if (busy == 1) {                                         // in the hop over a skull
                if (s.mode == Sim.Mode.AIR) { in.jumpHeld = s.vy > 0f; return; }
                busy = 0; inner = f.create();
            } else if (busy == 2) {                                  // stepping out of a blob's shadow
                if (s.mode == Sim.Mode.GROUND && s.blobShadow(blob) >= 0f) { in.moveX = dodge; return; }
                busy = 0; inner = f.create();
            }
            if (s.mode == Sim.Mode.GROUND && threat(s, in)) return;
            inner.act(s, in);
        }
        private boolean threat(Sim s, InputState in) {
            Course c = s.course; float hw = s.T.halfWidth;
            for (int k = 0, n = s.hz == null ? c.hazards.size() : s.hz.length; k < n; k++) {
                int hi = s.hz == null ? k : s.hz[k];
                if (hi >= c.hazards.size()) continue;
                Element h = c.hazards.get(hi);
                if (h.type == Element.Type.SKULL && s.skullDashing(hi)) {
                    int dj = s.mobDeckIdx(hi, h);                       // Build 79: the deck it hunts on now
                    if (dj < 0 || Math.abs(s.y - s.deckY(dj)) > 0.6f) continue;
                    float ahead = c.dsWrap(s.s, s.mobS[hi]) * s.mobDir[hi], gap = ahead - (Element.SKULL_R + hw);
                    // 2026-10-26: the dash runs at SKULL_SPEED x react() in the Sim; timing the hop on the base speed jumped too late in PUNISHER and always in NGH (0 NGH skulls)
                    if (ahead > 0f && gap / (Element.SKULL_SPEED * s.T.react()) < 0.15f) { in.jumpPressed = true; in.jumpHeld = true; busy = 1; return true; }
                } else if (h.type == Element.Type.CRAB && s.crabChasing(hi) && !(hi < s.featDone.length && s.featDone[hi]) && !s.crabFalling(hi)
                        && s.crabJump[hi] < 0f && hi < s.deckOff.length && Math.round(s.deckOff[hi]) != 0 && s.mobDeckIdx(hi, h) == s.onElem) {   // Build 79: a crab that followed you off its own deck runs at you: hop it as it comes (on its own deck the plan's crab hop handles it)
                    float d = c.dsWrap(s.crabAt(hi, h), s.s);
                    if (Math.abs(d) < 1.35f && Math.abs(d) > 0.25f && s.crabFacing(hi, h) * d < 0f) { in.jumpPressed = true; in.jumpHeld = true; busy = 1; return true; }
                } else if (h.type == Element.Type.BLOB && s.blobShadow(hi) >= 0f && s.mobDir[hi] == 0f) {
                    float d = c.dsWrap(s.s, s.mobTgt[hi]);
                    if (Math.abs(d) >= Element.BLOB_R + hw + 0.35f) continue;
                    int dj = s.mobDeckIdx(hi, h);                       // Build 79: the deck it comes down on
                    Element deck = dj >= 0 ? c.get(dj) : null;
                    float dir = d > 0f ? 1f : d < 0f ? -1f : s.facing;
                    if (deck != null) { float room = c.dsWrap(s.es1[dj] + dir * deck.halfW(), s.s) * dir; if (room < 1.3f) dir = -dir; }
                    dodge = dir; blob = hi; busy = 2; in.moveX = dir; return true;
                }
            }
            return false;
        }
    }

    static final class PullPolicy implements Policy {
        public void act(Sim s, InputState in) { in.moveY = 1f; }
    }

    // ------------------------------------------------------------------ planning

    static boolean movingNear(Course c, int a) {
        for (int k = 0; k <= 2; k++) { int i = c.deck(a, k); if (i >= 0 && (c.get(i).isMoving() || c.get(i).spins())) return true; }          // (DC6: route decks, the live tower's too)
        int a0 = anchorIdx(c, a), before = Math.max(0, c.deck(a0, -1)), hi = c.deck(a0, 2) < 0 ? c.size() - 1 : c.deck(a0, 2);
        for (Element h : c.hazards) if (h.isMoving() && nearLink(c, a0, before, hi, h)) return true;
        return false;
    }

    /**
     * Build 82 review (finding 5): hazard {@code h} sits on the route deck before {@code a0} (a cannon, aimed cannon or mortar there fires across this link)
     * or on one of the route decks {@code a0} .. two after it, counted along the route as the decks above are (the live tower lists each slice's side
     * structures after its route, so "a0 + 2" can name a decoy and miss the next slice's first decks). A hazard on a side structure is not on the link.
     */
    static boolean nearLink(Course c, int a0, int before, int hi, Element h) {          // before = the route deck before a0, hi = two route decks after it (course end if none)
        int at = h.anchor;
        if (at < 0 || at >= c.size() || c.get(at).anchor >= 0) return false;
        return at >= (firesAhead(h) ? before : a0) && at <= hi;
    }

    static float maxPeriod(Course c, int a) {
        float p = 0;
        for (int k = 0; k <= 2; k++) { int i = c.deck(a, k); if (i >= 0 && (c.get(i).isMoving() || c.get(i).spins())) p = Math.max(p, c.get(i).period); }
        int a0 = anchorIdx(c, a), before = Math.max(0, c.deck(a0, -1)), hi = c.deck(a0, 2) < 0 ? c.size() - 1 : c.deck(a0, 2);
        for (Element h : c.hazards) if (h.isMoving() && nearLink(c, a0, before, hi, h)) p = Math.max(p, h.period);
        return p;
    }
    /** Build 79: a cannon (or mortar) on the platform before fires across this one, as CourseGenerator.commit proves it: a person waits for a clear moment here too
     *  (framed seed 5 slice 6: off a spring the cannon from the platform before was always in the way, as the planner never tried waiting before the spring). */
    private static boolean firesAhead(Element h) { return h.type == Element.Type.CANNON || h.type == Element.Type.AIMED || h.type == Element.Type.MORTAR; }

    static boolean succeeded(Sim s, int a) {
        switch (s.mode) {
            case GROUND: case LEDGE: case PULLUP: case ROPE: case CABLE: case BEAM:
                return s.onElem > a && onRoute(s.course, s.onElem) && (s.mode != Sim.Mode.GROUND || !s.gone[s.onElem]);
            default: return s.lastPad > a && onRoute(s.course, s.lastPad);
        }
    }
    /** A route element, not a dead end or lure. Build 79: a streaming tower's world keeps each slice's decoys among the route indices, so "below routeSize()"
     *  alone took a grab of a dead end's ledge (a left-hand spur hanging over the route a few steps back) for progress (StreamingReplayTest, seed 12). */
    static boolean onRoute(Course c, int i) { return i >= 0 && i < c.routeSize() && c.get(i).anchor < 0; }

    /** Pair mode (key-room detours): success means attaching to exactly element {@code tgt}. */
    static boolean succeededTo(Sim s, int tgt) {
        switch (s.mode) {
            case GROUND: case LEDGE: case PULLUP: case ROPE: case CABLE: case BEAM: return s.onElem == tgt && (s.mode != Sim.Mode.GROUND || !s.gone[tgt]);
            default: return s.mode == Sim.Mode.AIR && s.lastPad == tgt;
        }
    }

    /** Detour landings must be survivable: after touching down the player can brake (full stick against the motion) and still be on the platform. */
    static boolean staysOn(Sim landed, int tgt) {
        if (landed.mode != Sim.Mode.GROUND) return true;
        Sim t = landed.copy(); InputState in = new InputState();
        for (int i = 0; i < 40; i++) { in.clear(); t.step(in); if (t.mode != Sim.Mode.GROUND || t.onElem != tgt) return false; }
        return true;
    }

    /**
     * The solver re-syncs to route element {@code i} the climber was carried onto. A pad launch further up the route is history by then: the simulation keeps
     * the last pad launched from only as a marker (it never moves the climber by it), and left set, the next jump from below read as that launch arriving
     * (succeeded: in the air past a pad beyond the link), so a climber shoved down past a pad "succeeded" at every hop (2026-10-26 GenBench, PUNISHER seed 2
     * slice 53: off platform 96 onto rope 88, then every move from 88-90 counted as reaching pad 95).
     */
    static int resync(Sim real, int i) { if (real.lastPad > i) real.lastPad = -1; return i; }

    static int anchorIdx(Course c, int i) { return c.get(i).anchor < 0 ? i : c.get(i).anchor; }

    /** Runs a policy on a copy of the state; true if it attaches to a later element without falling. */
    static boolean trial(Sim base, int a, PolicyFactory f, float limit) { return trialEnd(base, a, -1, f, limit) != null; }

    /** Like {@link #trial} but returns the state at the moment of success (null on failure). */
    static Sim trialEnd(Sim base, int a, PolicyFactory f, float limit) { return trialEnd(base, a, -1, f, limit); }

    static Sim trialEnd(Sim base, int a, int tgt, PolicyFactory f, float limit) { return trialEnd(base, a, tgt, f, limit, (MotionCache) null); }

    private static Sim trialEnd(Sim base, int a, int tgt, PolicyFactory f, float limit, MotionCache mc) {
        Sim s = trialStart(base, a, tgt, mc);
        return trialRun(s, make(f), a, tgt, s.setbacks(), 0, (int) (limit / Sim.DT));
    }

    private static Sim trialStart(Sim base, int a, int tgt, MotionCache mc) {
        Sim s = base.copy(); s.humanHold = HUMAN_HOLD; s.deferRespawn = false;          // (a trial's fall is a setback at once, never a respawn waiting for its window)
        s.motion = mc; s.motionGen = mc == null ? 0 : mc.gen(); s.mstep = 0;
        if (tgt >= 0) s.setWindowNear(anchorIdx(s.course, a) - 2, Math.max(anchorIdx(s.course, a) + 6, anchorIdx(s.course, tgt) + 1), a, tgt);     // plus earlier route platforms the climb passed that hang over a side branch (DC7: and the target, a fork's rejoin up to 9 ahead)
        else s.setWindow(a - 1, a + 6);
        return s;
    }

    /** Steps {@code from..n-1} of a trial; {@code s} has already run steps 0..from-1 of it. */
    private static Sim trialRun(Sim s, Policy p, int a, int tgt, int fallsBefore, int from, int n) {
        InputState in = new InputState();
        float floorY = trialFloor(s.course, a, tgt);
        for (int i = from; i < n; i++) {
            in.clear();
            p.act(s, in);
            s.step(in);
            int end = trialEnded(s, a, tgt, fallsBefore, floorY);
            if (end != 0) return end > 0 ? finishArrival(s, a, tgt, fallsBefore) : null;
        }
        return null;
    }

    /**
     * Owner correction directive 2026-10-27 (DC2): a ledge catch is not an arrival. The pull-up is played out on the trial's own simulation (up held,
     * as a person and the run do), and the move counts only if it ends standing on a deck that is still there - the deck it was aiming for, or a
     * later route deck - with no hit or fall on the way. Probe evidence: a link proven at 92% (PUNISHER seed 2 slice 2, link 60) was hit by a cannon
     * during the pull-up that followed every catch.
     */
    static Sim finishArrival(Sim s, int a, int tgt, int fallsBefore) {
        if (s.mode != Sim.Mode.LEDGE && s.mode != Sim.Mode.PULLUP) return s;
        InputState in = new InputState();
        for (int q = 0; q < 90 && (s.mode == Sim.Mode.LEDGE || s.mode == Sim.Mode.PULLUP); q++) { in.clear(); in.moveY = 1f; s.step(in); if (s.setbacks() != fallsBefore) return null; }
        if (s.mode != Sim.Mode.GROUND || s.onElem < 0 || s.gone[s.onElem]) return null;
        if (tgt >= 0) return s.onElem == tgt && staysOn(s, tgt) ? s : null;
        return s.onElem > a && onRoute(s.course, s.onElem) ? s : null;
    }

    private static float trialFloor(Course c, int a, int tgt) { return (tgt >= 0 ? Math.min(c.get(a).y, c.get(tgt).y) : Math.min(c.get(a).y, c.get(Math.min(c.routeSize() - 1, c.nextRoute(a))).y)) - 4.5f; }

    /** After a trial step: 1 succeeded, -1 failed, 0 carry on. */
    private static int trialEnded(Sim s, int a, int tgt, int fallsBefore, float floorY) {
        if (s.setbacks() != fallsBefore) return -1;
        if (tgt >= 0 ? succeededTo(s, tgt) : succeeded(s, a)) return tgt >= 0 && !staysOn(s, tgt) ? -1 : 1;
        if (tgt < 0 && s.mode == Sim.Mode.GROUND && s.onElem >= 0 && (s.onElem < a || !onRoute(s.course, s.onElem))) return -1;   // fell back, or onto a dead end
        if (s.mode == Sim.Mode.AIR && s.vy < 0 && s.y < floorY) return -1;
        return 0;
    }

    /**
     * Speed only, same results (2026-10-26, generation performance): every ground move that waits {@code wait} seconds before its run-up stands still
     * for exactly the same steps whatever its offset, hold and steering (the reflexes it runs under react to creatures the same way, and a reflex that
     * interrupts the wait restarts it the same way). So the wait is simulated once per wait and each move carries on from where it ends, instead of
     * every one of the 24 moves of a wait repeating it. A trial resumed here runs exactly the steps the whole trial would have.
     */
    static final class WaitPrefix {
        final Sim base; final int a, b, tgt, dir, n; final MotionCache mc;
        private final java.util.HashMap<Float, Object[]> done = new java.util.HashMap<>();          // wait -> {state where the moves part (null: the trial ended during the wait), step, policy clock, ended state}
        WaitPrefix(Sim base, int a, int b, int tgt, int dir, float limit) { this.base = base; this.a = a; this.b = b; this.tgt = tgt; this.dir = dir; this.n = (int) (limit / Sim.DT); this.mc = MotionCache.open(n); }

        Sim trial(GroundPolicy shape, PolicyFactory f) {
            Object[] pre = done.computeIfAbsent(shape.wait, this::prefix);
            if (pre[0] == null) return pre[3] == null ? null : ((Sim) pre[3]).copy();
            Sim s = ((Sim) pre[0]).copy();
            Reflex p = (Reflex) make(f);
            ((GroundPolicy) p.inner).t = (Float) pre[2];
            return trialRun(s, p, a, tgt, (Integer) pre[4], (Integer) pre[1], n);
        }

        private Object[] prefix(float wait) {
            Sim s = trialStart(base, a, tgt, mc);
            int fallsBefore = s.setbacks();
            float floorY = trialFloor(s.course, a, tgt);
            Reflex p = (Reflex) make(() -> { GroundPolicy g = new GroundPolicy(a, b, dir, wait, 0f, 0f, 0); g.probe = true; return g; });
            InputState in = new InputState();
            for (int i = 0; i < n; i++) {
                in.clear();
                p.act(s, in);
                GroundPolicy g = (GroundPolicy) p.inner;
                if (g.diverged) return new Object[]{s, i, g.t, null, fallsBefore};
                s.step(in);
                int end = trialEnded(s, a, tgt, fallsBefore, floorY);
                if (end != 0) return new Object[]{null, i, 0f, end > 0 ? finishArrival(s, a, tgt, fallsBefore) : null, fallsBefore};
            }
            return new Object[]{null, n, 0f, null, fallsBefore};
        }
    }

    /** The trial for candidate {@code f}, through the shared wait when it is a plain waiting ground move. */
    private static Sim trialEnd(Sim base, int a, int tgt, PolicyFactory f, float limit, WaitPrefix wp) {
        if (wp != null) { Policy probe = f.create(); if (probe instanceof GroundPolicy && ((GroundPolicy) probe).wait > 0f) return wp.trial((GroundPolicy) probe, f); }
        return trialEnd(base, a, tgt, f, limit, wp == null ? null : wp.mc);
    }

    /** True if, after finishing a pull-up, the planner can also solve the next link from this state. */
    static boolean leavesGoodState(Sim end, int a) {
        Sim s = end.copy();
        InputState in = new InputState(); Policy pull = new PullPolicy();
        for (int g = 0; g < 120 && (s.mode == Sim.Mode.LEDGE || s.mode == Sim.Mode.PULLUP); g++) { in.clear(); pull.act(s, in); s.step(in); }
        int na = s.mode == Sim.Mode.AIR ? s.lastPad : s.onElem;
        if (na < 0 || na >= s.course.goalIndex()) return true;
        return plan(s, na, false, false).ok;
    }

    /** Plan the next link from the state in {@code base} where the player is attached to route element a. */
    public static Result plan(Sim base, int a, boolean measureMargin) { return plan(base, a, measureMargin, false); }

    /** With lookahead, prefers moves that leave a state from which the next link is also solvable (used when actually playing the course). */
    public static Result plan(Sim base, int a, boolean measureMargin, boolean lookahead) { return planTo(base, a, base.course.nextRoute(a), false, measureMargin, lookahead); }

    /** Plans a link between any two elements (not just consecutive route elements): key-room detours. */
    public static Result planPair(Sim base, int a, int b, boolean measureMargin) { return planTo(base, a, b, true, measureMargin, false); }

    static Result planTo(Sim base, int a, int b, boolean pair, boolean measureMargin, boolean lookahead) {
        Course c = base.course;
        Result r = new Result();
        final int tgt = pair ? b : -1;
        if (!pair && b >= c.routeSize()) { r.ok = true; return r; }
        List<PolicyFactory> cands = new ArrayList<>(), slowCands = null;
        List<Integer> group = new ArrayList<>();      // move-style id per candidate (offset x hold x steering), for the timing-window metric
        // move-style ids for the timing-window metric (DC7: each physically distinct style its own id; ids used to collide across families)
        int[] groupWins = new int[G_COUNT]; int nWaits = 1;
        int dir = c.dsWrap(base.es1[b], base.es1[a]) >= 0 ? 1 : -1;
        boolean hangWaits = (base.mode == Sim.Mode.ROPE || base.mode == Sim.Mode.CABLE) && movingNear(c, a);
        int hw = hangWaits ? ROPE_WAITS : 1; float hstep = hangWaits ? maxPeriod(c, a) / ROPE_WAITS : 0f;
        if (c.get(a).type == Element.Type.ROPE) {
            // DC7: on a rope next to something that moves, the move is also tried after waiting there (sampled over the period), as the run does
            float[] deltas = {0f, 0.5f, 1.0f, 1.6f, 2.4f, 3.4f};
            nWaits = hw;
            for (int w = 0; w < hw; w++) { final float wt = w * hstep; int st = 0;
                for (float d : deltas) for (float h : HOLDS) { cands.add(wt == 0f ? () -> new RopePolicy(a, b, dir, d, h) : () -> new WaitFirst(new RopePolicy(a, b, dir, d, h), wt)); group.add(G_ROPE + st++); } }
        } else if (c.get(a).type == Element.Type.CABLE) {
            float[] ks = {0f, 0.6f, 1.4f, 2.4f};
            nWaits = hw;
            for (int w = 0; w < hw; w++) { final float wt = w * hstep; int st = 0;
                for (float k : ks) for (boolean drop : new boolean[]{false, true}) { cands.add(wt == 0f ? () -> new CablePolicy(a, b, dir, k, drop) : () -> new WaitFirst(new CablePolicy(a, b, dir, k, drop), wt)); group.add(G_CABLE + st++); } }
        } else if (base.mode == Sim.Mode.AIR && (c.get(a).type == Element.Type.PAD || c.get(a).type == Element.Type.SPRING)) {
            for (int m = 0; m < 3; m++) for (int h = 0; h < 2; h++) { final int mm = m; final boolean hh = h == 0; cands.add(() -> new AirPolicy(b, mm, hh)); }
        } else {
            boolean moving = movingNear(c, a);
            int waits = moving ? Math.min(24, (int) Math.ceil(maxPeriod(c, a) / 0.3f) + 1) : 1;
            nWaits = waits;
            for (int w = 0; w < waits; w++) {
                final float wt = w * 0.3f;
                for (int oi = 0; oi < OFFSETS.length; oi++) for (int hi = 0; hi < HOLDS.length; hi++) for (int sm = 0; sm < 4; sm += 1) {
                    if (sm == 2) continue;
                    final float fo = OFFSETS[oi], fh = HOLDS[hi]; final int fsm = sm;
                    cands.add(() -> new GroundPolicy(a, b, dir, wt, fo, fh, fsm));
                    group.add(G_GROUND + oi * 8 + hi * 4 + sm);
                }
            }
        }
        if (base.mode == Sim.Mode.GROUND && c.get(a).isPlatform() && c.get(b).isPlatform() && c.get(b).y < c.get(a).y - 0.3f) {      // heading down: just walk off the edge
            for (int w = 0; w < nWaits; w++) for (int sm : new int[]{0, 1, 3}) {
                final float wt = w * 0.3f; final int fsm = sm;
                cands.add(() -> new WalkOffPolicy(b, dir, wt, fsm));
                group.add(G_WALKOFF + sm);
            }
        }
        if (base.mode == Sim.Mode.GROUND && c.get(a).holed()) {           // Build 79: a bridge that drops planks: hop the holes (tried first: a plain run falls through)
            int at = 0;
            int hh = 0;
            for (float hd : new float[]{0.15f, 0.45f, 0.8f}) for (int sm : new int[]{0, 3}) { final float fhd = hd; final int fsm = sm; cands.add(at, () -> new HoleHopPolicy(a, b, dir, fhd, fsm)); group.add(at++, G_HOLEHOP + hh++); }
        }
        if (base.mode == Sim.Mode.GROUND && c.get(a).isPlatform()) {      // a hazard in the middle of this platform: hop-over moves
            for (int hi = 0; hi < c.hazards.size(); hi++) {
                Element h = c.hazards.get(hi);
                if (h.anchor != anchorIdx(c, a) || !(h.type == Element.Type.SAW_H || h.type == Element.Type.SPIKE_BLOCK || h.type == Element.Type.SPIKE_TRAP || h.type == Element.Type.CRAB)) continue;
                if (Math.abs(c.dsWrap(h.s, base.es1[a])) >= c.get(a).halfW()) continue;
                final float hx = base.es1[a] + c.dsWrap(h.s, base.es1[a]);
                final int crab = h.type == Element.Type.CRAB ? hi : -1;
                float[] hops = {1.0f, 1.5f, 2.0f, 2.5f};
                for (int w = 0; w < nWaits; w++) for (int hdi = 0; hdi < hops.length; hdi++) for (int sm = 0; sm < 2; sm++) {
                    final float wt = w * 0.3f, fhd = hops[hdi]; final int fsm = sm == 0 ? 0 : 3;
                    cands.add(() -> new MidHopPolicy(a, b, dir, wt, fhd, hx, 0f, fsm, crab));
                    group.add(G_MIDHOP + hdi * 2 + sm);          // (each steer variant its own style)
                }
                break;
            }
        }
        if (SLOW_APPROACH.get() && base.mode == Sim.Mode.GROUND && !pair && c.get(a).isPlatform() && !(movingNear(c, a))) {
            if (slowCands == null) slowCands = new ArrayList<>();
            for (int hi = 0; hi < c.hazards.size(); hi++) {          // hop the hazard in the middle, then ease off for the take-off
                Element h = c.hazards.get(hi);
                if (h.anchor != anchorIdx(c, a) || !(h.type == Element.Type.SAW_H || h.type == Element.Type.SPIKE_BLOCK || h.type == Element.Type.SPIKE_TRAP || h.type == Element.Type.CRAB)) continue;
                if (Math.abs(c.dsWrap(h.s, base.es1[a])) >= c.get(a).halfW()) continue;
                final float hx = base.es1[a] + c.dsWrap(h.s, base.es1[a]);
                final int crab = h.type == Element.Type.CRAB ? hi : -1;
                for (float rs : new float[]{0.5f, 0.3f}) for (float hd : new float[]{1.0f, 1.5f, 2.0f, 2.5f}) for (int sm = 0; sm < 2; sm++) {
                    final float fhd = hd, frs = rs; final int fsm = sm == 0 ? 0 : 3;
                    slowCands.add(() -> new MidHopPolicy(a, b, dir, 0f, fhd, hx, 0f, fsm, frs, crab));
                }
                break;
            }   // a gentle approach: a person can walk up slowly and make a short, controlled hop where a full-speed run-up would overshoot or catch the wrong ledge
            for (float sp : new float[]{0.5f, 0.3f}) for (int oi = 0; oi < OFFSETS.length; oi++) for (int hi = 0; hi < HOLDS.length; hi++) for (int sm = 0; sm < 4; sm++) {
                if (sm == 2) continue;
                final float fo = OFFSETS[oi], fh = HOLDS[hi], fs = sp; final int fsm = sm;
                slowCands.add(() -> { GroundPolicy g = new GroundPolicy(a, b, dir, 0f, fo, fh, fsm); g.speed = fs; return g; });
            }
        }
        PolicyFactory firstOk = null;
        WaitPrefix wp = new WaitPrefix(base, a, b, tgt, dir, 4.5f + (c.get(a).crumbles() ? 0 : 6f));
        for (int ci = 0; ci < cands.size(); ci++) {
            PolicyFactory f = cands.get(ci);
            r.trials++;
            float limit = 4.5f + (c.get(a).crumbles() ? 0 : 6f);
            if (lookahead) {
                Sim end = trialEnd(base, a, tgt, f, limit, wp);
                if (end == null) continue;
                r.successes++;
                if (firstOk == null) firstOk = f;
                if (!r.ok && leavesGoodState(end, a)) { r.ok = true; r.factory = f; }
                if (r.ok && !measureMargin) break;
            } else if (trialEnd(base, a, tgt, f, limit, wp) != null) {
                r.successes++;
                if (ci < group.size()) groupWins[group.get(ci)]++;
                if (!r.ok) { r.ok = true; r.factory = f; }
                if (!measureMargin) break;
            }
        }
        if (!r.ok && slowCands != null) {
            for (PolicyFactory f : slowCands) {
                r.trials++;
                Sim end = trialEnd(base, a, tgt, f, 4.5f + (c.get(a).crumbles() ? 0 : 6f), wp);
                if (end == null) continue;
                r.successes++;
                if (!lookahead || leavesGoodState(end, a)) { r.ok = true; r.factory = f; break; }      // while playing, a slow move that also suits the next link beats a working one that does not
                if (firstOk == null) firstOk = f;
            }
        }
        if (measureMargin && !group.isEmpty()) { int best = 0; for (int gw : groupWins) best = Math.max(best, gw); r.window = best / (float) nWaits; }
        if (lookahead && !r.ok) r.fallback = firstOk;          // (DC1: the caller decides whether a move with no good follow-up will do; ok means a good one)
        return r;
    }

    /**
     * The run's (and chainClean's) choice: a move that leaves a state the next link can be made from, else any working move, flagged in
     * {@link Result#fallback}. One search instead of the lookahead search followed by an identical plain one (the second found the same first move).
     */
    static Result planRun(Sim real, int a) {
        Result r = plan(real, a, false, true);
        if (!r.ok && r.fallback != null) { r.ok = true; r.factory = r.fallback; }
        return r;
    }

    /** Walks to {@code toS} on the platform underfoot and lets go to stop (at most {@code brake} steps), as the recovery walks do. */
    static void walkAndBrake(Sim sim, float toS, int brake) {
        Course c = sim.course; InputState in = new InputState();
        for (int q = 0; q < 240 && sim.mode == Sim.Mode.GROUND; q++) {
            float d = c.dsWrap(toS, sim.s);
            if (Math.abs(d) < 0.45f && Math.abs(sim.vx) < 0.4f) break;
            in.clear(); in.moveX = walkTo(d); sim.step(in);
        }
        for (int q = 0; q < brake && sim.mode == Sim.Mode.GROUND && Math.abs(sim.vx) > 0.05f; q++) { in.clear(); sim.step(in); }
    }
    /** Stands still for {@code n} steps (no input), stopping at a setback. */
    static void idle(Sim sim, int n) { InputState in = new InputState(); int sb = sim.setbacks(); for (int q = 0; q < n && sim.setbacks() == sb; q++) { in.clear(); sim.step(in); } }
    /** Waits {@code waitTicks} (hazard timing), walks to {@code toS} on platform a and brakes, then plans the next link from there; null if that leaves the platform or costs a setback. */
    static Result repositionPlan(Sim sim, int a, float toS, int waitTicks) {
        Course c = sim.course; InputState in = new InputState(); int sb = sim.setbacks();
        for (int q = 0; q < waitTicks && sim.mode == Sim.Mode.GROUND; q++) { in.clear(); sim.step(in); }
        for (int q = 0; q < 240 && sim.mode == Sim.Mode.GROUND; q++) {
            float d = c.dsWrap(toS, sim.s);
            if (Math.abs(d) < 0.45f && Math.abs(sim.vx) < 0.4f) break;
            in.clear(); in.moveX = walkTo(d); sim.step(in);
        }
        for (int q = 0; q < 40 && sim.mode == Sim.Mode.GROUND && Math.abs(sim.vx) > 0.05f; q++) { in.clear(); sim.step(in); }
        if (sim.mode != Sim.Mode.GROUND || sim.onElem != a || sim.setbacks() != sb) return null;
        return plan(sim, a, false, false);
    }

    /**
     * The generator's fairness proof for a pursuing creature's encounter (PUNISHER Enforcer, 2026-10-27): from the proven standing start on route
     * element {@code from}, play every link up to {@code to} the way the solver does - plan (preferring moves that suit the next link), execute on the
     * real simulation with every hazard of the course active, pull up a caught ledge, carry on from the route element landed on - and require that no
     * hit or fall happens and the route is never lost. A creature's shove that still lands on the route is no failure; one that throws the climber off it is.
     */
    public static boolean chainClean(Course c, Tuning t, int from, int to) { return chainClean(c, t, from, to, 0f); }
    /**
     * As {@link #chainClean(Course, Tuning, int, int)}, but the climber first stands {@code wait} seconds on deck {@code from} (rules 14: a NOT GONNA HAPPEN
     * avalanche is proven for several arrival times, because its long-lived blocks meet movers and creatures in whatever phase the real climb brings;
     * NGH seed 1 slice 10 on 27603e4 passed the one-arrival proof and failed the whole-slice solver). Standing there must itself be clean.
     */
    public static boolean chainClean(Course c, Tuning t, int from, int to, float wait) {
        Sim real = CourseGenerator.provenStart(c, t, from);
        real.humanHold = HUMAN_HOLD; real.act = null; real.hz = null;
        InputState in = new InputState(); int sb = real.setbacks(), a = from;
        for (int q = 0; q < (int) (wait / Sim.DT); q++) { in.clear(); real.step(in); if (real.setbacks() != sb) return false; }
        while (a < to) {
            for (int q = 0; q < 120 && (real.mode == Sim.Mode.LEDGE || real.mode == Sim.Mode.PULLUP); q++) { in.clear(); in.moveY = 1f; real.step(in); }
            for (int q = 0; q < 120 && real.mode == Sim.Mode.AIR; q++) { in.clear(); real.step(in); }          // (a shove's flight: it lands somewhere)
            if (real.setbacks() != sb || !(real.mode == Sim.Mode.GROUND || real.mode == Sim.Mode.ROPE || real.mode == Sim.Mode.CABLE) || !onRoute(c, real.onElem) || real.onElem < a) return false;
            if (real.onElem > a) { a = real.onElem; continue; }
            Result r = planRun(real, a);
            if (!r.ok) return false;
            Policy p = make(r.factory);
            for (int guard = (int) (20f / Sim.DT); guard > 0 && !succeeded(real, a); guard--) { in.clear(); p.act(real, in); real.step(in); if (real.setbacks() != sb) return false; }
            if (!succeeded(real, a)) return false;
            a = real.mode == Sim.Mode.AIR ? real.lastPad : real.onElem;
        }
        return real.setbacks() == sb;
    }

    /** True if the move reaches the next link on a copy with every hazard of the course active (no planning window). */
    static boolean clearOfEverything(Sim real, int a, PolicyFactory f) {
        Sim s = real.copy(); s.act = null; s.hz = null; s.humanHold = HUMAN_HOLD;
        Policy p = make(f); InputState in = new InputState(); int sb = s.setbacks();
        for (int i = 0; i < (int) (20f / Sim.DT); i++) {
            in.clear(); p.act(s, in); s.step(in);
            if (s.setbacks() != sb) return false;
            if (succeeded(s, a)) {
                if (s.mode == Sim.Mode.AIR && launcher(s.course, s.lastPad)) return launchClear(s, s.lastPad, sb);
                // caught its ledge: pull up first, as the run does, and the pull-up must be clear too (DC2; seed 1 slice 45 caught crumbling mover 48)
                for (int q = 0; q < 120 && (s.mode == Sim.Mode.LEDGE || s.mode == Sim.Mode.PULLUP); q++) { in.clear(); in.moveY = 1f; s.step(in); if (s.setbacks() != sb) return false; }
                if (s.mode == Sim.Mode.AIR) return false;          // let go (its deck went): not an arrival
                if (s.mode == Sim.Mode.GROUND && s.onElem >= 0 && s.course.get(s.onElem).crumbles() && onRoute(s.course, s.onElem)) return crumbleClear(s, s.onElem, sb);
                return true;
            }
        }
        return false;     // DC1: undecided within 20 s is not clear (it used to count as clear and let the run find out)
    }
    /** A pad or spring: landing on it launches you at once, so the move onto it is only clear if the launch it forces is too. */
    static boolean launcher(Course c, int b) { if (b < 0 || b >= c.size()) return false; Element.Type ty = c.get(b).type; return ty == Element.Type.PAD || ty == Element.Type.SPRING; }
    /**
     * Rules 14 (owner directive: whole-slice solvability, PUNISHER seed 2 slice 19 on cbe6415): the move onto a launcher is only half the move - the
     * launch follows without a choice, and an aimed cannon's ball met it right after take-off although waiting on the deck before gave a clean pass
     * (19 of 31 arrival times). The check follows the launch the way the run plays it (no input at first, a plan for the next link from the air every
     * 6 steps, then that plan) until the next link is reached, and the move is clear only without a setback on the way. Bounded like the run's own loop.
     */
    static boolean launchClear(Sim s, int b, int sb) {
        InputState in = new InputState(); Result r = null;
        for (int q = 0; q < 150 && s.mode == Sim.Mode.AIR && (r == null || !r.ok); q++) {
            in.clear(); s.step(in);
            if (s.setbacks() != sb) return false;
            if (succeeded(s, b)) return true;
            if (q % 6 == 5 && s.mode == Sim.Mode.AIR) r = plan(s, b, false, false);
        }
        if (r == null || !r.ok) return s.mode != Sim.Mode.AIR && s.setbacks() == sb && (onRoute(s.course, s.onElem) || branchOf(s.course, s.onElem) != null);          // DC1: clear only if it came down clean on the route (no plan found in flight is no longer "clear")
        return playClear(s, b, r, sb);
    }
    /**
     * The same for a crumbling deck (or crumbling mover): you cannot wait on it, so the move onto it is clear only if a move off it works from the
     * landing it makes, sized up there and up to 0.3 s later (PUNISHER seed 1 slice 45 on cbe6415: the solver jumped onto crumbling mover 48 the moment
     * it reached deck 47, found no move to deck 49 in that phase, and fell when the mover crumbled).
     */
    static boolean crumbleClear(Sim s, int b, int sb) {
        InputState in = new InputState(); Result r = null;
        for (int q = 0; q <= 18 && s.mode == Sim.Mode.GROUND && s.onElem == b; q++) {
            if (q % 6 == 0) { r = planRun(s, b); if (r.ok) break; }
            in.clear(); s.step(in);
            if (s.setbacks() != sb) return false;
        }
        if (r == null || !r.ok) return false;          // nothing leaves it before it goes: the move onto it is not clear now
        return playClear(s, b, r, sb);
    }
    private static boolean playClear(Sim s, int b, Result r, int sb) {
        InputState in = new InputState();
        Policy p = make(r.factory);
        for (int i = 0; i < (int) (10f / Sim.DT) && !succeeded(s, b); i++) { in.clear(); p.act(s, in); s.step(in); if (s.setbacks() != sb) return false; }
        return succeeded(s, b);          // DC1: 10 s without reaching it is not clear
    }

    static boolean crabDeck(Course c, int a) { for (Element h : c.hazards) if (h.anchor == a && h.type == Element.Type.CRAB) return true; return false; }
    static boolean hasMidHazard(Course c, int a) {
        Element p = c.get(a);
        if (!p.isPlatform()) return false;
        for (Element h : c.hazards) {
            if (h.anchor != a) continue;
            switch (h.type) { case SPIKE_TRAP: case SAW_H: case SPIKE_BLOCK: case SPIKE_DROP: case GATE: case CRAB: case SKULL: case BLOB: if (Math.abs(c.dsWrap(h.s, p.s)) < p.halfW()) return true; break; default: break; }
        }
        return false;
    }

    // ------------------------------------------------------------------ key-room detours

    /** Plans and executes one link between two arbitrary elements on the real sim; false if it cannot or the player falls/gets hurt. */
    static boolean execLink(Sim real, int a, int b, InputState in) {
        real.act = null; real.hz = null;
        Result r = planPair(real, a, b, false);
        if (!r.ok && real.mode == Sim.Mode.GROUND && real.onElem == a) {       // like a person: shuffle along the platform and size the move up again from another spot
            Course c = real.course; float room = c.get(a).halfW() - 0.4f;
            for (float off : new float[]{-0.3f, 0.3f, -0.6f, 0.6f, -0.9f, 0.9f, -1.3f, 1.3f}) {
                if (Math.abs(off) > room) continue;
                float toS = c.wrap(real.es1[a] + off);
                for (int q = 0; q < 240 && real.mode == Sim.Mode.GROUND; q++) {
                    float d = c.dsWrap(toS, real.s);
                    if (Math.abs(d) < 0.45f && Math.abs(real.vx) < 0.4f) break;
                    in.clear(); in.moveX = walkTo(d); real.step(in);
                }
                for (int q = 0; q < 40 && real.mode == Sim.Mode.GROUND && Math.abs(real.vx) > 0.05f; q++) { in.clear(); real.step(in); }
                if (real.mode != Sim.Mode.GROUND || real.onElem != a) break;
                r = planPair(real, a, b, false);
                if (r.ok) break;
            }
        }
        if (!r.ok) { if (Boolean.getBoolean("dbg")) System.out.printf("execLink %d->%d plan failed at t=%.2f s=%.2f y=%.2f keys=%d%n", a, b, real.time, real.s, real.y, real.keys); return false; }
        Policy p = make(r.factory); Policy pull = new PullPolicy();
        int f0 = real.setbacks(); int guard = (int) (14f / Sim.DT);
        while (guard-- > 0 && !succeededTo(real, b)) { in.clear(); p.act(real, in); real.step(in); if (real.setbacks() != f0) { if (Boolean.getBoolean("dbg")) System.out.printf("execLink %d->%d setback during exec: mode=%s s=%.2f y=%.2f t=%.2f events=%d hits=%d falls=%d%n", a, b, real.mode, real.s, real.y, real.time, real.events, real.hits, real.falls); return false; } }
        if (!succeededTo(real, b)) { if (Boolean.getBoolean("dbg")) System.out.printf("execLink %d->%d exec failed mode=%s s=%.2f y=%.2f falls=%d%n", a, b, real.mode, real.s, real.y, real.setbacks() - f0); return false; }
        for (int g = 0; g < 120 && (real.mode == Sim.Mode.LEDGE || real.mode == Sim.Mode.PULLUP); g++) { in.clear(); pull.act(real, in); real.step(in); if (real.setbacks() != f0) return false; }
        return real.mode == Sim.Mode.GROUND;
    }

    /** Fetches a key and comes back to the anchor platform. kr = {anchor, first branch platform, count, mode, key hazard, gate hazard}; mode 0: chain of platforms, 1: legacy room with a pad back up, 2: the key lies on the anchor platform itself, 3: pad then a perch above. */
    static String why = "";
    static boolean detour(Sim real, int[] kr, InputState in) {
        Course c = real.course; int r = kr[0], first = kr[1], cnt = Math.max(1, kr[2]), mode = kr[3];
        Element key = c.hazards.get(kr[4]);
        settle(real, r, in);                                                      // arrive, brake and line up in the middle, then plan the detour from a standstill
        int[] out = mode == 2 ? new int[0] : mode == 3 ? new int[]{first, first + 1} : mode == 1 ? new int[]{first} : chain(first, cnt);
        int prev = r;
        for (int idx : out) { why = "out"; if (!execLink(real, prev, idx, in)) return false; prev = idx; }
        for (int q = 0; q < 40 && Math.abs(real.vx) > 0.05f && real.mode == Sim.Mode.GROUND; q++) { in.clear(); real.step(in); }      // brake after landing
        int f0 = real.setbacks(); int guard = (int) (6f / Sim.DT);
        while (guard-- > 0 && (real.keys & (1 << key.color)) == 0) {            // walk across the key platform to the key
            in.clear(); float dx = c.dsWrap(key.s, real.s); in.moveX = Math.abs(dx) < 0.1f ? 0f : Math.signum(dx); real.step(in);
            if (real.setbacks() != f0) { why = "fell on key platform"; return false; }
        }
        if ((real.keys & (1 << key.color)) == 0) { why = "key not taken"; return false; }
        for (int q = 0; q < 24; q++) { in.clear(); real.step(in); }          // come to a stop before heading back
        boolean back = true;
        if (mode == 1) back = execLink(real, first, first + 1, in) && execLink(real, first + 1, r, in);
        else if (mode == 3) back = execLink(real, first + 1, r, in);
        else if (mode == 0) { for (int i = out.length - 1; i >= 0 && back; i--) back = execLink(real, out[i], i == 0 ? r : out[i - 1], in); }
        if (!back) { why = "back"; return false; }
        for (int q = 0; q < 180 && real.mode == Sim.Mode.GROUND && real.onElem == r; q++) {      // walk back to the middle of the platform and stop
            float dx = c.dsWrap(real.es1[r], real.s); in.clear();
            if (Math.abs(dx) < 0.25f) break;
            in.moveX = Math.signum(dx) * 0.7f; real.step(in);
        }
        for (int q = 0; q < 24; q++) { in.clear(); real.step(in); }
        if (real.mode != Sim.Mode.GROUND) recover(real, r, in);      // like a person: knocked off by a hazard on the way back, so land and carry on from wherever that is (the key is already in hand)
        if (real.mode == Sim.Mode.GROUND && onRoute(c, real.onElem)) return true;
        why = "back on the route platform"; return false;
    }
    /** Like a person arriving on a platform with momentum: counter-steer to brake and drift to the middle, then stand still. */
    static void settle(Sim real, int r, InputState in) {
        Course c = real.course;
        for (int q = 0; q < 240 && real.mode == Sim.Mode.GROUND && real.onElem == r; q++) {
            float dx = c.dsWrap(real.es1[r], real.s);
            if (Math.abs(dx) < 0.2f && Math.abs(real.vx) < 0.3f) break;
            in.clear(); in.moveX = Math.max(-1f, Math.min(1f, 1.5f * dx - 0.35f * real.vx)); real.step(in);
        }
        for (int q = 0; q < 24 && real.mode == Sim.Mode.GROUND && real.onElem == r; q++) { in.clear(); real.step(in); }
    }
    /** Knocked into the air by a bee or a crab (nothing to do with a planned move): steer back towards the platform we meant to stand on until something is under our feet. True if we landed on a route platform without a setback. */
    /** Standing on a spike slab's top: the way off it, toward the middle of the platform the slab hangs over (onto its deck; walking on toward the route ahead
     *  walked into whatever the next platform holds - NGH seed 2 slice 1, crabs on an intermediate platform, a 15 m fall). */
    static float slabStepOff(Sim real) {
        Course c = real.course; int p = c.hazards.get(real.onSlab).anchor;
        if (p < 0 || p >= c.size()) return -real.facing;
        float d = c.dsWrap(real.es1[p], real.s);
        return Math.abs(d) < 0.3f ? -1f : Math.signum(d);
    }
    static boolean recover(Sim real, int a, InputState in) {
        // a fall (back to the checkpoint) ends the recovery; another knock while still in the air does not: like a person, keep steering and land
        // (2026-10-26 campaign: four solver failures were a second bump during recovery, reported as "plan failed" mid-knock-back at 6.9/6.9)
        Course c = real.course; int f0 = real.falls;
        for (int q = 0; q < 360 && (real.mode != Sim.Mode.GROUND || (real.onElem < 0 && real.onSlab >= 0)); q++) {      // a spike slab's top is a surface, not the route: step off it onto its platform
            in.clear();
            if (real.mode == Sim.Mode.GROUND) in.moveX = slabStepOff(real);
            else if (real.mode == Sim.Mode.LEDGE || real.mode == Sim.Mode.PULLUP) in.moveY = 1f;
            else if (real.mode == Sim.Mode.AIR) in.moveX = Math.max(-1f, Math.min(1f, 1.5f * c.dsWrap(real.es1[a], real.s)));
            real.step(in);
            if (real.falls != f0) return false;
        }
        return real.mode == Sim.Mode.GROUND && (onRoute(c, real.onElem) || branchOf(c, real.onElem) != null);
    }
    /** The fork whose branch holds element {@code i} ({start, first branch element, count, rejoin}), or null. */
    static int[] branchOf(Course c, int i) { if (i < 0) return null; for (int[] fk : c.forks) if (i >= fk[1] && i < fk[1] + fk[2]) return fk; return null; }
    /** Carries on from a branch element along fork {@code fk}'s branch to its rejoin (planned pair moves, as the key detours make); true if back on the route clean. */
    static boolean followBranch(Sim real, int[] fk, InputState in) {
        Course c = real.course; int f0 = real.setbacks();
        for (int g = 0; g < fk[2] + 2; g++) {
            int at = real.mode == Sim.Mode.AIR ? real.lastPad : real.onElem;          // (a branch pad has just launched us: the next move is planned from it)
            if (real.setbacks() != f0) return false;
            if (branchOf(c, at) != fk) return onRoute(c, at) && real.mode != Sim.Mode.AIR;
            if (real.mode == Sim.Mode.GROUND) { settle(real, at, in); if (real.onElem != at) continue; }
            int next = at + 1 < fk[1] + fk[2] ? at + 1 : fk[3];
            if (real.mode == Sim.Mode.AIR && launcher(c, at)) {          // just launched by a branch pad: let it carry us, then steer for the next one (as the run does)
                Result r = null;
                for (int q = 0; q < 150 && real.mode == Sim.Mode.AIR; q++) {
                    if (q % 6 == 0) { r = planPair(real, at, next, false); if (r.ok) break; }          // at once first, as the run does: the launch is where the steering starts
                    in.clear(); real.step(in);
                    if (real.setbacks() != f0) return false;
                }
                if (r != null && r.ok) { Policy p = make(r.factory); for (int i = 0; i < (int) (10f / Sim.DT) && !succeededTo(real, next); i++) { in.clear(); p.act(real, in); real.step(in); if (real.setbacks() != f0) return false; } }
                for (int q = 0; q < 120 && (real.mode == Sim.Mode.LEDGE || real.mode == Sim.Mode.PULLUP); q++) { in.clear(); in.moveY = 1f; real.step(in); }
                continue;
            }
            if (!execLink(real, at, next, in)) return false;
        }
        return onRoute(c, real.onElem) && real.setbacks() == f0;
    }
    /**
     * Solver recovery repair (owner authorisation 2026-10-28, SolverRecoveryTest conveyor fixture): a repositioning (a brake, a walk, a lineup, a wait)
     * is first played on an exact copy of the live climb, every hazard of the course active, the way a planned move is checked ({@link #clearOfEverything});
     * it is played for real only if the copy takes no hit and no fall, so a recovery never walks or stands into a hazard. Otherwise the climber stays
     * where it is and the next option is tried. The copy is exact, so a recovery played for real is the one that was rehearsed (a run that recovered
     * cleanly before plays exactly as before; one that did not was already a failed witness). True if it was played.
     */
    static boolean rehearsed(Sim real, java.util.function.Consumer<Sim> move) {
        Sim s = real.copy(); int sb = s.setbacks();
        move.accept(s);
        if (s.setbacks() != sb) return false;
        move.accept(real);
        return true;
    }
    /** True if, from this state, a plan for link {@code a} exists and it reaches the link clean with every hazard active (on a copy; the climb is not moved). */
    static boolean cleanMoveFrom(Sim at, int a) {
        Sim s = at.copy(); Result r = planRun(s, a);
        return r.ok && clearOfEverything(s, a, r.factory);
    }
    /** True where the run lines the climber up before the balance conveyor that follows platform {@code b} (the same test as the run's lineup). */
    static boolean lineupAhead(Course c, int b) {
        if (b < 0 || b >= c.size()) return false;
        int nx = c.nextRoute(b);
        return nx < c.size() && c.get(nx).tiltBelt() && c.get(b).isPlatform() && !c.get(b).isMoving() && !hasMidHazard(c, b);
    }
    /**
     * The move for link {@code a}, played on a copy as {@link #clearOfEverything} plays it, lands on platform {@code b} where the run lines up for a
     * balance conveyor: true if from that landing either the lineup is clean and leaves a clean move, or the move straight from the landing is clean
     * (the choice the run makes there). A landing anywhere else is not judged here (true).
     */
    static boolean continuationClean(Sim real, int a, PolicyFactory f) {
        Sim s = real.copy(); s.act = null; s.hz = null; s.humanHold = HUMAN_HOLD;
        s = arrival(s, a, f);
        if (s == null) return false;
        int b = s.onElem;
        if (s.mode != Sim.Mode.GROUND || b < 0 || b != s.course.nextRoute(a) || !lineupAhead(s.course, b)) return true;
        Sim lined = s.copy(); int sb = lined.setbacks(); lined.restartHold();
        standAt(lined, lined.es1[b], new InputState()); if (lined.mode == Sim.Mode.GROUND && lined.onElem == b) lined.restartHold();
        if (lined.setbacks() == sb && lined.mode == Sim.Mode.GROUND && lined.onElem == b && beltReady(lined, b)) return true;
        return beltReady(s, b);
    }
    /** Plays the move for link {@code a} on {@code s} (in place) to its arrival, a caught ledge pulled up; null if it takes a setback or never arrives within 20 s. */
    static Sim arrival(Sim s, int a, PolicyFactory f) {
        Policy p = make(f); InputState in = new InputState(); int sb = s.setbacks();
        for (int i = 0; i < (int) (20f / Sim.DT) && !succeeded(s, a); i++) { in.clear(); p.act(s, in); s.step(in); if (s.setbacks() != sb) return null; }
        if (!succeeded(s, a)) return null;
        for (int q = 0; q < 120 && (s.mode == Sim.Mode.LEDGE || s.mode == Sim.Mode.PULLUP); q++) { in.clear(); in.moveY = 1f; s.step(in); if (s.setbacks() != sb) return null; }
        return s.setbacks() == sb ? s : null;
    }
    /**
     * From standing on platform {@code b} before a balance conveyor: the move onto the conveyor is clean and lands where the conveyor's own move is
     * clean too (landing on the conveyor is not enough: on its near end it tips down and slides you off, the reason for the lineup).
     */
    static boolean beltReady(Sim at, int b) {
        Sim s = at.copy(); Result r = planRun(s, b);
        if (!r.ok || !clearOfEverything(s, b, r.factory)) return false;
        s.act = null; s.hz = null; s.humanHold = HUMAN_HOLD;
        Sim on = arrival(s, b, r.factory);
        int nx = s.course.nextRoute(b);
        return on != null && on.mode == Sim.Mode.GROUND && on.onElem == nx && cleanMoveFrom(on, nx);
    }
    /** Stops within a few cm of {@code toS} on the platform underfoot the way a person does with a direction-only stick: back off if too close to stop cleanly, run in, let go at the stopping distance. */
    static void standAt(Sim real, float toS, InputState in) {
        Course c = real.course; int a = real.onElem; Element el = c.get(a);
        float room = Math.max(0f, el.halfW() - 0.35f);
        for (int tries = 0; tries < 3 && real.mode == Sim.Mode.GROUND && real.onElem == a; tries++) {
            float d = c.dsWrap(toS, real.s);
            if (Math.abs(d) < 0.07f) return;
            if (Math.abs(d) < 0.5f) {           // too close for a clean stop: step back first
                float off = Math.max(-room, Math.min(room, c.dsWrap(toS, real.es1[a]) - Math.signum(d) * 0.8f)), back = c.wrap(real.es1[a] + off);
                for (int q = 0; q < 120 && real.mode == Sim.Mode.GROUND && real.onElem == a; q++) { float db = c.dsWrap(back, real.s); if (Math.abs(db) < 0.3f) break; in.clear(); in.moveX = Math.signum(db); real.step(in); }
                for (int q = 0; q < 40 && real.mode == Sim.Mode.GROUND && Math.abs(real.vx) > 0.02f; q++) { in.clear(); real.step(in); }
            }
            for (int q = 0; q < 240 && real.mode == Sim.Mode.GROUND && real.onElem == a; q++) {
                float dd = c.dsWrap(toS, real.s), stop = real.vx * real.vx / (2f * real.T.groundDecel);
                if (Math.abs(dd) < 0.07f || (Math.signum(real.vx) == Math.signum(dd) && Math.abs(dd) <= stop + 0.04f)) break;
                in.clear(); in.moveX = Math.signum(dd); real.step(in);
            }
            for (int q = 0; q < 40 && real.mode == Sim.Mode.GROUND && Math.abs(real.vx) > 0.02f; q++) { in.clear(); real.step(in); }
        }
    }
    private static int[] chain(int first, int n) { int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = first + i; return a; }

    /** True if the planner can get from route/decoy element a to element b and attach to it (the same check the generator proves, re-run on the finished data: used by the tests). */
    public static boolean linkExists(Course c, Tuning t, int a, int b) {
        Sim base = Sim.startOn(c, t, a);
        return planPair(base, a, b, false).ok;
    }

    // ------------------------------------------------------------------ whole-course run

    /** Only the whole-course solver (a stand-in for a person) may use slow approaches; the generator's own proofs stay on the standard move set so difficulty is unchanged. */
    static final ThreadLocal<Boolean> SLOW_APPROACH = ThreadLocal.withInitial(() -> false);
    static Sim lastReal;       // diagnostics: the live sim of the most recent run
    private static final boolean DBG = Boolean.getBoolean("dbg");   // diagnostics: trace the whole-course run (java -Ddbg=true)
    public static Report run(Course c, Tuning t, float maxSimSeconds) {
        SLOW_APPROACH.set(true);
        try { return runInner(c, t, maxSimSeconds); } finally { SLOW_APPROACH.set(false); }
    }
    private static Report runInner(Course c, Tuning t, float maxSimSeconds) {
        Report rep = new Report();
        Sim real = Sim.startOn(c, t, 0); lastReal = real; real.humanHold = HUMAN_HOLD;
        real.keysFree = false; real.keys = 0;       // the solver must fetch every key it needs
        for (Element g : c.hazards) if (g.type == Element.Type.GATE) {      // endless: a castle's key lies in an earlier section, carried here
            boolean own = false; for (Element k : c.hazards) if (k.type == Element.Type.KEY && k.color == g.color && k.anchor < g.anchor) own = true;     // colours repeat: a key found above a gate opens a later castle, not this one
            if (!own) real.keys |= 1 << g.color;
        }
        int a = 0;
        java.util.HashSet<Integer> visited = new java.util.HashSet<>();
        InputState in = new InputState();
        boolean endless = c.get(c.goalIndex()).type != Element.Type.GOAL;     // endless slices end on a rest platform, not a goal flag
        int lastA = -1, sameA = 0, stallA = -1, stalls = 0;
        String phase = "start";          // (diagnostics: what the run was doing when a setback came)
        while (!real.won && real.time < maxSimSeconds) {
            if (a == lastA) sameA++; else { lastA = a; sameA = 0; GenTrace.Solve st = GenTrace.SOLVE.get(); if (st != null) st.arrive(a, real.time, real.setbacks()); }      // (observe-only trace for reports)
            if (DBG) System.out.printf("loop a=%d same=%d mode=%s on=%d s=%.2f y=%.2f vx=%.2f vy=%.2f t=%.2f%n", a, sameA, real.mode, real.onElem, real.s, real.y, real.vx, real.vy, real.time);
            if (sameA > 30) return stop(rep, real, a, "stuck", "stuck at one link: knocked back again and again");
            // owner policy 2026-10-27 (witness cleanliness): the traversal witness is hit-free from start to goal, not only while a move is executed;
            // a hit or fall anywhere (waiting, pulling up, recovering, drifting off a pad) means no clean route was established here
            if (real.setbacks() != 0) return stop(rep, real, a, "setback", String.format("setback outside a move (%s): hits=%d falls=%d mode=%s s=%.2f y=%.2f", phase, real.hits, real.falls, real.mode, real.s, real.y) + (real.hitBy != null ? " lastHazard=" + real.hitBy.type + " anchor=" + real.hitBy.anchor : ""));
            int[] br = (real.mode == Sim.Mode.GROUND || real.mode == Sim.Mode.ROPE || real.mode == Sim.Mode.CABLE) ? branchOf(c, real.onElem) : null;
            if (br != null) {
                // like a person carried onto a fork's branch (a bee's shove, a pad's launch onto a later fork's branch): carry on along the branch to where it
                // rejoins the route (rules 14, whole-slice solvability: PUNISHER seed 2 slice 47 on cbe6415, seed 5 slice 64 on rules 13 b06dc71 - the
                // solver resumed only from route elements and gave up on a forward branch)
                if (!followBranch(real, br, in)) return stop(rep, real, a, "branch", String.format("lost on a fork branch %d-%d: mode=%s on=%d s=%.2f y=%.2f", br[1], br[1] + br[2] - 1, real.mode, real.onElem, real.s, real.y));
                a = resync(real, real.onElem); continue;
            }
            if (a < 0 || a >= c.routeSize()) return stop(rep, real, a, "lost_route", String.format("lost the route: link index %d outside the route (0..%d) mode=%s on=%d slab=%d s=%.2f y=%.2f", a, c.routeSize() - 1, real.mode, real.onElem, real.onSlab, real.s, real.y));      // never index with it (2026-10-26: seven IndexOutOfBoundsException -1)
            if (real.mode == Sim.Mode.GROUND && real.onElem < 0 && real.onSlab >= 0) {
                // like a person: standing on top of a spike slab (a surface: Sim lands on it, onElem -1), so step off it onto its own platform and land; the
                // AIR branch below then recovers onto whatever route platform that is. 2026-10-26 campaign: seven slices threw IndexOutOfBoundsException -1
                // because a move ending on a slab's top set the link to onElem (-1)
                float dir = slabStepOff(real);
                for (int q = 0; q < 120 && real.mode == Sim.Mode.GROUND && real.onElem < 0; q++) { in.clear(); in.moveX = dir; real.step(in); }
                if (real.mode == Sim.Mode.GROUND && real.onElem < 0) return stop(rep, real, a, "stuck", String.format("stuck on a spike slab top: could not step off in 2 s s=%.2f y=%.2f slab=%d", real.s, real.y, real.onSlab));
                continue;
            }
            if (endless && a >= c.goalIndex()) return finish(rep, real, a);
            for (int[] kr : c.keyRooms) {
                if (kr[0] != a) continue;
                if (real.mode != Sim.Mode.GROUND || real.onElem != a) break;          // (DC1: the detour is used up only once it actually starts)
                if (!visited.add(kr[0])) continue;
                phase = "detour";
                if (!detour(real, kr, in)) return stop(rep, real, a, "detour", "detour " + why + String.format(" mode=%s on=%d s=%.2f y=%.2f t=%.2f", real.mode, real.onElem, real.s, real.y, real.time));
                a = real.onElem;
            }
            if (real.mode == Sim.Mode.GROUND && real.onElem == a && hasMidHazard(c, a) && !crabDeck(c, a)) {   // like a person would: stop in the safe pocket before sizing up the hazard (not with crabs closing in: they shove whoever stands still, so the plan's crab hop goes straight from the landing; seed 7 slice 9)
                for (int q = 0; q < 40 && Math.abs(real.vx) > 0.05f && real.mode == Sim.Mode.GROUND; q++) { in.clear(); real.step(in); }
            }
            if (real.mode == Sim.Mode.BEAM && real.onElem == a && c.get(a).type == Element.Type.ROPE) {
                // like a person: knocked off a ledge back onto the rope and hauled up onto its top beam, so climb back down onto the rope and size the move up
                // again from there (the moves off a rope are planned from the rope). Build 77, seed 2 slice 55: an aimed cannon hit the pull-up onto the next
                // block; the loop only took the beam as "nothing to do" and went round again without a step until it gave up
                for (int q = 0; q < 120 && real.mode == Sim.Mode.BEAM; q++) { in.clear(); in.moveY = -1f; real.step(in); }
                for (int q = 0; q < 6 && real.mode == Sim.Mode.ROPE; q++) { in.clear(); real.step(in); }
                continue;
            }
            if (real.mode == Sim.Mode.AIR && !(real.lastPad == a && (c.get(a).type == Element.Type.PAD || c.get(a).type == Element.Type.SPRING))) {      // shoved into the air by a bee or crab: settle first
                phase = "recover";
                if (!recover(real, a, in)) return stop(rep, real, a, "recover", "recover " + String.format("mode=%s s=%.2f y=%.2f", real.mode, real.s, real.y));
                a = real.onElem;
                if (!hasMidHazard(c, a)) settle(real, a, in);     // a hazard in the middle (a crab that comes after you): no walking to the centre to settle, size the next move up from where we landed
                continue;
            }
            if (real.mode == Sim.Mode.GROUND && real.onElem != a && onRoute(c, real.onElem)) { rep.resyncs++; a = resync(real, real.onElem); continue; }      // like a person: landed on another route platform (not a fall), so carry on from there
            if ((real.mode == Sim.Mode.ROPE || real.mode == Sim.Mode.CABLE) && real.onElem != a && onRoute(c, real.onElem) && c.get(real.onElem).type == (real.mode == Sim.Mode.ROPE ? Element.Type.ROPE : Element.Type.CABLE)) { a = resync(real, real.onElem); continue; }      // or caught a route rope or cable on the way down (2026-10-26 GenBench: PUNISHER seed 2 slice 53, a crab shoved the climber off platform 96 onto rope 88): climb on from there
            if ((real.mode == Sim.Mode.LEDGE || real.mode == Sim.Mode.PULLUP) && real.onElem != a && onRoute(c, real.onElem)) {          // or caught the ledge of another route platform: pull up, then carry on from there (same course, the next shove)
                Policy pull = new PullPolicy(); int before = real.setbacks();
                for (int q = 0; q < 120 && (real.mode == Sim.Mode.LEDGE || real.mode == Sim.Mode.PULLUP) && real.setbacks() == before; q++) { in.clear(); pull.act(real, in); real.step(in); }
                if (real.mode == Sim.Mode.GROUND && onRoute(c, real.onElem)) { a = resync(real, real.onElem); continue; }
            }
            int nx = c.nextRoute(a);
            if (sameA == 0 && nx < c.size() && c.get(nx).tiltBelt() && real.mode == Sim.Mode.GROUND && real.onElem == a && c.get(a).isPlatform() && !c.get(a).isMoving() && !hasMidHazard(c, a)) {
                // like a person: line up for the balance conveyor where its proven jump starts (the platform's middle, standing, CourseGenerator.provenStart).
                // Its exit is proven only from the landing that jump makes; jumping on from wherever we arrived (a rope top, at speed) can land at the near end,
                // which tips down and slides you off it again and again (2026-10-27, NORMAL seed 1 slices 55 and 75)
                // (solver recovery repair 2026-10-28: the lineup is rehearsed with every hazard active; at NORMAL seed 1 slice 55 deck 88 the aimed cannon by
                // platform 85 hits whoever stands in the middle, at every wait, while the move from where the climber arrived is clean. The lineup is skipped
                // only when it gives no clean crossing and the arrival does (beltReady); otherwise it is played as before)
                final int la = a;
                java.util.function.Consumer<Sim> lineup = x -> { standAt(x, x.es1[la], new InputState()); if (x.mode == Sim.Mode.GROUND && x.onElem == la) x.restartHold(); };
                Sim lined = real.copy(); int lsb = lined.setbacks(); lineup.accept(lined);
                boolean lineupClean = lined.setbacks() == lsb && lined.mode == Sim.Mode.GROUND && lined.onElem == a && beltReady(lined, a);
                if (lineupClean || !beltReady(real, a)) rehearsed(real, lineup);
            }
            phase = "plan";
            Result r = planRun(real, a);        // no move leaves a state that suits the next link: take any that works and sort the next link out from there
            rep.links++;
            if (!r.ok) { GenTrace.Solve st = GenTrace.SOLVE.get(); if (st != null) st.planFail(a); }      // (observe-only trace for reports)
            if (!r.ok && Boolean.getBoolean("dbg")) System.out.printf("first plan failed at %d: mode=%s on=%d s=%.2f y=%.2f vx=%.2f vy=%.2f t=%.2f%n", a, real.mode, real.onElem, real.s, real.y, real.vx, real.vy, real.time);
            if (!r.ok && real.mode == Sim.Mode.GROUND && real.onElem == a && !hasMidHazard(c, a)) {      // like a person: arrived still moving, so brake, line up in the middle and size the jump up from a standstill
                final int sa = a; rehearsed(real, x -> settle(x, sa, new InputState()));
                if (real.mode == Sim.Mode.GROUND && real.onElem == a) r = planRun(real, a);
            }
            for (int tryBack = 0; !r.ok && tryBack < 2 && real.mode == Sim.Mode.GROUND && real.onElem == a && !hasMidHazard(c, a); tryBack++) {
                // like a person: walk back along the platform for a longer run-up, settle, and size up the jump again
                Element el = c.get(a);
                float toS = c.wrap(real.es1[a] - el.halfW() + (tryBack == 0 ? 0.35f : 0.8f));
                if (!rehearsed(real, x -> walkAndBrake(x, toS, 30))) break;          // (a walk back into a hazard is no run-up)
                if (real.mode != Sim.Mode.GROUND || real.onElem != a) break;
                r = planRun(real, a);
            }
            if (!r.ok && real.mode == Sim.Mode.GROUND && real.onElem == a && !hasMidHazard(c, a)) {
                // like a person: a shorter or slower approach from a different spot can be the only clean line (a full-speed run-up from the far end can overshoot or catch the wrong ledge)
                float room = c.get(a).halfW() - 0.4f;
                for (float off : new float[]{0f, 0.5f, 1.0f, -0.5f, 1.3f, -1.0f}) {
                    if (Math.abs(off) > room) continue;
                    float toS = c.wrap(real.es1[a] + off);
                    if (!rehearsed(real, x -> walkAndBrake(x, toS, 40))) continue;      // (that spot is not reached clean: try the next)
                    if (real.mode != Sim.Mode.GROUND || real.onElem != a) break;
                    r = plan(real, a, false, false);
                    if (r.ok) break;
                }
            }
            if (!r.ok && real.mode == Sim.Mode.GROUND && real.onElem == a && !hasMidHazard(c, a)) {
                // like a person: stand right where the jump was proven (the platform's middle) and repeat it as proven. The walks above stop anywhere within 0.45 m of
                // a spot, and a standing jump off a short step can need ~0.35 m and the proof's exact start timing (Build 77, seed 3 slice 54: castle 7's staircase,
                // a 2.5 m step that works from -0.2..+0.15 m; proofs start with the stick just let go, a long-idle solver moved 0.1 s sooner and missed)
                final int ma = a;
                if (rehearsed(real, x -> standAt(x, x.es1[ma], new InputState())) && real.mode == Sim.Mode.GROUND && real.onElem == a) { real.restartHold(); r = planRun(real, a); }      // and from the stick state the proof started in
            }
            if (!r.ok && real.mode == Sim.Mode.GROUND && real.onElem == a && hasMidHazard(c, a) && c.get(a).isPlatform()) {
                // like a person: a hazard in the middle of this platform, and the spot we arrived at gives no clean hop. Wait for a better moment, step to another spot on the near side and size it up again (tried on a copy first: the stand-in never walks into the hazard)
                float dirA = c.dsWrap(real.es1[c.nextRoute(a)], real.es1[a]) >= 0 ? 1f : -1f;
                search:
                for (float off : new float[]{1.0f, 0.6f, 1.4f, 0.8f, 1.8f, 2.4f}) {
                    if (off > c.get(a).halfW() - 0.3f) continue;
                    float toS = c.wrap(real.es1[a] - dirA * off);
                    for (int wt = 0; wt <= 180; wt += 18) {
                        Sim cp = real.copy();
                        Result rr = repositionPlan(cp, a, toS, wt);
                        if (rr == null || !rr.ok) continue;
                        r = repositionPlan(real, a, toS, wt);
                        break search;
                    }
                }
            }
            if (!r.ok && real.mode == Sim.Mode.AIR && !(real.lastPad == a && (c.get(a).type == Element.Type.PAD || c.get(a).type == Element.Type.SPRING))) {     // bumped while lining up: land, then size the move up again
                if (recover(real, a, in)) { a = real.onElem; if (!hasMidHazard(c, a)) settle(real, a, in); continue; }
            }
            if (!r.ok && real.mode == Sim.Mode.AIR && real.lastPad == a && (c.get(a).type == Element.Type.PAD || c.get(a).type == Element.Type.SPRING)) {
                // like a person: just launched sideways off a pad, so let it carry you a moment (no input) before steering for the next one
                int before = real.setbacks();
                for (int q = 0; q < 150 && !r.ok && real.mode == Sim.Mode.AIR && real.setbacks() == before; q++) {
                    in.clear(); real.step(in);
                    if (q % 6 == 5 && real.mode == Sim.Mode.AIR) r = plan(real, a, false, false);
                }
            }
            final Sim.Mode held = real.mode;
            if (!r.ok && (held == Sim.Mode.GROUND || held == Sim.Mode.CABLE || held == Sim.Mode.ROPE) && real.onElem == a && !c.get(a).crumbles()) {
                // like a person: nothing works from here right now (a crab in the way, a slider on the far side of its run, a cannonball due along the cable), so stay put, watch for a few seconds and size it up again
                int before = real.setbacks(); phase = "wait"; rep.waits++;
                final int wa = a;
                final int[] watched = new int[1];
                java.util.function.Consumer<Sim> watch = x -> { InputState wi = new InputState(); int k = 0; for (; k < 12 && x.mode == held && x.onElem == wa && x.setbacks() == before; k++) { wi.clear(); x.step(wi); } watched[0] = k; };
                for (int q = 0; q < 300 && !r.ok && real.mode == held && real.onElem == a && real.setbacks() == before; q += 12) {
                    if (!rehearsed(real, watch)) break;          // (staying put would be hit: no clean wait here)
                    if (watched[0] == 12) r = planRun(real, a);          // (as before: the next look comes after 12 steps of watching)
                }
                if (!r.ok && (real.mode != held || real.onElem != a)) continue;      // bumped off the spot while waiting: recover and size it up again (the loop's own limits still apply)
            }
            if (!r.ok && real.mode != Sim.Mode.AIR && real.onElem != a && onRoute(c, real.onElem)) { rep.resyncs++; resync(real, real.onElem); continue; }      // (any hold: a ledge, a rope, a cable as well as a deck - PUNISHER seed 2 slice 53, off pad 95 onto platform 90's ledge) carried off the spot (a pad's or spring's bounce with no move to steer for, a bump) onto another route platform: size it up from there (2026-10-26 campaign: PUNISHER seed 2, 581 m, a spring's bounce dropped the climber onto platform 2 and link 12 was planned from there)
            if (!r.ok) return stop(rep, real, a, outOfReach(c, real.T, a) ? "out_of_reach" : "no_move", String.format("plan failed: mode=%s on=%d s=%.2f y=%.2f vx=%.2f vy=%.2f", real.mode, real.onElem, real.s, real.y, real.vx, real.vy));
            // like a person, who sees the whole screen: the planner tries moves among the nearby elements, so check the chosen one against every hazard (an aimed
            // cannon or a cannonball from links back) and, if one would catch it, hold on a moment and size it up again
            // (on a rope or a cable too: you can hang on and wait there as well - 2026-10-27, PUNISHER seed 2 slice 57, an aimed cannon by platform 10 hit the
            // move off rope 11, which this check skipped)
            final Sim.Mode holdAt = real.mode; phase = "look";
            boolean clear = false;
            for (int look = 0; look < 25 && !(clear = clearOfEverything(real, a, r.factory)); look++) {
                if ((holdAt != Sim.Mode.GROUND && holdAt != Sim.Mode.ROPE && holdAt != Sim.Mode.CABLE) || real.mode != holdAt || real.onElem != a) break;
                int before = real.setbacks();
                if (!rehearsed(real, x -> idle(x, 12))) break;          // (holding on would be hit: execute the move as planned and let the witness judge it)
                if (real.setbacks() != before || real.mode != holdAt || real.onElem != a) break;
                Result again = planRun(real, a);
                if (again.ok) r = again;
            }
            if (clear && lineupAhead(c, c.nextRoute(a)) && !continuationClean(real, a, r.factory) && (holdAt == Sim.Mode.GROUND || holdAt == Sim.Mode.ROPE || holdAt == Sim.Mode.CABLE)) {
                // like a person, who sees the whole screen: the move is clear, but the platform it lands on is where the balance conveyor's jump has to be
                // lined up, and neither lining up there nor jumping on from the landing would be clean right then (solver recovery repair 2026-10-28,
                // NORMAL seed 1 slice 55: the aimed cannon by platform 85 hits whoever lines up on deck 88 just after the rope's move lands; holding on to
                // rope 87 for 0.5 to 4.8 s first gives a clean crossing). Hold on for the first wait that gives a clean move AND a clean continuation,
                // found on a copy; if none does within the look's 5 s, nothing changes
                Sim w = real.copy(); Result wr = r; int found = -1;
                for (int k = 1; k <= 24 && found < 0; k++) {
                    int before = w.setbacks(); idle(w, 12);
                    if (w.setbacks() != before || w.mode != holdAt || w.onElem != a) break;
                    Result again = planRun(w, a); if (again.ok) wr = again;
                    if (clearOfEverything(w, a, wr.factory) && continuationClean(w, a, wr.factory)) found = k;
                }
                for (int k = 0; k < found; k++) { rehearsed(real, x -> idle(x, 12)); Result again = planRun(real, a); if (again.ok) r = again; }
            }
            if (!clear) rep.unclearMoves++;          // (executed with a hazard still on it: a hit fails the witness, so it is never counted as proven)
            if (r.fallback != null && r.factory == r.fallback) rep.fallbackMoves++;
            if (real.mode != Sim.Mode.GROUND && real.mode != Sim.Mode.CABLE && real.mode != Sim.Mode.ROPE && !(real.mode == Sim.Mode.AIR && real.lastPad == a)) continue;      // knocked about while waiting: recover first
            Policy p = make(r.factory); phase = "execute";
            int fallsBefore = real.setbacks();
            real.act = null;
            int guard = (int) (20f / Sim.DT);
            if (DBG) System.out.printf("  policy %s from mode=%s s=%.2f y=%.2f t=%.2f%n", p.getClass().getSimpleName(), real.mode, real.s, real.y, real.time);
            int back = 0;
            while (guard-- > 0 && !succeeded(real, a)) {
                in.clear(); p.act(real, in); real.step(in);
                // like a person: shoved back down onto an earlier route platform mid-move (a crab's shove is no hit and no fall), so stop playing the
                // move from the wrong place and size it up again from there (2026-10-26 campaign: NORMAL seed 2 slice 50 stood 20 s on platform 8 while
                // executing link 18). Half a second on that platform: a move never routes back through an earlier platform on purpose
                back = real.mode == Sim.Mode.GROUND && real.onElem >= 0 && ((real.onElem < a && onRoute(c, real.onElem)) || branchOf(c, real.onElem) != null) ? back + 1 : 0;
                if (back >= 30) break;
                if (real.setbacks() != fallsBefore) return stop(rep, real, a, "setback", String.format("setback while executing the move: hits=%d falls=%d mode=%s s=%.2f y=%.2f", real.hits, real.falls, real.mode, real.s, real.y) + (real.hitBy != null ? " lastHazard=" + real.hitBy.type + String.format(" s=%.2f y=%.2f anchor=%d", real.hitBy.s, real.hitBy.y, real.hitBy.anchor) : ""));
            }
            if (back >= 30) { rep.resyncs++; resync(real, real.onElem); continue; }      // the loop re-syncs to the platform it is on
            // like a person: a move that stalls for 20 s with no hit and no fall (NGH seed 2 slice 7, 2d4388a: the plan made on a rope beside a blob deck,
            // with the planner simulating only the nearby window, waited at the rope top while the real course moved on) is sized up again from where the
            // climber is, at most twice per link, before the link counts as failed
            if (!succeeded(real, a) && real.setbacks() == fallsBefore) { if (stallA != a) { stallA = a; stalls = 0; } if (stalls++ < 2) continue; }
            if (!succeeded(real, a)) return stop(rep, real, a, "stalled", String.format("move did not reach the next link within 20 s: mode=%s on=%d s=%.2f y=%.2f vx=%.2f vy=%.2f", real.mode, real.onElem, real.s, real.y, real.vx, real.vy));
            // settle: finish pull-ups and keep going from the attached element
            if (real.mode == Sim.Mode.LEDGE || real.mode == Sim.Mode.PULLUP) {
                phase = "pull-up";
                Policy pull = new PullPolicy();
                int g2 = 120;
                while (g2-- > 0 && real.mode != Sim.Mode.GROUND) { in.clear(); pull.act(real, in); real.step(in); }
            }
            int reached = real.mode == Sim.Mode.AIR ? real.lastPad : real.onElem;
            if (reached < 0 && real.mode == Sim.Mode.GROUND && real.onSlab >= 0) reached = Math.max(a + 1, c.hazards.get(real.onSlab).anchor);      // ended on a spike slab's top: the link is its platform (the loop steps off the slab next)
            a = reached;
            if (real.won) break;
            if (a >= c.goalIndex()) {
                // goal reached by attachment: let it register
                for (int i = 0; i < 90 && !real.won; i++) { in.clear(); real.step(in); }
            }
        }
        if (real.won) return finish(rep, real, a);
        return stop(rep, real, a, "budget", String.format("simulation budget of %.0f s used up at link %d (no verdict)", maxSimSeconds, a));
    }

    /** The goal was reached: PROVEN_SOLVABLE only if the whole witness was hit-free and every key on the route was really taken (DC1: a skipped key detour used to pass silently). */
    private static Report finish(Report rep, Sim real, int a) {
        Course c = real.course;
        if (real.setbacks() != 0) return stop(rep, real, a, "setback", String.format("reached the goal with hits=%d falls=%d", real.hits, real.falls));
        for (int i = 0; i < c.hazards.size(); i++) {
            Element k = c.hazards.get(i);
            if (k.type == Element.Type.KEY && k.anchor >= 0 && k.anchor < c.routeSize() && !(i < real.featDone.length && real.featDone[i])) return stop(rep, real, a, "key_missed", "key " + i + " on route deck " + k.anchor + " never taken");
        }
        rep.verdict = Verdict.PROVEN_SOLVABLE; rep.completed = true; rep.simTime = real.time; rep.cause = "";
        return rep;
    }

    /**
     * A sound, conservative bound (DC1: PROVEN_UNSOLVABLE): the next route deck of fixed deck {@code a} is a fixed deck higher than any body can
     * reach from {@code a} - a held jump's apex plus the hands' reach below a top, with a 0.5 m allowance - and nothing that could lift a body
     * (a launcher, rope, cable, mover, seesaw, swing, ramp, creature or other element) lies anywhere within 20 m along the lane between them.
     */
    static boolean outOfReach(Course c, Tuning t, int a) {
        int b = c.nextRoute(a);
        if (a < 0 || b >= c.size()) return false;
        Element A = c.get(a), B = c.get(b);
        if (!fixedDeck(A) || !fixedDeck(B)) return false;
        float apex = t.jumpVel * t.jumpVel / (2f * t.gravity), reach = apex + t.handHeight + t.ledgeReachBelow + 0.5f;
        if (B.y - A.y <= reach) return false;
        for (int i = 0; i < c.size(); i++) {
            if (i == a || i == b) continue;
            Element e = c.get(i);
            if (fixedDeck(e) && (e.y <= A.y + 0.01f || e.y > A.y + reach)) continue;          // a fixed deck no higher than A lifts nobody higher, and one out of reach is no stepping stone
            if (Math.abs(c.dsWrap(e.s, A.s)) < 20f + A.halfW() && e.y > A.y - 12f && e.y < B.y + 2f) return false;
        }
        for (Element h : c.hazards) {          // creatures shove without a hit (a hit fails the witness anyway, so a lethal hazard is no lift)
            boolean creature = h.type == Element.Type.CRAB || h.type == Element.Type.BEE || h.type == Element.Type.SKULL || h.type == Element.Type.BLOB || h.type == Element.Type.ENFORCER || h.type == Element.Type.AVALANCHE;
            if (creature && Math.abs(c.dsWrap(h.s, A.s)) < 20f + A.halfW() && h.y > A.y - 12f && h.y < B.y + 2f) return false;
        }
        return true;
    }
    private static boolean fixedDeck(Element e) { return e.type == Element.Type.STATIC || e.type == Element.Type.CRUMBLE || e.type == Element.Type.GOAL; }

    // ------------------------------------------------------------------ incremental driver (demo/attract mode, screenshots)

    /** Drives a live Sim one step at a time using the same planner; resyncs after respawns. */
    public static final class Driver {
        int a; Policy p; boolean settling; int fallsSeen;
        int holdT, waited, waitedAt = -1, brakeAt = -1, brakeT, stalled; boolean inner;
        public boolean failed;
        /** Plays the crossing ahead on a copy: true if it reaches solid footing past the crumbling run with no setback. */
        static boolean crossingClear(Sim s0, int a) {
            Sim s = s0.copy(); Driver d = new Driver(s); d.inner = true; InputState in = new InputState(); int sb = s.setbacks();
            for (int i = 0; i < 60 * 8; i++) {
                d.drive(s, in);
                if (d.failed) return false;
                s.step(in);
                if (s.setbacks() != sb) return false;
                if (s.mode == Sim.Mode.GROUND && s.onElem > a && onRoute(s.course, s.onElem) && !s.course.get(s.onElem).crumbles()) return true;
            }
            return false;
        }
        private final Policy pull = new PullPolicy();
        public Driver(Sim s) { a = Math.max(0, s.onElem); fallsSeen = s.setbacks(); s.humanHold = HUMAN_HOLD; }
        public int link() { return a; }
        /** The world was rebuilt around the player (floating origin / window change): carry the link over to the new element numbering and re-plan from the current state. */
        public void rebase(Tower.Remap m) {
            if (a >= 0 && a < m.elem.length && m.elem[a] >= 0) a = m.elem[a]; else a = Math.max(0, a); p = null; settling = false;
            brakeAt = remap(m, brakeAt); waitedAt = remap(m, waitedAt);          // these name elements too: carry them into the new numbering
        }
        private static int remap(Tower.Remap m, int i) { return i >= 0 && i < m.elem.length ? m.elem[i] : -1; }

        public void drive(Sim s, InputState in) {
            in.clear();
            if (s.won) return;
            if (s.setbacks() != fallsSeen) { fallsSeen = s.setbacks(); a = Math.max(0, s.onElem); p = null; settling = false; }
            if (settling) {
                if (s.mode == Sim.Mode.GROUND) { settling = false; a = s.onElem; p = null; } else { pull.act(s, in); return; }
            }
            if (p != null && succeeded(s, a)) {
                p = null;
                if (s.mode == Sim.Mode.LEDGE || s.mode == Sim.Mode.PULLUP) { settling = true; pull.act(s, in); return; }
                a = s.mode == Sim.Mode.AIR ? s.lastPad : s.onElem;
            }
            if (brakeT > 0 && p == null && s.mode == Sim.Mode.GROUND && s.onElem == a) { brakeT--; return; }
            if (p == null && !inner && s.mode == Sim.Mode.GROUND && s.onElem == a && s.course.nextRoute(a) < s.course.routeSize() && s.course.get(s.course.nextRoute(a)).crumbles() && !s.course.get(a).crumbles()) {
                // like a person: crumbling footing leaves no time to wait out a hazard, so size up the whole crossing from solid ground and wait here until it is clear
                if (waitedAt != a) { waitedAt = a; waited = 0; holdT = 0; }
                if (holdT > 0) { holdT--; return; }
                if (waited < 600 && !crossingClear(s, a)) { holdT = 6; waited += 6; return; }
            }
            if (p == null) {
                if (s.mode == Sim.Mode.AIR && s.lastPad != a && s.lastPad > a) a = s.lastPad;
                int[] win0 = s.act;
                Result r = planRun(s, a);
                s.act = win0;
                if (brakeAt != a) { brakeAt = a; stalled = 0; }
                if (!r.ok && stalled < 360 && s.mode == Sim.Mode.GROUND && s.onElem == a && !s.course.get(a).crumbles()) { brakeT = 6; stalled += 6; return; }   // like a person: no move works right now (still moving, or a cannonball on its way), so stand still a moment and size it up again
                if (!r.ok) { failed = true; return; }
                if (r.factory == null) return;          // the end of the route: nothing left to plan, stand still
                p = make(r.factory);
            }
            p.act(s, in);
        }
    }
}
