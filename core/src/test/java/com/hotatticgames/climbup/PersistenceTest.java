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
