package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.io.File;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Test;

/**
 * Build 82 independent code review (2026-10-30), findings 1, 3, 6, 7 and 8: regression tests for the repairs on repair/build-82-review. Headless:
 * the play screen needs a GL context, so the climb start is tested where the repair puts it (ClimbGame / ClimbStart's logic).
 */
public class Build82ReviewTest {
    @After public void noFault() { CourseGenerator.FAULT = null; }

    private static ClimbGame.Run waitForFreshRun(ClimbGame g) throws InterruptedException {
        long until = System.nanoTime() + 1_800_000_000_000L;          // a refused first slice tries every attempt and backtrack first (minutes on a slow machine)
        while (System.nanoTime() < until) { ClimbGame.Run r = g.takeFreshRunIfReady(); if (r != null) return r; Thread.sleep(20); }
        fail("the prepared climb never became ready"); return null;
    }

    /** Findings 1 and 6: a fresh climb is built off the frame; asking for it never generates on the caller's thread, and the climb is exactly the one {@code new Tower} makes. */
    @Test public void aFreshClimbIsPreparedOffTheFrameAndIsTheSameCourse() throws Exception {
        Tuning t = TestUtil.tuning(); long seed = 8201;
        ClimbGame g = GenerationRecoveryTest.game(Files.createTempDirectory("b82-fresh").toFile(), t);
        g.beginFreshRun(seed); g.prepareFreshRun();
        long t0 = System.nanoTime(); ClimbGame.Run early = g.takeFreshRunIfReady(); long asked = System.nanoTime() - t0;
        if (early == null) assertTrue("not ready: answered at once (" + asked / 1e6 + " ms), nothing generated on this thread", asked < 200_000_000L);
        ClimbGame.Run r = early != null ? early : waitForFreshRun(g);
        assertEquals(seed, r.tower.seed);
        assertEquals(seed, g.save.seed);
        assertFalse("no longer pending", g.runFresh);
        assertArrayEquals("the prepared climb is the course new Tower makes", new Tower(seed, t).blob(0), r.tower.blob(0));
    }

    /** Findings 1 and 6 (stale results): the selection changes while a climb is being prepared - another seed, then a reloaded tuning - and the
     *  climb that opens is exactly the latest selection's, never the earlier request's. */
    @Test public void aChangedSelectionNeverTakesAStalePreparedClimb() throws Exception {
        Tuning t = TestUtil.tuning(); long first = 8205, second = 8206;
        ClimbGame g = GenerationRecoveryTest.game(Files.createTempDirectory("b82-stale").toFile(), t);
        g.beginFreshRun(first); g.prepareFreshRun();
        g.beginFreshRun(second);          // the player went back and typed another code before the first was ready
        ClimbGame.Run r = waitForFreshRun(g);
        assertEquals("the latest selection", second, r.tower.seed);
        assertArrayEquals(new Tower(second, t).blob(0), r.tower.blob(0));
        Tuning reloaded = TestUtil.tuning();          // a tuning reload (new instance, same values) between request and start
        g.beginFreshRun(first); g.prepareFreshRun(); g.tuning = reloaded;
        ClimbGame.Run r2 = waitForFreshRun(g);
        assertEquals(first, r2.tower.seed);
        assertArrayEquals("made with the tuning in force when it starts", new Tower(first, reloaded).blob(0), r2.tower.blob(0));
        assertEquals(ClimbGame.tuningStamp(reloaded), g.save.climbTuning);
    }

    /** Finding 1: a typed course code that cannot be built is refused before any play screen exists, with its notice, never swapped for another seed. */
    @Test public void anUnbuildableTypedCodeIsRefusedBeforeThePlayScreen() throws Exception {
        Tuning t = TestUtil.tuning(); final long bad = 8202;
        CourseGenerator.FAULT = (sd, k, attempt, prev) -> sd == bad && k == 1;
        ClimbGame g = GenerationRecoveryTest.game(Files.createTempDirectory("b82-refused").toFile(), t);
        g.beginFreshRun(bad); g.prepareFreshRun();
        try { waitForFreshRun(g); fail("expected FirstSliceFailed"); }
        catch (Tower.FirstSliceFailed e) { assertNotNull(g.saveNotice); assertTrue(g.saveNotice, g.saveNotice.contains(SeedCode.encode(bad))); }
    }

