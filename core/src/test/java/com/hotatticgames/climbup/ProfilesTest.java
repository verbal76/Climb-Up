package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.badlogic.gdx.utils.JsonReader;
import com.hotatticgames.climbup.sim.Tuning;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/** Named players: each with an independent save, the last one used selected on start, the single old save moved into the first player without loss. */
public class ProfilesTest {
    private static File tmp() throws Exception { return Files.createTempDirectory("climbplayers").toFile(); }
    private static void write(File dir, String name, String text) throws Exception { dir.mkdirs(); Files.write(new File(dir, name).toPath(), text.getBytes(StandardCharsets.UTF_8)); }
    private static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    /** A save exactly as v6 wrote it (no name), with a climb in progress. */
    private static final String V6_SAVE = "{\"version\":6,\"seed\":4242,\"cpSlice\":3,\"cpLocal\":5,\"climbHeight\":310.5,\"keysHeld\":3,\"openedUpTo\":1,\"bestHeight\":2515,\"falls\":9,"
        + "\"playSeconds\":1234.5,\"completions\":1,\"shownTips\":[\"rope\",\"pad\"],\"runClock\":456.5,\"towers\":2,\"splits\":[80.5,120],\"towerTotals\":[80.5,200.5],\"bestFinish\":900,\"bestSplit\":42.5,\"bestTotals\":[42.5,99]}";

    @Test public void aFreshInstallGetsOneEmptyFirstPlayer() throws Exception {
        File dir = tmp(); Profiles p = Profiles.load(dir);
        assertEquals(1, p.count()); assertEquals(Profiles.FIRST_NAME, p.last().name); assertFalse(p.migrated);
        SaveData d = new SaveStore(p.dirOf(p.last().id)).loadGame();
        assertEquals(Profiles.FIRST_NAME, d.name); assertEquals(0L, d.seed); assertEquals(SaveData.CURRENT_VERSION, d.version);
    }

    @Test public void theSingleOldSaveBecomesPlayer1WithNothingLost() throws Exception {
        File dir = tmp();
        write(dir, "save.json", V6_SAVE); write(dir, "run.json", "{\"run\":\"snapshot bytes\"}"); write(dir, "history.bin", "history bytes");
        write(dir, "settings.json", "{\"music\":3,\"textScale\":2}");
        Profiles p = Profiles.load(dir);
        assertTrue(p.migrated); assertEquals(1, p.count()); assertEquals("PLAYER 1", p.last().name);
        File pd = p.dirOf(p.last().id);
        assertEquals("the climb snapshot moved with the player", "{\"run\":\"snapshot bytes\"}", read(new File(pd, "run.json")));
        assertEquals("history bytes", read(new File(pd, "history.bin")));
        assertFalse("nothing is left in the old place", new File(dir, "save.json").exists() || new File(dir, "run.json").exists() || new File(dir, "history.bin").exists());
        SaveData d = new SaveStore(pd).loadGame();
        assertEquals("PLAYER 1", d.name); assertEquals(SaveData.CURRENT_VERSION, d.version);
        assertEquals(4242L, d.seed); assertEquals(3, d.cpSlice); assertEquals(5, d.cpLocal); assertEquals(310.5f, d.climbHeight, 0f); assertEquals(3, d.keysHeld); assertEquals(1, d.openedUpTo);
        assertEquals(2515f, d.bestHeight, 0f); assertEquals(9, d.falls); assertEquals(1234.5f, d.playSeconds, 0f); assertEquals(1, d.completions);
        assertTrue(d.shownTips.contains("rope") && d.shownTips.contains("pad")); assertEquals(456.5f, d.runClock, 0f); assertEquals(2, d.towers);
        assertEquals(900f, d.bestFinish, 0f); assertEquals(42.5f, d.bestSplit, 0f); assertEquals(2, d.bestTotals.length);
        assertFalse("a v6 climb is not archived as an old-rules climb", d.noticeOldClimb);
        assertEquals("settings stay shared in the data folder", 3, new SaveStore(dir).loadSettings().music);
        // a second start finds the same player and changes nothing
        Profiles again = Profiles.load(dir);
        assertFalse(again.migrated); assertEquals(1, again.count()); assertEquals(4242L, new SaveStore(again.dirOf(1)).loadGame().seed);
    }

