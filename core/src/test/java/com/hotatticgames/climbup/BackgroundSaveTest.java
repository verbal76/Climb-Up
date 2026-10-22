package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;

/**
 * Build 71 (owner): the periodic autosave writes on a background thread. Its writes land in order, nothing older can overwrite newer progress or bring back a
 * deleted file, the history stays in slice order even when a write fails, and a climb autosaved this way resumes exactly like one saved directly.
 */
public class BackgroundSaveTest {
    private static String text(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), "UTF-8"); }

    @Test public void queuedWritesLandInOrderAndADirectWriteAlwaysWins() throws Exception {
        File dir = Files.createTempDirectory("bg-order").toFile();
        SaveStore st = new SaveStore(dir);
        for (int i = 0; i < 300; i++) st.writeAtomicLater("save.json", "queued " + i);
        st.writeAtomic("save.json", "newest");                          // e.g. pausing right after an autosave
        assertEquals("an older queued write never lands after a newer direct one", "newest", text(new File(dir, "save.json")));
        for (int i = 0; i < 50; i++) st.writeAtomicLater("save.json", "later " + i);
        DiskWriter.drain();
        assertEquals("the last queued write is the one on disk", "later 49", text(new File(dir, "save.json")));
        assertFalse("no temporary file is left behind", new File(dir, "save.json.tmp").exists());
    }

    @Test public void aQueuedSnapshotCannotBringBackADeletedRun() throws Exception {
        File dir = Files.createTempDirectory("bg-delete").toFile();
        SaveStore st = new SaveStore(dir);
        for (int i = 0; i < 100; i++) st.writeAtomicLater("run.json", "{\"snapshot\":" + i + "}");
        st.deleteRun();                                                  // END RUN / NEW RUN right after an autosave
        DiskWriter.drain();
        assertFalse(new File(dir, "run.json").exists());
        assertFalse(new File(dir, "run.json.tmp").exists());
    }

    @Test public void historyAppendedInTheBackgroundKeepsItsOrder() throws Exception {
        File dir = Files.createTempDirectory("bg-history").toFile();
        HistoryStore h = new HistoryStore(dir); h.reset(5);
        for (int i = 0; i < 40; i++) h.appendLater(new byte[]{(byte) i, (byte) (i * 3)});
        assertEquals("counted as stored at once, so it is not handed over twice", 40, h.count());
        List<byte[]> back = new HistoryStore(dir).read(5);
        assertEquals(40, back.size());
        for (int i = 0; i < 40; i++) assertEquals(i, back.get(i)[0]);
    }

    @Test public void aFailedBackgroundAppendIsHandedOverAgainInOrder() throws Exception {
        File dir = Files.createTempDirectory("bg-fail").toFile();
        HistoryStore h = new HistoryStore(dir); h.reset(6);
        h.append(new byte[]{0});
        File f = new File(dir, "history.bin"), keep = new File(dir, "keep.bin");
        assertTrue(f.renameTo(keep)); assertTrue(f.mkdir());            // the file cannot be written (a directory stands in its way)
        h.appendLater(new byte[]{1}); h.appendLater(new byte[]{2});
        DiskWriter.drain();
        assertTrue(f.delete()); assertTrue(keep.renameTo(f));            // the disk is writable again
        assertEquals("the failed slices are not counted as stored", 1, h.count());
        h.appendLater(new byte[]{1}); h.appendLater(new byte[]{2});     // the next autosave hands them over again
        List<byte[]> back = new HistoryStore(dir).read(6);
        assertEquals(3, back.size());
        for (int i = 0; i < 3; i++) assertEquals("slices stay in order", i, back.get(i)[0]);
    }

    @Test public void aClimbAutosavedInTheBackgroundResumesExactly() throws Exception {
        Tuning t = TestUtil.tuning();
        File dir = Files.createTempDirectory("bg-resume").toFile();
        String old = System.getProperty("climb.seed"); System.setProperty("climb.seed", "41");
        ClimbGame g1 = game(dir, t);
        ClimbGame.Run run;
        try { run = g1.openRun(true); } finally { if (old == null) System.clearProperty("climb.seed"); else System.setProperty("climb.seed", old); }
        Tower tw = run.tower; while (tw.topAbsY() < 150) tw.extend();
        Sim s = Sim.startOn(tw.world, t, 0); s.setRange(0, tw.world.size() - 1);
        InputState in = new InputState();
        for (int i = 0; i < 900; i++) { in.clear(); in.moveX = 1f; in.jumpPressed = i % 40 == 0; in.jumpHeld = i % 40 < 12; s.step(in); }
        g1.autosave(tw, s);                                              // the periodic autosave; then the app is killed
        DiskWriter.drain();

        ClimbGame g2 = game(dir, t);
        ClimbGame.Run r2 = g2.openRun(false);
        assertTrue("the climb resumes from the background-written files", r2.resumed);
        assertNotNull(r2.snapshot);
        assertEquals("every slice was stored, in order", tw.sliceCount(), new HistoryStore(dir).read(41).size());
        for (int k = 0; k < tw.sliceCount(); k++) assertArrayEquals(tw.blob(k), r2.tower.blob(k));
        assertEquals(41L, g2.save.seed);
    }

    private static ClimbGame game(File dir, Tuning t) {
        ClimbGame g = new ClimbGame(dir);
        g.store = new SaveStore(dir); g.history = new HistoryStore(dir); g.save = g.store.loadGame(); g.tuning = t;
        return g;
    }
}
