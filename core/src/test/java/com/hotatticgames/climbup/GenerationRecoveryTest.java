package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
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
 * R1-R6 implemented (feature/generation-recovery, 2026-10-28): the scenarios below that were pending in PR #11 now run against the real hooks
 * (CourseGenerator.FAULT, Tower.acceptedAttempt / onStitch / failure / waiting). H1 and H2 moved to SaveHistoryRepairTest (Track 2 owns them).
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

    @After public void noFault() { CourseGenerator.FAULT = null; }

    /** A shift-invariant signature of a slice (a slice is shifted into the next one's frame before that one is built from it). */
    static String sig(Course c) {
        StringBuilder b = new StringBuilder().append(c.size()).append('/').append(c.routeSize()).append('/').append(c.hazards.size());
        for (int i = 0; i < c.size(); i++) b.append(',').append(c.get(i).type.ordinal()).append(':').append(Float.floatToIntBits(c.get(i).w));
        return b.toString();
    }
    /** Refuses every attempt of slice {@code k} when it would be built on the slice whose bytes are {@code onBlob}. */
    static CourseGenerator.FaultPlan refuseOn(int k, byte[] onBlob) {
        final String want = sig(SliceCodec.decode(onBlob).c);
        return (seed, kk, attempt, prev) -> kk == k && prev != null && sig(prev).equals(want);
    }
    static int historyCount(File dir, long seed) { List<byte[]> h = new HistoryStore(dir).read(seed); return h == null ? -1 : h.size(); }

    // ------------------------------------------------------------------ C1  no provisional slice is ever stored (this flipped with R2)

    /** R2: history.bin holds exactly the final slices (a final slice may still be above the window; a provisional one is never stored). */
    @Test public void finalOnly_historyNeverHoldsAProvisionalSlice() throws Exception {
        Tuning t = TestUtil.tuning(); long seed = 4245;
        File dir = Files.createTempDirectory("recovery-finalonly").toFile();
        ClimbGame g = game(dir, t);
        ClimbGame.Run run = open(g, seed, true);
        Tower tw = run.tower; tw.blockingAdmit = false; Sim s = freshSim(g, run);
        double maxAbs = 0; InputState in = new InputState(); int[] size = {tw.world.size()};
        for (int i = 0; i < 60 * 20; i++) {
            maxAbs = step(g, tw, s, in, maxAbs, size);
            if (i % 60 == 0) { while (tw.generating()) Thread.sleep(20); tw.poll(); s.ensureCapacity(); g.syncHistory(tw); }
            assertEquals("history = the final slices", tw.sliceCount(), g.history.count());
        }
        DiskWriter.drain();
        assertEquals(tw.sliceCount(), historyCount(dir, seed));
        for (int k = 0; k < tw.sliceCount(); k++) assertArrayEquals(tw.blob(k), new HistoryStore(dir).read(seed).get(k));
    }

    // ------------------------------------------------------------------ 2  FORCED BACKTRACK

    @Test public void forcedBacktrack_theRejectedSliceIsNeverAdmittedResidentOrSaved() throws Exception {
        Tuning t = TestUtil.tuning(); long seed = 4246; int K = 3;
        Tower ref = new Tower(seed, t);
        while (ref.sliceCount() < K + 3) ref.extend();
        int aK = ref.acceptedAttempt(K); byte[] rejected = ref.blob(K);
        CourseGenerator.FAULT = refuseOn(K + 1, rejected);                                        // K+1 cannot be built on K's first passing attempt
        File dir = Files.createTempDirectory("recovery-backtrack").toFile();
        ClimbGame g = game(dir, t);
        ClimbGame.Run run = open(g, seed, true);
        Tower tw = run.tower;
        List<byte[]> stitched = new ArrayList<>(); tw.onStitch = (k, b) -> stitched.add(b);
        for (int k = 0; k < tw.sliceCount(); k++) stitched.add(tw.blob(k));                    // (what joined before the hook was set)
        while (tw.sliceCount() < K + 3) { tw.extend(); g.syncHistory(tw); }
        assertTrue("K was rebuilt on a later attempt (" + aK + " -> " + tw.acceptedAttempt(K) + ")", tw.acceptedAttempt(K) > aK);
        assertTrue("a backtrack happened", tw.backtracks >= 1);
        for (int k = 0; k < K; k++) assertArrayEquals("slices below K are untouched", ref.blob(k), tw.blob(k));
        assertFalse("the final K is not the rejected K", java.util.Arrays.equals(rejected, tw.blob(K)));
        for (byte[] b : stitched) assertFalse("the rejected K never joined the world", java.util.Arrays.equals(rejected, b));
        DiskWriter.drain();
        for (byte[] b : new HistoryStore(dir).read(seed)) assertFalse("nor was it saved", java.util.Arrays.equals(rejected, b));
        assertEquals("seed unchanged", seed, g.save.seed);
        Tower again = new Tower(seed, t); while (again.sliceCount() < tw.sliceCount()) again.extend();
        for (int k = 0; k < tw.sliceCount(); k++) assertArrayEquals("an independent instance builds the same recovered course", tw.blob(k), again.blob(k));
        assertEquals("the difficulty is kept", t.hard, tw.T.hard);
    }

    /** R2's promise: when nothing has to be undone, the course is exactly the course the old one-slice-at-a-time tower built. */
    @Test public void withoutAFailureTheCourseIsUnchanged() throws Exception {
        Tuning t = TestUtil.tuning(); long seed = 4253;
        Tower tw = new Tower(seed, t); while (tw.sliceCount() < 6) tw.extend();
        CourseGenerator.Framed prev = null;
        for (int k = 0; k < 6; k++) {
            CourseGenerator.Framed f = CourseGenerator.chunkFramed(seed, k, prev, t); prev = f;
            assertArrayEquals("slice " + k, SliceCodec.encode(f), tw.blob(k));
        }
        assertEquals(0, tw.backtracks);
    }

    // ------------------------------------------------------------------ 3  SAVE DURING RECOVERY

    @Test public void saveDuringRecovery_interruptAndResumeWhileKIsProvisional() throws Exception {
        Tuning t = TestUtil.tuning(); long seed = 4247; int K = 3;
        Tower ref = new Tower(seed, t); while (ref.sliceCount() < K + 3) ref.extend();
        CourseGenerator.FAULT = refuseOn(K + 1, ref.blob(K));                                    // K will need a backtrack once K+1 is tried
        File dir = Files.createTempDirectory("recovery-midsave").toFile();
        ClimbGame g = game(dir, t);
        ClimbGame.Run run = open(g, seed, true);
        Tower tw = run.tower; Sim s = freshSim(g, run);
        while (tw.sliceCount() < K) tw.extend();                                                   // K is the provisional slice now (built, not final)
        s.ensureCapacity(); s.setRange(0, tw.world.size() - 1);                                    // (as the game's keepTower does after the world grows)
        g.autosave(tw, s);                                                                         // queued writes...
        g.saveRun(tw, s);                                                                          // ...then a direct save (drains first)
        RunSnapshot snap = RunSnapshot.capture(s, tw, seed);
        File copy = copyDir(dir);                                                                  // the app is killed here
        List<byte[]> hist = new HistoryStore(copy).read(seed);
        assertEquals("only final slices are on disk", K, hist.size());
        for (int k = 0; k < K; k++) assertArrayEquals("in order, no duplicates", tw.blob(k), hist.get(k));
        assertTrue("the snapshot refers only to final slices", snap.maxSlice < K);

        ClimbGame g2 = game(copy, t);
        ClimbGame.Run r2 = g2.openRun(false);
        assertTrue(r2.resumed); assertEquals(seed, g2.save.seed);
        Sim s2 = resumedSim(g2, r2); assertNotNull(s2);
        assertEquals("player, checkpoint, keys, timers", comparable(snap), comparable(RunSnapshot.capture(s2, r2.tower, seed)));
        assertEquals("run clock", g.save.runTicks, g2.save.runTicks);
        while (r2.tower.sliceCount() < K + 3) r2.tower.extend();
        while (tw.sliceCount() < K + 3) tw.extend();
        for (int k = 0; k < K + 3; k++) assertArrayEquals("the tail is made again exactly as the uninterrupted climb's", tw.blob(k), r2.tower.blob(k));
        s2.ensureCapacity(); s2.setRange(0, r2.tower.world.size() - 1);                            // (as the game's keepTower does after the world grows)
        g2.saveRun(r2.tower, s2); DiskWriter.drain();
        assertEquals("no record twice", r2.tower.sliceCount(), historyCount(copy, seed));

        // a torn record at the end of the file (kill during the write): ignored, cut before the next append (H1), and the tail made again
        File torn = copyDir(dir);
        try (FileOutputStream o = new FileOutputStream(new File(torn, "history.bin"), true)) { o.write(new byte[]{0, 0, 3, (byte) 0xE8, 1, 2, 3}); }
        ClimbGame g3 = game(torn, t);
        ClimbGame.Run r3 = g3.openRun(false);
        assertTrue(r3.resumed);
        while (r3.tower.sliceCount() < K + 3) r3.tower.extend();
        for (int k = 0; k < K + 3; k++) assertArrayEquals(tw.blob(k), r3.tower.blob(k));
        g3.syncHistory(r3.tower); DiskWriter.drain();
        List<byte[]> h3 = new HistoryStore(torn).read(seed);
        assertEquals("the torn bytes are cut, not built upon", r3.tower.sliceCount(), h3.size());
        for (int k = 0; k < h3.size(); k++) assertArrayEquals(r3.tower.blob(k), h3.get(k));
    }

    // ------------------------------------------------------------------ R3  holding instead of blocking

    /** The game's tower (blockingAdmit false) never makes a slice on the game thread: it holds, and the climb that holds is step for step the climb that did not. */
    @Test public void aSlowGeneratorHoldsTheClimbWithoutChangingIt() throws Exception {
        Tuning t = TestUtil.tuning(); long seed = 4254;
        double[] fast = holdRun(t, seed, 0), slow = holdRun(t, seed, 60);
        assertArrayEquals("the same climb whatever the generator's speed", fast, slow, 0.0);
    }
    static double[] holdRun(Tuning t, long seed, long sleepMs) throws Exception {
        CourseGenerator.FAULT = sleepMs <= 0 ? null : (sd, k, attempt, prev) -> { if (k >= 2) try { Thread.sleep(sleepMs); } catch (InterruptedException e) { } return false; };
        try {
            Tower tw = new Tower(seed, t); tw.blockingAdmit = false;
            Sim s = Sim.startOn(tw.world, t, 0); s.deferRespawn = true; s.keysFree = true; s.floorOverride = tw.floorLocal(); s.setRange(0, tw.world.size() - 1);
            InputState in = new InputState(); int size = tw.world.size(); double maxAbs = 0; int holds = 0;
            double[] out = new double[900];
            for (int n = 0; n < out.length; ) {
                tw.admitAbs = maxAbs + 100.0; tw.ensureAbove(maxAbs, 150f); tw.maintain(s);
                if (tw.world.size() != size) { s.setRange(0, tw.world.size() - 1); size = tw.world.size(); }
                if (tw.waiting()) { holds++; Thread.sleep(5); continue; }                         // the hold: no step taken
                in.clear(); in.moveX = 1f; in.jumpPressed = n % 50 == 0; in.jumpHeld = n % 50 < 14;
                s.step(in); s.consumeEvents(); maxAbs = Math.max(maxAbs, tw.absY(s.y)); out[n++] = tw.absY(s.y) * 1000.0 + s.mode.ordinal();
                if (holds > 200000) fail("held for ever");
            }
            return out;
        } finally { CourseGenerator.FAULT = null; }
    }

    // ------------------------------------------------------------------ 5  ALL RECOVERY FAILS (R1)

    @Test public void allRecoveryFails_theClimbStaysRecoverableAndIsNeverDeleted() throws Exception {
        Tuning t = TestUtil.tuning(); long seed = 4248; int K = 3;
        CourseGenerator.FAULT = (sd, k, attempt, prev) -> k == K + 1;                             // no slice K can ever be followed
        File dir = Files.createTempDirectory("recovery-level5").toFile();
        ClimbGame g = game(dir, t);
        ClimbGame.Run run = open(g, seed, true);
        Tower tw = run.tower; tw.blockingAdmit = false; Sim s = freshSim(g, run);
        long deadline = System.currentTimeMillis() + 40L * 60_000L;                                  // every backtrack builds slice K again for real (the generator and its whole-slice witness), eight of them
        while (tw.failure() == null && System.currentTimeMillis() < deadline) { tw.admitAbs = 1e6; tw.ensureAbove(1e6, 150f); tw.maintain(s); s.ensureCapacity(); s.setRange(0, tw.world.size() - 1); Thread.sleep(tw.generating() ? 50 : 1); }
        assertNotNull("the tower knows it cannot grow", tw.failure());
        assertTrue("and the game would hold, not throw", tw.waiting());
        assertEquals("everything final is kept: slices 0.." + (K - 1), K, tw.sliceCount());
        g.syncHistory(tw); g.recordGenerationFailure(tw.failure());
        g.saveRun(tw, s); DiskWriter.drain();
        RunSnapshot snap = RunSnapshot.capture(s, tw, seed);
        assertTrue("history.bin is not deleted", new File(dir, "history.bin").exists());
        assertEquals(K, historyCount(dir, seed));
        assertTrue("a failure record was written", new File(dir, "generation_failures.txt").length() > 0);
        assertEquals("the seed is not changed or cleared", seed, g.save.seed);
        ClimbGame g2 = game(dir, t);
        ClimbGame.Run r2 = g2.openRun(false);
        assertTrue("the climb is still there", r2.resumed);
        Sim s2 = resumedSim(g2, r2); assertNotNull(s2);
        assertEquals(comparable(snap), comparable(RunSnapshot.capture(s2, r2.tower, seed)));
        assertEquals(g.save.runTicks, g2.save.runTicks);
    }

    // ------------------------------------------------------------------ R6  the first slice

    @Test public void firstSliceFailure_aTypedCodeIsRefusedARandomClimbDrawsAnother() throws Exception {
        Tuning t = TestUtil.tuning(); final long bad = 4255;
        CourseGenerator.FAULT = (sd, k, attempt, prev) -> sd == bad && k == 1;                    // slice 0 of this seed can never be final
        try { new Tower(bad, t); fail("expected FirstSliceFailed"); } catch (Tower.FirstSliceFailed e) { /* never a crash elsewhere, never another seed */ }
        File dir = Files.createTempDirectory("recovery-first").toFile();
        ClimbGame g = game(dir, t);
        try { open(g, bad, true); fail("a typed code is refused"); } catch (Tower.FirstSliceFailed e) { assertNotNull(g.saveNotice); assertTrue(g.saveNotice.contains(SeedCode.encode(bad))); }
        assertTrue("recorded", new File(dir, "generation_failures.txt").length() > 0);
    }

    // ------------------------------------------------------------------ R5  rules gate on resume

    @Test public void aClimbResumedOnOtherRulesSetsNoRecordAndSaysSo() throws Exception {
        Tuning t = TestUtil.tuning(); long seed = 4256;
        File dir = Files.createTempDirectory("recovery-rules").toFile();
        ClimbGame g = game(dir, t); ClimbGame.Run run = open(g, seed, true);
        g.syncHistory(run.tower); g.save.climbRules = CourseGenerator.RULES_VERSION - 1; g.store.saveGame(g.save);
        ClimbGame g2 = game(dir, t); ClimbGame.Run r2 = g2.openRun(false);
        assertTrue("it resumes", r2.resumed);
        assertTrue("marked not comparable", g2.save.climbNotComparable);
        assertNotNull("and the player is told", g2.saveNotice);
        ClimbGame g3 = game(dir, t); g3.save.climbRules = CourseGenerator.RULES_VERSION; g3.save.climbNotComparable = false; g3.save.climbTuning = ClimbGame.tuningStamp(t); g3.store.saveGame(g3.save);
        ClimbGame g4 = game(dir, t); g4.openRun(false);
        assertFalse("the same rules and tuning: comparable", g4.save.climbNotComparable);
    }

    static File copyDir(File dir) throws java.io.IOException {
        File out = Files.createTempDirectory("recovery-copy").toFile();
        File[] fs = dir.listFiles(); if (fs != null) for (File f : fs) if (f.isFile()) Files.copy(f.toPath(), new File(out, f.getName()).toPath());
        return out;
    }
}
