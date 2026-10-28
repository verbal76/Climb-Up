package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.util.Calendar;
import java.util.TimeZone;
import org.junit.Test;

/** Build 65: screenshot file names are timestamped, never reused, and the write never replaces an existing file. */
public class ScreenshotsTest {
    private static File tmp() throws Exception { return Files.createTempDirectory("shots").toFile(); }

    @Test public void theNameCarriesTheDateAndTimeAndNothingElse() {
        Calendar c = Calendar.getInstance(TimeZone.getDefault());
        c.set(2026, Calendar.OCTOBER, 14, 15, 30, 59); c.set(Calendar.MILLISECOND, 0);
        assertEquals("upwardly-20261014-153059.png", Screenshots.fileName(c.getTimeInMillis()));
        assertTrue(Screenshots.fileName(System.currentTimeMillis()).matches("upwardly-\\d{8}-\\d{6}\\.png"));
    }

    @Test public void aNameAlreadyOnDiskGetsASuffixAndNeverTheSameFileTwice() throws Exception {
        File dir = tmp();
        String name = "upwardly-20261014-153059.png";
        assertEquals(new File(dir, name), Screenshots.uniqueFile(dir, name));
        assertTrue(new File(dir, name).createNewFile());
        assertEquals(new File(dir, "upwardly-20261014-153059-2.png"), Screenshots.uniqueFile(dir, name));
        assertTrue(new File(dir, "upwardly-20261014-153059-2.png").createNewFile());
        assertEquals(new File(dir, "upwardly-20261014-153059-3.png"), Screenshots.uniqueFile(dir, name));
    }

    @Test public void writingCreatesTheFolderAndKeepsEveryEarlierFile() throws Exception {
        File dir = new File(tmp(), "deeper/Screenshots");
        byte[] a = {1, 2, 3}, b = {9, 8, 7, 6};
        File f1 = Screenshots.writeTo(dir, "upwardly-20261014-153059.png", a);
        File f2 = Screenshots.writeTo(dir, "upwardly-20261014-153059.png", b);
        assertTrue(dir.isDirectory());
        assertNotEquals(f1, f2);
        assertArrayEquals(a, Files.readAllBytes(f1.toPath()));
        assertArrayEquals(b, Files.readAllBytes(f2.toPath()));
        assertEquals(2, dir.list().length);
    }

    @Test public void thePrintedFolderIsUpperCaseWithForwardSlashesAndShortenedFromTheLeft() {
        assertEquals("C:/USERS/KEV/PICTURES/UPWARDLY/SCREENSHOTS", Screenshots.printable("C:\\Users\\kev\\Pictures\\Upwardly\\Screenshots", 70));
        String s = Screenshots.printable("/home/deck/.local/share/hotatticgames/upwardly/Screenshots", 30);
        assertEquals(30, s.length()); assertTrue(s.startsWith("...")); assertTrue(s.endsWith("/SCREENSHOTS"));
    }
}
