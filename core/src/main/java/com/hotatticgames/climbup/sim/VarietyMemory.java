package com.hotatticgames.climbup.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * The generator's memory of what it recently placed (owner 2026-10-27, history-aware variety; PUNISHER). Bounded: only placements within
 * {@link #SPAN} m below the top are kept. Only accepted placements enter it, and undoing modules forgets what they placed ({@link #forget}).
 * A family placed recently and often is penalised: each earlier placement within SPAN m below multiplies its factor by
 * 1 - {@link #A} * exp(-distance / {@link #DECAY}); the factor never falls below {@link #FLOOR}. Heights only, StrictMath: deterministic everywhere.
 */
public final class VarietyMemory {
    public static final float A = 0.45f, DECAY = 30f, SPAN = 100f, FLOOR = 0.1f;
    /** {family, height, owner index: an undo to at or below it forgets the entry; -1 never forgotten (carried from the previous slice)}. */
    private final List<float[]> h = new ArrayList<>();

    public void clear() { h.clear(); }
    public int size() { return h.size(); }
    public void add(int fam, float y, int owner) { if (fam >= 0) h.add(new float[]{fam, y, owner}); }
    /** Undoing the course back to {@code n0} elements forgets everything placed from there on. */
    public void forget(int n0) { h.removeIf(e -> e[2] >= n0); }
    /** Entries more than SPAN m below {@code top} leave the memory (they no longer affect anything). */
    public void prune(float top) { h.removeIf(e -> e[1] < top - SPAN); }
    public float penalty(int fam, float y) { return penalty(fam, y, A); }
    /** The penalty with strength {@code a} in place of {@link #A} (rules 13: NORMAL uses a lighter touch, DifficultyProfile.varietyStrength). */
    public float penalty(int fam, float y, float a) {
        if (fam < 0) return 1f;
        double f = 1.0;
        for (float[] e : h) { if ((int) e[0] != fam) continue; float d = y - e[1]; if (d >= 0f && d <= SPAN) f *= 1.0 - a * StrictMath.exp(-d / DECAY); }
        return (float) Math.max(FLOOR, f);
    }
}
