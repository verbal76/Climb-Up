package com.hotatticgames.climbup.sim;

/** One authored/generated traversal element. Platforms use (s, y=top, w); rope uses (s, y=top anchor, len); cable uses (s=center, y, w=span). */
public final class Element {
    public enum Type { STATIC, CRUMBLE, MOVE_H, MOVE_V, SWING, PAD, ROPE, CABLE, GOAL,
        /** Environmental hazards (touching one sends the player back to the last checkpoint; no health, no enemies). */
        SAW_H, SAW_V, PENDULUM, CANNON, SPIKE_TRAP, SPIKE_BLOCK, SPIKE_DROP,
        /** Platform that launches you at an angle (amp = angle from vertical in radians, + toward +s). */
        SPRING,
        /** Features (kept in Course.hazards, never lethal): a coloured key to pick up, and the castle gate that needs the same colour. */
        KEY, GATE,
        /** Crab: patrols a platform and shoves you (never hurts). A floating spiked club lets you knock crabs off. */
        CRAB, CLUB,
        /** Bee: flies in, hovers and dives around a spot to bump you (never hurts), then flies away until its next visit. */
        BEE,
        /** Wooden bridge balanced on a pivot in its middle: tips toward whoever stands on it, so keep running. (tilt state lives in the Sim) */
        SEESAW,
        /** Sloped walkway (amp = height gained per metre toward +s, w = horizontal length, y = height at its middle). skin: 0 plain, 1 crumbles, 2 shakes and bounces you, 3 spins like a record, 4 a conveyor belt running downhill. */
        RAMP,
        /** Platform that slides toward and away from the camera (amp = depth travel). You can only land on it while it is in your plane (|depth| <= Z_REACH); once you stand on it, it carries you. */
        MOVE_Z,
        /** Mortar: a cannon in the ground BELOW the path that points straight up. Its ball rises from the muzzle (y) by amp, falls back into the barrel, waits to reload, and fires again every period. The ball is a lethal disc (a hit only knocks you away). */
        MORTAR,
        /** Aimed cannon (late game): a turret at (s, y) that tracks the player, locks and flashes a warning, then fires a ball along exactly the locked barrel direction. The aim lives in the Sim (per-hazard state); the lethal ball is evaluated there. */
        AIMED,
        /** Skull (Build 71, owner): hovers and bobs above one end of a long fixed platform or bridge; when you are on its deck it drops low and dashes straight along it at you, leaving a misty trail. Jump over it; a hit launches you hard the way it was going (never hurts). Then it turns, rises, bobs, and comes again. w = its deck's width. */
        SKULL,
        /** Green blob (Build 71, owner): sits on a long fixed platform or a tipping bridge. When you are on its deck it wobbles, leaps high off the screen and drops on you: only its shadow shows where, at the last moment. Squashed flat, you are bounced away (never hurts). Landing on the far end of a tipping bridge you stand on launches you three times your jump height. w = its deck's width. */
        BLOB,
        /**
         * Enforcer robot (PUNISHER only, owner 2026-10-27): a pursuit enemy that waits at the far end of its first deck ({@code anchor}), chases you across
         * {@code len} consecutive route decks (3-5), kicks you off your footing, and - orange and red - jumps to swat you out of the air. After its burst it
         * runs off up the tower and leaps away. skin: tier 0 yellow, 1 orange, 2 red. It obeys gravity and the decks; it can be knocked or fall off.
         */
        ENFORCER,
        /**
         * Spike-block avalanche (owner 2026-10-27; PUNISHER, and NORMAL castles 9-10): when you land on its deck ({@code anchor}), and the cooldown since
         * the last avalanche ({@code period} s of simulation time) has passed, a warning shows where {@code len} spiked blocks (2 NORMAL, 4-5 PUNISHER) will
         * come down from above the screen onto the decks ahead; they fall, bounce, ricochet off the decks and each other, and tumble back down the tower
         * past you. {@code phase} seeds each block's start (position, delay, speed, spin). Single use: a spot that fires, or is reached during the
         * cooldown, never fires again. A block that hits you knocks you away like any hazard.
         */
        AVALANCHE,
        /**
         * Rules 19 (owner directive 2026-10-29): a TARZAN SWING - a rope hanging from a pivot at (s, y), {@code len} long, swinging from side to side
         * as a pendulum, {@code amp} radians either way of straight down, once every {@code period} seconds ({@code phase}). Its end is only in reach
         * while it swings toward you; the climber rides it and lets go with Jump, keeping the rope's motion. (Appended last: types are stored by number.)
         */
        TARZAN }

