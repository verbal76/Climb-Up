package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;

/** The ten-tower times table, the best total, the best infinity distance and the end-of-run record: rules, storage, damaged data, and that a pause or a killed app never changes a time. */
public class RecordsTest {
    private static final long SEED = 4242L;
    private static File tmp() throws Exception { return Files.createTempDirectory("climbrec").toFile(); }

    // ------------------------------------------------------------------ the rules
    @Test public void aLegIsAPersonalBestTheFirstTimeAndWhenFasterButNotWhenSlowerOrEqual() {
        RecordsData r = new RecordsData();
        assertTrue("the first time on a tower is its best", r.legDone(SEED, 0, 120f));
        assertFalse("slower", r.legDone(SEED + 1, 0, 130f));
        assertEquals(120f, r.bestLegs[0], 0f);
        assertFalse("equal is not an improvement", r.legDone(SEED + 2, 0, 120f));
        assertTrue("faster", r.legDone(SEED + 3, 0, 99.5f));
        assertEquals(99.5f, r.bestLegs[0], 0f);
        assertTrue("another tower is independent", r.legDone(SEED + 3, 4, 300f));
        assertEquals(0f, r.bestLegs[1], 0f); assertEquals(300f, r.bestLegs[4], 0f);
    }

    @Test public void theRunKeepsEachLegItsPbMarkAndNeverLosesItWhenTheSameLegIsReported() {
        RecordsData r = new RecordsData();
        r.legDone(SEED, 0, 50f); r.legDone(SEED, 1, 60f);
        RecordsData.Run run = r.current;
        assertEquals(SEED, run.seed); assertEquals(2, run.legsDone());
        assertTrue(run.legPb[0]); assertTrue(run.legPb[1]); assertFalse(run.legPb[2]);
        r.legDone(SEED, 1, 61f);                  // repeated report: still this run's leg, the mark of a real best stays
        assertTrue(run.legPb[1]); assertEquals(60f, r.bestLegs[1], 0f);
        r.endRun(SEED);
        r.legDone(SEED + 1, 0, 55f);              // a slower second run: no mark
        assertFalse(r.current.legPb[0]); assertEquals(50f, r.bestLegs[0], 0f);
        r.legDone(SEED + 1, 1, 45f);              // a faster one: marked
        assertTrue(r.current.legPb[1]); assertEquals(45f, r.bestLegs[1], 0f);
    }

    @Test public void badNumbersAreIgnored() {
        RecordsData r = new RecordsData();
        assertFalse(r.legDone(SEED, -1, 10f)); assertFalse(r.legDone(SEED, RecordsData.LEGS, 10f));
        assertFalse(r.legDone(SEED, 0, 0f)); assertFalse(r.legDone(SEED, 0, -3f)); assertFalse(r.legDone(SEED, 0, Float.NaN)); assertFalse(r.legDone(SEED, 0, Float.POSITIVE_INFINITY));
        assertFalse(r.finish(SEED, 0f)); assertFalse(r.finish(SEED, Float.NaN));
        assertFalse(r.infinity(SEED, 0f)); assertFalse(r.infinity(SEED, -5f)); assertFalse(r.infinity(SEED, Float.NaN));
        for (float b : r.bestLegs) assertEquals(0f, b, 0f);
        assertEquals(0f, r.bestTotal, 0f); assertEquals(0f, r.bestInfinity, 0f);
    }

    @Test public void theTotalForAllTenIsRecordedOnceAsTheBestWhenFaster() {
        RecordsData r = new RecordsData();
        assertTrue(r.finish(SEED, 3723.4f));
        assertTrue(r.current.finished); assertTrue(r.current.totalPb); assertEquals(3723.4f, r.bestTotal, 0f);
        r.endRun(SEED);
        assertFalse("slower", r.finish(SEED + 1, 4000f)); assertFalse(r.current.totalPb); assertEquals(3723.4f, r.bestTotal, 0f);
        r.endRun(SEED + 1);
        assertTrue("faster", r.finish(SEED + 2, 3600f)); assertEquals(3600f, r.bestTotal, 0f);
        assertEquals("the last ended run is the slower one", 4000f, r.last.total, 0f);
    }

