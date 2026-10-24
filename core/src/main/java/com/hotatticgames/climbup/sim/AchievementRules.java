package com.hotatticgames.climbup.sim;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The single, engine-free source of truth for "this gameplay event/state earns these Steam achievement ids".
 *
 * <p>No libGDX, no Steam, no I/O: it is fed a small immutable {@link Tick} snapshot of what the simulation and run
 * already track each fixed step, and returns the achievement ids newly earned (usually none). The Windows Steam
 * layer just forwards those ids to Steam; Android and headless tests never construct this at all. Because it is pure
 * and deterministic, every rule, threshold and distinction is unit-tested with no Steam client (see
 * {@code AchievementRulesTest}).
 *
 * <p>It adds NO gameplay state that affects physics, movement, difficulty or progression. The few counters it keeps
 * (same-spot fail streak, worlds-seen-this-run mask, per-tower fell mask, swing-chain latch) are pure achievement
 * telemetry derived from events the game already fires.
 */
public final class AchievementRules {

    // ---- the 27 Steam achievement api ids (must match the owner's Steamworks entries exactly) ----
    public static final String
            TOWER_1 = "ACH_TOWER_1",
            TOWER_5 = "ACH_TOWER_5",
            SUMMIT = "ACH_SUMMIT",
            FINGERTIP = "ACH_FINGERTIP",
            ROPE_TOP = "ACH_ROPE_TOP",
            MIDAIR_CATCH = "ACH_MIDAIR_CATCH",
            CRAB_OFF = "ACH_CRAB_OFF",
            BOUNCE = "ACH_BOUNCE",
            INFINITY_ENTER = "ACH_INFINITY_ENTER",
            INFINITY_BEST = "ACH_INFINITY_BEST",
            SPACE = "ACH_SPACE",
            CLEAN_RUN = "ACH_CLEAN_RUN",
            FIRST_FALL = "ACH_FIRST_FALL",
            FALL_25 = "ACH_FALL_25",
            FALL_100 = "ACH_FALL_100",
            FALL_EVERY_TOWER = "ACH_FALL_EVERY_TOWER",
            RAGE_SPOT = "ACH_RAGE_SPOT",
            INFINITY_500 = "ACH_INFINITY_500",
            INFINITY_1000 = "ACH_INFINITY_1000",
            INFINITY_2500 = "ACH_INFINITY_2500",
            INFINITY_5000 = "ACH_INFINITY_5000",
            ALL_WORLDS = "ACH_ALL_WORLDS",
            NO_CHECKPOINT_WORLD = "ACH_NO_CHECKPOINT_WORLD",
            SPEED_SUMMIT = "ACH_SPEED_SUMMIT",
            PERFECT_SUMMIT = "ACH_PERFECT_SUMMIT",
            SWING_CHAIN = "ACH_SWING_CHAIN",
            MARATHON = "ACH_MARATHON";

    /** Every api id, in the order above (for docs/tests). */
    public static final String[] ALL_IDS = {
            TOWER_1, TOWER_5, SUMMIT, FINGERTIP, ROPE_TOP, MIDAIR_CATCH, CRAB_OFF, BOUNCE, INFINITY_ENTER,
            INFINITY_BEST, SPACE, CLEAN_RUN, FIRST_FALL, FALL_25, FALL_100, FALL_EVERY_TOWER, RAGE_SPOT,
            INFINITY_500, INFINITY_1000, INFINITY_2500, INFINITY_5000, ALL_WORLDS, NO_CHECKPOINT_WORLD,
            SPEED_SUMMIT, PERFECT_SUMMIT, SWING_CHAIN, MARATHON};

    // ---- persistent Steam INT stat ids (Steam stores these across sessions on the player's machine) ----
    public static final String
            STAT_FALLS = "STAT_FALLS",                 // mirror of the lifetime fall count (SaveData.falls)
            STAT_MARATHON_M = "STAT_MARATHON_M",       // cumulative metres climbed across all runs
            STAT_TOWER_FELL_MASK = "STAT_TOWER_FELL_MASK",  // 10-bit mask of towers fallen on (cross-session)
            STAT_BEST_INFINITY_M = "STAT_BEST_INFINITY_M";  // best distance into the endless section

