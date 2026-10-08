package com.hotatticgames.climbup.sim;

/** One authored/generated traversal element. Platforms use (s, y=top, w); rope uses (s, y=top anchor, len); cable uses (s=center, y, w=span). */
public final class Element {
    public enum Type { STATIC, CRUMBLE, MOVE_H, MOVE_V, SWING, PAD, ROPE, CABLE, GOAL,
        /** Environmental hazards (touching one sends the player back to the last checkpoint; no health, no enemies). */
        SAW_H, SAW_V, PENDULUM, CANNON, SPIKE_TRAP, SPIKE_BLOCK }

    public Type type;
    public int zone;
    public float s, y, w;
    public float amp, period = 4f, phase, len;
    public boolean checkpoint;
    public int dir = 1;           // cannon firing direction (+1 / -1)
    public int anchor = -1;       // decoys only: route element this dead end / lure hangs off (-1 = part of the route)

    public Element(Type type, float s, float y, float w) { this.type = type; this.s = s; this.y = y; this.w = w; }

    public boolean isHazard() {
        switch (type) { case SAW_H: case SAW_V: case PENDULUM: case CANNON: case SPIKE_TRAP: case SPIKE_BLOCK: return true; default: return false; }
    }
    public boolean isPlatform() { return type != Type.ROPE && type != Type.CABLE && !isHazard(); }
    /** True for anything that changes with time (planner sweeps its phase). */
    public boolean isMoving() {
        switch (type) { case MOVE_H: case MOVE_V: case SWING: case SAW_H: case SAW_V: case PENDULUM: case CANNON: case SPIKE_TRAP: return true; default: return false; }
    }

    public static final float CANNON_FLIGHT = 0.7f;      // fraction of the cycle a ball is in the air
    public static final float SAW_R = 0.55f, BALL_R = 0.5f, SHOT_R = 0.3f;

    /** Position in the repeating cycle, 0..1. */
    public float cyc(float t) { float c = t / period + phase / (2f * (float) Math.PI); return c - (float) Math.floor(c); }

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

    /** Whether the hazard can hurt at time t. */
    public boolean lethalAt(float t) {
        switch (type) {
            case SPIKE_TRAP: { float c = cyc(t); return c >= 0.55f + 0.02f && c < 0.55f + amp; }
            case CANNON: return cyc(t) < CANNON_FLIGHT;
            case SPIKE_BLOCK: case SAW_H: case SAW_V: case PENDULUM: return true;
            default: return false;
        }
    }

    private float ang(float t) { return (float) (2 * Math.PI * t / period + phase); }

    /** Center arc position at time t (unwrapped, relative to course space). */
    public float sAt(float t) {
        switch (type) {
            case MOVE_H: return s + amp * (float) Math.sin(ang(t));
            case SWING: case PENDULUM: return s + len * (float) Math.sin(amp * Math.sin(ang(t)));
            case SAW_H: return s + amp * (float) Math.sin(ang(t));
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