    @Test public void theInfinityRecordOnlyGrowsAndFlagsTheRunThatBeatsIt() {
        RecordsData r = new RecordsData();
        assertTrue(r.infinity(SEED, 12f)); assertTrue(r.infinity(SEED, 340.5f));
        r.infinity(SEED, 100f);                      // falling back down the tower does not lower anything
        assertEquals(340.5f, r.bestInfinity, 0f); assertEquals(340.5f, r.current.infinity, 0f); assertTrue(r.current.infinityPb);
        r.endRun(SEED);
        r.infinity(SEED + 1, 200f);
        assertEquals("a shorter second climb keeps the record", 340.5f, r.bestInfinity, 0f);
        assertEquals(200f, r.current.infinity, 0f); assertFalse("and is not marked", r.current.infinityPb);
        r.infinity(SEED + 1, 900f);
        assertEquals(900f, r.bestInfinity, 0f); assertTrue(r.current.infinityPb);
    }

    @Test public void aNewClimbStartsCleanAndTheEndedRunIsKeptForTheSummary() {
        RecordsData r = new RecordsData();
        assertNull("nothing to show yet", r.shown(SEED));
        r.legDone(SEED, 0, 70f); r.legDone(SEED, 1, 80f); r.finish(SEED, 900f); r.infinity(SEED, 50f);
        assertSame(r.current, r.shown(SEED));
        assertNull("a different climb has nothing of this one", r.shown(SEED + 5));
        RecordsData.Run ended = r.endRun(SEED);
        assertSame(ended, r.last); assertTrue(r.hasLast);
        assertEquals(2, ended.legsDone()); assertTrue(ended.finished); assertEquals(900f, ended.total, 0f); assertEquals(50f, ended.infinity, 0f);
        assertEquals("the run in progress is cleared", 0, r.current.legsDone()); assertFalse(r.current.finished);
        assertSame("with nothing in progress the last run is shown", ended, r.shown(SEED));
        r.legDone(SEED + 9, 0, 75f);
        assertSame("a new climb shows itself once it has a time", r.current, r.shown(SEED + 9));
        assertSame("while the old climb's number shows the last ended run", ended, r.shown(SEED));
        assertEquals("a different seed is a different run", 1, r.current.legsDone()); assertEquals(SEED + 9, r.current.seed);
        // a climb replaced by NEW RUN (never ended): its leftovers are dropped when the next climb records
        r.legDone(SEED + 10, 0, 76f);
        assertEquals(SEED + 10, r.current.seed); assertEquals(1, r.current.legsDone());
    }

    @Test public void endingARunThatWasNeverRecordedGivesAnEmptySummaryNotACrash() {
        RecordsData r = new RecordsData();
        RecordsData.Run e = r.endRun(SEED);
        assertNotNull(e); assertEquals(0, e.legsDone()); assertFalse(e.finished);
    }

    @Test public void catchUpFillsInOnlyWhatIsMissingAndIsRepeatable() {
        RecordsData r = new RecordsData();
        float[] splits = {61.5f, 70.25f, 99f};
        r.catchUp(SEED, splits, 3, false, 0f);
        assertEquals(3, r.current.legsDone()); assertEquals(70.25f, r.current.legs[1], 0f); assertEquals(61.5f, r.bestLegs[0], 0f);
        r.catchUp(SEED, splits, 3, false, 0f);
        assertEquals(3, r.current.legsDone());
        r.legDone(SEED, 0, 50f);                     // a leg already recorded is not overwritten by the older figure
        r.catchUp(SEED, splits, 3, true, 800f);
        assertEquals(50f, r.current.legs[0], 0f); assertTrue(r.current.finished); assertEquals(800f, r.bestTotal, 0f);
        r.catchUp(SEED, null, 5, false, 0f);         // nothing to catch up from: harmless
    }

