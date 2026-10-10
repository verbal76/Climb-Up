package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/**
 * SAVE &amp; EXIT / CONTINUE: the snapshot must put the hero back exactly where he was, moving the way he was moving, on a world in the state it was left in, so that the climb from there on is
 * the very same climb. "Same" is checked the hard way: a reference simulation that never stopped and one rebuilt from the files alone are stepped with identical inputs and must agree bit for bit.
 */
public class RunSnapshotTest {
    private static final long SEED = 33;

    /** Inputs are a pure function of the step number, so two simulations can be fed the same flailing player without sharing any state. */
    private static void input(InputState in, int step) {
        Random r = new Random(0x9E3779B97F4A7C15L * (step / 20 + 1));
        in.clear();
        in.moveX = new float[]{1f, 1f, 1f, 0f, -1f}[r.nextInt(5)];
        in.moveY = new float[]{0f, 0f, 1f, -1f}[r.nextInt(4)];
        in.jumpHeld = r.nextInt(3) > 0;
        in.jumpPressed = step % 20 == r.nextInt(20);
    }

    private static Tower towerFrom(List<byte[]> blobs, Tuning t) { return new Tower(SEED, t, blobs, 0); }

    private static Sim simOn(Tower tw, Tuning t) {
        Sim s = new Sim(tw.world, t);
        s.keysFree = false; s.deferRespawn = true; s.floorOverride = tw.floorLocal(); s.setRange(0, tw.world.size() - 1);
        return s;
    }

    private static void advance(Sim s, Tower tw, int from, int to) {
        InputState in = new InputState();
        for (int i = from; i < to; i++) { tw.maintain(s); input(in, i); s.step(in); s.consumeEvents(); }
    }

    private static List<byte[]> history(Tuning t, double upTo) {
        Tower a = new Tower(SEED, t); while (a.topAbsY() < upTo) a.extend();
        List<byte[]> l = new ArrayList<>(); for (int k = 0; k < a.sliceCount(); k++) l.add(a.blob(k));
        return l;
    }

    private static void assertSame(String why, Sim a, Tower ta, Sim b, Tower tb) {
        assertEquals(why + " mode", a.mode, b.mode);
        assertEquals(why + " height", ta.absY(a.y), tb.absY(b.y), 1e-4);
        assertEquals(why + " arc", ta.world.wrap((float) (ta.originS + a.s)), tb.world.wrap((float) (tb.originS + b.s)), 1e-4);
        assertEquals(why + " vx", a.vx, b.vx, 1e-5f);
        assertEquals(why + " vy", a.vy, b.vy, 1e-5f);
        assertEquals(why + " falls", a.falls, b.falls);
        assertEquals(why + " hits", a.hits, b.hits);
        assertEquals(why + " keys", a.keys, b.keys);
        assertEquals(why + " standing on", ta.elemKeyAt(a.onElem), tb.elemKeyAt(b.onElem));
        assertEquals(why + " checkpoint", ta.elemKeyAt(a.checkpoint), tb.elemKeyAt(b.checkpoint));
    }

    @Test public void aClimbRestoredFromItsSnapshotContinuesExactlyLikeTheOneThatNeverStopped() throws Exception {
        Tuning t = TestUtil.tuning();
        List<byte[]> blobs = history(t, 200);
        int[] saveAt = {1, 90, 300, 777, 1500, 2400, 3600};
        for (int k : saveAt) {
            Tower ta = towerFrom(blobs, t); ta.windowAroundAbs(0); Sim a = simOn(ta, t);
            advance(a, ta, 0, k);
            RunSnapshot snap = RunSnapshot.capture(a, ta, SEED);
            assertNotNull("capture at " + k, snap);
            RunSnapshot back = RunSnapshot.fromJson(snap.toJson());                 // through the file format
            assertNotNull("the stored text parses at " + k, back);
            Tower tb = towerFrom(blobs, t); tb.setCheckpointRef(new Tower.Ref(back.cpSlice, back.cpLocal)); tb.windowAroundAbs(back.centreAbs());
            Sim b = simOn(tb, t);
            assertTrue("applies at " + k, back.apply(b, tb));
            assertSame("right after restoring at " + k, a, ta, b, tb);
            // identical inputs from here on: the two climbs must stay one climb (flailing: falls, respawns, crumbling, ledges)
            InputState in = new InputState();
            for (int i = k; i < k + 900; i++) {
                ta.maintain(a); tb.maintain(b);
                input(in, i); a.step(in); a.consumeEvents(); input(in, i); b.step(in); b.consumeEvents();
                if (i % 60 == 0) assertSame("step " + i + " (saved at " + k + ")", a, ta, b, tb);
            }
            assertSame("900 steps after " + k, a, ta, b, tb);
        }
    }

