package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;
import org.junit.Test;

/** Build 74: the climb bundled for the phone's hidden determinism check replays to exactly the result the check compares against (and that CI prints on Linux, Windows and Android). */
public class BundledReplayTest {
    @Test public void theBundledClimbFinishesOnTheExpectedStepWithTheExpectedHashes() throws Exception {
        byte[] in;
        try (InputStream i = new GZIPInputStream(Files.newInputStream(Paths.get("../assets/" + ReplayCheck.BUNDLED)))) { in = ReplayTool.readAll(i); }
        assertArrayEquals("the bundled file is the CI recording", Files.readAllBytes(Paths.get("../assets/" + ReplayCheck.BUNDLED)), Files.readAllBytes(Paths.get("src/test/resources/replays/seed12.bin.gz")));
        ReplayCheck.Climb c = ReplayCheck.replay(TestUtil.tuning(), ReplayCheck.BUNDLED_SEED, in, false, 150f, null);
        assertEquals(ReplayCheck.EXPECT_FINISH_STEP, c.finishStep);
        assertEquals(ReplayCheck.EXPECT_CLOCK_STEPS, c.finishClock);
        assertEquals(ReplayCheck.EXPECT_EVENTS, ReplayCheck.hex(c.events.digest()));
        assertEquals(ReplayCheck.EXPECT_STATES, ReplayCheck.hex(c.states.digest()));
        assertEquals("1:48:00.700", ClockText.time((float) (c.finishClock / (double) RunRecord.STEPS_PER_SECOND)));
    }
}
