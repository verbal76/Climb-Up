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
        public float margin() { return trials == 0 ? 0 : successes / (float) trials; }
    }

    public static final class Report {
        public boolean completed; public int failedLink = -1; public float simTime; public int links; public float minMargin = 1f;
        public int worstLink = -1;
        public String failInfo = "";
    }

    static final float[] OFFSETS = {0f, 0.45f, 0.95f, 1.5f};
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
        GroundPolicy(int a, int b, int dir, float wait, float offset, float hold, int steerMode) {
            this.a = a; this.b = b; this.dir = dir; this.wait = wait; this.offset = offset; this.hold = hold; this.steerMode = steerMode;
        }
        public void act(Sim s, InputState in) {
            if (s.mode == Sim.Mode.LEDGE || s.mode == Sim.Mode.PULLUP) { in.moveY = 1f; return; }
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
                    if (ahead > 0f && gap / Element.SKULL_SPEED < 0.15f) { in.jumpPressed = true; in.jumpHeld = true; busy = 1; return true; }
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
        for (int i = a; i <= Math.min(c.routeSize() - 1, a + 2); i++) if (c.get(i).isMoving() || c.get(i).spins()) return true;
        int a0 = anchorIdx(c, a); for (Element h : c.hazards) if (h.anchor >= a0 - (firesAhead(h) ? 1 : 0) && h.anchor <= a0 + 2 && h.isMoving()) return true;
        return false;
    }

    static float maxPeriod(Course c, int a) {
        float p = 0;
        for (int i = a; i <= Math.min(c.routeSize() - 1, a + 2); i++) if (c.get(i).isMoving() || c.get(i).spins()) p = Math.max(p, c.get(i).period);
        int a0 = anchorIdx(c, a); for (Element h : c.hazards) if (h.anchor >= a0 - (firesAhead(h) ? 1 : 0) && h.anchor <= a0 + 2 && h.isMoving()) p = Math.max(p, h.period);
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

    static int anchorIdx(Course c, int i) { return c.get(i).anchor < 0 ? i : c.get(i).anchor; }

    /** Runs a policy on a copy of the state; true if it attaches to a later element without falling. */
    static boolean trial(Sim base, int a, PolicyFactory f, float limit) { return trialEnd(base, a, -1, f, limit) != null; }

    /** Like {@link #trial} but returns the state at the moment of success (null on failure). */
    static Sim trialEnd(Sim base, int a, PolicyFactory f, float limit) { return trialEnd(base, a, -1, f, limit); }

    static Sim trialEnd(Sim base, int a, int tgt, PolicyFactory f, float limit) {
        Sim s = base.copy(); s.humanHold = HUMAN_HOLD;
        if (tgt >= 0) s.setWindowNear(anchorIdx(s.course, a) - 2, anchorIdx(s.course, a) + 6, a, tgt);     // plus earlier route platforms the climb passed that hang over a side branch
        else s.setWindow(a - 1, a + 6);
        Policy p = make(f);
        InputState in = new InputState();
        int fallsBefore = s.setbacks();
        float floorY = (tgt >= 0 ? Math.min(s.course.get(a).y, s.course.get(tgt).y) : Math.min(s.course.get(a).y, s.course.get(Math.min(s.course.routeSize() - 1, s.course.nextRoute(a))).y)) - 4.5f;
        int n = (int) (limit / Sim.DT);
        for (int i = 0; i < n; i++) {
            in.clear();
            p.act(s, in);
            s.step(in);
            if (s.setbacks() != fallsBefore) return null;
            if (tgt >= 0 ? succeededTo(s, tgt) : succeeded(s, a)) return tgt >= 0 && !staysOn(s, tgt) ? null : s;
            if (tgt < 0 && s.mode == Sim.Mode.GROUND && s.onElem >= 0 && (s.onElem < a || !onRoute(s.course, s.onElem))) return null;   // fell back, or onto a dead end
            if (s.mode == Sim.Mode.AIR && s.vy < 0 && s.y < floorY) return null;
        }
        return null;
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
        int[] groupWins = new int[32]; int nWaits = 1;
        int dir = c.dsWrap(base.es1[b], base.es1[a]) >= 0 ? 1 : -1;
        if (c.get(a).type == Element.Type.ROPE) {
            float[] deltas = {0f, 0.5f, 1.0f, 1.6f, 2.4f, 3.4f};
            for (float d : deltas) for (float h : HOLDS) cands.add(() -> new RopePolicy(a, b, dir, d, h));
        } else if (c.get(a).type == Element.Type.CABLE) {
            float[] ks = {0f, 0.6f, 1.4f, 2.4f};
            for (float k : ks) { cands.add(() -> new CablePolicy(a, b, dir, k, false)); cands.add(() -> new CablePolicy(a, b, dir, k, true)); }
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
                    group.add(oi * 8 + hi * 4 + sm);
                }
            }
        }
        if (base.mode == Sim.Mode.GROUND && c.get(a).isPlatform() && c.get(b).isPlatform() && c.get(b).y < c.get(a).y - 0.3f) {      // heading down: just walk off the edge
            for (int w = 0; w < nWaits; w++) for (int sm : new int[]{0, 1, 3}) {
                final float wt = w * 0.3f; final int fsm = sm;
                cands.add(() -> new WalkOffPolicy(b, dir, wt, fsm));
                group.add(24 + sm);
            }
        }
        if (base.mode == Sim.Mode.GROUND && c.get(a).holed()) {           // Build 79: a bridge that drops planks: hop the holes (tried first: a plain run falls through)
            int at = 0;
            for (float hd : new float[]{0.15f, 0.45f, 0.8f}) for (int sm : new int[]{0, 3}) { final float fhd = hd; final int fsm = sm; cands.add(at, () -> new HoleHopPolicy(a, b, dir, fhd, fsm)); group.add(at++, 16); }
        }
        if (base.mode == Sim.Mode.GROUND && c.get(a).isPlatform()) {      // a hazard in the middle of this platform: hop-over moves
            for (int hi = 0; hi < c.hazards.size(); hi++) {
                Element h = c.hazards.get(hi);
                if (h.anchor != anchorIdx(c, a) || !(h.type == Element.Type.SAW_H || h.type == Element.Type.SPIKE_BLOCK || h.type == Element.Type.SPIKE_TRAP || h.type == Element.Type.CRAB)) continue;
                if (Math.abs(c.dsWrap(h.s, base.es1[a])) >= c.get(a).halfW()) continue;
                final float hx = base.es1[a] + c.dsWrap(h.s, base.es1[a]);
                final int crab = h.type == Element.Type.CRAB ? hi : -1;
                float[] hops = {1.0f, 1.5f, 2.0f, 2.5f};
                for (int w = 0; w < nWaits; w++) for (float hd : hops) for (int sm = 0; sm < 2; sm++) {
                    final float wt = w * 0.3f, fhd = hd; final int fsm = sm == 0 ? 0 : 3;
                    cands.add(() -> new MidHopPolicy(a, b, dir, wt, fhd, hx, 0f, fsm, crab));
                    group.add(16 + (int) (hd * 2f) % 8 + sm * 0);
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
        for (int ci = 0; ci < cands.size(); ci++) {
            PolicyFactory f = cands.get(ci);
            r.trials++;
            float limit = 4.5f + (c.get(a).crumbles() ? 0 : 6f);
            if (lookahead) {
                Sim end = trialEnd(base, a, tgt, f, limit);
                if (end == null) continue;
                r.successes++;
                if (firstOk == null) firstOk = f;
                if (!r.ok && leavesGoodState(end, a)) { r.ok = true; r.factory = f; }
                if (r.ok && !measureMargin) break;
            } else if (trialEnd(base, a, tgt, f, limit) != null) {
                r.successes++;
                if (ci < group.size()) groupWins[group.get(ci)]++;
                if (!r.ok) { r.ok = true; r.factory = f; }
                if (!measureMargin) break;
            }
        }
        if (!r.ok && slowCands != null) {
            for (PolicyFactory f : slowCands) {
                r.trials++;
                Sim end = trialEnd(base, a, tgt, f, 4.5f + (c.get(a).crumbles() ? 0 : 6f));
                if (end == null) continue;
                r.successes++;
                if (!lookahead || leavesGoodState(end, a)) { r.ok = true; r.factory = f; break; }      // while playing, a slow move that also suits the next link beats a working one that does not
                if (firstOk == null) firstOk = f;
            }
        }
        if (measureMargin && !group.isEmpty()) { int best = 0; for (int gw : groupWins) best = Math.max(best, gw); r.window = best / (float) nWaits; }
        if (lookahead && !r.ok && firstOk != null) { r.ok = true; r.factory = firstOk; }   // nothing good follows: fall back to any working move
        return r;
    }

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

    /** True if the move reaches the next link on a copy with every hazard of the course active (no planning window). */
    static boolean clearOfEverything(Sim real, int a, PolicyFactory f) {
        Sim s = real.copy(); s.act = null; s.hz = null; s.humanHold = HUMAN_HOLD;
        Policy p = make(f); InputState in = new InputState(); int sb = s.setbacks();
        for (int i = 0; i < (int) (20f / Sim.DT); i++) {
            in.clear(); p.act(s, in); s.step(in);
            if (s.setbacks() != sb) return false;
            if (succeeded(s, a)) return true;
        }
        return true;      // undecided within the time: let the real run find out, as before
    }

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
        for (int g = 0; g < 120 && (real.mode == Sim.Mode.LEDGE || real.mode == Sim.Mode.PULLUP); g++) { in.clear(); pull.act(real, in); real.step(in); }
        return true;
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
    static boolean recover(Sim real, int a, InputState in) {
        Course c = real.course; int f0 = real.setbacks();
        for (int q = 0; q < 360 && real.mode != Sim.Mode.GROUND; q++) {
            in.clear();
            if (real.mode == Sim.Mode.LEDGE || real.mode == Sim.Mode.PULLUP) in.moveY = 1f;
            else if (real.mode == Sim.Mode.AIR) in.moveX = Math.max(-1f, Math.min(1f, 1.5f * c.dsWrap(real.es1[a], real.s)));
            real.step(in);
            if (real.setbacks() != f0) return false;
        }
        return real.mode == Sim.Mode.GROUND && onRoute(c, real.onElem);
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
        int lastA = -1, sameA = 0;
        while (!real.won && real.time < maxSimSeconds) {
            if (a == lastA) sameA++; else { lastA = a; sameA = 0; }
            if (DBG) System.out.printf("loop a=%d same=%d mode=%s on=%d s=%.2f y=%.2f vx=%.2f vy=%.2f t=%.2f%n", a, sameA, real.mode, real.onElem, real.s, real.y, real.vx, real.vy, real.time);
            if (sameA > 30) { rep.failedLink = a; rep.simTime = real.time; rep.failInfo = "stuck at one link: knocked back again and again"; return rep; }
            if (endless && a >= c.goalIndex()) { rep.completed = true; rep.simTime = real.time; return rep; }
            for (int[] kr : c.keyRooms) {
                if (kr[0] != a || !visited.add(kr[0])) continue;
                if (real.mode != Sim.Mode.GROUND || real.onElem != a) break;
                if (!detour(real, kr, in)) { rep.failedLink = a; rep.simTime = real.time; rep.failInfo = "detour " + why + String.format(" mode=%s on=%d s=%.2f y=%.2f t=%.2f", real.mode, real.onElem, real.s, real.y, real.time); return rep; }
                a = real.onElem;
            }
            if (real.mode == Sim.Mode.GROUND && real.onElem == a && hasMidHazard(c, a)) {   // like a person would: stop in the safe pocket before sizing up the hazard
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
                if (!recover(real, a, in)) { rep.failedLink = a; rep.simTime = real.time; rep.failInfo = "recover " + String.format("mode=%s s=%.2f y=%.2f", real.mode, real.s, real.y); return rep; }
                a = real.onElem;
                if (!hasMidHazard(c, a)) settle(real, a, in);     // a hazard in the middle (a crab that comes after you): no walking to the centre to settle, size the next move up from where we landed
                continue;
            }
            Result r = plan(real, a, false, true);
            if (!r.ok) r = plan(real, a, false, false);        // no move leaves a state that suits the next link: take any that works and sort the next link out from there
            rep.links++;
            if (!r.ok && Boolean.getBoolean("dbg")) System.out.printf("first plan failed at %d: mode=%s on=%d s=%.2f y=%.2f vx=%.2f vy=%.2f t=%.2f%n", a, real.mode, real.onElem, real.s, real.y, real.vx, real.vy, real.time);
            if (!r.ok && real.mode == Sim.Mode.GROUND && real.onElem == a && !hasMidHazard(c, a)) {      // like a person: arrived still moving, so brake, line up in the middle and size the jump up from a standstill
                settle(real, a, in);
                if (real.mode == Sim.Mode.GROUND && real.onElem == a) r = plan(real, a, false, true);
                if (!r.ok && real.mode == Sim.Mode.GROUND && real.onElem == a) r = plan(real, a, false, false);
            }
            for (int tryBack = 0; !r.ok && tryBack < 2 && real.mode == Sim.Mode.GROUND && real.onElem == a && !hasMidHazard(c, a); tryBack++) {
                // like a person: walk back along the platform for a longer run-up, settle, and size up the jump again
                Element el = c.get(a);
                float toS = c.wrap(real.es1[a] - el.halfW() + (tryBack == 0 ? 0.35f : 0.8f));
                for (int q = 0; q < 240 && real.mode == Sim.Mode.GROUND; q++) {
                    float d = c.dsWrap(toS, real.s);
                    if (Math.abs(d) < 0.45f && Math.abs(real.vx) < 0.4f) break;
                    in.clear(); in.moveX = walkTo(d); real.step(in);
                }
                for (int q = 0; q < 30 && real.mode == Sim.Mode.GROUND && Math.abs(real.vx) > 0.05f; q++) { in.clear(); real.step(in); }
                if (real.mode != Sim.Mode.GROUND || real.onElem != a) break;
                r = plan(real, a, false, true);
            }
            if (!r.ok && real.mode == Sim.Mode.GROUND && real.onElem == a && !hasMidHazard(c, a)) {
                // like a person: a shorter or slower approach from a different spot can be the only clean line (a full-speed run-up from the far end can overshoot or catch the wrong ledge)
                float room = c.get(a).halfW() - 0.4f;
                for (float off : new float[]{0f, 0.5f, 1.0f, -0.5f, 1.3f, -1.0f}) {
                    if (Math.abs(off) > room) continue;
                    float toS = c.wrap(real.es1[a] + off);
                    for (int q = 0; q < 240 && real.mode == Sim.Mode.GROUND; q++) {
                        float d = c.dsWrap(toS, real.s);
                        if (Math.abs(d) < 0.45f && Math.abs(real.vx) < 0.4f) break;
                        in.clear(); in.moveX = walkTo(d); real.step(in);
                    }
                    for (int q = 0; q < 40 && real.mode == Sim.Mode.GROUND && Math.abs(real.vx) > 0.05f; q++) { in.clear(); real.step(in); }
                    if (real.mode != Sim.Mode.GROUND || real.onElem != a) break;
                    r = plan(real, a, false, false);
                    if (r.ok) break;
                }
            }
            if (!r.ok && real.mode == Sim.Mode.GROUND && real.onElem == a && !hasMidHazard(c, a)) {
                // like a person: stand right where the jump was proven (the platform's middle) and repeat it as proven. The walks above stop anywhere within 0.45 m of
                // a spot, and a standing jump off a short step can need ~0.35 m and the proof's exact start timing (Build 77, seed 3 slice 54: castle 7's staircase,
                // a 2.5 m step that works from -0.2..+0.15 m; proofs start with the stick just let go, a long-idle solver moved 0.1 s sooner and missed)
                standAt(real, real.es1[a], in);
                if (real.mode == Sim.Mode.GROUND && real.onElem == a) { real.restartHold(); r = plan(real, a, false, true); if (!r.ok) r = plan(real, a, false, false); }      // and from the stick state the proof started in
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
                int before = real.setbacks();
                for (int q = 0; q < 300 && !r.ok && real.mode == held && real.onElem == a && real.setbacks() == before; q++) {
                    in.clear(); real.step(in);
                    if (q % 12 == 11) { r = plan(real, a, false, true); if (!r.ok) r = plan(real, a, false, false); }
                }
                if (!r.ok && (real.mode != held || real.onElem != a)) continue;      // bumped off the spot while waiting: recover and size it up again (the loop's own limits still apply)
            }
            if (!r.ok) { rep.failedLink = a; rep.simTime = real.time; rep.failInfo = String.format("plan failed: mode=%s on=%d s=%.2f y=%.2f vx=%.2f vy=%.2f", real.mode, real.onElem, real.s, real.y, real.vx, real.vy); return rep; }
            // like a person, who sees the whole screen: the planner tries moves among the nearby elements, so check the chosen one against every hazard (an aimed
            // cannon or a cannonball from links back) and, if one would catch it, hold on a moment and size it up again
            for (int look = 0; look < 25 && !clearOfEverything(real, a, r.factory); look++) {
                if (real.mode != Sim.Mode.GROUND || real.onElem != a) break;
                int before = real.setbacks();
                for (int q = 0; q < 12 && real.setbacks() == before; q++) { in.clear(); real.step(in); }
                if (real.setbacks() != before || real.mode != Sim.Mode.GROUND || real.onElem != a) break;
                Result again = plan(real, a, false, true); if (!again.ok) again = plan(real, a, false, false);
                if (again.ok) r = again;
            }
            if (real.mode != Sim.Mode.GROUND && real.mode != Sim.Mode.CABLE && real.mode != Sim.Mode.ROPE && !(real.mode == Sim.Mode.AIR && real.lastPad == a)) continue;      // knocked about while waiting: recover first
            Policy p = make(r.factory);
            int fallsBefore = real.setbacks();
            real.act = null;
            int guard = (int) (20f / Sim.DT);
            if (DBG) System.out.printf("  policy %s from mode=%s s=%.2f y=%.2f t=%.2f%n", p.getClass().getSimpleName(), real.mode, real.s, real.y, real.time);
            while (guard-- > 0 && !succeeded(real, a)) {
                in.clear(); p.act(real, in); real.step(in);
                if (real.setbacks() != fallsBefore) { rep.failedLink = a; rep.simTime = real.time; rep.failInfo = String.format("setback while executing the move: hits=%d falls=%d mode=%s s=%.2f y=%.2f", real.hits, real.falls, real.mode, real.s, real.y) + (real.hitBy != null ? " lastHazard=" + real.hitBy.type + String.format(" s=%.2f y=%.2f anchor=%d", real.hitBy.s, real.hitBy.y, real.hitBy.anchor) : ""); return rep; }
            }
            if (!succeeded(real, a)) { rep.failedLink = a; rep.simTime = real.time; return rep; }
            // settle: finish pull-ups and keep going from the attached element
            if (real.mode == Sim.Mode.LEDGE || real.mode == Sim.Mode.PULLUP) {
                Policy pull = new PullPolicy();
                int g2 = 120;
                while (g2-- > 0 && real.mode != Sim.Mode.GROUND) { in.clear(); pull.act(real, in); real.step(in); }
            }
            a = real.mode == Sim.Mode.AIR ? real.lastPad : real.onElem;
            if (real.won) break;
            if (a >= c.goalIndex()) {
                // goal reached by attachment: let it register
                for (int i = 0; i < 90 && !real.won; i++) { in.clear(); real.step(in); }
            }
        }
        rep.completed = real.won;
        rep.simTime = real.time;
        return rep;
    }

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
                Result r = plan(s, a, false, true);
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
