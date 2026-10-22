package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;

/**
 * Owner (Build 71): assists (slower speed, jump forgiveness) may be used, but an assisted run never sets or overwrites a best time. The climb itself (castles,
 * splits, the finish) is unchanged; the run is assisted from the first timed step with an assist on, even if the assist is switched off again.
 */
public class AssistedRunTest {
    private static SaveData climb(long seed) { SaveData sd = new SaveData(); sd.seed = seed; return sd; }
    private static void play(SaveData sd, int steps, boolean assist) { for (int i = 0; i < steps; i++) { RunRecord.tick(sd, 1f / 60f, true); RunRecord.noteAssist(sd, assist); } }

    @Test public void anAssistOnATimedStepMarksTheRunForGood() {
        SaveData sd = climb(7);
        play(sd, 600, false);
        assertFalse("no assist, no mark", sd.assisted);
        play(sd, 1, true);
        assertTrue(sd.assisted);
        play(sd, 600, false);
        assertTrue("switching the assist off again does not clear it", sd.assisted);
        assertTrue("the times table's run is marked too", sd.records().current.assisted);
        assertEquals("the clock itself is unchanged by the mark", 1201, sd.runTicks);
    }

    @Test public void anAssistedFinishCompletesTheRunButIsNoRecord() {
        SaveData sd = climb(7); sd.bestFinish = 4000f;
        play(sd, 60 * 60, true);                                  // a minute, far faster than the best
        assertEquals("not a record", 0, RunRecord.complete(sd));
        assertTrue("the run is still complete", sd.finished);
        assertEquals(60f, sd.finishTime, 1e-3f);
        assertEquals(60f, sd.lastFinish, 1e-3f);
        assertEquals("the best finish stands", 4000f, sd.bestFinish, 0f);

        SaveData first = climb(9);                                // no best yet: an assisted finish still does not become one
        play(first, 120, true);
        assertEquals(0, RunRecord.complete(first));
        assertEquals(0f, first.bestFinish, 0f);
    }

    @Test public void assistedLegsAndFinishesSetNoBestAnywhere() {
        RecordsData r = new RecordsData();
        assertTrue("an unassisted leg is a best", r.legDone(7, 0, 300f));
        r.markAssisted(7);
        assertFalse(r.legDone(7, 1, 100f));
        assertEquals("the assisted leg is kept with the run", 100f, r.current.legs[1], 0f);
        assertEquals("but sets no global best", 0f, r.bestLegs[1], 0f);
        assertFalse("nor a course best", r.current.legSeedPb[1]);
        assertEquals(0f, r.seedBests(7, com.hotatticgames.climbup.sim.CourseGenerator.RULES_VERSION).bestLegs[1], 0f);
        assertEquals("the earlier, unassisted leg's best stands", 300f, r.bestLegs[0], 0f);

        assertFalse(r.finish(7, 900f));
        assertTrue("the finish completes the run", r.current.finished);
        assertEquals(900f, r.current.total, 0f);
        assertEquals("no best total", 0f, r.bestTotal, 0f);
        assertEquals(0f, r.seedBests(7, com.hotatticgames.climbup.sim.CourseGenerator.RULES_VERSION).bestTotal, 0f);
    }

    @Test public void anAssistedLegIsStillComparedWithTheCourseBest() {
        RecordsData r = new RecordsData();
        r.legDone(7, 0, 300f); r.endRun(7);                       // an earlier unassisted run on the same course
        r.markAssisted(7);
        r.legDone(7, 0, 250f);
        assertEquals("the faster/slower difference still shows", 300f, r.current.legSeedPrev[0], 0f);
        assertEquals("but the course best is unchanged", 300f, r.seedBests(7, com.hotatticgames.climbup.sim.CourseGenerator.RULES_VERSION).bestLegs[0], 0f);
    }

    @Test public void aFreshClimbIsUnassistedAgainAndCanSetBests() {
        SaveData sd = climb(7);
        play(sd, 10, true);
        RunRecord.forgetClimb(sd); sd.seed = 8;
        assertFalse(sd.assisted);
        play(sd, 600, false);
        assertEquals("a new climb without assists is a record again", 1, RunRecord.complete(sd));
        assertEquals(10f, sd.bestFinish, 1e-3f);
    }

    @Test public void theMarkSurvivesSaveAndResume() throws Exception {
        File dir = Files.createTempDirectory("assist").toFile();
        SaveStore store = new SaveStore(dir);
        SaveData sd = climb(7);
        play(sd, 30, true);
        store.saveGame(sd);
        SaveData back = new SaveStore(dir).loadGame();
        assertTrue("the climb is still assisted after a restart", back.assisted);
        assertTrue("and so is its run in the times table", back.records().current.assisted);
        assertFalse("a leg after the resume is still no best", back.records().legDone(7, 0, 50f));
    }

    @Test public void anOlderSaveWithoutTheFieldReadsAsUnassisted() throws Exception {
        File dir = Files.createTempDirectory("assist-old").toFile();
        SaveData sd = climb(7); play(sd, 30, false); new SaveStore(dir).saveGame(sd);
        File f = new File(dir, "save.json");
        String text = new String(Files.readAllBytes(f.toPath()), "UTF-8");
        String old = text.replaceAll("\\\\?\"?assisted\\\\?\"?\\s*:\\s*false\\s*,?", "");      // a Build 71 save, written before the field existed
        assertFalse(old.contains("assisted"));
        Files.write(f.toPath(), old.getBytes("UTF-8"));
        SaveData back = new SaveStore(dir).loadGame();
        assertEquals(7L, back.seed);
        assertFalse(back.assisted);
    }
}
