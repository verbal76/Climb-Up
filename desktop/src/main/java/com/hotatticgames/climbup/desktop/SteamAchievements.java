package com.hotatticgames.climbup.desktop;

import com.hotatticgames.climbup.platform.Achievements;

/**
 * The Windows implementation of the core {@link Achievements} hook: a thin forwarder to {@link SteamManager}. It
 * holds no logic of its own - which id a gameplay moment earns is decided in core by {@code AchievementRules}; this
 * only carries the resulting ids and integer stats to Steam. Every call is a no-op while Steam is not running.
 */
public final class SteamAchievements implements Achievements {
    private final SteamManager steam;

    SteamAchievements(SteamManager steam) { this.steam = steam; }

    @Override public void unlock(String id) { steam.unlock(id); }
    @Override public int stat(String id) { return steam.getStat(id); }
    @Override public void stat(String id, int value) { steam.setStat(id, value); }
}