    @Test public void crumblingPlatformsAndTheWorldClockSurviveTheRoundTrip() throws Exception {
        Tuning t = TestUtil.tuning();
        List<byte[]> blobs = history(t, 200);
        Tower ta = towerFrom(blobs, t); ta.windowAroundAbs(0); Sim a = simOn(ta, t);
        int shown = 0;
        for (int step = 0; step < 6000 && shown < 3; step++) {
            advance(a, ta, step, step + 1);
            boolean shaking = false; for (int i = 0; i < a.course.size(); i++) if (a.crumbleT[i] > 0.2f || a.gone[i]) shaking = true;
            if (!shaking) continue;
            RunSnapshot snap = RunSnapshot.capture(a, ta, SEED);
            Tower tb = towerFrom(blobs, t); tb.windowAroundAbs(snap.centreAbs()); Sim b = simOn(tb, t);
            assertTrue(snap.apply(b, tb));
            assertEquals("world clock", a.time, b.time, 0f);
            for (int i = 0; i < a.course.size(); i++) {
                int j = tb.indexOfElemKey(ta.elemKeyAt(i));
                assertTrue("element resident", j >= 0);
                assertEquals("crumble clock " + i, a.crumbleT[i], b.crumbleT[j], 0f);
                assertEquals("gone " + i, a.gone[i], b.gone[j]);
                assertEquals("platform x " + i, a.es1[i], b.es1[j], 1e-4f);
                assertEquals("platform y " + i, ta.absY(a.ey1[i]), tb.absY(b.ey1[j]), 1e-4);
            }
            shown++; step += 400;
        }
        assertTrue("a shaking or fallen platform was captured at least once", shown > 0);
    }

    @Test public void aSnapshotThatCannotBeSavedFaithfullyIsRefusedAndNanNeverReachesDisk() throws Exception {
        Tuning t = TestUtil.tuning();
        Tower tw = new Tower(SEED, t); Sim s = simOn(tw, t);
        s.respawnPending = true;
        assertNull("a waiting respawn has no faithful state", RunSnapshot.capture(s, tw, SEED));
        s.respawnPending = false;
        RunSnapshot ok = RunSnapshot.capture(s, tw, SEED); assertNotNull(ok);
        File dir = Files.createTempDirectory("climb-snap").toFile(); SaveStore st = new SaveStore(dir);
        assertTrue(st.saveRun(ok));
        RunSnapshot bad = RunSnapshot.fromJson(ok.toJson()); bad.vy = Float.NaN;
        assertFalse("invalid state is not written", st.saveRun(bad));
        RunSnapshot kept = st.loadRun(); assertNotNull("the good snapshot is untouched", kept); assertEquals(ok.absY, kept.absY, 0.0);
    }

