package com.hotatticgames.climbup.ota;

/**
 * Over-the-air content updates (see docs/OTA.md). A manifest is trusted only if its ECDSA P-256 signature verifies against the public key pinned below;
 * the manifest in turn carries the SHA-256 of the payload, so the payload is authenticated too. Hosting is a public GitHub release (no server, no accounts,
 * nothing identifying sent). The scope is the tuning numbers only: no executable code and no assets.
 */
public final class OtaConfig {
    private OtaConfig() {}
    public static final String CHANNEL = "release";
    /** Release tag that holds the three OTA assets (manifest.json, manifest.sig, payload.zip). */
    public static final String BASE = "https://github.com/verbal76/Climb-Up/releases/download/ota/";
    public static final String MANIFEST_URL = BASE + "manifest.json", SIGNATURE_URL = BASE + "manifest.sig";
    /**
     * Identifies the game-layer interpreter this build ships. A payload is applied only if its runtime equals this number exactly; bump it (here and in
     * tools/ota/make_ota.py) whenever a change to the tuning schema or its meaning makes older payloads unsafe, or newer payloads unreadable by this build.
     */
    public static final int RUNTIME = 2;
    public static final int SCHEMA = 2;
    /** The content version compiled into this APK (assets/data/tuning.json). Manifests at or below it are "already current". */
    public static final int BUNDLED_CONTENT_VERSION = 1;
    /** Pinned verification key: X.509 SubjectPublicKeyInfo, base64. Must equal assets/ota/ota_public_key.b64 (OtaConfigTest). Rotating it needs a new APK. */
    public static final String PUBLIC_KEY_B64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEvZenFitRM7a1F6+qI5Itnn3e0lM++75XJKfvUh/HoaoB6rGVt+fMBNiEiHNzXKZKZlStCWMbbnJmh4sY89oBBw==";
    /** Only these files may be replaced by a payload. Nothing executable, no assets. */
    public static final String[] ALLOWED_FILES = {"tuning.json"};
    /** Layout constants of the tower: a payload may not change them (the stored climb and the tower's castles and zones depend on them); a new APK is needed. */
    public static final String[] FROZEN_TUNING = {"radius", "zoneHeight", "zoneCount", "castleSpacing", "finishCastle", "gemSpacing", "courseHeight", "rampHeight", "chunkHeight", "spiralPitch", "restEvery", "checkpointEveryRests"};
    public static final int MAX_MANIFEST_BYTES = 16 * 1024, MAX_SIGNATURE_BYTES = 1024, MAX_PAYLOAD_BYTES = 256 * 1024, MAX_FILE_BYTES = 128 * 1024;
    public static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;
    /** A freshly applied payload that has not proved itself (reached live play) in this many launches is rolled back. */
    public static final int MAX_UNCONFIRMED_LAUNCHES = 2;
    public static final int TIMEOUT_MS = 6000;
}
