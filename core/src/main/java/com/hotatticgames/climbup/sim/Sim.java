package com.hotatticgames.climbup.sim;

/**
 * Deterministic fixed-step platformer simulation on a wrapped (cylindrical) arc coordinate.
 * No rendering or engine dependencies; cloneable so tests and the course validator can look ahead.
 */
public final class Sim {
    public enum Mode { GROUND, AIR, ROPE, CABLE, LEDGE, PULLUP, BEAM, TARZAN }

    public static final float DT = 1f / 60f, SWING_TIME = 0.34f;
    // event bits
    public static final int EV_JUMP = 1, EV_LAND = 2, EV_BOUNCE = 4, EV_GRAB = 8, EV_PULL = 16, EV_CRUMBLE = 32,
            EV_CHECKPOINT = 64, EV_RESPAWN = 128, EV_WIN = 256, EV_ROPE = 512, EV_CABLE = 1024, EV_FALL_NEAR = 2048, EV_HIT = 4096, EV_KEY = 8192, EV_DOOR = 16384, EV_BLOCKED = 32768, EV_CLUB = 65536, EV_SWING = 131072, EV_SHOVE = 262144, EV_CRAB_OFF = 524288, EV_FINISH = 1048576, EV_AIM = 2097152,
            EV_DASH = 4194304, EV_BLOB_JUMP = 8388608, EV_BLOB_LAND = 16777216, EV_SQUASH = 33554432, EV_LAUNCH = 67108864, EV_SLIP = 134217728,
            EV_ENF_ALERT = 268435456, EV_ENF_JUMP = 536870912, EV_ENF_HIT = 1073741824, EV_AVL_WARN = Integer.MIN_VALUE;          // (EV_AVL_WARN: an avalanche breaks loose - rules 18: no warning before it - the sign bit, the last free one)   // (2026-10-27 PUNISHER Enforcer: it starts its chase; it jumps (a swat or its leap away))   // Build 71: a skull starts a dash, a blob leaps, a blob lands, you were squashed, a skull or a tipping bridge launched you

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
    /** Events that no longer fit in {@link #events} (all 32 bits are taken): accumulated since the last {@link #consumeEvents2}. */
    public int events2;
    public static final int EV2_REBOUND = 1;
    /**
     * Rules 19 timed rebound jump (owner directive 2026-10-29): a fresh Jump press in the air arms it for REBOUND_WINDOW seconds; if the climber then
     * touches a reboundable creature or projectile, that contact's normal outcome (shove or knock-back) is kept and a jump impulse is added to it,
     * mostly upward for a contact from above. Without the press nothing changes. Not saved (it lasts a tenth of a second).
     */
    public float reboundBuf;
    /** Rules 19 Tarzan swing: how far down the swinging rope the climber's hands hold it (m from the pivot). */
    public float tarzanR;
    /** Upward speed a Jump adds on letting go of a Tarzan swing, as a share of a jump (the rope's own motion is kept). Provisional. */
    static final float TARZAN_RELEASE_UP = 0.55f;
    public int rebounds;
    static final float REBOUND_WINDOW = 0.10f, REBOUND_GAIN = 1.0f;
    public float ps0, py0;           // player position at the start of the last step (render interpolation)
    public boolean deferRespawn, respawnPending;    // a managed world window: the respawn is completed by Tower.maintain
    public float floorOverride = Float.NaN;          // managed window: the height of the lowest platform of the WHOLE tower (the resident part is not all of it)
    public boolean teleported;       // set when the last step moved the player discontinuously (respawn)
    /** Planner trials only (see MotionCache): the planning call's position cache, its generation, and the trial's step count. Null everywhere else. */
    MotionCache motion; int motionGen, mstep;
    public float assistForgive;      // extra coyote/buffer seconds from assists