    @Test public void aMigrationInterruptedBeforeTheIndexIsRedoneWithNothingLost() throws Exception {
        File dir = tmp();
        write(dir, "save.json", V6_SAVE); write(dir, "history.bin", "history bytes");
        write(new File(dir, "players/p1"), "save.json", "{half a copy");               // the copy was cut short, the index never written
        Profiles p = Profiles.load(dir);
        assertTrue(p.migrated); SaveData d = new SaveStore(p.dirOf(1)).loadGame();
        assertEquals(4242L, d.seed); assertEquals(2515f, d.bestHeight, 0f); assertEquals("history bytes", read(new File(p.dirOf(1), "history.bin")));
    }

    @Test public void originalsLeftByAMigrationThatDiedAfterTheIndexAreCleanedUpOnce() throws Exception {
        File dir = tmp();
        write(dir, "save.json", V6_SAVE);
        Profiles.load(dir);
        write(dir, "save.json", "{\"version\":6,\"bestHeight\":1}");                     // the old file reappears (the index still says "just migrated")
        Profiles p = Profiles.load(dir);
        assertFalse(new File(dir, "save.json").exists());
        assertEquals("the player's own save was not touched", 2515f, new SaveStore(p.dirOf(1)).loadGame().bestHeight, 0f);
        write(dir, "save.json", "{\"version\":6,\"bestHeight\":1}");                     // after that one clean-up the loader never deletes anything outside players/
        Profiles.load(dir); assertTrue(new File(dir, "save.json").exists());
    }

    @Test public void aDamagedOldSaveIsStillKeptAndRecoveredAsPlayer1() throws Exception {
        File dir = tmp();
        write(dir, "save.json", "{ this is not json");
        Profiles p = Profiles.load(dir);
        assertEquals(1, p.count());
        SaveData d = new SaveStore(p.dirOf(1)).loadGame();
        assertEquals("PLAYER 1", d.name); assertEquals(0f, d.bestHeight, 0f);
        assertTrue("the damaged file is kept as .corrupt", new File(p.dirOf(1), "save.json.corrupt").exists());
    }

    @Test public void playersKeepIndependentSavesAndTheLastOneUsedIsSelectedOnStart() throws Exception {
        File dir = tmp(); Profiles p = Profiles.load(dir);
        Profiles.Entry ann = p.create("ann"), bo = p.create("Bo 2");
        assertEquals("ANN", ann.name); assertEquals("BO 2", bo.name); assertEquals(3, p.count());
        SaveStore sa = new SaveStore(p.dirOf(ann.id)), sb = new SaveStore(p.dirOf(bo.id));
        SaveData a = sa.loadGame(); a.bestHeight = 120; a.seed = 11; a.cpSlice = 2; a.falls = 4; sa.saveGame(a);
        SaveData b = sb.loadGame(); b.bestHeight = 999; b.seed = 22; b.cpSlice = 7; b.falls = 1; sb.saveGame(b);
        p.select(bo.id);
        Profiles q = Profiles.load(dir);                                              // the app is restarted
        assertEquals("the last one used", bo.id, q.last().id);
        SaveData ra = new SaveStore(q.dirOf(ann.id)).loadGame(), rb = new SaveStore(q.dirOf(bo.id)).loadGame(), r1 = new SaveStore(q.dirOf(1)).loadGame();
        assertEquals(120f, ra.bestHeight, 0f); assertEquals(11L, ra.seed); assertEquals(2, ra.cpSlice); assertEquals(4, ra.falls); assertEquals("ANN", ra.name);
        assertEquals(999f, rb.bestHeight, 0f); assertEquals(22L, rb.seed); assertEquals(7, rb.cpSlice); assertEquals(1, rb.falls);
        assertEquals("the first player is untouched", 0f, r1.bestHeight, 0f);
        assertEquals(3, q.count()); assertEquals("PLAYER 1", q.get(1).name);
    }