    /** Finding 7: the failure record names the climb that failed (its seed, difficulty and tuning), not the climb saved before it. */
    @Test public void aFirstSliceFailureRecordsTheFailedClimbsOwnSeedAndTuning() throws Exception {
        Tuning t = TestUtil.tuning(); final long bad = 8203, old = 8299;
        CourseGenerator.FAULT = (sd, k, attempt, prev) -> sd == bad && k == 1;
        File dir = Files.createTempDirectory("b82-record").toFile();
        ClimbGame g = GenerationRecoveryTest.game(dir, t);
        g.save.seed = old; g.save.difficulty = 1;          // what the save held before the new climb was asked for
        try { GenerationRecoveryTest.open(g, bad, true); fail("expected FirstSliceFailed"); } catch (Tower.FirstSliceFailed e) { /* refused */ }
        String line = new String(Files.readAllBytes(new File(dir, "generation_failures.txt").toPath()));
        assertTrue(line, line.contains(" seed=" + bad + " "));
        assertTrue(line, line.contains(" difficulty=" + g.newClimbTuning().hard + " "));
        assertTrue(line, line.contains(" tuning=" + Integer.toHexString(ClimbGame.tuningStamp(g.newClimbTuning())) + " "));
        assertFalse(line, line.contains("seed=" + old));
    }

    /** Finding 3: a mixed-rules climb (R5) sets no tower, pace or finish best, exactly as an assisted one; a comparable NORMAL climb still does. */
    @Test public void aMixedRulesClimbSetsNoPersonalBest() {
        SaveData sd = new SaveData();
        sd.climbNotComparable = true; sd.runClock = 100f;
        assertFalse(RunRecord.setsRecords(sd));
        assertEquals("no tower or pace best", 0, RunRecord.towerBests(sd, 0, 50f, 100f));
        assertEquals(0f, sd.bestSplit, 0f); assertEquals(0f, sd.bestTotals[0], 0f);
        assertEquals("finishes, but not a record", 0, RunRecord.complete(sd));
        assertEquals(0f, sd.bestFinish, 0f);
        SaveData ok = new SaveData(); ok.runClock = 100f;
        assertTrue(RunRecord.setsRecords(ok));
        assertEquals("both bests on a comparable climb", 3, RunRecord.towerBests(ok, 0, 50f, 100f));
        assertEquals(1, RunRecord.complete(ok)); assertEquals(100f, ok.bestFinish, 0f);
        SaveData assisted = new SaveData(); assisted.assisted = true; assisted.runClock = 100f;
        assertEquals("assisted: unchanged rule", 0, RunRecord.towerBests(assisted, 0, 50f, 100f));
    }

    /** Finding 8: the tuning stamp is the same on every runtime: pinned here, and it changes when any one tuning value does. */
    @Test public void theTuningStampIsRuntimeIndependentAndCoversEveryField() throws Exception {
        Tuning t = TestUtil.tuning();
        int s = ClimbGame.tuningStamp(t);
        assertEquals("stable for the same values", s, ClimbGame.tuningStamp(TestUtil.tuning()));
        assertEquals("the bundled NORMAL tuning (pin: independent of reflection order, so the same on HotSpot, Android and native builds)", STAMP_NORMAL, s);
        for (java.lang.reflect.Field f : Tuning.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            Tuning u = TestUtil.tuning();
            if (f.getType() == float.class) f.setFloat(u, f.getFloat(u) + 0.125f); else if (f.getType() == int.class) f.setInt(u, f.getInt(u) + 1); else continue;
            assertNotEquals("the stamp sees " + f.getName(), s, ClimbGame.tuningStamp(u));
        }
    }
    static final int STAMP_NORMAL = -2097562465;          // assets/data/tuning.json of Build 82 (rules 16)

    /** Finding 8, save compatibility: a climb stamped by Build 82 (the Json text stamp) resumes comparable on the same tuning and is restamped. */
    @Test public void aClimbStampedBeforeTheRepairStaysComparable() throws Exception {
        Tuning t = TestUtil.tuning(); long seed = 8204;
        File dir = Files.createTempDirectory("b82-stamp").toFile();
        ClimbGame g = GenerationRecoveryTest.game(dir, t); ClimbGame.Run run = GenerationRecoveryTest.open(g, seed, true);
        g.syncHistory(run.tower); g.save.climbTuning = ClimbGame.legacyTuningStamp(t); g.store.saveGame(g.save);
        ClimbGame g2 = GenerationRecoveryTest.game(dir, t); ClimbGame.Run r2 = g2.openRun(false);
        assertTrue("it resumes", r2.resumed);
        assertFalse("still comparable", g2.save.climbNotComparable);
        assertNull("nothing to tell", g2.saveNotice);
        assertEquals("restamped", ClimbGame.tuningStamp(t), g2.save.climbTuning);
        g2.store.saveGame(g2.save);
        ClimbGame g3 = GenerationRecoveryTest.game(dir, t); g3.save.climbTuning = 0x1234567; g3.store.saveGame(g3.save);
        ClimbGame g4 = GenerationRecoveryTest.game(dir, t); g4.openRun(false);
        assertTrue("other tuning is still caught", g4.save.climbNotComparable);
    }
}
