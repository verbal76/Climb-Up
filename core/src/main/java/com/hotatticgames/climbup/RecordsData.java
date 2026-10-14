package com.hotatticgames.climbup;

import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonWriter;
import com.hotatticgames.climbup.sim.CourseGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Personal records, kept apart from everything else so it can be owned by one player: the best time for each of the ten castle-to-castle legs, the best total for all ten, the best
 * distance climbed beyond the tenth tower (the endless "infinity" climb), plus the run in progress and the last run that was ended (for the end-of-run summary).
 * <p>
 * Build 64 adds the same bests PER SEED ({@link SeedBests}: the course a friend can type in), each stamped with the generation rules version it was climbed under, so a time is
 * only ever compared with times on the same course under the same rules. A seed's best leg and its best full run are kept apart: a best leg may come from a run that was not
 * the best full run, and the legs are never added up into a full-run record. The global bests above are unchanged and still cover every seed.
 * <p>
 * Plain public fields, versioned, stored as one JSON text ({@link #toJson}/{@link #fromJson}). Reading never throws: missing, damaged or newer text gives sane values.
 * The run in progress is keyed by the climb's seed, so a new climb starts clean without anybody having to remember to reset it. Nothing here gives the player any advantage; it only remembers.
 * No clock lives here: times come from the run clock in {@link SaveData} (which counts only while playing), so a pause or a killed app cannot change them.
 */
public final class RecordsData {
    public static final int VERSION = 2;
    /** Castle-to-castle legs in the official run. */
    public static final int LEGS = 10;
    /** How many seeds' bests are kept (the most recently played first). */
    public static final int MAX_SEEDS = 60;

    public int version = VERSION;
    public float[] bestLegs = new float[LEGS];     // seconds per leg, 0 = no time yet
    public float bestTotal = 0f;                   // seconds for all ten, 0 = none yet
    public float bestInfinity = 0f;                // metres beyond the tenth tower, 0 = none yet
    public Run current = new Run();                // the run being played, keyed by its climb's seed
    public Run last = new Run();                   // the last run that was ended with END RUN AND SAVE TIME
    public boolean hasLast = false;
    public ArrayList<SeedBests> seeds = new ArrayList<>();   // Build 64: per-seed bests, most recently played first
    private transient int loadedVersion = VERSION;           // the layout the text was read from (for the one-time upgrade)

    /** One run's times and which of them are personal bests. */
    public static final class Run {
        public long seed = 0;
        public int rules = 0;                      // Build 64: generation rules version of the course (0 = unknown: a run from before Build 63, not comparable)
        public float[] legs = new float[LEGS];     // seconds, 0 = leg not done
        public boolean[] legPb = new boolean[LEGS];
        public boolean[] legSeedPb = new boolean[LEGS];   // Build 64: a best for that leg on this seed (including the first time)
        public float[] legSeedPrev = new float[LEGS];     // Build 64: the seed's best for that leg BEFORE this run's leg was recorded (0 = none): the faster/slower difference
        public boolean finished = false;           // walked through castle 10
        public float total = 0f;                   // the official finish time (0 until finished)
        public boolean totalPb = false;
        public boolean totalSeedPb = false;        // Build 64: a best full run on this seed (including the first)
        public float totalSeedPrev = 0f;           // Build 64: the seed's best full run BEFORE this one (0 = none)
        public float infinity = 0f;                // metres beyond the tenth tower in this run
        public boolean infinityPb = false;
        public Run() {}
        Run(long seed, int rules) { this.seed = seed; this.rules = rules; }
        public int legsDone() { int n = 0; for (int i = 0; i < LEGS; i++) if (legs[i] > 0f) n = i + 1; return n; }
    }

    /** Build 64: the bests on one course (seed + rules): a best per leg and a best full run, each from whichever run on that course achieved it. */
    public static final class SeedBests {
        public long seed = 0;
        public int rules = 0;
        public float[] bestLegs = new float[LEGS]; // seconds per leg, 0 = none yet
        public float bestTotal = 0f;               // seconds for all ten, 0 = none yet
        public int runs = 0;                       // runs recorded on it
        public SeedBests() {}
        SeedBests(long seed, int rules) { this.seed = seed; this.rules = rules; }
    }

    public RecordsData() {}

    // ------------------------------------------------------------------ recording

    private Run run(long seed, int rules) {
        if (current == null || current.seed != seed) current = new Run(seed, rules);
        if (current.rules == 0 && rules > 0) current.rules = rules;
        return current;
    }
    private static boolean good(float v) { return !Float.isNaN(v) && !Float.isInfinite(v) && v > 0f; }

    /** The bests on a course, or null when nothing was ever recorded on it (unknown rules, 0, never match). */
    public SeedBests seedBests(long seed, int rules) {
        if (rules <= 0) return null;
        for (SeedBests s : seeds) if (s.seed == seed && s.rules == rules) return s;
        return null;
    }
    private SeedBests seedBestsOrNew(long seed, int rules) {
        SeedBests s = seedBests(seed, rules);
        if (s == null) { s = new SeedBests(seed, rules); if (seeds.size() >= MAX_SEEDS) seeds.remove(seeds.size() - 1); }
        else seeds.remove(s);
        seeds.add(0, s);                           // most recently played first
        return s;
    }

    /** A castle was opened on the current rules: leg {@code index} (0-based) took {@code seconds}. Returns true when that is a new personal best for the leg over every seed (including the first time). */
    public boolean legDone(long seed, int index, float seconds) { return legDone(seed, CourseGenerator.RULES_VERSION, index, seconds); }
    /** The same for a course on a known rules version. The run also learns the seed's best for the leg so far (for the faster/slower difference) and whether it now holds it. */
    public boolean legDone(long seed, int rules, int index, float seconds) {
        if (index < 0 || index >= LEGS || !good(seconds)) return false;
        Run r = run(seed, rules);
        r.legs[index] = seconds;
        boolean pb = bestLegs[index] <= 0f || seconds < bestLegs[index];
        if (pb) { bestLegs[index] = seconds; r.legPb[index] = true; }
        if (rules > 0) {
            SeedBests s = seedBestsOrNew(seed, rules);
            r.legSeedPrev[index] = s.bestLegs[index];
            boolean spb = s.bestLegs[index] <= 0f || seconds < s.bestLegs[index];
            if (spb) { s.bestLegs[index] = seconds; r.legSeedPb[index] = true; }
            if (index == 0) s.runs++;
        }
        return pb;
    }

    /** Castle 10's door was walked through at {@code total} seconds on the current rules. Returns true for a new best total over every seed. Safe to call once per run; a repeat only refreshes the time. */
    public boolean finish(long seed, float total) { return finish(seed, CourseGenerator.RULES_VERSION, total); }
    public boolean finish(long seed, int rules, float total) {
        if (!good(total)) return false;
        Run r = run(seed, rules);
        boolean first = !r.finished;
        r.finished = true; r.total = total;
        boolean pb = bestTotal <= 0f || total < bestTotal;
        if (pb) { bestTotal = total; r.totalPb = true; }
        if (rules > 0 && first) {
            SeedBests s = seedBestsOrNew(seed, rules);
            r.totalSeedPrev = s.bestTotal;
            boolean spb = s.bestTotal <= 0f || total < s.bestTotal;
            if (spb) { s.bestTotal = total; r.totalSeedPb = true; }
        }
        return pb;
    }

    /** The climb beyond castle 10 has reached {@code metres}. Call as often as you like; only the furthest counts. Returns true while this run holds the record. */
    public boolean infinity(long seed, float metres) {
        if (!good(metres)) return false;
        Run r = run(seed, current != null && current.seed == seed ? current.rules : 0);
        if (metres > r.infinity) r.infinity = metres;
        if (metres > bestInfinity) { bestInfinity = metres; r.infinityPb = true; }
        return r.infinityPb;
    }

    /**
     * Brings the run in progress up to date with the run clock's own record (the splits and finish time kept with the climb), for a climb that was started before these records existed or whose
     * records were lost. Legs already recorded are left alone, so calling it again changes nothing.
     */
    public void catchUp(long seed, float[] splits, int count, boolean finished, float finishTime) { catchUp(seed, CourseGenerator.RULES_VERSION, splits, count, finished, finishTime); }
    public void catchUp(long seed, int rules, float[] splits, int count, boolean finished, float finishTime) {
        if (splits != null) for (int i = 0; i < count && i < splits.length && i < LEGS; i++) if (run(seed, rules).legs[i] <= 0f) legDone(seed, rules, i, splits[i]);
        if (finished && !run(seed, rules).finished) finish(seed, rules, finishTime);
    }

    /** The run is over (finished and ended, or ended from the pause menu): it becomes the "last run" and the current run is cleared. Returns that last run. */
    public Run endRun(long seed) {
        Run r = (current != null && current.seed == seed) ? current : new Run(seed, 0);
        last = r; hasLast = true; current = new Run();
        return last;
    }

    /** The run to show on screen now: the climb in progress ({@code seed}) if it has anything yet, else the last ended run, else null. A climb that was replaced by NEW RUN is never shown as the current one. */
    public Run shown(long seed) {
        if (current != null && current.seed == seed && (current.legsDone() > 0 || current.finished || current.infinity > 0f)) return current;
        return hasLast ? last : null;
    }

    /**
     * Build 64, once, right after the records were read from a Build 63 (version 1) text: the run in progress and the last run are stamped with the rules their build used, and the run in
     * progress seeds the per-seed bests it already earned, so nothing a player timed on Build 63 is lost or shown as "old rules" when it is not. Records whose seed cannot be matched
     * (an older last run) keep rules 0: unknown, never compared. A no-op for current-layout text.
     */
    public void upgradeFrom63(long climbSeed, int climbRules) {
        if (loadedVersion >= 2) return;
        loadedVersion = VERSION;
        if (current != null && current.seed != 0 && current.seed == climbSeed && climbRules > 0) {
            current.rules = climbRules;
            if (seedBests(climbSeed, climbRules) == null) {
                SeedBests s = seedBestsOrNew(climbSeed, climbRules);
                for (int i = 0; i < LEGS; i++) if (current.legs[i] > 0f) { s.bestLegs[i] = current.legs[i]; current.legSeedPb[i] = true; }
                if (current.finished && current.total > 0f) { s.bestTotal = current.total; current.totalSeedPb = true; }
                s.runs = 1;
            }
        }
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
        r.legs = legs(r.legs); r.legPb = flags(r.legPb); r.legSeedPb = flags(r.legSeedPb); r.legSeedPrev = legs(r.legSeedPrev); r.total = clean(r.total); r.infinity = clean(r.infinity); r.totalSeedPrev = clean(r.totalSeedPrev);
        if (r.rules < 0) r.rules = 0;
        if (r.total <= 0f) { r.finished = false; r.totalPb = false; r.totalSeedPb = false; }
        if (r.infinity <= 0f) r.infinityPb = false;
        for (int i = 0; i < LEGS; i++) if (r.legs[i] <= 0f) { r.legPb[i] = false; r.legSeedPb[i] = false; }
    }
    /** Repairs anything out of range so the rest of the game can trust the numbers. */
    public RecordsData sanitize() {
        bestLegs = legs(bestLegs); bestTotal = clean(bestTotal); bestInfinity = clean(bestInfinity);
        if (current == null) current = new Run();
        if (last == null) last = new Run();
        clean(current); clean(last);
        if (seeds == null) seeds = new ArrayList<>();
        List<SeedBests> ok = new ArrayList<>();
        for (SeedBests s : seeds) {
            if (s == null || s.seed == 0 || s.rules <= 0) continue;
            s.bestLegs = legs(s.bestLegs); s.bestTotal = clean(s.bestTotal); if (s.runs < 0) s.runs = 0;
            boolean dup = false; for (SeedBests o : ok) if (o.seed == s.seed && o.rules == s.rules) dup = true;
            if (!dup) ok.add(s);
        }
        while (ok.size() > MAX_SEEDS) ok.remove(ok.size() - 1);
        seeds = new ArrayList<>(ok);
        version = VERSION;
        return this;
    }
    private static Json json(JsonWriter.OutputType type) { Json j = new Json(type); j.setUsePrototypes(false); j.setIgnoreUnknownFields(true); j.setElementType(RecordsData.class, "seeds", SeedBests.class); return j; }
    public String toJson() { return json(JsonWriter.OutputType.json).toJson(this); }
    /** Parses stored text; anything missing, empty, damaged or unreadable gives fresh empty records (never throws, never returns null). */
    public static RecordsData fromJson(String text) {
        if (text == null || text.trim().isEmpty()) return new RecordsData();
        try {
            RecordsData d = json(JsonWriter.OutputType.json).fromJson(RecordsData.class, text);
            if (d == null) return new RecordsData();
            d.loadedVersion = d.version;             // version 1 (Build 63 and before) is upgraded by upgradeFrom63 once the owner's climb is known
            return d.sanitize();
        } catch (Exception e) { return new RecordsData(); }
    }
    /** The layout the text was read from (1 until {@link #upgradeFrom63} has run on a Build 63 text). */
    public int loadedVersion() { return loadedVersion; }
}
