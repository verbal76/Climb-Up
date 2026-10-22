package com.hotatticgames.climbup.sim;

/**
 * Deterministic fixed-step platformer simulation on a wrapped (cylindrical) arc coordinate.
 * No rendering or engine dependencies; cloneable so tests and the course validator can look ahead.
 */
public final class Sim {
    public enum Mode { GROUND, AIR, ROPE, CABLE, LEDGE, PULLUP, BEAM }

    public static final float DT = 1f / 60f, SWING_TIME = 0.34f;
    // event bits
    public static final int EV_JUMP = 1, EV_LAND = 2, EV_BOUNCE = 4, EV_GRAB = 8, EV_PULL = 16, EV_CRUMBLE = 32,
            EV_CHECKPOINT = 64, EV_RESPAWN = 128, EV_WIN = 256, EV_ROPE = 512, EV_CABLE = 1024, EV_FALL_NEAR = 2048, EV_HIT = 4096, EV_KEY = 8192, EV_DOOR = 16384, EV_BLOCKED = 32768, EV_CLUB = 65536, EV_SWING = 131072, EV_SHOVE = 262144, EV_CRAB_OFF = 524288, EV_FINISH = 1048576, EV_AIM = 2097152,
            EV_DASH = 4194304, EV_BLOB_JUMP = 8388608, EV_BLOB_LAND = 16777216, EV_SQUASH = 33554432, EV_LAUNCH = 67108864, EV_SLIP = 134217728;   // Build 71: a skull starts a dash, a blob leaps, a blob lands, you were squashed, a skull or a tipping bridge launched you

    public final Course course;
    public final Tuning T;

    public float time;
    // player
    public float s, y, vx, vy;
    public int facing = 1;
    public Mode mode = Mode.AIR;
    public int onElem = -1;          // standing / hanging / roping element index
    public int lastPad = -1;
    public int onSlab = -1;          // hazard index of the spike slab whose (safe) top the hero stands on; mode GROUND with onElem < 0
    public static final float SLAB_BOUNCE_SPEED = 6f, SLAB_SLAM_SPEED = 6f;
    public float coyote, jumpBuf, lockout, pullT, pullFromS, pullFromY, pullToS, pullToY;
    public int ledgeSide = 1;
    private int sideIn = -1, sideDir = 1;      // a platform whose side the body has slipped into while rising fast (see trackSideEntry), and which side it came from
    public float ropeTopT;           // seconds the climber has pushed up against the top of a rope (a short hold mounts the beam)
    public static final float BEAM_MOUNT_TIME = 0.62f, BEAM_TOP = 0.24f, BEAM_HALF = 1.1f, ROPE_TOP_HOLD = 0.06f;
    public boolean jumpedUp;         // current ascent came from a jump (variable height cut applies)
    public boolean prevJumpHeld;
    public float lastGroundY;
    public float[] onT;             // ramps: seconds spent standing on them (shaking / sinking ramps)
    public float[] tilt;             // seesaw bridges: slope (height gained per metre toward +s) of each element
    private float minPlatY = Float.POSITIVE_INFINITY; private int minPlatSize = -1;
    public float floorY = Float.NEGATIVE_INFINITY;   // endless: height of the lowest platform in the window; falling past it (nothing left to land on) respawns
    public int checkpoint;
    public int bestElem;             // highest route element reached (progress)
    public float maxHeight;
    public boolean won;
    public int falls;
    public float landSpeed;          // for squash feedback
    public int events;               // accumulated since last consumeEvents()
    public float ps0, py0;           // player position at the start of the last step (render interpolation)
    public boolean deferRespawn, respawnPending;    // a managed world window: the respawn is completed by Tower.maintain
    public float floorOverride = Float.NaN;          // managed window: the height of the lowest platform of the WHOLE tower (the resident part is not all of it)
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
    public float[] aimAng = new float[0], aimFireT = new float[0];   // aimed cannons (Course.hazards index): barrel angle (0 = along +s, counter-clockwise), time the last ball was fired
    private boolean[] aimArmed = new boolean[0];                      // this cycle's shot was locked while the player was in range
    public float[] crabPos = new float[0], crabVel = new float[0];   // crabs (Course.hazards index): live arc, and the arc it moved this step (its facing), once it has left its sweep (Build 63, owner: "once they see you, they should chase you")
    private byte[] crabMode = new byte[0];                            // 0 on its sweep (the patrol, a pure function of time), 1 chasing, 2 walking back to the sweep
    private float[] crabStuck = new float[0], crabCool = new float[0];   // seconds it has been held at its edge with you out of reach; seconds left before it may chase again
    private float[] crabTurn = new float[0];                              // seconds left standing still to turn round (it faces the new way at once)
    private float[] crabSeen = new float[0];                              // seconds it has had you in sight (it reacts after CRAB_NOTICE)
    /** Build 71 creatures (Course.hazards index): bee drift off its post; skull and blob state (mobState), clock in that state, live arc, height above the deck, heading, target arc, and how long you have been in sight. */
    public byte[] mobState = new byte[0];
    public float[] mobT = new float[0], mobS = new float[0], mobY = new float[0], mobDir = new float[0], mobTgt = new float[0], mobSeen = new float[0];
    public float squashT;
    public float slipCd;                                                   // seconds before ice can set off another skid (EV_SLIP)                                                  // seconds left of the player being squashed flat (drawing only)
    public int lastKeyColor, lastGateColor, lastGateNo;
    public boolean finishedRun;         // the finishCastle's door has been walked through (the timed run is over)
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

    /** Like {@link #setWindow} but also simulates earlier route platforms that hang near the given elements (the climb winds round, so a platform passed a few steps ago can sit right over a side branch). */
    public void setWindowNear(int rLo, int rHi, int a, int b) {
        setWindow(rLo, rHi);
        int lo = Math.max(0, rLo);
        java.util.ArrayList<Integer> extra = new java.util.ArrayList<>();
        for (int i = Math.max(0, lo - 12); i < lo; i++) {
            Element e = course.get(i);
            for (int ref : new int[]{a, b}) {
                Element r = course.get(ref);
                if (Math.abs(course.dsWrap(e.s, r.s)) < 12f && Math.abs(e.y - r.y) < 9f) { extra.add(i); break; }
            }
        }
        if (extra.isEmpty()) return;
        int[] n = new int[act.length + extra.size()];
        System.arraycopy(act, 0, n, 0, act.length);
        for (int k = 0; k < extra.size(); k++) n[act.length + k] = extra.get(k);
        act = n;
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
        if (minPlatSize != course.size()) {          // the lowest platform of the WHOLE map: a fall only ends when you land on something or drop below all of it
            float fl = Float.POSITIVE_INFINITY;
            for (int i = 0; i < course.size(); i++) { Element e = course.get(i); if (e.isPlatform()) fl = Math.min(fl, e.y - Math.abs(e.amp) - 1f); }
            minPlatY = fl; minPlatSize = course.size();
        }
        floorY = !Float.isNaN(floorOverride) ? floorOverride : (minPlatY == Float.POSITIVE_INFINITY ? Float.NEGATIVE_INFINITY : minPlatY);
        hz = course.hazardsFor(lo - 6, hi + 6);
    }

    private void growAim(int n) {
        if (aimAng.length >= n) return;
        int o = aimAng.length;
        aimAng = java.util.Arrays.copyOf(aimAng, n); aimFireT = java.util.Arrays.copyOf(aimFireT, n); aimArmed = java.util.Arrays.copyOf(aimArmed, n);
        java.util.Arrays.fill(aimFireT, o, n, -1e9f);
        crabPos = java.util.Arrays.copyOf(crabPos, n); crabVel = java.util.Arrays.copyOf(crabVel, n); crabMode = java.util.Arrays.copyOf(crabMode, n);
        crabStuck = java.util.Arrays.copyOf(crabStuck, n); crabCool = java.util.Arrays.copyOf(crabCool, n); crabTurn = java.util.Arrays.copyOf(crabTurn, n); crabSeen = java.util.Arrays.copyOf(crabSeen, n);
        mobState = java.util.Arrays.copyOf(mobState, n); mobT = java.util.Arrays.copyOf(mobT, n); mobS = java.util.Arrays.copyOf(mobS, n); mobY = java.util.Arrays.copyOf(mobY, n);
        mobDir = java.util.Arrays.copyOf(mobDir, n); mobTgt = java.util.Arrays.copyOf(mobTgt, n); mobSeen = java.util.Arrays.copyOf(mobSeen, n);
    }

    /** Width of {@link #hazardState}: every live per-hazard value the simulation carries (aimed cannons, crabs, Build 71 creatures). */
    public static final int HAZ_STATE = 17;
    /** Build 71 (save/load determinism): the dynamic state of one hazard, packed, so a saved climb restores its cannons, crabs, bees, skulls and blobs mid-move. */
    public float[] hazardState(int i) {
        ensureCapacity();
        return new float[]{aimAng[i], aimFireT[i], aimArmed[i] ? 1f : 0f, crabPos[i], crabVel[i], crabMode[i], crabStuck[i], crabCool[i], crabTurn[i], crabSeen[i],
                mobState[i], mobT[i], mobS[i], mobY[i], mobDir[i], mobTgt[i], mobSeen[i]};
    }
    /** Writes back what {@link #hazardState} packed (ignored unless it has the right width and is all finite). */
    public void setHazardState(int i, float[] v) {
        if (v == null || v.length != HAZ_STATE) return;
        for (float f : v) if (Float.isNaN(f) || Float.isInfinite(f)) return;
        ensureCapacity();
        if (i < 0 || i >= aimAng.length) return;
        aimAng[i] = v[0]; aimFireT[i] = v[1]; aimArmed[i] = v[2] != 0f; crabPos[i] = v[3]; crabVel[i] = v[4]; crabMode[i] = (byte) v[5]; crabStuck[i] = v[6]; crabCool[i] = v[7]; crabTurn[i] = v[8]; crabSeen[i] = v[9];
        mobState[i] = (byte) v[10]; mobT[i] = v[11]; mobS[i] = v[12]; mobY[i] = v[13]; mobDir[i] = v[14]; mobTgt[i] = v[15]; mobSeen[i] = v[16];
    }

