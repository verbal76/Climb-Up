package com.hotatticgames.climbup;

import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonWriter;
import java.util.Arrays;

/**
 * Personal records, kept apart from everything else so it can be owned by one player: the best time for each of the ten castle-to-castle legs, the best total for all ten, the best
 * distance climbed beyond the tenth tower (the endless "infinity" climb), plus the run in progress and the last run that was ended (for the end-of-run summary).
 * <p>
 * Plain public fields, versioned, stored as one JSON text ({@link #toJson}/{@link #fromJson}). Reading never throws: missing, damaged or newer text gives sane values.
 * The run in progress is keyed by the climb's seed, so a new climb starts clean without anybody having to remember to reset it. Nothing here gives the player any advantage; it only remembers.
 * No clock lives here: times come from the run clock in {@link SaveData} (which counts only while playing), so a pause or a killed app cannot change them.
 */
public final class RecordsData {
    public static final int VERSION = 1;
    /** Castle-to-castle legs in the official run. */
    public static final int LEGS = 10;

    public int version = VERSION;
    public float[] bestLegs = new float[LEGS];     // seconds per leg, 0 = no time yet
    public float bestTotal = 0f;                   // seconds for all ten, 0 = none yet
    public float bestInfinity = 0f;                // metres beyond the tenth tower, 0 = none yet
    public Run current = new Run();                // the run being played, keyed by its climb's seed
    public Run last = new Run();                   // the last run that was ended with END RUN AND SAVE TIME
    public boolean hasLast = false;

    /** One run's times and which of them are personal bests. */
    public static final class Run {
        public long seed = 0;
        public float[] legs = new float[LEGS];     // seconds, 0 = leg not done
        public boolean[] legPb = new boolean[LEGS];
        public boolean finished = false;           // walked through castle 10
        public float total = 0f;                   // the official finish time (0 until finished)
        public boolean totalPb = false;
        public float infinity = 0f;                // metres beyond the tenth tower in this run
        public boolean infinityPb = false;

        public Run() {}
        Run(long seed) { this.seed = seed; }

        public int legsDone() { int n = 0; for (int i = 0; i < LEGS; i++) if (legs[i] > 0f) n = i + 1; return n; }
    }

    public RecordsData() {}

    // ------------------------------------------------------------------ recording

    private Run run(long seed) {
        if (current == null || current.seed != seed) current = new Run(seed);
        return current;
    }

    private static boolean good(float v) { return !Float.isNaN(v) && !Float.isInfinite(v) && v > 0f; }

    /** A castle was opened: leg {@code index} (0-based) took {@code seconds}. Returns true when that is a new personal best for the leg (including the first time). */
    public boolean legDone(long seed, int index, float seconds) {
        if (index < 0 || index >= LEGS || !good(seconds)) return false;
        Run r = run(seed);
        r.legs[index] = seconds;
        boolean pb = bestLegs[index] <= 0f || seconds < bestLegs[index];
        if (pb) { bestLegs[index] = seconds; r.legPb[index] = true; }
        return pb;
    }

    /** Castle 10's door was walked through at {@code total} seconds. Returns true for a new best total. Safe to call once per run; a repeat only refreshes the time. */
    public boolean finish(long seed, float total) {
        if (!good(total)) return false;
        Run r = run(seed);
        r.finished = true; r.total = total;
        boolean pb = bestTotal <= 0f || total < bestTotal;
        if (pb) { bestTotal = total; r.totalPb = true; }
        return pb;
    }

    /** The climb beyond castle 10 has reached {@code metres}. Call as often as you like; only the furthest counts. Returns true while this run holds the record. */
    public boolean infinity(long seed, float metres) {
        if (!good(metres)) return false;
        Run r = run(seed);
        if (metres > r.infinity) r.infinity = metres;
        if (metres > bestInfinity) { bestInfinity = metres; r.infinityPb = true; }
        return r.infinityPb;
    }

    /**
     * Brings the run in progress up to date with the run clock's own record (the splits and finish time kept with the climb), for a climb that was started before these records existed or whose
     * records were lost. Legs already recorded are left alone, so calling it again changes nothing.
     */
    public void catchUp(long seed, float[] splits, int count, boolean finished, float finishTime) {
        if (splits != null) for (int i = 0; i < count && i < splits.length && i < LEGS; i++) if (run(seed).legs[i] <= 0f) legDone(seed, i, splits[i]);
        if (finished && !run(seed).finished) finish(seed, finishTime);
    }

    /** The run is over (finished and ended, or ended from the pause menu): it becomes the "last run" and the current run is cleared. Returns that last run. */
    public Run endRun(long seed) {
        Run r = (current != null && current.seed == seed) ? current : new Run(seed);
        last = r; hasLast = true; current = new Run();
        return last;
    }

    /** The run to show on screen now: the climb in progress ({@code seed}) if it has anything yet, else the last ended run, else null. A climb that was replaced by NEW RUN is never shown as the current one. */
    public Run shown(long seed) {
        if (current != null && current.seed == seed && (current.legsDone() > 0 || current.finished || current.infinity > 0f)) return current;
        return hasLast ? last : null;
    }

    // ------------------------------------------------------------------ storage

    private static float clean(float v) { return good(v) ? v : 0f; }
    private static float[] legs(float[] a) {
        float[] o = new float[LEGS];
        if (a != null) for (int i = 0; i < LEGS && i < a.length; i++) o[i] = clean(a[i]);
        return o;
    }
    private static boolean[] flags(boolean[] a) { return a == null ? new boolean[LEGS] : Arrays.copyOf(a, LEGS); }
    private static void clean(Run r) {
        r.legs = legs(r.legs); r.legPb = flags(r.legPb); r.total = clean(r.total); r.infinity = clean(r.infinity);
        if (r.total <= 0f) { r.finished = false; r.totalPb = false; }
        if (r.infinity <= 0f) r.infinityPb = false;
        for (int i = 0; i < LEGS; i++) if (r.legs[i] <= 0f) r.legPb[i] = false;
    }

    /** Repairs anything out of range so the rest of the game can trust the numbers. */
    public RecordsData sanitize() {
        bestLegs = legs(bestLegs); bestTotal = clean(bestTotal); bestInfinity = clean(bestInfinity);
        if (current == null) current = new Run();
        if (last == null) last = new Run();
        clean(current); clean(last);
        version = VERSION;
        return this;
    }

    public String toJson() { Json j = new Json(JsonWriter.OutputType.json); j.setUsePrototypes(false); return j.toJson(this); }

    /** Parses stored text; anything missing, empty, damaged or unreadable gives fresh empty records (never throws, never returns null). */
    public static RecordsData fromJson(String text) {
        if (text == null || text.trim().isEmpty()) return new RecordsData();
        try {
            Json j = new Json(); j.setIgnoreUnknownFields(true);
            RecordsData d = j.fromJson(RecordsData.class, text);
            return d == null ? new RecordsData() : d.sanitize();     // (version 1 is the first layout; a later layout would be upgraded here)
        } catch (Exception e) { return new RecordsData(); }
    }
}
