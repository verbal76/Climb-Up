package com.hotatticgames.climbup;

/**
 * Performance probe (dev only, -Dclimb.perf=true; off in every shipped build, where {@link #ON} is a constant false the JIT folds away).
 * PlayScreen stamps the stages of each frame here; the desktop PerfRun harness reads them after every frame. Nothing here changes what the
 * game does, except {@link #forceFall}, which the harness sets to send the climber through the real respawn path.
 */
public final class PerfProbe {
    private PerfProbe() {}
    public static final boolean ON = Boolean.getBoolean("climb.perf");

    /** System.nanoTime() at the frame's start, after course streaming, after the simulation steps, after the world render, after the HUD. */
    public static long tStart, tStream, tSim, tWorld, tHud;
    /** Simulation steps run this frame, the events they raised, whether an autosave wrote to disk this frame. */
    public static int steps, events;
    public static boolean saved;
    /** Whether the background thread was generating a slice of tower during this frame. */
    public static boolean generating;
    /** What the climber was doing (Sim.Mode ordinal) and how high they were. */
    public static int mode;
    public static double absY;
    /** Nanoseconds this frame spent in the autopilot's planner (a test driver, not part of the game: subtracted from the simulation stage). */
    public static long tDrive;
    /** Harness request: drop the climber through the real fall-and-respawn path on the next frame. */
    public static volatile boolean forceFall;
    /** Harness request (the test autopilot is stuck): make a platform a few steps up the route the checkpoint and respawn there, through the real respawn path. */
    public static volatile boolean skipAhead;
}