    @Test public void namesAreCapitalsDigitsAndSpacesOnlyTenLongAndUnique() throws Exception {
        assertEquals("ANN LEE", Profiles.cleanName("  ann   lee "));
        assertEquals("ABCDEFGHIJ", Profiles.cleanName("abcdefghijklmnop"));
        assertEquals("A1B2", Profiles.cleanName("a-1!b_2é"));
        assertEquals("", Profiles.cleanName("  ")); assertEquals("", Profiles.cleanName(null));
        File dir = tmp(); Profiles p = Profiles.load(dir);
        assertNull("empty name", p.create("   ")); assertNull("taken (any case)", p.create("player 1"));
        assertNotNull(p.create("ann")); assertNull(p.create("ANN"));
        while (p.canAdd()) assertNotNull(p.create("P" + p.count()));
        assertEquals(Profiles.MAX_PLAYERS, p.count()); assertNull("the list is full", p.create("MORE"));
        assertTrue(Profiles.MAX_NAME == 10);
    }

    @Test public void deletingAPlayerRemovesOnlyTheirFilesAndKeepsTheRest() throws Exception {
        File dir = tmp(); Profiles p = Profiles.load(dir);
        Profiles.Entry ann = p.create("ANN"), bo = p.create("BO");
        write(p.dirOf(ann.id), "history.bin", "annhist"); write(p.dirOf(bo.id), "history.bin", "bohist");
        p.select(ann.id);
        assertTrue(p.delete(ann.id));
        assertFalse(p.dirOf(ann.id).exists()); assertNull(p.get(ann.id)); assertEquals(2, p.count());
        assertEquals("the selected player was deleted: someone else is selected", 1, p.last().id);
        assertEquals("bohist", read(new File(p.dirOf(bo.id), "history.bin")));
        assertFalse(p.delete(99));
        Profiles q = Profiles.load(dir); assertEquals(2, q.count()); assertNull(q.get(ann.id));
        assertNotNull("a new player can take the name again", q.create("ANN"));
    }

    @Test public void deletingTheLastPlayerLeavesAFreshEmptyOne() throws Exception {
        File dir = tmp(); Profiles p = Profiles.load(dir);
        SaveStore s = new SaveStore(p.dirOf(1)); SaveData d = s.loadGame(); d.bestHeight = 77; s.saveGame(d);
        assertTrue(p.delete(1));
        assertEquals(1, p.count()); assertEquals("PLAYER 1", p.last().name);
        assertEquals(0f, new SaveStore(p.dirOf(p.last().id)).loadGame().bestHeight, 0f);
        p.create("X"); p.deleteAll();
        assertEquals(1, p.count()); assertEquals(0f, new SaveStore(p.dirOf(p.last().id)).loadGame().bestHeight, 0f);
    }

    @Test public void aDamagedIndexIsRebuiltFromThePlayerFolders() throws Exception {
        File dir = tmp(); Profiles p = Profiles.load(dir);
        Profiles.Entry ann = p.create("ANN");
        SaveStore sa = new SaveStore(p.dirOf(ann.id)); SaveData a = sa.loadGame(); a.bestHeight = 55; sa.saveGame(a);
        write(dir, "players.json", "{\"players\":[ garbage");
        Profiles q = Profiles.load(dir);
        assertTrue(q.rebuilt); assertTrue(new File(dir, "players.json.corrupt").exists());
        assertEquals(2, q.count()); assertEquals("PLAYER 1", q.get(1).name); assertEquals("ANN", q.get(ann.id).name);
        assertEquals(55f, new SaveStore(q.dirOf(ann.id)).loadGame().bestHeight, 0f);
        assertEquals("and the rebuilt list is written back", 2, Profiles.load(dir).count());
        assertFalse(Profiles.load(dir).rebuilt);
    }

    @Test public void oneDamagedPlayerSaveDoesNotTouchTheOthersAndAMissingFolderIsAnEmptyPlayer() throws Exception {
        File dir = tmp(); Profiles p = Profiles.load(dir);
        Profiles.Entry ann = p.create("ANN"), bo = p.create("BO");
        SaveStore sb = new SaveStore(p.dirOf(bo.id)); SaveData b = sb.loadGame(); b.bestHeight = 321; sb.saveGame(b);
        write(p.dirOf(ann.id), "save.json", "not json at all");
        SaveStore sa = new SaveStore(p.dirOf(ann.id));
        assertEquals(0f, sa.loadGame().bestHeight, 0f); assertTrue(sa.recoveredFromCorruption);
        assertEquals(321f, new SaveStore(p.dirOf(bo.id)).loadGame().bestHeight, 0f);
        new File(p.dirOf(bo.id), "save.json").delete(); new File(p.dirOf(bo.id), "save.json.tmp").delete(); p.dirOf(bo.id).delete();
        Profiles q = Profiles.load(dir);
        assertEquals(3, q.count()); assertTrue(q.dirOf(bo.id).isDirectory()); assertEquals(0f, new SaveStore(q.dirOf(bo.id)).loadGame().bestHeight, 0f);
    }

