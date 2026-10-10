package com.hotatticgames.climbup.desktop.input;

/** Rewrites the game's touch-flavoured tip text ("STICK UP", "TAP SWING") into the prompts of the device in use. */
public final class Prompts {
    private Prompts() { }

    public static String rewrite(String t, String jump, String swing, String move, String up, String down, String left, String right) {
        if (t == null) return null;
        t = t.replace("THEN STICK UP OR JUMP TO PULL UP", "THEN " + up + " OR " + jump + " TO PULL UP");
        t = t.replace("STICK UP/DOWN CLIMBS. JUMP TO LEAP OFF", up + "/" + down + " CLIMBS. " + jump + " TO LEAP OFF");
        t = t.replace("STICK LEFT/RIGHT SHIMMIES. DOWN OR JUMP DROPS", left + "/" + right + " SHIMMIES. " + down + " OR " + jump + " DROPS");
        t = t.replace("HOLD JUMP", "HOLD " + jump);
        t = t.replace("THE STICK STEERS", move + " STEERS");
        t = t.replace("TAP SWING", "PRESS " + swing).replace("PRESS SWING", "PRESS " + swing);
        return t;
    }
}
