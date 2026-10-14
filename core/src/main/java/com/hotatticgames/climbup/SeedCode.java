package com.hotatticgames.climbup;

/**
 * The player-facing course code: the climb's 64-bit seed written as 13 characters in base 31 (digits 2-9 and the letters without I, L and O, so nothing
 * reads as 0/O or 1/I/l), shown in three groups (XXXXX-XXXX-XXXX). 31^13 is above 2^64, so every seed the game can hold has exactly one code and
 * decoding gives back the same 64 bits (the value is treated as unsigned; a 13-character string above 2^64-1 is not a course and is rejected).
 * Entry is case-insensitive and ignores spaces and hyphens. The internal seed and the generator are untouched; this is only a way to write a seed down.
 */
public final class SeedCode {
    private SeedCode() {}
    /** The 31 symbols, in value order. */
    public static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    public static final int LENGTH = 13;
    /** Group sizes of the displayed form. */
    private static final int[] GROUPS = {5, 4, 4};
    private static final int BASE = ALPHABET.length();

    /** The canonical code of a seed: 13 symbols in three groups, e.g. "7K3F9-Q2AB-CDEF". */
    public static String encode(long seed) {
        char[] out = new char[LENGTH];
        long v = seed;
        for (int i = LENGTH - 1; i >= 0; i--) {
            int d = (int) Long.remainderUnsigned(v, BASE);
            out[i] = ALPHABET.charAt(d);
            v = Long.divideUnsigned(v, BASE);
        }
        return group(new String(out));
    }

    /** Puts the hyphens into a bare 13-symbol string. */
    public static String group(String bare) {
        StringBuilder sb = new StringBuilder(LENGTH + GROUPS.length - 1);
        int p = 0;
        for (int gi = 0; gi < GROUPS.length; gi++) {
            if (gi > 0) sb.append('-');
            sb.append(bare, p, Math.min(bare.length(), p + GROUPS[gi]));
            p += GROUPS[gi];
            if (p >= bare.length()) break;
        }
        return sb.toString();
    }

    /** The symbols of an entered code with the case, spaces and hyphens taken out (what is left may still be wrong: see {@link #decode}). */
    public static String bare(String entered) {
        if (entered == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entered.length(); i++) {
            char c = Character.toUpperCase(entered.charAt(i));
            if (c == ' ' || c == '-' || c == '_' || c == '–' || c == '—') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    /** Why an entered code cannot be a course, or null when it can (then {@link #decode} succeeds). */
    public static String problem(String entered) {
        String b = bare(entered);
        if (b.isEmpty()) return "TYPE A COURSE CODE FIRST";
        for (int i = 0; i < b.length(); i++) if (ALPHABET.indexOf(b.charAt(i)) < 0) return "A COURSE CODE NEVER HAS '" + b.charAt(i) + "'";
        if (b.length() < LENGTH) return "A COURSE CODE HAS " + LENGTH + " CHARACTERS (" + b.length() + " SO FAR)";
        if (b.length() > LENGTH) return "A COURSE CODE HAS " + LENGTH + " CHARACTERS, NOT " + b.length();
        if (!inRange(b)) return "THAT IS NOT A COURSE CODE";
        return null;
    }

    /** True when the 13 symbols stand for a value below 2^64. */
    private static boolean inRange(String b) {
        // compare against the code of the largest seed, symbol by symbol (both are 13 symbols of the same alphabet, so lexical order is value order)
        return b.compareTo(MAX_CODE) <= 0;
    }
    private static final String MAX_CODE = bare(encode(-1L));

    /** The seed behind a code, or throws for anything {@link #problem} rejects. */
    public static long decode(String entered) {
        String why = problem(entered);
        if (why != null) throw new IllegalArgumentException(why);
        String b = bare(entered);
        long v = 0;
        for (int i = 0; i < LENGTH; i++) v = v * BASE + ALPHABET.indexOf(b.charAt(i));      // wraps exactly like unsigned arithmetic; inRange() has excluded overflow
        return v;
    }

    /** The canonical display of an entered code, or null when it is not one. */
    public static String canonical(String entered) {
        return problem(entered) == null ? group(bare(entered)) : null;
    }
}