    public Type type;
    public int zone;
    public float s, y, w;
    public float amp, period = 4f, phase, len;
    public boolean checkpoint;
    public int dir = 1;           // cannon firing direction (+1 / -1)
    public int skin = 0;          // platform look: 0 = stone/grass block, 1 = wooden bridge
    public int color = 0;         // key / gate colour index (see KEY_COLORS)
    public static final int KEY_COUNT = 4;   // red, blue, green, gold
    public int anchor = -1;       // decoys only: route element this dead end / lure hangs off (-1 = part of the route)

    /** A wet platform (rain cloud over it): a plain STATIC platform whose {@code color} holds this mark (color is otherwise only used by keys and gates, and is stored by the slice codec already). */
    public static final int WET = 7;
    public boolean wet() { return type == Type.STATIC && color == WET; }

    public Element(Type type, float s, float y, float w) { this.type = type; this.s = s; this.y = y; this.w = w; }

    public boolean isHazard() {
        switch (type) { case SAW_H: case SAW_V: case PENDULUM: case CANNON: case SPIKE_TRAP: case SPIKE_BLOCK: case SPIKE_DROP: case MORTAR: case AIMED: return true; default: return false; }
    }
    /**
     * Hazards that sit on top of their anchor platform (rather than hanging in a gap, standing beside the path, or flying): when that platform moves, the hazard rides it (see {@link Sim#hazCarryS}).
     * Mirrors the "sits on" set in CourseGenerator.ownerOf. A spike pad on a moving block therefore moves with the block instead of staying put in world space.
     */
    public boolean ridesAnchor() {
        switch (type) { case SPIKE_TRAP: case SPIKE_BLOCK: case SPIKE_DROP: case SAW_H: case CRAB: return true; default: return false; }
    }
    /**
     * A cannon's firing aim in the play plane, in radians (0 = toward +s, measured counter-clockwise), for the renderer to point the barrel.
     * A plain CANNON fires along this fixed line; an AIMED cannon turns to track the player, so its live aim is kept per-hazard in {@link Sim#aimAng} and supersedes this idle value; a MORTAR points straight up.
     */
    public float fireAim() {
        switch (type) {
            case CANNON: return dir >= 0 ? 0f : (float) Math.PI;
            case AIMED: case MORTAR: return (float) Math.PI * 0.5f;
            default: return 0f;
        }
    }
    /** Platforms that fall apart soon after being stood on. */
    public boolean crumbles() { return type == Type.CRUMBLE || (type == Type.RAMP && skin == 1) || (skin == 1 && (type == Type.MOVE_H || type == Type.MOVE_V || type == Type.SWING)); }
    /** How thick the platform's block is below its top (matches the renderer: wide blocks and the goal are 1 m, narrow ones 0.5 m). Its sides are edges: see Sim.stepAir. */
    public float slab() { return w >= 5f || type == Type.GOAL ? 1f : 0.5f; }
    public boolean isPlatform() { return type != Type.ROPE && type != Type.CABLE && type != Type.TARZAN && !isHazard(); }
    /** A Tarzan swing's angle from straight down at time t (radians; positive swings toward +s) and how fast it changes (rad/s). */
    public float tarzanAngle(float t) { return amp * (float) StrictMath.sin(ang(t)); }
    public float tarzanRate(float t) { return amp * (float) (2 * Math.PI / period) * (float) StrictMath.cos(ang(t)); }
    /** True for anything that changes with time (planner sweeps its phase). */
    /** Build 72 (owner): the spinning ramp (RAMP skin 3, was the sinking ramp) turns flat on its centre like a record: one turn every {@code period} seconds. */
    public boolean spins() { return type == Type.RAMP && skin == 3; }
    /**
     * Rules 19 TURBO RAMP (owner directive 2026-10-29): RAMP skin 5 with a booster at its top. Running off the top fires the climber out at
     * {@link #turboAngle} above the horizontal (radians, toward the ramp's uphill side) at {@link #turboSpeed} m/s; see Sim.turboT.
     */
    public boolean turbo() { return type == Type.RAMP && skin == 5; }
    public float turboAngle() { return period; }
    public float turboSpeed() { return phase; }
    /** Build 79 (owner: some bridges drop plank sections when you land, leaving holes to jump): a fixed wooden bridge (STATIC skin 1) whose sections set in {@code color}
     *  (a bit mask; the deck is cut into holeSections() equal sections from its -s end, each about HOLE_SEC long) fall out HOLE_DELAY after you land on it. */
    public static final float HOLE_SEC = 1.3f, HOLE_DELAY = 0.45f;
    /** Rules 19 (owner decision 2026-10-29): a bridge's planks that fall out are gone for the rest of the climb; its crumble clock rests here once they have fallen (Sim, Tower, saves). */
    public static final float HOLE_DONE = HOLE_DELAY + 2f;
    public boolean holed() { return type == Type.STATIC && skin == 1 && color != 0; }
    public int holeSections() { return Math.max(1, (int) (w / HOLE_SEC)); }
    /** True when x (from the deck's centre, along the lane) is over a hole section, at least {@code margin} in from its edges (a negative margin reaches past them). */
    public boolean overHole(float x, float margin) {
        if (!holed()) return false;
        int n = holeSections(); float sec = w / n;
        for (int k = 0; k < n; k++) {
            if ((color & (1 << k)) == 0) continue;
            float lo = -w * 0.5f + k * sec;
            if (x > lo + margin && x < lo + sec - margin) return true;
        }
        return false;
    }
    /** Build 72 (owner): conveyor belts. A RAMP with skin 4 runs downhill; a STATIC with skin 4 is a wooden plank belt (a long bridge) running to the left. {@code phase} is the belt speed in m/s. */
    public boolean belt() { return skin == 4 && (type == Type.RAMP || type == Type.STATIC); }
    /** The belt's surface speed along +s: both kinds carry you toward -s (down the ramp, to the left on screen). */
    public float beltV() { return belt() ? -phase : 0f; }
    /** Build 77 (owner): the balance conveyor - a tipping bridge (SEESAW skin 4) whose plank belt runs toward whichever end is down, {@code phase} m/s at full tilt.
     *  Its speed follows the tilt (live sim state), so it is not in {@link #beltV()}: see Sim#surfaceCarry. */
    public boolean tiltBelt() { return type == Type.SEESAW && skin == 4; }
    /** The spinning ramp's heading at time t (radians): 0 = uphill toward +s, as a fixed ramp. */
    public float spinAngle(float t) { return phase + 6.2831855f * t / period; }
    /** A ramp's slope along the lane at time t: fixed, or for the spinning ramp the slope of its turning top where the lane crosses it (it flattens side-on and flips). */
    public float rampSlope(float t) { return spins() ? amp * (float) StrictMath.cos(spinAngle(t)) : amp; }