    // ---- tunable thresholds (named constants) ----
    public static final int FALL_25_THRESHOLD = 25;
    public static final int FALL_100_THRESHOLD = 100;
    public static final int RAGE_SPOT_FAILS = 5;
    public static final float CATCH_DROP_M = 1.0f;                   // Nice Catch after stepping, slipping or crumbling off: at least this far below where the footing was lost
    public static final float CATCH_MISSED_JUMP_M = 2.5f;            // Nice Catch after a jump: it failed - at least this far below the jump's starting elevation
    public static final int TOWERS_TOTAL = 10;                       // mirrors Tuning.finishCastle (ten towers)
    public static final int ALL_TOWERS_MASK = (1 << TOWERS_TOTAL) - 1;
    public static final int WORLD_COUNT = 5;                         // mirrors Palette.ZONES (Meadow, Frost, Dusk, Night, Deep Space)
    public static final int ALL_WORLDS_MASK = (1 << WORLD_COUNT) - 1;
    public static final int SPACE_WORLD = 4;                         // mirrors Palette.SPACE (Deep Space)
    public static final int INFINITY_500_M = 500, INFINITY_1000_M = 1000, INFINITY_2500_M = 2500, INFINITY_5000_M = 5000;
    // TODO(owner): tune these two guesses once the build has been play-tested on Steam.
    public static final float SPEED_SUMMIT_SECONDS = 3600f;          // "Express Elevator": all ten towers under 60 minutes (owner, 15 Oct: the flawless careful autopilot takes 72.3 min on seed 7, see SpeedBaselineMeasure; 20 min was physically impossible)
    public static final int MARATHON_METERS = 50000;                 // "The Long Haul": cumulative metres across all runs

    /**
     * The world index for an absolute climb height. The worlds repeat upward for ever (after Deep Space comes Meadow Base
     * again), but nothing below the start belongs to another world: a fall under the starting platform stays in world 0.
     * (Build 69 wrapped negative heights round to Deep Space, which unlocked Deep Space and No Safety Net for a fall off
     * the first platforms - found by the owner on Steam.)
     */
    public static int zoneAt(double absHeight, double zoneHeight) {
        if (!(absHeight > 0) || !(zoneHeight > 0)) return 0;
        return (int) (Math.floor(absHeight / zoneHeight) % WORLD_COUNT);
    }

    /** Immutable per-step snapshot the caller fills from the simulation and the run record. */
    public static final class Tick {
        public int events;               // the sim event bitmask just consumed (Sim.EV_*)
        public Sim.Mode mode;            // the sim mode AFTER this step (distinguishes a fingertip mantle from a rope-top mantle)
        public Element.Type landedType;  // the element landed on this step (null unless a GROUND landing happened this step)
        public int towersOpened;         // castles opened so far in this climb (SaveData.towers)
        public float finishTimeSec;      // the finish clock, seconds (only meaningful on the finishing step)
        public int runFalls;             // checkpoint fall-backs in THIS climb (SaveData.climbFalls)
        public int runHits;              // hazard hits in THIS climb (SaveData.climbHits)
        public int totalFalls;           // lifetime fall-backs (SaveData.falls)
        public int zone;                 // current world index 0..WORLD_COUNT-1
        public int currentTower;         // which of the ten towers the player is on, 0..TOWERS_TOTAL-1
        public int infinityMeters;       // metres past the summit into the endless section, or -1 if not there
        public int bestInfinityMeters;   // the best endless distance recorded BEFORE this climb (fixed baseline)
        public int marathonMeters;       // cumulative metres climbed across all runs
        public int checkpointIndex;      // the route element index of the live checkpoint (Sim.checkpoint)
        public double height;            // the climber's absolute height this step, metres (Nice Catch measures the fall from it)
    }

    private final Set<String> fired = new HashSet<>();   // ids already returned this session (Steam also dedupes globally)
    private int towerFellMask;           // seeded from the persistent stat so it survives across sessions
    private int worldsSeenMask;          // worlds visited this run (session)
    private int lastFailCp = -1, sameSpotFails;
    private int lastZone = -1;
    private boolean fellInThisZone;
    private boolean ropeTouched, swungFromRope;
    // Nice Catch (Build 70, owner): the climber's own jump must not be the last thing that put them in the air
    private boolean wasAir;               // in the air on the previous step
    private boolean interrupted;          // this air time was started or broken by something other than a jump (a step off, a crumble, a let-go, a hit, a shove)
    private boolean knocked;              // ...and that something was a hit or a shove (no minimum drop then)
    private double footingHeight = Double.NaN;  // where the footing was last had

