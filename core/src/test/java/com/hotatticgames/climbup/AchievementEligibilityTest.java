package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.sim.*;
import java.util.List;
import org.junit.Test;

/**
 * Owner achievement policy (2026-10-30), release hardening: competitive achievements (SPEED_SUMMIT, PERFECT_SUMMIT, CLEAN_RUN,
 * NO_CHECKPOINT_WORLD, INFINITY_BEST) only for eligible climbs - not assisted, mixed-rules, world picker or developer autopilot - in every
 * difficulty; every other achievement stays open to assisted play; NO_CHECKPOINT_WORLD survives a quit and resume.
 */
public class AchievementEligibilityTest {
    static final String[] COMPETITIVE = {AchievementRules.SPEED_SUMMIT, AchievementRules.PERFECT_SUMMIT, AchievementRules.CLEAN_RUN,
        AchievementRules.NO_CHECKPOINT_WORLD, AchievementRules.INFINITY_BEST};

    /** A clean, fast summit with a beaten endless record and a world crossed without a fall: every competitive achievement's condition holds. */
    static List<String> summitRun(boolean competitive) {
        AchievementRules r = new AchievementRules();
        AchievementRules.Tick t = new AchievementRules.Tick();
        t.competitive = competitive; t.mode = Sim.Mode.GROUND; t.zone = 0; t.infinityMeters = -1;
        java.util.List<String> all = new java.util.ArrayList<>();
        all.addAll(r.step(t));                                         // world 0, no fall
        t.zone = 1; all.addAll(r.step(t));                             // crossed into world 1 without a fall-back
        t.events = Sim.EV_FINISH; t.towersOpened = 10; t.finishTimeSec = 1800f; t.runFalls = 0; t.runHits = 0;
        all.addAll(r.step(t));
        t.events = 0; t.bestInfinityMeters = 100; t.infinityMeters = 150; all.addAll(r.step(t));
        return all;
    }

    @Test public void competitiveAchievementsNeedAnEligibleClimb() {
        List<String> eligible = summitRun(true), ineligible = summitRun(false);
        for (String id : COMPETITIVE) {
            assertTrue(id + " on an eligible climb", eligible.contains(id));
            assertFalse(id + " never on an ineligible climb", ineligible.contains(id));
        }
        for (String id : new String[]{AchievementRules.SUMMIT, AchievementRules.TOWER_1, AchievementRules.TOWER_5, AchievementRules.INFINITY_ENTER})
            assertTrue(id + " stays open to assisted play", ineligible.contains(id));
    }

    @Test public void eligibilityExcludesAssistMixedRulesPickerAndAutopilotInEveryDifficulty() {
        for (int d = 0; d <= 2; d++) {
            SaveData sd = new SaveData(); sd.difficulty = d;
            assertTrue("difficulty " + d + " is eligible (not the NORMAL-only record gate)", RunRecord.competitiveEligible(sd, false));
            assertFalse("autopilot", RunRecord.competitiveEligible(sd, true));
            sd.assisted = true; assertFalse("assisted", RunRecord.competitiveEligible(sd, false)); sd.assisted = false;
            sd.climbNotComparable = true; assertFalse("mixed rules", RunRecord.competitiveEligible(sd, false)); sd.climbNotComparable = false;
            sd.devPicked = true; assertFalse("world picker", RunRecord.competitiveEligible(sd, false));
        }
        SaveData p = new SaveData(); p.difficulty = 1;
        assertFalse("the personal-best gate stays NORMAL-only", RunRecord.setsRecords(p));
    }

    /** An achievement earned while eligible stays earned when the climb later becomes ineligible (an assist switched on afterwards). */
    @Test public void anAchievementEarnedBeforeAnAssistStaysEarned() {
        AchievementRules r = new AchievementRules();
        AchievementRules.Tick t = new AchievementRules.Tick();
        t.competitive = true; t.mode = Sim.Mode.GROUND; t.zone = 0; t.infinityMeters = -1;
        r.step(t); t.zone = 1;
        assertTrue("earned while eligible", r.step(t).contains(AchievementRules.NO_CHECKPOINT_WORLD));
        t.competitive = false; t.zone = 2;
        assertFalse("the next world, assisted: not earned", r.step(t).contains(AchievementRules.NO_CHECKPOINT_WORLD));
    }

