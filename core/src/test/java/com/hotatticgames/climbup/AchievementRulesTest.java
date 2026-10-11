package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.render.Palette;
import com.hotatticgames.climbup.sim.AchievementRules;
import com.hotatticgames.climbup.sim.Element;
import com.hotatticgames.climbup.sim.Sim;
import com.hotatticgames.climbup.sim.Tuning;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * The achievement MAPPING is pure core logic, so every rule, threshold and the clean/perfect/first-fall distinctions
 * are proven here with no Steam client. {@code SteamManager}/{@code SteamAchievements} only forward these ids.
 */
public class AchievementRulesTest {

    /** A Tick with harmless defaults (not in the endless section, world 0, nothing landed). */
    private static AchievementRules.Tick t() {
        AchievementRules.Tick t = new AchievementRules.Tick();
        t.mode = Sim.Mode.AIR;
        t.infinityMeters = -1;
        return t;
    }

    private static Set<String> all(List<String> l) { return new HashSet<>(l); }

    // ---- id table ----------------------------------------------------------------------------------------------------

    @Test public void thereAreExactlyTwentySevenDistinctIds() {
        assertEquals(27, AchievementRules.ALL_IDS.length);
        assertEquals("ids are unique", 27, new HashSet<>(java.util.Arrays.asList(AchievementRules.ALL_IDS)).size());
        for (String id : AchievementRules.ALL_IDS) assertTrue("api id convention: " + id, id.startsWith("ACH_"));
    }

    @Test public void thresholdConstantsMatchTheEngine() {
        assertEquals("world count tracks Palette", Palette.ZONES, AchievementRules.WORLD_COUNT);
        assertEquals("space world tracks Palette", Palette.SPACE, AchievementRules.SPACE_WORLD);
        assertEquals("ten towers tracks Tuning.finishCastle", new Tuning().finishCastle, AchievementRules.TOWERS_TOTAL);
        assertEquals((1 << AchievementRules.TOWERS_TOTAL) - 1, AchievementRules.ALL_TOWERS_MASK);
        assertEquals((1 << AchievementRules.WORLD_COUNT) - 1, AchievementRules.ALL_WORLDS_MASK);
    }

    // ---- traversal feats ---------------------------------------------------------------------------------------------

    @Test public void fingertipAndRopeTopAreDistinguishedByResultingMode() {
        AchievementRules a = new AchievementRules();
        AchievementRules.Tick t = t(); t.events = Sim.EV_PULL; t.mode = Sim.Mode.PULLUP;
        Set<String> got = all(a.step(t));
        assertTrue(got.contains(AchievementRules.FINGERTIP));
        assertFalse(got.contains(AchievementRules.ROPE_TOP));

        AchievementRules b = new AchievementRules();
        AchievementRules.Tick u = t(); u.events = Sim.EV_PULL; u.mode = Sim.Mode.BEAM;
        Set<String> got2 = all(b.step(u));
        assertTrue(got2.contains(AchievementRules.ROPE_TOP));
        assertFalse(got2.contains(AchievementRules.FINGERTIP));
    }

    @Test public void midairCatchOnlyCountsTranslatingMovers() {
        AchievementRules a = new AchievementRules();
        AchievementRules.Tick t = t(); t.events = Sim.EV_LAND; t.mode = Sim.Mode.GROUND; t.landedType = Element.Type.MOVE_H;
        assertTrue(all(a.step(t)).contains(AchievementRules.MIDAIR_CATCH));

        AchievementRules b = new AchievementRules();
        AchievementRules.Tick u = t(); u.events = Sim.EV_LAND; u.mode = Sim.Mode.GROUND; u.landedType = Element.Type.STATIC;
        assertFalse(all(b.step(u)).contains(AchievementRules.MIDAIR_CATCH));
    }

    @Test public void crabOffAndBounce() {
        AchievementRules a = new AchievementRules();
        assertTrue(all(a.step(ev(Sim.EV_CRAB_OFF))).contains(AchievementRules.CRAB_OFF));
        AchievementRules b = new AchievementRules();
        assertTrue(all(b.step(ev(Sim.EV_BOUNCE))).contains(AchievementRules.BOUNCE));
    }

    private static AchievementRules.Tick ev(int events) { AchievementRules.Tick t = t(); t.events = events; return t; }

    // ---- towers / summit ---------------------------------------------------------------------------------------------

    @Test public void towerMilestonesFireOnceAtTheRightCount() {
        AchievementRules a = new AchievementRules();
        AchievementRules.Tick t = t(); t.towersOpened = 1;
        assertTrue(all(a.step(t)).contains(AchievementRules.TOWER_1));
        assertFalse("not fired again", all(a.step(t)).contains(AchievementRules.TOWER_1));
        t.towersOpened = 5;
        assertTrue(all(a.step(t)).contains(AchievementRules.TOWER_5));
    }

