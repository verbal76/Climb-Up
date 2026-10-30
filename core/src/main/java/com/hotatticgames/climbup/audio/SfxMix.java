package com.hotatticgames.climbup.audio;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Build 83 effects bus (owner: effects too quiet at EFFECTS VOLUME 10, quiet near the bottom, nothing clips, cues stay distinguishable).
 * Pure rules, no engine calls, so it is unit-testable headlessly; {@link Audio} asks it before every effect it starts.
 *
 * <ul>
 * <li><b>Slider curve:</b> EFFECTS VOLUME 0 is silence; 1..10 are equal steps in decibels from {@code bottomDb} (-28 dB) to {@code topDb} (0 dB),
 *     so every step sounds like the same change. (Build 82 used volume/10 as a plain amplitude: 1 was -20 dB and 5..10 changed only 6 dB.)
 *     The loudness gained at 10 comes from the mastered files (tools/audio/master_sfx.py), because a platform sound cannot play above 1.0.</li>
 * <li><b>Voices:</b> each sound has a cap on copies playing at once and a minimum gap between starts; at most {@code maxVoices} effects are
 *     counted as playing (texture and action sounds are dropped beyond it, major ones never are), well under Android's 24-stream pool and
 *     desktop OpenAL's 16 sources (music and the ambience bed use their own).</li>
 * <li><b>Stacking:</b> when more than {@code stackFree} effects started within {@code stackWindow} seconds, each further non-major one is
 *     scaled by 1/sqrt(n) (equal-power), so a pile-up of hazards does not add up into distortion.</li>
 * <li><b>Peak budget:</b> the peaks of effects that start within {@code peakWindow} of each other add up; a new effect is turned down so the
 *     sum stays at or under {@code peakBudget} (full scale), so even a worst-case pile-up does not clip the output (a non-major sound that would
 *     need more than -14 dB is skipped instead; a major one makes room by turning down the sounds that just started, minor ones first). Peaks come from sfx_levels.json.</li>
 * <li><b>Ducking:</b> a major cue (checkpoint, key, hit, alert...) dips texture sounds (steps, creaks, buzzes, hum) by {@code duckDb} for
 *     {@code duckTime} seconds so it is heard clearly over a busy scene.</li>
 * <li><b>Cosmetic randomness</b> (which variant, small pitch changes) comes from this class's own generator: never the simulation's, never
 *     the shared MathUtils one, so it cannot touch a replay or the course.</li>
 * </ul>
 * Data: assets/data/sfx_mix.json (also read by the mastering tool) and assets/data/sfx_levels.json (clip lengths).
 */
public final class SfxMix {
    public static final int TEXTURE = 0, ACTION = 1, MAJOR = 2;

    public static final class Entry {
        public int prio = ACTION; public float use = 1f; public int cap = 3; public float gap = 0.045f;
    }

    // ---- settings (defaults = the shipped sfx_mix.json)
    public boolean mastered = true;
    public float bottomDb = -28f, topDb = 0f;
    public int maxVoices = 14, stackFree = 3;
    public float stackWindow = 0.12f, duckDb = -6f, duckTime = 0.35f, peakWindow = 0.1f, peakBudget = 1f;
    private final Map<String, Entry> entries = new HashMap<>();
    private final Entry fallback = new Entry();

    // ---- voices (fixed arrays: no allocation per play)
    static final int SLOTS = 48;
    private final String[] vName = new String[SLOTS];
    private final double[] vStart = new double[SLOTS], vEnd = new double[SLOTS];
    private final int[] vPrio = new int[SLOTS];
    private final float[] vPeak = new float[SLOTS];
    private final Map<String, Double> lastStart = new HashMap<>();
    private double duckUntil = -1;
    public int dropped, ducked, stacked, limited;
    /**
     * Set by the last {@link #admit}: the factor (1 = none) by which the non-major voices started within {@code peakWindow} must be turned down
     * so a major cue fits the peak budget. The caller applies it to those voices (it knows their ids); the mix already counts them that way.
     */
    public float squeeze = 1f, squeezeMajor = 1f;
    static final float MINOR_FLOOR = 0.25f, MAJOR_FLOOR = 0.4f;

