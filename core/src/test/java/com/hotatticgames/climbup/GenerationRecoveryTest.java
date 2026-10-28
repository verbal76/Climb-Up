package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Targeted verification for the proposed generation-recovery system (docs/verification/GENERATION_FAILURE_RECOVERY_AUDIT.md, R1-R6). Owner
 * direction 2026-10-28: test preparation only, nothing implemented.
 *
 * RUNNABLE NOW (current code, no production change):
 *   1  saveReload_*        a saved climb reloads with identical course bytes, player state, checkpoint, keys, progress and time
 *   C1 characterisation    today every slice the generator has taken is persisted, including slices the player has not been admitted to (this is
 *                          what R2's final-only persistence changes; the test is expected to FLIP when R2 lands)
 *
 * PENDING (each @Ignore names what must exist first; bodies are written against {@link Seams}, a test-side adapter whose methods throw until the
 * production seams exist, so they compile today and cannot pass by accident):
 *   2  forcedBacktrack_*   K+1 cannot be built from K: K is regenerated while unseen; the rejected K is never admitted, resident or saved
 *   3  saveDuringRecovery_*  interrupt/resume while K is provisional (direct and queued writes): seed, final slices, checkpoint, timer and player
 *                          intact; the provisional tail regenerates deterministically; no duplicated or mismatched history records
 *   5  allRecoveryFails_*  Level 5: the climb stays recoverable and is never deleted
 *   H1, H2 history-file findings of this preparation (torn record; unreadable history replaced by a new climb)
 */
public class GenerationRecoveryTest {

    // ------------------------------------------------------------------ helpers (the PlayScreen loop, headless)

    static ClimbGame game(File dir, Tuning t) {
        ClimbGame g = new ClimbGame(dir);
        g.store = new SaveStore(dir); g.history = new HistoryStore(dir); g.save = g.store.loadGame(); g.tuning = t;
        return g;
    }

    static ClimbGame.Run open(ClimbGame g, long seed, boolean fresh) {
        String old = System.getProperty("climb.seed");
        if (fresh) System.setProperty("climb.seed", Long.toString(seed));
        try { return g.openRun(fresh); } finally { if (fresh) { if (old == null) System.clearProperty("climb.seed"); else System.setProperty("climb.seed", old); } }
    }

    /** The sim exactly as PlayScreen.show builds it for a fresh climb. */
    static Sim freshSim(ClimbGame g, ClimbGame.Run run) {
        Sim s = Sim.startOn(run.tower.world, g.runTuning(), run.startIdx);
        s.checkpoint = run.startIdx; s.keysFree = false; s.keys = 0; s.deferRespawn = true; s.floorOverride = run.tower.floorLocal(); s.setRange(0, run.tower.world.size() - 1);
        return s;
    }

    /** The sim exactly as PlayScreen.show builds it for CONTINUE (snapshot path); null if the snapshot does not apply. */
    static Sim resumedSim(ClimbGame g, ClimbGame.Run run) {
        Sim s = new Sim(run.tower.world, g.runTuning());
        s.keysFree = false; s.deferRespawn = true; s.floorOverride = run.tower.floorLocal(); s.setRange(0, run.tower.world.size() - 1);
        run.tower.openedUpTo = g.save.openedUpTo;
        ResumeState.restore(g.save, s, run.tower);
        return run.snapshot != null && run.snapshot.apply(s, run.tower) ? s : null;
    }

    /** One PlayScreen step: keepTower (admit 100 m / build 150 m ahead of the highest point), history sync, one fixed step, the run clock. */
    static double step(ClimbGame g, Tower tw, Sim s, InputState in, double maxAbs, int[] size) {
        tw.openedUpTo = g.save.openedUpTo; tw.admitAbs = maxAbs + 100.0; tw.ensureAbove(maxAbs, 150f); tw.maintain(s);
        if (tw.world.size() != size[0]) { s.setRange(0, tw.world.size() - 1); size[0] = tw.world.size(); }
        g.syncHistory(tw);
        s.step(in); s.consumeEvents();
        RunRecord.tick(g.save, Sim.DT, true);
        return Math.max(maxAbs, tw.absY(s.y));
    }

    /** Climbs with the live autopilot (the Driver PlayScreen's autopilot hook uses) for {@code steps} fixed steps. */
    static double climb(ClimbGame g, Tower tw, Sim s, int steps) {
        InputState in = new InputState(); int[] size = {tw.world.size()}; int seen = tw.rebuilds; double maxAbs = tw.absY(s.y);
        Autopilot.Driver d = new Autopilot.Driver(s);
        for (int i = 0; i < steps; i++) {
            d.drive(s, in);
            maxAbs = step(g, tw, s, in, maxAbs, size);
            if (tw.rebuilds != seen) { d.rebase(tw.lastRemap); seen = tw.rebuilds; }
            if (d.failed) d = new Autopilot.Driver(s);
        }
        return maxAbs;
    }

    /**
     * A snapshot's JSON without absS0: RunSnapshot.apply deliberately restarts the step history (ps0 = s, teleported = true; absS0 is used only to
     * sync moving elements), so a re-capture reports the current position there by design. Whether that changes anything is checked by the
     * step-for-step continuation in the save/reload test, not by this field.
     */
    static String comparable(RunSnapshot r) { double k = r.absS0; r.absS0 = 0; String j = r.toJson(); r.absS0 = k; return j; }

    /** Slices that have joined the resident window (Tower.sliceAtAbs clamps to the admitted range). */
    static int admittedCount(Tower tw) { return tw.sliceAtAbs(1e12) + 1; }

    // ------------------------------------------------------------------ 1  SAVE / RELOAD (runnable now)

    @Test public void saveReload_aSavedClimbReloadsWithIdenticalBytesStateCheckpointKeysProgressAndTime() throws Exception {
        Tuning t = TestUtil.tuning(); long seed = 4243;
        File dir = Files.createTempDirectory("recovery-save").toFile();
        ClimbGame g1 = game(dir, t);
        ClimbGame.Run run = open(g1, seed, true);
        Tower tw = run.tower; Sim s = freshSim(g1, run);
        climb(g1, tw, s, 60 * 45);                                           // 45 s of real climbing, through window rebuilds and a checkpoint or two
        // progress fixture (the 45 s climb reaches no castle): what a run with castle 1 opened and two keys held carries
        s.keys = (1 << 1) | (1 << 3); g1.save.openedUpTo = 1; g1.save.towers = 1; g1.save.splits = new float[]{123.5f}; g1.save.towerTotals = new float[]{123.5f};
        g1.saveRun(tw, s);                                                   // pause / SAVE & EXIT: history first, then run.json, then save.json
        RunSnapshot before = RunSnapshot.capture(s, tw, seed);
        assertNotNull("the state can be saved faithfully", before);
        float clock = g1.save.runClock; long ticks = g1.save.runTicks;

        // the app is killed; everything below comes from the files alone
        ClimbGame g2 = game(dir, t);
        ClimbGame.Run r2 = g2.openRun(false);
        assertTrue("the climb resumes", r2.resumed);
        assertNotNull("from its snapshot, not only the checkpoint", r2.snapshot);
        assertEquals("the original seed", seed, g2.save.seed);
        assertEquals("every stored slice comes back", tw.sliceCount(), r2.tower.sliceCount());
        for (int k = 0; k < tw.sliceCount(); k++) assertArrayEquals("slice " + k + " bytes", tw.blob(k), r2.tower.blob(k));
        Sim s2 = resumedSim(g2, r2);
        assertNotNull("the snapshot fits the world on disk", s2);
        RunSnapshot after = RunSnapshot.capture(s2, r2.tower, seed);
        assertEquals("position, velocity, timers, element and hazard states, done features, checkpoint", comparable(before), comparable(after));
        assertEquals("keys", s.keys, s2.keys);
        assertEquals("run clock", clock, g2.save.runClock, 0f); assertEquals(ticks, g2.save.runTicks);
        assertEquals("castles opened", 1, g2.save.openedUpTo); assertEquals(1, g2.save.towers);
        assertArrayEquals("splits", new float[]{123.5f}, g2.save.splits, 0f);

        // and the climb goes on identically: the same inputs from here give the same states (the resumed tower regenerates nothing it stored)
        InputState in = new InputState(); int[] z1 = {tw.world.size()}, z2 = {r2.tower.world.size()}; double m1 = before.maxAbs, m2 = after.maxAbs;
        for (int i = 0; i < 600; i++) {
            in.clear(); in.moveX = (i / 90) % 2 == 0 ? 1f : -1f; in.jumpPressed = i % 45 == 0; in.jumpHeld = i % 45 < 10;
            InputState inB = new InputState(); inB.moveX = in.moveX; inB.jumpPressed = in.jumpPressed; inB.jumpHeld = in.jumpHeld;
            m1 = step(g1, tw, s, in, m1, z1); m2 = step(g2, r2.tower, s2, inB, m2, z2);
            assertEquals("step " + i + " height", tw.absY(s.y), r2.tower.absY(s2.y), 1e-6);
            assertEquals("step " + i + " mode", s.mode, s2.mode);
        }
    }

    @Test public void saveReload_anAutosaveWrittenInTheBackgroundReloadsTheSameClimb() throws Exception {
        Tuning t = TestUtil.tuning(); long seed = 4244;
        File dir = Files.createTempDirectory("recovery-autosave").toFile();
        ClimbGame g1 = game(dir, t);
        ClimbGame.Run run = open(g1, seed, true);
        Tower tw = run.tower; Sim s = freshSim(g1, run);
        climb(g1, tw, s, 60 * 30);
        g1.autosave(tw, s);                                                  // queued writes (DiskWriter), in order
        RunSnapshot before = RunSnapshot.capture(s, tw, seed);
        DiskWriter.drain();                                                  // (a kill before the queue empties leaves the previous files: covered by BackgroundSaveTest)
        ClimbGame g2 = game(dir, t);
        ClimbGame.Run r2 = g2.openRun(false);
        assertTrue(r2.resumed); assertNotNull(r2.snapshot);
        List<byte[]> hist = new HistoryStore(dir).read(seed);
        assertEquals("one record per taken slice, no duplicates", tw.sliceCount(), hist.size());
        for (int k = 0; k < hist.size(); k++) assertArrayEquals(tw.blob(k), hist.get(k));
        Sim s2 = resumedSim(g2, r2);
        assertNotNull(s2);
        assertEquals(comparable(before), comparable(RunSnapshot.capture(s2, r2.tower, seed)));
        assertEquals(g1.save.runTicks, g2.save.runTicks);
    }

    // ------------------------------------------------------------------ C1  characterisation of today's persistence (runnable now; FLIPS with R2)

    /**
     * CURRENT BEHAVIOUR (not a requirement): ClimbGame.syncHistory appends every slice the tower has taken, and the tower takes a slice as soon as the
     * background thread finishes it - up to 150 m above the highest point, while admission stops at 100 m. So history.bin regularly holds slices the
     * player has never been admitted to. R2 (final-only persistence) must change this; when R2 lands, replace this test with
     * {@link #forcedBacktrack_theRejectedSliceIsNeverAdmittedResidentOrSaved}'s history assertions.
     */
    @Test public void characterisation_todayHistoryHoldsSlicesThePlayerWasNeverAdmittedTo() throws Exception {
        Tuning t = TestUtil.tuning(); long seed = 4245;
        File dir = Files.createTempDirectory("recovery-char").toFile();
        ClimbGame g = game(dir, t);
        ClimbGame.Run run = open(g, seed, true);
        Tower tw = run.tower; Sim s = freshSim(g, run);
        double maxAbs = 0; InputState in = new InputState(); int[] size = {tw.world.size()};
        int seenAhead = 0;
        for (int i = 0; i < 60 * 20; i++) {                                  // stand still: generation runs ahead, admission does not
            maxAbs = step(g, tw, s, in, maxAbs, size);
            if (i % 60 == 0) while (tw.generating()) Thread.sleep(20);          // let the background slice finish; the next step's maintain takes it
            seenAhead = Math.max(seenAhead, g.history.count() - admittedCount(tw));
        }
        assertTrue("history holds at least one slice beyond the admitted window (today's design; R2 changes this)", seenAhead >= 1);
    }

    // ------------------------------------------------------------------ 2  FORCED BACKTRACK (pending R2)

    @Ignore("PENDING R2 (one-slice lookahead + deterministic backtrack) and the test-only fault hook in CourseGenerator.chunkIn; see Seams")
    @Test public void forcedBacktrack_theRejectedSliceIsNeverAdmittedResidentOrSaved() throws Exception {
        Tuning t = TestUtil.tuning(); long seed = 4246; int K = 6;
        Tower ref = Seams.tower(seed, t, null);                                                   // no fault: today's course
        while (ref.sliceCount() < K + 3) ref.extend();
        int aK = Seams.acceptedAttempt(ref, K);
        Seams.Fault f = Seams.failAllAttemptsOf(K + 1).whenBuiltOn(K, aK);                        // K+1 cannot be built on K's first passing attempt
        File dir = Files.createTempDirectory("recovery-backtrack").toFile();
        ClimbGame g = game(dir, t);
        ClimbGame.Run run = Seams.openWithFault(g, seed, f);
        Tower tw = run.tower; Sim s = freshSim(g, run);
        climb(g, tw, s, 60 * 60 * 6);                                                              // climb well past K+1 (or grow the tower to K+3 if the climb is too slow)
        while (tw.sliceCount() < K + 3) tw.extend();
        assertTrue("K was rebuilt on a later attempt", Seams.acceptedAttempt(tw, K) > aK);
        for (int k = 0; k < K; k++) assertArrayEquals("slices below K are untouched", ref.blob(k), tw.blob(k));
        byte[] rejected = ref.blob(K);
        assertFalse("the final K is not the rejected K", java.util.Arrays.equals(rejected, tw.blob(K)));
        for (byte[] b : Seams.admittedLog(tw)) assertFalse("the rejected K was never admitted", java.util.Arrays.equals(rejected, b));
        for (byte[] b : Seams.residentLog(tw)) assertFalse("nor resident", java.util.Arrays.equals(rejected, b));
        g.saveRun(tw, s); DiskWriter.drain();
        for (byte[] b : new HistoryStore(dir).read(seed)) assertFalse("nor saved", java.util.Arrays.equals(rejected, b));
        assertTrue("K was admitted only after K+1 existed", Seams.admittedAfterSuccessor(tw, K));
        assertEquals("seed unchanged", seed, g.save.seed);
        Tower again = Seams.tower(seed, t, f); while (again.sliceCount() < tw.sliceCount()) again.extend();
        for (int k = 0; k < tw.sliceCount(); k++) assertArrayEquals("an independent instance builds the same recovered course", tw.blob(k), again.blob(k));
        assertEquals("the difficulty is kept", t.hard, Seams.tuningOf(tw).hard);
    }

    // ------------------------------------------------------------------ 3  SAVE DURING RECOVERY (pending R2)

    @Ignore("PENDING R2 (final-only history, provisional tail, resume regenerates the tail) and the fault hook; see Seams")
    @Test public void saveDuringRecovery_interruptAndResumeWhileKIsProvisional() throws Exception {
        Tuning t = TestUtil.tuning(); long seed = 4247; int K = 6;
        Seams.Fault f = Seams.failAllAttemptsOf(K + 1).whenBuiltOn(K, 0).slowly(2_000);             // K is provisional for a while: its successor fails first and is slow
        File dir = Files.createTempDirectory("recovery-midsave").toFile();
        ClimbGame g = game(dir, t);
        ClimbGame.Run run = Seams.openWithFault(g, seed, f);
        Tower tw = run.tower; Sim s = freshSim(g, run);
        Seams.climbUntilProvisional(g, tw, s, K);                                                    // K taken, K+1 not yet found
        int finalCount = Seams.finalSliceCount(tw);
        assertEquals("K is the provisional tail", K, finalCount);
        g.autosave(tw, s);                                                                           // queued writes...
        g.saveRun(tw, s);                                                                            // ...then a direct save (drains first)
        RunSnapshot snap = RunSnapshot.capture(s, tw, seed);
        File copy = Seams.copyDir(dir);                                                              // the app is killed here
        List<byte[]> hist = new HistoryStore(copy).read(seed);
        assertEquals("only final slices are on disk", finalCount, hist.size());
        for (int k = 0; k < hist.size(); k++) assertArrayEquals("in order, no duplicates", tw.blob(k), hist.get(k));
        assertTrue("the snapshot refers only to final slices", snap.maxSlice < finalCount);

        ClimbGame g2 = game(copy, t);
        ClimbGame.Run r2 = Seams.resumeWithFault(g2, f);
        assertTrue(r2.resumed); assertEquals(seed, g2.save.seed);
        Sim s2 = resumedSim(g2, r2); assertNotNull(s2);
        assertEquals("player, checkpoint, keys, timers", comparable(snap), comparable(RunSnapshot.capture(s2, r2.tower, seed)));
        assertEquals("run clock", g.save.runTicks, g2.save.runTicks);
        while (r2.tower.sliceCount() < K + 3) r2.tower.extend();
        while (tw.sliceCount() < K + 3) tw.extend();
        for (int k = 0; k < K + 3; k++) assertArrayEquals("the tail regenerates exactly as the uninterrupted climb's", tw.blob(k), r2.tower.blob(k));
        g2.saveRun(r2.tower, s2); DiskWriter.drain();
        List<byte[]> h2 = new HistoryStore(copy).read(seed);
        assertEquals("no record twice", Seams.finalSliceCount(r2.tower), h2.size());

        // a torn record at the end of the file (kill during the write) is ignored and the tail regenerates
        File torn = Seams.copyDir(dir);
        try (FileOutputStream o = new FileOutputStream(new File(torn, "history.bin"), true)) { o.write(new byte[]{0, 0, 3, (byte) 0xE8, 1, 2, 3}); }
        ClimbGame g3 = game(torn, t);
        ClimbGame.Run r3 = Seams.resumeWithFault(g3, f);
        assertTrue(r3.resumed);
        while (r3.tower.sliceCount() < K + 3) r3.tower.extend();
        for (int k = 0; k < K + 3; k++) assertArrayEquals(tw.blob(k), r3.tower.blob(k));
        g3.saveRun(r3.tower, resumedSim(g3, r3)); DiskWriter.drain();
        assertEquals("the torn bytes are repaired, not built upon", Seams.finalSliceCount(r3.tower), new HistoryStore(torn).read(seed).size());
    }

    // ------------------------------------------------------------------ 5  ALL RECOVERY FAILS (pending R1)

    /**
     * Expected Level 5 behaviour (owner decision A in the audit chooses what the player sees; these are the invariants whatever is chosen):
     * the tower reports "cannot grow" instead of throwing; the climber keeps the proven course already admitted; nothing is archived or deleted;
     * save.seed, history.bin (final slices), run.json and the clock are exactly what they were; a failure record (seed, difficulty, rules, slice,
     * refusal classes) is written; reopening the game resumes the same climb at the same state; nothing unverified is ever admitted.
     */
    @Ignore("PENDING R1 (no deletion on generation failure, cannot-grow state, failure record) + R2 + the fault hook; see Seams")
    @Test public void allRecoveryFails_theClimbStaysRecoverableAndIsNeverDeleted() throws Exception {
        Tuning t = TestUtil.tuning(); long seed = 4248; int K = 6;
        Seams.Fault f = Seams.failAllAttemptsOf(K + 1).always();                                     // no K can be followed: backtracking exhausts too
        File dir = Files.createTempDirectory("recovery-level5").toFile();
        ClimbGame g = game(dir, t);
        ClimbGame.Run run = Seams.openWithFault(g, seed, f);
        Tower tw = run.tower; Sim s = freshSim(g, run);
        climb(g, tw, s, 60 * 60 * 4);                                                                 // must not throw
        assertTrue("the tower knows it cannot grow", Seams.cannotGrow(tw));
        assertTrue("nothing beyond the final slices was admitted", admittedCount(tw) <= Seams.finalSliceCount(tw));
        assertNotNull("a failure record was written", Seams.failureRecord(dir));
        g.saveRun(tw, s); DiskWriter.drain();
        RunSnapshot snap = RunSnapshot.capture(s, tw, seed);
        assertTrue("history.bin is not deleted", new File(dir, "history.bin").exists());
        assertEquals("the seed is not changed or cleared", seed, g.save.seed);
        ClimbGame g2 = game(dir, t);
        ClimbGame.Run r2 = g2.openRun(false);
        assertTrue("the climb is still there", r2.resumed);
        Sim s2 = resumedSim(g2, r2); assertNotNull(s2);
        assertEquals(comparable(snap), comparable(RunSnapshot.capture(s2, r2.tower, seed)));
        assertEquals(g.save.runTicks, g2.save.runTicks);
    }

    // ------------------------------------------------------------------ H1, H2  history-file findings (pending; each fails on today's code)

    /**
     * FINDING H1 (this preparation): after a record cut short by a failed or interrupted write, HistoryStore keeps appending AFTER the broken bytes.
     * read() stops at the broken record, so every slice written later is invisible on resume; with the checkpoint in one of those slices the climb
     * cannot be resumed at all (openRun falls through to a new climb, finding H2). Verified failing on 093b6bb (see the preparation report).
     */
    @Ignore("PENDING history repair (truncate to the last whole record before appending; recommended with R2/R4). Fails on 093b6bb")
    @Test public void historyH1_aTornRecordDoesNotHideEverySliceWrittenAfterIt() throws Exception {
        File dir = Files.createTempDirectory("recovery-torn").toFile();
        HistoryStore h = new HistoryStore(dir); h.reset(9);
        h.append(new byte[]{0}); h.append(new byte[]{1});
        try (FileOutputStream o = new FileOutputStream(new File(dir, "history.bin"), true)) { o.write(new byte[]{0, 0, 0, 10, 7, 7}); }   // a record cut short (10 bytes announced, 2 written)
        HistoryStore h2 = new HistoryStore(dir);
        assertEquals("the two whole records", 2, h2.read(9).size());
        h2.append(new byte[]{2}); h2.append(new byte[]{3});
        List<byte[]> back = new HistoryStore(dir).read(9);
        assertEquals("records appended after a torn one are readable", 4, back.size());
        for (int i = 0; i < 4; i++) assertEquals(i, back.get(i)[0]);
    }

    /**
     * FINDING H2 (audit section 3G, confirmed here): a history that cannot be read on CONTINUE makes openRun start a new climb, whose history.reset
     * overwrites the old file. The expected behaviour (R4): the old climb's files are kept (renamed aside), it is archived, and the player is told.
     */
    @Ignore("PENDING R4 (never silently replace an unreadable saved climb). Fails on 093b6bb")
    @Test public void historyH2_anUnreadableHistoryIsNeverSilentlyReplaced() throws Exception {
        Tuning t = TestUtil.tuning(); long seed = 4249;
        File dir = Files.createTempDirectory("recovery-unreadable").toFile();
        ClimbGame g1 = game(dir, t);
        ClimbGame.Run run = open(g1, seed, true);
        while (run.tower.sliceCount() < 3) run.tower.extend();
        g1.syncHistory(run.tower);
        g1.save.cpSlice = 2; g1.save.cpLocal = 1; g1.store.saveGame(g1.save);
        byte[] all = Files.readAllBytes(new File(dir, "history.bin").toPath());
        all[16] ^= 0x5A;                                                                             // one damaged byte inside the first slice's deflate stream
        Files.write(new File(dir, "history.bin").toPath(), all);
        ClimbGame g2 = game(dir, t);
        ClimbGame.Run r2 = g2.openRun(false);
        assertFalse("not resumable", r2.resumed);
        assertEquals("but the old climb is not replaced by a new seed", seed, g2.save.seed);
        File[] kept = dir.listFiles((d, n) -> n.startsWith("history") && !n.equals("history.bin"));
        assertTrue("the damaged history is kept aside", kept != null && kept.length > 0);
    }

    // ------------------------------------------------------------------ the seams R1/R2 must provide (test-side adapter; throws until they exist)

    /**
     * Test-side adapter for production seams that do not exist yet. Each method documents the seam it needs; until it is wired, it throws, so a
     * pending test can never pass by accident. When R1/R2 land, implement these against the real hooks and remove the @Ignore annotations above.
     */
    static final class Seams {
        private Seams() {}
        static RuntimeException pending(String what) { return new UnsupportedOperationException("PENDING seam: " + what); }

        /** A fault plan for CourseGenerator.chunkIn (test-only, inert unless set): refuse every attempt of slice {@code k} under a condition. */
        static final class Fault {
            Fault whenBuiltOn(int prevSlice, int prevAttempt) { throw pending("Fault.whenBuiltOn: refuse slice k only when slice k-1 is its attempt a"); }
            Fault always() { throw pending("Fault.always: refuse slice k whatever slice k-1 is (forces Level 5)"); }
            Fault slowly(long millisPerAttempt) { throw pending("Fault.slowly: make each attempt of slice k take this long (provisional window)"); }
        }
        static Fault failAllAttemptsOf(int k) { throw pending("CourseGenerator fault hook (package-private, test-only)"); }
        /** A tower whose generator consults {@code f} (null: none). Needs the Tower generator seam. */
        static Tower tower(long seed, Tuning t, Fault f) { throw pending("Tower generator seam"); }
        static ClimbGame.Run openWithFault(ClimbGame g, long seed, Fault f) { throw pending("ClimbGame.openRun with the Tower generator seam"); }
        static ClimbGame.Run resumeWithFault(ClimbGame g, Fault f) { throw pending("ClimbGame.openRun(false) with the Tower generator seam"); }
        /** The attempt index slice k was accepted on (R2 records it; it is also the backtrack's continuation point). */
        static int acceptedAttempt(Tower tw, int k) { throw pending("Tower.acceptedAttempt"); }
        /** Slices that are final (their successor exists); history holds exactly these after R2. */
        static int finalSliceCount(Tower tw) { throw pending("Tower.finalCount"); }
        /** Blobs of every slice at the moment it was admitted / stitched resident (test log). */
        static List<byte[]> admittedLog(Tower tw) { throw pending("Tower admission log (test-only)"); }
        static List<byte[]> residentLog(Tower tw) { throw pending("Tower residency log (test-only)"); }
        static boolean admittedAfterSuccessor(Tower tw, int k) { throw pending("Tower admission log with successor timestamps"); }
        static boolean cannotGrow(Tower tw) { throw pending("Tower cannot-grow state (R1)"); }
        static File failureRecord(File dir) { throw pending("generation failure record file (R1)"); }
        static Tuning tuningOf(Tower tw) { return tw.T; }
        static void climbUntilProvisional(ClimbGame g, Tower tw, Sim s, int k) { throw pending("climb until slice k is taken and k+1 not yet found (needs finalCount)"); }
        static File copyDir(File dir) throws java.io.IOException {
            File out = Files.createTempDirectory("recovery-copy").toFile();
            File[] fs = dir.listFiles(); if (fs != null) for (File f : fs) if (f.isFile()) Files.copy(f.toPath(), new File(out, f.getName()).toPath());
            return out;
        }
    }
}
