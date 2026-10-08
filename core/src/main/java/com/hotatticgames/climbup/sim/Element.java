package com.hotatticgames.climbup.sim;

/** One authored/generated traversal element. Platforms use (s, y=top, w); rope uses (s, y=top anchor, len); cable uses (s=center, y, w=span). */
public final class Element {
    public enum Type { STATIC, CRUMBLE, MOVE_H, MOVE_V, SWING, PAD, ROPE, CABLE, GOAL }

    public Type type;
    public int zone;
    public float s, y, w;
    public float amp, period = 4f, phase, len;
    public boolean checkpoint;

    public Element(Type type, float s, float y, float w) { this.type = type; this.s = s; this.y = y; this.w = w; }

    public boolean isPlatform() { return type != Type.ROPE && type != Type.CABLE; }
    public boolean isMoving() { return type == Type.MOVE_H || type == Type.MOVE_V || type == Type.SWING; }

    private float ang(float t) { return (float) (2 * Math.PI * t / period + phase); }

    /** Center arc position at time t (unwrapped, relative to course space). */
    public float sAt(float t) {
        switch (type) {
            case MOVE_H: return s + amp * (float) Math.sin(ang(t));
            case SWING: return s + len * (float) Math.sin(amp * Math.sin(ang(t)));
            default: return s;
        }
    }

    /** Top surface height (platforms) or anchor/cable height at time t. */
    public float yAt(float t) {
        switch (type) {
            case MOVE_V: return y + amp * 0.5f * (1f - (float) Math.cos(ang(t)));
            case SWING: return y + len - len * (float) Math.cos(amp * Math.sin(ang(t)));
            default: return y;
        }
    }

    public float yBottom() { return y - len; }   // rope bottom
    public float halfW() { return w * 0.5f; }
}