    private final Random rng;

    public SfxMix() { this(System.nanoTime() ^ 0x5F83A0D1L); }
    /** A fixed seed for tests. */
    public SfxMix(long cosmeticSeed) { rng = new Random(cosmeticSeed); }

    /** Reads sfx_mix.json's text (null or unreadable: the defaults). */
    public SfxMix load(String json) {
        if (json == null) return this;
        try {
            JsonValue r = new JsonReader().parse(json);
            mastered = r.getBoolean("mastered", mastered);
            JsonValue c = r.get("curve");
            if (c != null) { bottomDb = c.getFloat("bottomDb", bottomDb); topDb = c.getFloat("topDb", topDb); }
            JsonValue v = r.get("voices");
            if (v != null) { maxVoices = v.getInt("max", maxVoices); stackWindow = v.getFloat("stackWindow", stackWindow); stackFree = v.getInt("stackFree", stackFree);
                             peakWindow = v.getFloat("peakWindow", peakWindow); peakBudget = v.getFloat("peakBudget", peakBudget); }
            JsonValue d = r.get("duck");
            if (d != null) { duckDb = d.getFloat("db", duckDb); duckTime = d.getFloat("time", duckTime); }
            Map<String, Integer> prio = new HashMap<>();
            JsonValue cl = r.get("classes");
            if (cl != null) for (JsonValue e = cl.child; e != null; e = e.next) prio.put(e.name, e.getInt("prio", ACTION));
            JsonValue s = r.get("sounds");
            if (s != null) for (JsonValue e = s.child; e != null; e = e.next) {
                Entry en = new Entry();
                en.prio = prio.getOrDefault(e.getString("class", "action"), ACTION);
                en.use = e.getFloat("use", 1f); en.cap = Math.max(1, e.getInt("cap", 3)); en.gap = Math.max(0f, e.getFloat("gap", 0.045f));
                entries.put(e.name, en);
            }
        } catch (Exception ignored) { }
        maxVoices = Math.max(4, Math.min(SLOTS, maxVoices));
        return this;
    }

    public Entry entry(String name) { Entry e = entries.get(name); return e != null ? e : fallback; }
    public boolean has(String name) { return entries.containsKey(name); }

    /** EFFECTS VOLUME step (0..10; anything else clamped) to a linear gain: 0 = silence, then equal decibel steps from bottomDb at 1 to topDb at 10. */
    public float bus(int step) { return curve(step, bottomDb, topDb); }
    public static float curve(int step, float bottomDb, float topDb) {
        if (step <= 0) return 0f;
        int s = Math.min(10, step);
        float db = bottomDb + (topDb - bottomDb) * (s - 1) / 9f;
        return (float) Math.pow(10.0, db / 20.0);
    }
    /** The step's level in dB (for the About line and the report); -inf for 0. */
    public float busDb(int step) { return step <= 0 ? Float.NEGATIVE_INFINITY : (float) (20 * Math.log10(bus(step))); }

    /** {@link #admit(String, float, float, double)} for a sound whose output peak is not known (counted as nothing for the peak budget). */
    public float admit(String name, float seconds, double now) { return admit(name, seconds, 0f, now); }