    /** The course grew (endless mode): extend the per-element state arrays. */
    public void ensureCapacity() {
        if (featDone.length < course.hazards.size()) featDone = java.util.Arrays.copyOf(featDone, Math.max(course.hazards.size(), featDone.length * 3 / 2 + 8));
        growAim(featDone.length);
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
        growAim(featDone.length);
        refreshElements();
        spawnAtCheckpoint(0);
    }

    private Sim(Sim o) {
        course = o.course; T = o.T;
        time = o.time; s = o.s; y = o.y; vx = o.vx; vy = o.vy; facing = o.facing; mode = o.mode; onElem = o.onElem;
        lastPad = o.lastPad; onSlab = o.onSlab; coyote = o.coyote; jumpBuf = o.jumpBuf; lockout = o.lockout; pullT = o.pullT;
        ropeTopT = o.ropeTopT; pullFromS = o.pullFromS; pullFromY = o.pullFromY; pullToS = o.pullToS; pullToY = o.pullToY;
        ledgeSide = o.ledgeSide; sideIn = o.sideIn; sideDir = o.sideDir; jumpedUp = o.jumpedUp; prevJumpHeld = o.prevJumpHeld; lastGroundY = o.lastGroundY; floorY = o.floorY;
        checkpoint = o.checkpoint; bestElem = o.bestElem; maxHeight = o.maxHeight; won = o.won; falls = o.falls;
        landSpeed = o.landSpeed; events = o.events; assistForgive = o.assistForgive; ps0 = o.ps0; py0 = o.py0; teleported = o.teleported;
        es0 = o.es0.clone(); ey0 = o.ey0.clone(); es1 = o.es1.clone(); ey1 = o.ey1.clone();
        crumbleT = o.crumbleT.clone(); gone = o.gone.clone(); goneT = o.goneT.clone(); padSquash = o.padSquash.clone(); tilt = o.tilt.clone(); onT = o.onT.clone();
        clubTime = o.clubTime; swingT = o.swingT; shoveCd = o.shoveCd; crabS = o.crabS; crabY = o.crabY; keys = o.keys; keysFree = o.keysFree; featDone = o.featDone.clone(); aimAng = o.aimAng.clone(); aimFireT = o.aimFireT.clone(); aimArmed = o.aimArmed.clone(); crabPos = o.crabPos.clone(); crabVel = o.crabVel.clone(); crabMode = o.crabMode.clone(); crabStuck = o.crabStuck.clone(); crabCool = o.crabCool.clone(); crabTurn = o.crabTurn.clone(); crabSeen = o.crabSeen.clone(); mobState = o.mobState.clone(); mobT = o.mobT.clone(); mobS = o.mobS.clone(); mobY = o.mobY.clone(); mobDir = o.mobDir.clone(); mobTgt = o.mobTgt.clone(); mobSeen = o.mobSeen.clone(); squashT = o.squashT; slipCd = o.slipCd; finishedRun = o.finishedRun; lastGateNo = o.lastGateNo; lastKeyColor = o.lastKeyColor; lastGateColor = o.lastGateColor;
        act = o.act; hz = o.hz; winLo = o.winLo; winHi = o.winHi; invuln = o.invuln; hits = o.hits; hitS = o.hitS; hitY = o.hitY;
        humanHold = o.humanHold; heldX = o.heldX; heldY = o.heldY; heldXT = o.heldXT; heldYT = o.heldYT;
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

    /**
     * After a saved state was written into this sim (time, position, tilt of seesaws and ramps): puts every element where it is at {@link #time}, so the first step sees no phantom platform motion.
     * Slopes and tipping planks report their surface at the player's own position, exactly as {@link #step} does.
     */
    public void syncElements() { syncElements(s); }

    /** As {@link #syncElements()}, with ramps and seesaws measured where the hero stood at the start of the step ({@code surfaceS}), exactly as {@link #step} measures them. */
    public void syncElements(float surfaceS) {
        refreshElements();
        for (int i = 0; i < course.size(); i++) {
            Element e = course.get(i);
            if (e.type == Element.Type.SEESAW) {
                float hw = e.halfW(), x = Math.max(-hw, Math.min(hw, course.dsWrap(surfaceS, es1[i])));
                ey0[i] = ey1[i] = e.y + tilt[i] * x;
            } else if (e.type == Element.Type.RAMP) {
                float hw = e.halfW(), x = Math.max(-hw, Math.min(hw, course.dsWrap(surfaceS, es1[i])));
                ey0[i] = ey1[i] = e.y + e.rampSlope(time) * x;
            }
        }
        sideIn = -1;
    }

    public int consumeEvents() { int e = events; events = 0; return e; }

    // ---------------------------------------------------------------- spawning

    public void spawnAtCheckpoint(int idx) {
        checkpoint = idx;
        Element e = course.get(idx);
        s = course.wrap(es1[idx]); y = ey1[idx]; vx = vy = 0;
        mode = Mode.GROUND; onElem = idx; lastGroundY = y; coyote = 0; jumpBuf = 0; lockout = 0.1f;
        jumpedUp = false; facing = 1; onSlab = -1;
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
                m.vx = e.type == Element.Type.SPRING ? t.padBounce * (float) StrictMath.sin(e.amp) : 0f; m.vy = e.type == Element.Type.SPRING ? t.padBounce * (float) StrictMath.cos(e.amp) : t.padBounce; m.mode = Mode.AIR;
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
                    if (h.anchor == idx && (h.type == Element.Type.SPIKE_TRAP || h.type == Element.Type.SAW_H || h.type == Element.Type.SPIKE_BLOCK || h.type == Element.Type.SPIKE_DROP || h.type == Element.Type.CRAB || h.type == Element.Type.SKULL || h.type == Element.Type.BLOB) && Math.abs(c.dsWrap(h.s, e.s)) < e.halfW()) {
                        m.s = c.wrap(m.es1[idx] - e.halfW() + 1.0f); break;
                    }
                }
                if (e.crumbles()) m.crumbleT[idx] = 0;
                if (e.type == Element.Type.RAMP) m.s = c.wrap(m.es1[idx] - e.halfW() + 0.4f);
                if (e.type == Element.Type.SEESAW) m.s = c.wrap(m.es1[idx] - e.halfW() + 0.7f);
        }
        return m;
    }

    /** A hazard never teleports you: it knocks you off whatever you hold, and you land on what is below or, if nothing is, fall back to the last red gem. */
    private void knockOff() {
        float side = hitBy == null ? 0f : course.dsWrap(s, hitBy.sAt(time));
        mode = Mode.AIR; onElem = -1; coyote = 0; jumpBuf = 0;
        vx = (side >= 0f ? 1f : -1f) * T.hitKnockSide; vy = T.hitKnockUp; invuln = 0.7f;      // tuning.json: hitKnockSide / hitKnockUp
    }

    /** Falls to the checkpoint plus hazard knock-offs: any of them means a move was not clean (the solver and the generator proofs reject such moves). */
    public int setbacks() { return falls + hits; }

    /** Falling past the lowest platform of the tower (or being sent back for any reason): back to the checkpoint. A sim whose world window is managed (see Tower.maintain) only flags it, because the checkpoint's slice may need to be loaded first. */
    public void respawn() {
        if (deferRespawn) { respawnPending = true; return; }
        completeRespawn(checkpoint);
    }

    public void completeRespawn(int idx) {
        respawnPending = false;
        falls++; invuln = 0.7f;
        lastPad = -1;          // Build 72: a launch from a pad before the fall is history; left set, the next hop off the checkpoint read as that pad's launch
        events |= EV_RESPAWN; teleported = true;
        // restore crumbled platforms so a retry is never stale or soft-locked
        java.util.Arrays.fill(crumbleT, -1f);
        java.util.Arrays.fill(gone, false);
        spawnAtCheckpoint(idx);
    }

    /**
     * The world course was rebuilt around a different set of slices and a new origin (see Tower): carry every index and every height over so the simulation continues exactly where it was.
     * Indices that are no longer resident become -1.
     */
    public void rebase(Tower.Remap m) {
        int n = m.newN, oldN = es0.length;
        float[] nes0 = new float[n], ney0 = new float[n], nes1 = new float[n], ney1 = new float[n], nc = new float[n], ngt = new float[n], npq = new float[n], ntl = new float[n], not = new float[n];
        boolean[] ngone = new boolean[n];
        java.util.Arrays.fill(nc, -1f);
        for (int i = 0; i < n; i++) { Element e = course.get(i); nes0[i] = nes1[i] = e.sAt(time); ney0[i] = ney1[i] = e.yAt(time); }
        for (int i = 0; i < m.elem.length && i < oldN; i++) {
            int j = m.elem[i]; if (j < 0) continue;
            // Build 74: the current height is taken from the rebuilt element itself, never by shifting the old one: a float shifted by the origin and the same
            // height rebuilt around that origin can differ in the last bit, and whether a far-off platform was resident during a shift depends on how far ahead
            // the tower happened to be generated (a background thread), so the shifted copy made the same inputs land a step's worth differently on
            // different devices (ReplayTool diff). Static platforms keep this height for good; movers, seesaws and ramps set it again every step anyway.
            Element e = course.get(j);
            nes0[j] = e.sAt(time - DT); nes1[j] = e.sAt(time); ney0[j] = ey0[i] - m.dy; ney1[j] = e.yAt(time);
            nc[j] = crumbleT[i]; ngone[j] = gone[i]; ngt[j] = goneT[i]; npq[j] = padSquash[i]; ntl[j] = tilt[i]; not[j] = onT[i];
        }
        sideIn = sideIn >= 0 && sideIn < m.elem.length ? m.elem[sideIn] : -1;
        es0 = nes0; ey0 = ney0; es1 = nes1; ey1 = ney1; crumbleT = nc; gone = ngone; goneT = ngt; padSquash = npq; tilt = ntl; onT = not;
        boolean[] nf = new boolean[Math.max(m.newH, 1) + 8];
        for (int i = 0; i < m.haz.length && i < featDone.length; i++) { int j = m.haz[i]; if (j >= 0) nf[j] = featDone[i]; }
        featDone = nf;
        float[] na = new float[nf.length], nft = new float[nf.length]; boolean[] nar = new boolean[nf.length]; java.util.Arrays.fill(nft, -1e9f);
        for (int i = 0; i < m.haz.length && i < aimAng.length; i++) { int j = m.haz[i]; if (j >= 0 && j < na.length) { na[j] = aimAng[i]; nft[j] = aimFireT[i]; nar[j] = aimArmed[i]; } }
        aimAng = na; aimFireT = nft; aimArmed = nar;
        float[] ncp = new float[nf.length], ncv = new float[nf.length], ncs = new float[nf.length], ncc = new float[nf.length], nct = new float[nf.length], ncn = new float[nf.length]; byte[] ncm = new byte[nf.length];
        for (int i = 0; i < m.haz.length && i < crabPos.length; i++) { int j = m.haz[i]; if (j >= 0 && j < ncp.length) { ncp[j] = crabPos[i]; ncv[j] = crabVel[i]; ncm[j] = crabMode[i]; ncs[j] = crabStuck[i]; ncc[j] = crabCool[i]; nct[j] = crabTurn[i]; ncn[j] = crabSeen[i]; } }
        crabPos = ncp; crabVel = ncv; crabMode = ncm; crabStuck = ncs; crabCool = ncc; crabTurn = nct; crabSeen = ncn;
        byte[] nms = new byte[nf.length]; float[] nmt = new float[nf.length], nmS = new float[nf.length], nmy = new float[nf.length], nmd = new float[nf.length], nmg = new float[nf.length], nmn = new float[nf.length];
        for (int i = 0; i < m.haz.length && i < mobState.length; i++) { int j = m.haz[i]; if (j >= 0 && j < nms.length) { nms[j] = mobState[i]; nmt[j] = mobT[i]; nmS[j] = mobS[i]; nmy[j] = mobY[i]; nmd[j] = mobDir[i]; nmg[j] = mobTgt[i]; nmn[j] = mobSeen[i]; } }
        mobState = nms; mobT = nmt; mobS = nmS; mobY = nmy; mobDir = nmd; mobTgt = nmg; mobSeen = nmn;      // arcs are wrap-invariant and mobY is relative to the deck, so no height shift is needed
        onElem = onElem >= 0 && onElem < m.elem.length ? m.elem[onElem] : onElem;
        lastPad = lastPad >= 0 && lastPad < m.elem.length ? m.elem[lastPad] : lastPad;
        onSlab = onSlab >= 0 && onSlab < m.haz.length ? m.haz[onSlab] : -1;
        checkpoint = checkpoint >= 0 && checkpoint < m.elem.length ? m.elem[checkpoint] : checkpoint;
        bestElem = bestElem >= 0 && bestElem < m.elem.length ? Math.max(0, m.elem[bestElem]) : bestElem;
        float dy = m.dy;
        y -= dy; py0 -= dy; lastGroundY -= dy; pullFromY -= dy; pullToY -= dy; hitY -= dy; crabY -= dy; maxHeight -= dy; floorY -= dy;
        hitBy = null; minPlatSize = -1;
        setRange(0, n - 1);
    }

    // ---------------------------------------------------------------- helpers

    /** Build 72 (owner: "it should bounce you all around"): a shaking ramp kicks you this soon after you step on, and again each time you land back on it. */
    public static final float RAMP_SHAKE_EVERY = 0.25f;
    /** Build 72 (owner): the spinning ramp's sideways push on whoever stands on it, at most this fast (m/s); it swings left and right with the turn and carries into a jump. */
    public static final float SPIN_CARRY = 2.6f;
    /** Build 72 (owner: "you trip-hop over it"): walking off a surface onto a fixed neighbour at most this much higher (or a little lower) carries straight on. */
    public static final float STEP_ACROSS = 0.2f;

    private float dsTo(int i) { return course.dsWrap(es1[i], s); }  // element - player

    public float platformVy(int i) { return (ey1[i] - ey0[i]) / DT; }
    public float platformVs(int i) { return course.dsWrap(es1[i], es0[i]) / DT + surfaceCarry(i); }
    /** The spinning ramp's sideways push now (0 for anything else). */
    /** How fast the surface itself carries a body along +s: a spinning ramp's sideways push, or a conveyor belt. */
    public float surfaceCarry(int i) { Element e = course.get(i); return e.spins() ? SPIN_CARRY * (float) StrictMath.sin(e.spinAngle(time)) : e.beltV(); }

    public boolean attachedTo(int idx) {
        if (idx < 0) return false;
        return ((mode == Mode.GROUND || mode == Mode.LEDGE || mode == Mode.ROPE || mode == Mode.CABLE || mode == Mode.BEAM) && onElem == idx);
    }

    // ---------------------------------------------------------------- step

    /** Below this the stick is centred. */
    public static final float STICK_DEAD = 0.05f;
    /**
     * Owner (Build 72, "Option B"): one consistent movement speed. The stick gives the direction, not how fast: any tilt past {@link #STICK_DEAD} is a full
     * push, for touch, pad, keys and the autopilots alike, so running, air control, ropes and cables all move at their one tuned speed.
     */
    public static float direction(float v) { return Math.abs(v) < STICK_DEAD ? 0f : Math.signum(v); }
    /**
     * The course generator's proofs and the solvers stand in for a person, and a person cannot flick a stick back and forth every frame: when this is above 0
     * (only the autopilots set it; the player's own climb leaves it at 0) a stick direction is held at least this many steps before it can change.
     */
    public int humanHold;
    private float heldX, heldY; private int heldXT, heldYT;

    public void step(InputState in) {
        if (respawnPending) return;          // frozen until the window has been rebuilt around the checkpoint
        float dX = direction(in.moveX), dY = direction(in.moveY);
        if (humanHold > 0 && dX != heldX && heldXT < humanHold) dX = heldX;
        if (humanHold > 0 && dY != heldY && heldYT < humanHold) dY = heldY;
        if (dX != heldX) { heldX = dX; heldXT = 0; } else if (heldXT < 1 << 20) heldXT++;
        if (dY != heldY) { heldY = dY; heldYT = 0; } else if (heldYT < 1 << 20) heldYT++;
        in.moveX = dX; in.moveY = dY;
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
            if (e.isMoving()) {
                es1[i] = e.sAt(time); ey1[i] = e.yAt(time);
                if (e.type == Element.Type.MOVE_Z) gone[i] = Math.abs(e.zAt(time)) > Element.Z_REACH && !(mode == Mode.GROUND && onElem == i);   // out of your plane: nothing to land on
            }
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
                ey0[i] = e.y + e.rampSlope(time - dt) * x; ey1[i] = e.y + e.rampSlope(time) * x;
            }
            if (padSquash[i] > 0) padSquash[i] = Math.max(0, padSquash[i] - dt);
            if (e.crumbles()) {
                if (gone[i]) { goneT[i] -= dt; if (goneT[i] <= 0) { gone[i] = false; crumbleT[i] = -1f; } }
                else if (crumbleT[i] >= 0) { crumbleT[i] += dt; if (crumbleT[i] >= (e.type == Element.Type.CRUMBLE || e.type == Element.Type.RAMP ? T.crumbleDelay : T.moverCrumbleDelay)) { gone[i] = true; goneT[i] = T.crumbleRespawn; events |= EV_CRUMBLE; } }
            }
        }

        if (mode != Mode.AIR) sideIn = -1;
        if (in.jumpPressed) jumpBuf = T.jumpBuffer + assistForgive; else jumpBuf = Math.max(0, jumpBuf - dt);
        if (lockout > 0) lockout = Math.max(0, lockout - dt);

        switch (mode) {
            case GROUND: if (onElem < 0 && onSlab >= 0) stepSlab(in, dt); else stepGround(in, dt); break;
            case AIR: stepAir(in, dt); break;
            case ROPE: stepRope(in, dt); break;
            case CABLE: stepCable(in, dt); break;
            case LEDGE: stepLedge(in, dt); break;
            case PULLUP: stepPull(dt); break;
            case BEAM: stepBeam(in, dt); break;
        }
        prevJumpHeld = in.jumpHeld;
        s = course.wrap(s);
        if (y > maxHeight) maxHeight = y;

        if (shoveCd > 0) shoveCd = Math.max(0f, shoveCd - dt);
        if (squashT > 0) squashT = Math.max(0f, squashT - dt);
        if (clubTime > 0) clubTime = Math.max(0f, clubTime - dt);
        if (swingT > 0) swingT = Math.max(0f, swingT - dt);
        else if (in.swingPressed && clubTime > 0f && (mode == Mode.GROUND || mode == Mode.AIR)) { swingT = SWING_TIME; events |= EV_SWING; }
        stepFeatures();
        stepAimed();
        stepGemPickup();
        if (invuln > 0) invuln = Math.max(0f, invuln - dt);
        else if (hazardHit()) { hits++; events |= EV_HIT; hitS = s; hitY = y; knockOff(); }

        if (mode != Mode.AIR) lastGroundY = y;
        else if (y > lastGroundY && mode == Mode.AIR && vy <= 0) { /* keep */ }
        if (floorY > Float.NEGATIVE_INFINITY) { if (y < floorY - 6f) respawn(); }   // endless: a fall only ends below the lowest platform
        else if (y < lastGroundY - T.fallRespawnDepth) respawn();
    }

    /** The checkpoint gem is collected by touching it (its pickup volume, {@link CheckpointGem}) as well as by landing on its platform; walking or running across is enough. */
    private void stepGemPickup() {
        if (mode == Mode.LEDGE || mode == Mode.PULLUP) return;          // hanging off the gem platform's lip: the landing collects it a moment later
        int n = act == null ? course.size() : act.length;
        for (int k = 0; k < n; k++) {
            int i = act == null ? k : act[k];
            if (i <= checkpoint || i <= 0 || i >= course.size()) continue;
            Element e = course.get(i);
            if (e.type != Element.Type.STATIC || !course.isCheckpoint(e)) continue;
            if (CheckpointGem.touches(course, e, es1[i], ey1[i], s, y, T)) { checkpoint = i; events |= EV_CHECKPOINT; }
        }
    }

    private void stepClubCrab(Element e, int idx, float hw) {
        if (idx < featDone.length && featDone[idx]) return;
        if (e.type == Element.Type.CLUB) {
            float dx = course.dsWrap(s, e.s);
            if (Math.abs(dx) < 0.95f && e.y > y - 0.4f && e.y < y + T.height + 0.4f) { featDone[idx] = true; clubTime = CLUB_SECONDS; events |= EV_CLUB; }
            return;
        }
        if (e.type == Element.Type.BEE) { stepBee(e, idx, hw); return; }
        float cs = stepCrab(e, idx), dx = course.dsWrap(s, cs);
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
        if (!e.beePresent(time)) { if (idx < mobS.length) { mobS[idx] = 0f; mobY[idx] = 0f; } return; }     // away: its next visit starts from its post again
        float[] b = beeAt(idx, e, true);
        float bs = b[0], by = b[1], dx = course.dsWrap(s, bs);
        if (swingT > 0.06f && swingT < 0.28f) {
            float ahead = -dx * facing;
            if (ahead > -0.3f && ahead < 2.2f && Math.abs(by - (y + 0.7f)) < 1.6f) { featDone[idx] = true; crabS = bs; crabY = by; knockedBee = true; events |= EV_CRAB_OFF; return; }
        }
        if (shoveCd <= 0f && invuln <= 0f && Math.abs(dx) < Element.BEE_R + hw && by + Element.BEE_R > y + 0.1f && by - Element.BEE_R < y + T.height - 0.05f && (mode == Mode.GROUND || mode == Mode.AIR)) {
            float side = Math.signum(dx); if (side == 0f) side = -facing;
            vx = side * Element.BEE_PUSH_X; vy = Math.max(vy, Element.BEE_PUSH_Y); mode = Mode.AIR; onElem = -1; lockout = 0.25f; jumpedUp = false; shoveCd = 1.0f; events |= EV_SHOVE;
        }
    }
    private final float[] beeTmp = new float[2];
    /**
     * Where bee {@code e} (hazard index idx) is: its flight path (Element.beeS / beeY) plus a drift toward you (Build 71). While it visits and you are within
     * BEE_SEEK of its post, its buzzing centre drifts toward you at BEE_DRIFT m/s up to BEE_REACH away, and its dive swoops most of the way to your chest.
     * {@code advance} moves the drift on one step (the simulation); false only reads it (the renderer). Driven by sim state alone: deterministic.
     */
    public float[] beeAt(int idx, Element e, boolean advance) {
        float ps = e.beeS(time), py = e.beeY(time);
        if (idx >= mobS.length) { beeTmp[0] = ps; beeTmp[1] = py; return beeTmp; }
        if (advance) {
            float dPost = course.dsWrap(s, e.s), dyPost = y - e.y;
            boolean seek = Math.abs(dPost) < Element.BEE_SEEK && dyPost > -4f && dyPost < 3f && (mode == Mode.GROUND || mode == Mode.AIR);
            float tx = seek ? Math.max(-Element.BEE_REACH, Math.min(Element.BEE_REACH, dPost)) : 0f;
            float ty = seek ? Math.max(-1.2f, Math.min(1.2f, (y + 0.9f) - e.y)) : 0f;
            float st = Element.BEE_DRIFT * DT;
            mobS[idx] = approach(mobS[idx], tx, st); mobY[idx] = approach(mobY[idx], ty, st);
        }
        float bs = ps + mobS[idx], by = py + mobY[idx];
        float dive = e.beeDive(time);
        if (dive > 0f) {           // the dive swoops at you (most of the way), limited to BEE_REACH from where it was
            float toS = Math.max(-Element.BEE_REACH, Math.min(Element.BEE_REACH, course.dsWrap(s, bs))), toY = Math.max(-2f, Math.min(1.5f, (y + 0.8f) - by));
            float k = dive * Element.BEE_SWOOP;
            bs += toS * k; by += toY * k;
        }
        beeTmp[0] = bs; beeTmp[1] = by; return beeTmp;
    }
    // ---------------------------------------------------------------- skull and green blob (Build 71, owner)
    private Element deckOf(Element e) { return e.anchor >= 0 && e.anchor < course.size() && course.get(e.anchor).isPlatform() ? course.get(e.anchor) : null; }
    /** You are over a creature's deck (or just past its ends) and near its height, on foot or in the air. */
    private boolean onDeck(float dS, float half, float dY) {
        return (mode == Mode.GROUND || mode == Mode.AIR) && Math.abs(course.dsWrap(s, dS)) <= half + 0.3f && y - dY > -0.4f && y - dY < 2.8f;
    }
    private static float smooth01(float k) { k = Math.max(0f, Math.min(1f, k)); return k * k * (3f - 2f * k); }

    /**
     * Skull: 1 hovers and bobs above one end of its deck; once you have been on the deck SKULL_NOTICE and it has rested SKULL_REST, 2 it drops low (SKULL_WINDUP),
     * 3 dashes along the deck at SKULL_SPEED toward your side to the far end, 4 rises and turns round (SKULL_TURN), and hovers again. Only the dash hits:
     * it is low (centre SKULL_LOW above the deck), so a jump clears it; a hit throws you hard along its way. Pure sim state: deterministic.
     */
    private void stepSkull(Element e, int idx, float hw) {
        if (idx >= mobState.length) return;
        Element deck = deckOf(e);
        float dS = deck != null ? es1[e.anchor] : e.s, half = deck != null ? deck.halfW() : Math.max(2f, e.w * 0.5f), dY = deck != null ? deck.y : e.y;
        float end = Math.max(0.5f, half - Element.SKULL_END);
        if (mobState[idx] == 0) { mobState[idx] = 1; mobS[idx] = course.wrap(dS + end); mobDir[idx] = -1f; mobT[idx] = Element.SKULL_REST; mobY[idx] = Element.SKULL_HOVER; mobSeen[idx] = 0f; }
        boolean sees = onDeck(dS, half, dY);
        mobSeen[idx] = sees ? mobSeen[idx] + DT : 0f;
        mobT[idx] += DT;
        switch (mobState[idx]) {
            case 1: {
                mobY[idx] = Element.SKULL_HOVER + 0.12f * (float) StrictMath.sin(time * 2.6f + e.phase);
                if (sees && mobSeen[idx] >= Element.SKULL_NOTICE && mobT[idx] >= Element.SKULL_REST) {
                    float d = course.dsWrap(s, mobS[idx]), dir = d > 0.05f ? 1f : d < -0.05f ? -1f : mobDir[idx];
                    float tgt = dS + dir * end;
                    if (Math.abs(course.dsWrap(tgt, mobS[idx])) < 1.2f) { dir = -dir; tgt = dS + dir * end; }      // already at that end: go the other way
                    mobDir[idx] = dir; mobTgt[idx] = course.wrap(tgt); mobState[idx] = 2; mobT[idx] = 0f;
                }
                break;
            }
            case 2: {
                float k = smooth01(mobT[idx] / Element.SKULL_WINDUP);
                mobY[idx] = Element.SKULL_HOVER + (Element.SKULL_LOW - Element.SKULL_HOVER) * k;
                if (mobT[idx] >= Element.SKULL_WINDUP) { mobState[idx] = 3; mobT[idx] = 0f; events |= EV_DASH; }
                break;
            }
            case 3: {
                mobY[idx] = Element.SKULL_LOW;
                float rem = course.dsWrap(mobTgt[idx], mobS[idx]) * mobDir[idx], stp = Element.SKULL_SPEED * DT;
                if (rem <= stp) { mobS[idx] = mobTgt[idx]; mobState[idx] = 4; mobT[idx] = 0f; }
                else mobS[idx] = course.wrap(mobS[idx] + mobDir[idx] * stp);
                float cy = dY + mobY[idx], dx = course.dsWrap(s, mobS[idx]);
                if (shoveCd <= 0f && invuln <= 0f && Math.abs(dx) < Element.SKULL_R + hw && cy - Element.SKULL_R < y + T.height && cy + Element.SKULL_R > y + 0.05f && (mode == Mode.GROUND || mode == Mode.AIR)) {
                    vx = mobDir[idx] * Element.SKULL_LAUNCH_X; vy = Math.max(vy, Element.SKULL_LAUNCH_Y); mode = Mode.AIR; onElem = -1; coyote = 0f; lockout = 0.35f; jumpedUp = false; shoveCd = 0.9f;
                    events |= EV_SHOVE | EV_LAUNCH;
                }
                break;
            }
            default: {
                float k = smooth01(mobT[idx] / Element.SKULL_TURN);
                mobY[idx] = Element.SKULL_LOW + (Element.SKULL_HOVER - Element.SKULL_LOW) * k;
                if (mobT[idx] >= Element.SKULL_TURN) { mobDir[idx] = -mobDir[idx]; mobState[idx] = 1; mobT[idx] = 0f; }
                break;
            }
        }
    }
    /** The skull's live arc / height above its deck / heading (for the renderer and the planner's reflex); state 3 = dashing. */
    public boolean skullDashing(int hi) { return hi < mobState.length && mobState[hi] == 3; }

    /**
     * Green blob: 1 sits; once you have been on its deck BLOB_NOTICE and it has rested, 2 wobbles (BLOB_WOBBLE), 3 leaps BLOB_APEX up off the screen and falls back over
     * BLOB_AIR; BLOB_SHADOW before it lands it picks its spot - where you are (kept on its deck), or, if you stand on a tipping bridge, the far end from you - and its
     * shadow shows there; 4 it lands and rests. Landing on you squashes you flat and bounces you away; landing on the far end of the bridge you stand on launches you
     * BLOB_LAUNCH times the jump speed (three times the jump height). Pure sim state: deterministic.
     */
    private void stepBlob(Element e, int idx, float hw) {
        if (idx >= mobState.length) return;
        Element deck = deckOf(e); int a = e.anchor;
        float dS = deck != null ? es1[a] : e.s, half = deck != null ? deck.halfW() : Math.max(2f, e.w * 0.5f), dY = deck != null ? deck.y : e.y;
        boolean saw = deck != null && deck.type == Element.Type.SEESAW;
        float end = Math.max(0.4f, half - Element.BLOB_END);
        if (mobState[idx] == 0) { mobState[idx] = 1; mobS[idx] = course.wrap(e.s); mobT[idx] = Element.BLOB_REST; mobDir[idx] = 0f; }
        boolean sees = onDeck(dS, half, dY);
        mobSeen[idx] = sees ? mobSeen[idx] + DT : 0f;
        float t0 = mobT[idx]; mobT[idx] += DT;
        switch (mobState[idx]) {
            case 1:
                mobY[idx] = 0f;
                if (sees && mobSeen[idx] >= Element.BLOB_NOTICE && mobT[idx] >= Element.BLOB_REST) { mobState[idx] = 2; mobT[idx] = 0f; }
                break;
            case 2:
                mobY[idx] = 0f;
                if (mobT[idx] >= Element.BLOB_WOBBLE) { mobState[idx] = 3; mobT[idx] = 0f; mobDir[idx] = 0f; events |= EV_BLOB_JUMP; }
                break;
            case 3: {
                mobY[idx] = Element.blobLeapY(Math.min(1f, mobT[idx] / Element.BLOB_AIR));
                float lockAt = Element.BLOB_AIR - Element.BLOB_SHADOW;
                if (t0 < lockAt && mobT[idx] >= lockAt) {              // it picks its spot now; its shadow shows there from here on
                    float x = course.dsWrap(s, dS);
                    if (saw && mode == Mode.GROUND && onElem == a) { float side = x >= 0f ? 1f : -1f; mobTgt[idx] = course.wrap(dS - side * end); mobDir[idx] = side; }
                    else { mobTgt[idx] = course.wrap(dS + Math.max(-end, Math.min(end, x))); mobDir[idx] = 0f; }
                    mobS[idx] = mobTgt[idx];
                }
                if (mobT[idx] >= Element.BLOB_AIR) {
                    mobY[idx] = 0f; mobState[idx] = 4; mobT[idx] = 0f; events |= EV_BLOB_LAND;
                    blobLands(deck, a, dS, dY, saw, idx, hw);
                }
                break;
            }
            default:
                mobY[idx] = 0f;
                if (mobT[idx] >= Element.BLOB_REST) { mobState[idx] = 1; mobT[idx] = Element.BLOB_REST; }
                break;
        }
    }
    private void blobLands(Element deck, int a, float dS, float dY, boolean saw, int idx, float hw) {
        float side = mobDir[idx];
        if (saw && side != 0f && mode == Mode.GROUND && onElem == a && (course.dsWrap(s, dS) >= 0f ? 1f : -1f) == side) {     // the far end of the bridge you are on: up you go
            vy = T.jumpVel * Element.BLOB_LAUNCH; mode = Mode.AIR; onElem = -1; coyote = 0f; jumpedUp = false; lockout = 0.2f;
            tilt[a] = side * T.seesawMaxTilt;
            events |= EV_LAUNCH;
            return;
        }
        float surf = blobSurfaceY(idx, deck, a, dY), dx = course.dsWrap(s, mobS[idx]);
        if (invuln <= 0f && Math.abs(dx) < Element.BLOB_R + hw && y < surf + 1.4f && y > surf - 0.6f && (mode == Mode.GROUND || mode == Mode.AIR)) {      // squashed flat, and bounced away
            float push = dx > 0f ? 1f : dx < 0f ? -1f : -facing;
            vx = push * Element.BLOB_PUSH_X; vy = Math.max(vy, Element.BLOB_PUSH_Y); mode = Mode.AIR; onElem = -1; coyote = 0f; lockout = 0.3f; jumpedUp = false; shoveCd = 0.9f;
            squashT = 0.45f; events |= EV_SQUASH | EV_SHOVE;
        }
    }
    /** Height of the deck surface under the blob (a tipping bridge's plank at the blob's own arc). */
    public float blobSurfaceY(int idx, Element deck, int a, float dY) {
        if (deck == null || deck.type != Element.Type.SEESAW || a < 0 || a >= tilt.length || idx >= mobS.length) return dY;
        float hw = deck.halfW(), x = Math.max(-hw, Math.min(hw, course.dsWrap(mobS[idx], es1[a])));
        return deck.y + tilt[a] * x;
    }
    /** Seconds left before the leaping blob lands, while its shadow shows (else -1): the renderer grows the shadow, the planner's reflex steps out of it. */
    public float blobShadow(int hi) {
        if (hi >= mobState.length || mobState[hi] != 3) return -1f;
        float left = Element.BLOB_AIR - mobT[hi];
        return left <= Element.BLOB_SHADOW ? left : -1f;
    }

    public static final float CLUB_SECONDS = 30f;      // Build 71 (owner): 30 seconds, with crabs and bees placed ahead to use it on (CourseGenerator.clubBoost)

    // ---------------------------------------------------------------- crabs (Build 63, owner: "once they see you, they should chase you")
    /** How far along its platform a crab sees you, and the height band above its deck it looks in (standing on its platform, or hopping over it). */
    public static final float CRAB_SIGHT = 4.0f, CRAB_SIGHT_LO = -0.3f, CRAB_SIGHT_HI = 2.2f;
    /** It only watches its own deck: you are seen while over its platform (this much past either edge), never while you line up the jump from the platform before it, so it cannot camp the edge you have to land on. */
    public static final float CRAB_SIGHT_OVER = 0.5f;
    /** Seconds a chasing crab stands still to turn round when you get past it (it faces the new way at once): a hop over it is a real escape, as it is for any crab. */
    public static final float CRAB_TURN = 0.35f;
    /** Seconds it must have you in sight before it reacts (it keeps to its sweep meanwhile): landing on its platform is never an instant shove, you get a moment to hop it or run. */
    public static final float CRAB_NOTICE = 0.35f;
    /** Its chase speed as a multiple of its peak patrol speed (2 pi amp / period): quicker than the sweep, slower than you running. */
    public static final float CRAB_CHASE = 1.5f;
    /** A crab keeps this far inside its platform's edge: it never runs off its own deck (owner), so it cannot be baited off. */
    public static final float CRAB_EDGE = 0.45f;
    /** Held at its edge with you out of reach for CRAB_STUCK seconds, it gives up (walks back to its sweep) and will not chase again for CRAB_COOL seconds: it never camps an edge you need to land on. */
    public static final float CRAB_STUCK = 0.6f, CRAB_COOL = 2.5f;

    /**
     * Advances crab {@code e} (hazard index {@code idx}) one step and returns its arc. On its sweep it follows the patrol exactly (as before Build 63,
     * a pure function of time); the moment it sees you over its own deck it runs at you at CRAB_CHASE times its peak patrol speed (stopping for
     * CRAB_TURN seconds to turn round when you are behind it), kept on its own platform; when it loses you it walks back onto the sweep and resumes it.
     * Driven by sim state alone, so it is deterministic and the generator's proofs and the autopilot (which hops it where it actually is) include the chase.
     */
    private float stepCrab(Element e, int idx) {
        float patrol = e.sAt(time);
        if (idx >= crabPos.length) return patrol;
        float cs = crabMode[idx] == 0 ? patrol : crabPos[idx];
        float dx = course.dsWrap(s, cs), dy = y - e.y;
        Element deck = e.anchor >= 0 && e.anchor < course.size() && course.get(e.anchor).isPlatform() ? course.get(e.anchor) : null;
        boolean over = deck == null || Math.abs(course.dsWrap(s, deck.s)) <= deck.halfW() + CRAB_SIGHT_OVER;     // it watches its own deck only
        boolean sees = over && Math.abs(dx) <= CRAB_SIGHT && dy >= CRAB_SIGHT_LO && dy <= CRAB_SIGHT_HI && (mode == Mode.GROUND || mode == Mode.AIR);
        if (crabCool[idx] > 0f) { crabCool[idx] = Math.max(0f, crabCool[idx] - DT); sees = false; }
        if (!sees) crabSeen[idx] = 0f;
        else if (crabMode[idx] != 1) { crabSeen[idx] += DT; if (crabSeen[idx] < CRAB_NOTICE) sees = false; }   // a moment's notice before it reacts; once chasing it keeps you in sight
        float stepLen = CRAB_CHASE * 2f * (float) Math.PI * Math.abs(e.amp) / Math.max(0.1f, e.period) * DT;
        float ns = cs;
        // the way it faces: along its sweep, else the way it last moved (or was turned)
        float face = crabMode[idx] == 0 ? (e.amp * (float) StrictMath.cos(2 * Math.PI * time / e.period + e.phase) >= 0f ? 1f : -1f) : (crabVel[idx] >= 0f ? 1f : -1f);
        boolean turning = false;
        if (sees) {
            float want = dx > 0f ? 1f : dx < 0f ? -1f : face;
            if (want != face) { crabTurn[idx] = CRAB_TURN; face = want; }                  // you are behind it: it stops and turns round before it comes on
            crabMode[idx] = 1;
            if (crabTurn[idx] > 0f) { crabTurn[idx] = Math.max(0f, crabTurn[idx] - DT); turning = true; }
            else ns = cs + want * Math.min(stepLen, Math.abs(dx));
        } else {
            crabTurn[idx] = 0f;
            if (crabMode[idx] != 0) {
                float back = course.dsWrap(patrol, cs);
                if (Math.abs(back) <= stepLen) { ns = patrol; crabMode[idx] = 0; } else { crabMode[idx] = 2; ns = cs + Math.signum(back) * stepLen; }
            }
        }
        if (deck != null) { float lim = Math.max(0f, deck.halfW() - CRAB_EDGE), off = course.dsWrap(ns, deck.s); if (off > lim) ns = deck.s + lim; else if (off < -lim) ns = deck.s - lim; }   // never off its own platform
        ns = course.wrap(ns);
        float moved = course.dsWrap(ns, cs);
        if (crabMode[idx] == 1 && !turning) {                                               // held at its edge with you beyond it: it soon loses interest, and does not camp there
            if (Math.abs(moved) < 1e-4f && Math.abs(dx) > 0.6f) { crabStuck[idx] += DT; if (crabStuck[idx] >= CRAB_STUCK) { crabMode[idx] = 2; crabCool[idx] = CRAB_COOL; crabStuck[idx] = 0f; } }
            else crabStuck[idx] = 0f;
        } else crabStuck[idx] = 0f;
        crabVel[idx] = Math.abs(moved) > 1e-5f ? moved : (crabMode[idx] == 1 ? face * 1e-3f : 0f);   // faces the way it moves; standing (turning, or held at its edge) it faces you
        crabPos[idx] = ns;
        return ns;
    }
    /** The crab's live arc (hazard index), for the renderer: where it really is while chasing or returning, else its sweep. */
    public float crabAt(int hi, Element e) { return hi < crabPos.length && crabMode[hi] != 0 ? crabPos[hi] : e.sAt(time); }
    /** The way the crab faces: the arc it moved this step while off its sweep, else the sweep's own direction. */
    public float crabFacing(int hi, Element e) {
        if (hi < crabVel.length && crabMode[hi] != 0) return crabVel[hi] >= 0f ? 1f : -1f;
        return e.amp * (float) StrictMath.cos(2 * Math.PI * time / e.period + e.phase) >= 0f ? 1f : -1f;
    }
    public boolean crabChasing(int hi) { return hi < crabMode.length && crabMode[hi] == 1; }

    /** Keys are picked up by touching them; a closed castle gate is a wall until you carry its colour. */
    private void stepFeatures() {
        final float hw = T.halfWidth, lo = y, hi = y + T.height;
        for (int k = 0, cnt = hz == null ? course.hazards.size() : hz.length; k < cnt; k++) {
            int hi_ = hz == null ? k : hz[k];
            Element e = course.hazards.get(hi_);
            if (e.type == Element.Type.CLUB || e.type == Element.Type.CRAB || e.type == Element.Type.BEE) { stepClubCrab(e, hi_, hw); continue; }
            if (e.type == Element.Type.SKULL) { stepSkull(e, hi_, hw); continue; }
            if (e.type == Element.Type.BLOB) { stepBlob(e, hi_, hw); continue; }
            if (e.type != Element.Type.KEY && e.type != Element.Type.GATE) continue;
            if (e.type == Element.Type.GATE && !finishedRun && e.skin > 0 && e.skin == T.finishCastle && hi_ < featDone.length && featDone[hi_]
                    && Math.abs(course.dsWrap(s, e.s)) < 0.35f && hi < e.y + e.len && lo > e.y - 1f) { finishedRun = true; events |= EV_FINISH; }          // walked through the finish castle's open door
            if (hi_ < featDone.length && featDone[hi_]) continue;
            float dx = course.dsWrap(s, e.s);
            if (e.type == Element.Type.KEY) {
                float ky = e.y + 0.05f * (float) StrictMath.sin(time * 3f);
                if (Math.abs(dx) < 0.95f && ky > lo - 0.6f && ky < hi + 0.4f) { featDone[hi_] = true; keys |= 1 << e.color; lastKeyColor = e.color; events |= EV_KEY; }
            } else {
                float half = e.w * 0.5f + hw;
                if (Math.abs(dx) >= half || hi <= e.y - 1f || lo >= e.y + e.len) continue;
                if (keysFree || (keys & (1 << e.color)) != 0) {
                    featDone[hi_] = true; if (!keysFree) keys &= ~(1 << e.color);
                    lastGateColor = e.color; lastGateNo = e.skin; events |= EV_DOOR;
                    // the castle is a checkpoint as well as the red gems (owner): opening its door saves you at its deck, so a long fall afterwards
                    // comes back to the castle - inside it, behind the door you opened (it stays open) - never to the gem half a section below
                    if (e.anchor > checkpoint && e.anchor < course.size()) { checkpoint = e.anchor; events |= EV_CHECKPOINT; }
                } else {
                    float side = Math.signum(course.dsWrap(ps0, e.s)); if (side == 0f) side = Math.signum(dx) == 0f ? 1f : Math.signum(dx);
                    s = course.wrap(e.s + side * (half + 0.01f));
                    if (vx * side < 0f) vx = 0f;
                    events |= EV_BLOCKED; lastGateColor = e.color;
                }
            }
        }
    }

    // ---------------------------------------------------------------- aimed cannons

    private static float angDiff(float a, float b) { float d = (a - b) % (2f * (float) Math.PI); if (d > Math.PI) d -= 2f * (float) Math.PI; else if (d < -Math.PI) d += 2f * (float) Math.PI; return d; }

    /** Where the ball of aimed cannon h is right now (arc, height) if it is in flight, else null. */
    public float[] aimedBall(int hi, Element h) {
        if (hi >= aimFireT.length) return null;
        float age = time - aimFireT[hi];
        if (age < 0f || age > Element.AIM_LIFE) return null;
        float d = Element.AIM_MUZZLE + Element.AIM_SPEED * age;      // starts at the muzzle tip, not the barrel centre
        return new float[]{h.s + (float) StrictMath.cos(aimAng[hi]) * d, h.y + (float) StrictMath.sin(aimAng[hi]) * d};
    }

    /** Aimed cannons: track the player (turning at a limited rate) while awake, lock at AIM_LOCK of the cycle, fire at AIM_FIRE along exactly the locked direction. Asleep (barrel idles up) when the player is out of range. */
    private void stepAimed() {
        for (int k = 0, cnt = hz == null ? course.hazards.size() : hz.length; k < cnt; k++) {
            int hi = hz == null ? k : hz[k];
            Element h = course.hazards.get(hi);
            if (h.type != Element.Type.AIMED || hi >= aimAng.length) continue;
            float c = h.cyc(time), pc = h.cyc(time - DT);
            float dx = course.dsWrap(s, h.s), dy = y + 0.7f - h.y;
            boolean inRange = dx * dx + dy * dy <= Element.AIM_RANGE * Element.AIM_RANGE;
            boolean near = inRange && dy >= -9f && dy <= 8f;             // the barrel ROTATES to face a nearby hero, including one still climbing up toward a cannon on a pillar, so it visibly tracks instead of idling straight up until he is level
            boolean awake = inRange && dy >= -4f && dy <= 5f;            // but it only ARMS and FIRES on its own layer of the spiral (the tower spirals; a hero on the turn above or below, same arc 10+ m away, must never be shot through the floor)
            float ageBall = time - aimFireT[hi];
            boolean ballFlying = ageBall >= 0f && ageBall < Element.AIM_LIFE;          // while its shot is still crossing the lane the barrel HOLDS the fired angle, so it points along the ball instead of already swinging back to re-aim at you (which read as "the ball came out the wrong way")
            if (c < Element.AIM_LOCK && !ballFlying) {
                float flight = Math.max(0f, Math.min(1f, (float) Math.sqrt(dx * dx + dy * dy) / Element.AIM_SPEED));   // ~time for the ball to reach you
                float lead = Element.AIM_LEAD * flight;
                float tdx = dx + vx * lead, tdy = dy + vy * lead;                      // lead the player: aim where they are heading, so a cannon you have passed shoots across in front of you
                float target = near ? (float) StrictMath.atan2(tdy, tdx) : (float) Math.PI * 0.5f;
                float d = angDiff(target, aimAng[hi]), step = Element.AIM_TURN * DT;
                aimAng[hi] += Math.max(-step, Math.min(step, d));
            }
            if (pc < Element.AIM_LOCK && c >= Element.AIM_LOCK) aimArmed[hi] = awake;
            if (pc < Element.AIM_FIRE && c >= Element.AIM_FIRE && aimArmed[hi]) { aimFireT[hi] = time; aimArmed[hi] = false; events |= EV_AIM; }
        }
    }

    private boolean aimedBallHits(int hi, Element h, float hw, float lo, float hiY) {
        float[] b = aimedBall(hi, h);
        if (b == null) return false;
        float dx = Math.abs(course.dsWrap(b[0], s)), ddx = Math.max(0f, dx - hw);
        float ddy = b[1] < lo ? lo - b[1] : (b[1] > hiY ? b[1] - hiY : 0f);
        return ddx * ddx + ddy * ddy < Element.AIM_R * Element.AIM_R;
    }

    // ---------------------------------------------------------------- hazards that ride a moving platform (bug #14)

    /**
     * A hazard that sits on a platform rides it when the platform moves: these return the live offset of the hazard's anchor platform from that platform's home position at time {@code t}.
     * They are exactly zero for a hazard in a gap, beside the path, or on static ground, so every static-anchored hazard keeps behaving precisely as before. Pure functions of {@code t}
     * and the (immutable) anchor element, so clones and the generator's look-ahead stay deterministic.
     */
    private float carryS(Element h, float t) {
        int a = h.anchor;
        if (a < 0 || a >= course.size() || !h.ridesAnchor()) return 0f;
        Element o = course.get(a);
        return o.isMoving() ? course.dsWrap(o.sAt(t), o.s) : 0f;
    }
    private float carryY(Element h, float t) {
        int a = h.anchor;
        if (a < 0 || a >= course.size() || !h.ridesAnchor()) return 0f;
        Element o = course.get(a);
        return o.isMoving() ? o.yAt(t) - o.y : 0f;
    }
    /** The horizontal / vertical offset a hazard currently inherits from the moving platform it rides (0 unless it sits on a mover). The renderer adds these to the drawn hazard position so a spike pad stays on its platform. */
    public float hazCarryS(Element h) { return carryS(h, time); }
    public float hazCarryY(Element h) { return carryY(h, time); }

    /** True if the player's body overlaps any lethal hazard right now. */
    private boolean hazardHit() {
        final float hw = T.halfWidth - 0.03f, lo = y + 0.12f, hi = y + T.height - 0.1f;
        for (int k = 0, cnt = hz == null ? course.hazards.size() : hz.length; k < cnt; k++) {
            Element e = course.hazards.get(hz == null ? k : hz[k]);
            hitBy = e;
            if (e.type == Element.Type.AIMED) { if (aimedBallHits(hz == null ? k : hz[k], e, hw, lo, hi)) return true; continue; }
            if (e.type == Element.Type.CANNON) {                       // every ball on its way - this cycle's and the earlier launches flying on past the lane, exactly the set that is drawn - hits (Build 63)
                float cs = carryS(e, time), cy = e.y + carryY(e, time), r = Element.SHOT_R;
                float ddy = cy < lo ? lo - cy : (cy > hi ? cy - hi : 0f);
                if (ddy >= r) continue;
                for (int back = 0; back < Element.CANNON_TRAIL; back++) {
                    if (!e.cannonBallAlive(time, back)) continue;
                    float dx = Math.abs(course.dsWrap(e.cannonBallS(time, back) + cs, s)), ddx = Math.max(0f, dx - hw);
                    if (ddx * ddx + ddy * ddy < r * r) return true;
                }
                continue;
            }
            if (!e.lethalAt(time)) continue;
            float cs = carryS(e, time), cyOff = carryY(e, time);      // ride the platform this hazard sits on (both are 0 unless the platform is a mover)
            float dx = Math.abs(course.dsWrap(e.sAt(time) + cs, s));
            float r = e.discR();
            if (r > 0f) {
                float ddx = Math.max(0f, dx - hw), cy = e.yAt(time) + cyOff;
                float ddy = cy < lo ? lo - cy : (cy > hi ? cy - hi : 0f);
                if (ddx * ddx + ddy * ddy < r * r) return true;
            } else if (e.type == Element.Type.SPIKE_TRAP) {
                if (dx < e.w * 0.5f + hw && y > e.y + cyOff - 0.5f && y < e.y + cyOff + e.spikeHeight(time) - 0.08f) return true;
            } else if (e.type == Element.Type.SPIKE_DROP) {
                float b = e.dropBottom(time) + cyOff;
                if (dx < e.w * 0.5f + hw && hi > b && lo < b + Element.DROP_SPIKE_H) return true;          // only the spiked underside hurts; the top of the slab is a surface
            } else {   // spike block: a solid lethal box [y, y+len]
                if (dx < e.w * 0.5f + hw && hi > e.y + cyOff && lo < e.y + cyOff + e.len) return true;
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
        s += dsP + surfaceCarry(e) * dt; y = ey1[e];
        // horizontal control
        float target = in.moveX * T.runSpeed;
        boolean saw = course.get(e).type == Element.Type.SEESAW;
        if (saw) { float up = tilt[e] * Math.signum(target) / T.seesawMaxTilt; if (up > 0) target *= Math.max(0.2f, 1f - T.seesawUphill * up); }
        float a = (Math.abs(target) > 0.01f && Math.signum(target) == Math.signum(vx) || Math.abs(vx) < 0.01f) ? T.groundAccel : T.groundDecel;
        if (Math.abs(target) < 0.01f) a = T.groundDecel;
        boolean wet = course.get(e).wet();
        if (wet) a *= T.wetGrip;            // a wet platform: low friction (jump, edges and everything else are unchanged)
        vx = approach(vx, target, a * dt);
        if (wet) {                          // Build 71 (owner: "a goofy dance with no real chance of falling"): on ice your feet skid about - a lurch that comes and goes - and braking hard sets off a real slide
            boolean braking = Math.abs(vx) > 1.6f && (Math.abs(target) < 0.01f || Math.signum(target) != Math.signum(vx));
            vx += (float) StrictMath.sin(time * T.wetLurchRate) * T.wetLurch * dt;
            if (braking && slipCd <= 0f) { slipCd = 0.9f; vx *= T.wetSkid; events |= EV_SLIP; }
        }
        if (slipCd > 0f) slipCd = Math.max(0f, slipCd - dt);
        if (Math.abs(in.moveX) > 0.15f) facing = in.moveX > 0 ? 1 : -1;
        s += vx * dt;
        if (saw) s -= tilt[e] * T.seesawSlide * dt;       // the tipped plank slides you downhill whatever you do
        coyote = T.coyote + assistForgive;
        if (jumpBuf > 0) {
            float pv = platformVy(e);
            vx += platformVs(e) * 0.6f;
            float boost = Math.max(0, pv) * 0.6f;
            Element je = course.get(e);
            if (je.type == Element.Type.SWING) {          // jump at the top of the arc and the swing catapults you
                float f = je.swingFrac(time), k = f * f * f * f;
                boost += T.swingSuper * k; vx += Math.signum(f) * T.swingSuperVx * k;
                if (k > 0.5f) events |= EV_BOUNCE;
            }
            doJump(boost);
            return;
        }
        int nb = stepAcross(e);
        if (nb >= 0) { int ev0 = events; land(nb, in, surfaceAt(nb)); if ((ev0 & EV_LAND) == 0) events &= ~EV_LAND; return; }       // walked straight on: no landing
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
            vx = (h % 3 == 0 ? -1f : 1f) * (3.0f + (h % 5) * 0.6f); vy = 10f + (h % 4) * 1.2f;      // Build 72 (owner): hard enough to make you fight for it
            mode = Mode.AIR; onElem = -1; jumpedUp = false; coyote = 0; events |= EV_BOUNCE;
            return;
        }
        // checkpoint / win
        if (course.isCheckpoint(el) && e > checkpoint) { checkpoint = e; events |= EV_CHECKPOINT; }
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
        trackSideEntry();
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
        if (landOnSlope(in, py)) return;
        if (landOnSlab(in, py)) return;
        if (sideIn >= 0 && catchInside(in)) return;
        if (lockout <= 0) { if (tryGrab(in)) return; }
    }

    /** The top of a spike slab is a surface: a body falling onto it from above stands on it, and the slab carries it up and down like an elevator (see {@link #stepSlab}). */
    private boolean landOnSlab(InputState in, float py) {
        int best = -1; float bestTop = -1e9f;
        for (int k = 0, cnt = hz == null ? course.hazards.size() : hz.length; k < cnt; k++) {
            int hi = hz == null ? k : hz[k];
            Element h = course.hazards.get(hi);
            if (h.type != Element.Type.SPIKE_DROP) continue;
            float top = h.dropTop(time) + carryY(h, time), prev = h.dropTop(time - DT) + carryY(h, time - DT);   // the slab's safe top, riding its platform if that platform moves
            if (Math.abs(course.dsWrap(h.sAt(time) + carryS(h, time), s)) > h.w * 0.5f + T.edgeOverhang) continue;
            if (vy - (top - prev) / DT > 0f) continue;
            if (py >= prev - 0.08f && y <= top + 0.0001f && top > bestTop) { bestTop = top; best = hi; }
        }
        if (best < 0) return false;
        y = bestTop; landSpeed = Math.max(0f, -vy); vy = 0; mode = Mode.GROUND; onElem = -1; onSlab = best; jumpedUp = false; coyote = T.coyote + assistForgive;
        events |= EV_LAND;
        if (jumpBuf > 0) { Element hb = course.hazards.get(best); doJump(Math.max(0f, (bestTop - (hb.dropTop(time - DT) + carryY(hb, time - DT))) / DT) * 0.6f); onSlab = -1; }
        return true;
    }

    /** Standing on a slab's top: carried with it; a fast slam throws the hero gently off (no ride down, no hurt); jump and walking off work as on any platform. */
    private void stepSlab(InputState in, float dt) {
        Element h = course.hazards.get(onSlab);
        float top = h.dropTop(time) + carryY(h, time), pv = (top - (h.dropTop(time - dt) + carryY(h, time - dt))) / dt;
        if (pv < -SLAB_SLAM_SPEED) {
            mode = Mode.AIR; onSlab = -1; vy = SLAB_BOUNCE_SPEED; jumpedUp = false; coyote = 0; events |= EV_BOUNCE; y = top + 0.0f;
            return;
        }
        y = top;
        s += carryS(h, time) - carryS(h, time - dt);        // a slab on a mover carries the hero along with it, like any platform (0 on static ground)
        float target = in.moveX * T.runSpeed;
        float a = (Math.abs(target) > 0.01f && Math.signum(target) == Math.signum(vx) || Math.abs(vx) < 0.01f) ? T.groundAccel : T.groundDecel;
        if (Math.abs(target) < 0.01f) a = T.groundDecel;
        vx = approach(vx, target, a * dt);
        if (Math.abs(in.moveX) > 0.15f) facing = in.moveX > 0 ? 1 : -1;
        s += vx * dt;
        coyote = T.coyote + assistForgive;
        if (jumpBuf > 0) { doJump(Math.max(0f, pv) * 0.6f); onSlab = -1; return; }
        if (Math.abs(course.dsWrap(s, h.sAt(time) + carryS(h, time))) > h.w * 0.5f + T.edgeOverhang) { mode = Mode.AIR; onSlab = -1; vy = Math.min(0f, pv); jumpedUp = false; }
    }

    /**
     * A ramp's surface height depends on where along it the body is, and the landing test above measures it where the body WAS at the start of the step. A body running up a ramp while it
     * jumps is carried into the slope: the surface climbs past its feet faster than the jump rises, and since a rising body is never landed, at the top of the arc it is already inside the ramp and
     * falls straight through it. So when nothing else caught the body, a ramp whose surface at the body's NEW position has reached its feet (they were above that ramp before the move) is landed on there.
     * Only bodies that would have sunk into the slope are affected; every landing the test above makes is unchanged.
     */
    /**
     * Build 72 (owner: "the ramps don't line up with the block at the top, you trip-hop onto it"): at the end of the surface you are walking on, a fixed
     * neighbour whose top meets it at about the same height (at most {@link #STEP_ACROSS} higher) is simply walked onto, instead of being climbed like a ledge.
     * Moving platforms and the spinning ramp are never stepped onto this way.
     */
    private int stepAcross(int e) {
        Element cur = course.get(e);
        if (Math.abs(course.dsWrap(s, es1[e])) < cur.halfW() - 0.15f) return -1;
        int best = -1; float bestTop = -1e9f;
        for (int k = 0, cnt = act == null ? course.size() : act.length; k < cnt; k++) {
            int i = act == null ? k : act[k];
            if (i == e || gone[i]) continue;
            Element el = course.get(i);
            boolean fixed = el.type == Element.Type.STATIC || el.type == Element.Type.CRUMBLE || el.type == Element.Type.GOAL || (el.type == Element.Type.RAMP && !el.spins());
            if (!fixed || Math.abs(course.dsWrap(s, es1[i])) > el.halfW()) continue;
            float top = surfaceAt(i);
            if (top < y - 0.05f || top > y + STEP_ACROSS || top <= bestTop) continue;
            best = i; bestTop = top;
        }
        return best;
    }
    /** The top of element i where the body now stands (a ramp's slope at the body's own position). */
    private float surfaceAt(int i) {
        Element el = course.get(i);
        if (el.type != Element.Type.RAMP) return ey1[i];
        float hw = el.halfW();
        return el.y + el.rampSlope(time) * Math.max(-hw, Math.min(hw, course.dsWrap(s, es1[i])));
    }

    private boolean landOnSlope(InputState in, float py) {
        int best = -1; float bestSurf = -1e9f;
        for (int k = 0, cnt = act == null ? course.size() : act.length; k < cnt; k++) {
            int i = act == null ? k : act[k];
            Element el = course.get(i);
            if (el.type != Element.Type.RAMP || gone[i]) continue;
            float hw = el.halfW(), x = course.dsWrap(s, es1[i]);
            if (Math.abs(x) > hw + T.edgeOverhang) continue;
            float surf = el.y + el.rampSlope(time) * Math.max(-hw, Math.min(hw, x));
            if (py >= ey0[i] - 0.08f && y <= surf + 0.0001f && surf > bestSurf) { bestSurf = surf; best = i; }
        }
        if (best < 0) return false;
        vy = Math.min(vy, 0f);          // a rising body is stopped by the slope; a falling one lands as it always did
        land(best, in, bestSurf);
        return true;
    }

    /**
     * Edges. The grab rules are off while the body rises faster than 3.5 m/s (so a leap past a ledge is not snatched), and a platform's side is not solid, so a fast jump into the side of a block
     * can slip inside it. Such a body is remembered here; {@link #catchInside} then catches it the moment it slows, instead of letting it sink through the block. Nothing about the flight itself
     * changes (it may still pass through the block, or clear its top and land on it), so existing routes behave exactly as before; only a body that would otherwise fall through is caught.
     */
    private void trackSideEntry() {
        for (int k = 0, cnt = act == null ? course.size() : act.length; k < cnt; k++) {
            int i = act == null ? k : act[k];
            Element el = course.get(i);
            if (!el.isPlatform() || gone[i]) continue;
            float top = ey1[i], hw = el.halfW();
            if (y >= top + sideSlack(el) || y + T.height <= top - el.slab() + 0.05f) continue;       // feet above the top (it lands) or head below the underside (it passes under)
            float dPrev = course.dsWrap(es0[i], ps0), dNow = course.dsWrap(es1[i], s);
            if (Math.abs(dPrev) >= hw && Math.abs(dNow) < hw) { sideIn = i; sideDir = dPrev > 0 ? 1 : -1; }      // + : the platform is ahead (+s)
        }
    }

    /** A flat top is either above the feet (the body lands) or below them; the surface of a ramp or seesaw rises or falls with the body's own position, so a body just above it at the face can end up below it. */
    private static float sideSlack(Element el) { return el.type == Element.Type.RAMP || el.type == Element.Type.SEESAW ? 0.5f : -0.05f; }

    private boolean catchInside(InputState in) {
        int i = sideIn; Element el = course.get(i);
        float top = ey1[i], hw = el.halfW();
        if (gone[i] || Math.abs(course.dsWrap(es1[i], s)) > hw + 0.28f || y >= top + sideSlack(el) || y + T.height <= top - el.slab()) { sideIn = -1; return false; }   // it left the block, or its feet cleared the top
        if (y >= top - 0.05f) return false;                                                 // feet at the surface (a slope can still rise past them): nothing to catch yet
        if (vy > 3.5f) return false;                                                        // still shooting up: wait for it to slow
        if (y + T.handHeight < top - T.ledgeReachBelow) { sideIn = -1; return false; }      // hands still too far below the top to reach it
        sideIn = -1;
        if (el.type == Element.Type.PAD || el.type == Element.Type.SPRING) { y = top; land(i, in); return true; }
        ledgeSide = sideDir; onElem = i; mode = Mode.PULLUP; pullT = 0; pullFromS = s; pullFromY = y; vx = vy = 0; facing = sideDir; events |= EV_GRAB | EV_PULL;
        return true;
    }

    private void land(int i, InputState in) { land(i, in, ey1[i]); }

    private void land(int i, InputState in, float surface) {
        Element el = course.get(i);
        y = surface;
        landSpeed = -vy;
        if (el.type == Element.Type.PAD || el.type == Element.Type.SPRING) {
            float v = in.jumpHeld ? T.padBounceHeld : T.padBounce;
            if (el.type == Element.Type.SPRING) { vy = v * (float) StrictMath.cos(el.amp); vx = v * (float) StrictMath.sin(el.amp); if (Math.abs(vx) > 0.5f) facing = vx > 0 ? 1 : -1; }
            else vy = v;
            jumpedUp = false; mode = Mode.AIR; onElem = -1; lastPad = i; padSquash[i] = 0.25f;
            events |= EV_BOUNCE;
            progress(i);
            return;
        }
        vy = 0; mode = Mode.GROUND; onElem = i; jumpedUp = false; coyote = T.coyote + assistForgive;
        events |= EV_LAND;
        if (course.isCheckpoint(el) && i > checkpoint) { checkpoint = i; events |= EV_CHECKPOINT; }
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
            if (!el.isPlatform() || gone[i]) continue;
            float top = ey1[i];
            if (hand < top - T.ledgeReachBelow) continue;
            boolean mantle = hand > top + T.ledgeReachAbove;      // the hands are already above the top: the feet are only a little below it (walked or dropped off a nearby platform)
            if (mantle ? (y >= top - 0.05f || y < top - T.handHeight) : y >= top - 0.1f) continue;           // feet at or above the top: would have landed
            float d = course.dsWrap(es1[i], s);      // + => platform ahead (to +s)
            float edgeDist = Math.abs(d) - el.halfW();   // distance from player center to the near vertical face
            if (edgeDist > T.ledgeReachX || edgeDist < (mantle ? -0.45f : -0.12f)) continue;
            int side = d > 0 ? 1 : -1;
            boolean toward = (side > 0 ? (vx > 0.3f || in.moveX > 0.3f) : (vx < -0.3f || in.moveX < -0.3f));
            if (!toward) continue;
            if (el.type == Element.Type.PAD || el.type == Element.Type.SPRING) { y = top; land(i, in); return true; }       // walked or dropped into the side of a bounce pad: it bounces you, just as if you had landed on it
            if (mantle) {          // a step-up: scramble straight onto the platform from where the feet are, no hang
                ledgeSide = side; onElem = i; mode = Mode.PULLUP; pullT = 0; pullFromS = s; pullFromY = y; vx = vy = 0; facing = side; events |= EV_GRAB | EV_PULL;
                return true;
            }
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
        // pushing on at the very top: haul up from behind the beam and stand on it (a cliff-style mantle); a firm, held up-push, so a light touch while climbing never yanks you off the rope
        if (y >= yMax - 0.01f && in.moveY > 0.3f) ropeTopT += dt; else ropeTopT = 0f;
        if (ropeTopT >= ROPE_TOP_HOLD) { ropeTopT = 0f; mode = Mode.BEAM; pullT = 0; pullFromY = y; vx = vy = 0; events |= EV_PULL; }
    }

    /** True while the climber is still hauling himself over the rope's top beam (as opposed to standing on it). */
    public boolean mounting() { return mode == Mode.BEAM && pullT < BEAM_MOUNT_TIME; }

    /** The beam above a rope is a small standing place: climb up onto it, walk along it, jump from it, or step back down onto the rope. */
    private void stepBeam(InputState in, float dt) {
        int i = onElem; Element el = course.get(i);
        float top = el.y + BEAM_TOP;
        s = course.wrap(es1[i] + course.dsWrap(s, es1[i]));
        if (pullT < BEAM_MOUNT_TIME) {
            pullT = Math.min(BEAM_MOUNT_TIME, pullT + dt);
            float k = pullT / BEAM_MOUNT_TIME, up = Math.min(1f, k * 1.25f);
            y = pullFromY + (top - pullFromY) * up; vx = 0;
            if (pullT >= BEAM_MOUNT_TIME) { y = top; coyote = T.coyote; jumpBuf = 0; }
            return;
        }
        y = top; vy = 0; coyote = T.coyote;
        float target = in.moveX * T.runSpeed * 0.55f;
        vx = target;
        s += vx * dt;
        if (Math.abs(in.moveX) > 0.15f) facing = in.moveX > 0 ? 1 : -1;
        if (jumpBuf > 0) { vx = Math.abs(in.moveX) > 0.2f ? in.moveX * T.runSpeed : 0f; doJump(0f); lockout = T.grabLockout; return; }
        float d = course.dsWrap(s, es1[i]);
        if (in.moveY < -0.7f) { mode = Mode.ROPE; s = es1[i]; y = el.y - T.handHeight; vx = 0; ropeTopT = 0f; lockout = 0f; return; }
        if (Math.abs(d) > BEAM_HALF) { mode = Mode.AIR; onElem = -1; vy = 0; lockout = T.grabLockout; jumpedUp = false; }
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
        float fromS = pullFromS;       // where the pull began (the hang spot for a ledge grab, or the player's own position for a step-up)
        float want = fromS + course.dsWrap(es1[i] - ledgeSide * (el.halfW() - 0.35f), fromS);   // short way round the ring
        float toS = ledgeSide * (want - fromS) < 0f ? fromS : want;      // a body that slipped in from the side is already further inside than that spot: it is lifted straight up
        // easing: up first, then forward
        float up = Math.min(1f, k * 1.6f), fw = Math.max(0f, (k - 0.45f) / 0.55f);
        y = pullFromY + (ey1[i] - pullFromY) * up;
        s = course.wrap(fromS + (toS - fromS) * fw);
        if (k >= 1f) {
            s = course.wrap(toS); y = ey1[i]; vx = vy = 0; mode = Mode.GROUND; onElem = i; coyote = T.coyote;
            if (course.isCheckpoint(el) && i > checkpoint) { checkpoint = i; events |= EV_CHECKPOINT; }
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
