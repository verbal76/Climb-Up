package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;

public class PersistenceTest {
    private static File tmp() throws Exception { return Files.createTempDirectory("climbsave").toFile(); }

    @Test public void roundTrip() throws Exception {
        SaveStore st = new SaveStore(tmp());
        SaveData d = new SaveData(); d.bestHeight = 123.5f; d.checkpoint = 42; d.courseIndex = 3; d.falls = 7; d.shownTips.add("rope"); d.completions = 2; d.bestTime = 611f;
        st.saveGame(d);
        SaveData r = st.loadGame();
        assertEquals(123.5f, r.bestHeight, 0f); assertEquals(42, r.checkpoint); assertEquals(3, r.courseIndex); assertEquals(7, r.falls);
        assertEquals(2, r.completions); assertEquals(611f, r.bestTime, 0f); assertTrue(r.shownTips.contains("rope"));
        assertEquals(SaveData.CURRENT_VERSION, r.version);
        Settings s = new Settings(); s.music = 3; s.reducedMotion = true; s.textScale = 2; s.leftHanded = true;
        st.saveSettings(s);
        Settings rs = st.loadSettings();
        assertEquals(3, rs.music); assertTrue(rs.reducedMotion); assertEquals(2, rs.textScale); assertTrue(rs.leftHanded);
    }

    /** Build 64: SHOW COURSE SEED and SHOW BEST TIMES are independent settings, default ON, saved with the rest and ON again for a settings file from before them. */
    @Test public void theHudSeedAndBestTimeSwitchesPersistSeparatelyAndDefaultOn() throws Exception {
        SaveStore st = new SaveStore(tmp());
        Settings fresh = new Settings();
        assertTrue("seed shown by default", fresh.showSeed); assertTrue("best times shown by default", fresh.showBestTimes);
        Settings s = new Settings(); s.showSeed = false;                      // one off, the other still on
        st.saveSettings(s);
        Settings rs = st.loadSettings();
        assertFalse(rs.showSeed); assertTrue("the other switch is untouched", rs.showBestTimes);
        s.showSeed = true; s.showBestTimes = false; st.saveSettings(s);
        rs = st.loadSettings();
        assertTrue(rs.showSeed); assertFalse(rs.showBestTimes);
        st.writeAtomic("settings.json", "{\"music\":2,\"reducedMotion\":true}");      // a Build 63 settings file: neither switch exists yet
        rs = st.loadSettings();
        assertTrue("absent = on", rs.showSeed); assertTrue("absent = on", rs.showBestTimes); assertEquals("the rest still loads", 2, rs.music); assertTrue(rs.reducedMotion);
    }

    /** Build 67: REDUCED HUD is its own switch, default OFF, saved with the rest, and off for a settings file from before it. */
    @Test public void theReducedHudSwitchPersistsAndDefaultsOff() throws Exception {
        SaveStore st = new SaveStore(tmp());
        assertFalse("the full HUD by default", new Settings().reducedHud);
        Settings s = new Settings(); s.reducedHud = true; s.showSeed = false;
        st.saveSettings(s);
        Settings rs = st.loadSettings();
        assertTrue(rs.reducedHud); assertFalse("the other switches keep their own values", rs.showSeed); assertTrue(rs.showBestTimes);
        s.reducedHud = false; st.saveSettings(s);
        assertFalse(st.loadSettings().reducedHud);
        st.writeAtomic("settings.json", "{\"music\":2,\"showSeed\":false}");           // a Build 66 settings file
        rs = st.loadSettings();
        assertFalse("absent = off", rs.reducedHud); assertFalse(rs.showSeed); assertEquals(2, rs.music);
    }