    // ------------------------------------------------------------------ storage
    private static RecordsData busy() {
        RecordsData r = new RecordsData();
        for (int i = 0; i < 10; i++) r.legDone(SEED, i, 61.5f + i * 3.25f);
        r.finish(SEED, 744.9f); r.infinity(SEED, 1234.5f);
        r.endRun(SEED);
        r.legDone(SEED + 1, 0, 59.5f); r.legDone(SEED + 1, 1, 80f);
        return r;
    }

    @Test public void recordsSurviveJsonAndTheSaveFileExactly() throws Exception {
        RecordsData a = busy();
        RecordsData b = RecordsData.fromJson(a.toJson());
        assertEquals(a.toJson(), b.toJson());
        assertEquals(59.5f, b.bestLegs[0], 0f); assertEquals(744.9f, b.bestTotal, 0f); assertEquals(1234.5f, b.bestInfinity, 0f);
        assertTrue(b.hasLast); assertTrue(b.last.finished); assertEquals(61.5f, b.last.legs[0], 0f); assertEquals(1234.5f, b.last.infinity, 0f); assertTrue(b.last.infinityPb);
        assertEquals(SEED + 1, b.current.seed); assertEquals(2, b.current.legsDone()); assertTrue(b.current.legPb[0]);

        File dir = tmp(); SaveStore st = new SaveStore(dir);
        SaveData d = new SaveData(); d.bestHeight = 77f;
        d.records().legDone(SEED, 3, 100.25f); d.records().finish(SEED, 999.9f); d.records().infinity(SEED, 42f);
        st.saveGame(d);
        String file = new String(Files.readAllBytes(new File(dir, "save.json").toPath()), "UTF-8");
        assertFalse("the helper object itself is not written, only the records text", file.contains("recordsObj"));
        SaveData r = st.loadGame();
        assertEquals(77f, r.bestHeight, 0f);
        assertEquals(100.25f, r.records().bestLegs[3], 0f); assertEquals(999.9f, r.records().bestTotal, 0f); assertEquals(42f, r.records().bestInfinity, 0f);
        assertEquals(SEED, r.records().current.seed); assertTrue(r.records().current.finished);
        r.records().infinity(SEED, 60f); st.saveGame(r);                        // a second round trip after a change
        assertEquals(60f, st.loadGame().records().bestInfinity, 0f);
        assertFalse(st.recoveredFromCorruption);
    }

    @Test public void recordsAreKeptWhenTheClimbIsForgotten() throws Exception {
        SaveData d = new SaveData(); d.seed = SEED; d.towers = 1; d.splits = new float[]{80f}; d.towerTotals = new float[]{80f};
        d.records().legDone(SEED, 0, 80f); d.records().infinity(SEED, 10f);
        RunRecord.forgetClimb(d);
        assertEquals(80f, d.records().bestLegs[0], 0f); assertEquals(10f, d.records().bestInfinity, 0f);
    }

    @Test public void damagedOrUnreadableRecordsFallBackToEmptyAndNeverTakeTheRestOfTheSaveWithThem() throws Exception {
        for (String bad : new String[]{null, "", "   ", "not json at all", "{", "{\"bestLegs\":\"x\"}", "[1,2,3]", "{\"current\":5}", "\u0000\u0001"}) {
            RecordsData r = RecordsData.fromJson(bad);
            assertNotNull(bad, r); assertEquals(RecordsData.VERSION, r.version);
            assertEquals(0f, r.bestTotal, 0f); assertEquals(RecordsData.LEGS, r.bestLegs.length); assertNotNull(r.current); assertNotNull(r.last);
        }
        File dir = tmp(); SaveStore st = new SaveStore(dir);
        st.writeAtomic("save.json", "{\"version\":6,\"bestHeight\":321,\"falls\":4,\"recordsJson\":\"{ this is damaged\"}");
        SaveData d = st.loadGame();
        assertFalse("a damaged records text must not make the whole save look corrupt", st.recoveredFromCorruption);
        assertEquals(321f, d.bestHeight, 0f); assertEquals(4, d.falls);
        assertEquals(0f, d.records().bestTotal, 0f);
        d.records().legDone(SEED, 0, 90f); st.saveGame(d);                      // and it is replaced by good text at the next save
        assertEquals(90f, st.loadGame().records().bestLegs[0], 0f);
    }

