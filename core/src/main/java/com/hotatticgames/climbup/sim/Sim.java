package com.hotatticgames.climbup.sim;

/**
 * Deterministic fixed-step platformer simulation on a wrapped (cylindrical) arc coordinate.
 * No rendering or engine dependencies; cloneable so tests and the course validator can look ahead.
 */
public final class Sim {
    public enum Mode { GROUND, AIR, ROPE, CABLE, LEDGE, PULLUP }

    public static final float DT = 1f / 60f;
    // event bits
    public static final int EV_JUMP = 1, EV_LAND = 2, EV_BOUNCE = 4, EV_GRAB = 8, EV_PULL = 16, EV_CRUMBLE = 32,
            EV_CHECKPOINT = 64, EV_RESPAWN = 128, EV_WIN = 256, EV_ROPE = 512, EV_CABLE = 1024, EV_FALL_NEAR = 2048;

    public final Course course;
    public final Tuning T;

    public float time;
    // player
    public float s, y, vx, vy;
    public int facing = 1;
    public Mode mode = Mode.AIR;
    public int onElem = -1;          // standing / hanging / roping element index
    public int lastPad = -1;
    public float coyote, jumpBuf, lockout, pullT, pullFromS, pullFromY, pullToS, pullToY;
    public int ledgeSide = 1;
    public boolean jumpedUp;         // current ascent came from a jump (variable height cut applies)
    public boolean prevJumpHeld;
    public float lastGroundY;
    public int checkpoint;
    public int bestElem;             // highest route element reached (progress)
    public float maxHeight;
    public boolean won;
    public int falls;
    public float landSpeed;          // for squash feedback
    public int events;               // accumulated since last consumeEvents()
    public float ps0, py0;           // player position at the start of the last step (render interpolation)
    public boolean teleported;       // set when the last step moved the player discontinuously (respawn)
    public float assistForgive;      // extra coyote/buffer seconds from assists

    // element dynamic state
    public final float[] es0, ey0, es1, ey1;      // previous/current positions
    public final float[] crumbleT;                // <0 idle, >=0 counting, set to +big when gone
    public final boolean[] gone;
    public final float[] goneT;
    public final float[] padSquash;
    public int[] act;                // planner window: element indices simulated (null = everything)

    /** Restrict simulation to route elements rLo..rHi plus the decoys anchored near them (used by the planner for speed). */
    public void setWindow(int rLo, int rHi) {
        int rs = course.routeSize();
        int lo = Math.max(0, rLo), hi = Math.min(rs - 1, rHi);
        int[] d = course.decoyRange(lo - 4, hi + 1);
        int n = Math.max(0, hi - lo + 1) + (d[1] - d[0]);
        int[] a = new int[n]; int k = 0;
        for (int i = lo; i <= hi; i++) a[k++] = i;
        for (int i = d[0]; i < d[1]; i++) a[k++] = i;
        act = a;
    }

    private void progress(int i) { if (i < course.routeSize() && i > bestElem) bestElem = i; }

    public Sim(Course c, Tuning t) {
        this.course = c; this.T = t;
        int n = c.size();
        es0 = new float[n]; ey0 = new float[n]; es1 = new float[n]; ey1 = new float[n];
        crumbleT = new float[n]; gone = new boolean[n]; goneT = new float[n]; padSquash = new float[n];
        java.util.Arrays.fill(crumbleT, -1f);
        refreshElements();
        spawnAtCheckpoint(0);
    }

    private Sim(Sim o) {
        course = o.course; T = o.T;
        time = o.time; s = o.s; y = o.y; vx = o.vx; vy = o.vy; facing = o.facing; mode = o.mode; onElem = o.onElem;
        lastPad = o.lastPad; coyote = o.coyote; jumpBuf = o.jumpBuf; lockout = o.lockout; pullT = o.pullT;
        pullFromS = o.pullFromS; pullFromY = o.pullFromY; pullToS = o.pullToS; pullToY = o.pullToY;
        ledgeSide = o.ledgeSide; jumpedUp = o.jumpedUp; prevJumpHeld = o.prevJumpHeld; lastGroundY = o.lastGroundY;
        checkpoint = o.checkpoint; bestElem = o.bestElem; maxHeight = o.maxHeight; won = o.won; falls = o.falls;
        landSpeed = o.landSpeed; events = o.events; assistForgive = o.assistForgive; ps0 = o.ps0; py0 = o.py0; teleported = o.teleported;
        es0 = o.es0.clone(); ey0 = o.ey0.clone(); es1 = o.es1.clone(); ey1 = o.ey1.clone();
        crumbleT = o.crumbleT.clone(); gone = o.gone.clone(); goneT = o.goneT.clone(); padSquash = o.padSquash.clone();
        act = o.act;
    }