    public boolean isMoving() {
        switch (type) { case MOVE_H: case MOVE_V: case MOVE_Z: case SWING: case SAW_H: case SAW_V: case PENDULUM: case CANNON: case SPIKE_TRAP: case SPIKE_DROP: case CRAB: case BEE: case MORTAR: case AIMED: case SKULL: case BLOB: case ENFORCER: case AVALANCHE: case TARZAN: return true; default: return false; }
    }

    public static final float CANNON_FLIGHT = 0.7f;      // fraction of the cycle the cycle's ball takes to cross the lane (its launch speed)
    /**
     * Side cannon balls (Build 63, owner: "the cannon balls have no collision" - the balls that flew on past the lane used to be drawn only): a ball
     * flies on past the end of its lane for another CANNON_BEYOND units and then fizzles out, and every ball in the air hits. CANNON_TRAIL is how
     * many launches can be in the air at once (the renderer draws exactly the alive ones, the simulation tests exactly the same set).
     */
    public static final int CANNON_TRAIL = 4;
    public static final float CANNON_BEYOND = 14f;
    /** How far the ball of the launch {@code back} cycles ago has flown from the muzzle at time t. */
    public float cannonFlown(float t, int back) { return len / CANNON_FLIGHT * (cyc(t) + back); }
    /** Whether that ball is still in the air: within CANNON_BEYOND past the end of the lane. */
    public boolean cannonBallAlive(float t, int back) { return cannonFlown(t, back) <= len + CANNON_BEYOND; }
    /** The arc every ball of this cannon can reach: its lane plus CANNON_BEYOND (the simulation keeps a cannon live while the player is within it). */
    public float cannonReach() { return len + CANNON_BEYOND; }
    /** Lethal disc radii, sized to the drawn balls: the spiky ball model is 0.60 pack units to its body and 0.83 to its spike tips, drawn at 0.55 (side cannon, mortar) and 0.50 (turret). */
    public static final float SAW_R = 0.55f, BALL_R = 0.5f, SHOT_R = 0.42f;

    /** Position in the repeating cycle, 0..1. */
    public float cyc(float t) { float c = t / period + phase / (2f * (float) Math.PI); return c - (float) Math.floor(c); }