    /** Build 64: a typed course code can name any 64-bit seed (a random draw never leaves 31 bits), so the save, the records and the legacy list must keep every bit. */
    @Test public void aCourseSeedOfAnySizeSurvivesTheSaveTheRecordsAndTheLegacyList() throws Exception {
        for (long seed : new long[]{0x5EED_C0DE_0000_0064L, Long.MAX_VALUE, Long.MIN_VALUE + 1, -1L, 987654321987654321L}) {
            SaveStore st = new SaveStore(tmp());
            SaveData d = new SaveData(); d.seed = seed; d.climbRules = 1;
            d.records().legDone(seed, 1, 0, 61.5f);
            SaveData.LegacyRun lr = new SaveData.LegacyRun(); lr.seed = seed; lr.rules = 1; d.legacy.add(lr);
            d.syncRecords(); st.saveGame(d);
            SaveData r = st.loadGame();
            assertEquals("seed " + SeedCode.encode(seed), seed, r.seed);
            assertEquals(seed, r.legacy.get(0).seed);
            assertNotNull("the course's bests are found again by the exact seed", r.records().seedBests(seed, 1));
            assertEquals(61.5f, r.records().seedBests(seed, 1).bestLegs[0], 1e-4f);
            assertEquals("the code shown is the code typed", SeedCode.encode(seed), SeedCode.encode(r.seed));
        }
    }

    @Test public void speedRunSplitsSurviveASaveAndOldSavesGetDefaults() throws Exception {
        File dir = tmp(); SaveStore st = new SaveStore(dir);
        SaveData d = new SaveData(); d.runClock = 312.4f; d.towerStartClock = 200f; d.towerStartHeight = 140f; d.towers = 2;
        d.splits = new float[]{80.5f, 119.5f}; d.towerTotals = new float[]{80.5f, 200f}; d.bestSplit = 80.5f; d.bestTotals = new float[]{80.5f, 200f};
        st.saveGame(d);
        SaveData r = st.loadGame();
        assertEquals(312.4f, r.runClock, 1e-3f); assertEquals(2, r.towers); assertEquals(119.5f, r.splits[1], 1e-3f); assertEquals(200f, r.towerTotals[1], 1e-3f);
        assertEquals(80.5f, r.bestSplit, 1e-3f); assertEquals(2, r.bestTotals.length);
        st.writeAtomic("save.json", "{\"version\":3,\"bestHeight\":70,\"falls\":2}");      // a save from before the clock existed
        SaveData old = st.loadGame();
        assertEquals(0f, old.runClock, 0f); assertEquals(0, old.towers); assertNotNull(old.splits); assertEquals(70f, old.bestHeight, 0f);
    }

    @Test public void aClimbFromBeforeTheV6SaveFormatIsKeptAsALegacyRecordAndTheRestIsPreserved() throws Exception {
        File dir = tmp(); SaveStore st = new SaveStore(dir);
        st.writeAtomic("save.json", "{\"version\":5,\"seed\":12345,\"slice\":2,\"sliceJson\":\"{}\",\"sliceCheckpoint\":4,\"runClock\":56.2,\"towers\":1,\"splits\":[10],\"towerTotals\":[10],"
            + "\"climbHeight\":900,\"climbVersion\":\"1.1.3\",\"climbBuild\":43,\"bestHeight\":2515,\"falls\":9,\"bestSplit\":42.5,\"bestFinish\":900,\"bestTotals\":[42.5]}");
        SaveData d = st.loadGame();
        assertEquals("no climb carries over", 0L, d.seed); assertTrue("the player is told once", d.noticeOldClimb);
        assertEquals("the old climb is kept as a stamped record", 1, d.legacy.size()); assertEquals(900f, d.legacy.get(0).height, 0f); assertEquals("1.1.3", d.legacy.get(0).version); assertEquals(43, d.legacy.get(0).build); assertEquals(1, d.legacy.get(0).towers);
        assertEquals("records are untouched", 2515f, d.bestHeight, 0f); assertEquals(42.5f, d.bestSplit, 1e-3f); assertEquals(900f, d.bestFinish, 1e-3f); assertEquals(9, d.falls);
        assertEquals(SaveData.CURRENT_VERSION, d.version);
        d.noticeOldClimb = false; st.saveGame(d);
        SaveData again = st.loadGame();
        assertFalse("the notice is shown once", again.noticeOldClimb); assertEquals("and the record is not archived twice", 1, again.legacy.size());
        d.seed = 77; d.cpSlice = 3; d.cpLocal = 5; d.keysHeld = 5; d.openedUpTo = 2; st.saveGame(d);
        SaveData r = st.loadGame(); assertEquals(77L, r.seed); assertEquals(3, r.cpSlice); assertEquals(5, r.cpLocal); assertEquals(5, r.keysHeld); assertEquals(2, r.openedUpTo);
    }

