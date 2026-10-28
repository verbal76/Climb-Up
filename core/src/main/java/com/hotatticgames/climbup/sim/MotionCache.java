package com.hotatticgames.climbup.sim;

import java.util.Arrays;

/**
 * Speed only, same results (2026-10-26, generation performance). Where a moving element or a hazard is depends on nothing but the time, and every trial
 * the planner runs from one starting state passes through the same times, step for step (each starts from the same clock and adds the same step). So a
 * planning call keeps the positions it has computed, per element and per step of the trial, and the next trial reads them instead of evaluating the same
 * sines and cosines again. Each stored value carries the exact bits of the time it was computed for and is used only for that time; anything else is
 * computed as before. One cache belongs to one planning call on one thread (a nested call, or the next call, starts a new generation; the arrays are reused).
 */
final class MotionCache {
    static final int NO_TIME = 0x7fc0dead;          // a NaN pattern the clock never takes
    /** Values kept per element per step: es1, ey1, edy, depth (MOVE_Z). */
    static final int EL = 4;
    /** Values kept per hazard per step: arc, height, a crab's facing on its sweep. */
    static final int HZ = 3;

    private static final ThreadLocal<MotionCache> MINE = ThreadLocal.withInitial(MotionCache::new);
    private static final ThreadLocal<int[]> GEN = ThreadLocal.withInitial(() -> new int[1]);

    private int gen, steps;
    private Element[] elKey = new Element[0], hzKey = new Element[0];
    private int[] elGen = new int[0], hzGen = new int[0];
    private int[][] elT = new int[0][], hzT = new int[0][];
    private float[][] elV = new float[0][], hzV = new float[0][];

    /** This thread's cache, opened for a new planning call whose trials run at most {@code steps} steps. */
    static MotionCache open(int steps) {
        MotionCache m = MINE.get();
        m.gen = ++GEN.get()[0]; m.steps = steps;
        return m;
    }
    int gen() { return gen; }

    /** The stored values for element {@code i} at trial step {@code k}, or -1 when they are not there for this exact time (then {@link #elPut} them). */
    int elAt(int g, int i, Element e, int k, float time) {
        if (g != gen || k < 0 || k >= steps || i >= elKey.length || elKey[i] != e || elGen[i] != gen) return -1;
        return elT[i][k] == Float.floatToRawIntBits(time) ? k * EL : -1;
    }
    float[] elVals(int i) { return elV[i]; }
    void elPut(int g, int i, Element e, int k, float time, float a, float b, float c, float d) {
        if (g != gen || k < 0 || k >= steps) return;
        if (i >= elKey.length) grow(i + 1, true);
        if (elKey[i] != e || elGen[i] != gen) { elKey[i] = e; elGen[i] = gen; elT[i] = fresh(elT[i]); if (elV[i] == null || elV[i].length < steps * EL) elV[i] = new float[steps * EL]; }
        elT[i][k] = Float.floatToRawIntBits(time); float[] v = elV[i]; int o = k * EL; v[o] = a; v[o + 1] = b; v[o + 2] = c; v[o + 3] = d;
    }

    int hzAt(int g, int i, Element e, int k, float time) {
        if (g != gen || k < 0 || k >= steps || i >= hzKey.length || hzKey[i] != e || hzGen[i] != gen) return -1;
        return hzT[i][k] == Float.floatToRawIntBits(time) ? k * HZ : -1;
    }
    float[] hzVals(int i) { return hzV[i]; }
    void hzPut(int g, int i, Element e, int k, float time, float a, float b, float c) {
        if (g != gen || k < 0 || k >= steps) return;
        if (i >= hzKey.length) grow(i + 1, false);
        if (hzKey[i] != e || hzGen[i] != gen) { hzKey[i] = e; hzGen[i] = gen; hzT[i] = fresh(hzT[i]); if (hzV[i] == null || hzV[i].length < steps * HZ) hzV[i] = new float[steps * HZ]; }
        hzT[i][k] = Float.floatToRawIntBits(time); float[] v = hzV[i]; int o = k * HZ; v[o] = a; v[o + 1] = b; v[o + 2] = c;
    }

    private int[] fresh(int[] t) { if (t == null || t.length < steps) t = new int[steps]; Arrays.fill(t, 0, steps, NO_TIME); return t; }

    private void grow(int n, boolean el) {
        n = Math.max(n, 64);
        if (el) { n = Math.max(n, elKey.length * 3 / 2); elKey = Arrays.copyOf(elKey, n); elGen = Arrays.copyOf(elGen, n); elT = Arrays.copyOf(elT, n); elV = Arrays.copyOf(elV, n); }
        else { n = Math.max(n, hzKey.length * 3 / 2); hzKey = Arrays.copyOf(hzKey, n); hzGen = Arrays.copyOf(hzGen, n); hzT = Arrays.copyOf(hzT, n); hzV = Arrays.copyOf(hzV, n); }
    }
}
