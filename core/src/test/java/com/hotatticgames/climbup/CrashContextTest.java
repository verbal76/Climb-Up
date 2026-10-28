package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Test;

/** Build 71 audit: the crash line is built only when a crash logger asks for it, and still says something when building it fails. */
public class CrashContextTest {
    @After public void clear() { CrashContext.source(null); CrashContext.set("no gameplay yet (still in menus or loading)"); }

    @Test public void theLiveSourceIsAskedOnlyWhenTheLineIsRead() {
        int[] asked = {0};
        CrashContext.source(() -> { asked[0]++; return "screen=play mode=ROPE absY=812"; });
        assertEquals("registering it builds nothing", 0, asked[0]);
        assertEquals("screen=play mode=ROPE absY=812", CrashContext.get());
        assertEquals(1, asked[0]);
    }

    @Test public void aFailingLiveSourceFallsBackToTheStoredLine() {
        CrashContext.set("screen=play mode=AIR absY=40");
        CrashContext.source(() -> { throw new OutOfMemoryError(); });
        String line = CrashContext.get();
        assertTrue(line, line.startsWith("screen=play mode=AIR absY=40"));
        assertTrue(line, line.contains("OutOfMemoryError"));
        CrashContext.source(() -> null);
        assertEquals("a source with nothing to say leaves the stored line", "screen=play mode=AIR absY=40", CrashContext.get());
    }

    @Test public void clearingTheSourceKeepsTheLastStoredLine() {
        CrashContext.source(() -> "live");
        CrashContext.set("left the climb at absY=300");
        CrashContext.source(null);
        assertEquals("left the climb at absY=300", CrashContext.get());
    }
}
