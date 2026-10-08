package com.hotatticgames.climbup;

import java.util.ArrayList;
import java.util.List;

/** Run progress and stats. Versioned; see {@link SaveStore#migrate}. No power progression of any kind is stored. */
public final class SaveData {
    public static final int CURRENT_VERSION = 2;
    public int version = CURRENT_VERSION;
    public int courseIndex = 0;          // which generated tower is being climbed
    public int checkpoint = 0;           // route element index of the last checkpoint
    public float bestHeight = 0f;
    public int falls = 0;
    public float playSeconds = 0f;
    public int completions = 0;
    public float bestTime = 0f;          // fastest summit, seconds (0 = none yet)
    public List<String> shownTips = new ArrayList<>();
}
