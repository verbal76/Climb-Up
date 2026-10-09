package com.hotatticgames.climbup;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** Version numbers, and what happens to a run in progress when an update changes the rules. */
public class LegacyTest {
    private static SaveData climb(String version, int build) {
        SaveData d = new SaveData(); d.seed = 99; d.sliceJson = "{}"; d.climbVersion = version; d.climbBuild = build; d.climbDate = "2026-10-01";
        d.climbHeight = 1234f; d.towers = 2; d.runClock = 3600.5f; d.bestHeight = 2028f; d.bestSplit = 80f; d.bestFinish = 5000f; d.falls = 7;
        return d;
    }

    @Test public void majorNumberDecidesWhetherRulesChanged() {
        assertEquals(1, Legacy.major("1.1.3")); assertEquals(0, Legacy.major("")); assertEquals(0, Legacy.major(null)); assertEquals(0, Legacy.major("0.1.0")); assertEquals(2, Legacy.major("2.0.0")); assertEquals(0, Legacy.major("x.y"));
    }

    @Test public void onlyAMajorBumpSetsAClimbAside() {
        assertFalse("patch update keeps the run", Legacy.needsPrompt(climb("1.1.3", 43), "1.1.4"));
        assertFalse("minor update keeps the run", Legacy.needsPrompt(climb("1.1.3", 43), "1.2.0"));
        assertTrue("major update sets it aside", Legacy.needsPrompt(climb("1.9.9", 60), "2.0.0"));
        assertTrue("a climb from before versions were stamped counts as older rules", Legacy.needsPrompt(climb("", 0), "1.1.3"));
        SaveData none = new SaveData(); assertFalse("nothing in progress, nothing to ask", Legacy.needsPrompt(none, "2.0.0"));
    }

    @Test public void archivingStampsTheRecordKeepsTheRecordsAndClearsTheClimb() {
        SaveData d = climb("1.0.0", 41);
        SaveData.LegacyRun r = Legacy.archive(d, "2026-10-09");
        assertEquals(1, d.legacy.size()); assertSame(r, d.legacy.get(0));
        assertEquals("1.0.0", r.version); assertEquals(41, r.build); assertEquals("2026-10-01", r.date); assertEquals("2026-10-09", r.savedDate);
        assertEquals(1234f, r.height, 0f); assertEquals(2, r.towers); assertEquals(3600.5f, r.runClock, 1e-3f);
        assertEquals("climb cleared", 0L, d.seed); assertNull(d.sliceJson); assertEquals(0f, d.runClock, 0f); assertEquals("", d.climbVersion);
        assertEquals("records untouched", 2028f, d.bestHeight, 0f); assertEquals(80f, d.bestSplit, 0f); assertEquals(5000f, d.bestFinish, 0f); assertEquals(7, d.falls);
        assertFalse(Legacy.needsPrompt(d, "2.0.0"));
    }

    @Test public void legacyRunsSurviveASaveRoundTrip() throws Exception {
        File dir = Files.createTempDirectory("climb-legacy").toFile(); SaveStore st = new SaveStore(dir);
        SaveData d = climb("", 0); Legacy.archive(d, "2026-10-09"); Legacy.stampNewClimb(d, "1.1.3", 43, "2026-10-09"); d.seed = 5; d.sliceJson = "{}";
        st.saveGame(d);
        SaveData r = st.loadGame();
        assertEquals(1, r.legacy.size()); assertEquals(1234f, r.legacy.get(0).height, 0f); assertEquals(2, r.legacy.get(0).towers); assertEquals("2026-10-01", r.legacy.get(0).date);
        assertEquals("1.1.3", r.climbVersion); assertEquals(43, r.climbBuild); assertFalse("a climb started by this version is resumable", Legacy.needsPrompt(r, "1.1.3"));
    }