    public Sim copy() { return new Sim(this); }

    /** Course may grow while generating; keeps arrays in sync by constructing a fresh Sim instead. */
    private void refreshElements() {
        for (int i = 0; i < course.size(); i++) {
            Element e = course.get(i);
            es0[i] = es1[i] = e.sAt(time);
            ey0[i] = ey1[i] = e.yAt(time);
        }
    }

    public int consumeEvents() { int e = events; events = 0; return e; }

    // ---------------------------------------------------------------- spawning

    public void spawnAtCheckpoint(int idx) {
        checkpoint = idx;
        Element e = course.get(idx);
        s = course.wrap(es1[idx]); y = ey1[idx]; vx = vy = 0;
        mode = Mode.GROUND; onElem = idx; lastGroundY = y; coyote = 0; jumpBuf = 0; lockout = 0.1f;
        jumpedUp = false; facing = 1;
        progress(idx);
    }

    /** Start state used by planners: standing/attached on element idx at the current time. */
    public static Sim startOn(Course c, Tuning t, int idx) {
        Sim m = new Sim(c, t);
        Element e = c.get(idx);
        m.checkpoint = 0;
        switch (e.type) {
            case PAD:
                m.s = c.wrap(m.es1[idx]); m.y = m.ey1[idx]; m.vx = 0; m.vy = t.padBounce; m.mode = Mode.AIR;
                m.onElem = -1; m.lastPad = idx; m.lastGroundY = m.y; m.bestElem = idx;
                break;
            case ROPE:
                m.s = c.wrap(m.es1[idx]); m.y = e.yBottom() - 0.1f; m.mode = Mode.ROPE; m.onElem = idx; m.lastGroundY = m.y;
                m.bestElem = idx;
                break;
            case CABLE:
                m.s = c.wrap(m.es1[idx] - e.w * 0.5f + 0.4f); m.y = e.y - t.handHeight; m.mode = Mode.CABLE; m.onElem = idx;
                m.lastGroundY = m.y; m.bestElem = idx;
                break;
            default:
                m.spawnAtCheckpoint(idx); m.checkpoint = 0; m.bestElem = idx;
                if (e.type == Element.Type.CRUMBLE) m.crumbleT[idx] = 0;
        }
        return m;
    }

    public void respawn() {
        falls++;
        events |= EV_RESPAWN; teleported = true;
        // restore crumbled platforms so a retry is never stale or soft-locked
        java.util.Arrays.fill(crumbleT, -1f);
        java.util.Arrays.fill(gone, false);
        spawnAtCheckpoint(checkpoint);
    }

    // ---------------------------------------------------------------- helpers

    private float dsTo(int i) { return course.dsWrap(es1[i], s); }  // element - player

    public float platformVy(int i) { return (ey1[i] - ey0[i]) / DT; }
    public float platformVs(int i) { return course.dsWrap(es1[i], es0[i]) / DT; }

    public boolean attachedTo(int idx) {
        if (idx < 0) return false;
        return ((mode == Mode.GROUND || mode == Mode.LEDGE || mode == Mode.ROPE || mode == Mode.CABLE) && onElem == idx);
    }

    // ---------------------------------------------------------------- step

