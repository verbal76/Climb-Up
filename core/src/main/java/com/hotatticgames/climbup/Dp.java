package com.hotatticgames.climbup;

import com.badlogic.gdx.Gdx;
import com.hotatticgames.climbup.ui.Ui;

/** Touch-target sizes from the screen's real density: every tappable button is at least 48dp tall (Android's minimum), whatever the screen. */
public final class Dp {
    public static final float MIN_TOUCH = 48f;
    private Dp() { }

    /**
     * Virtual UI units per Android dp: world units per screen pixel times pixels per dp. A small phone gives about 2 (so 48dp is 96 units), a big TV or monitor well under 1.
     * 0 means unknown. {@code -Dclimb.upd=2} simulates a small phone on a desktop.
     */
    public static float unitsPerDp(Ui ui) {
        String sim = System.getProperty("climb.upd"); if (sim != null) { try { return Float.parseFloat(sim); } catch (NumberFormatException ignored) { } }
        int sh = ui.viewport.getScreenHeight(); if (sh <= 0) return 0f;
        float density = Math.max(0.5f, Math.min(4f, Gdx.graphics.getDensity()));
        return ui.viewport.getWorldHeight() / sh * density;
    }

    /** A button height: the normal size, raised to 48dp on a small screen, never above {@code max} and never above {@code fit} (what the screen has room for). */
    public static float touchHeight(float normal, float max, float fit, float unitsPerDp) {
        float upd = unitsPerDp > 0f && unitsPerDp < 10f ? unitsPerDp : 0f;
        return Math.max(40f, Math.min(fit, Math.min(max, Math.max(normal, MIN_TOUCH * upd))));
    }
}
