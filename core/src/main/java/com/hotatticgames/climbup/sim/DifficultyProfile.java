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
    /**
     * Rules 21: metres further up the 5,000 m TERRAIN curve this difficulty starts (endless climbs; its own value, no longer {@link #curveShift}).
     * NGH read the terrain curve with its whole 3,500 m head start, so its castle-1 jump demand was already 0.73 (Rule 20 campaign: castle-1 terrain
     * 15 % over target, +9 % terrain over the climb); at 2,000 m it opens at 0.46, still above PUNISHER's 0.33, and rises to its 1.15 cap.
     * NORMAL 0 and PUNISHER 1,250 m are unchanged.
     */
    public float terrainShift() { return this == NGH ? NGH_TERRAIN_SHIFT : curveShift; }
    public static final float NGH_TERRAIN_SHIFT = 2000f;
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

    /**
     * NORMAL: occasional simple splits, every link within the threat budget, quiet key rooms first. Terrain cap 0.9 (rules 20, the 30/15 Split):
     * its jump demand rises to 0.9 by the top instead of 1.0 (plain jumps at the full reach by castle 10), which kept castle 10 about 9 % above
     * NORMAL's linked target with the terrain factor already at its floor; NORMAL stays approachable to the end.
     */
    public static final DifficultyProfile NORMAL = new DifficultyProfile(0f, 1f, 70f, 3, 4.4f, false, 36f, 0.5f, 7, 7, false,
        true, EncounterModel.THREAT_BASE, EncounterModel.THREAT_PER_LEVEL, EncounterModel.THREAT_CRAMPED, true, 8, 0.12f, 0.04f, 0.9f, Double.POSITIVE_INFINITY, 1f,
        0f, 0.3f, 0f, 0.4f, 1f);
    /**
     * PUNISHER: the curve read 1,250 m higher (owner 2026-10-27, rules 13: its castle 1 resembles NORMAL castles 3-4; rules 9-12 read it 2,000 m higher
     * for "castle 5", rules 7-8 1,500 m; PROVISIONAL), more and longer
     * splits with a narrow foothold, nested forks. The threat budget and quiet key rooms are not applied (owner decision pending since 2026-10-25).
     */
    public static final DifficultyProfile PUNISHER = new DifficultyProfile(1250f, 1.4f, 34f, 5, 3.4f, true, 18f, 0.9f, 4, 9, true,
        false, EncounterModel.THREAT_BASE, EncounterModel.THREAT_PER_LEVEL, EncounterModel.THREAT_CRAMPED, false, 4, 0.15f, 0.04f, 1.08f, 3333.0, 2f,
        0.15f, 0.35f, 0.3f, 0.4f, 1.5f);
    /**
     * NEVER GONNA HAPPEN (its own profile since 2026-10-26; it shared PUNISHER's): the curve read 3,500 m higher (owner target: castle 1 resembles NORMAL
     * castle 8, PROVISIONAL), a higher late cap, otherwise PUNISHER's structure. Creatures act faster through {@link Tuning#react()} (hard = 2).
     */
    public static final DifficultyProfile NGH = new DifficultyProfile(3500f, 1.7f, 34f, 5, 3.4f, true, 18f, 0.9f, 4, 9, true,
        false, EncounterModel.THREAT_BASE, EncounterModel.THREAT_PER_LEVEL, EncounterModel.THREAT_CRAMPED, false, 1, 0.18f, 0.04f, 1.15f, 1667.0, 2.5f,
        0.3f, 0.4f, 0.5f, 0.4f, 2f);

    /**
     * Enforcer robot encounters: PUNISHER (owner 2026-10-27, one or two between every two castles, tiers by height) and, since rules 14, NOT GONNA
     * HAPPEN (owner 2026-10-27: RED Enforcers only, from castle 1, two a castle interval and at most three). NORMAL never gets them.
     */
    public boolean enforcers() { return this == PUNISHER || this == NGH; }
    /**
     * Spike-block avalanches: 2 PUNISHER (2-3 a castle interval, 4-5 blocks), 1 NORMAL (castles 9-10 only, rare, 2 blocks), 3 NOT GONNA HAPPEN (rules
     * 14, owner 2026-10-27: 3-4 a castle interval, at most 4, 5-7 blocks each with its own seeded size and physics, 15-45 s cooldown).
     */
    /** History-aware variety weighting (owner 2026-10-27): PUNISHER (rules 12) and, since rules 13, NORMAL with a lighter strength; NOT GONNA HAPPEN keeps its selection. */
    public boolean adaptiveVariety() { return varietyStrength() > 0f; }
    /** NORMAL's narrow rope correction (owner 2026-10-27, authorised on measured clustering): see CourseGenerator.ropeAfterRecent. */
    public boolean ropeFallbackSpacing() { return this == NORMAL; }
    public int avalanches() { return this == PUNISHER ? 2 : this == NORMAL ? 1 : this == NGH ? 3 : 0; }

    // ------------------------------------------------------------------ castle-to-castle progression (owner directive 2026-10-27, rules 13)
    /** Every mode since rules 14 (NOT GONNA HAPPEN joined with its own calibration, owner 2026-10-27; NORMAL and PUNISHER since rules 13). */
    public boolean progression() { return true; }
    /**
     * Intended castle totals in the lane report's units (terrain + hazards + enemies + interaction - mitigation), PROVISIONAL engineering values for
     * owner review. NORMAL: castle 1 where it is (rules 12 measured 628), rising evenly to 870 at castle 10 (rules 12: 829, with plateaus at castles
     * 5-6 and 9-10). PUNISHER (owner 2026-10-27): castle 1 at NORMAL castles 3-4 (700), castle 10 about 20% above NORMAL castle 10 (1,045).
     * NOT GONNA HAPPEN (rules 14, owner 2026-10-27): castle 1 at PUNISHER castles 3-4 as measured in rules 13 (783-863: 850), castle 10 about 20% above
     * PUNISHER castle 10 as measured (mean 1,007: 1,210; 16% above PUNISHER's 1,045 target). Castles beyond 10 (the endless climb) keep castle 10's target.
     */
    /**
     * Rules 21 (owner Rule 21 directive 2026-10-31): compounded progression. NORMAL castle n = {@link #NORMAL_CASTLE1} x {@link #GROWTH}^(n-1)
     * (3.3 % per castle, +33.9 % castle 1 -> 10; Rule 20 interpolated linearly, so its planned steps shrank from 3.3 % to 2.6 %), and every mode
     * stands {@link #SEPARATION} above the one below it at every castle (PUNISHER 1.15 x NORMAL, NGH 1.3225 x NORMAL). Castles past 10 keep castle
     * 10's target. NORMAL's castle-1 anchor stays 560 (Rule 20 measured 541).
     */
    public static final float NORMAL_CASTLE1 = 560f, GROWTH = 1.033f, SEPARATION = 1.15f;
    /** Rule 20's linear arc, kept only for reports that print the superseded design (never used by generation). */
    public static final float ARC = 0.30f;
    /** This mode's multiple of NORMAL at every castle: 1, 1.15 or 1.3225. */
    public float level() { return this == NGH ? SEPARATION * SEPARATION : this == PUNISHER ? SEPARATION : 1f; }
    /**
     * Category shares {terrain, hazards, enemies, interaction} at castle 1 and castle 10, interpolated between (rules 21: the Rule 20 campaign
     * 41201387807's castle 1-3 and 8-10 mean lane shares, extrapolated to castles 1 and 10). NORMAL's terrain share falls less than measured (0.75 ->
     * 0.67 rather than 0.75 -> 0.63), so terrain carries a real part of the rise (+19 % terrain over the climb, against the near-flat +4 % of the
     * Rule 20 table) instead of leaving it all to the small hazard and enemy lanes; its castle-1 hazard share leaves out the castle-2 spike.
     */
    private float[][] shares() {
        if (this == NGH) return new float[][]{{0.618f, 0.219f, 0.120f, 0.043f}, {0.577f, 0.239f, 0.141f, 0.043f}};
        return this == PUNISHER ? new float[][]{{0.664f, 0.208f, 0.090f, 0.038f}, {0.620f, 0.210f, 0.122f, 0.048f}}
                                : new float[][]{{0.750f, 0.160f, 0.060f, 0.030f}, {0.670f, 0.200f, 0.085f, 0.045f}};
    }
    /** Intended castle-n total. */
    public float targetTotal(int n) { return (float) (NORMAL_CASTLE1 * level() * Math.pow(GROWTH, Math.max(0, Math.min(9, n - 1)))); }
    /** Intended castle-n value of category k (Progression.T, H, E, X). */
    public float target(int n, int k) { float[][] s = shares(); float u = Math.max(0f, Math.min(1f, (n - 1) / 9f)); return targetTotal(n) * (s[0][k] + (s[1][k] - s[0][k]) * u); }
    /**
     * Bounds of the selection factors {terrain, hazards, enemies, interaction} (PROVISIONAL): enemies may be raised only 10% (no enemy spam to reach a
     * number), the others 30-35%; any category may be eased by 20-30%.
     */
    public float factorLo(int k) { return k == 1 ? 0.7f : k == 2 ? 0.7f : 0.8f; }
    public float factorHi(int k) { return k == 1 ? 1.35f : k == 2 ? 1.1f : 1.3f; }
    /**
     * NOT GONNA HAPPEN (owner amendment 2026-10-27): no fixed hazard ceiling; its hazard factor escalates as far as the generator can place hazards
     * (Progression.hazardControl: step-limited, held while acceptance is low, a computational bound) and its hazard kinds are re-weighted by what they
     * actually deliver (Progression.capMultipliers). NORMAL and PUNISHER keep factorHi's 1.35.
     */
    public boolean capacityControl() { return this == NGH; }
    /**
     * Castle 1's factors before any of it is measured (PROVISIONAL): PUNISHER opens 8-11% above its castle-1 target with neutral factors (rules 13
     * iteration 1, seeds 1-2: 755-778 against 700; its Enforcer and avalanche encounters contribute only 2-7 points of that, the surplus is ordinary
     * terrain and hazard selection under its 1,250 m head start); it opens with lighter hazard and enemy selection instead, and its terrain eased
     * slightly. NORMAL opens neutral. NOT GONNA HAPPEN (rules 21) opens with lighter hazard and interaction selection: the Rule 20 campaign measured
     * its castle 1 at +16 % hazards and +25 % interaction over target with neutral factors (its terrain is eased through {@link #terrainShift()}).
     */
    public float[] initialFactors() { return this == PUNISHER ? new float[]{0.9f, 0.8f, 0.8f, 0.9f} : this == NGH ? new float[]{1f, 0.85f, 1f, 0.9f} : new float[]{1f, 1f, 1f, 1f}; }
    /** History-aware variety strength (VarietyMemory.A): PUNISHER 0.45 (rules 12), NORMAL 0.25 (rules 13: a lighter touch), NGH 0.35 (rules 14). */
    public float varietyStrength() { return this == PUNISHER ? 0.45f : this == NORMAL ? 0.25f : 0.35f; }

    /** The profile a climb at {@code t.hard} generates with. */
    public static DifficultyProfile of(Tuning t) { return t.hard >= 2 ? NGH : t.hard > 0 ? PUNISHER : NORMAL; }

    /** The most weighted pursuit one link may carry at level {@code lv} (0..1). */
    public float threatBudget(float lv, boolean cramped) {
        float b = threatBase + threatPerLevel * Math.max(0f, Math.min(1f, lv));
        return cramped ? b * threatCramped : b;
    }
}