    public void step(InputState in) {
        float dt = DT;
        ps0 = s; py0 = y; teleported = false;
        time += dt;
        int n = course.size();
        int cnt0 = act == null ? n : act.length;
        for (int k0 = 0; k0 < cnt0; k0++) {
            int i = act == null ? k0 : act[k0];
            es0[i] = es1[i]; ey0[i] = ey1[i];
            Element e = course.get(i);
            if (e.isMoving()) { es1[i] = e.sAt(time); ey1[i] = e.yAt(time); }
            if (padSquash[i] > 0) padSquash[i] = Math.max(0, padSquash[i] - dt);
            if (e.type == Element.Type.CRUMBLE) {
                if (gone[i]) { goneT[i] -= dt; if (goneT[i] <= 0) { gone[i] = false; crumbleT[i] = -1f; } }
                else if (crumbleT[i] >= 0) { crumbleT[i] += dt; if (crumbleT[i] >= T.crumbleDelay) { gone[i] = true; goneT[i] = T.crumbleRespawn; events |= EV_CRUMBLE; } }
            }
        }

        if (in.jumpPressed) jumpBuf = T.jumpBuffer + assistForgive; else jumpBuf = Math.max(0, jumpBuf - dt);
        if (lockout > 0) lockout = Math.max(0, lockout - dt);

        switch (mode) {
            case GROUND: stepGround(in, dt); break;
            case AIR: stepAir(in, dt); break;
            case ROPE: stepRope(in, dt); break;
            case CABLE: stepCable(in, dt); break;
            case LEDGE: stepLedge(in, dt); break;
            case PULLUP: stepPull(dt); break;
        }
        prevJumpHeld = in.jumpHeld;
        s = course.wrap(s);
        if (y > maxHeight) maxHeight = y;

        if (mode != Mode.AIR) lastGroundY = y;
        else if (y > lastGroundY && mode == Mode.AIR && vy <= 0) { /* keep */ }
        if (y < lastGroundY - T.fallRespawnDepth) respawn();
    }

    private void doJump(float boost) {
        vy = T.jumpVel + boost; jumpedUp = true; mode = Mode.AIR; coyote = 0; jumpBuf = 0; onElem = -1;
        events |= EV_JUMP;
    }

    private void stepGround(InputState in, float dt) {
        int e = onElem;
        // ride the platform
        float dsP = course.dsWrap(es1[e], es0[e]);
        s += dsP; y = ey1[e];
        // horizontal control
        float target = in.moveX * T.runSpeed;
        float a = (Math.abs(target) > 0.01f && Math.signum(target) == Math.signum(vx) || Math.abs(vx) < 0.01f) ? T.groundAccel : T.groundDecel;
        if (Math.abs(target) < 0.01f) a = T.groundDecel;
        vx = approach(vx, target, a * dt);
        if (Math.abs(in.moveX) > 0.15f) facing = in.moveX > 0 ? 1 : -1;
        s += vx * dt;
        coyote = T.coyote + assistForgive;
        if (jumpBuf > 0) {
            float pv = platformVy(e);
            vx += platformVs(e) * 0.6f;
            doJump(Math.max(0, pv) * 0.6f);
            return;
        }
        // leave the edge?
        Element el = course.get(e);
        float off = Math.abs(course.dsWrap(s, es1[e]));
        if (off > el.halfW() + T.edgeOverhang || gone[e]) {
            mode = Mode.AIR; onElem = -1; vy = Math.min(0, platformVy(e)); jumpedUp = false;
            vx += platformVs(e) * 0.5f;
            return;
        }
        // crumble trigger
        if (el.type == Element.Type.CRUMBLE && crumbleT[e] < 0) crumbleT[e] = 0;
        // checkpoint / win
        if (el.checkpoint && e > checkpoint) { checkpoint = e; events |= EV_CHECKPOINT; }
        if (el.type == Element.Type.GOAL && !won) { won = true; events |= EV_WIN; }
        progress(e);
    }

    private void stepAir(InputState in, float dt) {
        // horizontal
        float target = in.moveX * T.runSpeed;
        if (Math.abs(in.moveX) > 0.05f) vx = approach(vx, target, T.airAccel * dt);
        else vx = approach(vx, 0, T.airDrag * dt);
        if (Math.abs(in.moveX) > 0.15f) facing = in.moveX > 0 ? 1 : -1;
        // vertical
        vy -= T.gravity * dt;
        if (vy < -T.maxFall) vy = -T.maxFall;
        if (jumpedUp && !in.jumpHeld && vy > T.jumpVel * T.jumpCutMul) vy = T.jumpVel * T.jumpCutMul;
        if (vy <= 0) jumpedUp = false;
        if (coyote > 0) {
            coyote -= dt;
            if (jumpBuf > 0 && coyote > 0) { doJump(0); return; }
        }
        float py = y, ps = s;
        // previous feet height relative to previous platform tops handled per element
        s += vx * dt; y += vy * dt;
        // landing
        int best = -1; float bestTop = -1e9f;
        for (int k1 = 0, cnt1 = act == null ? course.size() : act.length; k1 < cnt1; k1++) {
            int i = act == null ? k1 : act[k1];
            Element el = course.get(i);
            if (!el.isPlatform() || gone[i]) continue;
            if (Math.abs(ey1[i] - y) > 8f) continue;
            float d = Math.abs(course.dsWrap(es1[i], s));
            if (d > el.halfW() + T.edgeOverhang) continue;
            float relVy = vy - platformVy(i);
            if (relVy > 0) continue;
            if (py >= ey0[i] - 0.08f && y <= ey1[i] + 0.0001f) {
                if (ey1[i] > bestTop) { bestTop = ey1[i]; best = i; }
            }
        }
        if (best >= 0) { land(best, in); return; }
        if (lockout <= 0) { if (tryGrab(in)) return; }
    }

