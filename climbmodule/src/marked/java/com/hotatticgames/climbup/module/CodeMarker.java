package com.hotatticgames.climbup.module;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Validation-only code that exists in the "code update" test release and nowhere in the shipped module. It is ordinary executable logic (a hash over a fixed string), so a release that
 * contains it is a real change to the code the device runs, and its result shows on the device that the new code, and not the old, is the one executing. It never touches the game.
 */
final class CodeMarker {
    static final String SPEC = "climb-up signed code update marker";
    private CodeMarker() {}

    static String value() {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(SPEC.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(); for (int i = 0; i < 6; i++) b.append(String.format("%02x", h[i]));
            return b.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
