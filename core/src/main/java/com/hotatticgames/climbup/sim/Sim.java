package com.hotatticgames.climbup.sim;

/**
 * Deterministic fixed-step platformer simulation on a wrapped (cylindrical) arc coordinate.
 * No rendering or engine dependencies; cloneable so tests and the course validator can look ahead.
 */
public final class Sim {
    public enum Mode { GROUND, AIR, ROPE, CABLE, LEDGE, PULLUP }

    public static final float DT = 1f / 60f, SWING_TIME = 0.34f;
    // event bits
    public static final int EV_JUMP = 1, EV_LAND = 2, EV_BOUNCE = 4, EV_GRAB = 8, EV_PULL = 16, EV_CRUMBLE = 32,
            EV_CHECKPOINT = 64, EV_RESPAWN = 128, EV_WIN = 256, EV_ROPE = 512, EV_CABLE = 1024, EV_FALL_NEAR = 2048, EV_HIT = 4096, EV_KEY = 8192, EV_DOOR = 16384, EV_BLOCKED = 32768, EV_CLUB = 65536, EV_SWING = 131072, EV_SHOVE = 262144, EV_CRAB_OFF = 524288;

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
    public float[] onT;             // ramps: seconds spent standing on them (shaking / sinking ramps)
    public float[] tilt;             // seesaw bridges: slope (height gained per metre toward +s) of each element
    public float floorY = Float.NEGATIVE_INFINITY;   // endless: height of the lowest platform in the window; falling past it (nothing left to land on) respawns
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
    public float[] es0, ey0, es1, ey1;            // previous/current positions
    public float[] crumbleT;                      // <0 idle, >=0 counting, set to +big when gone
    public boolean[] gone;
    public float[] goneT;
    public float[] padSquash;
    public float invuln;                          // brief grace after a respawn so a hazard can never chain-kill
    public int hits;                              // hazard hits so far
    public int keys;                              // bit per colour of the keys carried
    public boolean keysFree;                      // planners/demos: every gate simply opens on touch
    public boolean[] featDone = new boolean[0];   // per feature (Course.hazards index): key taken / gate opened
    public int lastKeyColor, lastGateColor;
    public float clubTime, swingT, shoveCd;       // spiked club carried (seconds left), swing animation clock, grace between shoves
    public boolean knockedBee;
    public float crabS, crabY;                    // where the last crab was knocked off (effects)
    public Element hitBy;
    public float hitS, hitY;                      // where the last hit happened (effects)
    public int[] act;                // planner window: element indices simulated (null = everything)
    public int[] hz;                 // hazard indices simulated (null = every hazard)
    public int winLo = 0, winHi = Integer.MAX_VALUE;   // element index range covered by the window (renderers iterate just this)

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
        hz = course.hazardsFor(lo - 3, hi + 3);
    }

    private void progress(int i) { if (i > bestElem && course.get(i).anchor < 0 && i < course.routeSize()) bestElem = i; }

    /** Endless worlds: simulate only elements lo..hi (inclusive); refreshes positions of elements that newly enter the window. */
    public void setRange(int lo, int hi) {
        lo = Math.max(0, lo); hi = Math.min(course.size() - 1, hi);
        ensureCapacity();
        int[] a = new int[Math.max(0, hi - lo + 1)];
        for (int i = lo; i <= hi; i++) a[i - lo] = i;
        if (act == null || act.length == 0 || act[0] > lo || act[act.length - 1] < hi) {
            int oldLo = act == null || act.length == 0 ? Integer.MAX_VALUE : act[0], oldHi = act == null || act.length == 0 ? -1 : act[act.length - 1];
            for (int i = lo; i <= hi; i++) if (i < oldLo || i > oldHi) { Element e = course.get(i); es0[i] = es1[i] = e.sAt(time); ey0[i] = ey1[i] = e.yAt(time); }
        }
        act = a; winLo = lo; winHi = hi;
        float fl = Float.POSITIVE_INFINITY;
        for (int i = lo; i <= hi; i++) { Element e = course.get(i); if (e.isPlatform()) fl = Math.min(fl, e.y - Math.abs(e.amp) - 1f); }
        floorY = fl == Float.POSITIVE_INFINITY ? Float.NEGATIVE_INFINITY : fl;
        hz = course.hazardsFor(lo - 6, hi + 6);
    }

    /** The course grew (endless mode): extend the per-element state arrays. */
    public void ensureCapacity() {
        if (featDone.length < course.hazards.size()) featDone = java.util.Arrays.copyOf(featDone, Math.max(course.hazards.size(), featDone.length * 3 / 2 + 8));
        int n = course.size();
        if (n <= es0.length) return;
        int m = Math.max(n, es0.length * 3 / 2 + 16), o = es0.length;
        es0 = java.util.Arrays.copyOf(es0, m); ey0 = java.util.Arrays.copyOf(ey0, m); es1 = java.util.Arrays.copyOf(es1, m); ey1 = java.util.Arrays.copyOf(ey1, m);
        crumbleT = java.util.Arrays.copyOf(crumbleT, m); gone = java.util.Arrays.copyOf(gone, m); goneT = java.util.Arrays.copyOf(goneT, m); padSquash = java.util.Arrays.copyOf(padSquash, m); tilt = java.util.Arrays.copyOf(tilt, m); onT = java.util.Arrays.copyOf(onT, m);
        java.util.Arrays.fill(crumbleT, o, m, -1f);
        for (int i = o; i < n; i++) { Element e = course.get(i); es0[i] = es1[i] = e.sAt(time); ey0[i] = ey1[i] = e.yAt(time); }
    }

    public Sim(Course c, Tuning t) {
        this.course = c; this.T = t;
        int n = c.size();
        es0 = new float[n]; ey0 = new float[n]; es1 = new float[n]; ey1 = new float[n];
        crumbleT = new float[n]; gone = new boolean[n]; goneT = new float[n]; padSquash = new float[n]; tilt = new float[n]; onT = new float[n];
        java.util.Arrays.fill(crumbleT, -1f);
        featDone = new boolean[c.hazards.size() + 8];
        refreshElements();
        spawnAtCheckpoint(0);
    }

    private Sim(Sim o) {
        course = o.course; T = o.T;
        time = o.time; s = o.s; y = o.y; vx = o.vx; vy = o.vy; facing = o.facing; mode = o.mode; onElem = o.onElem;
        lastPad = o.lastPad; coyote = o.coyote; jumpBuf = o.jumpBuf; lockout = o.lockout; pullT = o.pullT;
        pullFromS = o.pullFromS; pullFromY = o.pullFromY; pullToS = o.pullToS; pullToY = o.pullToY;
        ledgeSide = o.ledgeSide; jumpedUp = o.jumpedUp; prevJumpHeld = o.prevJumpHeld; lastGroundY = o.lastGroundY; floorY = o.floorY;
        checkpoint = o.checkpoint; bestElem = o.bestElem; maxHeight = o.maxHeight; won = o.won; falls = o.falls;
        landSpeed = o.landSpeed; events = o.events; assistForgive = o.assistForgive; ps0 = o.ps0; py0 = o.py0; teleported = o.teleported;
        es0 = o.es0.clone(); ey0 = o.ey0.clone(); es1 = o.es1.clone(); ey1 = o.ey1.clone();
        crumbleT = o.crumbleT.clone(); gone = o.gone.clone(); goneT = o.goneT.clone(); padSquash = o.padSquash.clone(); tilt = o.tilt.clone(); onT = o.onT.clone();
        clubTime = o.clubTime; swingT = o.swingT; shoveCd = o.shoveCd; crabS = o.crabS; crabY = o.crabY; keys = o.keys; keysFree = o.keysFree; featDone = o.featDone.clone(); lastKeyColor = o.lastKeyColor; lastGateColor = o.lastGateColor;
        act = o.act; hz = o.hz; winLo = o.winLo; winHi = o.winHi; invuln = o.invuln; hits = o.hits; hitS = o.hitS; hitY = o.hitY;
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
        m.keysFree = true;      // planners assume the key is in hand; the solver proves separately that the key can be fetched
        Element e = c.get(idx);
        m.checkpoint = 0;
        switch (e.type) {
            case PAD: case SPRING:
                m.s = c.wrap(m.es1[idx]); m.y = m.ey1[idx];
                m.vx = e.type == Element.Type.SPRING ? t.padBounce * (float) Math.sin(e.amp) : 0f; m.vy = e.type == Element.Type.SPRING ? t.padBounce * (float) Math.cos(e.amp) : t.padBounce; m.mode = Mode.AIR;
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
                for (Element h : c.hazards) {      // platforms with a hazard in the middle: start in the safe pocket at the back, as a real landing would
                    if (h.anchor == idx && (h.type == Element.Type.SPIKE_TRAP || h.type == Element.Type.SAW_H || h.type == Element.Type.SPIKE_BLOCK || h.type == Element.Type.SPIKE_DROP || h.type == Element.Type.CRAB) && Math.abs(c.dsWrap(h.s, e.s)) < e.halfW()) {
                        m.s = c.wrap(m.es1[idx] - e.halfW() + 1.0f); break;
                    }
                }
                if (e.crumbles()) m.crumbleT[idx] = 0;
                if (e.type == Element.Type.RAMP) m.s = c.wrap(m.es1[idx] - e.halfW() + 0.4f);
                if (e.type == Element.Type.SEESAW) m.s = c.wrap(m.es1[idx] - e.halfW() + 0.7f);
        }
        return m;
    }

    public void respawn() {
        falls++; invuln = 0.7f;
        events |= EV_RESPAWN; teleported = true;
        // restore crumbled platforms so a retry is never stale or soft-locked
        java.util.Arrays.fill(crumbleT, -1f);
        java.util.Arrays.fill(gone, false);
        spawnAtCheckpoint(checkpoint);
    }

    // ---------------------------------------------------------------- helpers

    public static final float RAMP_SINK_DELAY = 0.35f, RAMP_SINK_MAX = 3.2f, RAMP_SINK_SPEED = 2.2f, RAMP_SHAKE_EVERY = 0.55f;

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
        if (course.size() > es0.length || course.hazards.size() > featDone.length) ensureCapacity();
        int n = course.size();
        int cnt0 = act == null ? n : act.length;
        for (int k0 = 0; k0 < cnt0; k0++) {
            int i = act == null ? k0 : act[k0];
            es0[i] = es1[i]; ey0[i] = ey1[i];
            Element e = course.get(i);
            if (e.isMoving()) { es1[i] = e.sAt(time); ey1[i] = e.yAt(time); }
            else if (e.type == Element.Type.SEESAW) {          // surface height is reported at the player's own position along the plank
                float hw = e.halfW(), x = Math.max(-hw, Math.min(hw, course.dsWrap(s, es1[i])));
                boolean on = mode == Mode.GROUND && onElem == i;
                float kp = tilt[i], tgt = on ? -Math.max(-1f, Math.min(1f, x / hw)) * T.seesawMaxTilt : 0f;
                float kn = approach(kp, tgt, (on ? T.seesawRate : T.seesawRelax) * dt);
                tilt[i] = kn; ey0[i] = e.y + kp * x; ey1[i] = e.y + kn * x;
            } else if (e.type == Element.Type.RAMP) {          // sloped walkway: surface height at the player's own position along it
                float hw = e.halfW(), x = Math.max(-hw, Math.min(hw, course.dsWrap(s, es1[i])));
                boolean on = mode == Mode.GROUND && onElem == i;
                onT[i] = on ? onT[i] + dt : Math.max(0f, onT[i] - 1.5f * dt);
                float d0 = e.skin == 3 ? tilt[i] : 0f, d1 = d0;
                if (e.skin == 3) { d1 = approach(d0, on && onT[i] > RAMP_SINK_DELAY ? RAMP_SINK_MAX : 0f, (on ? RAMP_SINK_SPEED : 0.7f) * dt); tilt[i] = d1; }
                ey0[i] = e.y + e.amp * x - d0; ey1[i] = e.y + e.amp * x - d1;
            }
            if (padSquash[i] > 0) padSquash[i] = Math.max(0, padSquash[i] - dt);
            if (e.crumbles()) {
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

        if (shoveCd > 0) shoveCd = Math.max(0f, shoveCd - dt);
        if (clubTime > 0) clubTime = Math.max(0f, clubTime - dt);
        if (swingT > 0) swingT = Math.max(0f, swingT - dt);
        else if (in.swingPressed && clubTime > 0f && (mode == Mode.GROUND || mode == Mode.AIR)) { swingT = SWING_TIME; events |= EV_SWING; }
        stepFeatures();
        if (invuln > 0) invuln = Math.max(0f, invuln - dt);
        else if (hazardHit()) { hits++; events |= EV_HIT; hitS = s; hitY = y; respawn(); return; }

        if (mode != Mode.AIR) lastGroundY = y;
        else if (y > lastGroundY && mode == Mode.AIR && vy <= 0) { /* keep */ }
        if (floorY > Float.NEGATIVE_INFINITY) { if (y < floorY - 6f) respawn(); }   // endless: a fall only ends below the lowest platform
        else if (y < lastGroundY - T.fallRespawnDepth) respawn();
    }

    private void stepClubCrab(Element e, int idx, float hw) {
        if (idx < featDone.length && featDone[idx]) return;
        if (e.type == Element.Type.CLUB) {
            float dx = course.dsWrap(s, e.s);
            if (Math.abs(dx) < 0.95f && e.y > y - 0.4f && e.y < y + T.height + 0.4f) { featDone[idx] = true; clubTime = CLUB_SECONDS; events |= EV_CLUB; }
            return;
        }
        if (e.type == Element.Type.BEE) { stepBee(e, idx, hw); return; }
        float cs = e.sAt(time), dx = course.dsWrap(s, cs);
        if (swingT > 0.06f && swingT < 0.28f) {                       // the club is on its way through: anything in front of you and within reach goes flying
            float ahead = -dx * facing;
            if (ahead > -0.3f && ahead < 2.2f && Math.abs(e.y - y) < 1.5f) { featDone[idx] = true; crabS = cs; crabY = e.y; knockedBee = false; events |= EV_CRAB_OFF; return; }
        }
        if (shoveCd <= 0f && invuln <= 0f && Math.abs(dx) < 0.55f + hw && y < e.y + 0.62f && y + T.height > e.y + 0.05f && (mode == Mode.GROUND || mode == Mode.AIR)) {
            float side = Math.signum(dx); if (side == 0f) side = -facing;
            vx = side * 9f; vy = Math.max(vy, 6f); mode = Mode.AIR; onElem = -1; lockout = 0.3f; jumpedUp = false; shoveCd = 0.9f; events |= EV_SHOVE;
        }
    }
    private void stepBee(Element e, int idx, float hw) {
        if (!e.beePresent(time)) return;
        float bs = e.beeS(time), by = e.beeY(time), dx = course.dsWrap(s, bs);
        if (swingT > 0.06f && swingT < 0.28f) {
            float ahead = -dx * facing;
            if (ahead > -0.3f && ahead < 2.2f && Math.abs(by - (y + 0.7f)) < 1.6f) { featDone[idx] = true; crabS = bs; crabY = by; knockedBee = true; events |= EV_CRAB_OFF; return; }
        }
        if (shoveCd <= 0f && invuln <= 0f && Math.abs(dx) < Element.BEE_R + hw && by + Element.BEE_R > y + 0.1f && by - Element.BEE_R < y + T.height - 0.05f && (mode == Mode.GROUND || mode == Mode.AIR)) {
            float side = Math.signum(dx); if (side == 0f) side = -facing;
            vx = side * 6.5f; vy = Math.max(vy, 3f); mode = Mode.AIR; onElem = -1; lockout = 0.25f; jumpedUp = false; shoveCd = 1.0f; events |= EV_SHOVE;
        }
    }
    public static final float CLUB_SECONDS = 18f;

    /** Keys are picked up by touching them; a closed castle gate is a wall until you carry its colour. */
    private void stepFeatures() {
        final float hw = T.halfWidth, lo = y, hi = y + T.height;
        for (int k = 0, cnt = hz == null ? course.hazards.size() : hz.length; k < cnt; k++) {
            int hi_ = hz == null ? k : hz[k];
            Element e = course.hazards.get(hi_);
            if (e.type == Element.Type.CLUB || e.type == Element.Type.CRAB || e.type == Element.Type.BEE) { stepClubCrab(e, hi_, hw); continue; }
            if (e.type != Element.Type.KEY && e.type != Element.Type.GATE) continue;
            if (hi_ < featDone.length && featDone[hi_]) continue;
            float dx = course.dsWrap(s, e.s);
            if (e.type == Element.Type.KEY) {
                float ky = e.y + 0.05f * (float) Math.sin(time * 3f);
                if (Math.abs(dx) < 0.95f && ky > lo - 0.6f && ky < hi + 0.4f) { featDone[hi_] = true; keys |= 1 << e.color; lastKeyColor = e.color; events |= EV_KEY; }
            } else {
                float half = e.w * 0.5f + hw;
                if (Math.abs(dx) >= half || hi <= e.y - 1f || lo >= e.y + e.len) continue;
                if (keysFree || (keys & (1 << e.color)) != 0) {
                    featDone[hi_] = true; if (!keysFree) keys &= ~(1 << e.color);
                    lastGateColor = e.color; events |= EV_DOOR;
                } else {
                    float side = Math.signum(course.dsWrap(ps0, e.s)); if (side == 0f) side = Math.signum(dx) == 0f ? 1f : Math.signum(dx);
                    s = course.wrap(e.s + side * (half + 0.01f));
                    if (vx * side < 0f) vx = 0f;
                    events |= EV_BLOCKED; lastGateColor = e.color;
                }
            }
        }
    }

    /** True if the player's body overlaps any lethal hazard right now. */
    private boolean hazardHit() {
        final float hw = T.halfWidth - 0.03f, lo = y + 0.12f, hi = y + T.height - 0.1f;
        for (int k = 0, cnt = hz == null ? course.hazards.size() : hz.length; k < cnt; k++) {
            Element e = course.hazards.get(hz == null ? k : hz[k]);
            hitBy = e;
            if (!e.lethalAt(time)) continue;
            float dx = Math.abs(course.dsWrap(e.sAt(time), s));
            float r = e.discR();
            if (r > 0f) {
                float ddx = Math.max(0f, dx - hw), cy = e.yAt(time);
                float ddy = cy < lo ? lo - cy : (cy > hi ? cy - hi : 0f);
                if (ddx * ddx + ddy * ddy < r * r) return true;
            } else if (e.type == Element.Type.SPIKE_TRAP) {
                if (dx < e.w * 0.5f + hw && y > e.y - 0.5f && y < e.y + e.spikeHeight(time) - 0.08f) return true;
            } else if (e.type == Element.Type.SPIKE_DROP) {
                float b = e.dropBottom(time);
                if (dx < e.w * 0.5f + hw && hi > b && lo < b + Element.DROP_H) return true;
            } else {   // spike block: a solid lethal box [y, y+len]
                if (dx < e.w * 0.5f + hw && hi > e.y && lo < e.y + e.len) return true;
            }
        }
        return false;
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
        boolean saw = course.get(e).type == Element.Type.SEESAW;
        if (saw) { float up = tilt[e] * Math.signum(target) / T.seesawMaxTilt; if (up > 0) target *= Math.max(0.2f, 1f - T.seesawUphill * up); }
        float a = (Math.abs(target) > 0.01f && Math.signum(target) == Math.signum(vx) || Math.abs(vx) < 0.01f) ? T.groundAccel : T.groundDecel;
        if (Math.abs(target) < 0.01f) a = T.groundDecel;
        vx = approach(vx, target, a * dt);
        if (Math.abs(in.moveX) > 0.15f) facing = in.moveX > 0 ? 1 : -1;
        s += vx * dt;
        if (saw) s -= tilt[e] * T.seesawSlide * dt;       // the tipped plank slides you downhill whatever you do
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
        if (el.crumbles() && crumbleT[e] < 0) crumbleT[e] = 0;
        if (el.type == Element.Type.RAMP && el.skin == 2 && onT[e] >= RAMP_SHAKE_EVERY) {      // a shaking ramp throws you around
            int n = (int) tilt[e]; tilt[e] = n + 1; onT[e] = 0f;
            int h = (n * 73856093 + e * 19349663) >>> 7;
            vx = (h % 3 == 0 ? -1f : 1f) * (2.2f + (h % 5) * 0.5f); vy = 7.5f + (h % 4) * 0.8f;
            mode = Mode.AIR; onElem = -1; jumpedUp = false; coyote = 0; events |= EV_BOUNCE;
            return;
        }
        // checkpoint / win
        if (el.checkpoint && e > checkpoint) { checkpoint = e; events |= EV_CHECKPOINT; }
        if (el.type == Element.Type.GOAL && !won) { won = true; events |= EV_WIN; }
        progress(e);
    }

    private void stepAir(InputState in, float dt) {
        // horizontal
        float target = in.moveX * T.runSpeed;
        if (Math.abs(in.moveX) > 0.05f && !(Math.abs(vx) > Math.abs(target) && Math.signum(vx) == Math.signum(target))) vx = approach(vx, target, T.airAccel * dt);
        else vx = approach(vx, 0, T.airDrag * dt);      // launched faster than you can run (spring, moving platform): keep it, only drag eases it
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
        if (el.type == Element.Type.PAD || el.type == Element.Type.SPRING) {
            float v = in.jumpHeld ? T.padBounceHeld : T.padBounce;
            if (el.type == Element.Type.SPRING) { vy = v * (float) Math.cos(el.amp); vx = v * (float) Math.sin(el.amp); if (Math.abs(vx) > 0.5f) facing = vx > 0 ? 1 : -1; }
            else vy = v;
            jumpedUp = false; mode = Mode.AIR; onElem = -1; lastPad = i; padSquash[i] = 0.25f;
            events |= EV_BOUNCE;
            progress(i);
            return;
        }
        vy = 0; mode = Mode.GROUND; onElem = i; jumpedUp = false; coyote = T.coyote + assistForgive;
        events |= EV_LAND;
        if (el.checkpoint && i > checkpoint) { checkpoint = i; events |= EV_CHECKPOINT; }
        if (el.crumbles() && crumbleT[i] < 0) crumbleT[i] = 0;
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
            if (!el.isPlatform() || el.type == Element.Type.PAD || el.type == Element.Type.SPRING || gone[i]) continue;
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
        float fromS = course.wrap(es1[i] - ledgeSide * (el.halfW() + 0.24f));
        float toS = fromS + course.dsWrap(es1[i] - ledgeSide * (el.halfW() - 0.35f), fromS);   // short way round the ring
        // easing: up first, then forward
        float up = Math.min(1f, k * 1.6f), fw = Math.max(0f, (k - 0.45f) / 0.55f);
        y = (ey1[i] - T.handHeight + 0.08f) + (T.handHeight - 0.08f) * up;
        s = course.wrap(fromS + (toS - fromS) * fw);
        if (k >= 1f) {
            s = course.wrap(toS); y = ey1[i]; vx = vy = 0; mode = Mode.GROUND; onElem = i; coyote = T.coyote;
            if (el.checkpoint && i > checkpoint) { checkpoint = i; events |= EV_CHECKPOINT; }
            if (el.crumbles() && crumbleT[i] < 0) crumbleT[i] = 0;
            if (el.type == Element.Type.GOAL && !won) { won = true; events |= EV_WIN; }
            progress(i);
        }
    }

    private static float approach(float v, float target, float delta) {
        if (v < target) return Math.min(target, v + delta);
        return Math.max(target, v - delta);
    }
}
