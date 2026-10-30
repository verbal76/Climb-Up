package com.hotatticgames.climbup;

import java.util.ArrayList;
import java.util.List;

/**
 * One player's run progress and stats (each player has their own SaveData in their own folder, see {@link Profiles}); settings are shared and live elsewhere.
 * Versioned; see {@link SaveStore#migrate}. No power progression of any kind is stored.
 * v7: added {@link #name}; the single save became the first player's.
 */
public final class SaveData {
    public static final int CURRENT_VERSION = 7;
    public int version = CURRENT_VERSION;
    public String name = "";             // this player's name (v7); empty only for a save read before players existed - Profiles fills it in
    public int courseIndex = 0;          // legacy (v2 finite towers); unused by the endless climb
    public int checkpoint = 0;           // legacy (v2)
    // endless climb (v6): the climb is its seed plus every slice generated so far (history.bin, see HistoryStore); here only where it stands
    public long seed = 0;                // 0 = no climb in progress
    public int cpSlice = 0, cpLocal = 0; // the last red gem touched: slice index and local element index
    public boolean noticeOldClimb = false;   // the first launch after the v6 update: tell the player their old climb could not carry over (shown once)
    // which build / version started the climb in progress (empty = started before versions were stamped) - see Legacy
    public String climbVersion = "";
    public int climbBuild = 0;
    public String climbDate = "";
    public int climbRules = 0;           // Build 64: the generation rules version the climb in progress was generated under (0 = unknown: started before the stamps)
    public int climbTuning = 0;          // R5 (2026-10-28): CRC32 of the tuning the climb was generated with (0 = unknown: started before the stamp)
    public boolean climbNotComparable;   // R5: resumed on other generation rules or tuning than it began with: the course is mixed, so its times set no record
    public float climbHeight = 0f;       // highest point reached in the climb in progress
    public int keysHeld = 0;             // keys carried (bit per colour) - a resumed climb only has the stored slice, so this must be saved
    public int openedUpTo = 0;           // highest castle number opened in this climb (castles open in order)
    // achievement telemetry only (never read by the simulation; does not affect physics, difficulty or progression): fall-backs and hazard hits in the CURRENT climb, so a clean/flawless summit can be detected across a resume
    public int climbFalls = 0;
    public int climbHits = 0;
    public List<LegacyRun> legacy = new ArrayList<>();   // runs set aside when an update changed the rules
    public float bestHeight = 0f;
    public int falls = 0;
    public float playSeconds = 0f;
    public int completions = 0;
    public float bestTime = 0f;          // fastest summit, seconds (0 = none yet)
    public List<String> shownTips = new ArrayList<>();
    // speed-run clock: counted only while playing, from the first input of a climb; reset by NEW CLIMB
    public long runTicks = 0;            // the run clock in whole 1/60 s simulation steps (exact); runClock is derived from it
    public float runClock = 0f;          // total seconds of this climb so far
    public float towerStartClock = 0f, towerStartHeight = 0f;   // when / where the current tower segment began (the last unlock, or the start)
    public int towers = 0;               // castles unlocked this climb
    public float[] splits = new float[0];        // seconds each unlocked castle took (from the previous unlock)
    public float[] towerTotals = new float[0];  // run clock at each unlock (the last one is the total time to the last tower)
    public boolean finished = false;             // this climb has walked through the finish castle (castle 10): the run clock is frozen
    public float finishTime = 0f;                // the run clock at that moment
    public float lastFinish = 0f;                // the most recent completed run's time (kept after END RUN)
    public float bestFinish = 0f;                // fastest finish ever (0 = none)
    public float bestSplit = 0f;                 // fastest single tower ever (0 = none)
    public float[] bestTotals = new float[0];    // fastest run clock at the Nth unlock, ever
    /** Release hardening (owner achievement policy 2026-10-30): the world whose "cleared without a fall-back" progress is being tracked for
     *  NO_CHECKPOINT_WORLD, and whether a fall-back happened in it - kept with the climb so a quit and resume cannot forget a fall.
     *  -1 = a fresh climb, nothing tracked yet; -2 = unknown (a climb saved before this field existed: its current world counts as fallen in). */
    public int achZone = -2;
    public boolean achZoneFell = false;
    /** Owner decision C1 (2026-10-30): fall-backs across all climbs, for FIRST_FALL / FALL_25 / FALL_100 and STAT_FALLS (assisted play counts, the
     *  world picker does not). {@link #falls} stays the per-climb count it always was. -1 = not yet migrated (a save from before this field). */
    public int lifetimeFalls = -1;
    /** Owner decision (2026-10-30): the worlds ever visited (bit per world), so ALL_WORLDS credit survives restarts; merged with OR. */
    public int worldsSeenMask = 0;
    /** Owner decision (2026-10-30): achievement ids earned but not yet confirmed by Steam (offline, Steam not running, stats not delivered);
     *  retried until Steam reports them unlocked, then removed. Comma-separated api ids. */
    public String pendingAchievements = "";
    public boolean devPicked = false;            // Build 76 (owner): started from the hidden world picker (footage): never a record, a split or an achievement
    public int difficulty = 0;                   // Build 79 (owner): the climb in progress's difficulty (0 NORMAL, 1 PUNISHER, 2 NEVER GONNA HAPPEN), set when it starts from the setting; its times go on that difficulty's board
    public boolean assisted = false;             // owner (Build 71): this climb's clock has run with an assist on (slower speed or jump forgiveness); its times stay with the run but never become bests
    // personal records (times table, infinity distance): one self-contained object owned by whoever owns this save, stored as its own JSON text (see RecordsData)
    public String recordsJson = "";
    private transient RecordsData recordsObj;
    public RecordsData records() {
        if (recordsObj == null) {
            recordsObj = RecordsData.fromJson(recordsJson);
            if ((recordsJson == null || recordsJson.isEmpty()) && bestFinish > 0f) recordsObj.bestTotal = bestFinish;      // an older save only knew the best finish
            recordsObj.upgradeFrom63(seed, climbRules > 0 ? climbRules : com.hotatticgames.climbup.sim.CourseGenerator.rulesOfBuild(climbBuild));   // Build 64: stamp the Build 63 run with its rules (a no-op for current text)
        }
        return recordsObj;
    }
    /** Build 79 (owner): PUNISHER and NEVER GONNA HAPPEN climbs keep their own times tables, apart from the normal one. */
    public String punisherRecordsJson = "", neverRecordsJson = "";
    private transient RecordsData punisherObj, neverObj;
    /** The times table of difficulty {@code d} (0 the normal one). */
    public RecordsData board(int d) {
        if (d == 1) { if (punisherObj == null) punisherObj = RecordsData.fromJson(punisherRecordsJson); return punisherObj; }
        if (d == 2) { if (neverObj == null) neverObj = RecordsData.fromJson(neverRecordsJson); return neverObj; }
        return records();
    }
    /** The times table this climb's times go on (its difficulty's). */
    public RecordsData board() { return board(difficulty); }
    public void syncRecords() { if (recordsObj != null) recordsJson = recordsObj.toJson(); if (punisherObj != null) punisherRecordsJson = punisherObj.toJson(); if (neverObj != null) neverRecordsJson = neverObj.toJson(); }       // call before writing

    /** A run that was in progress when an update changed the rules, kept as a stamped record (it cannot be resumed: its castles and obstacles came from the old rules). */
    public static final class LegacyRun {
        public String version = "";      // game version that started it ("" = before versions were stamped)
        public int build = 0;            // build number that started it (0 = unknown)
        public String date = "";         // day the climb was started
        public String savedDate = "";    // day it was set aside
        public long seed = 0;            // Build 64: the course it was climbed on (0 = set aside before seeds were recorded)
        public int rules = 0;            // Build 64: its generation rules version (0 = unknown)
        public float height = 0f;
        public int towers = 0;
        public float runClock = 0f;
        public boolean finished = false;
        public float finishTime = 0f;
    }
}
