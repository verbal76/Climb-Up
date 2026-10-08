package com.hotatticgames.climbup.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;

/** Per-zone sky/fog/tint colours, blended continuously by climb height. */
public final class Palette {
    public static final Color[] SKY_TOP = {c(0.30f, 0.62f, 0.95f), c(0.52f, 0.72f, 0.95f), c(0.22f, 0.16f, 0.42f), c(0.03f, 0.05f, 0.16f), c(0.005f, 0.005f, 0.04f)};
    public static final Color[] SKY_BOT = {c(0.80f, 0.93f, 1.00f), c(0.90f, 0.96f, 1.00f), c(0.99f, 0.58f, 0.40f), c(0.14f, 0.20f, 0.44f), c(0.06f, 0.03f, 0.16f)};
    public static final Color[] TINT = {c(1f, 1f, 1f), c(1f, 1f, 1f), c(1f, 0.84f, 0.80f), c(0.72f, 0.80f, 1f), c(0.66f, 0.74f, 1f)};
    public static final Color[] AMBIENT = {c(0.62f, 0.62f, 0.66f), c(0.64f, 0.66f, 0.72f), c(0.58f, 0.46f, 0.52f), c(0.36f, 0.40f, 0.58f), c(0.40f, 0.42f, 0.62f)};
    // lighting rig per world: key (sun / moon), fill from the opposite side, a warm-or-cool bounce from below (fake global illumination for undersides), and the hero's rim light (a colour that contrasts with the world so he never melts into it)
    public static final Color[] SUN = {c(1.00f, 0.96f, 0.86f), c(0.96f, 0.98f, 1.00f), c(1.00f, 0.70f, 0.45f), c(0.50f, 0.60f, 1.00f), c(0.90f, 0.93f, 1.00f)};
    public static final Color[] FILL = {c(0.22f, 0.30f, 0.45f), c(0.25f, 0.30f, 0.45f), c(0.35f, 0.22f, 0.40f), c(0.15f, 0.22f, 0.40f), c(0.20f, 0.14f, 0.35f)};
    public static final Color[] BOUNCE = {c(0.32f, 0.26f, 0.18f), c(0.22f, 0.28f, 0.34f), c(0.40f, 0.22f, 0.12f), c(0.10f, 0.20f, 0.26f), c(0.20f, 0.12f, 0.30f)};
    public static final Color[] RIM = {c(0.55f, 0.75f, 1.00f), c(1.00f, 0.80f, 0.60f), c(0.40f, 0.80f, 1.00f), c(1.00f, 0.70f, 0.40f), c(0.30f, 1.00f, 0.90f)};
    public static final float[][] SUN_DIR = {{-0.5f, -0.9f, -0.6f}, {-0.4f, -0.95f, -0.5f}, {-0.85f, -0.35f, -0.55f}, {-0.3f, -0.9f, -0.5f}, {-0.7f, -0.5f, -0.4f}};
    public static final float[] VIGNETTE = {0.22f, 0.20f, 0.30f, 0.40f, 0.45f};
    public static final String[] NAMES = {"Meadow Base", "Frost Ridge", "Dusk Spire", "Night Summit", "Deep Space"};
    public static final int ZONES = NAMES.length, SPACE = 4;

    private static Color c(float r, float g, float b) { return new Color(r, g, b, 1f); }

    /** Blends a per-world scalar array the same way as the colours. */
    public static float blendF(float[] arr, float zoneF) {
        int z = Math.min(ZONES - 1, (int) zoneF); float f = zoneF - z, t = MathUtils.clamp((f - 0.8f) / 0.2f, 0f, 1f);
        return MathUtils.lerp(arr[z], arr[(z + 1) % ZONES], t);
    }
    public static void blendDir(float[][] arr, float zoneF, com.badlogic.gdx.math.Vector3 out) {
        int z = Math.min(ZONES - 1, (int) zoneF); float f = zoneF - z, t = MathUtils.clamp((f - 0.8f) / 0.2f, 0f, 1f);
        float[] a = arr[z], b = arr[(z + 1) % ZONES];
        out.set(MathUtils.lerp(a[0], b[0], t), MathUtils.lerp(a[1], b[1], t), MathUtils.lerp(a[2], b[2], t)).nor();
    }

    /** zoneF in [0,5): integer part = zone, fraction = progress through it. Blends over the last 20% of each zone. */
    public static void blend(Color[] arr, float zoneF, Color out) {
        int z = Math.min(ZONES - 1, (int) zoneF);
        float f = zoneF - z;
        float t = MathUtils.clamp((f - 0.8f) / 0.2f, 0f, 1f);
        Color a = arr[z], b = arr[(z + 1) % ZONES];      // themes repeat for ever: after Deep Space comes Meadow Base again
        out.set(a).lerp(b, t);
    }
}