    /**
     * Where the ball of the launch {@code back} cycles ago is at time t, flying on at the speed it was fired with. For back = 0 during the flight this is exactly {@link #sAt}
     * (the cycle's ball crossing the lane); beyond the flight, and for earlier launches, it is the continued straight path. The renderer draws the {@link #CANNON_TRAIL} balls
     * (until off screen) and {@link Sim} tests every one of them for a hit, so what you see is what hits you.
     */
    public float cannonBallS(float t, int back) { return s + dir * len / (CANNON_FLIGHT * period) * (cyc(t) + back) * period; }

    public static final float MORTAR_FLIGHT = 0.82f, MORTAR_R = 0.42f;
    /** Aimed cannon timing, as fractions of its period: tracks the player until LOCK, holds (warning) until FIRE, fires; the ball flies AIM_LIFE seconds at AIM_SPEED; it only wakes when the player is within AIM_RANGE. */
    public static final float AIM_LOCK = 0.55f, AIM_FIRE = 0.70f, AIM_SPEED = 9f, AIM_LIFE = 1.6f, AIM_R = 0.40f, AIM_RANGE = 15f, AIM_TURN = 2.4f;
    /** Build 79 (owner): the turret turns flat like a record (AIM_YAW_TURN rad/s) and its barrel only tilts between AIM_PITCH_LO and AIM_PITCH_HI (radians, 0 level). */
    public static final float AIM_YAW_TURN = 4.0f, AIM_PITCH_LO = -0.35f, AIM_PITCH_HI = 0.5f;
    /** The ball leaves the muzzle TIP, not the barrel's centre: the cannon model's barrel runs 2.26 units from its axle to its mouth along the model's +X (PK = 0.37 scale), so the mouth is 0.84 world units ahead of (s,y). Without this the ball erupts from the middle of the cannon. */
    public static final float AIM_MUZZLE = 0.84f;
    /** How far an aimed cannon leads a moving player (fraction of the full "where will they be when the ball lands" prediction): it tries to put the shot in front of you, but partially, so a committed move still slips past. A plain CANNON stays a fixed straight-line shot for the "measure the gap" variety. */
    public static final float AIM_LEAD = 0.5f;
    /** Mortar ball height at time t: a parabola from the muzzle (y) to y + amp and back during the first MORTAR_FLIGHT of the cycle, then it sits in the barrel while the mortar reloads. */
    public float mortarBallY(float t) {
        float c = cyc(t);
        if (c >= MORTAR_FLIGHT) return y;
        float u = c / MORTAR_FLIGHT;
        return y + amp * 4f * u * (1f - u);
    }
    /** 0..1 over the reload pause (the warning glow), 0 while the ball is in flight. */
    public float mortarCharge(float t) { float c = cyc(t); return c < MORTAR_FLIGHT ? 0f : (c - MORTAR_FLIGHT) / (1f - MORTAR_FLIGHT); }

    /** Radius of the lethal disc for round hazards (0 = not a disc). */
    public float discR() {
        switch (type) { case SAW_H: case SAW_V: return SAW_R; case PENDULUM: return BALL_R; case CANNON: return SHOT_R; case MORTAR: return MORTAR_R; default: return 0f; }
    }

    /** Spike trap: 0 retracted, small = warning tips, 0.6 = fully extended. */
    public float spikeHeight(float t) {
        float c = cyc(t), st = 0.55f, d = amp;
        if (c < st - 0.18f || c >= st + d + 0.04f) return 0f;
        if (c < st) return 0.14f + 0.05f * (float) StrictMath.sin(c * 90f);
        if (c < st + d) return 0.62f;
        return 0.62f * (1f - (c - st - d) / 0.04f);
    }

    /** Spiked stone block on a chain: pauses briefly at y+amp, shakes, slams down to y, rests, then rises slowly back. Returns the height of its spiked underside. */
    public float dropBottom(float t) {
        float c = cyc(t), hi = y + amp, lo = y;
        if (c < 0.15f) return hi;                                                   // brief pause at the top, then it strikes (was a long 0.50 hover that made the first drop feel too slow to be a threat)
        if (c < 0.27f) return hi + 0.05f * (float) StrictMath.sin(c * 220f);              // telegraph: it trembles
        if (c < 0.33f) { float k = (c - 0.27f) / 0.06f; return hi + (lo - hi) * k * k; }   // slam (same quickness as before)
        if (c < 0.45f) return lo;                                                   // rest at the bottom (same)
        return lo + (hi - lo) * (c - 0.45f) / 0.55f;                                // slow rise back up fills the rest of the cycle, so the slam-to-slam period is unchanged
    }
    public static final float DROP_H = 1.0f;
    /** The slab is a 0.5 m stone block whose underside carries the spikes: only the lower SPIKE_H of the DROP_H box hurts; the top is a safe surface (see Sim.landOnSlab). */
    public static final float DROP_SPIKE_H = 0.5f;
    /** Height of the slab's top face at time t. */
    public float dropTop(float t) { return dropBottom(t) + DROP_H; }