    /**
     * Asks to start {@code name} now, lasting {@code seconds} (its clip length over its pitch), peaking at {@code peak} (linear, after the caller's
     * volume and the bus: the file's true peak times both). Returns the extra gain (0..1) to apply on top of the caller's volume and the bus, or
     * -1 when it must not start (too soon after the last one, too many copies, too many sounds at once, or no peak room left).
     */
    public float admit(String name, float seconds, float peak, double now) {
        Entry e = entry(name);
        Double last = lastStart.get(name);
        if (last != null && now - last < e.gap) { dropped++; return -1f; }
        int same = 0, total = 0, recent = 0, free = -1; float peaks = 0f, minorPeaks = 0f;
        squeeze = 1f; squeezeMajor = 1f;
        for (int i = 0; i < SLOTS; i++) {
            if (vName[i] == null) { if (free < 0) free = i; continue; }
            if (vEnd[i] <= now) { vName[i] = null; if (free < 0) free = i; continue; }
            total++;
            if (vName[i].equals(name)) same++;
            if (now - vStart[i] < stackWindow) recent++;
            if (now - vStart[i] < peakWindow) { peaks += vPeak[i]; if (vPrio[i] < MAJOR) minorPeaks += vPeak[i]; }
        }
        if (same >= e.cap && e.prio < MAJOR) { dropped++; return -1f; }
        if (total >= maxVoices && e.prio < MAJOR) { dropped++; return -1f; }
        float g = 1f;
        if (e.prio == TEXTURE && now < duckUntil) { g *= duckGain(); ducked++; }
        if (e.prio < MAJOR && recent >= stackFree) { g *= (float) (1.0 / Math.sqrt(recent - stackFree + 2)); stacked++; }
        if (peak > 0f && peaks + peak * g > peakBudget) {             // the peak budget
            if (e.prio == MAJOR) {
                // a major cue makes room, in this order: the non-major voices that just started are turned down (to a quarter at most), then this
                // cue itself, then the major voices that just started (each to 0.4 at most). The caller applies {@link #squeeze} / {@link #squeezeMajor}.
                float majors = peaks - minorPeaks, mine = peak * g, need = peaks + mine - peakBudget;
                if (minorPeaks > 0f) { squeeze = Math.max(MINOR_FLOOR, 1f - need / minorPeaks); need -= minorPeaks * (1f - squeeze); }
                if (need > 0f) { float k = Math.max(MAJOR_FLOOR, 1f - need / mine); need -= mine * (1f - k); g *= k; }
                if (need > 0f && majors > 0f) squeezeMajor = Math.max(MAJOR_FLOOR, 1f - need / majors);
                for (int i = 0; i < SLOTS; i++) if (vName[i] != null && now - vStart[i] < peakWindow) vPeak[i] *= vPrio[i] < MAJOR ? squeeze : squeezeMajor;
            } else {                                                  // a minor one takes what is left, or waits
                float room = Math.max(0f, peakBudget - peaks) / (peak * g);
                if (room < 0.2f) { dropped++; return -1f; }
                g *= Math.min(1f, room);
            }
            limited++;
        }
        if (free < 0) {                                   // every slot is still sounding (only possible with many major cues): reuse the oldest
            free = 0; for (int i = 1; i < SLOTS; i++) if (vStart[i] < vStart[free]) free = i;
        }
        vName[free] = name; vStart[free] = now; vEnd[free] = now + Math.max(0.03f, seconds); vPrio[free] = e.prio; vPeak[free] = peak * g;
        lastStart.put(name, now);
        if (e.prio == MAJOR) duckUntil = Math.max(duckUntil, now + duckTime);
        return g;
    }

    /** Effects counted as playing now. */
    public int active(double now) { int n = 0; for (int i = 0; i < SLOTS; i++) if (vName[i] != null && vEnd[i] > now) n++; return n; }
    /** The texture duck as a gain while it lasts, else 1 (the conveyor hum and the ambience bed follow it too). */
    public float duckNow(double now) { return now < duckUntil ? duckGain() : 1f; }
    private float duckGain() { return (float) Math.pow(10.0, duckDb / 20.0); }
    /** Forgets every playing voice (audio restarted, all sounds stopped). */
    public void reset() { for (int i = 0; i < SLOTS; i++) vName[i] = null; lastStart.clear(); duckUntil = -1; }

    // ---- cosmetic randomness: this generator only
    /** One of n variants, never the same as {@code last} twice in a row when there is a choice. */
    public int pick(int n, int last) {
        if (n <= 1) return 0;
        int i = rng.nextInt(n);
        return i == last ? (i + 1) % n : i;
    }
    /** base + a uniform 0..spread (for pitch and volume variation). */
    public float vary(float base, float spread) { return base + rng.nextFloat() * spread; }
}
