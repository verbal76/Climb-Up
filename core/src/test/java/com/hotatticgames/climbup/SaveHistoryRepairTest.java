package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;

/**
 * Save repairs H1 and H2 (owner authorisation 2026-10-28; findings of docs/verification/GENERATION_RECOVERY_TEST_PREP.md). H1: a record cut short by a
 * failed or interrupted write never corrupts the records appended after it. H2: a saved climb whose history is damaged is resumed from its readable
 * start when its checkpoint lies there (the rest is made again, exactly), and a climb that cannot be resumed is never silently replaced: its file is
 * kept, it is archived, and the player is told.
 */
public class SaveHistoryRepairTest {
    static ClimbGame game(File dir, Tuning t) {
        ClimbGame g = new ClimbGame(dir);
        g.store = new SaveStore(dir); g.history = new HistoryStore(dir); g.save = g.store.loadGame(); g.tuning = t;
        return g;
    }
    static void tear(File dir) throws Exception {          // a record cut short: 10 bytes announced, 2 written
        try (FileOutputStream o = new FileOutputStream(new File(dir, "history.bin"), true)) { o.write(new byte[]{0, 0, 0, 10, 7, 7}); }
    }

    // ---------------------------------------------------------------- H1

    @Test public void h1_aTornRecordDoesNotHideEverySliceWrittenAfterIt() throws Exception {
        File dir = Files.createTempDirectory("h1-direct").toFile();
        HistoryStore h = new HistoryStore(dir); h.reset(9);
        h.append(new byte[]{0}); h.append(new byte[]{1});
        tear(dir);
        HistoryStore h2 = new HistoryStore(dir);
        assertEquals("the two whole records", 2, h2.read(9).size());
        h2.append(new byte[]{2}); h2.append(new byte[]{3});
        List<byte[]> back = new HistoryStore(dir).read(9);
        assertEquals("records appended after a torn one are readable", 4, back.size());
        for (int i = 0; i < 4; i++) { assertEquals(1, back.get(i).length); assertEquals(i, back.get(i)[0]); }
    }

    @Test public void h1_aTornRecordFoundWithoutAReadIsCutBeforeTheNextAppend() throws Exception {
        File dir = Files.createTempDirectory("h1-noread").toFile();
        HistoryStore h = new HistoryStore(dir); h.reset(9); h.append(new byte[]{0});
        tear(dir);
        HistoryStore h2 = new HistoryStore(dir);          // never read: the append itself finds where the whole records end
        h2.append(new byte[]{1});
        List<byte[]> back = new HistoryStore(dir).read(9);
        assertEquals(2, back.size()); assertEquals(0, back.get(0)[0]); assertEquals(1, back.get(1)[0]);
    }

    @Test public void h1_backgroundAppendsAfterATornRecordStayInOrder() throws Exception {
        File dir = Files.createTempDirectory("h1-later").toFile();
        HistoryStore h = new HistoryStore(dir); h.reset(9); h.append(new byte[]{0});
        tear(dir);                                         // e.g. the previous session died mid-write
        HistoryStore h2 = new HistoryStore(dir); assertEquals(1, h2.read(9).size());
        h2.appendLater(new byte[]{1}); h2.appendLater(new byte[]{2});
        DiskWriter.drain();
        List<byte[]> back = new HistoryStore(dir).read(9);
        assertEquals(3, back.size());
        for (int i = 0; i < 3; i++) assertEquals(i, back.get(i)[0]);
        assertEquals("count agrees with the file", 3, h2.count());
    }

    @Test public void h1_aHealthyFileIsLeftExactlyAsItWas() throws Exception {
        File dir = Files.createTempDirectory("h1-healthy").toFile();
        HistoryStore h = new HistoryStore(dir); h.reset(9); h.append(new byte[]{0, 1, 2});
        byte[] before = Files.readAllBytes(new File(dir, "history.bin").toPath());
        HistoryStore h2 = new HistoryStore(dir); h2.append(new byte[]{3});
        byte[] after = Files.readAllBytes(new File(dir, "history.bin").toPath());
        assertEquals(before.length + 4 + 1, after.length);
        for (int i = 0; i < before.length; i++) assertEquals("no byte of the old records changes", before[i], after[i]);
    }

    // ---------------------------------------------------------------- H2

    /** A climb on seed 4251 with five stored slices and its checkpoint in slice 1; the history then has record {@code damage} damaged. */
    static ClimbGame climbWithDamagedRecord(File dir, Tuning t, int damage, int cpSlice, byte[][] blobs) throws Exception {
        ClimbGame g1 = game(dir, t);
        String old = System.getProperty("climb.seed"); System.setProperty("climb.seed", "4251");
        ClimbGame.Run run; try { run = g1.openRun(true); } finally { if (old == null) System.clearProperty("climb.seed"); else System.setProperty("climb.seed", old); }
        while (run.tower.sliceCount() < 5) run.tower.extend();
        g1.syncHistory(run.tower);
        for (int k = 0; k < 5; k++) blobs[k] = run.tower.blob(k);
        g1.save.cpSlice = cpSlice; g1.save.cpLocal = 1; g1.save.towers = 1; g1.save.splits = new float[]{300f}; g1.save.towerTotals = new float[]{300f}; g1.save.runClock = 321f; g1.store.saveGame(g1.save);          // (a consistent save: SaveStore clamps towers to the recorded splits)
        byte[] all = Files.readAllBytes(new File(dir, "history.bin").toPath());
        int off = 12; for (int k = 0; k < damage; k++) off += 4 + blobs[k].length;
        all[off + 4] ^= 0x5A;                                                   // the record's first byte (its deflate header): it no longer decodes
        Files.write(new File(dir, "history.bin").toPath(), all);
        return g1;
    }