    private void land(int i, InputState in) {
        Element el = course.get(i);
        y = ey1[i];
        landSpeed = -vy;
        if (el.type == Element.Type.PAD) {
            vy = in.jumpHeld ? T.padBounceHeld : T.padBounce;
            jumpedUp = false; mode = Mode.AIR; onElem = -1; lastPad = i; padSquash[i] = 0.25f;
            events |= EV_BOUNCE;
            progress(i);
            return;
        }
        vy = 0; mode = Mode.GROUND; onElem = i; jumpedUp = false; coyote = T.coyote + assistForgive;
        events |= EV_LAND;
        if (el.checkpoint && i > checkpoint) { checkpoint = i; events |= EV_CHECKPOINT; }
        if (el.type == Element.Type.CRUMBLE && crumbleT[i] < 0) crumbleT[i] = 0;
        if (el.type == Element.Type.GOAL && !won) { won = true; events |= EV_WIN; }
        progress(i);
        // buffered jump on landing
        if (jumpBuf > 0) { vx += platformVs(i) * 0.6f; doJump(Math.max(0, platformVy(i)) * 0.6f); }
    }

    // ---------------------------------------------------------------- grabbing

    private boolean tryGrab(InputState in) {
        float hand = y + T.handHeight;
        // ropes
        for (int k2 = 0, cnt2 = act == null ? course.size() : act.length; k2 < cnt2; k2++) {
            int i = act == null ? k2 : act[k2];
            Element el = course.get(i);
            if (el.type == Element.Type.ROPE) {
                if (Math.abs(course.dsWrap(es1[i], s)) <= T.ropeGrabRadius && hand <= el.y + 0.1f && hand >= el.yBottom()) {
                    mode = Mode.ROPE; onElem = i; vx = vy = 0; s = es1[i]; events |= EV_GRAB | EV_ROPE;
                    progress(i);
                    return true;
                }
            } else if (el.type == Element.Type.CABLE) {
                float half = el.w * 0.5f;
                float d = course.dsWrap(s, es1[i]);
                if (Math.abs(d) <= half && hand >= el.y - 0.45f && hand <= el.y + 0.15f && vy < 4f) {
                    mode = Mode.CABLE; onElem = i; vx = vy = 0; y = el.y - T.handHeight; events |= EV_GRAB | EV_CABLE;
                    progress(i);
                    return true;
                }
            }
        }
        // ledges
        if (vy > 3.5f) return false;
        for (int k3 = 0, cnt3 = act == null ? course.size() : act.length; k3 < cnt3; k3++) {
            int i = act == null ? k3 : act[k3];
            Element el = course.get(i);
            if (!el.isPlatform() || el.type == Element.Type.PAD || gone[i]) continue;
            float top = ey1[i];
            if (hand < top - T.ledgeReachBelow || hand > top + T.ledgeReachAbove) continue;
            if (y >= top - 0.1f) continue;           // feet above the top: would have landed
            float d = course.dsWrap(es1[i], s);      // + => platform ahead (to +s)
            float edgeDist = Math.abs(d) - el.halfW();   // distance from player center to the near vertical face
            if (edgeDist > T.ledgeReachX || edgeDist < -0.12f) continue;
            int side = d > 0 ? 1 : -1;
            boolean toward = (side > 0 ? (vx > 0.3f || in.moveX > 0.3f) : (vx < -0.3f || in.moveX < -0.3f));
            if (!toward) continue;
            ledgeSide = side; onElem = i; mode = Mode.LEDGE; vx = vy = 0; facing = side; events |= EV_GRAB;
            s = course.wrap(es1[i] - side * (el.halfW() + 0.24f));
            y = top - T.handHeight + 0.08f;
            return true;
        }
        return false;
    }

