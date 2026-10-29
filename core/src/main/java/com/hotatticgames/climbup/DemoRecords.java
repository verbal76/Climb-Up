package com.hotatticgames.climbup;

import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.JsonReader;
import java.io.File;

/**
 * The demo's own progress and records (demo.json in the demo's data folder; the demo is installed and saved apart from the full game, so nothing here
 * ever meets a full-game save). Per difficulty: the best stage time in whole simulation steps (the run clock: title cards, loading and pauses never
 * count), with the seed and generator fingerprint it was set on. A best set on another course or generator is kept but never compared with: it is
 * shown as from an older demo and the next finish on this course replaces it. Also the stage the player has reached in the sequence (the demo
 * continues there; it never skips ahead) and the best cumulative time of a whole NORMAL-PUNISHER-NOT GONNA HAPPEN run.
 */
public final class DemoRecords {
    public static final int FORMAT = 1;

    public static final class Best { public long ticks; public long seed; public String fingerprint = ""; }

    public int format = FORMAT;
    public int stage;                                      // 0..2: the stage the sequence continues at; 3 = the sequence was finished (the promo screen)
    public Best[] best = {new Best(), new Best(), new Best()};
    public long runTicks;                                  // the sequence being played: the sum of its finished stages (0 when one was assisted or is from another course)
    public boolean runClean = true;                        // every stage of this sequence so far was unassisted and on the recipe's courses
    public long bestRunTicks; public String bestRunFingerprint = "";
    public int finishes;                                   // whole sequences finished

    /** What a stage finish did to the records. */
    public static final class Result { public boolean newBest, assisted, firstOnCourse; public long previous; }

    /** Records a finished stage: {@code ticks} of run clock, on the course with {@code fingerprint}. Assisted times are shown but never become bests. */
    public Result stageFinished(int difficulty, long ticks, long seed, String fingerprint, boolean assisted) {
        Result r = new Result(); r.assisted = assisted;
        Best b = best[difficulty];
        boolean comparable = b.ticks > 0 && b.seed == seed && fingerprint.equals(b.fingerprint);
        r.previous = comparable ? b.ticks : 0L; r.firstOnCourse = !comparable;
        if (!assisted && (!comparable || ticks < b.ticks)) { b.ticks = ticks; b.seed = seed; b.fingerprint = fingerprint; r.newBest = true; }
        if (assisted) runClean = false;
        runTicks += ticks;
        stage = difficulty + 1;
        return r;
    }

    /** The sequence was finished (after the last stage): keeps the best cumulative time when every stage was clean. Returns true for a new best. */
    public boolean sequenceFinished(String fingerprint) {
        finishes++;
        boolean pb = runClean && runTicks > 0 && (bestRunTicks == 0 || !fingerprint.equals(bestRunFingerprint) || runTicks < bestRunTicks);
        if (pb) { bestRunTicks = runTicks; bestRunFingerprint = fingerprint; }
        return pb;
    }

    /** PLAY DEMO AGAIN (or a first start): the sequence begins again at NORMAL. Bests stay. */
    public void restartSequence() { stage = 0; runTicks = 0; runClean = true; }

    /** ERASE DEMO DATA: everything back to a first start. */
    public void reset() { stage = 0; best = new Best[]{new Best(), new Best(), new Best()}; runTicks = 0; runClean = true; bestRunTicks = 0; bestRunFingerprint = ""; finishes = 0; }

    /** A best that belongs to this course and generator (0 when there is none, or it was set on another). */
    public long bestFor(int difficulty, long seed, String fingerprint) {
        Best b = best[difficulty];
        return b.ticks > 0 && b.seed == seed && fingerprint.equals(b.fingerprint) ? b.ticks : 0L;
    }

    // ---- storage: written atomically (temp file, then rename); a damaged file is kept aside and the demo starts its records afresh, never crashes

    public static DemoRecords load(File dir) {
        File f = new File(dir, "demo.json");
        if (!f.isFile()) return new DemoRecords();
        try {
            String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            return parse(text);
        } catch (Exception e) {
            try { java.nio.file.Files.copy(f.toPath(), new File(dir, "demo.json.corrupt").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignored) { }
            return new DemoRecords();
        }
    }

    static DemoRecords parse(String text) {
        JsonValue v = new JsonReader().parse(text);
        if (v == null || v.getInt("format", -1) != FORMAT) throw new IllegalArgumentException("demo records format");
        DemoRecords r = new Json().fromJson(DemoRecords.class, text);
        if (r.best == null || r.best.length != 3) throw new IllegalArgumentException("demo records bests");
        for (int i = 0; i < 3; i++) { if (r.best[i] == null) r.best[i] = new Best(); if (r.best[i].fingerprint == null) r.best[i].fingerprint = ""; if (r.best[i].ticks < 0) r.best[i].ticks = 0; }
        if (r.bestRunFingerprint == null) r.bestRunFingerprint = "";
        r.stage = Math.max(0, Math.min(3, r.stage));
        return r;
    }

    String toJson() { Json j = new Json(); j.setOutputType(com.badlogic.gdx.utils.JsonWriter.OutputType.json); j.setUsePrototypes(false); return j.prettyPrint(this); }

    public void save(File dir) {
        try { new SaveStore(dir).writeAtomic("demo.json", toJson()); } catch (java.io.IOException ignored) { /* the demo goes on; the next save tries again */ }
    }

    public static void erase(File dir) { DiskWriter.drain(); new File(dir, "demo.json").delete(); new File(dir, "demo.json.tmp").delete(); }
}