    @Test public void nonsenseNumbersAreRepairedOnLoad() {
        String text = "{\"version\":1,\"bestLegs\":[-5,NaN,12.5,1,2,3,4,5,6,7,8,9,10,11,12],\"bestTotal\":-1,\"bestInfinity\":-2,"
                + "\"current\":{\"seed\":9,\"legs\":[10],\"legPb\":[true,true,true],\"finished\":true,\"total\":0,\"totalPb\":true,\"infinity\":0,\"infinityPb\":true},\"last\":null,\"hasLast\":false}";
        RecordsData r = RecordsData.fromJson(text);
        assertEquals(RecordsData.LEGS, r.bestLegs.length);
        assertEquals("negative", 0f, r.bestLegs[0], 0f); assertEquals(12.5f, r.bestLegs[2], 0f);
        assertEquals(0f, r.bestTotal, 0f); assertEquals(0f, r.bestInfinity, 0f);
        assertEquals(RecordsData.LEGS, r.current.legs.length); assertEquals(10f, r.current.legs[0], 0f); assertTrue(r.current.legPb[0]);
        assertFalse("a leg with no time has no mark", r.current.legPb[1]);
        assertFalse("finished with no time is not finished", r.current.finished); assertFalse(r.current.totalPb); assertFalse(r.current.infinityPb);
        assertNotNull(r.last);
    }

    @Test public void olderAndNewerLayoutsLoadWithDefaults() {
        RecordsData older = RecordsData.fromJson("{\"version\":1,\"bestTotal\":812.5}");
        assertEquals(812.5f, older.bestTotal, 0f); assertEquals(0f, older.bestLegs[0], 0f); assertFalse(older.hasLast);
        RecordsData newer = RecordsData.fromJson("{\"version\":7,\"bestTotal\":700,\"someFutureField\":{\"a\":1}}");
        assertEquals(700f, newer.bestTotal, 0f);
    }

    @Test public void aSaveFromBeforeTheRecordsExistedKeepsItsBestFinishAsTheBestTotal() throws Exception {
        File dir = tmp(); SaveStore st = new SaveStore(dir);
        st.writeAtomic("save.json", "{\"version\":6,\"bestFinish\":3723.4,\"bestHeight\":5200}");
        SaveData d = st.loadGame();
        assertEquals(3723.4f, d.records().bestTotal, 0f);
        assertEquals(0f, d.records().bestInfinity, 0f);
        d.records().finish(SEED, 4000f);                     // slower than the old record: not a best
        assertFalse(d.records().current.totalPb);
        st.saveGame(d);
        SaveData again = st.loadGame();
        assertEquals(3723.4f, again.records().bestTotal, 0f);
        SaveData fresh = new SaveData(); assertEquals(0f, fresh.records().bestTotal, 0f);
        st.writeAtomic("save.json", "{\"version\":3,\"bestHeight\":70,\"falls\":2}");        // an old save with neither
        SaveData v3 = st.loadGame(); assertEquals(0f, v3.records().bestTotal, 0f); assertEquals(70f, v3.bestHeight, 0f);
    }

    // ------------------------------------------------------------------ time never moves while not playing
    /** The play loop's rule, as PlayScreen applies it: the clock steps only while the game is PLAYING and the climb has begun (first input); a pause, the settings screen, the times table or a backgrounded app steps nothing. */
    private static void frames(SaveData sd, int n, boolean playing, boolean live) { for (int i = 0; i < n; i++) if (playing) RunRecord.tick(sd, 1f / 60f, live); }

