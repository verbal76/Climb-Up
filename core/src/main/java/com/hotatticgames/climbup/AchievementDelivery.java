package com.hotatticgames.climbup;

import com.hotatticgames.climbup.platform.Achievements;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Owner decision (2026-10-30): an achievement earned while Steam cannot take it (not running, offline, stats not yet delivered) is not lost.
 * Every earned id is kept in the save ({@link SaveData#pendingAchievements}) until the platform confirms it unlocked, and offered again on later
 * steps and sessions. Idempotent: Steam ignores an id it already holds, and a confirmed id leaves the list, so nothing is awarded twice and
 * nothing is re-granted after a confirmation. No platform (Android, no Steam): ids stay pending, harmlessly (at most the 27 api ids).
 */
final class AchievementDelivery {
    private AchievementDelivery() { }

    static Set<String> pending(SaveData sd) {
        Set<String> out = new LinkedHashSet<>();
        if (sd.pendingAchievements != null) for (String id : sd.pendingAchievements.split(",")) if (!id.trim().isEmpty()) out.add(id.trim());
        return out;
    }

    private static void store(SaveData sd, Set<String> ids) { sd.pendingAchievements = String.join(",", ids); }

    /**
     * Owner decision C1: Steam's STAT_FALLS (the lifetime count another machine or an earlier session reached, or before this build the largest
     * per-climb count) is merged into the local lifetime count with max - never added - so nothing is counted twice. Returns true when it rose.
     */
    static boolean mergeLifetimeFalls(SaveData sd, int steamFalls) {
        if (steamFalls <= sd.lifetimeFalls) return false;
        sd.lifetimeFalls = steamFalls;
        return true;
    }

    /** An id earned now: offered to the platform and kept pending until confirmed. Returns true when the pending list changed. */
    static boolean earn(SaveData sd, Achievements a, String id) {
        Set<String> p = pending(sd);
        a.unlock(id);
        boolean changed = a.unlocked(id) ? p.remove(id) : p.add(id);
        if (changed) store(sd, p);
        return changed;
    }

    /** Offers every pending id again and drops the confirmed ones. Returns true when the pending list changed (the caller saves). */
    static boolean retry(SaveData sd, Achievements a) {
        Set<String> p = pending(sd);
        if (p.isEmpty()) return false;
        boolean changed = false;
        for (java.util.Iterator<String> it = p.iterator(); it.hasNext(); ) {
            String id = it.next();
            if (!a.unlocked(id)) a.unlock(id);
            if (a.unlocked(id)) { it.remove(); changed = true; }
        }
        if (changed) store(sd, p);
        return changed;
    }
}
