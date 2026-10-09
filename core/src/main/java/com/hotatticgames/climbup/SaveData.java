package com.hotatticgames.climbup;

import java.util.ArrayList;
import java.util.List;

/** Run progress and stats. Versioned; see {@link SaveStore#migrate}. No power progression of any kind is stored. */
public final class SaveData {
    public static final int CURRENT_VERSION = 4;
    public int version = CURRENT_VERSION;
    public int courseIndex = 0;          // legacy (v2 finite towers); unused by the endless climb
    public int checkpoint = 0;           // legacy (v2)
    // endless climb (v3): the climb is a pure function of (seed, slice, previous slice), so we keep just the slice the last checkpoint is in
    public long seed = 0;                // 0 = no climb in progress
    public int slice = 0;
    public String sliceJson = null;      // that slice exactly as generated (see CourseIO)
    public int sliceCheckpoint = 0;      // local element index of the checkpoint inside the slice
    public float bestHeight = 0f;
    public int falls = 0;
    public float playSeconds = 0f;
    public int completions = 0;
    public float bestTime = 0f;          // fastest summit, seconds (0 = none yet)
    public List<String> shownTips = new ArrayList<>();
    // speed-run clock: counted only while playing, from the first input of a climb; reset by NEW CLIMB
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
}
