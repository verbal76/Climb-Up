package com.hotatticgames.climbup;

import java.util.function.Supplier;

/**
 * A one-line snapshot of what the game was doing, so that if the game dies the crash
 * logger can record the game state alongside the stack trace ("mode=ROPE absY=812 elem=ROPE char=2 ...").
 * The owner asked for this telemetry to diagnose the "most common causes of crashes".
 *
 * <p>Diagnostic only: it never affects the simulation. It holds no personal data. The play screen registers a
 * {@link #source live source} that builds the line only when a crash logger asks for it (Build 71 performance audit:
 * building it with String.format on every frame was about a tenth of the play screen's own CPU samples). In case the
 * live source itself cannot run at crash time (out of memory, a broken state), a stored line, refreshed about once a
 * second by the play screen, is the fallback.
 */
public final class CrashContext {
    private static volatile String snapshot = "no gameplay yet (still in menus or loading)";
    private static volatile Supplier<String> source;
    private CrashContext() { }

    /** Records a game-state line (the fallback when there is no live source, or it fails). */
    public static void set(String line) { if (line != null) snapshot = line; }

    /** The live source of the game-state line, asked only when a crash logger reads {@link #get}; null clears it. */
    public static void source(Supplier<String> live) { source = live; }

    /** The current game-state line, for the crash logger: the live source if it answers, else the last recorded line. */
    public static String get() {
        Supplier<String> s = source;
        if (s != null) {
            try { String line = s.get(); if (line != null) return line; }
            catch (Throwable e) { return snapshot + " (live state unavailable: " + e.getClass().getSimpleName() + ")"; }
        }
        return snapshot;
    }
}