    @Test public void interruptedAndCorruptedSnapshotFilesAreHandledAndTheLastGoodOneWins() throws Exception {
        Tuning t = TestUtil.tuning();
        Tower tw = new Tower(SEED, t); Sim s = simOn(tw, t);
        RunSnapshot ok = RunSnapshot.capture(s, tw, SEED);
        File dir = Files.createTempDirectory("climb-snap2").toFile(); SaveStore st = new SaveStore(dir);
        assertNull("nothing saved yet", st.loadRun());
        assertTrue(st.saveRun(ok));
        // a write that died half way leaves a temp file: the real file is still the last good one
        Files.write(new File(dir, "run.json.tmp").toPath(), "{\"version\":1,\"seed\":33,\"absY\":".getBytes("UTF-8"));
        assertNotNull("an interrupted write does not hurt the saved snapshot", new SaveStore(dir).loadRun());
        // a damaged real file is set aside and ignored (the climb then resumes from its checkpoint), never a crash
        Files.write(new File(dir, "run.json").toPath(), "{\"version\":1,\"seed\":33,\"absY\":".getBytes("UTF-8"));
        SaveStore st2 = new SaveStore(dir);
        assertNull(st2.loadRun()); assertTrue(st2.recoveredFromCorruption); assertTrue(new File(dir, "run.json.corrupt").exists()); assertFalse(new File(dir, "run.json").exists());
        Files.write(new File(dir, "run.json").toPath(), "{\"version\":1,\"seed\":33,\"mode\":\"FLYING\"}".getBytes("UTF-8"));
        assertNull("an unknown mode is rejected", new SaveStore(dir).loadRun());
    }

    private static ClimbGame game(File dir, Tuning t) {
        ClimbGame g = new ClimbGame(dir);
        g.store = new SaveStore(dir); g.history = new HistoryStore(dir); g.save = g.store.loadGame(); g.tuning = t;
        return g;
    }

    @Test public void saveAndExitThenContinueAfterARelaunchRestoresTheClimbAndNewRunDropsIt() throws Exception {
        Tuning t = TestUtil.tuning();
        File dir = Files.createTempDirectory("climb-game").toFile();
        String old = System.getProperty("climb.seed"); System.setProperty("climb.seed", "" + SEED);
        ClimbGame g1 = game(dir, t);
        ClimbGame.Run run;
        try { run = g1.openRun(true); } finally { if (old == null) System.clearProperty("climb.seed"); else System.setProperty("climb.seed", old); }
        assertFalse(run.resumed);
        Tower tw = run.tower; while (tw.topAbsY() < 150) tw.extend();
        Sim s = simOn(tw, t); advance(s, tw, 0, 1800);
        double y = tw.absY(s.y), sArc = tw.originS + s.s; Sim.Mode mode = s.mode; float vy = s.vy, time = s.time; int falls = s.falls;
        g1.saveRun(tw, s);                                  // SAVE & EXIT
        assertTrue(new File(dir, "run.json").exists());

        // the app is killed; a new process reads the files alone
        ClimbGame g2 = game(dir, t);
        assertTrue("CONTINUE is offered", g2.climbValid());
        ClimbGame.Run r2 = g2.openRun(false);
        assertTrue(r2.resumed); assertNotNull("the exact state came back, not just the checkpoint", r2.snapshot);
        Sim s2 = simOn(r2.tower, t); ResumeState.restore(g2.save, s2, r2.tower);
        assertTrue(r2.snapshot.apply(s2, r2.tower));
        assertEquals(y, r2.tower.absY(s2.y), 1e-3);
        assertEquals(sArc, r2.tower.originS + s2.s, 1e-3);
        assertEquals(mode, s2.mode); assertEquals(vy, s2.vy, 1e-5f); assertEquals(time, s2.time, 0f); assertEquals(falls, s2.falls);

        // a snapshot from another climb is ignored, the checkpoint resume still works
        ClimbGame g3 = game(dir, t); g3.save.seed = SEED + 1;
        g3.history.reset(SEED + 1); for (int k = 0; k < tw.sliceCount(); k++) g3.history.append(tw.blob(k));
        assertNull("another climb's snapshot is not applied", g3.openRun(false).snapshot);
        assertFalse(new File(dir, "run.json").exists());

        // NEW RUN: forgetting the run removes the snapshot with the rest
        g2.saveRun(r2.tower, s2); assertTrue(new File(dir, "run.json").exists());
        g2.forgetRun(); assertFalse(new File(dir, "run.json").exists()); assertEquals(0, g2.save.seed);
    }
}
