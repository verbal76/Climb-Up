package com.hotatticgames.climbup.desktop;

import com.codedisaster.steamworks.SteamAPI;
import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamLeaderboardEntriesHandle;
import com.codedisaster.steamworks.SteamLeaderboardHandle;
import com.codedisaster.steamworks.SteamResult;
import com.codedisaster.steamworks.SteamUserStats;
import com.codedisaster.steamworks.SteamUserStatsCallback;
import com.hotatticgames.climbup.platform.Achievements;
import com.hotatticgames.climbup.sim.AchievementRules;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Owns the Steamworks client on the Windows build: initialise if the Steam client is running, pump callbacks once a
 * frame, and expose achievement/stat operations through an {@link Achievements} sink. EVERYTHING here is defensive:
 * if Steam is not installed, not running, or its native libraries cannot load, {@link #init()} fails silently, every
 * operation becomes a no-op, and the game is fully playable as the plain (non-Steam) zip build.
 *
 * <p>This is the ONLY code that talks to Steam. The decision of which achievement a moment earns lives in the pure,
 * tested {@link AchievementRules} in core; here we only forward ids and persist the integer stats.
 *
 * <p>Persistent stats are kept monotonic ({@code max}, or bitwise-OR for the tower mask) so a stale/zero seed can
 * never overwrite a larger stored value — there is no way to lose progress even if a stat is read before Steam has
 * delivered the current values.
 */
public final class SteamManager {

    /**
     * The Steam App ID for Upwardly (public: it appears in the store URL, so it is NOT a secret).
     * TODO(owner): confirm this matches the Steamworks app before publishing; dev runs also read {@code steam_appid.txt}.
     */
    public static final int STEAM_APP_ID = 5427130;

    private boolean available;
    private boolean statsReady;                 // Steam has delivered the current stats; safe to read/merge/store
    private SteamUserStats userStats;
    private final SteamAchievements sink = new SteamAchievements(this);

    private final Set<String> pendingUnlocks = new LinkedHashSet<>();
    private final Map<String, Integer> pendingStats = new HashMap<>();
    private boolean dirty;
    private long lastStoreNs;
    private static final long STORE_EVERY_NS = 2_000_000_000L;

    /** The sink the platform hands to the shared game. Always valid; a no-op until (and unless) Steam initialises. */
    public Achievements achievements() { return sink; }

    public boolean available() { return available; }

    /** Initialise Steam if the client is running. Any failure is swallowed and logged; the game keeps running without Steam. */
    public void init() {
        try {
            SteamAPI.loadLibraries();
            if (!SteamAPI.init()) { log("Steam client not running or init failed - achievements disabled"); return; }
            userStats = new SteamUserStats(new Callbacks());
            available = true;
            userStats.requestCurrentStats();        // onUserStatsReceived flips statsReady and flushes anything queued
            lastStoreNs = System.nanoTime();
            log("Steam initialised (App ID " + STEAM_APP_ID + ")");
        } catch (Throwable t) {                      // missing natives, no Steam install, wrong arch, sandbox: never crash the game
            available = false; userStats = null;
            log("Steam unavailable - achievements disabled: " + t);
        }
    }

    /** Pump Steam callbacks; call once per rendered frame. Cheap and safe when Steam is not running. */
    public void runCallbacks() {
        if (!available) return;
        try {
            if (SteamAPI.isSteamRunning()) SteamAPI.runCallbacks();
            if (dirty && System.nanoTime() - lastStoreNs > STORE_EVERY_NS) storeNow();
        } catch (Throwable ignored) { }
    }

    /** Persist and release Steam on exit. */
    public void shutdown() {
        if (!available) return;
        try { storeNow(); } catch (Throwable ignored) { }
        try { SteamAPI.shutdown(); } catch (Throwable ignored) { }
        available = false; statsReady = false; userStats = null;
    }

    // ---- operations the Achievements sink forwards here --------------------------------------------------------------

    void unlock(String id) {
        if (!available || id == null) return;
        if (!statsReady) { pendingUnlocks.add(id); return; }
        applyUnlock(id);
    }

    int getStat(String id) {
        if (!available || !statsReady) return 0;
        return liveStat(id);
    }

    void setStat(String id, int value) {
        if (!available || id == null) return;
        if (!statsReady) { pendingStats.put(id, value); return; }
        applyStat(id, value);
    }

    // ---- internals ---------------------------------------------------------------------------------------------------

    private void applyUnlock(String id) {
        try {
            if (userStats.isAchieved(id, false)) return;                // already unlocked: do not store again
            if (userStats.setAchievement(id)) { dirty = true; storeNow(); log("achievement unlocked: " + id); }
        } catch (Throwable ignored) { }
    }

    private void applyStat(String id, int value) {
        try {
            int cur = liveStat(id);
            int merged = STAT_IS_MASK(id) ? (cur | value) : Math.max(cur, value);   // never regress a persistent counter
            if (merged != cur) { userStats.setStatI(id, merged); dirty = true; }
        } catch (Throwable ignored) { }
    }

    private int liveStat(String id) {
        try { return userStats.getStatI(id, 0); } catch (Throwable ignored) { return 0; }
    }

    private void storeNow() {
        if (userStats == null) return;
        try { if (userStats.storeStats()) { dirty = false; lastStoreNs = System.nanoTime(); } } catch (Throwable ignored) { }
    }

    private static boolean STAT_IS_MASK(String id) { return AchievementRules.STAT_TOWER_FELL_MASK.equals(id); }

    private static void log(String s) { try { DesktopLog.append("[steam] " + s); } catch (Throwable ignored) { } }

    /** Steam callbacks (on the render thread, driven by {@link #runCallbacks()}). Only the stats-received one does anything; the leaderboard methods are part of the interface and left empty (no leaderboards here). */
    private final class Callbacks implements SteamUserStatsCallback {
        @Override public void onUserStatsReceived(long gameId, SteamID steamIDUser, SteamResult result) {
            if (result != SteamResult.OK) { log("stats not received: " + result); return; }
            statsReady = true;
            for (Map.Entry<String, Integer> e : pendingStats.entrySet()) applyStat(e.getKey(), e.getValue());
            pendingStats.clear();
            for (String id : pendingUnlocks) applyUnlock(id);
            pendingUnlocks.clear();
            if (dirty) storeNow();
            log("stats received");
        }
        @Override public void onUserStatsStored(long gameId, SteamResult result) { }
        @Override public void onUserStatsUnloaded(SteamID steamIDUser) { }
        @Override public void onUserAchievementStored(long gameId, boolean group, String name, int cur, int max) { }
        @Override public void onLeaderboardFindResult(SteamLeaderboardHandle leaderboard, boolean found) { }
        @Override public void onLeaderboardScoresDownloaded(SteamLeaderboardHandle leaderboard, SteamLeaderboardEntriesHandle entries, int numEntries) { }
        @Override public void onLeaderboardScoreUploaded(boolean success, SteamLeaderboardHandle leaderboard, int score, boolean scoreChanged, int globalRankNew, int globalRankPrevious) { }
        @Override public void onNumberOfCurrentPlayersReceived(boolean success, int players) { }
        @Override public void onGlobalStatsReceived(long gameId, SteamResult result) { }
    }
}