    /** A fall-back in the current world is not forgotten by a quit and resume; a climb saved before the tracking existed counts as fallen in. */
    @Test public void noCheckpointWorldSurvivesAResume() {
        AchievementRules.Tick t = new AchievementRules.Tick();
        t.competitive = true; t.mode = Sim.Mode.GROUND; t.infinityMeters = -1;
        AchievementRules before = new AchievementRules();
        t.zone = 2; before.step(t);
        t.events = Sim.EV_RESPAWN; before.step(t); t.events = 0;
        AchievementRules resumed = new AchievementRules();
        resumed.seedWorld(before.trackedWorld(), before.fellInTrackedWorld());
        resumed.step(t); t.zone = 3;
        assertFalse("the fall before the quit still counts", resumed.step(t).contains(AchievementRules.NO_CHECKPOINT_WORLD));
        AchievementRules legacy = new AchievementRules(); legacy.seedWorld(-2, false);
        t.zone = 2; legacy.step(t); t.zone = 3;
        assertFalse("unknown history: not awarded for that world", legacy.step(t).contains(AchievementRules.NO_CHECKPOINT_WORLD));
        t.zone = 4;
        assertTrue("the next world, crossed cleanly, is", legacy.step(t).contains(AchievementRules.NO_CHECKPOINT_WORLD));
        AchievementRules fresh = new AchievementRules(); fresh.seedWorld(-1, false);
        t.zone = 0; fresh.step(t); t.zone = 1;
        assertTrue("a fresh climb tracks its first world", fresh.step(t).contains(AchievementRules.NO_CHECKPOINT_WORLD));
    }

    /** A fresh climb resets the tracking; the save keeps it with the climb (a new field with a default: older saves still load). */
    @Test public void aFreshClimbResetsTheWorldTracking() {
        SaveData sd = new SaveData();
        assertEquals("an older save without the field: unknown", -2, sd.achZone);
        sd.achZone = 3; sd.achZoneFell = true;
        RunRecord.forgetClimb(sd);
        assertEquals(-1, sd.achZone); assertFalse(sd.achZoneFell);
    }

    // ---------------------------------------------------------------- owner decisions C1-C3 and the three additional fixes (2026-10-30)

