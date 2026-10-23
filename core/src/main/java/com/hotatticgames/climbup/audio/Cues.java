package com.hotatticgames.climbup.audio;

import com.hotatticgames.climbup.render.Palette;
import com.hotatticgames.climbup.sim.Element;

/** Pure rules that decide which sound an element or world makes (no engine calls, so they are unit-testable headlessly). */
public final class Cues {
    private Cues() { }

    /**
     * THE one test for "this element is a conveyor, so it hums": plank conveyor bridges (STATIC skin 4) and downhill conveyor ramps (RAMP skin 4),
     * i.e. {@link Element#belt()}. Extend it here when another kind of belt (for example a balance-conveyor bridge) is added.
     */
    public static boolean isConveyor(Element e) { return e != null && (e.belt() || e.tiltBelt()); }      // plank bridges, downhill ramps and the balance conveyor (Build 77)

    public enum Surface { STONE, DIRT, GRASS, WOOD }

    /** What a platform sounds like underfoot: wooden bridges, seesaws, crumbling boards and plank belts are wood; otherwise the world decides (meadow and dusk grass, snow and space stone). */
    public static Surface surface(Element e) {
        if (e == null) return Surface.STONE;
        if (e.type == Element.Type.SEESAW || e.type == Element.Type.CRUMBLE || e.crumbles()) return Surface.WOOD;
        if (e.type == Element.Type.STATIC && (e.skin == 1 || e.skin == 4)) return Surface.WOOD;
        if (e.zone == Palette.SPACE) return Surface.STONE;
        return e.zone == 1 || e.zone == 3 ? Surface.STONE : Surface.GRASS;
    }

    /** Pack clip group for a footstep (wood has no step clip of its own: bare dirt is the closest). */
    public static String stepGroup(Surface s) {
        switch (s) { case GRASS: return "step_grass"; case DIRT: case WOOD: return "step_dirt"; default: return "step_stone"; }
    }

    /** Pack clip group for a landing; wood lands with a knock. */
    public static String landGroup(Surface s) {
        switch (s) { case GRASS: return "land_grass"; case WOOD: return "hit_wood"; default: return "land_stone"; }
    }

    /** Seconds between footsteps at a ground speed (metres per second): quick runs patter, slow walks stroll. */
    public static float stepInterval(float speed) {
        float v = Math.max(0.5f, Math.abs(speed));
        return Math.max(0.2f, Math.min(0.4f, 1.9f / v));
    }

    /** Hum loudness 0..1 from the distance (metres) to the nearest conveyor: full when you stand on it, gone at {@code HUM_RANGE}. */
    public static final float HUM_RANGE = 9f;
    public static float humLevel(float distance, boolean standingOn) {
        if (standingOn) return 1f;
        float k = 1f - Math.max(0f, distance) / HUM_RANGE;
        return k <= 0f ? 0f : k * k * 0.8f;
    }

    /** Which ambience bed a world gets (0 meadow birds, 1 frost wind, 2 dusk crickets, 3 night wind, space none); null = silence. */
    public static String ambience(int zone) {
        switch (zone) { case 0: return "amb_birds"; case 1: case 3: return "amb_wind"; case 2: return "amb_crickets"; default: return null; }
    }
}
