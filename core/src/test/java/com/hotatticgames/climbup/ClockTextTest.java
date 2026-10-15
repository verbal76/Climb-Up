package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import org.junit.Test;

/** Build 66: the run clock's text, including the days a 24-hour stream can reach. The clock itself is simulation steps; this is only how it prints. */
public class ClockTextTest {
    @Test public void underAnHourAndUnderADayPrintAsBefore() {
        assertEquals("0:00.0", ClockText.time(0f));
        assertEquals("0:59.9", ClockText.time(59.9f));
        assertEquals("1:00.0", ClockText.time(60f));
        assertEquals("1:05.2", ClockText.time(65.2f));
        assertEquals("59:59.9", ClockText.time(3599.9f));
        assertEquals("1:00:00.0", ClockText.time(3600f));
        assertEquals("1:02:03.4", ClockText.time(3723.4f));
        assertEquals("23:59:59.5", ClockText.time(86399.5f));
        assertEquals("a negative clock never prints", "0:00.0", ClockText.time(-5f));
    }

    @Test public void fromADayOnTheDaysAreWrittenOut() {
        assertEquals("1 DAY 00:00:00.0", ClockText.time(86400f));
        assertEquals("1 DAY 02:03:04.5", ClockText.time(86400f + 7384.5f));
        assertEquals("2 DAYS 01:02:03.4", ClockText.time(172800f + 3723.4f));
        assertEquals("the owner's example: 4 days, 23 hours, 17 minutes, 5.1 seconds", "4 DAYS 23:17:05.1", ClockText.time(429425.1f));
        assertEquals("hours inside a day keep two digits", "3 DAYS 00:00:00.0", ClockText.time(259200f));
    }

    @Test public void aMarathonNeverOverflows() {
        assertEquals("30 DAYS 00:00:00.0", ClockText.time(30f * 86400f));
        assertEquals("400 DAYS 00:00:00.0", ClockText.time(400f * 86400f));       // beyond what an int of tenths could hold (6.8 years is the old limit; a long holds it)
        assertTrue(ClockText.time(Float.MAX_VALUE).endsWith(":00.0") || ClockText.time(Float.MAX_VALUE).contains("DAYS"));
    }

    @Test public void differencesAreRoundedAndSigned() {
        assertEquals("-0:04.8", ClockText.delta(-4.8f));
        assertEquals("+2:03.4", ClockText.delta(123.44f));
        assertEquals("rounds, never truncates: 123.36 is +2:03.4", "+2:03.4", ClockText.delta(123.36f));
        assertEquals("a difference that rounds to nothing is a plus", "+0:00.0", ClockText.delta(-0.04f));
        assertEquals("+1 DAY 01:00:00.0", ClockText.delta(90000f));
        assertEquals("-1 DAY 01:00:00.0", ClockText.delta(-90000f));
    }

    @Test public void theScreensUseTheSameText() {
        assertEquals(ClockText.time(3723.4f), PlayScreen.fmtTime(3723.4f));
        assertEquals(ClockText.delta(-4.8f), PlayScreen.fmtDelta(-4.8f));
        assertEquals("1 DAY 00:00:00.0", PlayScreen.fmtTime(86400f));
    }
}