    // element dynamic state
    public float[] es0, ey0, es1, ey1;            // previous/current positions
    /** Build 79: how far each element's surface rose in the last step, taken from its own motion (Element#yOffsetAt, a seesaw's tilt, a ramp's slope) rather than
     *  from ey1 - ey0, whose rounding depends on how high the element sits in the current frame; {@link #platformVy} is this over one step. */
    private float[] edy = new float[0];
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
    /**
     * Spike-block avalanches (owner 2026-10-27): AVL_W floats per hazard. [0] state (0 not reached, 1 warning/falling, 2 spent: fired and over, or
     * reached during the cooldown), [1] clock since it fired; then per block (AVL_B floats): arc and height relative to the trigger deck, velocity,
     * angle (deg), spin (deg/s), status (0 none/gone, 1 falling, 2 waiting to be released, its delay in the rest slot), rest clock, came near the body (0/1). Relative to the
     * deck, so a tower rebase needs no shift. lastAvlT: simulation time the last avalanche fired (the cooldown is measured from it).
     */
    public float[] avl = new float[0];
    public float lastAvlT = -1e9f;
    /** Hard block impacts so far (for sound only; never read by the simulation). */
    public int avlImpacts;
    /** Block-on-block knocks so far (reports and tests only). */
    public int avlKnocks;
    /** Encounter outcomes (difficulty and variety reports only; never read by the simulation, not saved): Enforcer kicks and swats tried and landed,
     *  retreats and falls; avalanches fired and skipped (cooldown), blocks released, blocks that came within 2.5 m of the body, blocks that reached the
     *  route decks from the one before the trigger deck to the fourth after it (the traversal area), avalanche hits. */
    public int enfKickTries, enfKickHits, enfSwatTries, enfSwatHits, enfRetreats, enfFalls, avlFired, avlSkipped, avlReleased, avlNear, avlArea, avlHits;
    /** Enforcer judgment telemetry (report-only, rules 14): robots that started a chase; swats sized up, turned down (no landing it accepts), taken although
     *  risky (category B); landings back on its own deck and on a neighbouring one; hops; falls by cause - came down just past an end (within 0.6 m),
     *  carried off by its own chase steering, nothing under it at all. */
    public int enfAlerts, enfSwatConsidered, enfSwatRejected, enfSwatRisky, enfLandOwn, enfLandNear, enfHops, enfFallEdge, enfFallChase, enfFallNoDeck;
    public static final int AVL_B = 9, AVL_W = 2 + Element.AVL_MAX * AVL_B;
    public float[] mobT = new float[0], mobS = new float[0], mobY = new float[0], mobDir = new float[0], mobTgt = new float[0], mobSeen = new float[0];
    /** Build 79 (owner: creatures reach the platforms before and after their own): the deck a skull, blob or crab is on, as an offset from its home deck (its anchor). */
    public float[] deckOff = new float[0];
    /** Build 79 crabs follow you across gaps: seconds into a hop (-1: not hopping), the arc it hopped from and that deck's offset; seconds falling with a crumbled deck (-1: not falling). */
    public float[] crabJump = new float[0], crabFrom = new float[0], crabFromDeck = new float[0], crabFall = new float[0];
    /** Build 79 (owner: the aimed cannon turns flat like a record): the turret's heading, 0 facing +s, pi facing -s; aimAng is then its in-lane firing angle. */
    public float[] aimYaw = new float[0];
    public float squashT;
    public float slipCd;                                                   // seconds before ice can set off another skid (EV_SLIP)                                                  // seconds left of the player being squashed flat (drawing only)
    public int lastKeyColor, lastGateColor, lastGateNo;
    public boolean finishedRun;         // the finishCastle's door has been walked through (the timed run is over)
    public float clubTime, swingT, shoveCd;       // spiked club carried (seconds left), swing animation clock, grace between shoves
    /** What the club knocked away last (effects): 0 a crab, 1 a bee, 2 a skull (Build 79: batted off like a hooked golf shot; that breaks the club). */
    public int knocked;
    public float crabS, crabY;                    // where the last crab was knocked off (effects)
    public Element hitBy;
    public float hitS, hitY;                      // where the last hit happened (effects)
    /** Arc position of the part of the hazard that touched the body in the last {@link #hazardHit} that returned true (the ball, the blade where it rides now, the falling block). */
    private float hitFromS = Float.NaN;
    /** Where the touching part of the last hazard hit was (arc and height), kept for the timed rebound after knockOff clears hitFromS. */
    private float hitFromS2 = Float.NaN, hitFromY = Float.NaN;
    /** Seconds a knock-off keeps the hands from grabbing again (owner correction 2026-10-27, DC4: knocked off a rope, the climber re-grabbed it the next frame and was hit again every 0.72 s). */
    static final float KNOCK_LOCKOUT = 0.3f;
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
        if (!course.routeFinished()) {          // Build 79: a streaming tower's world keeps each slice's decoys right after its route, so the window counts route elements, not indices: the next slice's route is past the decoys between (its dead ends grew with spurs both ways), and the decoys anchored in the window come too (a left-hand spur can hang over the route a few steps back)
            int n0 = course.size(), want = Math.max(0, hi - lo + 1), got = 0, lastRoute = lo, cnt = 0;
            boolean[] in = new boolean[n0];
            if (lo < n0 && course.get(lo).anchor >= 0) { int b = lo - 1; while (b >= 0 && course.get(b).anchor >= 0) b--; if (b >= 0) in[b] = true; }
            for (int i = lo; i < n0 && got < want; i++) { in[i] = true; if (course.get(i).anchor < 0) { got++; lastRoute = i; } }
            for (int i = 0; i < n0; i++) { int an = course.get(i).anchor; if (an >= lo - 4 && an <= lastRoute + 1) in[i] = true; }
            for (boolean b : in) if (b) cnt++;
            act = new int[cnt]; for (int i = 0, j = 0; i < n0; i++) if (in[i]) act[j++] = i;
            hz = course.hazardsFor(lo - 3, lastRoute + 3);
            return;
        }
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
            for (int i = lo; i <= hi; i++) if (i < oldLo || i > oldHi) { Element e = course.get(i); es0[i] = es1[i] = e.sAt(time); ey0[i] = ey1[i] = e.yAt(time); edy[i] = 0f; }
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
        deckOff = java.util.Arrays.copyOf(deckOff, n); crabJump = java.util.Arrays.copyOf(crabJump, n); crabFrom = java.util.Arrays.copyOf(crabFrom, n); crabFromDeck = java.util.Arrays.copyOf(crabFromDeck, n);
        crabFall = java.util.Arrays.copyOf(crabFall, n); aimYaw = java.util.Arrays.copyOf(aimYaw, n);
        avl = java.util.Arrays.copyOf(avl, n * AVL_W);
        java.util.Arrays.fill(crabJump, o, n, -1f); java.util.Arrays.fill(crabFall, o, n, -1f);
    }

    /** Width of {@link #hazardState}: every live per-hazard value the simulation carries (aimed cannons, crabs, Build 71 creatures). */
    public static final int HAZ_STATE = 23;
    /** Build 71 (save/load determinism): the dynamic state of one hazard, packed, so a saved climb restores its cannons, crabs, bees, skulls and blobs mid-move. */
    public float[] hazardState(int i) {
        ensureCapacity();
        return new float[]{aimAng[i], aimFireT[i], aimArmed[i] ? 1f : 0f, crabPos[i], crabVel[i], crabMode[i], crabStuck[i], crabCool[i], crabTurn[i], crabSeen[i],
                mobState[i], mobT[i], mobS[i], mobY[i], mobDir[i], mobTgt[i], mobSeen[i], deckOff[i], crabJump[i], crabFrom[i], crabFromDeck[i], crabFall[i], aimYaw[i]};
    }
    /** Writes back what {@link #hazardState} packed (ignored unless it has the right width and is all finite). */
    public void setHazardState(int i, float[] v) {
        if (v == null || v.length != HAZ_STATE) return;
        for (float f : v) if (Float.isNaN(f) || Float.isInfinite(f)) return;
        ensureCapacity();
        if (i < 0 || i >= aimAng.length) return;
        aimAng[i] = v[0]; aimFireT[i] = v[1]; aimArmed[i] = v[2] != 0f; crabPos[i] = v[3]; crabVel[i] = v[4]; crabMode[i] = (byte) v[5]; crabStuck[i] = v[6]; crabCool[i] = v[7]; crabTurn[i] = v[8]; crabSeen[i] = v[9];
        mobState[i] = (byte) v[10]; mobT[i] = v[11]; mobS[i] = v[12]; mobY[i] = v[13]; mobDir[i] = v[14]; mobTgt[i] = v[15]; mobSeen[i] = v[16];
        deckOff[i] = v[17]; crabJump[i] = v[18]; crabFrom[i] = v[19]; crabFromDeck[i] = v[20]; crabFall[i] = v[21]; aimYaw[i] = v[22];
    }

    /** The course grew (endless mode): extend the per-element state arrays. */
    public void ensureCapacity() {
        if (featDone.length < course.hazards.size()) featDone = java.util.Arrays.copyOf(featDone, Math.max(course.hazards.size(), featDone.length * 3 / 2 + 8));
        growAim(featDone.length);
        int n = course.size();
        if (n <= es0.length) return;
        int m = Math.max(n, es0.length * 3 / 2 + 16), o = es0.length;
        es0 = java.util.Arrays.copyOf(es0, m); ey0 = java.util.Arrays.copyOf(ey0, m); es1 = java.util.Arrays.copyOf(es1, m); ey1 = java.util.Arrays.copyOf(ey1, m); edy = java.util.Arrays.copyOf(edy, m);
        crumbleT = java.util.Arrays.copyOf(crumbleT, m); gone = java.util.Arrays.copyOf(gone, m); goneT = java.util.Arrays.copyOf(goneT, m); padSquash = java.util.Arrays.copyOf(padSquash, m); tilt = java.util.Arrays.copyOf(tilt, m); onT = java.util.Arrays.copyOf(onT, m);
        java.util.Arrays.fill(crumbleT, o, m, -1f);
        for (int i = o; i < n; i++) { Element e = course.get(i); es0[i] = es1[i] = e.sAt(time); ey0[i] = ey1[i] = e.yAt(time); }
    }

    public Sim(Course c, Tuning t) {
        this.course = c; this.T = t;
        int n = c.size();
        es0 = new float[n]; ey0 = new float[n]; es1 = new float[n]; ey1 = new float[n]; edy = new float[n];
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
        deferRespawn = o.deferRespawn; respawnPending = o.respawnPending; floorOverride = o.floorOverride;          // (DC3/DC9: a copy of a managed sim behaves as the sim does)
        checkpoint = o.checkpoint; bestElem = o.bestElem; maxHeight = o.maxHeight; won = o.won; falls = o.falls;
        landSpeed = o.landSpeed; events = o.events; assistForgive = o.assistForgive; ps0 = o.ps0; py0 = o.py0; teleported = o.teleported;
        es0 = o.es0.clone(); ey0 = o.ey0.clone(); es1 = o.es1.clone(); ey1 = o.ey1.clone(); edy = o.edy.clone();
        crumbleT = o.crumbleT.clone(); gone = o.gone.clone(); goneT = o.goneT.clone(); padSquash = o.padSquash.clone(); tilt = o.tilt.clone(); onT = o.onT.clone();
        reboundBuf = o.reboundBuf; rebounds = o.rebounds; tarzanR = o.tarzanR;
        clubTime = o.clubTime; swingT = o.swingT; shoveCd = o.shoveCd; crabS = o.crabS; crabY = o.crabY; keys = o.keys; keysFree = o.keysFree; featDone = o.featDone.clone(); aimAng = o.aimAng.clone(); aimFireT = o.aimFireT.clone(); aimArmed = o.aimArmed.clone(); crabPos = o.crabPos.clone(); crabVel = o.crabVel.clone(); crabMode = o.crabMode.clone(); crabStuck = o.crabStuck.clone(); crabCool = o.crabCool.clone(); crabTurn = o.crabTurn.clone(); crabSeen = o.crabSeen.clone(); mobState = o.mobState.clone(); mobT = o.mobT.clone(); mobS = o.mobS.clone(); mobY = o.mobY.clone(); mobDir = o.mobDir.clone(); mobTgt = o.mobTgt.clone(); mobSeen = o.mobSeen.clone(); deckOff = o.deckOff.clone(); crabJump = o.crabJump.clone(); crabFrom = o.crabFrom.clone(); crabFromDeck = o.crabFromDeck.clone(); crabFall = o.crabFall.clone(); aimYaw = o.aimYaw.clone(); avl = o.avl.clone(); lastAvlT = o.lastAvlT; avlImpacts = o.avlImpacts; avlKnocks = o.avlKnocks; enfKickTries = o.enfKickTries; enfKickHits = o.enfKickHits; enfSwatTries = o.enfSwatTries; enfSwatHits = o.enfSwatHits; enfRetreats = o.enfRetreats; enfFalls = o.enfFalls; enfAlerts = o.enfAlerts; enfSwatConsidered = o.enfSwatConsidered; enfSwatRejected = o.enfSwatRejected; enfSwatRisky = o.enfSwatRisky; enfLandOwn = o.enfLandOwn; enfLandNear = o.enfLandNear; enfHops = o.enfHops; enfFallEdge = o.enfFallEdge; enfFallChase = o.enfFallChase; enfFallNoDeck = o.enfFallNoDeck; avlFired = o.avlFired; avlSkipped = o.avlSkipped; avlReleased = o.avlReleased; avlNear = o.avlNear; avlArea = o.avlArea; avlHits = o.avlHits; avlRicochets = o.avlRicochets; avlPeak = o.avlPeak; avlTrigT = o.avlTrigT; avlTrigV = o.avlTrigV; avlTrigDist = o.avlTrigDist; avlFirstImpactT = o.avlFirstImpactT; avlFirstNearT = o.avlFirstNearT; avlRebounds = o.avlRebounds; avlTumbles = o.avlTumbles; squashT = o.squashT; slipCd = o.slipCd; finishedRun = o.finishedRun; lastGateNo = o.lastGateNo; lastKeyColor = o.lastKeyColor; lastGateColor = o.lastGateColor;
        act = o.act; hz = o.hz; winLo = o.winLo; winHi = o.winHi; invuln = o.invuln; hits = o.hits; hitS = o.hitS; hitY = o.hitY;
        humanHold = o.humanHold; heldX = o.heldX; heldY = o.heldY; heldXT = o.heldXT; heldYT = o.heldYT;
        motion = o.motion; motionGen = o.motionGen; mstep = o.mstep;
    }

    public Sim copy() { return new Sim(this); }

    /** Every skull resting, ready, at the other end of its deck from where it starts: where it waits after it has dashed at someone crossing its deck
     *  (Build 77). Proofs that only saw the starting end missed this (seed 8: a key detour beside a skull's deck). */
    public void parkSkullsAtFarEnd() {
        for (int k = 0; k < course.hazards.size() && k < mobState.length; k++) {
            Element e = course.hazards.get(k);
            if (e.type != Element.Type.SKULL) continue;
            Element deck = deckOf(e);
            float dS = deck != null ? es1[e.anchor] : e.s, half = deck != null ? deck.halfW() : Math.max(2f, e.w * 0.5f);
            mobState[k] = 1; deckOff[k] = 0f; mobS[k] = course.wrap(dS - Math.max(0.5f, half - Element.SKULL_END)); mobDir[k] = 1f; mobT[k] = (Element.SKULL_REST / T.react()); mobY[k] = Element.SKULL_HOVER; mobSeen[k] = 0f;
        }
    }
    public boolean hasSkull() { for (Element e : course.hazards) if (e.type == Element.Type.SKULL) return true; return false; }

    /** Course may grow while generating; keeps arrays in sync by constructing a fresh Sim instead. */
    private void refreshElements() {
        for (int i = 0; i < course.size(); i++) {
            Element e = course.get(i);
            es0[i] = es1[i] = e.sAt(time);
            ey0[i] = ey1[i] = e.yAt(time); edy[i] = 0f;
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
    public int consumeEvents2() { int e = events2; events2 = 0; return e; }
    /** The side entry in progress (see trackSideEntry): {platform, side}, for saving a climb mid-catch (DC3). */
    public int[] sideEntry() { return new int[]{sideIn, sideDir}; }
    public void setSideEntry(int platform, int side) { sideIn = platform; sideDir = side >= 0 ? 1 : -1; }

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
        // away from what touched you (DC4: the hazard's home was used, so a body between a carried saw's home and the saw itself was thrown through it)
        float side = hitBy == null ? 0f : course.dsWrap(s, Float.isNaN(hitFromS) ? hitBy.sAt(time) : hitFromS);
        hitFromS2 = hitFromS; hitFromS = Float.NaN;
        mode = Mode.AIR; onElem = -1; coyote = 0; jumpBuf = 0; lockout = Math.max(lockout, KNOCK_LOCKOUT);
        vx = (side >= 0f ? 1f : -1f) * T.hitKnockSide; vy = T.hitKnockUp; invuln = 0.7f;      // tuning.json: hitKnockSide / hitKnockUp
        if (hitBy != null && reboundable(hitBy.type)) tryRebound(hitFromS2, hitFromY);          // (a timed press turns the knock into a rebound; it is still a hit)
    }
    /** Hazards a timed press rebounds off (creatures go through their own shoves): cannon balls, aimed shots, mortar rounds, saws. */
    static boolean reboundable(Element.Type t) { return t == Element.Type.CANNON || t == Element.Type.AIMED || t == Element.Type.MORTAR || t == Element.Type.SAW_H || t == Element.Type.SAW_V; }
    /**
     * The timed rebound (see {@link #reboundBuf}): with a fresh press armed, adds a jump impulse to the outcome this contact already gave - all of it
     * upward for a contact from straight above (the sideways part of the shove is damped), half of it for a contact from the side.
     */
    private void tryRebound(float objS, float objY) {
        if (reboundBuf <= 0f || Float.isNaN(objS)) return;
        float dx = course.dsWrap(s, objS), dy = y + T.height * 0.5f - objY, d = (float) Math.sqrt(dx * dx + dy * dy);
        float ny = d > 1e-4f ? Math.max(0f, dy / d) : 1f;
        vy += T.jumpVel * REBOUND_GAIN * (0.5f + 0.5f * ny);
        vx *= 1f - 0.6f * ny;
        reboundBuf = 0f; jumpBuf = 0f; rebounds++; events2 |= EV2_REBOUND;
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
        // restore crumbled platforms so a retry is never stale or soft-locked - except a bridge's fallen planks, which stay gone for the climb (rules 19,
        // owner decision 2026-10-29; the generator proves every holed bridge with its holes open, so a retry is never soft-locked by them)
        for (int i = 0; i < crumbleT.length; i++) crumbleT[i] = i < course.size() && course.get(i).holed() && crumbleT[i] >= Element.HOLE_DELAY ? Element.HOLE_DONE : -1f;
        java.util.Arrays.fill(gone, false);
        resetEncounters(idx);
        spawnAtCheckpoint(idx);
    }

    /**
     * The checkpoint contract (owner correction directive 2026-10-27, DC3), one rule for every retry: a retry from checkpoint {@code cp} meets the
     * challenge as it was generated and proven. Every encounter still in progress goes back to its starting state - creatures mid-chase (crabs, bees,
     * skulls, blobs, Enforcers), aimed cannons, seesaw tilt, ramp and belt timers, the shaking ramp's kick count, and avalanches above the checkpoint
     * (armed again, their blocks gone). What the run has achieved stays: keys, opened gates, checkpoints, the run time, and encounters already resolved
     * (a creature knocked off or gone, an avalanche spent at or below the checkpoint). Crumbles and holes are restored by the caller as before.
     */
    void resetEncounters(int cp) {
        java.util.Arrays.fill(tilt, 0f); java.util.Arrays.fill(onT, 0f); java.util.Arrays.fill(padSquash, 0f);
        ensureCapacity();
        boolean avlReset = false;
        for (int i = 0; i < course.hazards.size(); i++) {
            Element h = course.hazards.get(i);
            boolean done = i < featDone.length && featDone[i];
            switch (h.type) {
                case AVALANCHE:
                    if (h.anchor > cp) { if (i < featDone.length) featDone[i] = false; if ((i + 1) * AVL_W <= avl.length) java.util.Arrays.fill(avl, i * AVL_W, (i + 1) * AVL_W, 0f); avlReset = true; }
                    break;
                case CRAB: case BEE: case SKULL: case BLOB: case ENFORCER: case AIMED:
                    if (done || h.anchor <= cp) break;          // (repair 2026-10-28, same rule as the avalanches: only encounters ahead of the checkpoint start again; one on the checkpoint's own deck goes on, so it can still be outlasted - reset on every respawn, an Enforcer there kicked the climber off again and never left)
                    aimAng[i] = 0f; aimFireT[i] = -1e9f; aimArmed[i] = false; aimYaw[i] = 0f;
                    crabPos[i] = 0f; crabVel[i] = 0f; crabMode[i] = 0; crabStuck[i] = 0f; crabCool[i] = 0f; crabTurn[i] = 0f; crabSeen[i] = 0f;
                    mobState[i] = 0; mobT[i] = 0f; mobS[i] = 0f; mobY[i] = 0f; mobDir[i] = 0f; mobTgt[i] = 0f; mobSeen[i] = 0f;
                    deckOff[i] = 0f; crabJump[i] = -1f; crabFrom[i] = 0f; crabFromDeck[i] = 0f; crabFall[i] = -1f;
                    break;
                default: break;
            }
        }
        if (avlReset) lastAvlT = -1e9f;
        shoveCd = 0f; swingT = 0f; squashT = 0f; slipCd = 0f; reboundBuf = 0f;
    }

    /**
     * The world course was rebuilt around a different set of slices and a new origin (see Tower): carry every index and every height over so the simulation continues exactly where it was.
     * Indices that are no longer resident become -1.
     */
    public void rebase(Tower.Remap m) {
        int n = m.newN, oldN = es0.length;
        float[] nes0 = new float[n], ney0 = new float[n], nes1 = new float[n], ney1 = new float[n], nedy = new float[n], nc = new float[n], ngt = new float[n], npq = new float[n], ntl = new float[n], not = new float[n];
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
            nes0[j] = e.sAt(time - DT); nes1[j] = e.sAt(time); ney0[j] = ey0[i] - m.dy; ney1[j] = e.yAt(time); nedy[j] = i < edy.length ? edy[i] : 0f;
            nc[j] = crumbleT[i]; ngone[j] = gone[i]; ngt[j] = goneT[i]; npq[j] = padSquash[i]; ntl[j] = tilt[i]; not[j] = onT[i];
        }
        sideIn = sideIn >= 0 && sideIn < m.elem.length ? m.elem[sideIn] : -1;
        es0 = nes0; ey0 = ney0; es1 = nes1; ey1 = ney1; edy = nedy; crumbleT = nc; gone = ngone; goneT = ngt; padSquash = npq; tilt = ntl; onT = not;
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
        float[] ndo = new float[nf.length], ncj = new float[nf.length], ncf = new float[nf.length], ncd = new float[nf.length], ncl = new float[nf.length], nay = new float[nf.length];
        java.util.Arrays.fill(ncj, -1f); java.util.Arrays.fill(ncl, -1f);
        for (int i = 0; i < m.haz.length && i < deckOff.length; i++) { int j = m.haz[i]; if (j >= 0 && j < ndo.length) { ndo[j] = deckOff[i]; ncj[j] = crabJump[i]; ncf[j] = crabFrom[i]; ncd[j] = crabFromDeck[i]; ncl[j] = crabFall[i]; nay[j] = aimYaw[i]; } }
        deckOff = ndo; crabJump = ncj; crabFrom = ncf; crabFromDeck = ncd; crabFall = ncl; aimYaw = nay;      // deck offsets are relative to the (remapped) anchor and heights come from the decks, so nothing shifts
        float[] nav = new float[nf.length * AVL_W];
        for (int i = 0; i < m.haz.length && (i + 1) * AVL_W <= avl.length; i++) { int j = m.haz[i]; if (j >= 0 && j < nf.length) System.arraycopy(avl, i * AVL_W, nav, j * AVL_W, AVL_W); }
        avl = nav;          // blocks are held relative to their trigger deck: nothing shifts
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

    public float platformVy(int i) { return edy[i] / DT; }
    public float platformVs(int i) { return platformDs(i) / DT + surfaceCarry(i); }
    /** How far element i moved along the arc in the last step: from its own motion (Element#sOffsetAt), the same whatever origin the frame has (Build 76). */
    private float platformDs(int i) { Element e = course.get(i); return e.isMoving() ? e.sOffsetAt(time) - e.sOffsetAt(time - DT) : course.dsWrap(es1[i], es0[i]); }
    /** The spinning ramp's sideways push now (0 for anything else). */
    /** How fast the surface itself carries a body along +s: a spinning ramp's sideways push, or a conveyor belt. */
    public float surfaceCarry(int i) {
        Element e = course.get(i);
        if (e.tiltBelt()) return (onT[i] != 0f ? onT[i] : 1f) * e.phase;        // Build 77: the balance conveyor runs downhill; Build 79 (owner): always at full speed, toward the end you last stood on (onT holds that side)
        return e.spins() ? SPIN_CARRY * (float) StrictMath.sin(e.spinAngle(time)) : e.beltV();
    }

    public boolean attachedTo(int idx) {
        if (idx < 0) return false;
        return ((mode == Mode.GROUND || mode == Mode.LEDGE || mode == Mode.ROPE || mode == Mode.CABLE || mode == Mode.BEAM || mode == Mode.TARZAN) && onElem == idx);
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
    /** The stick as {@link #startOn} leaves it (just let go, so a new direction waits out {@link #humanHold}): lets a solver repeat a move exactly as the
     *  generator proved it. It can only delay inputs, never add any (Build 77). */
    public void restartHold() { heldXT = 0; heldYT = 0; }

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
        final int mk = mstep++;
        if (course.size() > es0.length || course.hazards.size() > featDone.length) ensureCapacity();
        int n = course.size();
        int cnt0 = act == null ? n : act.length;
        for (int k0 = 0; k0 < cnt0; k0++) {
            int i = act == null ? k0 : act[k0];
            es0[i] = es1[i]; ey0[i] = ey1[i]; edy[i] = 0f;
            Element e = course.get(i);
            if (e.isMoving()) {
                int o = motion == null ? -1 : motion.elAt(motionGen, i, e, mk, time);
                float z;
                if (o >= 0) { float[] v = motion.elVals(i); es1[i] = v[o]; ey1[i] = v[o + 1]; edy[i] = v[o + 2]; z = v[o + 3]; }
                else {
                    es1[i] = e.sAt(time); ey1[i] = e.yAt(time); edy[i] = e.yOffsetAt(time) - e.yOffsetAt(time - dt); z = e.type == Element.Type.MOVE_Z ? e.zAt(time) : 0f;
                    if (motion != null) motion.elPut(motionGen, i, e, mk, time, es1[i], ey1[i], edy[i], z);
                }
                if (e.type == Element.Type.MOVE_Z) gone[i] = Math.abs(z) > Element.Z_REACH && !(mode == Mode.GROUND && onElem == i);   // out of your plane: nothing to land on
            }
            else if (e.type == Element.Type.SEESAW) {          // surface height is reported at the player's own position along the plank
                float hw = e.halfW(), x = Math.max(-hw, Math.min(hw, course.dsWrap(s, es1[i])));
                boolean on = mode == Mode.GROUND && onElem == i;
                float xr = x;
                if (e.tiltBelt()) { float dz = Math.min(T.beltSeesawDead, hw * 0.5f); xr = Math.signum(x) * Math.max(0f, Math.abs(x) - dz) * hw / (hw - dz); }     // Build 77 (owner): a calm middle, so one step off the pivot doesn't slam it down
                float kp = tilt[i], tgt = on ? -Math.max(-1f, Math.min(1f, xr / hw)) * T.seesawMaxTilt : 0f;
                if (e.tiltBelt() && on && xr != 0f) onT[i] = Math.signum(xr);        // Build 79: the belt turns to run toward the end you stand on, and keeps running that way
                float kn = approach(kp, tgt, (on ? T.seesawRate : T.seesawRelax) * dt);
                tilt[i] = kn; ey0[i] = e.y + kp * x; ey1[i] = e.y + kn * x; edy[i] = kn * x - kp * x;
            } else if (e.type == Element.Type.RAMP) {          // sloped walkway: surface height at the player's own position along it
                float hw = e.halfW(), x = Math.max(-hw, Math.min(hw, course.dsWrap(s, es1[i])));
                boolean on = mode == Mode.GROUND && onElem == i;
                onT[i] = on ? onT[i] + dt : Math.max(0f, onT[i] - 1.5f * dt);
                ey0[i] = e.y + e.rampSlope(time - dt) * x; ey1[i] = e.y + e.rampSlope(time) * x; edy[i] = e.rampSlope(time) * x - e.rampSlope(time - dt) * x;
            }
            if (padSquash[i] > 0) padSquash[i] = Math.max(0, padSquash[i] - dt);
            if (e.holed() && crumbleT[i] >= 0f && crumbleT[i] < Element.HOLE_DONE) {             // Build 79: its holes open HOLE_DELAY after you land; rules 19 (owner): the planks never come back
                float c0 = crumbleT[i]; crumbleT[i] = Math.min(Element.HOLE_DONE, crumbleT[i] + dt);
                if (c0 < Element.HOLE_DELAY && crumbleT[i] >= Element.HOLE_DELAY) events |= EV_CRUMBLE;
            }
            if (e.crumbles()) {
                if (gone[i]) { goneT[i] -= dt; if (goneT[i] <= 0) { gone[i] = false; crumbleT[i] = -1f; } }
                else if (crumbleT[i] >= 0) { crumbleT[i] += dt; if (crumbleT[i] >= (e.type == Element.Type.CRUMBLE || e.type == Element.Type.RAMP ? T.crumbleDelay : T.moverCrumbleDelay)) { gone[i] = true; goneT[i] = T.crumbleRespawn; events |= EV_CRUMBLE; } }
            }
        }

        if (mode != Mode.AIR) sideIn = -1;
        if (in.jumpPressed) jumpBuf = T.jumpBuffer + assistForgive; else jumpBuf = Math.max(0, jumpBuf - dt);
        if (in.jumpPressed && mode == Mode.AIR) reboundBuf = REBOUND_WINDOW; else reboundBuf = Math.max(0f, reboundBuf - dt);          // (a fresh press only: holding Jump never re-arms it)
        if (lockout > 0) lockout = Math.max(0, lockout - dt);

        switch (mode) {
            case GROUND: if (onElem < 0 && onSlab >= 0) stepSlab(in, dt); else stepGround(in, dt); break;
            case AIR: stepAir(in, dt); break;
            case ROPE: stepRope(in, dt); break;
            case CABLE: stepCable(in, dt); break;
            case LEDGE: stepLedge(in, dt); break;
            case PULLUP: stepPull(dt); break;
            case BEAM: stepBeam(in, dt); break;
            case TARZAN: stepTarzan(in, dt); break;
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
        else if (hazardHit()) { hits++; events |= EV_HIT; hitS = s; hitY = y; if (hitBy != null && hitBy.type == Element.Type.AVALANCHE) avlHits++; knockOff(); }

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
        if (idx < crabFall.length && crabFall[idx] >= 0f) {           // Build 79: falling with a crumbled deck; gone for good once it is well below
            crabFall[idx] += DT;
            if (crabFall[idx] >= CRAB_FALL_T) featDone[idx] = true;
            return;
        }
        float cs = stepCrab(e, idx), cy = crabY(idx, e), dx = course.dsWrap(s, cs);
        if (idx < crabFall.length && crabFall[idx] >= 0f) return;
        if (swingT > 0.06f && swingT < 0.28f) {                       // the club is on its way through: anything in front of you and within reach goes flying
            float ahead = -dx * facing;
            if (ahead > -0.3f && ahead < 2.2f && Math.abs(cy - y) < 1.5f) { featDone[idx] = true; crabS = cs; crabY = cy; knocked = 0; clubHit(); events |= EV_CRAB_OFF; return; }
        }
        if (shoveCd <= 0f && invuln <= 0f && Math.abs(dx) < 0.55f + hw && y < cy + 0.62f && y + T.height > cy + 0.05f && (mode == Mode.GROUND || mode == Mode.AIR)) {
            float side = Math.signum(dx); if (side == 0f) side = -facing;
            vx = side * 9f; vy = Math.max(vy, 6f); mode = Mode.AIR; onElem = -1; lockout = 0.3f; jumpedUp = false; coyote = 0f; shoveCd = 0.9f; events |= EV_SHOVE;
            tryRebound(cs, cy + 0.35f);          // (the crab's body centre)
        }
    }
    /** Build 79: a bridge's plank holes are open (see Element.holed); you drop through one once your middle is HOLE_FOOT inside its edges. */
    public boolean holesOpen(int i) { Element e = course.get(i); return e.holed() && i < crumbleT.length && crumbleT[i] >= Element.HOLE_DELAY; }
    public static final float HOLE_FOOT = 0.2f;
    /** Build 79 (owner): every creature the club knocks away costs CLUB_HIT_COST seconds of it. */
    private void clubHit() { clubTime = Math.max(0f, clubTime - CLUB_HIT_COST); }
    private void stepBee(Element e, int idx, float hw) {
        if (!e.beePresent(time)) { if (idx < mobS.length) { mobS[idx] = 0f; mobY[idx] = 0f; } return; }     // away: its next visit starts from its post again
        float[] b = beeAt(idx, e, true);
        float bs = b[0], by = b[1], dx = course.dsWrap(s, bs);
        if (swingT > 0.06f && swingT < 0.28f) {
            float ahead = -dx * facing;
            if (ahead > -0.3f && ahead < 2.2f && Math.abs(by - (y + 0.7f)) < 1.6f) { featDone[idx] = true; crabS = bs; crabY = by; knocked = 1; clubHit(); events |= EV_CRAB_OFF; return; }
        }
        if (shoveCd <= 0f && invuln <= 0f && Math.abs(dx) < Element.BEE_R + hw && by + Element.BEE_R > y + 0.1f && by - Element.BEE_R < y + T.height - 0.05f && (mode == Mode.GROUND || mode == Mode.AIR)) {
            float side = Math.signum(dx); if (side == 0f) side = -facing;
            vx = side * Element.BEE_PUSH_X; vy = Math.max(vy, Element.BEE_PUSH_Y); mode = Mode.AIR; onElem = -1; lockout = 0.25f; jumpedUp = false; coyote = 0f; shoveCd = 1.0f; events |= EV_SHOVE;
            tryRebound(bs, by);
        }
    }
    private final float[] beeTmp = new float[2], beeEnv = new float[4];
    /**
     * Where bee {@code e} (hazard index idx) is: its flight path (Element.beeS / beeY) plus a drift toward you (Build 71). While it visits and you are within
     * BEE_SEEK of its post, its buzzing centre drifts toward you at BEE_DRIFT m/s within its envelope (EncounterModel.beeEnvelope: at least BEE_REACH,
     * out to the platforms either side of it), back toward its post when you are not noticed, and its dive swoops most of the way to your chest.
     * {@code advance} moves the drift on one step (the simulation); false only reads it (the renderer). Driven by sim state alone: deterministic.
     */
    public float[] beeAt(int idx, Element e, boolean advance) {
        float ps = e.beeS(time), py = e.beeY(time);
        if (idx >= mobS.length) { beeTmp[0] = ps; beeTmp[1] = py; return beeTmp; }
        if (advance) {
            float dPost = course.dsWrap(s, e.s), dyPost = y - e.y;
            boolean seek = Math.abs(dPost) < Element.BEE_SEEK && dyPost > -4f && dyPost < 3f && (mode == Mode.GROUND || mode == Mode.AIR);
            EncounterModel.beeEnvelope(course, e, beeEnv);          // owner decision B (2026-10-25): it may follow you onto the platforms either side, at the same drift speed; its post stays put
            float tx = seek ? Math.max(beeEnv[0], Math.min(beeEnv[1], dPost)) : 0f;
            float ty = seek ? Math.max(beeEnv[2], Math.min(beeEnv[3], (y + 0.9f) - e.y)) : 0f;
            float st = (Element.BEE_DRIFT * T.react()) * DT;
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

    // ---------------------------------------------------------------- creature decks (Build 79, owner: they reach the platforms before and after their own)
    /** Element index of the deck creature {@code hi} is on now: its anchor plus its deck offset (-1 when that is outside the course). */
    public int mobDeckIdx(int hi, Element e) { return deck(e.anchor, hi < deckOff.length ? Math.round(deckOff[hi]) : 0); }
    /**
     * DC6 (owner correction 2026-10-27): the k-th route deck from route element {@code a} (k < 0: before it), skipping side structures (fork branches,
     * key rooms: elements with an anchor), or -1 outside the course. A slice keeps its route contiguous, but the live tower lists each slice's side
     * structures after its route, so "anchor + k" near a slice's end named another slice's decoy (a crab's deck -2 resolved to a pad 46 m lower);
     * stepping over route decks gives the same deck in the generator's proofs and in the live game.
     */
    public int deck(int a, int k) { return course.deck(a, k); }
    /** The route offset of deck {@code j} from {@code a} (as {@link #deck}), within +-lim, or NONE. */
    private int offsetOf(int a, int j, int lim) { if (j < 0) return NONE; for (int d = -lim; d <= lim; d++) if (deck(a, d) == j) return d; return NONE; }
    /** The last deck of an encounter of {@code n} decks from {@code a0}. */
    private int lastDeck(int a0, int n) { int q = deck(a0, n - 1); return q < 0 ? a0 + n - 1 : q; }
    /** A deck a creature may move onto: a flat, fixed platform on the route (no movers, ramps or tipping bridges) that is there; crabs also walk onto crumbling ones. */
    private boolean roamDeck(int j, boolean crab) {
        if (j < 0 || j >= course.routeSize() || j >= gone.length) return false;
        Element d = course.get(j);
        if (d.anchor >= 0 || d.type == Element.Type.PAD || d.type == Element.Type.SPRING || d.type == Element.Type.GOAL) return false;          // (DC6: a route deck, never a side structure, a launcher or the goal, in the live tower as in a slice)
        if (!d.isPlatform() || d.isMoving() || d.type == Element.Type.RAMP || d.type == Element.Type.SEESAW || gone[j]) return false;
        return crab || !d.crumbles();
    }
    /** Height of deck j's top (a mover's live height). */
    public float deckY(int j) { Element d = course.get(j); return d.isMoving() ? ey1[j] : d.y; }
    private static final int NONE = Integer.MIN_VALUE;
    /** The offset from deck a of the deck you stand on, if it is within lo..hi and one a creature can move to (its own deck always counts), else NONE. */
    private int standOff(int a, int lo, int hi, boolean crab) {
        if (mode != Mode.GROUND || onElem < 0) return NONE;
        int k = offsetOf(a, onElem, Math.max(Math.abs(lo), Math.abs(hi)));
        if (k == NONE || k < lo || k > hi || (k != 0 && !roamDeck(onElem, crab))) return NONE;
        return k;
    }

    /**
     * Skull: 1 hovers and bobs above one end of its deck; once you have been on the deck SKULL_NOTICE and it has rested SKULL_REST, 2 it drops low (SKULL_WINDUP),
     * 3 dashes along the deck at SKULL_SPEED toward your side to the far end, 4 rises and turns round (SKULL_TURN), and hovers again. Only the dash hits:
     * it is low (centre SKULL_LOW above the deck), so a jump clears it; a hit throws you hard along its way. Build 79: when you stand on the platform before
     * or after its own, 5 it flies over (SKULL_FLY) to the end of that deck away from you and hunts you there; SKULL_HOME seconds without you on any of the
     * three, it flies home. Pure sim state: deterministic.
     */
    private void stepSkull(Element e, int idx, float hw) {
        if (idx >= mobState.length || (idx < featDone.length && featDone[idx])) return;
        int a0 = e.anchor, j = mobDeckIdx(idx, e);
        if (j < 0 || (j != a0 && !roamDeck(j, false))) { deckOff[idx] = 0f; j = a0; }
        Element deck = j >= 0 && j < course.size() && course.get(j).isPlatform() ? course.get(j) : null;
        float dS = deck != null ? es1[j] : e.s, half = deck != null ? deck.halfW() : Math.max(2f, e.w * 0.5f), dY = deck != null ? deckY(j) : e.y;
        float end = Math.max(0.5f, half - Element.SKULL_END);
        if (mobState[idx] == 0) { mobState[idx] = 1; mobS[idx] = course.wrap(dS + end); mobDir[idx] = -1f; mobT[idx] = (Element.SKULL_REST / T.react()); mobY[idx] = Element.SKULL_HOVER; mobSeen[idx] = 0f; }
        if (swingT > 0.06f && swingT < 0.28f && idx < featDone.length) {      // Build 79 (owner): the club bats it away like a hooked golf shot - and breaks doing it
            float ahead = -course.dsWrap(s, mobS[idx]) * facing, ky = dY + mobY[idx];
            if (ahead > -0.3f && ahead < 2.2f && Math.abs(ky - (y + 0.7f)) < 1.6f) { featDone[idx] = true; crabS = mobS[idx]; crabY = ky; knocked = 2; clubTime = 0f; events |= EV_CRAB_OFF; return; }
        }
        boolean sees = onDeck(dS, half, dY);
        mobSeen[idx] = sees ? mobSeen[idx] + DT : 0f;
        mobT[idx] += DT;
        switch (mobState[idx]) {
            case 1: {
                mobY[idx] = Element.SKULL_HOVER + 0.12f * (float) StrictMath.sin(time * 2.6f + e.phase);
                int cur = Math.round(deckOff[idx]), want = standOff(a0, -1, 1, false);
                boolean home = want == NONE && !sees && cur != 0 && mobT[idx] >= (Element.SKULL_HOME / T.react());
                if (home) want = 0;
                if (want != NONE && want != cur && deck(a0, want) >= 0 && Math.abs(deckY(deck(a0, want)) - dY) <= Element.ROAM_DY) {
                    int nj = deck(a0, want); Element nd = course.get(nj);
                    float nS = es1[nj], nEnd = Math.max(0.5f, nd.halfW() - Element.SKULL_END);
                    float side = home ? 1f : (course.dsWrap(s, nS) >= 0f ? -1f : 1f);      // the end away from you, so its dash comes across at you (home: where it started)
                    mobY[idx] += dY - deckY(nj);                                          // the same point in the air, measured from the new deck
                    deckOff[idx] = want; mobTgt[idx] = course.wrap(nS + side * nEnd); mobDir[idx] = -side; mobState[idx] = 5; mobT[idx] = 0f;
                    break;
                }
                if (sees && mobSeen[idx] >= (Element.SKULL_NOTICE / T.react()) && mobT[idx] >= (Element.SKULL_REST / T.react())) {
                    float d = course.dsWrap(s, mobS[idx]), dir = d > 0.05f ? 1f : d < -0.05f ? -1f : mobDir[idx];
                    float tgt = dS + dir * end;
                    if (Math.abs(course.dsWrap(tgt, mobS[idx])) < 1.2f) { dir = -dir; tgt = dS + dir * end; }      // already at that end: go the other way
                    mobDir[idx] = dir; mobTgt[idx] = course.wrap(tgt); mobState[idx] = 2; mobT[idx] = 0f;
                }
                break;
            }
            case 2: {          // Build 77: a loop-the-loop where it hovers, then a swoop down (drawing back a little) to dash height. It always winds up at the end opposite its target.
                float base = course.wrap(dS - mobDir[idx] * end), t = mobT[idx];
                if (t < (Element.SKULL_LOOP_T / T.react())) {
                    double th = 2.0 * Math.PI * smooth01(t / (Element.SKULL_LOOP_T / T.react()));
                    mobS[idx] = course.wrap(base - mobDir[idx] * Element.SKULL_LOOP_R * (float) StrictMath.sin(th));
                    mobY[idx] = Element.SKULL_HOVER + Element.SKULL_LOOP_R * (1f - (float) StrictMath.cos(th));
                } else {
                    float k = smooth01((t - (Element.SKULL_LOOP_T / T.react())) / ((Element.SKULL_WINDUP / T.react()) - (Element.SKULL_LOOP_T / T.react())));
                    mobS[idx] = course.wrap(base - mobDir[idx] * Element.SKULL_SWOOP_BACK * (float) StrictMath.sin(Math.PI * k));
                    mobY[idx] = Element.SKULL_HOVER + (Element.SKULL_LOW - Element.SKULL_HOVER) * k;
                }
                if (t >= (Element.SKULL_WINDUP / T.react())) { mobS[idx] = base; mobY[idx] = Element.SKULL_LOW; mobState[idx] = 3; mobT[idx] = 0f; events |= EV_DASH; }
                break;
            }
            case 3: {
                mobY[idx] = Element.SKULL_LOW;
                float rem = course.dsWrap(mobTgt[idx], mobS[idx]) * mobDir[idx], stp = (Element.SKULL_SPEED * T.react()) * DT;
                if (rem <= stp) { mobS[idx] = mobTgt[idx]; mobState[idx] = 4; mobT[idx] = 0f; }
                else mobS[idx] = course.wrap(mobS[idx] + mobDir[idx] * stp);
                float cy = dY + mobY[idx], dx = course.dsWrap(s, mobS[idx]);
                if (shoveCd <= 0f && invuln <= 0f && Math.abs(dx) < Element.SKULL_R + hw && cy - Element.SKULL_R < y + T.height && cy + Element.SKULL_R > y + 0.05f && (mode == Mode.GROUND || mode == Mode.AIR)) {
                    vx = mobDir[idx] * Element.SKULL_LAUNCH_X; vy = Math.max(vy, Element.SKULL_LAUNCH_Y); mode = Mode.AIR; onElem = -1; coyote = 0f; lockout = 0.35f; jumpedUp = false; shoveCd = 0.9f;
                    events |= EV_SHOVE | EV_LAUNCH;
                }
                break;
            }
            case 5: {          // Build 79: flying over to another deck (it does not hit on the way)
                float d = course.dsWrap(mobTgt[idx], mobS[idx]), st = (Element.SKULL_FLY * T.react()) * DT;
                mobS[idx] = Math.abs(d) <= st ? mobTgt[idx] : course.wrap(mobS[idx] + Math.signum(d) * st);
                mobY[idx] = approach(mobY[idx], Element.SKULL_HOVER, st);
                if (Math.abs(d) <= st && Math.abs(mobY[idx] - Element.SKULL_HOVER) < 1e-4f) { mobState[idx] = 1; mobT[idx] = (Element.SKULL_REST / T.react()); }
                break;
            }
            default: {
                float k = smooth01(mobT[idx] / (Element.SKULL_TURN / T.react()));
                mobY[idx] = Element.SKULL_LOW + (Element.SKULL_HOVER - Element.SKULL_LOW) * k;
                if (mobT[idx] >= (Element.SKULL_TURN / T.react())) { mobDir[idx] = -mobDir[idx]; mobState[idx] = 1; mobT[idx] = 0f; }
                break;
            }
        }
    }
    /**
     * Enforcer robot (PUNISHER, owner 2026-10-27). States: 1 waits at the far end of its first deck; 2 chases - runs at you on your deck, runs to the
     * edge and hops to the next deck toward you (one deck at a time, only gaps it can jump, inside its {@code len} decks); 3 kick - rears back
     * (ENF_WINDUP, the telegraph), kicks for ENF_KICK_ACT (a hit throws you off your footing), recovers; 4 swat (orange, red) - while you are in the
     * air within reach it jumps (ENF_SWAT_VY, steering toward where you are - red: where you will be) and bats you away on contact; 5 hop; 6 runs up the
     * tower to its deck's far edge, 8 leaps away up and right; 7 falls (it missed its deck). It leaves after ENF_PURSUE seconds of chase or once you are past
     * its last deck. Ballistic parts use the player's gravity. Per-hazard state reuses the creature arrays: mobS arc, mobY height above its deck,
     * mobDir facing, mobTgt hop/edge target, mobSeen chase clock, deckOff deck, crabVel vertical speed, crabFrom arc speed (or hop origin), crabStuck
     * notice clock, crabCool swat cooldown. Pure sim state: deterministic.
     */
    private void stepEnforcer(Element e, int idx, float hw) {
        if (idx >= mobState.length || (idx < featDone.length && featDone[idx])) return;
        int tier = Math.max(0, Math.min(2, e.skin)), a0 = e.anchor, n = Math.max(1, Math.round(e.len));
        if (a0 < 0 || a0 >= course.size()) return;
        float rc = T.react();
        if (mobState[idx] == 0) {
            Element d0 = course.get(a0);
            // (owner 2026-10-27, variety: not the same encounter every time) where it waits along its first deck - from the far end to a third of the
            // way in - comes from its seed (phase); its notice delay and its leap away vary a little the same way
            float far = Math.max(0f, d0.halfW() - 0.6f), at = far - (far + d0.halfW() * 0.33f) * enfVar(e, 0) * 0.85f;
            mobState[idx] = 1; deckOff[idx] = 0f; mobS[idx] = course.wrap(es1[a0] + Math.max(-d0.halfW() * 0.33f, at)); mobDir[idx] = -1f; mobY[idx] = 0f;
            mobT[idx] = 0f; mobSeen[idx] = 0f; crabVel[idx] = 0f; crabFrom[idx] = 0f; crabStuck[idx] = 0f; crabCool[idx] = 0f;
        }
        int j = deck(a0, Math.round(deckOff[idx]));
        if (j < 0 || j >= course.size()) { featDone[idx] = true; return; }
        Element deck = course.get(j);
        float dS = es1[j], half = deck.halfW(), dY = deckY(j), g = T.gravity;
        mobT[idx] += DT;
        if (crabCool[idx] > 0f) crabCool[idx] = Math.max(0f, crabCool[idx] - DT);
        int cur = Math.round(deckOff[idx]), pk = standOff(a0, -1, n - 1, false);
        float dx = course.dsWrap(s, mobS[idx]);
        byte st = mobState[idx];
        if (st >= 2 && st <= 5) mobSeen[idx] += DT;
        boolean past = mode == Mode.GROUND && onElem >= 0 && onElem < course.routeSize() && onElem > lastDeck(a0, n) && course.get(onElem).anchor < 0;
        float temper = 0.85f + 0.3f * enfTrait(e, 4, 0f, 1f);          // (rules 14: its temper scales its chase time and swat cooldown, 0.85-1.15)
        if (st >= 2 && st <= 3 && (mobSeen[idx] >= ENFPURSUE(tier, rc) * temper || past)) { mobState[idx] = 6; mobT[idx] = 0f; st = 6; enfRetreats++; }
        switch (st) {
            case 1: {
                crabStuck[idx] = (pk != NONE || onDeck(dS, half, dY)) ? crabStuck[idx] + DT : 0f;
                if (crabStuck[idx] >= Element.ENF_NOTICE[tier] * (0.8f + 0.4f * enfVar(e, 1)) / rc) { mobState[idx] = 2; mobT[idx] = 0f; crabStuck[idx] = 0f; events |= EV_ENF_ALERT; enfAlerts++; }
                break;
            }
            case 2: {
                float run = Element.ENF_RUN[tier] * rc * DT;
                // rules 14 (owner: no lemmings, no trained assassins): a swat only after its reaction time with you in reach in the air, and only when it
                // has somewhere it accepts to come down; otherwise it keeps after you on foot (below: under you along its deck, or the next deck over)
                boolean inReach = mode == Mode.AIR && Element.ENF_SWAT_RANGE[tier] > 0f && Math.abs(dx) < Element.ENF_SWAT_RANGE[tier]
                        && y - dY > 0.6f && y - dY < 3.2f && (tier == 2 || vy > -2f);
                crabStuck[idx] = inReach ? crabStuck[idx] + DT : 0f;
                if (inReach && crabCool[idx] <= 0f && crabStuck[idx] >= enfTrait(e, 5, Element.ENF_REACT_LO[tier], Element.ENF_REACT_HI[tier]) / rc
                        && enfSwat(e, idx, tier, j, a0, n, dx, rc, hw, temper)) break;
                int want = pk != NONE ? Math.max(0, pk) : cur;
                if (want == cur) {
                    if (mode == Mode.GROUND && onElem == j && Math.abs(dx) <= Element.ENF_HALF + hw + Element.ENF_KICK_REACH[tier]) {
                        mobDir[idx] = dx >= 0f ? 1f : -1f; mobState[idx] = 3; mobT[idx] = 0f; enfKickTries++;
                    } else if (Math.abs(dx) > 0.3f && (mode == Mode.GROUND && onElem == j || mode == Mode.AIR)) {
                        float lo = -half + 0.3f, hiE = half - 0.3f, x = course.dsWrap(mobS[idx], dS);
                        float nx = Math.max(lo, Math.min(hiE, x + Math.signum(dx) * run));
                        mobS[idx] = course.wrap(dS + nx); mobDir[idx] = Math.signum(dx);
                    }
                } else {
                    int nj = j + (want > cur ? 1 : -1);
                    float side = course.dsWrap(es1[nj], dS) >= 0f ? 1f : -1f, edge = side * (half - 0.35f), x = course.dsWrap(mobS[idx], dS);
                    mobDir[idx] = side;
                    if (Math.abs(edge - x) > run) mobS[idx] = course.wrap(dS + x + Math.signum(edge - x) * run);
                    else if (enfHopOk(j, nj)) {
                        Element nd = course.get(nj);
                        crabFrom[idx] = mobS[idx]; mobTgt[idx] = course.wrap(es1[nj] - side * Math.max(0.3f, nd.halfW() - 0.5f));
                        crabVel[idx] = nj - a0; mobState[idx] = 5; mobT[idx] = 0f; enfHops++;
                    }
                }
                break;
            }
            case 3: {
                float wu = Element.ENF_WINDUP[tier] / rc, act = Element.ENF_KICK_ACT, rec = Element.ENF_RECOVER[tier] / rc, t = mobT[idx];
                if (t >= wu && t < wu + act && shoveCd <= 0f && invuln <= 0f && (mode == Mode.GROUND || mode == Mode.AIR)) {
                    float ahead = dx * mobDir[idx];
                    if (ahead > -Element.ENF_HALF - hw && ahead < Element.ENF_HALF + hw + Element.ENF_KICK_REACH[tier] && y < dY + 1.3f && y + T.height > dY + 0.1f) {
                        vx = mobDir[idx] * Element.ENF_KICK_X; vy = Math.max(vy, Element.ENF_KICK_Y); mode = Mode.AIR; onElem = -1; coyote = 0f; lockout = 0.3f; jumpedUp = false; shoveCd = 0.9f;
                        events |= EV_SHOVE | EV_LAUNCH | EV_ENF_HIT; enfKickHits++;
                    }
                }
                if (t >= wu + act + rec) { mobState[idx] = 2; mobT[idx] = 0f; }
                break;
            }
            case 4: {
                // in the air (rules 14): limited steering only - rising, it keeps after you; coming down, it steers for the deck it planned to land on.
                // It lands on whichever deck of its encounter it actually comes down on, and falls only once it has passed below all of them
                float prevY = mobY[idx], steer = enfTrait(e, 8, Element.ENF_STEER_LO[tier], Element.ENF_STEER_HI[tier]) * DT, lim = Element.ENF_RUN[tier] * rc * 1.3f, v0 = crabFrom[idx];
                if (crabVel[idx] > 0f) {
                    float tA = Math.max(0.05f, crabVel[idx] / g), want = course.dsWrap(s, mobS[idx]) / tA;
                    crabFrom[idx] = Math.max(-lim, Math.min(lim, v0 + Math.max(-steer, Math.min(steer, want - v0))));
                    crabStuck[idx] += Math.abs(crabFrom[idx] - v0);          // how far its chase has pulled it off the jump it planned
                } else {
                    int q = deck(a0, Math.round(mobTgt[idx]));
                    if (q >= 0 && q < course.size()) {
                        float r = deckY(q) - dY - mobY[idx], disc = crabVel[idx] * crabVel[idx] - 2f * g * r;
                        if (disc >= 0f) {
                            float t = Math.max(0.05f, (crabVel[idx] + (float) Math.sqrt(disc)) / g), in = Math.max(0f, course.get(q).halfW() - 0.3f);
                            float land = course.dsWrap(course.wrap(mobS[idx] + v0 * t), es1[q]), to = Math.max(-in, Math.min(in, land));
                            crabFrom[idx] = Math.max(-lim, Math.min(lim, v0 + Math.max(-steer, Math.min(steer, (to - land) / t))));
                        }
                    }
                }
                mobS[idx] = course.wrap(mobS[idx] + crabFrom[idx] * DT); mobY[idx] += crabVel[idx] * DT; crabVel[idx] -= g * DT;
                float cy = dY + mobY[idx], bx = course.dsWrap(s, mobS[idx]);
                if (shoveCd <= 0f && invuln <= 0f && (mode == Mode.GROUND || mode == Mode.AIR) && Math.abs(bx) < Element.ENF_HALF + hw + 0.25f && y < cy + Element.ENF_H + Element.ENF_REACH_UP && y + T.height > cy) {      // (DC4: hanging on a rope, cable or ledge is out of a creature's reach, as for every other creature)
                    vx = (bx >= 0f ? 1f : -1f) * Element.ENF_SWAT_X; vy = Math.min(vy, Element.ENF_SWAT_Y); mode = Mode.AIR; onElem = -1; coyote = 0f; lockout = 0.3f; jumpedUp = false; shoveCd = 0.9f;
                    events |= EV_SHOVE | EV_LAUNCH | EV_ENF_HIT; enfSwatHits++;
                }
                if (crabVel[idx] < 0f) {
                    int q = enfTouch(j, a0, n, prevY, mobY[idx], mobS[idx]);
                    if (q >= 0) {
                        if (q == j) enfLandOwn++; else { deckOff[idx] = offsetOf(a0, q, n + 2); enfLandNear++; }
                        mobY[idx] = 0f; crabVel[idx] = 0f; crabFrom[idx] = 0f; crabStuck[idx] = 0f; mobState[idx] = 2; mobT[idx] = 0f;
                    } else if (mobY[idx] < enfLowest(j, a0, n) - 0.5f) {          // below every deck it could land on: it falls
                        int p = deck(a0, Math.round(mobTgt[idx]));
                        float off = p >= 0 && p < course.size() ? Math.abs(course.dsWrap(mobS[idx], es1[p])) - course.get(p).halfW() : 99f;
                        if (crabStuck[idx] > 1f) enfFallChase++; else if (off < 0.6f) enfFallEdge++; else enfFallNoDeck++;
                        mobState[idx] = 7; mobT[idx] = 0f; enfFalls++;
                    }
                }
                break;
            }
            case 5: {
                int to = Math.round(crabVel[idx]), nj = deck(a0, to);
                float k = Math.min(1f, mobT[idx] / (Element.ENF_HOP_T / rc)), rise = deckY(nj) - dY;
                mobS[idx] = course.wrap(crabFrom[idx] + course.dsWrap(mobTgt[idx], crabFrom[idx]) * k);
                mobY[idx] = rise * k + 4f * (Element.ENF_HOP_PEAK + Math.max(0f, rise) * 0.5f) * k * (1f - k);
                if (k >= 1f) { deckOff[idx] = to; mobY[idx] = 0f; crabVel[idx] = 0f; mobState[idx] = 2; mobT[idx] = 0f; }
                break;
            }
            case 6: {          // leaving: up the tower, to its deck's far (+s) edge, then a leap away
                float x = course.dsWrap(mobS[idx], dS), edge = half - 0.3f, run = Element.ENF_EXIT_RUN * rc * DT;
                mobDir[idx] = 1f;
                if (edge - x > run) mobS[idx] = course.wrap(mobS[idx] + run);
                else { crabFrom[idx] = Element.ENF_EXIT_VX * (0.8f + 0.4f * enfVar(e, 2)); crabVel[idx] = Element.ENF_EXIT_VY * (0.8f + 0.4f * enfVar(e, 3)); mobState[idx] = 8; mobT[idx] = 0f; events |= EV_ENF_JUMP; }
                break;
            }
            default: {          // 7 falling, 8 leaping away: ballistic, then gone
                mobS[idx] = course.wrap(mobS[idx] + crabFrom[idx] * DT); mobY[idx] += crabVel[idx] * DT; crabVel[idx] -= g * DT;
                if ((st == 8 && (mobY[idx] > Element.ENF_GONE_Y || mobT[idx] > 2.5f)) || (st == 7 && mobT[idx] > Element.ENF_FALL_T)) featDone[idx] = true;
                break;
            }
        }
    }
    /** A seeded value in [0, 1) for draw k of an Enforcer (its phase: the same on every platform). 0 for an unseeded one (phase 0): the original behaviour. */
    private static float enfVar(Element e, int k) { return e.phase == 0f ? 0f : avlRand(e, 7, k); }
    private static float ENFPURSUE(int tier, float rc) { return Element.ENF_PURSUE[tier] / rc; }
    /** One of a robot's fixed traits (rules 14): its seeded draw k placed in [lo, hi]; an unseeded robot (phase 0) sits in the middle of its tier's range. */
    private static float enfTrait(Element e, int k, float lo, float hi) { return lo + (hi - lo) * (e.phase == 0f ? 0.5f : avlRand(e, 7, k)); }
    private float enfMargin;          // (scratch: enfLand's landing margin)
    /**
     * Sizes up a swat (rules 14 jump judgment) from a few candidate jumps - toward where it reads you will be, part way there, straight up - each kept
     * only if it can still reach you and comes down on a deck of its encounter with a margin this robot accepts (category A, or B within its boldness;
     * C, nothing to land on, is never attempted). It takes the one that gets closest to you, reasonable before risky; false when none qualifies (it
     * looks again after a moment and keeps after you on foot meanwhile). Closed-form arcs: a handful of products per candidate, no search.
     */
    private boolean enfSwat(Element e, int idx, int tier, int j, int a0, int n, float dx, float rc, float hw, float temper) {
        float g = T.gravity, vy0 = Element.ENF_SWAT_VY, tUp = vy0 / g, lim = Element.ENF_RUN[tier] * rc * 1.3f;
        float pred = course.dsWrap(s + vx * tUp, mobS[idx]), aim = dx + enfTrait(e, 6, Element.ENF_PREDICT_LO[tier], Element.ENF_PREDICT_HI[tier]) * (pred - dx);
        float need = enfTrait(e, 7, Element.ENF_MARGIN_LO[tier], Element.ENF_MARGIN_HI[tier]), reach = Element.ENF_HALF + hw + 0.6f;
        float best = Float.MAX_VALUE, bestV = 0f; int bestQ = -1; boolean any = false, risky = false;
        for (int c = 0; c < 4; c++) {
            float want = c == 0 ? aim : c == 1 ? 0.65f * aim : c == 2 ? 0.35f * aim : 0f;
            float v = Math.max(-lim, Math.min(lim, want / tUp)), miss = Math.abs(aim - v * tUp);
            if (miss > reach) continue;
            any = true;
            int q = enfLand(j, a0, n, mobS[idx], v, vy0, 0f);
            if (q < 0 || enfMargin < need) continue;
            boolean b = enfMargin < Element.ENF_SAFE;
            float score = miss + (b ? 0.5f : 0f);
            if (score < best) { best = score; bestV = v; bestQ = q; risky = b; }
        }
        if (!any) return false;
        enfSwatConsidered++;
        if (bestQ < 0) { enfSwatRejected++; crabCool[idx] = 0.25f / rc; return false; }
        if (risky) enfSwatRisky++;
        crabFrom[idx] = bestV; crabVel[idx] = vy0; mobY[idx] = 0f; mobTgt[idx] = bestQ - a0; crabStuck[idx] = 0f;
        mobDir[idx] = dx >= 0f ? 1f : -1f; mobState[idx] = 4; mobT[idx] = 0f; crabCool[idx] = Element.ENF_SWAT_CD[tier] * temper / rc; events |= EV_ENF_JUMP; enfSwatTries++;
        return true;
    }
    /** Decks a robot of encounter a0..a0+n-1 standing on deck j can land on: those within two of j that are flat fixed route decks (its own always). */
    private boolean enfDeck(int q, int j) { return q == j || roamDeck(q, false); }
    /**
     * Where a robot leaving arc {@code s0}, {@code y0} above deck j, at {@code v} along and {@code vy0} up, first comes down on a deck it can land on:
     * that deck's index (or -1), with enfMargin = how far in from that deck's nearer end. Uses the decks' real spans and heights as they are now (a robot
     * knows nothing of the future).
     */
    private int enfLand(int j, int a0, int n, float s0, float v, float vy0, float y0) {
        float g = T.gravity, dY = deckY(j), bestT = Float.MAX_VALUE; int best = -1;
        for (int dd = -2; dd <= 2; dd++) {
            int q = deck(j, dd);
            if (q < a0 || q > lastDeck(a0, n) || q >= course.size() || !enfDeck(q, j)) continue;
            float r = deckY(q) - dY - y0, disc = vy0 * vy0 - 2f * g * r;
            if (disc < 0f) continue;
            float t = (vy0 + (float) Math.sqrt(disc)) / g;
            if (t <= 0f || t >= bestT) continue;
            float m = course.get(q).halfW() - Math.abs(course.dsWrap(course.wrap(s0 + v * t), es1[q]));
            if (m < 0f) continue;
            bestT = t; best = q; enfMargin = m;
        }
        return best;
    }
    /** The deck (of those it can land on) whose top a robot coming down from {@code y0} to {@code y1} above deck j crossed this step over its span, or -1. */
    private int enfTouch(int j, int a0, int n, float y0, float y1, float ms) {
        float dY = deckY(j), top = -Float.MAX_VALUE; int best = -1;
        for (int dd = -2; dd <= 2; dd++) {
            int q = deck(j, dd);
            if (q < a0 || q > lastDeck(a0, n) || q >= course.size() || !enfDeck(q, j)) continue;
            float r = deckY(q) - dY;
            if (y0 >= r && y1 < r && r > top && Math.abs(course.dsWrap(ms, es1[q])) <= course.get(q).halfW()) { top = r; best = q; }
        }
        return best;
    }
    /** The lowest deck top it could land on, relative to deck j: below that (and a little more) it has fallen. */
    private float enfLowest(int j, int a0, int n) {
        float dY = deckY(j), lo = 0f;
        for (int dd = -2; dd <= 2; dd++) { int q = deck(j, dd); if (q >= a0 && q <= lastDeck(a0, n) && q < course.size() && enfDeck(q, j)) lo = Math.min(lo, deckY(q) - dY); }
        return lo;
    }
    /** The robot can hop from deck j to deck nj: a flat fixed route deck, a gap it can jump and a height it can climb or drop. */
    private boolean enfHopOk(int j, int nj) {
        if (!roamDeck(nj, false)) return false;
        Element a = course.get(j), b = course.get(nj);
        float gap = Math.abs(course.dsWrap(es1[nj], es1[j])) - a.halfW() - b.halfW();
        return gap <= Element.ENF_HOP_GAP && Math.abs(deckY(nj) - deckY(j)) <= Element.ENF_HOP_DY;
    }
    // ---------------------------------------------------------------- spike-block avalanche (one physics for every mode)

    private int avlTouched = -1;          // (scratch: the deck a block touched this step, for the traversal-area counter)
    /** A seeded value in [0, 1) for block b, draw k of an avalanche (a fixed integer mixer over its phase bits: the same on every platform). */
    private static float avlRand(Element e, int b, int k) {
        long z = (Float.floatToIntBits(e.phase) & 0xffffffffL) * 0x9E3779B97F4A7C15L + b * 0xBF58476D1CE4E5B9L + k * 0x94D049BB133111EBL + 0x2545F4914F6CDD1DL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L; z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL; z ^= z >>> 31;
        return (z >>> 40) / (float) (1L << 24);
    }
    private boolean avlDeck(int j) {
        if (j < 0 || j >= course.routeSize() || j >= gone.length || gone[j]) return false;
        Element d = course.get(j);
        return d.isPlatform() && d.anchor < 0 && d.type != Element.Type.ROPE && d.type != Element.Type.CABLE && d.type != Element.Type.PAD && d.type != Element.Type.SPRING && d.type != Element.Type.GOAL;          // (DC6)
    }
    /** Deck j's top at arc offset x from its centre (a ramp's slope), and the slope there. */
    private float avlTop(int j, float x) {
        Element d = course.get(j); float cx = Math.max(-d.halfW(), Math.min(d.halfW(), x));
        if (d.type == Element.Type.RAMP) return d.y + d.rampSlope(time) * cx;
        if (d.type == Element.Type.SEESAW) return d.y + tilt[j] * cx;          // (DC6: the plank as it is tilted, not flat)
        return deckY(j);
    }

    /**
     * Rules 18 (owner Build 83 avalanche overhaul, 2026-10-29): has the climber reached avalanche e's trigger, or are they moving toward it fast
     * enough to get there before its blocks could come down? Standing on the trigger deck fires it, as before. Otherwise, within AVL_LEAD_MAX m
     * short of the deck's near edge (along the route), AVL_LEAD_BELOW below to AVL_LEAD_ABOVE above it, the climber's real speed toward it gives
     * the time they need to get there; once that is no more than the blocks' fall time (+ AVL_LEAD_PAD) it fires. Flying over the deck (a jump or a
     * launch that never lands on it) fires it too. A faster climber is met earlier: a run fires a few metres short, a launch from the deck before.
     * Each spot fires once (its state goes to 1), whatever the climber does next.
     */
    private boolean avlReached(int a0) {
        if (mode == Mode.GROUND && onElem == a0) return true;
        float dir = avlDir(a0);
        if (dir == 0f) return false;
        Element d = course.get(a0);
        float dy = y - deckY(a0);
        if (dy < -Element.AVL_LEAD_BELOW || dy > Element.AVL_LEAD_ABOVE) return false;
        float over = Math.abs(course.dsWrap(es1[a0], s));
        if (mode == Mode.AIR && over <= d.halfW() && dy >= -0.5f) return true;
        float dist = course.dsWrap(es1[a0] - dir * d.halfW(), s) * dir, v = vx * dir;
        if (dist <= 0f || dist > Element.AVL_LEAD_MAX || v <= 0.3f) return false;
        return dist / v <= avlFallTime() + Element.AVL_LEAD0 + Element.AVL_LEAD_PAD;
    }
    /** The way along the tower the route runs through trigger deck a0 (+1 or -1; 0 when it cannot tell). */
    private float avlDir(int a0) {
        int p = deck(a0, -1), n = deck(a0, 1);
        float d = p >= 0 ? course.dsWrap(es1[a0], es1[p]) : (n >= 0 ? course.dsWrap(es1[n], es1[a0]) : 0f);
        return d > 0f ? 1f : (d < 0f ? -1f : 0f);
    }
    /** Seconds a block takes to come down AVL_DROP m from rest. */
    private static float avlFallTime() { return (float) Math.sqrt(2f * Element.AVL_DROP / Element.AVL_G); }
    /** Rules 18 measurements of the last avalanche fired (reports and tests only): when, the climber's speed toward it (m/s), how far short of the
     *  trigger deck's edge they were (m, 0 on it), the first hard deck impact after it fired and the first block within 2.5 m of the body (s, -1 none). */
    public float avlTrigT = -1f, avlTrigV, avlTrigDist, avlFirstImpactT = -1f, avlFirstNearT = -1f;
    /** Rules 18 counters: rebounds (deck or wall contacts that throw the block back off at more than 1.5 m/s) and tumbles (contacts that changed a
     *  block's spin by more than 180 deg/s: a corner landing, a glancing knock). */
    public int avlRebounds, avlTumbles;

    private void avlMeasure(int a0) {
        float dir = avlDir(a0), dist = dir == 0f ? 0f : course.dsWrap(es1[a0] - dir * course.get(a0).halfW(), s) * dir;
        avlTrigT = time; avlTrigV = vx * dir; avlTrigDist = Math.max(0f, dist); avlFirstImpactT = -1f; avlFirstNearT = -1f;
    }

    /** The route decks an avalanche's blocks can touch (offsets -8..8 from its trigger deck; -1 where there is none or it cannot be hit), looked up once
     *  per step (rules 18 profiling: walking the route for every block, sub-step and deck was 60% of generation time). Scratch, not state. */
    private final int[] avlDk = new int[17];
    /** ... and, for the rigid blocks, each deck's arc offset from the trigger deck, half width and top this step (a ramp or seesaw: NaN, its top is per point). */
    private final float[] avlDo = new float[17], avlDw = new float[17], avlDt = new float[17];
    private void avlNearDecks(int a0) {
        float as = es1[a0];
        for (int dd = -8; dd <= 8; dd++) {
            int j = deck(a0, dd), k = dd + 8; avlDk[k] = avlDeck(j) ? j : -1;
            if (avlDk[k] < 0) continue;
            Element d = course.get(j);
            avlDo[k] = course.dsWrap(es1[j], as); avlDw[k] = d.halfW(); avlDt[k] = d.type == Element.Type.RAMP || d.type == Element.Type.SEESAW ? Float.NaN : deckY(j);
        }
    }

    private void stepAvalanche(Element e, int idx) {
        if (e.skin == 1 || e.skin == 2) { stepRigid(e, idx); return; }          // rules 18: NOT GONNA HAPPEN (1) and PUNISHER (2) rigid cubes; the code below is NORMAL's
        int o = idx * AVL_W, a0 = e.anchor;
        if (o + AVL_W > avl.length || a0 < 0 || a0 >= course.size()) return;
        float st = avl[o];
        if (st == 2f) { if (idx < featDone.length) featDone[idx] = true; return; }          // (DC3: spent is resolved, so it stays spent across a slice unload: Tower keeps featDone)
        if (idx < featDone.length && featDone[idx]) { avl[o] = 2f; return; }
        if (st == 0f) {
            if (!avlReached(a0)) return;
            if (time - lastAvlT < e.period) { if (mode == Mode.GROUND && onElem == a0) { avl[o] = 2f; avlSkipped++; } return; }          // reached during the cooldown: this spot is spent, it never fires later (a lead during the cooldown waits)
            avl[o] = 1f; avl[o + 1] = 0f; lastAvlT = time; events |= EV_AVL_WARN; avlFired++; avlMeasure(a0);
            int n = Math.max(1, Math.min(Element.AVL_MAX, Math.round(e.len)));
            float ay = course.get(a0).y;
            for (int b = 0; b < Element.AVL_MAX; b++) {
                int p = o + 2 + b * AVL_B;
                for (int q = 0; q < AVL_B; q++) avl[p + q] = 0f;
                if (b >= n) continue;
                // each block comes down over one of the decks ahead (up the tower), seeded: which deck, where on it, when, how fast and spinning which way
                int kk = 1 + (int) (avlRand(e, b, 0) * 3.99f), j = deck(a0, kk); while (kk > 1 && !avlDeck(j)) j = deck(a0, --kk);
                if (!avlDeck(j)) j = a0;
                Element d = course.get(j);
                float ds = course.dsWrap(es1[j], es1[a0]) + (avlRand(e, b, 1) - 0.5f) * Math.max(0.4f, d.w - 0.8f);
                avl[p] = ds; avl[p + 1] = deckY(j) - ay + Element.AVL_DROP + 2.5f * avlRand(e, b, 2);
                avl[p + 2] = -(0.6f + 2.4f * avlRand(e, b, 3)); avl[p + 3] = -1.5f * avlRand(e, b, 4);
                avl[p + 4] = 90f * avlRand(e, b, 5); avl[p + 5] = (avlRand(e, b, 6) - 0.5f) * 500f;
                avl[p + 6] = 2f; avl[p + 7] = Element.AVL_LEAD0 + b * Element.AVL_STAGGER + 0.25f * avlRand(e, b, 7);          // (rules 18: no warning wait)
            }
            return;
        }
        avl[o + 1] += DT;
        avlNearDecks(a0);
        float clock = avl[o + 1], ay = course.get(a0).y, as = es1[a0], g = Element.AVL_G, R = Element.AVL_R;
        int live = 0;
        for (int b = 0; b < Element.AVL_MAX; b++) {
            int p = o + 2 + b * AVL_B;
            if (avl[p + 6] == 2f) { if (clock >= avl[p + 7]) { avl[p + 6] = 1f; avl[p + 7] = 0f; avlReleased++; } else { live++; continue; } }
            if (avl[p + 6] != 1f) continue;
            avl[p + 3] -= g * DT; avl[p] += avl[p + 2] * DT; avl[p + 1] += avl[p + 3] * DT; avl[p + 4] += avl[p + 5] * DT;
            avlTouched = -1;
            for (int dd = -8; dd <= 8; dd++) {          // the decks: tops (ramps on their slope) and sides
                int j = avlDk[dd + 8];
                if (j < 0) continue;
                Element d = course.get(j);
                float hw = d.halfW(), x = course.dsWrap(as + avl[p], es1[j]), by = ay + avl[p + 1];
                float cx = Math.max(-hw, Math.min(hw, x)), top = avlTop(j, cx), cy = Math.max(top - Element.AVL_SLAB, Math.min(top, by));
                float dx = x - cx, dy = by - cy, d2 = dx * dx + dy * dy;
                if (d2 >= R * R) continue;
                float dist = (float) Math.sqrt(d2), nx, ny;
                if (dist > 1e-5f) { nx = dx / dist; ny = dy / dist; }
                else { nx = 0f; ny = 1f; dist = 0f; }
                if (d.type == Element.Type.RAMP && ny > 0.5f) { float sl = d.rampSlope(time), l = (float) Math.sqrt(1f + sl * sl); nx = -sl / l; ny = 1f / l; }      // on a slope: its own normal
                float push = R - dist; avl[p] += nx * push; avl[p + 1] += ny * push; if (avlTouched < 0 || Math.abs(j - a0) < Math.abs(avlTouched - a0)) avlTouched = j;
                float vn = avl[p + 2] * nx + avl[p + 3] * ny;
                if (vn < 0f) {
                    if (vn < -3f) { avlImpacts++; if (avlFirstImpactT < 0f) avlFirstImpactT = time - avlTrigT; }
                    avl[p + 2] -= (1f + Element.AVL_E) * vn * nx; avl[p + 3] -= (1f + Element.AVL_E) * vn * ny;
                    float tx = -ny, vt = avl[p + 2] * tx + avl[p + 3] * nx;          // along the surface: friction, and it rolls
                    float keep = vt * (Element.AVL_FRICTION - 1f);
                    avl[p + 2] += keep * tx; avl[p + 3] += keep * nx;
                    avl[p + 5] = -vt * Element.AVL_FRICTION / R * 57.29578f;
                }
            }
            for (int c = b + 1; c < Element.AVL_MAX; c++) {          // block on block: equal masses, a springy knock
                int q = o + 2 + c * AVL_B;
                if (avl[q + 6] != 1f) continue;
                float dx = avl[p] - avl[q], dy = avl[p + 1] - avl[q + 1], d2 = dx * dx + dy * dy;
                if (d2 >= 4f * R * R || d2 < 1e-10f) continue;
                float dist = (float) Math.sqrt(d2), nx = dx / dist, ny = dy / dist, push = (2f * R - dist) * 0.5f;
                avl[p] += nx * push; avl[p + 1] += ny * push; avl[q] -= nx * push; avl[q + 1] -= ny * push;
                float vn = (avl[p + 2] - avl[q + 2]) * nx + (avl[p + 3] - avl[q + 3]) * ny;
                if (vn < 0f) {
                    float jn = -(1f + Element.AVL_EB) * vn * 0.5f;
                    avl[p + 2] += jn * nx; avl[p + 3] += jn * ny; avl[q + 2] -= jn * nx; avl[q + 3] -= jn * ny;
                    avlKnocks++; if (vn < -3f) avlImpacts++;
                }
            }
            int fl = (int) avl[p + 8];          // flags: 1 came near the body, 2 reached the traversal area
            if ((fl & 1) == 0) { float nx = Math.abs(course.dsWrap(as + avl[p], s)), ny = Math.abs(ay + avl[p + 1] - (y + T.height * 0.5f)); if (nx < 2.5f && ny < 2.5f) { fl |= 1; avlNear++; if (avlFirstNearT < 0f) avlFirstNearT = time - avlTrigT; } }
            if ((fl & 2) == 0 && offsetOf(a0, avlTouched, 8) != NONE && offsetOf(a0, avlTouched, 8) >= -1 && offsetOf(a0, avlTouched, 8) <= 4) { fl |= 2; avlArea++; }
            avl[p + 8] = fl; avlTouched = -1;
            float sp2 = avl[p + 2] * avl[p + 2] + avl[p + 3] * avl[p + 3];
            avl[p + 7] = sp2 < 0.09f ? avl[p + 7] + DT : 0f;
            if (avl[p + 1] < -Element.AVL_FLOOR || clock > Element.AVL_LIFE || avl[p + 7] > Element.AVL_REST || Math.abs(avl[p]) > 30f) { avl[p + 6] = 0f; continue; }
            live++;
        }
        if (live == 0) avl[o] = 2f;
    }

    // ---- PUNISHER and NOT GONNA HAPPEN avalanches (rules 18, owner Build 83 overhaul 2026-10-29): rigid spiked cubes, seeded, nothing scripted
    /** Block b's collision radius (m, the cube's half side): AVL_R for NORMAL/PUNISHER avalanches; NGH blocks 0.32-0.62 m from the avalanche's seed. */
    public static float avlRadius(Element e, int b) { return e.skin == 1 ? Element.AVL_CR0 + Element.AVL_CR1 * avlRand(e, b, 10) : Element.AVL_R; }
    /** Rules 18 block b: restitution off decks and walls, and its friction coefficient (each block its own, from the seed). */
    private static float rigidE(Element e, int b) { return Element.AVL_HE0 + Element.AVL_HE1 * avlRand(e, b, 11); }
    private static float rigidMu(Element e, int b) { return Element.AVL_HMU0 + Element.AVL_HMU1 * avlRand(e, b, 12); }
    /** Hard rebounds (a deck or wall impact faster than AVL_RICOCHET m/s; reports and tests only) and the most blocks falling at once. */
    public int avlRicochets, avlPeak;
    private static final float DEG = (float) (Math.PI / 180.0), RAD = (float) (180.0 / Math.PI);
    /** Presentation only (never read by the simulation, not saved): the hardest deck strike of each block of avalanche avlStrikeIdx in the last step (m/s). */
    private final float[] avlStrike = new float[Element.AVL_MAX]; private int avlStrikeIdx = -1;
    /** -1..1: which corner (or, near 0, the face between two) of a cube touches along a contact direction component u (soft, so a face lies flat). */
    private static float avlCorner(float u) { return Math.max(-1f, Math.min(1f, u * 5f)); }

    /**
     * PUNISHER's and NOT GONNA HAPPEN's avalanche: the trigger and cooldown every avalanche has (avlReached), then 8-10 (PUNISHER) or 9-12 (NGH)
     * blocks break loose AVL_GAP0-AVL_GAP1 s apart, the first AVL_LEAD0 s after the trigger. AVL_AIM of them come down where the climber will be
     * (their real velocity carried over the fall, landing a little ahead along the route), the rest over the decks ahead, thrown back down the route
     * toward the climber or (some) up it. Every block is a rigid cube with mass and moment of inertia: a contact acts at the corner or face that
     * touches, the impulse there (restitution, Coulomb friction) changes both its velocity and its spin, so corner landings tumble, a spinning block
     * kicks itself off line on the next bounce, and blocks bounce down stairs, off deck sides (walls) and off each other (mass-weighted, momentum
     * conserving, with friction between them that trades spin). AVL_HSUB sub-steps per step keep fast blocks from passing through decks or each other.
     * Pure simulation state and fixed arithmetic (StrictMath for the trigonometry): the same on every platform.
     */
    private void stepRigid(Element e, int idx) {
        int o = idx * AVL_W, a0 = e.anchor;
        if (o + AVL_W > avl.length || a0 < 0 || a0 >= course.size()) return;
        float st = avl[o];
        if (st == 2f) { if (idx < featDone.length) featDone[idx] = true; return; }
        if (idx < featDone.length && featDone[idx]) { avl[o] = 2f; return; }
        if (st == 0f) {
            if (!avlReached(a0)) return;
            if (time - lastAvlT < e.period) { if (mode == Mode.GROUND && onElem == a0) { avl[o] = 2f; avlSkipped++; } return; }
            avl[o] = 1f; avl[o + 1] = 0f; lastAvlT = time; events |= EV_AVL_WARN; avlFired++; avlMeasure(a0);
            int n = Math.max(1, Math.min(Element.AVL_MAX, Math.round(e.len)));
            float ay = course.get(a0).y, rel = Element.AVL_LEAD0, dir = avlDir(a0), fall = avlFallTime();
            if (dir == 0f) dir = 1f;
            for (int b = 0; b < Element.AVL_MAX; b++) {
                int p = o + 2 + b * AVL_B;
                for (int q = 0; q < AVL_B; q++) avl[p + q] = 0f;
                if (b >= n) continue;
                if (avlRand(e, b, 20) < Element.AVL_AIM) {          // aimed: where the climber's own motion takes them by the time it is down, a little ahead
                    float lead = vx * (rel + fall) + dir * (Element.AVL_AIM0 + (Element.AVL_AIM1 - Element.AVL_AIM0) * avlRand(e, b, 21));
                    lead = Math.max(-10f, Math.min(10f, lead));
                    avl[p] = course.dsWrap(s + lead, es1[a0]); avl[p + 1] = y - ay + Element.AVL_DROP + 1.2f * avlRand(e, b, 2);
                    avl[p + 2] = (avlRand(e, b, 3) - 0.5f) * 0.8f; avl[p + 3] = -1.2f * avlRand(e, b, 4);
                } else {          // over one of the decks ahead, thrown back down the route (70%) or on up it
                    int kk = 1 + (int) (avlRand(e, b, 0) * 3.99f), j = deck(a0, kk); while (kk > 1 && !avlDeck(j)) j = deck(a0, --kk);
                    if (!avlDeck(j)) j = a0;
                    Element d = course.get(j);
                    avl[p] = course.dsWrap(es1[j], es1[a0]) + (avlRand(e, b, 1) - 0.5f) * Math.max(0.4f, d.w - 0.8f);
                    avl[p + 1] = deckY(j) - ay + Element.AVL_DROP + 2.5f * avlRand(e, b, 2);
                    avl[p + 2] = (avlRand(e, b, 13) < 0.7f ? -dir : dir) * (0.8f + 3.4f * avlRand(e, b, 3)); avl[p + 3] = -2.5f * avlRand(e, b, 4);
                }
                avl[p + 4] = 90f * avlRand(e, b, 5); avl[p + 5] = (avlRand(e, b, 6) < 0.5f ? -1f : 1f) * (150f + 550f * avlRand(e, b, 14));
                avl[p + 6] = 2f; avl[p + 7] = rel;
                rel += Element.AVL_GAP0 + (Element.AVL_GAP1 - Element.AVL_GAP0) * avlRand(e, b, 7);
            }
            return;
        }
        avl[o + 1] += DT;
        avlStrikeIdx = idx; java.util.Arrays.fill(avlStrike, 0f);
        avlNearDecks(a0);
        float clock = avl[o + 1], ay = course.get(a0).y, as = es1[a0], g = Element.AVL_G, h = DT / Element.AVL_HSUB;
        int live = 0, falling = 0;
        for (int b = 0; b < Element.AVL_MAX; b++) {
            int p = o + 2 + b * AVL_B;
            if (avl[p + 6] == 2f && clock >= avl[p + 7]) { avl[p + 6] = 1f; avl[p + 7] = 0f; avlReleased++; }
            if (avl[p + 6] == 1f) falling++;
        }
        avlPeak = Math.max(avlPeak, falling);
        for (int sub = 0; sub < Element.AVL_HSUB; sub++) {
            for (int b = 0; b < Element.AVL_MAX; b++) {
                int p = o + 2 + b * AVL_B;
                if (avl[p + 6] != 1f) continue;
                float R = avlRadius(e, b), E = rigidE(e, b), MU = rigidMu(e, b), iM = 1f / (R * R), iI = iM * 1.5f / (R * R);          // a square: m ~ side^2, I = m side^2 / 6
                avl[p + 3] -= g * h; avl[p] += avl[p + 2] * h; avl[p + 1] += avl[p + 3] * h; avl[p + 4] = (avl[p + 4] + avl[p + 5] * h) % 360f;
                float reach = R * 1.4143f, by = ay + avl[p + 1];
                boolean touched = false;
                for (int dd = -8; dd <= 8; dd++) {          // the decks: tops (ramps on their slope), sides (walls) and corners
                    int k = dd + 8, j = avlDk[k];
                    if (j < 0) continue;
                    float hw = avlDw[k], x = avl[p] - avlDo[k];
                    if (x - hw >= reach || -x - hw >= reach) continue;          // (out of reach along the tower: no contact possible)
                    float t0 = avlDt[k];
                    if (t0 == t0 && (by - t0 >= reach || t0 - Element.AVL_SLAB - by >= reach)) continue;          // (a flat deck: out of reach above or below)
                    Element d = course.get(j);
                    float cx = Math.max(-hw, Math.min(hw, x)), top = t0 == t0 ? t0 : avlTop(j, cx), cy = Math.max(top - Element.AVL_SLAB, Math.min(top, by));
                    float dx = x - cx, dy = by - cy, d2 = dx * dx + dy * dy;
                    if (d2 >= reach * reach) continue;
                    float dist = (float) Math.sqrt(d2), nx, ny;
                    if (dist > 1e-5f) { nx = dx / dist; ny = dy / dist; } else { nx = 0f; ny = 1f; dist = 0f; }
                    if (d.type == Element.Type.RAMP && ny > 0.5f) { float sl = d.rampSlope(time), l = (float) Math.sqrt(1f + sl * sl); nx = -sl / l; ny = 1f / l; }
                    // the cube's touching corner (or face): its offset r from the centre, and how far the cube reaches toward the deck along -n
                    float th = avl[p + 4] * DEG, c = (float) StrictMath.cos(th), sn = (float) StrictMath.sin(th);
                    float lx = -(nx * c + ny * sn), ly = -(-nx * sn + ny * c), px = R * avlCorner(lx), py = R * avlCorner(ly);
                    float rx = px * c - py * sn, ry = px * sn + py * c, sup = -(rx * nx + ry * ny), pen = sup - dist;
                    if (pen <= 0f) continue;
                    avl[p] += nx * pen; avl[p + 1] += ny * pen; touched = true;
                    int fl = (int) avl[p + 8]; if ((fl & 2) == 0 && dd >= -1 && dd <= 4) { avl[p + 8] = fl | 2; avlArea++; }
                    float w = avl[p + 5] * DEG, vcx = avl[p + 2] - w * ry, vcy = avl[p + 3] + w * rx, vn = vcx * nx + vcy * ny;
                    if (vn >= 0f) continue;
                    if (vn < -3f) { avlImpacts++; if (avlFirstImpactT < 0f) avlFirstImpactT = time - avlTrigT; avlStrike[b] = Math.max(avlStrike[b], -vn); }
                    if (vn < -Element.AVL_RICOCHET) avlRicochets++;
                    float rn = rx * ny - ry * nx, ee = vn > -Element.AVL_HREST ? 0f : E, jn = -(1f + ee) * vn / (iM + rn * rn * iI);
                    float w0 = w;
                    avl[p + 2] += jn * nx * iM; avl[p + 3] += jn * ny * iM; w += rn * jn * iI;
                    float tx = -ny, ty = nx; vcx = avl[p + 2] - w * ry; vcy = avl[p + 3] + w * rx;
                    float vt = vcx * tx + vcy * ty, rt = rx * ty - ry * tx, jt = -vt / (iM + rt * rt * iI), lim = MU * jn;
                    jt = Math.max(-lim, Math.min(lim, jt));
                    avl[p + 2] += jt * tx * iM; avl[p + 3] += jt * ty * iM; w += rt * jt * iI;
                    avl[p + 5] = w * RAD;
                    if (avl[p + 2] * nx + avl[p + 3] * ny > 1.5f) avlRebounds++;
                    if (Math.abs(w - w0) * RAD > 180f) avlTumbles++;
                }
                for (int q2 = b + 1; q2 < Element.AVL_MAX; q2++) {          // block on block: mass ~ side squared, momentum conserved, friction trades spin
                    int q = o + 2 + q2 * AVL_B;
                    if (avl[q + 6] != 1f) continue;
                    float Rb = avlRadius(e, b) * 1.15f, Rc = avlRadius(e, q2) * 1.15f, rr = Rb + Rc;
                    float dx = avl[p] - avl[q], dy = avl[p + 1] - avl[q + 1], d2 = dx * dx + dy * dy;
                    if (d2 >= rr * rr || d2 < 1e-10f) continue;
                    float ib = 1f / (Rb * Rb), ic = 1f / (Rc * Rc), iIb = ib * 1.5f / (Rb * Rb), iIc = ic * 1.5f / (Rc * Rc);
                    float dist = (float) Math.sqrt(d2), nx = dx / dist, ny = dy / dist, pen = rr - dist, wb = ib / (ib + ic), wc = ic / (ib + ic);
                    avl[p] += nx * pen * wb; avl[p + 1] += ny * pen * wb; avl[q] -= nx * pen * wc; avl[q + 1] -= ny * pen * wc;
                    float vn = (avl[p + 2] - avl[q + 2]) * nx + (avl[p + 3] - avl[q + 3]) * ny;
                    if (vn >= 0f) continue;
                    float jn = -(1f + Element.AVL_HEB) * vn / (ib + ic);
                    avl[p + 2] += jn * nx * ib; avl[p + 3] += jn * ny * ib; avl[q + 2] -= jn * nx * ic; avl[q + 3] -= jn * ny * ic;
                    // friction at the contact (b's point -Rb n, c's +Rc n): slip = relative tangential speed there, including both spins
                    float tx = -ny, ty = nx, wB = avl[p + 5] * DEG, wC = avl[q + 5] * DEG;
                    float vt = (avl[p + 2] - avl[q + 2]) * tx + (avl[p + 3] - avl[q + 3]) * ty - wB * Rb - wC * Rc;
                    float jt = -vt / (ib + ic + Rb * Rb * iIb + Rc * Rc * iIc), lim = Element.AVL_HMUB * jn;
                    jt = Math.max(-lim, Math.min(lim, jt));
                    avl[p + 2] += jt * tx * ib; avl[p + 3] += jt * ty * ib; avl[q + 2] -= jt * tx * ic; avl[q + 3] -= jt * ty * ic;
                    avl[p + 5] = (wB - Rb * jt * iIb) * RAD; avl[q + 5] = (wC - Rc * jt * iIc) * RAD;
                    if (vn < -1f) avlKnocks++; if (vn < -3f) avlImpacts++;          // (a knock is a real collision, not blocks settling against each other)
                }
                float v2 = avl[p + 2] * avl[p + 2] + avl[p + 3] * avl[p + 3];
                if (touched && v2 < Element.AVL_SETTLE_V * Element.AVL_SETTLE_V && Math.abs(avl[p + 5]) < Element.AVL_SETTLE_W) {          // a slow block lying on a deck settles (no endless rocking on a face or corner)
                    avl[p + 2] *= Element.AVL_SETTLE_K; avl[p + 3] *= Element.AVL_SETTLE_K; avl[p + 5] *= Element.AVL_SETTLE_K; v2 *= Element.AVL_SETTLE_K * Element.AVL_SETTLE_K;
                }
                if (v2 > Element.AVL_VMAX * Element.AVL_VMAX) { float k = Element.AVL_VMAX / (float) Math.sqrt(v2); avl[p + 2] *= k; avl[p + 3] *= k; }
                avl[p + 5] = Math.max(-Element.AVL_WMAX, Math.min(Element.AVL_WMAX, avl[p + 5]));
            }
        }
        for (int b = 0; b < Element.AVL_MAX; b++) {
            int p = o + 2 + b * AVL_B;
            if (avl[p + 6] == 2f) { live++; continue; }
            if (avl[p + 6] != 1f) continue;
            int fl = (int) avl[p + 8];
            if ((fl & 1) == 0) { float nx = Math.abs(course.dsWrap(as + avl[p], s)), ny = Math.abs(ay + avl[p + 1] - (y + T.height * 0.5f)); if (nx < 2.5f && ny < 2.5f) { avl[p + 8] = fl | 1; avlNear++; if (avlFirstNearT < 0f) avlFirstNearT = time - avlTrigT; } }
            float sp2 = avl[p + 2] * avl[p + 2] + avl[p + 3] * avl[p + 3];
            avl[p + 7] = sp2 < 0.09f && Math.abs(avl[p + 5]) < 30f ? avl[p + 7] + DT : 0f;
            if (avl[p + 1] < -Element.AVL_FLOOR || clock > Element.AVL_LIFE || avl[p + 7] > Element.AVL_REST || Math.abs(avl[p]) > 30f) { avl[p + 6] = 0f; continue; }
            live++;
            if (DIAG_AVL_ENV) avlEnv(e, p, a0);
        }
        if (live == 0) avl[o] = 2f;
    }

    /** Diagnostics only (S1-C, owner directive 2026-10-29; off unless climb.diagAvlEnv is set, and then only inside generation proofs): where the blocks really go. */
    static final boolean DIAG_AVL_ENV = Boolean.getBoolean("climb.diagAvlEnv");
    /**
     * Tallies a falling block's 2 m cell relative to the trigger deck, and the first time it passes through the body (standing room included) of an
     * element of another revolution (blocks collide only with the trigger route's nearby decks), with whether the generator's lethal box covered it.
     */
    private void avlEnv(Element e, int p, int a0) {
        if (GenTrace.GEN.get() == null) return;
        GenTrace.tally("t4env_" + (int) Math.floor(avl[p] / 2f) + "_" + (int) Math.floor(avl[p + 1] / 2f));
        if (((int) avl[p + 8] & 8) != 0) return;
        Element d = course.get(a0);
        float bs = d.s + avl[p], by = d.y + avl[p + 1], half = course.circumference * 0.5f;
        float hs = CourseGenerator.hzS(e), hh = CourseGenerator.hzHalf(e), lo = CourseGenerator.hzLo(e), hi = CourseGenerator.hzHi(e);
        for (Element f : course.elements) {
            if (Math.abs(bs - f.s) < half || !(Math.abs(course.dsWrap(bs, f.s)) < CourseGenerator.arcHalf(f) && by > CourseGenerator.vLo(f) && by < CourseGenerator.vHi(f))) continue;
            avl[p + 8] = (int) avl[p + 8] | 8;
            boolean inBox = Math.abs(course.dsWrap(hs, f.s)) < hh + CourseGenerator.arcHalf(f) && lo < CourseGenerator.vHi(f) && hi > CourseGenerator.vLo(f);
            GenTrace.tally("t4envthru_" + f.type + "_" + (inBox ? "inbox" : "outside_box"));
            return;
        }
    }

    /** A falling block overlaps the body (a disc against the body's box). */
    private boolean avalancheHits(int idx, Element e, float hw, float lo, float hi) {
        int o = idx * AVL_W, a0 = e.anchor;
        if (o + AVL_W > avl.length || avl[o] != 1f || a0 < 0 || a0 >= course.size()) return false;
        float ay = course.get(a0).y, R = Element.AVL_R;
        for (int b = 0; b < Element.AVL_MAX; b++) {
            int p = o + 2 + b * AVL_B;
            if (avl[p + 6] != 1f) continue;
            if (e.skin == 1) R = avlRadius(e, b);
            float dx = Math.max(0f, Math.abs(course.dsWrap(es1[a0] + avl[p], s)) - hw), by = ay + avl[p + 1];
            float dy = by < lo ? lo - by : (by > hi ? by - hi : 0f);
            if (dx * dx + dy * dy < R * R) { hitFromS = es1[a0] + avl[p]; lastAvlHitV = (float) Math.sqrt(avl[p + 2] * avl[p + 2] + avl[p + 3] * avl[p + 3]); lastAvlHitAge = avl[o + 1]; return true; }
        }
        return false;
    }

    /** Diagnostics only (never read by the simulation): the speed (m/s) of the last avalanche block that hit the body, and how long its avalanche had run (s). */
    public float lastAvlHitV, lastAvlHitAge;
    /** Diagnostics: block b's speed (m/s). */
    public float avalancheBlockSpeed(int hi, int b) { int p = hi * AVL_W + 2 + b * AVL_B; return (hi + 1) * AVL_W <= avl.length ? (float) Math.sqrt(avl[p + 2] * avl[p + 2] + avl[p + 3] * avl[p + 3]) : 0f; }
    /** Avalanche state for the renderer and reports: 0 not reached, 1 under way, 2 spent. */
    public int avalancheState(int hi) { return (hi + 1) * AVL_W <= avl.length ? (int) avl[hi * AVL_W] : 0; }
    /** Block b: {arc, height, angle (deg), status (1 falling, 2 about to drop), collision radius, impact speed (m/s) of a deck strike this step (0 none)}
     *  in world terms, or null when it is not there. */
    public float[] avalancheBlock(int hi, Element e, int b) {
        int o = hi * AVL_W, p = o + 2 + b * AVL_B;
        if ((hi + 1) * AVL_W > avl.length || avl[o] != 1f || e.anchor < 0 || e.anchor >= course.size() || avl[p + 6] == 0f) return null;
        return new float[]{course.wrap(es1[e.anchor] + avl[p]), course.get(e.anchor).y + avl[p + 1], avl[p + 4], avl[p + 6], avlRadius(e, b), hi == avlStrikeIdx && b < avlStrike.length ? avlStrike[b] : 0f};
    }

    /** Enforcer state for the renderer and reports (0 not started, 1 waiting, 2 chasing, 3 kicking, 4 swatting, 5 hopping, 6 leaving, 7 falling, 8 leaping away). */
    public int enforcerState(int hi) { return hi < mobState.length ? mobState[hi] : 0; }
    /** Its feet: arc and absolute height. */
    public float enforcerS(int hi, Element e) { return hi < mobS.length && mobState[hi] != 0 ? mobS[hi] : e.s; }
    public float enforcerY(int hi, Element e) { int j = mobDeckIdx(hi, e); return (j >= 0 ? deckY(j) : e.y) + (hi < mobY.length ? mobY[hi] : 0f); }
    /** Its deck: the offset from its first deck it stands on (or took off from). */
    public int enforcerDeck(int hi) { return hi < deckOff.length ? Math.round(deckOff[hi]) : 0; }
    /** Its speed along the lane while in the air (a swat's steering), m/s. */
    public float enforcerVs(int hi) { return hi < crabFrom.length && hi < mobState.length && mobState[hi] == 4 ? crabFrom[hi] : 0f; }
    /** Tests and probes (rules 14): put Enforcer {@code hi} straight into its chase, standing on deck offset {@code off} of its encounter at arc {@code at}. */
    public void enforcerChaseAt(int hi, int off, float at) {
        if (hi >= mobState.length) return;
        mobState[hi] = 2; deckOff[hi] = off; mobS[hi] = course.wrap(at); mobY[hi] = 0f; mobT[hi] = 0f; mobSeen[hi] = 0f; mobDir[hi] = 1f;
        crabVel[hi] = 0f; crabFrom[hi] = 0f; crabStuck[hi] = 0f; crabCool[hi] = 0f;
    }

    /** The skull's live arc / height above its deck / heading (for the renderer and the planner's reflex); state 3 = dashing. */
    public boolean skullDashing(int hi) { return hi < mobState.length && mobState[hi] == 3; }

    /**
     * Green blob: 1 sits; once you have been on one of its decks BLOB_NOTICE and it has rested BLOB_REST, 3 leaps BLOB_APEX up off the screen and falls back over
     * BLOB_AIR (Build 79, owner: no wobble first); BLOB_SHADOW before it lands it picks its spot - where you are on its own deck or the platform before or after it,
     * or, if you stand on a tipping bridge, the far end from you - and its shadow shows there; 4 it lands and rests BLOB_REST, then leaps again at once if you are
     * still there. Landing on you squashes you flat and knocks you away; landing on the far end of the bridge you stand on launches you BLOB_LAUNCH times the jump
     * speed (three times the jump height). BLOB_HOME seconds without you on any of the three decks, it leaps home. Pure sim state: deterministic.
     */
    private void stepBlob(Element e, int idx, float hw) {
        if (idx >= mobState.length) return;
        int a0 = e.anchor;
        if (mobState[idx] == 0) { mobState[idx] = 1; mobS[idx] = course.wrap(e.s); mobT[idx] = (Element.BLOB_REST / T.react()); mobDir[idx] = 0f; deckOff[idx] = 0f; }
        int a = mobDeckIdx(idx, e);
        if (a < 0 || (a != a0 && !roamDeck(a, false))) { deckOff[idx] = 0f; a = a0; }
        Element deck = a >= 0 && a < course.size() && course.get(a).isPlatform() ? course.get(a) : null;
        float dS = deck != null ? es1[a] : e.s, half = deck != null ? deck.halfW() : Math.max(2f, e.w * 0.5f), dY = deck != null ? deckY(a) : e.y;
        int cur = Math.round(deckOff[idx]), want = standOff(a0, -1, 1, false);
        boolean over = onDeck(dS, half, dY), sees = want != NONE || over;
        mobSeen[idx] = sees ? mobSeen[idx] + DT : 0f;
        float t0 = mobT[idx]; mobT[idx] += DT;
        switch (mobState[idx]) {
            case 1: case 2: {
                mobY[idx] = 0f;
                boolean home = cur != 0 && !sees && mobT[idx] >= (Element.BLOB_REST / T.react()) + (Element.BLOB_HOME / T.react());
                if ((sees && mobSeen[idx] >= (Element.BLOB_NOTICE / T.react()) && mobT[idx] >= (Element.BLOB_REST / T.react())) || home) { mobState[idx] = 3; mobT[idx] = 0f; mobDir[idx] = 0f; events |= EV_BLOB_JUMP; }
                break;
            }
            case 3: {
                mobY[idx] = Element.blobLeapY(Math.min(1f, mobT[idx] / Element.BLOB_AIR));
                float lockAt = Element.BLOB_AIR - Element.BLOB_SHADOW;
                if (t0 < lockAt && mobT[idx] >= lockAt) {              // it picks its spot now; its shadow shows there from here on
                    int to = want != NONE ? want : over ? cur : sees ? cur : 0;
                    if (to != cur && (deck(a0, to) < 0 || Math.abs(deckY(deck(a0, to)) - dY) > Element.ROAM_DY)) to = cur;
                    boolean homeSpot = to == 0 && want == NONE && !over;
                    int nj = deck(a0, to); deckOff[idx] = to;
                    Element nd = course.get(nj);
                    float nS = es1[nj], nEnd = Math.max(0.4f, nd.halfW() - Element.BLOB_END), x = course.dsWrap(s, nS);
                    if (nd.type == Element.Type.SEESAW && mode == Mode.GROUND && onElem == nj) { float side = x >= 0f ? 1f : -1f; mobTgt[idx] = course.wrap(nS - side * nEnd); mobDir[idx] = side; }
                    else if (homeSpot) { mobTgt[idx] = course.wrap(e.s); mobDir[idx] = 0f; }
                    else { mobTgt[idx] = course.wrap(nS + Math.max(-nEnd, Math.min(nEnd, x))); mobDir[idx] = 0f; }
                    mobS[idx] = mobTgt[idx];
                }
                if (mobT[idx] >= Element.BLOB_AIR) {
                    mobY[idx] = 0f; mobState[idx] = 4; mobT[idx] = 0f; events |= EV_BLOB_LAND;
                    int la = mobDeckIdx(idx, e); Element ld = la >= 0 ? course.get(la) : null;
                    if (ld != null && ld.isPlatform()) blobLands(ld, la, es1[la], deckY(la), ld.type == Element.Type.SEESAW, idx, hw);
                }
                break;
            }
            default:
                mobY[idx] = 0f;
                if (mobT[idx] >= (Element.BLOB_REST / T.react())) { mobState[idx] = 1; mobT[idx] = (Element.BLOB_REST / T.react()); }
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
        if (invuln <= 0f && Math.abs(dx) < Element.BLOB_R + hw && y < surf + 1.4f && y > surf - 0.6f && (mode == Mode.GROUND || mode == Mode.AIR)) {      // squashed flat, and knocked away
            float push = dx > 0f ? 1f : dx < 0f ? -1f : -facing;
            vx = push * Element.BLOB_PUSH_X; vy = Math.max(vy, Element.BLOB_PUSH_Y); mode = Mode.AIR; onElem = -1; coyote = 0f; lockout = 0.4f; jumpedUp = false; shoveCd = 0.9f;
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
    /** Build 79 (owner): a chasing crab follows you up to CRAB_FOLLOW decks past its own, hopping gaps up to CRAB_HOP_GAP wide and CRAB_HOP_DY high in CRAB_HOP_T seconds
     *  (CRAB_HOP_H high); a crab on a deck that crumbles falls with it and is gone after CRAB_FALL_T seconds. */
    public static final int CRAB_FOLLOW = 3;
    /** Build 79: the longest it watches you in the air before it gives up (a jump over a gap is well under this). */
    public static final float CRAB_WAIT = 1.5f;
    public static final float CRAB_HOP_GAP = 3.2f, CRAB_HOP_DY = 1.8f, CRAB_HOP_T = 0.55f, CRAB_HOP_H = 1.0f, CRAB_FALL_T = 1.5f;
    /** Build 79 (owner): seconds of club each knocked creature costs. */
    public static final float CLUB_HIT_COST = 5f;

    /**
     * Advances crab {@code e} (hazard index {@code idx}) one step and returns its arc. On its sweep it follows the patrol exactly (as before Build 63,
     * a pure function of time); the moment it sees you over its own deck it runs at you at CRAB_CHASE times its peak patrol speed (stopping for
     * CRAB_TURN seconds to turn round when you are behind it), kept on its own platform; when it loses you it walks back onto the sweep and resumes it.
     * Driven by sim state alone, so it is deterministic and the generator's proofs and the autopilot (which hops it where it actually is) include the chase.
     */
    /** Hazard {@code hi}'s arc, height and (crabs) facing on its sweep right now, through the planner's MotionCache when there is one: the offset of
     *  the three values in {@link #hzv}. */
    private int hzPose(int hi, Element e) {
        int o = motion == null ? -1 : motion.hzAt(motionGen, hi, e, mstep - 1, time);
        if (o >= 0) { hzv = motion.hzVals(hi); return o; }
        float sa = e.sAt(time), ya = e.yAt(time), face = e.type == Element.Type.CRAB ? (e.amp * (float) StrictMath.cos(2 * Math.PI * time / e.period + e.phase) >= 0f ? 1f : -1f) : 0f;
        if (motion != null) motion.hzPut(motionGen, hi, e, mstep - 1, time, sa, ya, face);
        hzTmp[0] = sa; hzTmp[1] = ya; hzTmp[2] = face; hzv = hzTmp; return 0;
    }
    private float[] hzv; private final float[] hzTmp = new float[3];

    private float stepCrab(Element e, int idx) {
        int po = hzPose(idx, e);
        float patrol = hzv[po], sweepFace = hzv[po + 2];
        if (idx >= crabPos.length) return patrol;
        int a0 = e.anchor, off = Math.round(deckOff[idx]), j = deck(a0, off);
        if (off != 0 && (j < 0 || j >= course.size())) { off = 0; j = a0; deckOff[idx] = 0f; crabJump[idx] = -1f; crabMode[idx] = 2; }     // its deck left the course window: back home
        Element deck = j >= 0 && j < course.size() && course.get(j).isPlatform() ? course.get(j) : null;
        float dS = deck != null ? es1[j] : e.s;
        if (crabJump[idx] >= 0f) {                                    // Build 79: in a hop to the next deck
            crabJump[idx] += DT;
            float k = Math.min(1f, crabJump[idx] / (CRAB_HOP_T / T.react())), land = hopLanding(a0, Math.round(crabFromDeck[idx]), off, crabFrom[idx]);
            float ns = course.wrap(crabFrom[idx] + course.dsWrap(land, crabFrom[idx]) * k);
            crabVel[idx] = course.dsWrap(ns, crabPos[idx]); crabPos[idx] = ns;
            if (k >= 1f) { crabJump[idx] = -1f; if (deck == null || gone[j]) crabFall[idx] = 0f; }
            return ns;
        }
        if (deck != null && j < gone.length && (gone[j] || (holesOpen(j) && deck.overHole(course.dsWrap(crabMode[idx] == 0 && off == 0 ? patrol : crabPos[idx], dS), 0.1f)))) { crabFall[idx] = 0f; crabPos[idx] = crabMode[idx] == 0 && off == 0 ? patrol : crabPos[idx]; crabMode[idx] = 2; return crabPos[idx]; }    // its deck crumbled under it: it never thought to hurry
        float cs = crabMode[idx] == 0 && off == 0 ? patrol : crabPos[idx];
        float dx = course.dsWrap(s, cs), dy = y - (off == 0 ? e.y : deckY(j));
        boolean over = deck == null || Math.abs(course.dsWrap(s, dS)) <= deck.halfW() + CRAB_SIGHT_OVER;     // it watches its own deck only
        boolean sees = over && Math.abs(dx) <= CRAB_SIGHT && dy >= CRAB_SIGHT_LO && dy <= CRAB_SIGHT_HI && (mode == Mode.GROUND || mode == Mode.AIR);
        if (crabCool[idx] > 0f) { crabCool[idx] = Math.max(0f, crabCool[idx] - DT); sees = false; }
        if (!sees) crabSeen[idx] = 0f;
        else if (crabMode[idx] != 1) { crabSeen[idx] += DT; if (crabSeen[idx] < (CRAB_NOTICE / T.react())) sees = false; }   // a moment's notice before it reacts; once chasing it keeps you in sight
        float stepLen = (CRAB_CHASE * T.react()) * 2f * (float) Math.PI * Math.abs(e.amp) / Math.max(0.1f, e.period) * DT;
        float ns = cs;
        // Build 79 (owner: crabs follow you for 2-3 platforms): chasing, it goes after you onto the next or previous deck (up to CRAB_FOLLOW past its own); having lost you, it hops back home
        int follow = 0;
        if (!sees && deck != null) {
            int ps = crabMode[idx] == 1 ? standOff(j, -1, 1, true) : NONE;
            if (ps != NONE && ps != 0 && off + ps >= 0 && off + ps <= CRAB_FOLLOW && canHop(j, j + ps)) follow = ps;
            else if (crabMode[idx] == 1 && mode == Mode.AIR && Math.abs(dx) <= CRAB_SIGHT + 3f && dy > -4f && dy < CRAB_SIGHT_HI + 2f && crabStuck[idx] < CRAB_WAIT) follow = 2;      // you are in the air over a gap near its deck: it waits (a moment) to see where you land
            else if (off != 0) { int back = off > 0 ? -1 : 1; if (canHop(j, j + back)) follow = back * 3; }      // lost you away from home: it heads back
        }
        // the way it faces: along its sweep, else the way it last moved (or was turned)
        float face = crabMode[idx] == 0 && off == 0 ? sweepFace : (crabVel[idx] >= 0f ? 1f : -1f);
        boolean turning = false;
        float lim = deck != null ? Math.max(0f, deck.halfW() - CRAB_EDGE) : 1e9f;
        if (sees || follow == 1 || follow == -1 || follow == 3 || follow == -3) {
            float want = sees ? (dx > 0f ? 1f : dx < 0f ? -1f : face) : (course.dsWrap(es1[j + (follow > 0 ? 1 : -1)], dS) >= 0f ? 1f : -1f);
            if (want != face) { crabTurn[idx] = (CRAB_TURN / T.react()); face = want; }                  // you are behind it: it stops and turns round before it comes on
            crabMode[idx] = (byte) (follow == 3 || follow == -3 ? 2 : 1);
            if (crabTurn[idx] > 0f) { crabTurn[idx] = Math.max(0f, crabTurn[idx] - DT); turning = true; }
            else if (sees) ns = cs + want * Math.min(stepLen, Math.abs(dx));
            else {                                                                      // to the edge facing that deck, then over the gap
                float edge = dS + want * lim, toEdge = course.dsWrap(edge, cs);
                if (Math.abs(toEdge) <= stepLen) {
                    int to = off + (follow > 0 ? 1 : -1);
                    crabFrom[idx] = course.wrap(edge); crabFromDeck[idx] = off; deckOff[idx] = to; crabJump[idx] = 0f; crabStuck[idx] = 0f; crabCool[idx] = 0f;
                    crabVel[idx] = want * 1e-3f; crabPos[idx] = crabFrom[idx];
                    return crabPos[idx];
                }
                ns = cs + Math.signum(toEdge) * stepLen;
            }
        } else if (follow == 2) {
            crabTurn[idx] = 0f; crabStuck[idx] += DT;                                   // it stands at its edge and watches you fly
        } else {
            crabTurn[idx] = 0f;
            if (crabMode[idx] != 0) {
                if (off != 0) crabMode[idx] = 2;                                         // stranded away from home: it stays put
                else {
                    float back = course.dsWrap(patrol, cs);
                    if (Math.abs(back) <= stepLen) { ns = patrol; crabMode[idx] = 0; } else { crabMode[idx] = 2; ns = cs + Math.signum(back) * stepLen; }
                }
            }
        }
        if (deck != null) { float o = course.dsWrap(ns, dS); if (o > lim) ns = dS + lim; else if (o < -lim) ns = dS - lim; }   // never off its deck except by a hop
        ns = course.wrap(ns);
        float moved = course.dsWrap(ns, cs);
        if (crabMode[idx] == 1 && !turning && follow == 0) {                            // held at its edge with you beyond it: it soon loses interest, and does not camp there
            if (Math.abs(moved) < 1e-4f && Math.abs(dx) > 0.6f) { crabStuck[idx] += DT; if (crabStuck[idx] >= CRAB_STUCK) { crabMode[idx] = 2; crabCool[idx] = CRAB_COOL; crabStuck[idx] = 0f; } }
            else crabStuck[idx] = 0f;
        } else if (follow != 2) crabStuck[idx] = 0f;
        crabVel[idx] = Math.abs(moved) > 1e-5f ? moved : (crabMode[idx] == 1 ? face * 1e-3f : 0f);   // faces the way it moves; standing (turning, or held at its edge) it faces you
        crabPos[idx] = ns;
        return ns;
    }
    /** Build 79: a crab can hop from deck j to deck k: k is a deck it may stand on, the gap between their edges is at most CRAB_HOP_GAP and the climb at most CRAB_HOP_DY. */
    private boolean canHop(int j, int k) {
        if (!roamDeck(k, true) || j < 0 || j >= course.size()) return false;
        Element a = course.get(j), b = course.get(k);
        float gap = Math.abs(course.dsWrap(es1[k], es1[j])) - a.halfW() - b.halfW();
        return gap <= CRAB_HOP_GAP && Math.abs(deckY(k) - deckY(j)) <= CRAB_HOP_DY;
    }
    /** Where a hop from deck offset {@code from} to {@code to} (anchor a0) lands: just inside the near edge of the new deck. */
    private float hopLanding(int a0, int from, int to, float fallback) {
        int k = deck(a0, to), j = deck(a0, from);
        if (k < 0 || k >= course.size() || j < 0 || j >= course.size()) return fallback;
        float side = course.dsWrap(es1[j], es1[k]) >= 0f ? 1f : -1f;                   // the side of the new deck that faces the old one
        return course.wrap(es1[k] + side * Math.max(0f, course.get(k).halfW() - CRAB_EDGE));
    }
    /** Build 79: the crab's live height: its own deck's top (as before), the deck it followed you onto, the arc of a hop, or a fall with a crumbled deck. */
    public float crabY(int hi, Element e) {
        if (hi >= crabJump.length) return e.y;
        int off = Math.round(deckOff[hi]), j = deck(e.anchor, off);
        float top = off == 0 || j < 0 || j >= course.size() ? e.y : deckY(j);
        if (crabFall[hi] >= 0f) { float t = crabFall[hi]; return top - 0.5f * T.gravity * t * t; }
        if (crabJump[hi] >= 0f) {
            int fj = deck(e.anchor, Math.round(crabFromDeck[hi]));
            float from = Math.round(crabFromDeck[hi]) == 0 || fj < 0 || fj >= course.size() ? e.y : deckY(fj), k = Math.min(1f, crabJump[hi] / (CRAB_HOP_T / T.react()));
            return from + (top - from) * k + CRAB_HOP_H * 4f * k * (1f - k);
        }
        return top;
    }
    public boolean crabFalling(int hi) { return hi < crabFall.length && crabFall[hi] >= 0f; }
    /** The crab's live arc (hazard index), for the renderer: where it really is while chasing or returning, else its sweep. */
    public float crabAt(int hi, Element e) { return hi < crabPos.length && (crabMode[hi] != 0 || deckOff[hi] != 0f || crabJump[hi] >= 0f) ? crabPos[hi] : e.sAt(time); }
    /** The way the crab faces: the arc it moved this step while off its sweep, else the sweep's own direction. */
    public float crabFacing(int hi, Element e) {
        if (hi < crabVel.length && (crabMode[hi] != 0 || deckOff[hi] != 0f || crabJump[hi] >= 0f)) return crabVel[hi] >= 0f ? 1f : -1f;
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
            if (e.type == Element.Type.ENFORCER) { stepEnforcer(e, hi_, hw); continue; }
            if (e.type == Element.Type.AVALANCHE) { stepAvalanche(e, hi_); continue; }
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

    private static float cosSide(float yaw) { return (float) StrictMath.cos(yaw); }
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
                // Build 79 (owner: it turns flat like a record to aim, with only a slight tilt, never down into its pedestal): it swings round (aimYaw) to
                // face your side and tilts its barrel within AIM_PITCH_LO..AIM_PITCH_HI; its in-lane firing angle follows from the two
                float yawTgt = near ? (tdx >= 0f ? 0f : (float) Math.PI) : aimYaw[hi];
                float pitch = cosSide(aimYaw[hi]) >= 0f ? aimAng[hi] : (float) Math.PI - aimAng[hi];
                pitch = angDiff(pitch, 0f);
                float pTgt = near ? Math.max(Element.AIM_PITCH_LO, Math.min(Element.AIM_PITCH_HI, (float) StrictMath.atan2(tdy, Math.abs(tdx)))) : 0f;
                float ys = (Element.AIM_YAW_TURN * T.react()) * DT, ps = (Element.AIM_TURN * T.react()) * DT;
                aimYaw[hi] += Math.max(-ys, Math.min(ys, yawTgt - aimYaw[hi]));
                pitch = Math.max(Element.AIM_PITCH_LO, Math.min(Element.AIM_PITCH_HI, pitch + Math.max(-ps, Math.min(ps, pTgt - pitch))));
                aimAng[hi] = cosSide(aimYaw[hi]) >= 0f ? pitch : (float) Math.PI - pitch;
            }
            if (pc < Element.AIM_LOCK && c >= Element.AIM_LOCK) aimArmed[hi] = awake && Math.abs((float) StrictMath.cos(aimYaw[hi])) > 0.7f;    // only fires once it has swung round to face along the lane
            if (pc < Element.AIM_FIRE && c >= Element.AIM_FIRE && aimArmed[hi]) { aimFireT[hi] = time; aimArmed[hi] = false; events |= EV_AIM; }
        }
    }

    private boolean aimedBallHits(int hi, Element h, float hw, float lo, float hiY) {
        float[] b = aimedBall(hi, h);
        if (b == null) return false;
        float dx = Math.abs(course.dsWrap(b[0], s)), ddx = Math.max(0f, dx - hw);
        float ddy = b[1] < lo ? lo - b[1] : (b[1] > hiY ? b[1] - hiY : 0f);
        if (ddx * ddx + ddy * ddy >= Element.AIM_R * Element.AIM_R) return false;
        hitFromS = b[0]; hitFromY = b[1]; return true;
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
            if (e.type == Element.Type.AVALANCHE) { if (avalancheHits(hz == null ? k : hz[k], e, hw, lo, hi)) return true; continue; }
            if (e.type == Element.Type.CANNON) {                       // every ball on its way - this cycle's and the earlier launches flying on past the lane, exactly the set that is drawn - hits (Build 63)
                float cs = carryS(e, time), cy = e.y + carryY(e, time), r = Element.SHOT_R;
                float ddy = cy < lo ? lo - cy : (cy > hi ? cy - hi : 0f);
                if (ddy >= r) continue;
                for (int back = 0; back < Element.CANNON_TRAIL; back++) {
                    if (!e.cannonBallAlive(time, back)) continue;
                    float dx = Math.abs(course.dsWrap(e.cannonBallS(time, back) + cs, s)), ddx = Math.max(0f, dx - hw);
                    if (ddx * ddx + ddy * ddy < r * r) { hitFromS = e.cannonBallS(time, back) + cs; hitFromY = cy; return true; }
                }
                continue;
            }
            if (!e.lethalAt(time)) continue;
            float cs = carryS(e, time), cyOff = carryY(e, time);      // ride the platform this hazard sits on (both are 0 unless the platform is a mover)
            int po = hzPose(hz == null ? k : hz[k], e);
            float ePosY = hzv[po + 1];
            float dx = Math.abs(course.dsWrap(hzv[po] + cs, s));
            hitFromS = hzv[po] + cs; hitFromY = ePosY + cyOff;
            float r = e.discR();
            if (r > 0f) {
                float ddx = Math.max(0f, dx - hw), cy = ePosY + cyOff;
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
        float dsP = platformDs(e);
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
        if (jumpBuf > 0 && gone[e]) { doJump(0f); return; }          // DC8 8.3, refined (repair 2026-10-28): a jump pressed on the frame a deck vanishes is a plain jump - none of the vanished deck's velocity - but it is the jump, on that frame (owner: crumbling platforms stay together just long enough to make the jump)
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
        if (off > el.halfW() + T.edgeOverhang || gone[e] || (holesOpen(e) && el.overHole(course.dsWrap(s, es1[e]), HOLE_FOOT))) {
            mode = Mode.AIR; onElem = -1; vy = Math.min(0, platformVy(e)); jumpedUp = false;
            vx += platformVs(e) * 0.5f;
            return;
        }
        // crumble trigger
        if (el.crumbles() && crumbleT[e] < 0) crumbleT[e] = 0;
        if (el.holed() && crumbleT[e] < 0) crumbleT[e] = 0;
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
            if (holesOpen(i) && el.overHole(course.dsWrap(s, es1[i]), HOLE_FOOT)) continue;      // Build 79: through an open hole in a bridge
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
            if (holesOpen(i) && el.overHole(course.dsWrap(s, es1[i]), HOLE_FOOT)) continue;          // (DC8: never onto an open hole)
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
            // the bounce wins (DC4: a coyote left from the step off a deck let a jump tap replace a 6.3 m bounce with a 0.44 m hop)
            jumpedUp = false; mode = Mode.AIR; onElem = -1; lastPad = i; padSquash[i] = 0.25f; coyote = 0; jumpBuf = 0;
            events |= EV_BOUNCE;
            progress(i);
            return;
        }
        vy = 0; mode = Mode.GROUND; onElem = i; jumpedUp = false; coyote = T.coyote + assistForgive;
        events |= EV_LAND;
        if (course.isCheckpoint(el) && i > checkpoint) { checkpoint = i; events |= EV_CHECKPOINT; }
        if ((el.crumbles() || el.holed()) && crumbleT[i] < 0) crumbleT[i] = 0;          // (DC8: a holed bridge starts on landing too, as a crumble does, so a landing jump cannot skip it)
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
            if (el.type == Element.Type.TARZAN) {          // rules 19: the rope where it is now, not where it hangs at rest
                float th = el.tarzanAngle(time), ux = (float) StrictMath.sin(th), uy = -(float) StrictMath.cos(th);
                float px = course.dsWrap(s, es1[i]), py = hand - el.y, along = px * ux + py * uy, perp = Math.abs(px * uy - py * ux);
                if (along >= 0.6f && along <= el.len && perp <= T.ropeGrabRadius) {
                    mode = Mode.TARZAN; onElem = i; tarzanR = along; coyote = 0; jumpBuf = 0; reboundBuf = 0f; events |= EV_GRAB | EV_ROPE;
                    progress(i); stepTarzan(NO_INPUT, 0f);
                    return true;
                }
                continue;
            }
            if (el.type == Element.Type.ROPE) {
                if (Math.abs(course.dsWrap(es1[i], s)) <= T.ropeGrabRadius && hand <= el.y + 0.1f && hand >= el.yBottom()) {
                    mode = Mode.ROPE; onElem = i; vx = vy = 0; s = es1[i]; coyote = 0; jumpBuf = 0; events |= EV_GRAB | EV_ROPE;      // (DC4: no coyote or buffered press carried onto the rope)
                    progress(i);
                    return true;
                }
            } else if (el.type == Element.Type.CABLE) {
                float half = el.w * 0.5f;
                float d = course.dsWrap(s, es1[i]);
                if (Math.abs(d) <= half && hand >= el.y - 0.45f && hand <= el.y + 0.15f && vy < 4f) {
                    mode = Mode.CABLE; onElem = i; vx = vy = 0; y = el.y - T.handHeight; coyote = 0; jumpBuf = 0; events |= EV_GRAB | EV_CABLE;
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

    private static final InputState NO_INPUT = new InputState();
    /**
     * Rules 19 Tarzan swing (owner directive 2026-10-29): the climber hangs where the swinging rope is ({@link #tarzanR} down it from the pivot) and
     * moves with it - position and velocity are the rope's. Up and down climb it slowly; Jump lets go with the rope's own velocity plus a little lift,
     * so when you let go decides where you fly; down at its end drops off.
     */
    private void stepTarzan(InputState in, float dt) {
        Element el = course.get(onElem);
        tarzanR = Math.max(0.8f, Math.min(el.len, tarzanR - in.moveY * T.climbSpeed * 0.5f * dt));
        float th = el.tarzanAngle(time), w = el.tarzanRate(time), sn = (float) StrictMath.sin(th), cs = (float) StrictMath.cos(th);
        s = course.wrap(es1[onElem] + tarzanR * sn); y = el.y - tarzanR * cs - T.handHeight;
        vx = tarzanR * cs * w; vy = tarzanR * sn * w;
        if (Math.abs(in.moveX) > 0.15f) facing = in.moveX > 0 ? 1 : -1; else if (Math.abs(vx) > 0.3f) facing = vx > 0 ? 1 : -1;
        if (jumpBuf > 0) {
            mode = Mode.AIR; onElem = -1; vy += T.jumpVel * TARZAN_RELEASE_UP; jumpBuf = 0; reboundBuf = 0f; lockout = T.grabLockout; jumpedUp = false; coyote = 0; events |= EV_JUMP;
            return;
        }
        if (in.moveY < -0.7f && tarzanR >= el.len - 0.01f) { mode = Mode.AIR; onElem = -1; lockout = T.grabLockout; }
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
        if (in.moveY < -0.7f) { mode = Mode.ROPE; s = es1[i]; y = el.y - T.handHeight; vx = 0; ropeTopT = 0f; lockout = 0f; coyote = 0; return; }
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
        // the deck crumbled or a depth mover left the plane mid-pull: let go, as a hang does (DC8: the pull completed onto it and stood on nothing,
        // or on a platform metres deep)
        if (gone[i]) { mode = Mode.AIR; onElem = -1; lockout = T.grabLockout; vx = vy = 0; return; }
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