    // ---- bee: a visit takes BEE_VISIT of the cycle; it enters high from one side, buzzes around (s, y) with amplitude amp (arc) and len (height), dives, and leaves high on the other side
    public static final float BEE_VISIT = 0.58f, BEE_R = 0.5f;
    /** Build 71 (owner: the bee "flies by and leaves, it should do more"): while it visits it homes in on you, its buzzing centre drifting toward you at BEE_DRIFT m/s up to BEE_REACH from its post, and its dive swoops at your chest; you are noticed within BEE_SEEK of its post. It still leaves after its visit. */
    public static final float BEE_REACH = 2.4f, BEE_DRIFT = 2.2f, BEE_SEEK = 7f, BEE_SWOOP = 0.85f;
    /** Bee bump (was 6.5 / 3: barely a nudge). */
    public static final float BEE_PUSH_X = 8f, BEE_PUSH_Y = 5f;
    /** 0..1 strength of the dive at time t (0 outside the dive), shared by the simulation's swoop at the player and the drawn bee. */
    public float beeDive(float t) { float u = beeU(t); return u > 0.38f && u < 0.62f ? (float) StrictMath.sin((u - 0.38f) / 0.24f * Math.PI) : 0f; }

    // ---- skull (Build 71): body radius, heights of its centre above the deck while hovering and while dashing, dash speed, and the phase lengths
    public static final float SKULL_R = 0.42f, SKULL_HOVER = 1.75f, SKULL_LOW = 0.55f, SKULL_SPEED = 16f;      // Build 77 (owner: "come at you faster"): 10 -> 13 m/s; Build 79 (owner: "attack faster"): 16
    public static final float SKULL_NOTICE = 0.35f, SKULL_WINDUP = 0.8f, SKULL_TURN = 0.5f, SKULL_REST = 0.6f, SKULL_END = 0.7f;      // Build 79: wind-up 1.2 -> 0.8 s, rest 1.3 -> 0.6 s
    /** Build 79 (owner: it goes to the platforms before and after its own): it flies between decks at SKULL_FLY m/s, and goes home after SKULL_HOME seconds without you on any of the three. */
    public static final float SKULL_FLY = 8f, SKULL_HOME = 1.5f;
    /** Build 79: a skull or blob only roams to a neighbouring deck within this height of the one it is on. */
    public static final float ROAM_DY = 3.0f;
    /** A dash that catches you throws you this hard along its way, and up. */
    public static final float SKULL_LAUNCH_X = 14f, SKULL_LAUNCH_Y = 9.5f;      // Build 77 (owner: "mush you farther"): +30% (was 11 / 7.5)
    /** Build 77 (owner: when it turns to face you it loops and swoops before it dashes): the wind-up is a vertical loop-the-loop of this radius over the
     *  first SKULL_LOOP_T seconds, then a swoop down to dash height, drawing back a little first, for the rest of SKULL_WINDUP. */
    public static final float SKULL_LOOP_R = 0.6f, SKULL_LOOP_T = 0.5f, SKULL_SWOOP_BACK = 0.5f;