    @Test public void cleanRunPerfectAndSpeedAreDistinct() {
        // flawless, fast: all four
        AchievementRules a = new AchievementRules();
        AchievementRules.Tick t = t(); t.events = Sim.EV_FINISH; t.runFalls = 0; t.runHits = 0; t.finishTimeSec = 1000f;
        Set<String> flawless = all(a.step(t));
        assertTrue(flawless.contains(AchievementRules.SUMMIT));
        assertTrue(flawless.contains(AchievementRules.CLEAN_RUN));
        assertTrue(flawless.contains(AchievementRules.PERFECT_SUMMIT));
        assertTrue(flawless.contains(AchievementRules.SPEED_SUMMIT));

        // no fall-backs but took hazard hits: clean, NOT perfect
        AchievementRules b = new AchievementRules();
        AchievementRules.Tick u = t(); u.events = Sim.EV_FINISH; u.runFalls = 0; u.runHits = 3; u.finishTimeSec = 1000f;
        Set<String> hit = all(b.step(u));
        assertTrue(hit.contains(AchievementRules.CLEAN_RUN));
        assertFalse(hit.contains(AchievementRules.PERFECT_SUMMIT));

        // fell back to checkpoints, slow: summit only
        AchievementRules c = new AchievementRules();
        AchievementRules.Tick v = t(); v.events = Sim.EV_FINISH; v.runFalls = 4; v.runHits = 0; v.finishTimeSec = 5000f;
        Set<String> messy = all(c.step(v));
        assertTrue(messy.contains(AchievementRules.SUMMIT));
        assertFalse(messy.contains(AchievementRules.CLEAN_RUN));
        assertFalse(messy.contains(AchievementRules.PERFECT_SUMMIT));
        assertFalse(messy.contains(AchievementRules.SPEED_SUMMIT));
    }

    // ---- falls -------------------------------------------------------------------------------------------------------

    @Test public void fallMilestonesAtOneTwentyFiveAndHundred() {
        AchievementRules a = new AchievementRules();
        AchievementRules.Tick t = t(); t.events = Sim.EV_RESPAWN; t.totalFalls = 1;
        Set<String> first = all(a.step(t));
        assertTrue(first.contains(AchievementRules.FIRST_FALL));
        assertFalse(first.contains(AchievementRules.FALL_25));
        assertFalse("first fall is a one-time thing", all(a.step(ev2(Sim.EV_RESPAWN, 2))).contains(AchievementRules.FIRST_FALL));

        AchievementRules b = new AchievementRules();
        assertTrue(all(b.step(ev2(Sim.EV_RESPAWN, 25))).contains(AchievementRules.FALL_25));
        AchievementRules c = new AchievementRules();
        assertTrue(all(c.step(ev2(Sim.EV_RESPAWN, 100))).contains(AchievementRules.FALL_100));
    }

    private static AchievementRules.Tick ev2(int events, int totalFalls) { AchievementRules.Tick t = t(); t.events = events; t.totalFalls = totalFalls; return t; }

    @Test public void fallEveryTowerNeedsAllTenAndRespectsTheSeed() {
        AchievementRules a = new AchievementRules();
        for (int i = 0; i < 9; i++) { AchievementRules.Tick t = t(); t.events = Sim.EV_RESPAWN; t.currentTower = i; assertFalse(all(a.step(t)).contains(AchievementRules.FALL_EVERY_TOWER)); }
        AchievementRules.Tick last = t(); last.events = Sim.EV_RESPAWN; last.currentTower = 9;
        assertTrue(all(a.step(last)).contains(AchievementRules.FALL_EVERY_TOWER));
        assertEquals(AchievementRules.ALL_TOWERS_MASK, a.towerFellMask());

        // a persisted mask missing only tower 7 completes on the next fall there
        AchievementRules seeded = new AchievementRules(AchievementRules.ALL_TOWERS_MASK & ~(1 << 7));
        AchievementRules.Tick t7 = t(); t7.events = Sim.EV_RESPAWN; t7.currentTower = 7;
        assertTrue(all(seeded.step(t7)).contains(AchievementRules.FALL_EVERY_TOWER));
    }

    @Test public void rageSpotNeedsFiveFailsInARowThenClearing() {
        AchievementRules a = new AchievementRules();
        AchievementRules.Tick fail = t(); fail.events = Sim.EV_RESPAWN; fail.checkpointIndex = 12;
        for (int i = 0; i < 5; i++) a.step(fail);
        AchievementRules.Tick clear = t(); clear.events = Sim.EV_CHECKPOINT; clear.checkpointIndex = 13;   // reached the next checkpoint
        assertTrue(all(a.step(clear)).contains(AchievementRules.RAGE_SPOT));

        // only four fails -> clearing earns nothing
        AchievementRules b = new AchievementRules();
        for (int i = 0; i < 4; i++) b.step(fail);
        assertFalse(all(b.step(clear)).contains(AchievementRules.RAGE_SPOT));
    }

    // ---- endless / infinity ------------------------------------------------------------------------------------------