    private void stepRope(InputState in, float dt) {
        int i = onElem; Element el = course.get(i);
        s = es1[i]; vx = 0;
        float climb = in.moveY * T.climbSpeed;
        y += climb * dt;
        float yMax = el.y - T.handHeight, yMin = el.yBottom() - 0.2f;
        if (y > yMax) y = yMax;
        if (y < yMin) y = yMin;
        if (Math.abs(in.moveX) > 0.15f) facing = in.moveX > 0 ? 1 : -1;
        if (jumpBuf > 0) {
            float dir = Math.abs(in.moveX) > 0.2f ? Math.signum(in.moveX) : facing;
            vx = dir * T.runSpeed * 0.95f; facing = (int) dir;
            doJump(-T.jumpVel * 0.12f); lockout = T.grabLockout;
            return;
        }
        if (in.moveY < -0.7f && y <= yMin + 0.01f) { mode = Mode.AIR; onElem = -1; lockout = T.grabLockout; vy = 0; }
    }

    private void stepCable(InputState in, float dt) {
        int i = onElem; Element el = course.get(i);
        y = el.y - T.handHeight;
        s += in.moveX * T.cableShimmy * dt;
        if (Math.abs(in.moveX) > 0.15f) facing = in.moveX > 0 ? 1 : -1;
        float d = course.dsWrap(s, es1[i]);
        float half = el.w * 0.5f;
        if (jumpBuf > 0) {
            vx = in.moveX * T.runSpeed; doJump(-T.jumpVel * 0.25f); lockout = T.grabLockout; return;
        }
        if (in.moveY < -0.7f || Math.abs(d) > half + 0.1f) {
            mode = Mode.AIR; onElem = -1; vx = in.moveX * 2f; vy = 0; lockout = T.grabLockout; jumpedUp = false;
        }
    }

    private void stepLedge(InputState in, float dt) {
        int i = onElem; Element el = course.get(i);
        if (gone[i]) { mode = Mode.AIR; onElem = -1; lockout = T.grabLockout; vy = 0; return; }
        s = course.wrap(es1[i] - ledgeSide * (el.halfW() + 0.24f));
        y = ey1[i] - T.handHeight + 0.08f;
        if (in.moveY > 0.5f || jumpBuf > 0) {
            jumpBuf = 0;
            mode = Mode.PULLUP; pullT = 0; pullFromS = s; pullFromY = y; events |= EV_PULL;
            return;
        }
        boolean away = ledgeSide > 0 ? in.moveX < -0.6f : in.moveX > 0.6f;
        if (in.moveY < -0.6f || away) { mode = Mode.AIR; onElem = -1; lockout = T.grabLockout; vy = 0; vx = 0; }
    }

    private void stepPull(float dt) {
        int i = onElem; Element el = course.get(i);
        pullT += dt;
        float k = Math.min(1f, pullT / T.pullUpTime);
        float toS = es1[i] - ledgeSide * (el.halfW() - 0.35f);
        float fromS = course.wrap(es1[i] - ledgeSide * (el.halfW() + 0.24f));
        // easing: up first, then forward
        float up = Math.min(1f, k * 1.6f), fw = Math.max(0f, (k - 0.45f) / 0.55f);
        y = (ey1[i] - T.handHeight + 0.08f) + (T.handHeight - 0.08f) * up;
        s = course.wrap(fromS + (toS - fromS) * fw);
        if (k >= 1f) {
            s = course.wrap(toS); y = ey1[i]; vx = vy = 0; mode = Mode.GROUND; onElem = i; coyote = T.coyote;
            if (el.checkpoint && i > checkpoint) { checkpoint = i; events |= EV_CHECKPOINT; }
            if (el.type == Element.Type.CRUMBLE && crumbleT[i] < 0) crumbleT[i] = 0;
            if (el.type == Element.Type.GOAL && !won) { won = true; events |= EV_WIN; }
            progress(i);
        }
    }

    private static float approach(float v, float target, float delta) {
        if (v < target) return Math.min(target, v + delta);
        return Math.max(target, v - delta);
    }
}
