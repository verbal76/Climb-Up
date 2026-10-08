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
        public float margin() { return trials == 0 ? 0 : successes / (float) trials; }
    }

    public static final class Report {
        public boolean completed; public int failedLink = -1; public float simTime; public int links; public float minMargin = 1f;
        public int worstLink = -1;
    }

    static final float[] OFFSETS = {0f, 0.45f, 0.95f, 1.5f};
    static final float[] HOLDS = {0.9f, 0.22f};

    // ------------------------------------------------------------------ policies

    static float steer(Sim s, int b, int mode) {
        float d = s.course.dsWrap(s.es1[b], s.s);
        Element el = s.course.get(b);
        if (mode == 1) return Math.signum(d);
        if (mode == 2) { // aim at the near edge of the target platform
            float near = d - Math.signum(d) * (el.isPlatform() ? Math.max(0f, el.halfW() - 0.3f) : 0f);
            return Math.max(-1f, Math.min(1f, near / 0.4f));
        }
        return Math.max(-1f, Math.min(1f, d / 0.45f));
    }

    static final class GroundPolicy implements Policy {
        final int a, b, dir, steerMode; final float wait, offset, hold;
        float t, holdT; int phase;
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
                    if (ahead > 0.06f) { in.moveX = dir; return; }
                    if (ahead < -0.3f) { in.moveX = -dir * 0.6f; return; }
                    in.moveX = dir; in.jumpPressed = true; in.jumpHeld = true; phase = 2; holdT = 0; return;
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

    static final class PullPolicy implements Policy {
        public void act(Sim s, InputState in) { in.moveY = 1f; }
    }

    // ------------------------------------------------------------------ planning

    static boolean movingNear(Course c, int a) {
        for (int i = a; i <= Math.min(c.routeSize() - 1, a + 2); i++) if (c.get(i).isMoving()) return true;
        return false;
    }

    static float maxPeriod(Course c, int a) {
        float p = 0;
        for (int i = a; i <= Math.min(c.routeSize() - 1, a + 2); i++) if (c.get(i).isMoving()) p = Math.max(p, c.get(i).period);
        return p;
    }

    static boolean succeeded(Sim s, int a) {
        switch (s.mode) {
            case GROUND: case LEDGE: case PULLUP: case ROPE: case CABLE:
                return s.onElem > a && s.onElem < s.course.routeSize() && (s.mode != Sim.Mode.GROUND || !s.gone[s.onElem]);
            default: return s.lastPad > a && s.lastPad < s.course.routeSize();
        }
    }

    /** Runs a policy on a copy of the state; true if it attaches to a later element without falling. */
    static boolean trial(Sim base, int a, PolicyFactory f, float limit) { return trialEnd(base, a, f, limit) != null; }

    /** Like {@link #trial} but returns the state at the moment of success (null on failure). */
    static Sim trialEnd(Sim base, int a, PolicyFactory f, float limit) {
        Sim s = base.copy();
        s.setWindow(a - 1, a + 6);
        Policy p = f.create();
        InputState in = new InputState();
        int fallsBefore = s.falls;
        float floorY = Math.min(s.course.get(a).y, s.course.get(Math.min(s.course.routeSize() - 1, a + 1)).y) - 4.5f;
        int n = (int) (limit / Sim.DT);
        for (int i = 0; i < n; i++) {
            in.clear();
            p.act(s, in);
            s.step(in);
            if (s.falls != fallsBefore) return null;
            if (succeeded(s, a)) return s;
            if (s.mode == Sim.Mode.GROUND && s.onElem >= 0 && (s.onElem < a || s.onElem >= s.course.routeSize())) return null;   // fell back, or onto a dead end
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
    public static Result plan(Sim base, int a, boolean measureMargin, boolean lookahead) {
        Course c = base.course;
        Result r = new Result();
        int b = a + 1;
        if (b >= c.routeSize()) { r.ok = true; return r; }
        List<PolicyFactory> cands = new ArrayList<>();
        int dir = c.dsWrap(base.es1[b], base.es1[a]) >= 0 ? 1 : -1;
        if (c.get(a).type == Element.Type.ROPE) {
            float[] deltas = {0f, 0.5f, 1.0f, 1.6f, 2.4f, 3.4f};
            for (float d : deltas) for (float h : HOLDS) cands.add(() -> new RopePolicy(a, b, dir, d, h));
        } else if (c.get(a).type == Element.Type.CABLE) {
            float[] ks = {0f, 0.6f, 1.4f, 2.4f};
            for (float k : ks) { cands.add(() -> new CablePolicy(a, b, dir, k, false)); cands.add(() -> new CablePolicy(a, b, dir, k, true)); }
        } else if (base.mode == Sim.Mode.AIR && c.get(a).type == Element.Type.PAD) {
            for (int m = 0; m < 3; m++) for (int h = 0; h < 2; h++) { final int mm = m; final boolean hh = h == 0; cands.add(() -> new AirPolicy(b, mm, hh)); }
        } else {
            boolean moving = movingNear(c, a);
            int waits = moving ? Math.min(24, (int) Math.ceil(maxPeriod(c, a) / 0.3f) + 1) : 1;
            for (int w = 0; w < waits; w++) {
                final float wt = w * 0.3f;
                for (float o : OFFSETS) for (float h : HOLDS) for (int sm = 0; sm < 4; sm += 1) {
                    if (sm == 2) continue;
                    final float fo = o, fh = h; final int fsm = sm;
                    cands.add(() -> new GroundPolicy(a, b, dir, wt, fo, fh, fsm));
                }
            }
        }
        PolicyFactory firstOk = null;
        for (PolicyFactory f : cands) {
            r.trials++;
            float limit = 4.5f + (c.get(a).type == Element.Type.CRUMBLE ? 0 : 6f);
            if (lookahead) {
                Sim end = trialEnd(base, a, f, limit);
                if (end == null) continue;
                r.successes++;
                if (firstOk == null) firstOk = f;
                if (!r.ok && leavesGoodState(end, a)) { r.ok = true; r.factory = f; }
                if (r.ok && !measureMargin) break;
            } else if (trial(base, a, f, limit)) {
                r.successes++;
                if (!r.ok) { r.ok = true; r.factory = f; }
                if (!measureMargin) break;
            }
        }
        if (lookahead && !r.ok && firstOk != null) { r.ok = true; r.factory = firstOk; }   // nothing good follows: fall back to any working move
        return r;
    }

    // ------------------------------------------------------------------ whole-course run

    public static Report run(Course c, Tuning t, float maxSimSeconds) {
        Report rep = new Report();
        Sim real = Sim.startOn(c, t, 0);
        int a = 0;
        InputState in = new InputState();
        while (!real.won && real.time < maxSimSeconds) {
            Result r = plan(real, a, false, true);
            rep.links++;
            if (!r.ok) { rep.failedLink = a; rep.simTime = real.time; return rep; }
            Policy p = r.factory.create();
            int fallsBefore = real.falls;
            real.act = null;
            int guard = (int) (20f / Sim.DT);
            while (guard-- > 0 && !succeeded(real, a)) {
                in.clear(); p.act(real, in); real.step(in);
                if (real.falls != fallsBefore) { rep.failedLink = a; rep.simTime = real.time; return rep; }
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
        public boolean failed;
        private final Policy pull = new PullPolicy();
        public Driver(Sim s) { a = Math.max(0, s.onElem); fallsSeen = s.falls; }
        public int link() { return a; }

        public void drive(Sim s, InputState in) {
            in.clear();
            if (s.won) return;
            if (s.falls != fallsSeen) { fallsSeen = s.falls; a = Math.max(0, s.onElem); p = null; settling = false; }
            if (settling) {
                if (s.mode == Sim.Mode.GROUND) { settling = false; a = s.onElem; p = null; } else { pull.act(s, in); return; }
            }
            if (p != null && succeeded(s, a)) {
                p = null;
                if (s.mode == Sim.Mode.LEDGE || s.mode == Sim.Mode.PULLUP) { settling = true; pull.act(s, in); return; }
                a = s.mode == Sim.Mode.AIR ? s.lastPad : s.onElem;
            }
            if (p == null) {
                if (s.mode == Sim.Mode.AIR && s.lastPad != a && s.lastPad > a) a = s.lastPad;
                int[] win0 = s.act;
                Result r = plan(s, a, false, true);
                s.act = win0;
                if (!r.ok) { failed = true; return; }
                p = r.factory.create();
            }
            p.act(s, in);
        }
    }
}
