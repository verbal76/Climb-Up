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
        /** Sloped walkway (amp = height gained per metre toward +s, w = horizontal length, y = height at its middle). skin: 0 plain, 1 crumbles, 2 shakes and bounces you, 3 sinks away when stepped on. */
        RAMP,
        /** Platform that slides toward and away from the camera (amp = depth travel). You can only land on it while it is in your plane (|depth| <= Z_REACH); once you stand on it, it carries you. */
        MOVE_Z }

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

    public Element(Type type, float s, float y, float w) { this.type = type; this.s = s; this.y = y; this.w = w; }

    public boolean isHazard() {
        switch (type) { case SAW_H: case SAW_V: case PENDULUM: case CANNON: case SPIKE_TRAP: case SPIKE_BLOCK: case SPIKE_DROP: return true; default: return false; }
    }
    /** Platforms that fall apart soon after being stood on. */
    public boolean crumbles() { return type == Type.CRUMBLE || (type == Type.RAMP && skin == 1) || (skin == 1 && (type == Type.MOVE_H || type == Type.MOVE_V || type == Type.SWING)); }
    /** How thick the platform's block is below its top (matches the renderer: wide blocks and the goal are 1 m, narrow ones 0.5 m). Its sides are edges: see Sim.stepAir. */
    public float slab() { return w >= 5f || type == Type.GOAL ? 1f : 0.5f; }
    public boolean isPlatform() { return type != Type.ROPE && type != Type.CABLE && !isHazard(); }
    /** True for anything that changes with time (planner sweeps its phase). */
    public boolean isMoving() {
        switch (type) { case MOVE_H: case MOVE_V: case MOVE_Z: case SWING: case SAW_H: case SAW_V: case PENDULUM: case CANNON: case SPIKE_TRAP: case SPIKE_DROP: case CRAB: case BEE: return true; default: return false; }
    }

    public static final float CANNON_FLIGHT = 0.7f;      // fraction of the cycle a ball is in the air
    public static final float SAW_R = 0.55f, BALL_R = 0.5f, SHOT_R = 0.3f;

    /** Position in the repeating cycle, 0..1. */
    public float cyc(float t) { float c = t / period + phase / (2f * (float) Math.PI); return c - (float) Math.floor(c); }

    /**
     * Where the ball of the launch {@code back} cycles ago is at time t, if it simply kept flying at the speed it was fired with. For back = 0 during the flight this is exactly {@link #sAt}
     * (the lethal ball); beyond the flight, and for earlier launches, it is the continued straight path, which the renderer draws until the ball is off screen. Never used for hits.
     */
    public float cannonBallS(float t, int back) { return s + dir * len / (CANNON_FLIGHT * period) * (cyc(t) + back) * period; }

    /** Radius of the lethal disc for round hazards (0 = not a disc). */
    public float discR() {
        switch (type) { case SAW_H: case SAW_V: return SAW_R; case PENDULUM: return BALL_R; case CANNON: return SHOT_R; default: return 0f; }
    }

    /** Spike trap: 0 retracted, small = warning tips, 0.6 = fully extended. */
    public float spikeHeight(float t) {
        float c = cyc(t), st = 0.55f, d = amp;
        if (c < st - 0.18f || c >= st + d + 0.04f) return 0f;
        if (c < st) return 0.14f + 0.05f * (float) Math.sin(c * 90f);
        if (c < st + d) return 0.62f;
        return 0.62f * (1f - (c - st - d) / 0.04f);
    }

    /** Spiked stone block on a chain: hovers at y+amp, shakes, slams down to y, rests, rises. Returns the height of its spiked underside. */
    public float dropBottom(float t) {
        float c = cyc(t), hi = y + amp, lo = y;
        if (c < 0.50f) return hi;
        if (c < 0.62f) return hi + 0.05f * (float) Math.sin(c * 220f);              // telegraph: it trembles
        if (c < 0.68f) { float k = (c - 0.62f) / 0.06f; return hi + (lo - hi) * k * k; }
        if (c < 0.80f) return lo;
        return lo + (hi - lo) * (c - 0.80f) / 0.20f;
    }
    public static final float DROP_H = 1.0f;

    // ---- bee: a visit takes BEE_VISIT of the cycle; it enters high from one side, buzzes around (s, y) with amplitude amp (arc) and len (height), dives, and leaves high on the other side
    public static final float BEE_VISIT = 0.58f, BEE_R = 0.5f;
    private float beeU(float t) { return cyc(t) / BEE_VISIT; }
    public boolean beePresent(float t) { return beeU(t) < 1f; }
    private static float smooth(float x) { x = Math.max(0f, Math.min(1f, x)); return x * x * (3f - 2f * x); }
    private float beeEnv(float u) { return smooth(u / 0.22f) * smooth((1f - u) / 0.22f); }
    public float beeS(float t) {
        float u = beeU(t); if (u >= 1f) return s;
        float env = beeEnv(u), fly = (u < 0.5f ? -1f : 1f) * 14f * (1f - env);
        return s + fly + amp * env * (float) Math.sin(2 * Math.PI * 2.3f * u);
    }
    public float beeY(float t) {
        float u = beeU(t); if (u >= 1f) return y + 6f;
        float env = beeEnv(u);
        float dive = u > 0.38f && u < 0.62f ? 0.9f * (float) Math.sin((u - 0.38f) / 0.24f * Math.PI) : 0f;
        return y + len * env * (float) Math.sin(2 * Math.PI * 3.1f * u + 1f) - dive + 4f * (1f - env);
    }

    /** Whether the hazard can hurt at time t. */
    public boolean lethalAt(float t) {
        switch (type) {
            case SPIKE_TRAP: { float c = cyc(t); return c >= 0.55f + 0.02f && c < 0.55f + amp; }
            case CANNON: return cyc(t) < CANNON_FLIGHT;
            case SPIKE_BLOCK: case SPIKE_DROP: case SAW_H: case SAW_V: case PENDULUM: return true;
            default: return false;
        }
    }

    public static final float Z_REACH = 1.0f;
    /** Depth offset of a depth mover at time t (+ = away from the camera). */
    public float zAt(float t) { return type == Type.MOVE_Z ? amp * (float) Math.sin(ang(t)) : 0f; }
    /** Swing platforms: -1..1 position along the arc (+-1 = top of the arc, where the platform is momentarily still). */
    public float swingFrac(float t) { return (float) Math.sin(ang(t)); }

    private float ang(float t) { return (float) (2 * Math.PI * t / period + phase); }

    /** Center arc position at time t (unwrapped, relative to course space). */
    public float sAt(float t) {
        switch (type) {
            case MOVE_H: return s + amp * (float) Math.sin(ang(t));
            case SWING: case PENDULUM: return s + len * (float) Math.sin(amp * Math.sin(ang(t)));
            case SAW_H: case CRAB: return s + amp * (float) Math.sin(ang(t));
            case CANNON: { float c = cyc(t); return c < CANNON_FLIGHT ? s + dir * len * (c / CANNON_FLIGHT) : s; }
            default: return s;
        }
    }

    /** Top surface height (platforms) or anchor/cable height at time t. */
    public float yAt(float t) {
        switch (type) {
            case MOVE_V: case SAW_V: return y + amp * 0.5f * (1f - (float) Math.cos(ang(t)));
            case SWING: case PENDULUM: return y + len - len * (float) Math.cos(amp * Math.sin(ang(t)));
            default: return y;
        }
    }

    public float yBottom() { return y - len; }   // rope bottom
    public float halfW() { return w * 0.5f; }
}
