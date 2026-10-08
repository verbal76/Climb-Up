package com.hotatticgames.climbup;

import java.util.ArrayList;
import java.util.List;

/** Run progress and stats. Versioned; see {@link SaveStore#migrate}. No power progression of any kind is stored. */
public final class SaveData {
    public static final int CURRENT_VERSION = 3;
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
}
