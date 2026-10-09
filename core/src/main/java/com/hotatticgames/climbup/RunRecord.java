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

    /** Completes the run exactly once at the current clock. Returns 1 for a new personal best, 0 for a finish that is not a record, -1 if the run was already complete (nothing changes). */
    public static int complete(SaveData sd) {
        if (sd.finished) return -1;
        sd.finished = true; sd.finishTime = sd.runClock; sd.lastFinish = sd.finishTime;
        boolean best = sd.bestFinish <= 0f || sd.finishTime < sd.bestFinish;
        if (best) sd.bestFinish = sd.finishTime;
        return best ? 1 : 0;
    }

    /** END RUN (and every fresh climb): forget the climb itself; the completed times (lastFinish, bestFinish) and the other personal bests stay. */
    public static void forgetClimb(SaveData sd) {
        sd.seed = 0; sd.sliceJson = null; sd.slice = 0; sd.sliceCheckpoint = 0; sd.climbVersion = ""; sd.climbBuild = 0; sd.climbDate = ""; sd.climbHeight = 0f; sd.continuedFromOlder = false; sd.keysHeld = 0; sd.openedUpTo = 0; sd.runTicks = 0;
        sd.finished = false; sd.finishTime = 0; sd.runClock = 0; sd.towerStartClock = 0; sd.towerStartHeight = 0; sd.towers = 0; sd.splits = new float[0]; sd.towerTotals = new float[0];
    }
}
