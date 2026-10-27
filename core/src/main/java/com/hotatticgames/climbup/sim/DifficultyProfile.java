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
    /**
     * Owner structural rules (2026-10-26, PROVISIONAL values): rare enemies on genuine intermediate safe platforms inside integrated hazard rows, from
     * castle {@code midEnemyFromCastle} on, with chance {@code midEnemyChance} per gap at that castle, growing by {@code midEnemyGrowth} per castle above it.
     * The dedicated hazard platforms themselves never carry enemies (protected).
     */
    public final int midEnemyFromCastle; public final float midEnemyChance, midEnemyGrowth;
    /**
     * Terrain difficulty of the endless climb (2026-10-26, PROVISIONAL): it rises from {@link #TERRAIN_FLOOR} over {@link #TERRAIN_RAMP_Y} metres of the
     * curve (this profile's {@link #curveShift} included) up to {@code terrainCap}. Before, it reached 1 at 520 m and stayed there for the other 4,480 m in
     * every mode (flat terrain progression, identical in all three modes). Every jump it asks for is still proven by the planner.
     */
    public final float terrainCap;
    /** Owner rule (2026-10-26): absolute height from which enemies may pursue onto protected hazard platforms (NORMAL never; PROVISIONAL heights). */
    public final double pursuitFromY;
    /** How strongly the late-climb hazard, trap and mortar weights grow with {@code late()} (owner 2026-10-26: PUNISHER/NGH hazards stayed flat over the ten castles; PROVISIONAL). NORMAL 1. */
    public final float lateHazScale;
    /**
     * Owner difficulty clarification (2026-10-26, PROVISIONAL values): safe, uncontested platforms grow rarer as the climb and the mode get harder, NGH
     * becoming near-continuous danger. At curve level {@code lv} (0 at NORMAL's start, 1 at its top, beyond 1 for the head-started modes):
     * a plain HOP/STAIRS pick becomes a hazard module with chance {@code pressureBase + pressureGrowth * lv}; a hazard pick that cannot be placed first
     * tries another hazard module before the safe fallbacks with chance {@code threatFallbackBase + threatFallbackGrowth * lv}; creature-deck chances are
     * multiplied by {@code creatureScale}. Every module is still proven by the planner; checkpoints (gem rests) are unchanged.
     */
    public final float pressureBase, pressureGrowth, threatFallbackBase, threatFallbackGrowth, creatureScale;
    public float pressure(float lv) { return Math.min(0.95f, pressureBase + pressureGrowth * lv); }
    public float threatFallback(float lv) { return Math.min(1f, threatFallbackBase + threatFallbackGrowth * lv); }
    public static final float TERRAIN_FLOOR = 0.1f, TERRAIN_RAMP_Y = 5000f;

    private DifficultyProfile(float curveShift, float lateCap, float forkEvery, int forkRisers, float branchHop, boolean narrowFoothold, float wideHopEvery,
                              float forkChance, int forkSpacing, int forkReach, boolean nestedForks,
                              boolean threatBudget, float threatBase, float threatPerLevel, float threatCramped, boolean quietKeyRooms,
                              int midEnemyFromCastle, float midEnemyChance, float midEnemyGrowth, float terrainCap, double pursuitFromY, float lateHazScale,
                              float pressureBase, float pressureGrowth, float threatFallbackBase, float threatFallbackGrowth, float creatureScale) {
        this.terrainCap = terrainCap; this.lateHazScale = lateHazScale;
        this.pressureBase = pressureBase; this.pressureGrowth = pressureGrowth; this.threatFallbackBase = threatFallbackBase; this.threatFallbackGrowth = threatFallbackGrowth; this.creatureScale = creatureScale;
        this.pursuitFromY = pursuitFromY;
        this.midEnemyFromCastle = midEnemyFromCastle; this.midEnemyChance = midEnemyChance; this.midEnemyGrowth = midEnemyGrowth;
        this.curveShift = curveShift; this.lateCap = lateCap; this.forkEvery = forkEvery; this.forkRisers = forkRisers; this.branchHop = branchHop;
        this.narrowFoothold = narrowFoothold; this.wideHopEvery = wideHopEvery; this.forkChance = forkChance; this.forkSpacing = forkSpacing;
        this.forkReach = forkReach; this.nestedForks = nestedForks; this.threatBudget = threatBudget; this.threatBase = threatBase;
        this.threatPerLevel = threatPerLevel; this.threatCramped = threatCramped; this.quietKeyRooms = quietKeyRooms;
    }

    /** NORMAL: occasional simple splits, every link within the threat budget, quiet key rooms first. */
    public static final DifficultyProfile NORMAL = new DifficultyProfile(0f, 1f, 70f, 3, 4.4f, false, 36f, 0.5f, 7, 7, false,
        true, EncounterModel.THREAT_BASE, EncounterModel.THREAT_PER_LEVEL, EncounterModel.THREAT_CRAMPED, true, 8, 0.12f, 0.04f, 1f, Double.POSITIVE_INFINITY, 1f,
        0f, 0.3f, 0f, 0.4f, 1f);
    /**
     * PUNISHER: the curve read 2,000 m higher (owner target: its castle 1 resembles NORMAL castle 5; was 1,500 m, PROVISIONAL), more and longer
     * splits with a narrow foothold, nested forks. The threat budget and quiet key rooms are not applied (owner decision pending since 2026-10-25).
     */
    public static final DifficultyProfile PUNISHER = new DifficultyProfile(2000f, 1.4f, 34f, 5, 3.4f, true, 18f, 0.9f, 4, 9, true,
        false, EncounterModel.THREAT_BASE, EncounterModel.THREAT_PER_LEVEL, EncounterModel.THREAT_CRAMPED, false, 4, 0.15f, 0.04f, 1.08f, 3333.0, 2f,
        0.15f, 0.35f, 0.3f, 0.4f, 1.5f);
    /**
     * NEVER GONNA HAPPEN (its own profile since 2026-10-26; it shared PUNISHER's): the curve read 3,500 m higher (owner target: castle 1 resembles NORMAL
     * castle 8, PROVISIONAL), a higher late cap, otherwise PUNISHER's structure. Creatures act faster through {@link Tuning#react()} (hard = 2).
     */
    public static final DifficultyProfile NGH = new DifficultyProfile(3500f, 1.7f, 34f, 5, 3.4f, true, 18f, 0.9f, 4, 9, true,
        false, EncounterModel.THREAT_BASE, EncounterModel.THREAT_PER_LEVEL, EncounterModel.THREAT_CRAMPED, false, 1, 0.18f, 0.04f, 1.15f, 1667.0, 2.5f,
        0.3f, 0.4f, 0.5f, 0.4f, 2f);

    /** PUNISHER only (owner 2026-10-27): Enforcer robot encounters, one or two between every two castles. NORMAL (frozen) and NGH never get them. */
    public boolean enforcers() { return this == PUNISHER; }
    /** Spike-block avalanches (owner 2026-10-27): 2 PUNISHER (2-3 a castle interval, 4-5 blocks), 1 NORMAL (castles 9-10 only, rare, 2 blocks), 0 none (NOT GONNA HAPPEN). */
    public int avalanches() { return this == PUNISHER ? 2 : this == NORMAL ? 1 : 0; }

    /** The profile a climb at {@code t.hard} generates with. */
    public static DifficultyProfile of(Tuning t) { return t.hard >= 2 ? NGH : t.hard > 0 ? PUNISHER : NORMAL; }

    /** The most weighted pursuit one link may carry at level {@code lv} (0..1). */
    public float threatBudget(float lv, boolean cramped) {
        float b = threatBase + threatPerLevel * Math.max(0f, Math.min(1f, lv));
        return cramped ? b * threatCramped : b;
    }
}
