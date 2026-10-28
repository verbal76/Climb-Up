package com.hotatticgames.climbup.render;

/** The playable characters. All share the Quaternius 'Character' skeleton and its animation clips. */
public final class Characters {
    private Characters() {}
    public static final int COUNT = 6;
    public static final String[] NAMES = {"BUNNY", "HAMSTER", "FINN FROG", "RAE PANDA", "FERNANDO", "BARBARA BEE"};
    private static final String[] MODELS = {"hero/hero.g3dj", "hero/hamster.g3dj", "space/astronaut_finnthefrog.g3dj", "space/astronaut_raetheredpanda.g3dj",
            "space/astronaut_fernandotheflamingo.g3dj", "space/astronaut_barbarathebee.g3dj"};
    public static int clamp(int c) { return c < 0 || c >= COUNT ? 0 : c; }
    public static String model(int c) { return MODELS[clamp(c)]; }
    public static boolean textured(int c) { return c == 1; }
    public static boolean astronaut(int c) { return c >= 2; }
    /** Model units -> world units (the hero is about 1.4 world units tall). */
    public static float scale(int c) { return astronaut(c) ? 0.50f : 0.37f; }
    /** Pitch multiplier for the grunts and squeaks: a small frog voice, a high bee voice... */
    public static float voice(int c) { return new float[]{1.0f, 1.25f, 0.82f, 1.05f, 1.12f, 1.45f}[clamp(c)]; }
    public static String name(int c) { return NAMES[clamp(c)]; }
    public static int next(int c) { return (clamp(c) + 1) % COUNT; }
}
