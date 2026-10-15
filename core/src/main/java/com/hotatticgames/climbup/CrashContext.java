package com.hotatticgames.climbup;

/**
 * A one-line snapshot of what the game was doing on the last frame, so that if the game dies the crash
 * logger can record the game state alongside the stack trace ("mode=ROPE absY=812 elem=ROPE char=2 ...").
 * The owner asked for this telemetry to diagnose the "most common causes of crashes".
 *
 * <p>Diagnostic only: it never affects the simulation. The snapshot is a plain volatile string updated once
 * per frame (cheap) and read by whatever launcher logs the crash (desktop today). It holds no personal data.
 */
public final class CrashContext {
    private static volatile String snapshot = "no gameplay yet (still in menus or loading)";
    private CrashContext() { }

    /** Records the current game-state line. Called once per frame from the play screen; building it is guarded there. */
    public static void set(String line) { if (line != null) snapshot = line; }

    /** The most recent game-state line, for the crash logger. */
    public static String get() { return snapshot; }
}