    @Test public void theIndexFileIsPlainValidJson() throws Exception {
        File dir = tmp(); Profiles p = Profiles.load(dir); p.create("ANN");
        com.badlogic.gdx.utils.JsonValue v = new JsonReader().parse(read(new File(dir, "players.json")));
        assertEquals(2, v.get("players").size); assertEquals("ANN", v.get("players").get(1).getString("name"));
        assertFalse(new File(dir, "players.json.tmp").exists());
    }

    // ---- the save format

    @Test public void aV6SaveMigratesToV7AndTheNameRoundTrips() throws Exception {
        File dir = tmp(); SaveStore st = new SaveStore(dir);
        st.writeAtomic("save.json", V6_SAVE);
        SaveData d = st.loadGame();
        assertEquals(7, SaveData.CURRENT_VERSION); assertEquals(7, d.version); assertEquals("", d.name);
        assertEquals(4242L, d.seed); assertEquals(2515f, d.bestHeight, 0f); assertTrue(d.legacy.isEmpty());
        d.name = "ANN"; st.saveGame(d);
        assertEquals("ANN", st.loadGame().name);
        assertTrue("the file says v7", read(new File(dir, "save.json")).contains("\"version\":7"));
        st.writeAtomic("save.json", "{\"version\":7,\"name\":null,\"bestHeight\":5}");
        assertEquals("a missing name never becomes null", "", st.loadGame().name);
    }

    @Test public void olderSavesStillMigrateAllTheWayToV7() throws Exception {
        File dir = tmp(); SaveStore st = new SaveStore(dir);
        st.writeAtomic("save.json", "{\"best\":88,\"cp\":3}");
        SaveData d = st.loadGame(); assertEquals(7, d.version); assertEquals(88f, d.bestHeight, 0f);
    }

    @Test public void erasePlayerKeepsTheSharedSettings() throws Exception {
        File dir = tmp(); SaveStore st = new SaveStore(dir);
        SaveData d = new SaveData(); d.bestHeight = 9; st.saveGame(d); Settings s = new Settings(); s.music = 2; st.saveSettings(s);
        st.erasePlayer();
        assertEquals(0f, st.loadGame().bestHeight, 0f); assertEquals(2, st.loadSettings().music);
    }

    // ---- the game flow

    private static ClimbGame game(File dir, Tuning t) { ClimbGame g = new ClimbGame(dir); g.initStores(); g.tuning = t; return g; }

    @Test public void switchingPlayersSavesTheOldOneOpensTheNewOneAndRemembersThemAcrossARestart() throws Exception {
        Tuning t = TestUtil.tuning(); File dir = tmp();
        ClimbGame g = game(dir, t);
        assertEquals("PLAYER 1", g.save.name);
        g.settings.music = 4;
        String old = System.getProperty("climb.seed"); System.setProperty("climb.seed", "33");
        try { g.openRun(true); } finally { if (old == null) System.clearProperty("climb.seed"); else System.setProperty("climb.seed", old); }
        g.save.bestHeight = 150; g.save.falls = 3;
        assertTrue(g.climbValid());
        assertTrue(g.addPlayer("ANN"));                                              // adding switches to the new player; PLAYER 1 is saved first
        assertEquals("ANN", g.save.name); assertEquals(0L, g.save.seed); assertEquals(0f, g.save.bestHeight, 0f); assertFalse("no climb of PLAYER 1 leaks over", g.climbValid());
        assertEquals("settings are shared", 4, g.settings.music);
        g.save.bestHeight = 40; g.save.falls = 8; g.persist();
        g.switchPlayer(1);
        assertEquals("PLAYER 1", g.save.name); assertEquals(150f, g.save.bestHeight, 0f); assertEquals(3, g.save.falls); assertEquals(33L, g.save.seed); assertTrue("their climb is still there", g.climbValid());
        g.persist();
        ClimbGame h = game(dir, t);                                                   // restart
        assertEquals("the last used player is selected", "PLAYER 1", h.save.name); assertTrue(h.climbValid()); assertEquals(150f, h.save.bestHeight, 0f);
        h.switchPlayer(2); assertEquals(40f, h.save.bestHeight, 0f); assertEquals(8, h.save.falls); assertFalse(h.climbValid());
        h.persist();
        assertEquals("ANN", game(dir, t).save.name);
    }

