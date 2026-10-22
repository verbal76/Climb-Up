package com.hotatticgames.climbup;

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
        // built by hand, not with String.format: the HUD prints this a few times every frame (Build 71 performance audit); the text is unchanged (ClockTextTest)
        StringBuilder b = new StringBuilder(24);
        if (d > 0) { b.append(d).append(d == 1 ? " DAY " : " DAYS "); two(b, h).append(':'); two(b, m); }
        else if (h > 0) { b.append(h).append(':'); two(b, m); }
        else b.append(m);
        b.append(':'); two(b, s).append('.').append(tenth);
        return b.toString();
    }
    private static StringBuilder two(StringBuilder b, long v) { if (v < 10) b.append('0'); return b.append(v); }

    /** A signed time difference to the nearest tenth: "-0:04.8" faster, "+2:03.4" slower. */
    public static String delta(float d) { float a = Math.round(Math.abs(d) * 10f) / 10f; return (d < 0f && a > 0f ? "-" : "+") + time(a); }
}
