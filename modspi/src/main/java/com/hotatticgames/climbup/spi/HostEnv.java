package com.hotatticgames.climbup.spi;

import java.io.File;

/** What the host gives a module. Deliberately tiny: anything else the game needs it gets from libGDX (Gdx.files, Gdx.input, Gdx.audio, Gdx.graphics). */
public interface HostEnv {
    /** App-private, persistent directory for saves, settings and the climb history (the same place the packaged game uses). */
    File dataDir();
    /** Android versionCode of the installed host (0 off-device). */
    int appBuild();
    /** The host's own compatibility level (see HostInfo.HOST_LEVEL). */
    int hostLevel();
    /** Directory holding this module's verified files (read-only); null if the module was built into the host. */
    File moduleDir();
    /** The game reached live play with this module: the host stops treating it as unproven. Call once; later calls are ignored. */
    void confirmHealthy();
    /**
     * Tells the host whether a climb is in progress (its history is stored and resumable). The host never activates a module that would read that climb differently
     * (other generator ruleset, unreadable save schema) while this is true; it waits until the climb ends.
     */
    void climbInProgress(boolean inProgress);
    /** Diagnostics line for the host log (Settings/About reads these); never required for play. */
    void diag(String line);
    /** Asks the host to check its update channel now (the game's Settings > About > CHECK). Asynchronous; a staged release applies at the next cold start. Hosts without a channel ignore it. */
    default void checkForUpdates() { }
    /** One line describing the host's update state (active content version, staged release, last result), or empty. Cheap; may be called every second. */
    default String updateStatus() { return ""; }
}