    @Test public void deletingThePlayerInUseOpensAnotherAndKeepsTheirData() throws Exception {
        Tuning t = TestUtil.tuning(); File dir = tmp();
        ClimbGame g = game(dir, t); g.save.bestHeight = 66; g.persist();
        assertTrue(g.addPlayer("ANN")); g.save.bestHeight = 5; g.persist();
        g.deletePlayer(2);
        assertEquals(1, g.profiles.count()); assertEquals("PLAYER 1", g.save.name); assertEquals(66f, g.save.bestHeight, 0f);
        assertFalse(g.profiles.dirOf(2).exists());
        g.persist(); assertEquals(1, game(dir, t).profiles.count());
        g.deletePlayer(1);                                                            // the only one
        assertEquals("PLAYER 1", g.save.name); assertEquals(0f, g.save.bestHeight, 0f);
    }

    @Test public void aClimbOfAnotherPlayerStillCountsForTheUpdateGuard() throws Exception {
        Tuning t = TestUtil.tuning(); File dir = tmp();
        ClimbGame g = game(dir, t);
        assertFalse(g.anyClimbInProgress());
        String old = System.getProperty("climb.seed"); System.setProperty("climb.seed", "33");
        try { g.openRun(true); } finally { if (old == null) System.clearProperty("climb.seed"); else System.setProperty("climb.seed", old); }
        assertTrue(g.climbValid()); assertTrue(g.anyClimbInProgress());
        assertTrue(g.addPlayer("ANN"));
        assertFalse("the player now playing has no climb", g.climbValid());
        assertTrue("but PLAYER 1 does, and the host must still hold back a generator change", g.anyClimbInProgress());
        g.switchPlayer(1); g.forgetRun();
        assertFalse("nobody has a climb now", g.anyClimbInProgress());
    }

    @Test public void eraseEverythingClearsAllPlayersAndSettings() throws Exception {
        Tuning t = TestUtil.tuning(); File dir = tmp();
        ClimbGame g = game(dir, t); g.addPlayer("ANN"); g.save.bestHeight = 5; g.settings.music = 1; g.persist();
        g.eraseEverything();
        assertEquals(1, g.profiles.count()); assertEquals(0f, g.save.bestHeight, 0f); assertEquals(new Settings().music, g.settings.music);
        ClimbGame h = game(dir, t); assertEquals(1, h.profiles.count()); assertEquals(new Settings().music, h.settings.music);
    }

    @Test public void theNamePickerBuildsAValidNameAndRefusesBadOnes() throws Exception {
        File dir = tmp(); ClimbGame g = game(dir, TestUtil.tuning());
        NameScreen n = new NameScreen(g, null, null);
        assertFalse("nothing typed yet", n.confirm());
        assertFalse("no leading space", n.type(' '));
        for (char c : "ann lee 12345".toCharArray()) n.type(c);
        assertEquals("capitals, 10 at most", "ANN LEE 12", n.current());
        assertFalse(n.type('X')); n.backspace(); n.backspace(); assertEquals("ANN LEE ", n.current());
        assertFalse("no doubled space", n.type(' ')); assertFalse("no symbols", n.type('!')); assertTrue(n.type('9'));
        assertTrue(n.confirm()); assertEquals("ANN LEE 9", g.save.name); assertEquals(2, g.profiles.count());
        NameScreen again = new NameScreen(g, null, null); again.type('a'); again.type('n'); again.type('n'); again.type(' '); again.type('l'); again.type('e'); again.type('e'); again.type(' '); again.type('9');
        assertFalse("the same name twice", again.confirm()); assertEquals(2, g.profiles.count());
    }
}
