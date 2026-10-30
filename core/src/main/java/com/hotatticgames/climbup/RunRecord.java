package com.hotatticgames.climbup;

/** The official timed run (a 5,000 m climb ending at the castle-10 door), kept apart from the UI so it can be tested: the clock, the one-time completion, and what END RUN keeps. */
public final class RunRecord {
    private RunRecord() {}

    /** Advances the official clock by one simulation step; it stops for good once the run is complete. */
    public static void tick(SaveData sd, float dt, boolean clockLive) {
        if (!clockLive || sd.finished) return;
        // counted in whole simulation steps: a float seconds counter drifts by whole seconds within an hour (its step size changes as it grows), and run times are compared to the tenth of a second
        if (sd.runTicks == 0 && sd.runClock > 0f) sd.runTicks = Math.round(sd.runClock * STEPS_PER_SECOND);        // a clock saved before ticks were kept
        sd.runTicks += Math.max(1, Math.round(dt * STEPS_PER_SECOND));
        sd.runClock = (float) (sd.runTicks / (double) STEPS_PER_SECOND);
    }
    public static final int STEPS_PER_SECOND = 60;

    /**
     * Build 74 (owner): the competitive time is the whole number of simulation steps, never a float. Every time kept in seconds is a step count divided by
     * {@link #STEPS_PER_SECOND} (or a difference of two such clocks), so rounding it back gives that exact count (a float holds it to well under half a step
     * for any time below 18 hours). Rankings and ties compare these counts.
     */
    public static long ticksOf(float seconds) { return Math.round(seconds * (double) STEPS_PER_SECOND); }
    /** True when {@code a} is a faster time than {@code b}, compared in whole simulation steps (a tie is not faster). */
    public static boolean faster(float a, float b) { return ticksOf(a) < ticksOf(b); }

    /**
     * Owner (Build 71): a climb whose clock runs with an assist on (slower speed or jump forgiveness) is an assisted run from then on, even if the assist is
     * switched off again. Its castles, splits and finish still count for the climb itself; none of its times become a personal or course best. Called on
     * every timed simulation step; a finished run is left as it is.
     */
    public static void noteAssist(SaveData sd, boolean assistOn) {
        if (!assistOn || sd.assisted || sd.finished) return;
        sd.assisted = true;
        if (sd.seed != 0) sd.board().markAssisted(sd.seed);
    }

    /**
     * Build 82 review (finding 3): the one gate for every personal best (tower split, pace total, finish): a NORMAL climb that is neither assisted
     * (owner, Build 71) nor mixed-rules (R5: resumed on other generation rules or tuning than it began with). The times table has its own rules stamps.
     */
    public static boolean setsRecords(SaveData sd) { return !sd.assisted && !sd.climbNotComparable && sd.difficulty == 0; }

    /**
     * Owner achievement policy (2026-10-30): a climb that may earn the competitive achievements (SPEED_SUMMIT, PERFECT_SUMMIT, CLEAN_RUN,
     * NO_CHECKPOINT_WORLD, INFINITY_BEST) and move the competitive endless record: not assisted, not mixed-rules, not the world picker, not the
     * developer autopilot - in every difficulty (unlike {@link #setsRecords}, which is the NORMAL-only personal-best gate).
     */
    public static boolean competitiveEligible(SaveData sd, boolean autopilot) { return !sd.assisted && !sd.climbNotComparable && !sd.devPicked && !autopilot; }

    /**
     * A castle opened {@code n} towers into the climb: keeps the fastest tower {@code split} and the pace best for {@code total} at that castle
     * (only for a climb that {@link #setsRecords}). Returns 1 when the split is a new best, 2 when the total is, 3 for both.
     */
    public static int towerBests(SaveData sd, int n, float split, float total) {
        int pb = 0;
        if (setsRecords(sd) && (sd.bestSplit <= 0f || faster(split, sd.bestSplit))) { sd.bestSplit = split; pb |= 1; }
        if (sd.bestTotals.length <= n) sd.bestTotals = java.util.Arrays.copyOf(sd.bestTotals, n + 1);
        if (setsRecords(sd) && (sd.bestTotals[n] <= 0f || faster(total, sd.bestTotals[n]))) { sd.bestTotals[n] = total; pb |= 2; }
        return pb;
    }

    /** Completes the run exactly once at the current clock. Returns 1 for a new personal best, 0 for a finish that is not a record, -1 if the run was already complete (nothing changes). */
    public static int complete(SaveData sd) {
        if (sd.finished) return -1;
        sd.finished = true; sd.finishTime = sd.runClock; sd.lastFinish = sd.finishTime;
        boolean best = setsRecords(sd) && (sd.bestFinish <= 0f || faster(sd.finishTime, sd.bestFinish));      // an assisted or mixed-rules run finishes normally but is never a record
        if (best) sd.bestFinish = sd.finishTime;
        return best ? 1 : 0;
    }

    /** END RUN (and every fresh climb): forget the climb itself; the completed times (lastFinish, bestFinish) and the other personal bests stay. */
    public static void forgetClimb(SaveData sd) {
        sd.seed = 0; sd.cpSlice = 0; sd.cpLocal = 0; sd.climbVersion = ""; sd.climbBuild = 0; sd.climbDate = ""; sd.climbRules = 0; sd.climbTuning = 0; sd.climbNotComparable = false; sd.climbHeight = 0f; sd.keysHeld = 0; sd.openedUpTo = 0; sd.runTicks = 0; sd.climbFalls = 0; sd.climbHits = 0; sd.assisted = false; sd.devPicked = false; sd.difficulty = 0; sd.achZone = -1; sd.achZoneFell = false;
        sd.finished = false; sd.finishTime = 0; sd.runClock = 0; sd.towerStartClock = 0; sd.towerStartHeight = 0; sd.towers = 0; sd.splits = new float[0]; sd.towerTotals = new float[0];
    }
}
