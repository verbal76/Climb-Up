package com.hotatticgames.climbup;

/**
 * The run clock as text (Build 66: days for marathon sessions). Pure, so it is unit-tested without the engine.
 * Build 74 (owner): the standard speedrun in-game-time format with milliseconds. Under an hour "m:ss.mmm", under a day "h:mm:ss.mmm", from a day on
 * "N DAYS hh:mm:ss.mmm" ("1 DAY 02:03:04.500"). The clock is whole simulation steps (SaveData.runTicks, 60 a second), so the milliseconds are the step
 * count converted exactly and rounded to the nearest millisecond (one step = 0:00.017): the resolution is 1/60 s, the three digits only print it.
 * A difference ({@link #delta}) is converted the same way and always carries its sign. This only prints the clock; rankings compare step counts.
 */
public final class ClockText {
    private ClockText() { }

    public static String time(float sec) {
        // milliseconds from the exact step count, never from float arithmetic (past 18 hours a float holds the clock only to the nearest few steps; such a
        // marathon clock is never a ranked time). Capped far beyond any clock so the millisecond count cannot overflow.
        return text(msOf(Math.max(0L, RunRecord.ticksOf(Math.min(sec, 1e12f)))));
    }

    /** A step count in milliseconds, to the nearest (one step = 16.667 ms prints as 17). */
    static long msOf(long steps) { return (steps * 1000L + RunRecord.STEPS_PER_SECOND / 2) / RunRecord.STEPS_PER_SECOND; }

    private static String text(long ms) {
        long milli = ms % 1000, s = (ms / 1000) % 60, m = (ms / 60000) % 60, h = (ms / 3600000L) % 24, d = ms / 86400000L;
        // built by hand, not with String.format: the HUD prints this a few times every frame (Build 71 performance audit)
        StringBuilder b = new StringBuilder(28);
        if (d > 0) { b.append(d).append(d == 1 ? " DAY " : " DAYS "); two(b, h).append(':'); two(b, m); }
        else if (h > 0) { b.append(h).append(':'); two(b, m); }
        else b.append(m);
        b.append(':'); two(b, s).append('.');
        if (milli < 100) b.append('0');
        if (milli < 10) b.append('0');
        return b.append(milli).toString();
    }
    private static StringBuilder two(StringBuilder b, long v) { if (v < 10) b.append('0'); return b.append(v); }

    /** A signed time difference: "-0:04.800" faster, "+2:03.433" slower; nothing faster or slower is "+0:00.000". */
    public static String delta(float d) {
        long steps = RunRecord.ticksOf(d);
        return (steps < 0 ? "-" : "+") + text(msOf(Math.abs(steps)));
    }
}