    @Test public void infinityThresholds() {
        AchievementRules a = new AchievementRules();
        AchievementRules.Tick t = t(); t.infinityMeters = 1;
        assertTrue(all(a.step(t)).contains(AchievementRules.INFINITY_ENTER));
        t.infinityMeters = 500; assertTrue(all(a.step(t)).contains(AchievementRules.INFINITY_500));
        t.infinityMeters = 5000;
        Set<String> high = all(a.step(t));
        assertTrue(high.contains(AchievementRules.INFINITY_1000));
        assertTrue(high.contains(AchievementRules.INFINITY_2500));
        assertTrue(high.contains(AchievementRules.INFINITY_5000));

        // not in the endless section: nothing
        AchievementRules b = new AchievementRules();
        AchievementRules.Tick u = t(); u.infinityMeters = -1;
        assertTrue(all(b.step(u)).isEmpty());
    }

    @Test public void infinityBestNeedsAPriorRecordToBeat() {
        AchievementRules a = new AchievementRules();
        AchievementRules.Tick t = t(); t.infinityMeters = 700; t.bestInfinityMeters = 600;
        assertTrue(all(a.step(t)).contains(AchievementRules.INFINITY_BEST));

        // no prior record (baseline 0): entering the endless section is not "beating" anything
        AchievementRules b = new AchievementRules();
        AchievementRules.Tick u = t(); u.infinityMeters = 50; u.bestInfinityMeters = 0;
        assertFalse(all(b.step(u)).contains(AchievementRules.INFINITY_BEST));
    }

    @Test public void marathonCrossesItsThreshold() {
        AchievementRules a = new AchievementRules();
        AchievementRules.Tick t = t(); t.marathonMeters = AchievementRules.MARATHON_METERS - 1;
        assertFalse(all(a.step(t)).contains(AchievementRules.MARATHON));
        t.marathonMeters = AchievementRules.MARATHON_METERS;
        assertTrue(all(a.step(t)).contains(AchievementRules.MARATHON));
    }

    // ---- worlds ------------------------------------------------------------------------------------------------------

    @Test public void spaceAndAllWorlds() {
        AchievementRules a = new AchievementRules();
        AchievementRules.Tick t = t(); t.zone = AchievementRules.SPACE_WORLD;
        assertTrue(all(a.step(t)).contains(AchievementRules.SPACE));

        AchievementRules b = new AchievementRules();
        Set<String> got = new HashSet<>();
        for (int z = 0; z < AchievementRules.WORLD_COUNT; z++) { AchievementRules.Tick u = t(); u.zone = z; got.addAll(b.step(u)); }
        assertTrue(got.contains(AchievementRules.ALL_WORLDS));
    }

    @Test public void noCheckpointWorldRequiresAFallFreeWorld() {
        // climb cleanly from world 0 up into world 1
        AchievementRules a = new AchievementRules();
        AchievementRules.Tick z0 = t(); z0.zone = 0; a.step(z0);
        AchievementRules.Tick z1 = t(); z1.zone = 1;
        assertTrue(all(a.step(z1)).contains(AchievementRules.NO_CHECKPOINT_WORLD));

        // a fall inside world 0 disqualifies it
        AchievementRules b = new AchievementRules();
        AchievementRules.Tick e0 = t(); e0.zone = 0; b.step(e0);
        AchievementRules.Tick fell = t(); fell.zone = 0; fell.events = Sim.EV_RESPAWN; b.step(fell);
        AchievementRules.Tick up = t(); up.zone = 1;
        assertFalse(all(b.step(up)).contains(AchievementRules.NO_CHECKPOINT_WORLD));
    }

    // ---- swing chain -------------------------------------------------------------------------------------------------

    @Test public void swingChainRopeThenSwingThenMidairCatch() {
        AchievementRules a = new AchievementRules();
        a.step(ev(Sim.EV_ROPE));                                   // on a rope
        AchievementRules.Tick onSwing = t(); onSwing.events = Sim.EV_LAND; onSwing.mode = Sim.Mode.GROUND; onSwing.landedType = Element.Type.SWING;
        a.step(onSwing);                                           // landing on the swing must NOT break the chain
        a.step(ev(Sim.EV_JUMP | Sim.EV_BOUNCE));                   // swing super-jump
        AchievementRules.Tick catchIt = t(); catchIt.events = Sim.EV_LAND; catchIt.mode = Sim.Mode.GROUND; catchIt.landedType = Element.Type.MOVE_V;
        Set<String> got = all(a.step(catchIt));
        assertTrue(got.contains(AchievementRules.SWING_CHAIN));
        assertTrue(got.contains(AchievementRules.MIDAIR_CATCH));
    }

    @Test public void swingChainIsBrokenByTouchingSolidGround() {
        AchievementRules a = new AchievementRules();
        a.step(ev(Sim.EV_ROPE));
        AchievementRules.Tick ground = t(); ground.events = Sim.EV_LAND; ground.mode = Sim.Mode.GROUND; ground.landedType = Element.Type.STATIC;
        a.step(ground);                                            // touched the ground: chain reset
        a.step(ev(Sim.EV_JUMP | Sim.EV_BOUNCE));
        AchievementRules.Tick catchIt = t(); catchIt.events = Sim.EV_LAND; catchIt.mode = Sim.Mode.GROUND; catchIt.landedType = Element.Type.MOVE_V;
        assertFalse(all(a.step(catchIt)).contains(AchievementRules.SWING_CHAIN));
    }
}
