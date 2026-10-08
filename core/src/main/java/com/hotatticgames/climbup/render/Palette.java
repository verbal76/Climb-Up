package com.hotatticgames.climbup.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;

/** Per-zone sky/fog/tint colours, blended continuously by climb height. */
public final class Palette {
    public static final Color[] SKY_TOP = {c(0.30f, 0.62f, 0.95f), c(0.52f, 0.72f, 0.95f), c(0.22f, 0.16f, 0.42f), c(0.03f, 0.05f, 0.16f), c(0.005f, 0.005f, 0.04f)};
    public static final Color[] SKY_BOT = {c(0.80f, 0.93f, 1.00f), c(0.90f, 0.96f, 1.00f), c(0.99f, 0.58f, 0.40f), c(0.14f, 0.20f, 0.44f), c(0.06f, 0.03f, 0.16f)};
    public static final Color[] TINT = {c(1f, 1f, 1f), c(1f, 1f, 1f), c(1f, 0.84f, 0.80f), c(0.72f, 0.80f, 1f), c(0.66f, 0.74f, 1f)};
    public static final Color[] AMBIENT = {c(0.62f, 0.62f, 0.66f), c(0.64f, 0.66f, 0.72f), c(0.58f, 0.46f, 0.52f), c(0.36f, 0.40f, 0.58f), c(0.40f, 0.42f, 0.62f)};
    public static final String[] NAMES = {"Meadow Base", "Frost Ridge", "Dusk Spire", "Night Summit", "Deep Space"};
    public static final int ZONES = NAMES.length, SPACE = 4;

    private static Color c(float r, float g, float b) { return new Color(r, g, b, 1f); }

    /** zoneF in [0,4): integer part = zone, fraction = progress through it. Blends over the last 20% of each zone. */
    public static void blend(Color[] arr, float zoneF, Color out) {
        int z = Math.min(ZONES - 1, (int) zoneF);
        float f = zoneF - z;
        float t = MathUtils.clamp((f - 0.8f) / 0.2f, 0f, 1f);
        Color a = arr[z], b = arr[(z + 1) % ZONES];      // themes repeat for ever: after Deep Space comes Meadow Base again
        out.set(a).lerp(b, t);
    }
}
