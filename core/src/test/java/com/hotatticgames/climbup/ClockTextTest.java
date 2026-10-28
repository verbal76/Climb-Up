package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import org.junit.Test;

/** Build 66: the run clock's text, including the days a 24-hour stream can reach. Build 74: the speedrun format with milliseconds, from whole simulation steps. */
public class ClockTextTest {
    @Test public void underAnHourAndUnderADayPrintWithMilliseconds() {
        assertEquals("0:00.000", ClockText.time(0f));
        assertEquals("0:59.900", ClockText.time(59.9f));
        assertEquals("1:00.000", ClockText.time(60f));
        assertEquals("1:05.200", ClockText.time(65.2f));
        assertEquals("59:59.900", ClockText.time(3599.9f));
        assertEquals("1:00:00.000", ClockText.time(3600f));
        assertEquals("1:02:03.400", ClockText.time(3723.4f));
        assertEquals("23:59:59.500", ClockText.time(86399.5f));
        assertEquals("a negative clock never prints", "0:00.000", ClockText.time(-5f));
    }

    @Test public void oneStepIsSeventeenMillisecondsAndEveryTimeIsAWholeNumberOfSteps() {
        assertEquals("0:00.017", ClockText.time(1f / 60f));
        assertEquals("0:00.033", ClockText.time(2f / 60f));
        assertEquals("0:00.050", ClockText.time(3f / 60f));
        assertEquals("the seed 12 replay's finish clock: 472,942 steps", "2:11:22.367", ClockText.time((float) (472942 / 60.0)));
    }

    @Test public void fromADayOnTheDaysAreWrittenOut() {
        assertEquals("1 DAY 00:00:00.000", ClockText.time(86400f));
        assertEquals("1 DAY 02:03:04.500", ClockText.time(86400f + 7384.5f));
        assertEquals("2 DAYS 01:02:03.400", ClockText.time(172800f + 3723.4f));
        assertEquals("4 days, 23 hours, 17 minutes, 5.1 seconds", "4 DAYS 23:17:05.100", ClockText.time(429425.1f));
        assertEquals("hours inside a day keep two digits", "3 DAYS 00:00:00.000", ClockText.time(259200f));
    }

    @Test public void aMarathonNeverOverflows() {
        assertEquals("30 DAYS 00:00:00.000", ClockText.time(30f * 86400f));
        assertEquals("400 DAYS 00:00:00.000", ClockText.time(400f * 86400f));
        assertTrue(ClockText.time(Float.MAX_VALUE).contains("DAYS"));
        assertEquals("0:00.000", ClockText.time(Float.NaN));
    }

    @Test public void differencesAreSignedAndInMilliseconds() {
        assertEquals("-0:04.800", ClockText.delta(-4.8f));
        assertEquals("+2:03.433", ClockText.delta(123.44f));
        assertEquals("one step slower", "+0:00.017", ClockText.delta(1f / 60f));
        assertEquals("one step faster", "-0:00.017", ClockText.delta(-1f / 60f));
        assertEquals("a tie is a plus zero", "+0:00.000", ClockText.delta(0f));
        assertEquals("+1 DAY 01:00:00.000", ClockText.delta(90000f));
    }

    @Test public void theScreensUseTheSameText() {
        assertEquals(ClockText.time(3723.4f), PlayScreen.fmtTime(3723.4f));
        assertEquals(ClockText.delta(-4.8f), PlayScreen.fmtDelta(-4.8f));
        assertEquals("1 DAY 00:00:00.000", PlayScreen.fmtTime(86400f));
    }

    /** Build 74 (owner): a clock is a whole number of 1/60 s steps; its milliseconds come from that count exactly, whatever the float holds. */
    @Test public void millisecondsComeFromTheExactStepCount() {
        for (long ticks = 0; ticks < 60L * 3600 * 2; ticks += 7) {
            float clock = (float) (ticks / (double) RunRecord.STEPS_PER_SECOND);          // how SaveData keeps the run clock
            assertEquals(ticks, RunRecord.ticksOf(clock));
            long ms = (ticks * 1000 + 30) / 60;
            String want = (ms / 60000) % 60 + ":" + String.format(java.util.Locale.ROOT, "%02d.%03d", (ms / 1000) % 60, ms % 1000);
            if (ms < 3600000) assertEquals("at " + ticks + " steps", want, ClockText.time(clock));
        }
    }
}