    // ---- green blob (Build 71): landing half-width, phase lengths, how far up it leaps (off the top of the screen), how long its shadow shows before it lands
    public static final float BLOB_R = 0.6f, BLOB_NOTICE = 0.3f, BLOB_AIR = 1.7f, BLOB_SHADOW = 0.55f, BLOB_REST = 1.0f, BLOB_APEX = 11f, BLOB_END = 0.8f;
    /** Build 79 (owner: no wobble - it jumps, rests a second, jumps again; it reaches the platforms before and after its own): it goes home after BLOB_HOME seconds without you on any of the three. */
    public static final float BLOB_HOME = 1.5f;
    /** A squash knocks you away and up (Build 79, owner: "it hits me and I just stand there": 4.5 / 8 -> 9 / 7, like a crab's shove). */
    public static final float BLOB_PUSH_X = 9f, BLOB_PUSH_Y = 7f;
    /** Landing on the far end of a tipping bridge you stand on: launch speed = this times the jump speed (sqrt 3 = three times the jump height). */
    public static final float BLOB_LAUNCH = 1.7320508f;
    // ---- Enforcer robot (PUNISHER, owner 2026-10-27). Per tier {yellow, orange, red}; times are scaled by Tuning.react() like every creature's.
    /** Body: half-width along the lane and height (the robot model is drawn to this size). */
    public static final float ENF_HALF = 0.4f, ENF_H = 1.6f;
    /** Seconds you must be in its range before it starts; its run speed (m/s); kick wind-up (the telegraph: it rears back), active and recovery times (s). */
    public static final float[] ENF_NOTICE = {0.5f, 0.4f, 0.3f}, ENF_RUN = {3.6f, 4.4f, 5.2f}, ENF_WINDUP = {0.45f, 0.36f, 0.28f}, ENF_RECOVER = {0.75f, 0.55f, 0.4f};
    public static final float ENF_KICK_ACT = 0.15f;
    /** How far in front of its body a kick reaches (m), and how hard it throws you (m/s along / up), like a crab's shove but harder. */
    public static final float[] ENF_KICK_REACH = {0.85f, 0.95f, 1.05f};
    public static final float ENF_KICK_X = 9.5f, ENF_KICK_Y = 6.5f;
    /** Air swat (orange and red): it jumps when you are airborne within this arc distance (0 = never), at most once per cooldown; its jump reaches about 2.3 m. */
    public static final float[] ENF_SWAT_RANGE = {0f, 2.4f, 3.0f}, ENF_SWAT_CD = {99f, 1.6f, 1.1f};
    public static final float ENF_SWAT_VY = 12.5f, ENF_SWAT_X = 8f, ENF_SWAT_Y = -2f, ENF_REACH_UP = 0.5f;
    /** How long a burst lasts at most (s), then it leaves; a hop between decks (s) and the largest gap / height change it hops. */
    public static final float[] ENF_PURSUE = {6.5f, 7.5f, 8.5f};
    public static final float ENF_HOP_T = 0.5f, ENF_HOP_GAP = 3.6f, ENF_HOP_DY = 2.2f, ENF_HOP_PEAK = 1.0f;
    /**
     * Jump judgment (owner 2026-10-27, "no lemmings, no trained assassins"). Each robot draws its own fixed traits from its seed inside its tier's
     * [LO, HI]: how far ahead it reads your flight (0 = where you are now, 1 = where you will be at its peak), the landing margin it still accepts (m from
     * the deck's end; smaller = bolder), its reaction before it swats (s, scaled by Tuning.react()), how hard it can steer in the air (m/s^2), and its
     * temper (a 0..1 draw that scales its chase time and swat cooldown by 0.85..1.15). A landing at least ENF_SAFE in from an end is reasonable (A),
     * one inside the margin it accepts is risky (B), anything else is never attempted (C). The planned landing is any flat fixed deck of its encounter.
     */
    public static final float[] ENF_PREDICT_LO = {0f, 0.25f, 0.55f}, ENF_PREDICT_HI = {0f, 0.55f, 0.9f};
    public static final float[] ENF_MARGIN_LO = {0.6f, 0.35f, 0.1f}, ENF_MARGIN_HI = {0.6f, 0.5f, 0.3f};
    public static final float[] ENF_REACT_LO = {0.3f, 0.12f, 0.06f}, ENF_REACT_HI = {0.4f, 0.22f, 0.14f};
    public static final float[] ENF_STEER_LO = {0f, 2.5f, 3.5f}, ENF_STEER_HI = {0f, 4f, 6f};
    public static final float ENF_SAFE = 0.5f;
    // ---- spike-block avalanche (one physics for every mode: only the count and how often differ)
    /** Most blocks in one avalanche; a block's collision radius (the cube_spikes body, m). */
    public static final int AVL_MAX = 12;          // (5 until rules 14, 7 until rules 18; rules 18: NORMAL 2, PUNISHER 8-10, NOT GONNA HAPPEN 9-12)
    public static final float AVL_R = 0.45f;
    /** NOT GONNA HAPPEN blocks (rules 14): radius AVL_CR0 + AVL_CR1 x seed (0.32-0.62 m); a deck impact faster than AVL_RICOCHET m/s counts as a hard rebound. */
    public static final float AVL_CR0 = 0.32f, AVL_CR1 = 0.30f, AVL_RICOCHET = 5f;
    /** NORMAL: the gap between releases (s); every mode: how far above its target deck a block starts (m: above the screen). (Rules 12-17 also waited
     *  1.4 s of warning before the first block; rules 18 has none.) */
    public static final float AVL_STAGGER = 0.35f, AVL_DROP = 11f;
    /** Block physics: gravity (m/s^2), bounce restitution off decks and off each other, tangential friction kept per contact, deck slab thickness (m). */
    public static final float AVL_G = 24f, AVL_E = 0.42f, AVL_EB = 0.55f, AVL_FRICTION = 0.82f, AVL_SLAB = 0.6f;
    /** A block is gone when it has fallen this far below the trigger deck (m), after this long (s), or after resting this long (s). */
    public static final float AVL_FLOOR = 16f, AVL_LIFE = 12f, AVL_REST = 0.6f;
    /**
     * Rules 18 (owner Build 83 avalanche overhaul, 2026-10-29): no warning. The first block breaks loose AVL_LEAD0 s after the trigger and the rest follow
     * AVL_GAP0-AVL_GAP1 s apart (each gap seeded). The trigger leads a moving climber: it fires once the time they need to reach the trigger deck's
     * near edge (their real speed toward it) is no more than the blocks' fall time plus AVL_LEAD_PAD, up to AVL_LEAD_MAX m ahead of that edge, and only
     * from AVL_LEAD_BELOW m below to AVL_LEAD_ABOVE m above the deck. Standing still, it fires on the deck itself as before. NORMAL keeps its gentler physics.
     */
    public static final float AVL_LEAD0 = 0.05f, AVL_GAP0 = 0.07f, AVL_GAP1 = 0.16f, AVL_LEAD_PAD = 0.25f, AVL_LEAD_MAX = 12f, AVL_LEAD_BELOW = 5f, AVL_LEAD_ABOVE = 3f;
    /**
     * Rules 18 PUNISHER and NOT GONNA HAPPEN blocks are rigid cubes (half side = collision radius): a contact acts at the cube's corner or face that
     * touches, so a corner landing turns the fall into spin and a spinning block throws itself off line (impulses with angular momentum and Coulomb
     * friction). Restitution AVL_HE0 + AVL_HE1 x seed off decks and walls, friction coefficient AVL_HMU0 + AVL_HMU1 x seed, block on block AVL_HEB with
     * friction AVL_HMUB; contacts slower than AVL_HREST m/s do not bounce (blocks can come to rest). Speeds are capped at AVL_VMAX m/s and AVL_WMAX deg/s;
     * AVL_HSUB sub-steps per simulation step (no tunnelling). AVL_AIM: share of the blocks aimed where the climber will be when they come down, landing
     * AVL_AIM0-AVL_AIM1 m ahead of them along the route (so a climber who keeps running meets them, one who checks their speed can dodge).
     */
    public static final float AVL_HE0 = 0.58f, AVL_HE1 = 0.22f, AVL_HMU0 = 0.45f, AVL_HMU1 = 0.30f, AVL_HEB = 0.82f, AVL_HMUB = 0.35f, AVL_HREST = 0.9f;
    public static final float AVL_VMAX = 22f, AVL_WMAX = 1500f, AVL_AIM = 0.35f, AVL_AIM0 = 0.6f, AVL_AIM1 = 2.6f;
    public static final int AVL_HSUB = 4;
    /** Rules 18 settling (avalanche frequency investigation): a rigid block touching a deck slower than AVL_SETTLE_V m/s and AVL_SETTLE_W deg/s loses
     *  (1 - AVL_SETTLE_K) of its speed and spin each sub-step, so it comes to rest (and is cleaned up after AVL_REST) instead of rocking on its corners
     *  for the rest of its life. Hard impacts, ricochets and tumbles are faster than this and keep their full physics. */
    public static final float AVL_SETTLE_V = 1.2f, AVL_SETTLE_W = 200f, AVL_SETTLE_K = 0.85f;
    /** Leaving: it runs up the tower at ENF_EXIT_RUN and leaps away (m/s); once ENF_GONE_Y above its deck (or falling for ENF_FALL_T s) it is gone. */
    public static final float ENF_EXIT_RUN = 7f, ENF_EXIT_VX = 6f, ENF_EXIT_VY = 13f, ENF_GONE_Y = 10f, ENF_FALL_T = 2f;