    @Test public void h2_aDamagedRecordAfterTheCheckpointResumesFromTheReadableStartAndRemakesTheRest() throws Exception {
        Tuning t = TestUtil.tuning(); File dir = Files.createTempDirectory("h2-prefix").toFile(); byte[][] blobs = new byte[5][];
        climbWithDamagedRecord(dir, t, 3, 1, blobs);
        byte[] damaged = Files.readAllBytes(new File(dir, "history.bin").toPath());
        ClimbGame g2 = game(dir, t);
        ClimbGame.Run r = g2.openRun(false);
        assertTrue("the climb resumes", r.resumed);
        assertEquals("on its own seed", 4251L, g2.save.seed);
        assertNull("nothing to tell: nothing was lost", g2.saveNotice);
        assertEquals("from the readable start", 3, r.tower.sliceCount());
        for (int k = 0; k < 3; k++) assertArrayEquals(blobs[k], r.tower.blob(k));
        while (r.tower.sliceCount() < 5) r.tower.extend();
        for (int k = 3; k < 5; k++) assertArrayEquals("slice " + k + " is made again exactly as it was", blobs[k], r.tower.blob(k));
        g2.syncHistory(r.tower);
        List<byte[]> h = new HistoryStore(dir).read(4251);
        assertEquals("the history is whole again, no record twice", 5, h.size());
        for (int k = 0; k < 5; k++) assertArrayEquals(blobs[k], h.get(k));
        File[] kept = dir.listFiles((d, n) -> n.startsWith("history-damaged-"));
        assertTrue("the damaged file is kept", kept != null && kept.length == 1);
        assertArrayEquals("byte for byte", damaged, Files.readAllBytes(kept[0].toPath()));
        assertEquals("progress kept", 1, g2.save.towers); assertEquals(321f, g2.save.runClock, 0f);
    }

    @Test public void h2_aClimbThatCannotBeResumedIsKeptArchivedAndAnnouncedNeverSilentlyReplaced() throws Exception {
        Tuning t = TestUtil.tuning(); File dir = Files.createTempDirectory("h2-unresumable").toFile(); byte[][] blobs = new byte[5][];
        climbWithDamagedRecord(dir, t, 0, 2, blobs);                            // the first record is damaged: nothing before the checkpoint is readable
        byte[] damaged = Files.readAllBytes(new File(dir, "history.bin").toPath());
        int legacyBefore = new SaveStore(dir).loadGame().legacy.size();
        ClimbGame g2 = game(dir, t);
        ClimbGame.Run r = g2.openRun(false);
        assertFalse("it cannot be resumed", r.resumed);
        File[] kept = dir.listFiles((d, n) -> n.startsWith("history-unresumable-"));
        assertTrue("its history is kept, not deleted or overwritten", kept != null && kept.length == 1);
        assertArrayEquals("byte for byte", damaged, Files.readAllBytes(kept[0].toPath()));
        SaveData sd = new SaveStore(dir).loadGame();
        assertEquals("the climb is archived", legacyBefore + 1, sd.legacy.size());
        SaveData.LegacyRun lr = sd.legacy.get(sd.legacy.size() - 1);
        assertEquals("with its seed", 4251L, lr.seed); assertEquals("and its castles", 1, lr.towers); assertEquals(321f, lr.runClock, 0f);
        assertNotNull("the player is told", g2.saveNotice);
        assertTrue(g2.saveNotice.contains(SeedCode.encode(4251L)));
        assertNotEquals("only then a new climb starts", 4251L, g2.save.seed);
    }

    @Test public void h2_anUndamagedClimbResumesExactlyAsBefore() throws Exception {
        Tuning t = TestUtil.tuning(); File dir = Files.createTempDirectory("h2-healthy").toFile();
        ClimbGame g1 = game(dir, t);
        String old = System.getProperty("climb.seed"); System.setProperty("climb.seed", "4252");
        ClimbGame.Run run; try { run = g1.openRun(true); } finally { if (old == null) System.clearProperty("climb.seed"); else System.setProperty("climb.seed", old); }
        while (run.tower.sliceCount() < 3) run.tower.extend();
        g1.syncHistory(run.tower); g1.save.cpSlice = 1; g1.save.cpLocal = 1; g1.store.saveGame(g1.save);
        byte[] before = Files.readAllBytes(new File(dir, "history.bin").toPath());
        ClimbGame g2 = game(dir, t);
        ClimbGame.Run r = g2.openRun(false);
        assertTrue(r.resumed); assertNull(g2.saveNotice);
        assertEquals(3, r.tower.sliceCount());
        assertArrayEquals("the history file is not touched", before, Files.readAllBytes(new File(dir, "history.bin").toPath()));
        File[] extra = dir.listFiles((d, n) -> n.startsWith("history-"));
        assertEquals("nothing is kept aside", 0, extra == null ? 0 : extra.length);
    }
}
