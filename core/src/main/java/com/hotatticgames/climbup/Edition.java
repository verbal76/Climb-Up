package com.hotatticgames.climbup;

/**
 * Which game this build is: the full game, or the demo (docs/DEMO.md). Fixed when the game is built (-Pclimb.edition=demo, see core/build.gradle),
 * never by a launch option or a save: {@code -Dclimb.demo=true} is the old attract-mode autopilot switch and has nothing to do with this.
 */
public final class Edition {
    private Edition() { }

    /** True in the demo build: three fixed stages, no free play, no developer tools, no OTA. */
    public static final boolean DEMO = EditionInfo.DEMO;
    /** The demo layer's revision (the demo workflow's input; "0" in a local build). */
    public static final String DEMO_REVISION = EditionInfo.DEMO_REVISION;
    /** The commit the build was made from ("unknown" in a local build). */
    public static final String SOURCE_COMMIT = EditionInfo.SOURCE_COMMIT;
    /** The build number the build was stamped with ("0" in a local build). */
    public static final String BUILD_NUMBER = EditionInfo.BUILD_NUMBER;

    /**
     * Developer and test hooks read from system properties (climb.autopilot, climb.startHeight, climb.hazard, climb.seed, ...). The demo honours none
     * of them: this returns null there for every name, so a launch option can never start mid-tower, pick a course or play itself.
     */
    public static String devProp(String name) { return DEMO ? null : System.getProperty(name); }
    public static boolean devFlag(String name) { return !DEMO && Boolean.getBoolean(name); }
    public static Integer devInt(String name, Integer def) { return DEMO ? def : Integer.getInteger(name, def); }

    /**
     * Launch options the demo keeps: where files are saved and read (climb.data, climb.assets), the build stamp, and the screenshot harness the demo
     * workflow uses to look at every screen (window size, density, scripted taps that press what a player could press, timed captures). None of them
     * can start a stage anywhere, choose a course or difficulty, change a number or play for you.
     */
    static final java.util.Set<String> KEPT = new java.util.HashSet<>(java.util.Arrays.asList(
        "climb.data", "climb.assets", "climb.build", "climb.shots", "climb.shotEvery", "climb.shotCount", "climb.taps", "climb.w", "climb.h", "climb.upd"));

    /** The demo clears every other climb.* system property at start-up, before anything reads them (desktop launcher, then the game). */
    public static void scrubLaunchOptions() {
        if (!DEMO) return;
        for (String k : System.getProperties().stringPropertyNames()) if (k.startsWith("climb.") && !KEPT.contains(k)) System.clearProperty(k);
    }
}