    /** C1: a save from before the lifetime count starts it at the climb's falls (nothing earlier was kept); one already migrated keeps its own. */
    @Test public void lifetimeFallsMigrateFromAnOlderSave() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("b83-falls").toFile();
        java.nio.file.Files.write(new java.io.File(dir, "save.json").toPath(), "{\"version\":7,\"falls\":7,\"bestHeight\":10}".getBytes("UTF-8"));
        SaveData old = new SaveStore(dir).loadGame();
        assertEquals("migrated from the climb's falls", 7, old.lifetimeFalls);
        old.lifetimeFalls = 40; old.falls = 2; new SaveStore(dir).saveGame(old);
        assertEquals("a migrated save keeps its lifetime count", 40, new SaveStore(dir).loadGame().lifetimeFalls);
    }

    /** C1: Steam's count is merged with max, never added (no double counting when the same falls arrive from both sides). */
    @Test public void steamFallsMergeWithMaxNeverAdd() {
        SaveData sd = new SaveData(); sd.lifetimeFalls = 30;
        assertFalse("a smaller Steam value (the old per-climb maximum) changes nothing", AchievementDelivery.mergeLifetimeFalls(sd, 12));
        assertEquals(30, sd.lifetimeFalls);
        assertTrue("a larger one (another machine) is adopted", AchievementDelivery.mergeLifetimeFalls(sd, 45));
        assertEquals(45, sd.lifetimeFalls);
        assertFalse("the same value again: no change, not 90", AchievementDelivery.mergeLifetimeFalls(sd, 45));
    }

    /** C1: FALL_25 counts falls across climbs (the lifetime count, not the per-climb one). */
    @Test public void fallAchievementsCountAcrossClimbs() {
        AchievementRules r = new AchievementRules();
        AchievementRules.Tick t = new AchievementRules.Tick();
        t.mode = Sim.Mode.GROUND; t.infinityMeters = -1; t.runFalls = 3; t.totalFalls = 25;
        List<String> got = r.step(t);
        assertTrue(got.contains(AchievementRules.FALL_25)); assertTrue(got.contains(AchievementRules.FIRST_FALL));
    }

    /** A fake platform: Steam unavailable, then available (stats delivered), counting every unlock call. */
    static final class FakeSteam implements com.hotatticgames.climbup.platform.Achievements {
        boolean ready; final java.util.Set<String> held = new java.util.HashSet<>(); int calls;
        @Override public void unlock(String id) { calls++; if (ready) held.add(id); }
        @Override public boolean unlocked(String id) { return ready && held.contains(id); }
    }

    /** Offline earning is kept, delivered once Steam is back, and never awarded twice. */
    @Test public void achievementsEarnedOfflineAreDeliveredLaterExactlyOnce() {
        SaveData sd = new SaveData(); FakeSteam steam = new FakeSteam();
        assertTrue(AchievementDelivery.earn(sd, steam, AchievementRules.SUMMIT));
        assertTrue("kept in the save while Steam cannot take it", AchievementDelivery.pending(sd).contains(AchievementRules.SUMMIT));
        assertFalse("retrying offline changes nothing", AchievementDelivery.retry(sd, steam));
        steam.ready = true;
        assertTrue("delivered on reconnect", AchievementDelivery.retry(sd, steam));
        assertTrue(steam.held.contains(AchievementRules.SUMMIT));
        assertTrue("and no longer pending", AchievementDelivery.pending(sd).isEmpty());
        int before = steam.calls;
        assertFalse(AchievementDelivery.retry(sd, steam));
        assertEquals("nothing offered again once confirmed", before, steam.calls);
        assertFalse("earned again later (a threshold achievement re-fires): already held, no change", AchievementDelivery.earn(sd, steam, AchievementRules.SUMMIT));
        assertTrue(AchievementDelivery.pending(sd).isEmpty());
    }

    /** The pending list survives a save round trip (a quit before Steam came back). */
    @Test public void pendingAchievementsSurviveARestart() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("b83-pending").toFile();
        SaveData sd = new SaveData(); AchievementDelivery.earn(sd, new FakeSteam(), AchievementRules.SPACE);
        new SaveStore(dir).saveGame(sd);
        assertTrue(AchievementDelivery.pending(new SaveStore(dir).loadGame()).contains(AchievementRules.SPACE));
    }

    /** ALL_WORLDS credit survives a restart: worlds visited earlier count. */
    @Test public void allWorldsCreditSurvivesARestart() {
        AchievementRules r = new AchievementRules();
        r.seedWorldsSeen(0b01111);          // worlds 0-3 seen in earlier sessions
        AchievementRules.Tick t = new AchievementRules.Tick();
        t.mode = Sim.Mode.GROUND; t.infinityMeters = -1; t.zone = AchievementRules.SPACE_WORLD;
        assertTrue(r.step(t).contains(AchievementRules.ALL_WORLDS));
        assertEquals(AchievementRules.ALL_WORLDS_MASK, r.worldsSeen());
    }

    /** Developer autopilot is an assist: no personal best, split, finish or endless record, and no competitive achievement. */
    @Test public void autopilotSetsNoRecordAndIsNeverCompetitive() {
        SaveData sd = new SaveData(); sd.runClock = 100f;
        RunRecord.noteAssist(sd, true);          // what PlayScreen does for every timed step under the autopilot
        assertFalse(RunRecord.setsRecords(sd));
        assertEquals(0, RunRecord.towerBests(sd, 0, 50f, 100f));
        assertEquals(0, RunRecord.complete(sd));
        assertFalse(RunRecord.competitiveEligible(sd, true));
    }
}