    /** Height of the leaping blob above its deck, k = 0..1 through the leap: up fast, a hang off screen, and down. */
    public static float blobLeapY(float k) {
        if (k < 0.45f) { float u = k / 0.45f; return BLOB_APEX * (1f - (1f - u) * (1f - u)); }
        float u = (k - 0.45f) / 0.55f; return BLOB_APEX * (1f - u * u);
    }
    private float beeU(float t) { return cyc(t) / BEE_VISIT; }
    public boolean beePresent(float t) { return beeU(t) < 1f; }
    private static float smooth(float x) { x = Math.max(0f, Math.min(1f, x)); return x * x * (3f - 2f * x); }
    private float beeEnv(float u) { return smooth(u / 0.22f) * smooth((1f - u) / 0.22f); }
    public float beeS(float t) {
        float u = beeU(t); if (u >= 1f) return s;
        float env = beeEnv(u), fly = (u < 0.5f ? -1f : 1f) * (dir < 0 ? -1f : 1f) * 14f * (1f - env);      // Build 79: dir -1 comes in from the other side (a bee pair never shares a side)
        return s + fly + amp * env * (float) StrictMath.sin(2 * Math.PI * 2.3f * u);
    }
    public float beeY(float t) {
        float u = beeU(t); if (u >= 1f) return y + 6f;
        float env = beeEnv(u);
        float dive = u > 0.38f && u < 0.62f ? 0.9f * (float) StrictMath.sin((u - 0.38f) / 0.24f * Math.PI) : 0f;
        return y + len * env * (float) StrictMath.sin(2 * Math.PI * 3.1f * u + 1f) - dive + 4f * (1f - env);
    }

