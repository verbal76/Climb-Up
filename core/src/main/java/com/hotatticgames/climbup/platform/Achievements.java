package com.hotatticgames.climbup.platform;

/**
 * The seam the shared game fires Steam achievements (and a few persistent integer stats) through.
 *
 * <p>Nothing here touches the simulation, physics or progression: it is pure, fire-and-forget telemetry. The
 * decision of WHICH achievement a gameplay moment earns lives in the engine-free, unit-tested
 * {@link com.hotatticgames.climbup.sim.AchievementRules}; this interface only carries the resulting ids (and the
 * stats Steam persists) out to a launcher.
 *
 * <p>The default implementation {@link #NONE} does nothing, so Android, headless tests and any desktop launcher
 * without a running Steam client are completely unaffected. Only the Windows launcher supplies a Steam-backed
 * implementation (see {@code desktop/.../SteamAchievements}).
 */
public interface Achievements {
    /** Unlock the achievement with this Steam API id. Idempotent (Steam dedupes); a no-op when Steam is not running. */
    void unlock(String id);

    /** Current value of a persistent integer stat, or 0 if unknown / Steam not running. */
    default int stat(String id) { return 0; }

    /** Store a persistent integer stat value. A no-op when Steam is not running. */
    default void stat(String id, int value) { }

    /** A sink that does nothing at all (Android, headless tests, desktop without Steam). */
    Achievements NONE = new Achievements() {
        @Override public void unlock(String id) { }
    };
}
