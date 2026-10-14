package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.Random;
import org.junit.Test;

/** Build 64: the course code is a reversible, collision-free writing of the whole 64-bit seed. */
public class SeedCodeTest {
    private static final long[] EDGES = {0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 2L, 31L, 30L, 31L * 31L, 0x7fffffffL | 1L, 2024L, 11L, 1L << 32, (1L << 32) - 1, (1L << 63) - 1, 1L << 63};

    @Test public void everyEdgeValueRoundTrips() {
        for (long v : EDGES) {
            String code = SeedCode.encode(v);
            assertEquals("13 symbols in 5-4-4 groups: " + code, 15, code.length());
            assertEquals('-', code.charAt(5)); assertEquals('-', code.charAt(10));
            assertEquals(code, v, SeedCode.decode(code));
            assertEquals(code, SeedCode.canonical(code));
        }
    }

    @Test public void randomValuesRoundTripAndNeverCollide() {
        Random r = new Random(64);
        HashSet<String> codes = new HashSet<>(); HashSet<Long> values = new HashSet<>();
        for (int i = 0; i < 20000; i++) {
            long v = i % 3 == 0 ? r.nextLong() : i % 3 == 1 ? (r.nextLong() & 0x7fffffffL) | 1L : r.nextInt(1000);     // full range, the game's own random draws, small numbers
            String code = SeedCode.encode(v);
            assertEquals(v, SeedCode.decode(code));
            codes.add(code); values.add(v);
        }
        assertEquals("distinct seeds give distinct codes and the same seed always the same code", values.size(), codes.size());
        assertTrue(values.size() > 14000);
    }

    @Test public void theAlphabetHasNoLookAlikesAndCoversSixtyFourBits() {
        String a = SeedCode.ALPHABET;
        assertEquals(31, a.length());
        for (char c : "0O1IlLo".toCharArray()) assertTrue("no " + c, a.indexOf(Character.toUpperCase(c)) < 0);
        assertEquals("every symbol once", a.length(), a.chars().distinct().count());
        assertTrue("31^13 exceeds 2^64", Math.pow(31, 13) > Math.pow(2, 64));
        assertEquals(13, SeedCode.LENGTH);
    }

    @Test public void entryIsCaseInsensitiveAndIgnoresGroupingAndSpaces() {
        long v = 0x1234_5678_9abc_def0L;
        String code = SeedCode.encode(v);
        String bare = code.replace("-", "");
        assertEquals(v, SeedCode.decode(bare));
        assertEquals(v, SeedCode.decode(bare.toLowerCase()));
        assertEquals(v, SeedCode.decode(" " + code.toLowerCase().replace("-", " ") + " "));
        assertEquals(v, SeedCode.decode(code.replace("-", "_")));
        assertEquals(code, SeedCode.canonical(bare.toLowerCase()));
    }

    @Test public void badCodesAreRejectedWithAReason() {
        assertNotNull(SeedCode.problem(""));
        assertNotNull(SeedCode.problem(null));
        assertNotNull("too short", SeedCode.problem("ABCDE-FGHJ"));
        assertNotNull("too long", SeedCode.problem("ABCDE-FGHJ-KMNP-Q"));
        assertNotNull("an O", SeedCode.problem("ABCDO-FGHJ-KMNP"));
        assertNotNull("a zero", SeedCode.problem("ABCD0-FGHJ-KMNP"));
        assertNotNull("an I", SeedCode.problem("ABCDI-FGHJ-KMNP"));
        assertNotNull("an L", SeedCode.problem("ABCDL-FGHJ-KMNP"));
        assertNotNull("above 2^64-1", SeedCode.problem("ZZZZZ-ZZZZ-ZZZZ"));
        assertNull(SeedCode.problem(SeedCode.encode(-1L)));         // exactly 2^64-1 is the largest course
        for (String bad : new String[]{"", "ABCDE-FGHJ", "ZZZZZ-ZZZZ-ZZZZ", "ABCDO-FGHJ-KMNP"}) {
            try { SeedCode.decode(bad); fail("decoded " + bad); } catch (IllegalArgumentException expected) { assertEquals(SeedCode.problem(bad), expected.getMessage()); }
            assertNull(SeedCode.canonical(bad));
        }
    }

    @Test public void theLargestCodeIsTheLargestSeedAndOneAboveItIsRejected() {
        String max = SeedCode.encode(-1L).replace("-", "");
        assertEquals(-1L, SeedCode.decode(max));
        // the next code in lexical order (last symbol bumped) stands for 2^64 and is not a course
        int last = SeedCode.ALPHABET.indexOf(max.charAt(12));
        assertTrue(last < SeedCode.ALPHABET.length() - 1);
        String above = max.substring(0, 12) + SeedCode.ALPHABET.charAt(last + 1);
        assertNotNull(SeedCode.problem(above));
    }
}