    public AchievementRules() { this(0); }

    /** @param towerFellMaskSeed the persisted 10-bit "fell on this tower" mask, so the feat can be completed across sessions. */
    public AchievementRules(int towerFellMaskSeed) { this.towerFellMask = towerFellMaskSeed & ALL_TOWERS_MASK; }

    /** The live per-tower fell mask (the caller mirrors it back to {@link #STAT_TOWER_FELL_MASK} so it persists). */
    public int towerFellMask() { return towerFellMask; }

    /**
     * Evaluate one simulation step. Returns the achievement ids newly earned (usually empty). Deterministic and
     * free of side effects beyond this evaluator's own counters; safe to call every fixed step.
     */
    public List<String> step(Tick t) {
        List<String> out = new ArrayList<>();
        int ev = t.events;

        // ---- traversal feats (one clean event/state each) ----
        if ((ev & Sim.EV_PULL) != 0 && t.mode == Sim.Mode.PULLUP) earn(out, FINGERTIP);          // fingertip grab / ledge mantle over a too-big gap
        if ((ev & Sim.EV_PULL) != 0 && t.mode == Sim.Mode.BEAM) earn(out, ROPE_TOP);             // hauled onto the beam at the top of a rope
        if ((ev & Sim.EV_LAND) != 0 && isMovingPlatform(t.landedType)) {         // Build 70 (owner): a moving platform saved a fall - a clean jump onto one is not a catch
            double drop = Double.isNaN(footingHeight) ? 0.0 : footingHeight - t.height;
            if (knocked                                                        // a hit or a shove broke the air time
                    || (interrupted && drop >= CATCH_DROP_M)                   // stepped, slipped or crumbled off, then a real fall
                    || drop >= CATCH_MISSED_JUMP_M)                            // a jump that failed: well below where it started
                earn(out, MIDAIR_CATCH);
        }
        if ((ev & Sim.EV_CRAB_OFF) != 0) earn(out, CRAB_OFF);                                    // knocked a crab/bee off with the club
        if ((ev & Sim.EV_BOUNCE) != 0) earn(out, BOUNCE);                                        // launched off a trampoline/spring/slab/swing

        // ---- towers / summit ----
        if (t.towersOpened >= 1) earn(out, TOWER_1);
        if (t.towersOpened >= 5) earn(out, TOWER_5);
        boolean summit = (ev & (Sim.EV_FINISH | Sim.EV_WIN)) != 0;
        if (summit) {
            earn(out, SUMMIT);
            if (t.runFalls == 0) earn(out, CLEAN_RUN);                                           // never fell back to a checkpoint
            if (t.runFalls == 0 && t.runHits == 0) earn(out, PERFECT_SUMMIT);                    // flawless: no fall-backs AND no hazard hits
            if (t.finishTimeSec > 0f && Math.round(t.finishTimeSec * 60.0) <= Math.round(SPEED_SUMMIT_SECONDS * 60.0)) earn(out, SPEED_SUMMIT);      // Build 74: compared in whole simulation steps
        }

        // ---- worlds / space ----
        if (t.zone >= 0 && t.zone < WORLD_COUNT) {
            worldsSeenMask |= (1 << t.zone);
            if (t.zone == SPACE_WORLD) earn(out, SPACE);
        }
        if (worldsSeenMask == ALL_WORLDS_MASK) earn(out, ALL_WORLDS);
        updateNoCheckpointWorld(out, t, ev);

        // ---- endless / infinity (metres past the summit) ----
        if (t.infinityMeters >= 1) earn(out, INFINITY_ENTER);
        if (t.infinityMeters >= INFINITY_500_M) earn(out, INFINITY_500);
        if (t.infinityMeters >= INFINITY_1000_M) earn(out, INFINITY_1000);
        if (t.infinityMeters >= INFINITY_2500_M) earn(out, INFINITY_2500);
        if (t.infinityMeters >= INFINITY_5000_M) earn(out, INFINITY_5000);
        if (t.bestInfinityMeters > 0 && t.infinityMeters > t.bestInfinityMeters) earn(out, INFINITY_BEST);   // beat a prior record

        // ---- fall milestones (lifetime cumulative) ----
        if (t.totalFalls >= 1) earn(out, FIRST_FALL);
        if (t.totalFalls >= FALL_25_THRESHOLD) earn(out, FALL_25);
        if (t.totalFalls >= FALL_100_THRESHOLD) earn(out, FALL_100);
        if (t.marathonMeters >= MARATHON_METERS) earn(out, MARATHON);

        // ---- per-fall bookkeeping (no physics touched) ----
        if ((ev & Sim.EV_RESPAWN) != 0) {
            if (t.currentTower >= 0 && t.currentTower < TOWERS_TOTAL) towerFellMask |= (1 << t.currentTower);
            if (t.checkpointIndex == lastFailCp) sameSpotFails++;
            else { lastFailCp = t.checkpointIndex; sameSpotFails = 1; }
        }
        if (towerFellMask == ALL_TOWERS_MASK) earn(out, FALL_EVERY_TOWER);

        // ---- rage spot: cleared a checkpoint after failing it >=5 times in a row ----
        if ((ev & Sim.EV_CHECKPOINT) != 0 && sameSpotFails >= RAGE_SPOT_FAILS && t.checkpointIndex > lastFailCp) {
            earn(out, RAGE_SPOT); sameSpotFails = 0; lastFailCp = -1;
        }

        // ---- swing chain: rope -> swing launch -> mid-air catch, without touching solid ground ----
        if ((ev & Sim.EV_ROPE) != 0) ropeTouched = true;
        if ((ev & Sim.EV_JUMP) != 0 && (ev & Sim.EV_BOUNCE) != 0 && ropeTouched) swungFromRope = true;   // EV_JUMP+EV_BOUNCE together is uniquely a swing super-jump
        if ((ev & Sim.EV_LAND) != 0) {
            if (isMovingPlatform(t.landedType)) { if (swungFromRope) earn(out, SWING_CHAIN); }
            else if (t.landedType != null && t.landedType != Element.Type.SWING) { ropeTouched = false; swungFromRope = false; }   // solid ground breaks the chain (a swing in between does not)
        }
        if ((ev & Sim.EV_RESPAWN) != 0) { ropeTouched = false; swungFromRope = false; }

        // ---- footing bookkeeping for Nice Catch (after the checks above, so a landing step still sees the fall it ends)
        boolean air = t.mode == Sim.Mode.AIR, jumped = (ev & (Sim.EV_JUMP | Sim.EV_BOUNCE)) != 0;
        if (!air) { footingHeight = t.height; interrupted = false; knocked = false; }          // footing again: a fresh start
        else if (jumped) { interrupted = false; knocked = false; }                              // a jump (or a pad / swing launch) is the latest input: not a catch
        else if (!wasAir) interrupted = true;                                                    // left the footing without jumping: stepped off, crumbled, let go
        if ((ev & (Sim.EV_HIT | Sim.EV_SHOVE | Sim.EV_CRUMBLE)) != 0) { interrupted = true; if ((ev & Sim.EV_CRUMBLE) == 0) knocked = true; }   // something broke the jump
        if ((ev & Sim.EV_RESPAWN) != 0) { interrupted = false; knocked = false; footingHeight = Double.NaN; }
        wasAir = air;

        return out;
    }

    /** Cleared a whole world (crossed up into the next one) with no fall-back during it. */
    private void updateNoCheckpointWorld(List<String> out, Tick t, int ev) {
        if ((ev & Sim.EV_RESPAWN) != 0) fellInThisZone = true;
        if (lastZone < 0) { lastZone = t.zone; return; }
        if (t.zone != lastZone) {
            if (t.zone > lastZone && !fellInThisZone) earn(out, NO_CHECKPOINT_WORLD);
            fellInThisZone = t.zone < lastZone;      // dropped into a lower world: we got here by falling
            lastZone = t.zone;
        }
    }

    private static boolean isMovingPlatform(Element.Type type) {
        return type == Element.Type.MOVE_H || type == Element.Type.MOVE_V || type == Element.Type.MOVE_Z;
    }

    private void earn(List<String> out, String id) { if (fired.add(id)) out.add(id); }
}