    @Test public void pausingNeverAddsTimeAndTheClockContinuesFromTheExactTick() throws Exception {
        SaveData sd = new SaveData(); sd.seed = SEED;
        frames(sd, 600, true, false);
        assertEquals("before the first input nothing counts", 0L, sd.runTicks);
        frames(sd, 600, true, true);                           // ten seconds of play
        long t0 = sd.runTicks; float c0 = sd.runClock;
        assertEquals(600L, t0); assertEquals(10f, c0, 1e-4f);
        frames(sd, 60 * 3600, false, true);                    // an hour on the pause menu / times table / in the background
        assertEquals(t0, sd.runTicks); assertEquals(c0, sd.runClock, 0f);
        // the app is killed while paused: the time that comes back is the time that was saved
        File dir = tmp(); SaveStore st = new SaveStore(dir);
        sd.towers = 1; sd.splits = new float[]{c0}; sd.towerTotals = new float[]{c0}; sd.records().legDone(SEED, 0, c0);
        st.saveGame(sd);
        SaveData back = st.loadGame();
        assertEquals(t0, back.runTicks); assertEquals(c0, back.runClock, 0f); assertEquals(c0, back.splits[0], 0f); assertEquals(c0, back.records().current.legs[0], 0f);
        frames(back, 60, false, false);                        // the relaunch is paused until the player touches the controls
        assertEquals(t0, back.runTicks);
        frames(back, 90, true, true);
        assertEquals(t0 + 90, back.runTicks); assertEquals((t0 + 90) / 60.0, back.runClock, 1e-4);
    }

    @Test public void aWholeRunFromFirstStepToEndOfRunGivesTheSameTimesAfterEveryKindOfInterruption() throws Exception {
        File dir = tmp(); SaveStore st = new SaveStore(dir);
        SaveData sd = new SaveData(); sd.seed = SEED;
        float[] legSeconds = {61.5f, 70.25f, 55f, 90f, 66f, 120.5f, 80f, 77.75f, 101f, 64f};
        for (int i = 0; i < 10; i++) {
            frames(sd, Math.round(legSeconds[i] * 60f), true, true);
            frames(sd, 1234, false, true);                                  // a pause somewhere in every leg
            float split = sd.runClock - sd.towerStartClock;
            sd.splits = java.util.Arrays.copyOf(sd.splits, i + 1); sd.splits[i] = split;
            sd.towerTotals = java.util.Arrays.copyOf(sd.towerTotals, i + 1); sd.towerTotals[i] = sd.runClock;
            sd.towers = i + 1; sd.towerStartClock = sd.runClock;
            sd.records().legDone(SEED, i, split);
            if (i % 3 == 0) { st.saveGame(sd); sd = st.loadGame(); }         // killed and relaunched now and then
        }
        assertEquals(1, RunRecord.complete(sd));
        assertTrue(sd.records().finish(SEED, sd.finishTime));
        float total = sd.finishTime;
        float sum = 0f; for (float s : legSeconds) sum += s;
        assertEquals("the total is the sum of the legs that were played, and no pause is in it", sum, total, 0.2f);
        st.saveGame(sd); SaveData back = st.loadGame();
        RecordsData.Run run = back.records().current;
        assertEquals(10, run.legsDone()); assertTrue(run.finished); assertEquals(total, run.total, 0f); assertTrue(run.totalPb);
        for (int i = 0; i < 10; i++) { assertEquals(sd.splits[i], run.legs[i], 0f); assertTrue(run.legPb[i]); assertEquals(sd.splits[i], back.records().bestLegs[i], 0f); }
        RecordsData.Run ended = back.records().endRun(SEED);
        assertSame(ended, back.records().last);
        assertEquals(total, back.records().bestTotal, 0f);
        // a second, slower run: no marks, the bests stay
        RecordsData rec = back.records();
        for (int i = 0; i < 10; i++) rec.legDone(SEED + 1, i, legSeconds[i] + 5f);
        rec.finish(SEED + 1, total + 50f);
        for (int i = 0; i < 10; i++) assertFalse(rec.current.legPb[i]);
        assertFalse(rec.current.totalPb); assertEquals(total, rec.bestTotal, 0f);
    }

    // ------------------------------------------------------------------ what the screens show
    @Test public void timesAreWrittenPlainlyAndMissingOnesAsDashes() {
        assertEquals("--:--.-", TimesScreen.time(0f));
        assertEquals("1:01.5", TimesScreen.time(61.5f));
        assertEquals("1:02:03.4", TimesScreen.time(3723.4f));
        assertEquals("--", TimesScreen.metres(0f));
        assertEquals("1234 M", TimesScreen.metres(1234.9f));
    }
}
