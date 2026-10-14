package com.hotatticgames.climbup;

import java.util.Locale;

/**
 * The run clock as text (Build 66: days for marathon sessions). Pure, so it is unit-tested without the engine.
 * Under an hour "m:ss.t", under a day "h:mm:ss.t", from a day on "N DAYS hh:mm:ss.t" ("1 DAY 02:03:04.5"). Tenths are
 * truncated, as the HUD always has; a difference ({@link #delta}) is rounded to the nearest tenth and always carries its sign.
 * The clock itself (whole simulation steps in SaveData.runTicks) is untouched: this only prints it.
 */
public final class ClockText {
    private ClockText() { }

    public static String time(float sec) {
        long t = Math.max(0L, (long) (sec * 10f));
        long tenth = t % 10, s = (t / 10) % 60, m = (t / 600) % 60, h = (t / 36000) % 24, d = t / 864000;
        if (d > 0) return d + (d == 1 ? " DAY " : " DAYS ") + String.format(Locale.ROOT, "%02d:%02d:%02d.%d", h, m, s, tenth);
        if (h > 0) return String.format(Locale.ROOT, "%d:%02d:%02d.%d", h, m, s, tenth);
        return String.format(Locale.ROOT, "%d:%02d.%d", m, s, tenth);
    }

    /** A signed time difference to the nearest tenth: "-0:04.8" faster, "+2:03.4" slower. */
    public static String delta(float d) { float a = Math.round(Math.abs(d) * 10f) / 10f; return (d < 0f && a > 0f ? "-" : "+") + time(a); }
}
