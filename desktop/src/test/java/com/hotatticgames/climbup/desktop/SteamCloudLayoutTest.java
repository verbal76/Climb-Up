package com.hotatticgames.climbup.desktop;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.Profiles;
import com.hotatticgames.climbup.RecordsData;
import com.hotatticgames.climbup.SaveData;
import com.hotatticgames.climbup.SaveStore;
import com.hotatticgames.climbup.Settings;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Steam Auto-Cloud readiness (docs/STEAMWORKS_SETUP.md): the Windows and Linux folders carry the same name so one root override maps them,
 * the old lower-case Linux folder moves across without loss, and the files the cloud entries name are exactly the player's progress.
 */
public class SteamCloudLayoutTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void windowsAndLinuxShareTheSubdirectoryUnderTheirRoots() {
        File win = DataDirs.resolve("Windows 11", "C:\\Users\\K\\AppData\\Roaming", null, "C:\\Users\\K");
        File lin = DataDirs.resolve("Linux", null, null, "/home/deck");
        File xdg = DataDirs.resolve("Linux", null, "/home/deck/.xdg", "/home/deck");
        assertEquals("HotAtticGames/Upwardly", rel(win, "C:\\Users\\K\\AppData\\Roaming"));
        assertEquals("HotAtticGames/Upwardly", rel(lin, "/home/deck/.local/share"));
        assertEquals("HotAtticGames/Upwardly", rel(xdg, "/home/deck/.xdg"));
        assertNull(DataDirs.legacyLinux("Windows 10", null, "C:\\x"));
        assertEquals(new File("/home/deck/.local/share/hotatticgames/upwardly"), DataDirs.legacyLinux("Linux", null, "/home/deck"));
    }

    @Test public void theSaveFolderDoesNotDependOnTheWorkingDirectory() {
        String before = System.getProperty("user.dir");
        try {
            File a = DataDirs.resolve("Linux", null, null, "/home/deck");
            System.setProperty("user.dir", "/tmp/somewhere/else");
            assertEquals(a, DataDirs.resolve("Linux", null, null, "/home/deck"));
            assertTrue(a.isAbsolute());
        } finally { System.setProperty("user.dir", before); }
    }

    @Test public void theOldLinuxFolderMovesAcrossWithEveryRecordIntact() throws Exception {
        File base = tmp.newFolder("share"), old = new File(base, "hotatticgames/upwardly"), now = new File(base, "HotAtticGames/Upwardly");
        Profiles p = Profiles.load(old);
        File pd = p.dirOf(p.last().id);
        SaveData d = new SaveStore(pd).loadGame();
        d.bestHeight = 1234f; d.bestFinish = 600.5f; d.seed = 0x1234_5678_9ABCL; d.towers = 3; d.splits = new float[]{60f, 70f, 80f};
        RecordsData r = d.records(); long seed = 0x1234_5678_9ABCL;
        r.legDone(seed, 0, 60f); r.legDone(seed, 1, 70f); r.legDone(seed, 2, 80f); r.finish(seed, 650f);
        new SaveStore(pd).saveGame(d);
        Settings s = new Settings(); s.music = 3; new SaveStore(old).saveSettings(s);
        Files.write(new File(old, "upwardly-desktop.cfg").toPath(), "display=WINDOWED\n".getBytes(StandardCharsets.UTF_8));
        String saveBefore = new String(Files.readAllBytes(new File(pd, "save.json").toPath()), StandardCharsets.UTF_8);

        assertTrue(DataDirs.migrateLinuxLegacy(old, now) >= 3);
        File npd = new File(now, "players/p" + p.last().id);
        assertEquals(saveBefore, new String(Files.readAllBytes(new File(npd, "save.json").toPath()), StandardCharsets.UTF_8));
        SaveData back = new SaveStore(npd).loadGame();
        assertEquals(1234f, back.bestHeight, 0f); assertEquals(600.5f, back.bestFinish, 0f); assertEquals(0x1234_5678_9ABCL, back.seed);
        assertArrayEquals(new float[]{60f, 70f, 80f}, back.splits, 0f);
        RecordsData rb = back.records();
        RecordsData.SeedBests sb = rb.seedBests(0x1234_5678_9ABCL, com.hotatticgames.climbup.sim.CourseGenerator.RULES_VERSION);
        assertNotNull("the seed's bests survive the move", sb);
        assertEquals(60f, sb.bestLegs[0], 0f); assertEquals(70f, sb.bestLegs[1], 0f); assertEquals(80f, sb.bestLegs[2], 0f);
        assertEquals("the full-run record stays apart from the legs", 650f, sb.bestTotal, 0f);
        assertEquals(650f, rb.bestTotal, 0f); assertEquals(60f, rb.bestLegs[0], 0f);
        assertEquals(3, new SaveStore(now).loadSettings().music);
        assertTrue(new File(now, "upwardly-desktop.cfg").isFile());
        assertFalse("the emptied old folder is tidied away", old.exists());
        assertEquals("a second run does nothing", 0, DataDirs.migrateLinuxLegacy(old, now));
    }

    @Test public void nothingInTheNewFolderIsEverOverwritten() throws Exception {
        File base = tmp.newFolder("share2"), old = new File(base, "hotatticgames/upwardly"), now = new File(base, "HotAtticGames/Upwardly");
        old.mkdirs(); now.mkdirs();
        Files.write(new File(old, "players.json").toPath(), "OLD".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(old, "settings.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(now, "players.json").toPath(), "NEW".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, DataDirs.migrateLinuxLegacy(old, now));
        assertEquals("NEW", new String(Files.readAllBytes(new File(now, "players.json").toPath()), StandardCharsets.UTF_8));
        assertEquals("the clashing file stays in the old folder, never deleted", "OLD", new String(Files.readAllBytes(new File(old, "players.json").toPath()), StandardCharsets.UTF_8));
        assertTrue(new File(now, "settings.json").isFile());
    }

    @Test public void noOldFolderMeansNothingHappens() {
        File base = new File(tmp.getRoot(), "none");
        assertEquals(0, DataDirs.migrateLinuxLegacy(new File(base, "hotatticgames/upwardly"), new File(base, "HotAtticGames/Upwardly")));
        assertFalse(new File(base, "HotAtticGames/Upwardly").exists());
    }

    @Test public void thePlayerFolderHoldsOnlyTheCloudFilesAfterNormalPlay() throws Exception {
        File root = tmp.newFolder("data");
        Profiles p = Profiles.load(root);
        File pd = p.dirOf(p.last().id);
        SaveStore st = new SaveStore(pd);
        SaveData d = st.loadGame(); d.bestHeight = 50f; st.saveGame(d); st.saveGame(d);
        new SaveStore(root).saveSettings(new Settings());
        new com.hotatticgames.climbup.HistoryStore(pd).reset(42L);
        List<String> names = new ArrayList<>();
        for (File f : pd.listFiles()) names.add(f.getName());
        for (String n : names) assertTrue("unexpected file in a player folder: " + n, n.equals("save.json") || n.equals("history.bin") || n.equals("run.json"));
        assertTrue(new File(root, "players.json").isFile());
        assertTrue("settings stay per device, outside the player folders", new File(root, "settings.json").isFile());
    }

    @Test public void savedFilesCarryNoMachinePaths() throws Exception {
        File root = tmp.newFolder("paths");
        Profiles p = Profiles.load(root);
        File pd = p.dirOf(p.last().id);
        SaveStore st = new SaveStore(pd); SaveData d = st.loadGame(); d.seed = 7L; st.saveGame(d);
        for (File f : new File[]{new File(root, "players.json"), new File(pd, "save.json")}) {
            String t = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            assertFalse(f.getName() + " mentions the data folder", t.contains(root.getAbsolutePath()) || t.contains(":\\\\") || t.contains("/home/"));
        }
    }

    private static String rel(File f, String base) {
        String a = f.getPath().replace('\\', '/'), b = base.replace('\\', '/');
        assertTrue(a + " is under " + b, a.startsWith(b + "/"));
        return a.substring(b.length() + 1);
    }
}
