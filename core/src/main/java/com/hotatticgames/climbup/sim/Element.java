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
        BLOB }

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
    public boolean isPlatform() { return type != Type.ROPE && type != Type.CABLE && !isHazard(); }
    /** True for anything that changes with time (planner sweeps its phase). */
    /** Build 72 (owner): the spinning ramp (RAMP skin 3, was the sinking ramp) turns flat on its centre like a record: one turn every {@code period} seconds. */
    public boolean spins() { return type == Type.RAMP && skin == 3; }
    /** Build 72 (owner): conveyor belts. A RAMP with skin 4 runs downhill; a STATIC with skin 4 is a wooden plank belt (a long bridge) running to the left. {@code phase} is the belt speed in m/s. */
    public boolean belt() { return skin == 4 && (type == Type.RAMP || type == Type.STATIC); }
    /** The belt's surface speed along +s: both kinds carry you toward -s (down the ramp, to the left on screen). */
    public float beltV() { return belt() ? -phase : 0f; }
    /** The spinning ramp's heading at time t (radians): 0 = uphill toward +s, as a fixed ramp. */
    public float spinAngle(float t) { return phase + 6.2831855f * t / period; }
    /** A ramp's slope along the lane at time t: fixed, or for the spinning ramp the slope of its turning top where the lane crosses it (it flattens side-on and flips). */
    public float rampSlope(float t) { return spins() ? amp * (float) StrictMath.cos(spinAngle(t)) : amp; }

    public boolean isMoving() {
        switch (type) { case MOVE_H: case MOVE_V: case MOVE_Z: case SWING: case SAW_H: case SAW_V: case PENDULUM: case CANNON: case SPIKE_TRAP: case SPIKE_DROP: case CRAB: case BEE: case MORTAR: case AIMED: case SKULL: case BLOB: return true; default: return false; }
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
    public static final float SKULL_R = 0.42f, SKULL_HOVER = 1.75f, SKULL_LOW = 0.55f, SKULL_SPEED = 10f;
    public static final float SKULL_NOTICE = 0.35f, SKULL_WINDUP = 0.55f, SKULL_TURN = 0.5f, SKULL_REST = 1.3f, SKULL_END = 0.7f;
    /** A dash that catches you throws you this hard along its way, and up. */
    public static final float SKULL_LAUNCH_X = 11f, SKULL_LAUNCH_Y = 7.5f;

    // ---- green blob (Build 71): landing half-width, phase lengths, how far up it leaps (off the top of the screen), how long its shadow shows before it lands
    public static final float BLOB_R = 0.6f, BLOB_NOTICE = 0.3f, BLOB_WOBBLE = 0.8f, BLOB_AIR = 1.7f, BLOB_SHADOW = 0.55f, BLOB_REST = 1.2f, BLOB_APEX = 11f, BLOB_END = 0.8f;
    /** A squash bounces you away and up. */
    public static final float BLOB_PUSH_X = 4.5f, BLOB_PUSH_Y = 8f;
    /** Landing on the far end of a tipping bridge you stand on: launch speed = this times the jump speed (sqrt 3 = three times the jump height). */
    public static final float BLOB_LAUNCH = 1.7320508f;
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
        float env = beeEnv(u), fly = (u < 0.5f ? -1f : 1f) * 14f * (1f - env);
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

    public float yBottom() { return y - len; }   // rope bottom
    public float halfW() { return w * 0.5f; }
}