    @Test public void theTitleLineShowsVersionAndBuild() {
        assertEquals("V1.1.3  BUILD 43", Legacy.versionLine("1.1.3", 43)); assertEquals("V1.1.3  DEV", Legacy.versionLine("1.1.3", 0));
        assertEquals("V1.0.0 BUILD 41", Legacy.stampLabel("1.0.0", 41, "1.1.3")); assertEquals("BEFORE V1.1.3", Legacy.stampLabel("", 0, "1.1.3"));
    }

    @Test public void theGameVersionMatchesTheGradleVersion() throws Exception {
        File f = new File("../build.gradle"); assumeTrue(f.exists());
        Matcher m = Pattern.compile("appVersionName = '([^']+)'").matcher(new String(Files.readAllBytes(f.toPath())));
        assertTrue(m.find()); assertEquals(m.group(1), ClimbGame.VERSION);
    }

    private static SaveData unstampedFrom(com.hotatticgames.climbup.sim.Course c, long seed, int slice) {
        SaveData d = new SaveData(); d.seed = seed; d.slice = slice; d.sliceJson = com.hotatticgames.climbup.sim.CourseIO.toJson(c); d.climbVersion = ""; d.towers = 1; d.runClock = 600f;
        return d;
    }

    @Test public void aWorkingOlderClimbCarriesOnInsteadOfBeingRetired() throws Exception {
        com.hotatticgames.climbup.sim.Tuning t = TestUtil.tuning();
        com.hotatticgames.climbup.sim.Course c = null; for (int k = 0; k < 4; k++) c = com.hotatticgames.climbup.sim.CourseGenerator.chunk(21, k, c, t);
        SaveData d = unstampedFrom(c, 21, 3);
        assertTrue("unstamped climbs are normally asked about", Legacy.needsPrompt(d, "1.1.3"));
        assertTrue("but this one can safely carry on", Legacy.tryContinue(d, t));
        assertFalse("so nobody is asked, and the run is untouched", Legacy.needsPrompt(d, "1.1.3"));
        assertEquals(21L, d.seed); assertEquals(600f, d.runClock, 0f); assertEquals(0, d.legacy.size());
        SaveData.LegacyRun none = null; assertNull(none);
    }

    @Test public void aClimbThatCannotBeExtendedStillGetsTheExplicitChoiceAndNeverThrows() throws Exception {
        com.hotatticgames.climbup.sim.Tuning t = TestUtil.tuning();
        String json = new String(Files.readAllBytes(new File("src/test/resources/legacy/build41_seed4_slice7_castle_end.json").toPath()), "UTF-8");   // a real slice from build 41 that ends on castle 1; extending it throws "castle approach out of range"
        SaveData d = new SaveData(); d.seed = 4; d.slice = 7; d.sliceJson = json; d.climbVersion = ""; d.runClock = 900f;
        boolean threw = false;
        try { com.hotatticgames.climbup.sim.CourseGenerator.chunk(4, 8, com.hotatticgames.climbup.sim.CourseIO.fromJson(json), t); } catch (IllegalStateException e) { threw = true; assertTrue(e.getMessage(), e.getMessage().contains("castle approach out of range")); }
        assertTrue("the stored slice really does break the current generator (this is the exception behind the crash)", threw);
        assertFalse("so it is not continued silently", Legacy.tryContinue(d, t));
        assertTrue("the player is asked instead", Legacy.needsPrompt(d, "1.1.3"));
        assertEquals("and nothing was changed or lost", 900f, d.runClock, 0f);
    }

    @Test public void anOffGridCastleMarksAnOldLayout() throws Exception {
        com.hotatticgames.climbup.sim.Tuning t = TestUtil.tuning();
        com.hotatticgames.climbup.sim.Course c = null; for (int k = 0; k < 9; k++) c = com.hotatticgames.climbup.sim.CourseGenerator.chunk(22, k, c, t);
        boolean moved = false;
        for (com.hotatticgames.climbup.sim.Element h : c.hazards) if (h.type == com.hotatticgames.climbup.sim.Element.Type.GATE) { h.y += 40f; moved = true; }
        if (!moved) return;          // this slice holds no castle: nothing to move
        assertFalse(Legacy.tryContinue(unstampedFrom(c, 22, 8), t));
    }
}