    @Test public void theHistoryFileKeepsEverySliceAndSurvivesATornWrite() throws Exception {
        File dir = tmp(); HistoryStore h = new HistoryStore(dir);
        h.reset(42L);
        byte[][] blobs = {new byte[]{1, 2, 3}, new byte[500], new byte[]{9}};
        for (byte[] b : blobs) h.append(b);
        java.util.List<byte[]> back = new HistoryStore(dir).read(42L);
        assertEquals(3, back.size()); assertArrayEquals(blobs[0], back.get(0)); assertEquals(500, back.get(1).length); assertArrayEquals(blobs[2], back.get(2));
        assertNull("another seed's history is never used", new HistoryStore(dir).read(43L));
        try (java.io.FileOutputStream f = new java.io.FileOutputStream(new File(dir, "history.bin"), true)) { f.write(new byte[]{0, 0, 1, 0, 7, 7}); }       // a record cut short by a crash
        assertEquals("the torn record is ignored, the rest is intact", 3, new HistoryStore(dir).read(42L).size());
    }

    @Test public void corruptSaveIsBackedUpAndDefaultsReturned() throws Exception {
        File dir = tmp(); SaveStore st = new SaveStore(dir);
        st.writeAtomic("save.json", "{ this is not json");
        SaveData d = st.loadGame();
        assertEquals(0f, d.bestHeight, 0f);
        assertTrue(st.recoveredFromCorruption);
        assertTrue(new File(dir, "save.json.corrupt").exists());
        // a fresh save works afterwards
        d.bestHeight = 9; st.saveGame(d); assertEquals(9f, st.loadGame().bestHeight, 0f);
    }

    @Test public void migratesFromEveryOlderVersion() throws Exception {
        File dir = tmp(); SaveStore st = new SaveStore(dir);
        st.writeAtomic("save.json", "{\"best\":55.5,\"cp\":12}");                       // v0 fixture
        SaveData v0 = st.loadGame();
        assertEquals(55.5f, v0.bestHeight, 0.001f); assertEquals(12, v0.checkpoint); assertEquals(SaveData.CURRENT_VERSION, v0.version);
        st.writeAtomic("save.json", "{\"version\":1,\"bestHeight\":70,\"checkpoint\":20,\"falls\":3,\"playSeconds\":100}");  // v1 fixture
        SaveData v1 = st.loadGame();
        assertEquals(70f, v1.bestHeight, 0f); assertEquals(20, v1.checkpoint); assertEquals(0, v1.courseIndex); assertEquals(SaveData.CURRENT_VERSION, v1.version);
    }

    @Test public void atomicWriteLeavesNoTempFile() throws Exception {
        File dir = tmp(); SaveStore st = new SaveStore(dir);
        st.saveGame(new SaveData());
        assertFalse(new File(dir, "save.json.tmp").exists()); assertTrue(new File(dir, "save.json").exists());
    }

    @Test public void eraseAllResetsData() throws Exception {
        File dir = tmp(); SaveStore st = new SaveStore(dir);
        SaveData d = new SaveData(); d.bestHeight = 10; st.saveGame(d); st.saveSettings(new Settings());
        st.eraseAll();
        assertEquals(0f, st.loadGame().bestHeight, 0f);
    }

    @Test public void noPowerProgressionIsStored() {
        for (java.lang.reflect.Field f : SaveData.class.getFields()) {
            String n = f.getName().toLowerCase();
            assertFalse("no upgrades/xp/level in save: " + n, n.contains("upgrade") || n.contains("xp") || n.contains("level") || n.contains("unlock") || n.contains("powerup"));
        }
    }
}
