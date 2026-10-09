package com.hotatticgames.climbup.ota;

/**
 * FAMILY PLAYTEST OTA - TEST ONLY.
 * Integrity is a SHA-256 checksum taken from a manifest that anyone with write access to the release can change; it does NOT authenticate the publisher.
 * This configuration is not approved for untrusted users or for any store/production distribution (see docs/OTA.md).
 */
public final class OtaConfig {
    private OtaConfig() {}
    public static final String CHANNEL = "dev";
    /** Release tag that holds the two OTA assets (public GitHub release; no credentials, no server). */
    public static final String BASE = "https://github.com/verbal76/Climb-Up/releases/download/ota-dev/";
    public static final String MANIFEST_URL = BASE + "manifest.json";
    /** Bumped only when a native/runtime change makes older payloads unsafe; payloads built for another runtime are ignored. */
    public static final int RUNTIME = 1;
    public static final int SCHEMA = 1;
    /** Only these files may be replaced by a payload (balance / gameplay numbers). Nothing executable, no assets. */
    public static final String[] ALLOWED_FILES = {"tuning.json"};
    public static final int MAX_MANIFEST_BYTES = 16 * 1024, MAX_PAYLOAD_BYTES = 256 * 1024, MAX_FILE_BYTES = 128 * 1024;
    public static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;
    /** A freshly applied payload that has not proved itself (reached live play) in this many launches is rolled back. */
    public static final int MAX_UNCONFIRMED_LAUNCHES = 2;
    public static final int TIMEOUT_MS = 6000;
}
