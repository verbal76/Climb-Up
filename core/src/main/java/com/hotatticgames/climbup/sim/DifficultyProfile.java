package com.hotatticgames.climbup.sim;

/**
 * Owner directive 2026-10-25 (one shared generation and validation system): everything that makes one difficulty's courses differ from another's,
 * in one place. NORMAL and PUNISHER run the same generator, the same solver, the same physics and the same proofs; they differ only in these
 * values (and in {@link Tuning#react()}, how much faster creatures and cannons act, and {@link Tuning#hard}, the extra creatures in each group).
 * <p>
 * The values reproduce the courses exactly as they were before the profile existed (rules 7). Changing one changes that difficulty's seeded courses,
 * so it needs a rules-version bump, a new generation fingerprint and new replays.
 */
public final class DifficultyProfile {
    /** Metres further up the 10-level curve this difficulty reads (endless climbs only): PUNISHER's Level 1 plays like NORMAL's Levels 3-4. */
    public final float curveShift;
    /** Highest value the late-climb ramp reaches (endless climbs only): PUNISHER keeps climbing past NORMAL's top. */
    public final float lateCap;
    /** Height between reserved route splits, and risers in each split (more risers: a longer branch). */
    public final float forkEvery; public final int forkRisers;
    /** Hop spacing along a reserved split's branch, and whether its middle foothold is a narrow one. */
    public final float branchHop; public final boolean narrowFoothold;
    /** Height between the wide hops that give opportunistic forks open air to drop through. */
    public final float wideHopEvery;
    /** Opportunistic forks: chance at a take-off that allows one, minimum route platforms between forks, furthest rejoin, and whether a long branch may get a second branch. */
    public final float forkChance; public final int forkSpacing, forkReach; public final boolean nestedForks;
    /** Encounter threat budget ({@link #threatBudget}, measured by {@link EncounterModel#threat}): applied or not, and its base, growth over the 10 levels and share from a cramped take-off. */
    public final boolean threatBudget; public final float threatBase, threatPerLevel, threatCramped;
    /** Key rooms try take-offs no roaming creature reaches before threatened ones. */
    public final boolean quietKeyRooms;

    private DifficultyProfile(float curveShift, float lateCap, float forkEvery, int forkRisers, float branchHop, boolean narrowFoothold, float wideHopEvery,
                              float forkChance, int forkSpacing, int forkReach, boolean nestedForks,
                              boolean threatBudget, float threatBase, float threatPerLevel, float threatCramped, boolean quietKeyRooms) {
        this.curveShift = curveShift; this.lateCap = lateCap; this.forkEvery = forkEvery; this.forkRisers = forkRisers; this.branchHop = branchHop;
        this.narrowFoothold = narrowFoothold; this.wideHopEvery = wideHopEvery; this.forkChance = forkChance; this.forkSpacing = forkSpacing;
        this.forkReach = forkReach; this.nestedForks = nestedForks; this.threatBudget = threatBudget; this.threatBase = threatBase;
        this.threatPerLevel = threatPerLevel; this.threatCramped = threatCramped; this.quietKeyRooms = quietKeyRooms;
    }

    /** NORMAL: occasional simple splits, every link within the threat budget, quiet key rooms first. */
    public static final DifficultyProfile NORMAL = new DifficultyProfile(0f, 1f, 70f, 3, 4.4f, false, 36f, 0.5f, 7, 7, false,
        true, EncounterModel.THREAT_BASE, EncounterModel.THREAT_PER_LEVEL, EncounterModel.THREAT_CRAMPED, true);
    /**
     * PUNISHER (and NEVER GONNA HAPPEN, whose code is kept): the curve read 1,500 m higher, more and longer splits with a narrow foothold, nested
     * forks. The threat budget and quiet key rooms are not applied yet: that is a new difficulty rule awaiting the owner's decision (2026-10-25).
     */
    public static final DifficultyProfile PUNISHER = new DifficultyProfile(1500f, 1.4f, 34f, 5, 3.4f, true, 18f, 0.9f, 4, 9, true,
        false, EncounterModel.THREAT_BASE, EncounterModel.THREAT_PER_LEVEL, EncounterModel.THREAT_CRAMPED, false);

    /** The profile a climb at {@code t.hard} generates with. */
    public static DifficultyProfile of(Tuning t) { return t.hard > 0 ? PUNISHER : NORMAL; }

    /** The most weighted pursuit one link may carry at level {@code lv} (0..1). */
    public float threatBudget(float lv, boolean cramped) {
        float b = threatBase + threatPerLevel * Math.max(0f, Math.min(1f, lv));
        return cramped ? b * threatCramped : b;
    }
}
