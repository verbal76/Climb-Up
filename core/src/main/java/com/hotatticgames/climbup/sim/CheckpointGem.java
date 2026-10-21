package com.hotatticgames.climbup.sim;

/**
 * The checkpoint gem (owner, "final checkpoint gem height rule"): a reward you walk into, never something to jump for. Shared by the simulation
 * (its pickup volume) and the renderer (where it is drawn), so the two can never disagree.
 *
 * The rule: the gem's lowest visible point, at the very top of its bob, never rises above the standing character's centre-mass reference,
 * which the owner put at about face height. Measured in the real renderer (desktop GemShot): the head bone (the base of the face) stands
 * 0.618 m above the feet for the bunny and the hamster, 0.983 m for the astronauts; the bunny's eyes are at about 0.84 m. The limit used is
 * the strictest of these, {@link #BOTTOM_LIMIT}. The gem model is 1.938 units tall, drawn at scale 0.46 (half height 0.446 m), and rolls
 * up to 9 degrees, which raises its lowest point by at most 0.006 m.
 */
public final class CheckpointGem {
    private CheckpointGem() {}

    /** The highest the gem's bottom edge may ever be above the deck: the bunny's and hamster's head bone (0.618 m, measured), the lowest of all the characters. */
    public static final float BOTTOM_LIMIT = 0.618f;
    /** Half the drawn gem's height (model half height 0.969 units x scale 0.46). */
    public static final float HALF_H = 0.969f * 0.46f;
    /** The drawn gem's half width (model 0.565 units x 0.46). */
    public static final float HALF_W = 0.565f * 0.46f;
    /** The most the gem rolls either way while it waits (degrees). */
    public static final float ROLL_DEG = 9f;
    /** How far the gem bobs up and down about its mean height. */
    public static final float BOB = 0.13f;
    /** The gem's mean centre height above its platform's deck: its bottom edge stays between 0.32 m and 0.59 m, under {@link #BOTTOM_LIMIT}. Was 1.7 m (the bottom
     *  edge hovered 1.13-1.39 m up, above the bunny's head: it read as a jump target). */
    public static final float LIFT = 0.90f;

    /** Where the bottom edge is (above the deck) for a given bob phase sin(...) in [-1, 1] and roll angle. */
    public static float bottomAt(float bobSin, float rollDeg) { return LIFT + BOB * bobSin - HALF_H * (float) Math.cos(Math.toRadians(rollDeg)); }
    /** The highest the gem's top ever reaches above the deck. */
    public static float topMax() { return LIFT + BOB + HALF_H; }

    /**
     * The pickup volume: the whole width of the gem's platform, from the deck up to the gem's highest top. Anyone walking or running across the platform
     * is inside it whatever the bob, from either side, with no jump and no lining up (and so is a hop that passes low over it).
     */
    public static boolean touches(Course c, Element platform, float platformS, float deckY, float s, float y, Tuning t) {
        float dx = Math.abs(c.dsWrap(s, platformS));
        return dx < platform.halfW() + t.halfWidth && y < deckY + topMax() && y + t.height > deckY - 0.05f;
    }
}