    /** Whether the hazard can hurt at time t. */
    public boolean lethalAt(float t) {
        switch (type) {
            case SPIKE_TRAP: { float c = cyc(t); return c >= 0.55f + 0.02f && c < 0.55f + amp; }
            case CANNON: return cyc(t) < CANNON_FLIGHT;
            case MORTAR: return mortarBallY(t) > y + 0.35f;          // inside the barrel (or just leaving it) the ball hurts nobody
            case SPIKE_BLOCK: case SPIKE_DROP: case SAW_H: case SAW_V: case PENDULUM: return true;
            default: return false;
        }
    }

    public static final float Z_REACH = 1.0f;
    /** Depth offset of a depth mover at time t (+ = away from the camera). */
    public float zAt(float t) { return type == Type.MOVE_Z ? amp * (float) StrictMath.sin(ang(t)) : 0f; }
    /** Swing platforms: -1..1 position along the arc (+-1 = top of the arc, where the platform is momentarily still). */
    public float swingFrac(float t) { return (float) StrictMath.sin(ang(t)); }

    private float ang(float t) { return (float) (2 * Math.PI * t / period + phase); }

    /** Center arc position at time t (unwrapped, relative to course space). */
    public float sAt(float t) { return s + sOffsetAt(t); }

    /**
     * How far the element is from its rest position s at time t (0 for anything that does not move along the arc). Build 76: the simulation takes a mover's
     * displacement per step from this, not from the difference of two {@link #sAt} values, whose rounding depends on how large s is in the current frame
     * (the streaming tower's local origin or the whole tower's), and a platform's speed is that difference divided by one step.
     */
    public float sOffsetAt(float t) {
        switch (type) {
            case MOVE_H: return amp * (float) StrictMath.sin(ang(t));
            case SWING: case PENDULUM: return len * (float) StrictMath.sin(amp * StrictMath.sin(ang(t)));
            case SAW_H: case CRAB: return amp * (float) StrictMath.sin(ang(t));
            case CANNON: { float c = cyc(t); return c < CANNON_FLIGHT ? dir * len * (c / CANNON_FLIGHT) : 0f; }
            default: return 0f;
        }
    }

    /** Top surface height (platforms) or anchor/cable height at time t. */
    public float yAt(float t) {
        switch (type) {
            case MOVE_V: case SAW_V: return y + amp * 0.5f * (1f - (float) StrictMath.cos(ang(t)));
            case SWING: case PENDULUM: return y + len - len * (float) StrictMath.cos(amp * StrictMath.sin(ang(t)));
            case MORTAR: return mortarBallY(t);
            default: return y;
        }
    }

    /**
     * How far the element is above its rest height y at time t (0 for anything that does not move up and down). Build 79: as {@link #sOffsetAt} for the
     * arc, the simulation takes a mover's rise per step from this rather than from the difference of two {@link #yAt} values, whose rounding depends on
     * how high y is in the current frame (the streaming tower's local origin or the whole tower's); divided by one step that rounding became a platform
     * speed that differed by millimetres per second between the two.
     */
    public float yOffsetAt(float t) {
        switch (type) {
            case MOVE_V: case SAW_V: return amp * 0.5f * (1f - (float) StrictMath.cos(ang(t)));
            case SWING: case PENDULUM: return len - len * (float) StrictMath.cos(amp * StrictMath.sin(ang(t)));
            default: return 0f;
        }
    }

    public float yBottom() { return y - len; }   // rope bottom
    public float halfW() { return w * 0.5f; }
}
